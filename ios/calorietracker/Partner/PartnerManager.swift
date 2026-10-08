import BackgroundTasks
import CryptoKit
import Foundation
import Observation

/// The observable Partner Health Sync model for the (later) UI: partners with their status and grants, the pairing
/// screen state, a pending package preview, and the actions (Sync Now, pair, grants, unpair / delete, export, import).
/// Nothing opens at app launch: the database and identity are created on first use (pairing), and the sync window
/// opens on scene-active / background refresh only when at least one trusted partner exists.
@MainActor
@Observable
final class PartnerManager {
    static let shared = PartnerManager()

    nonisolated struct PartnerItem: Identifiable, Sendable, Equatable {
        var peer: PartnerPeer
        var state: PartnerSyncState?
        var grantsOut: [String]
        var grantsReceived: [PartnerGrantReceived]
        var recordCount: Int
        var id: String { peer.ownerID }
        var name: String { peer.displayName }
        var status: String { peer.isTrusted ? (state?.status ?? "pairing_required") : "pairing_required" }
    }

    static let myNameKey = "partner.myDisplayName"

    private(set) var partners: [PartnerItem] = []
    private(set) var isSyncing = false
    private(set) var lastWindow: PartnerSyncCoordinator.WindowResult?
    private(set) var pairingState: PartnerPairingState = .idle
    private(set) var pendingImport: PartnerPackagePreview?
    private(set) var importError: String?
    private(set) var lastImport: PartnerImportOutcome?
    private(set) var lastError: String?
    /// The app-wide import sheet (ContentView): preview → importing → result. Nil when hidden.
    private(set) var importSheetPhase: PartnerImportSheetPhase?
    /// Sender name of the package on the sheet (kept for the result after the preview is consumed).
    private(set) var importSenderName: String?
    /// This device's formatted fingerprint; nil until the identity exists (it is never created just to show it).
    private(set) var deviceFingerprint: String?

    /// The name shown to partners (QR, HELLO, packages). Defaults to the profile name, else "Ayuvo".
    var myName: String {
        get {
            if let stored = defaults.string(forKey: Self.myNameKey), !stored.isEmpty { return stored }
            return PartnerRef.cleanName(UserProfile.load()?.name)
        }
        set { defaults.set(PartnerRef.cleanName(newValue), forKey: Self.myNameKey) }
    }

    private let defaults: UserDefaults
    private let databaseURL: URL
    private let secrets: PartnerSecretStore
    private var store: PartnerDatabase?
    private var coordinator: PartnerSyncCoordinator?
    private var pairing: PartnerPairing?
    private var identity: DeviceIdentity?
    /// Stale `connecting` / `syncing` (from a window killed with the app) settled once per process.
    @ObservationIgnored private var staleStatusSettled = false

    init(defaults: UserDefaults = .standard, databaseURL: URL = PartnerLocation.databaseURL(),
         secrets: PartnerSecretStore = KeychainPartnerSecretStore()) {
        self.defaults = defaults
        self.databaseURL = databaseURL
        self.secrets = secrets
    }

    var hasDatabase: Bool { store != nil || FileManager.default.fileExists(atPath: databaseURL.path) }
    var hasTrustedPartners: Bool { partners.contains { $0.peer.isTrusted } }

    // MARK: - Opening

    /// The database; `create == false` never creates the file (scene-active checks on installs without Partner).
    private func openStore(create: Bool) async -> PartnerDatabase? {
        if let store { return store }
        guard create || FileManager.default.fileExists(atPath: databaseURL.path) else { return nil }
        do {
            let (db, _) = try await PartnerDatabase.openQuarantiningCorruption(url: databaseURL)
            store = db
            return db
        } catch {
            lastError = String(describing: error)
            return nil
        }
    }

    /// The device identity; created (Keychain, device-only) only when `create`.
    private func loadIdentity(create: Bool) -> DeviceIdentity? {
        if let identity { return identity }
        identity = create ? (try? DeviceIdentity.loadOrCreate(store: secrets)) : DeviceIdentity.load(store: secrets)
        return identity
    }

    private func makeCoordinator(store: PartnerDatabase, identity: DeviceIdentity) async -> PartnerSyncCoordinator {
        if let coordinator { return coordinator }
        let c = PartnerSyncCoordinator(store: store, identity: identity, local: PartnerLocalInfo(deviceID: identity.deviceID, name: myName))
        await c.setOnChange { Task { @MainActor in await PartnerManager.shared.reload() } }
        coordinator = c
        return c
    }

    // MARK: - Reading

    func reload() async {
        if deviceFingerprint == nil { deviceFingerprint = loadIdentity(create: false)?.formattedFingerprint }
        guard let store = await openStore(create: false) else {
            partners = []
            return
        }
        // A window killed with the app leaves connecting/syncing behind: settled once, before the first status shows
        // (the coordinator does the same before its first window).
        if !staleStatusSettled {
            staleStatusSettled = true
            if let coordinator {
                await coordinator.clearStaleStatus()
            } else {
                _ = try? await PartnerSyncCoordinator.settleStaleStatus(store: store)
            }
        }
        var items: [PartnerItem] = []
        for peer in (try? await store.partners()) ?? [] {
            items.append(PartnerItem(
                peer: peer, state: try? await store.syncState(peer.ownerID), grantsOut: (try? await store.grantsOut(peer.ownerID)) ?? [],
                grantsReceived: (try? await store.grantsReceived(peer.ownerID)) ?? [], recordCount: (try? await store.recordCount(peer.ownerID)) ?? 0))
        }
        partners = items.sorted { ($0.state?.lastSyncMs ?? 0) > ($1.state?.lastSyncMs ?? 0) }
    }

    /// The partner database for read-only UI queries (never created here). Its methods run on the database actor.
    func database() async -> PartnerDatabase? {
        await openStore(create: false)
    }

    // MARK: - Automatic sync

    /// `scenePhase == .active`: opens a window only when the partner database exists and has a trusted partner.
    func sceneDidBecomeActive() {
        guard hasDatabase else { return }
        Task { await syncNow() }
    }

    /// Leaving the foreground: ask for a background refresh (only when someone is paired).
    func sceneDidEnterBackground() {
        guard hasDatabase else { return }
        Task {
            await reload()
            if hasTrustedPartners { PartnerBackgroundRefresh.schedule() }
        }
    }

    /// Sync Now (and the automatic triggers): one ≤ 60 s window.
    @discardableResult
    func syncNow() async -> PartnerSyncCoordinator.WindowResult? {
        guard let store = await openStore(create: false), let identity = loadIdentity(create: false) else { return nil }
        guard let trusted = try? await store.trustedPeers(), !trusted.isEmpty else {
            await reload()
            return nil
        }
        let coordinator = await makeCoordinator(store: store, identity: identity)
        isSyncing = true
        let result = await coordinator.runWindow()
        isSyncing = false
        lastWindow = result
        await reload()
        return result
    }

    /// BGAppRefreshTask: run a window in whatever time the OS grants; expiration closes everything.
    func performBackgroundRefresh(expiration register: (@escaping @Sendable () -> Void) -> Void) async -> Bool {
        if let store = await openStore(create: false), let identity = loadIdentity(create: false) {
            let coordinator = await makeCoordinator(store: store, identity: identity)
            register { Task { await coordinator.cancel() } }
        }
        let result = await syncNow()
        if hasTrustedPartners { PartnerBackgroundRefresh.schedule() }
        return result.map { !$0.synced.isEmpty || $0.unavailable.isEmpty } ?? true
    }

    // MARK: - Pairing

    private func preparePairing() async -> PartnerPairing? {
        guard let store = await openStore(create: true), let identity = loadIdentity(create: true) else {
            pairingState = .failed("internal")
            return nil
        }
        try? await store.setMeta("device_id", identity.deviceID)
        if let pairing { return pairing }
        let coordinator = await makeCoordinator(store: store, identity: identity)
        let p = PartnerPairing(store: store, identity: identity, coordinator: coordinator) { state in
            Task { @MainActor in
                PartnerManager.shared.pairingState = state
                if case .paired = state { await PartnerManager.shared.reload() }
            }
        }
        pairing = p
        return p
    }

    /// Add partner › Show code: the QR text (also published through `pairingState`).
    @discardableResult
    func showCode() async -> String? {
        guard let pairing = await preparePairing() else { return nil }
        return await pairing.showCode(name: myName)
    }

    /// Add partner › Scan code.
    func scan(_ text: String) async {
        guard let pairing = await preparePairing() else { return }
        await pairing.scan(text)
    }

    /// After comparing the 6 digits: share `grants` with the new partner (default none), tell them my `name`, and
    /// continue into the initial sync.
    func confirmPairing(accepted: Bool, grants: [String], name: String? = nil) async {
        if let name { myName = name }
        await pairing?.confirm(accepted: accepted, grants: grants, name: name ?? myName)
    }

    func cancelPairing() async {
        await pairing?.cancel()
        pairingState = .idle
    }

    // MARK: - Partner actions

    func setGrant(_ ownerID: String, category: String, granted: Bool) async {
        guard let store = await openStore(create: false) else { return }
        try? await store.grantCategory(ownerID, category: category, granted: granted, nowMs: Self.nowMs)
        await reload()
    }

    /// Unpair: trust removed, data kept.
    func unpair(_ ownerID: String) async {
        guard let store = await openStore(create: false) else { return }
        try? await store.unpair(ownerID, nowMs: Self.nowMs)
        await reload()
    }

    /// Delete partner data: rows removed, cursor reset.
    func deleteData(_ ownerID: String) async {
        guard let store = await openStore(create: false) else { return }
        try? await store.deletePartnerData(ownerID)
        await reload()
    }

    /// Remove partner: trust and data.
    func remove(_ ownerID: String) async {
        guard let store = await openStore(create: false) else { return }
        try? await store.removePartner(ownerID)
        await reload()
    }

    static var nowMs: Int64 { Int64((Date().timeIntervalSince1970 * 1000).rounded()) }

    // MARK: - Packages

    /// Writes a `.ayuvo.zip` for one partner (everything shared, or changes since their last acknowledged rev) and
    /// presents the share sheet.
    @discardableResult
    func exportPackage(for ownerID: String, sinceLastSync: Bool, present: Bool = true) async -> URL? {
        guard let store = await openStore(create: false), let identity = loadIdentity(create: false),
              let peer = try? await store.partner(ownerID), peer.isTrusted else { return nil }
        let sources = PartnerSources.live()
        defer { Task { for r in sources.readers { await r.close() } } }
        do {
            _ = try await PartnerLedgerRefresher(store: store, registry: sources.registry).refresh()
            let directory = FileManager.default.temporaryDirectory.appendingPathComponent("partner-packages", isDirectory: true)
            let exporter = PartnerPackageExporter(store: store, registry: sources.registry, identity: identity, senderName: myName)
            let output = try await exporter.export(recipient: peer, sinceLastSync: sinceLastSync, directory: directory)
            if present { ShareSheetPresenter.present(url: output.url) }
            return output.url
        } catch {
            lastError = String(describing: error)
            return nil
        }
    }

    /// An incoming file (Open in, share extension, launch hook). Returns false when it is not a partner package so
    /// the caller can hand it to the existing handlers. A partner package becomes `pendingImport` (or `importError`).
    @discardableResult
    func handleIncomingFile(_ url: URL) async -> Bool {
        let scoped = url.startAccessingSecurityScopedResource()
        defer { if scoped { url.stopAccessingSecurityScopedResource() } }
        guard await Task.detached(priority: .userInitiated, operation: { PartnerPackageImporter.isPartnerPackage(url) }).value else { return false }
        // Keep a private copy: Documents/Inbox and app-group files are cleaned up by their owners.
        let incoming = FileManager.default.temporaryDirectory.appendingPathComponent("partner-incoming", isDirectory: true)
        try? FileManager.default.createDirectory(at: incoming, withIntermediateDirectories: true)
        let copy = incoming.appendingPathComponent(UUID().uuidString.lowercased() + ".ayuvo.zip")
        do { try FileManager.default.copyItem(at: url, to: copy) } catch {
            importError = "unreadable"
            importSenderName = nil
            importSheetPhase = .result
            return true
        }
        if url.path.contains("/Documents/Inbox/") { try? FileManager.default.removeItem(at: url) }
        importError = nil
        lastImport = nil
        importSenderName = nil
        guard let store = await openStore(create: false), let identity = loadIdentity(create: false) else {
            importError = "unknown_sender"
            importSheetPhase = .result
            try? FileManager.default.removeItem(at: copy)
            return true
        }
        do {
            pendingImport = try await PartnerPackageImporter.open(url: copy, store: store, me: identity.deviceID)
        } catch let e as PartnerPackageError {
            importError = e.code
        } catch {
            importError = "malformed"
        }
        importSenderName = pendingImport?.senderName
        importSheetPhase = pendingImport == nil ? .result : .preview
        if pendingImport == nil { try? FileManager.default.removeItem(at: copy) }
        return true
    }

    /// The confirmation sheet's Import.
    func confirmImport() async {
        guard let preview = pendingImport, let store = await openStore(create: false), let identity = loadIdentity(create: false) else { return }
        pendingImport = nil
        importSheetPhase = .importing
        defer { importSheetPhase = .result }
        do {
            lastImport = try await PartnerPackageImporter.importPackage(preview, store: store, me: identity.deviceID)
        } catch let e as PartnerPackageError {
            importError = e.code
        } catch {
            importError = "malformed"
        }
        try? FileManager.default.removeItem(at: preview.url)
        await reload()
    }

    func cancelImport() {
        if let url = pendingImport?.url { try? FileManager.default.removeItem(at: url) }
        pendingImport = nil
        importError = nil
        importSheetPhase = nil
    }

    /// The file importer picked a zip that is not a partner package.
    func reportNotPartnerFile() {
        importError = "not_package"
        importSenderName = nil
        lastImport = nil
        importSheetPhase = .result
    }

    /// Closes the import sheet (Cancel on the preview, Done on the result). An import in progress keeps it open.
    func dismissImportSheet() {
        switch importSheetPhase {
        case .preview: cancelImport()
        case .importing: return
        case .result, nil:
            importSheetPhase = nil
            importError = nil
            lastImport = nil
            importSenderName = nil
        }
    }

    /// Partner packages the share extension left in the app-group inbox are claimed before Health Records drains
    /// it (other zips stay for Records).
    func claimInboxPackages(root: URL? = RecordsLocation.inboxDirectory()) async {
        guard let root, FileManager.default.fileExists(atPath: root.path) else { return }
        for entry in RecordsInbox.pendingEntries(root: root) {
            guard let payload = entry.payload, payload.pathExtension.lowercased() == "zip" else { continue }
            if await handleIncomingFile(payload) {
                try? FileManager.default.removeItem(at: entry.directory)
            }
        }
    }

    #if DEBUG
    /// UI-test hook: `-ayuvoImportPartnerFile <absolute path>` opens that package as if it had been shared to Ayuvo.
    /// `-ayuvoPartnerSeed <partner-sample.json>` first pairs the fixture's sender with this device (see below).
    func importLaunchFileIfRequested() async {
        await seedFixturePartnerIfRequested()
        await runCrossPlatformHooksIfRequested()
        guard let path = defaults.string(forKey: "ayuvoImportPartnerFile"), FileManager.default.fileExists(atPath: path) else { return }
        await handleIncomingFile(URL(fileURLWithPath: path))
        // `-ayuvoConfirmPartnerImport YES` taps Import (screenshots of the result and the imported data).
        if defaults.bool(forKey: "ayuvoConfirmPartnerImport"), pendingImport != nil { await confirmImport() }
        NSLog("AyuvoPartnerDebug import %@: error=%@ outcome=%@", path, importError ?? "none", String(describing: lastImport))
    }

    /// Cross-platform test hooks (iOS simulator ↔ Android emulator; Bonjour does not cross the emulator NAT). Each
    /// one drives the real pairing / Noise / session / package code; only addresses are supplied. Logged with NSLog
    /// under "AyuvoPartnerDebug".
    /// - `-ayuvoPartnerResetIdentity YES`: a fresh device id and keys (stored partners stay).
    /// - `-ayuvoPartnerShowCode YES`: Show code; logs `QR <text>` (hosts carry the listener port).
    /// - `-ayuvoPartnerScan <qr>` [`-ayuvoPartnerScanHost host:port`, dialled before the QR hosts]: Scan code.
    /// - `-ayuvoPartnerAutoConfirm YES`: logs `SAS <digits>` and confirms with every category granted.
    /// - `-ayuvoPartnerListenPort P` / `-ayuvoPartnerDial host:port` / `-ayuvoPartnerNoAdvertise YES`: see
    ///   PartnerSyncCoordinator.
    /// - `-ayuvoPartnerSyncNow YES`: Sync Now; logs the window result.
    /// - `-ayuvoPartnerKKProbe host:port`: a fresh unknown identity attempts Noise KK against the most recently
    ///   paired partner's key there and logs whether it was rejected.
    /// - `-ayuvoPartnerDeleteData <owner id>`: Delete partner data (rows removed, cursor back to 0).
    /// - `-ayuvoPartnerExportFor <owner id>`: writes a full package (no share sheet) and logs its path.
    /// - `-ayuvoPartnerDump YES`: logs this device, partners and sync state.
    func runCrossPlatformHooksIfRequested() async {
        let d = defaults
        if d.bool(forKey: "ayuvoPartnerResetIdentity") {
            DeviceIdentity.reset(store: secrets)
            identity = nil
            coordinator = nil
            pairing = nil
            deviceFingerprint = nil
            _ = loadIdentity(create: true)
            if let store = await openStore(create: true), let id = identity { try? await store.setMeta("device_id", id.deviceID) }
            NSLog("AyuvoPartnerDebug identity reset: %@", identity?.deviceID ?? "nil")
        }
        let autoConfirm = d.bool(forKey: "ayuvoPartnerAutoConfirm")
        if d.bool(forKey: "ayuvoPartnerShowCode") {
            let qr = await showCode()
            NSLog("AyuvoPartnerDebug QR %@ state %@", qr ?? "nil", String(describing: pairingState))
            if autoConfirm { Task { await debugAutoConfirm() } }
        }
        if var text = d.string(forKey: "ayuvoPartnerScan") {
            if let host = d.string(forKey: "ayuvoPartnerScanHost"), let me = loadIdentity(create: true) {
                let parsed = PartnerRef.qrParse(.str(text), nowMs: Int(Self.nowMs), selfDeviceID: .str(me.deviceID))
                if parsed["ok"].bool == true, case .obj(var payload) = parsed["payload"] {
                    payload["fingerprint"] = nil
                    payload["protocol"] = .str(PartnerCatalog.shared.protocolID)
                    payload["v"] = .int(PartnerCatalog.shared.protocolVersion)
                    payload["hosts"] = .arr([.str(host)] + (payload["hosts"]?.array ?? []))
                    text = PartnerRef.qrEncode(.obj(payload))
                }
            }
            if autoConfirm { Task { await debugAutoConfirm() } }
            await scan(text)
            NSLog("AyuvoPartnerDebug scan state %@", String(describing: pairingState))
        }
        if let hp = d.string(forKey: "ayuvoPartnerKKProbe").flatMap(PartnerAddresses.split),
           let store = await openStore(create: false), let target = try? await store.trustedPeers().max(by: { $0.pairedMs < $1.pairedMs }) {
            let stranger = DeviceIdentity(deviceID: UUID().uuidString.lowercased(), agreementKey: .init(), signingKey: .init())
            let conn = PartnerNWConnection(host: hp.host, port: hp.port)
            var outcome: String
            do {
                try await conn.start(timeout: 5)
                let channel = try await PartnerHandshake.kkInitiate(io: conn, identity: stranger, peer: target)
                let first = try? await channel.receive(timeout: 5)
                outcome = "ACCEPTED (unexpected), first message: \(String(describing: first))"
            } catch {
                outcome = "rejected (not_trusted): \(error)"
            }
            await conn.close()
            NSLog("AyuvoPartnerDebug kk_probe %@:%d as stranger vs %@: %@", hp.host, Int(hp.port), target.ownerID, outcome)
        }
        if let owner = d.string(forKey: "ayuvoPartnerDeleteData") {
            await deleteData(owner)
            NSLog("AyuvoPartnerDebug deleted partner data of %@", owner)
        }
        if let owner = d.string(forKey: "ayuvoPartnerExportFor") {
            let url = await exportPackage(for: owner, sinceLastSync: false, present: false)
            NSLog("AyuvoPartnerDebug exported %@ error %@", url?.path ?? "nil", lastError ?? "none")
        }
        if d.bool(forKey: "ayuvoPartnerSyncNow") {
            let result = await syncNow()
            NSLog("AyuvoPartnerDebug sync_now result %@", String(describing: result))
            await debugDump()
        }
        if d.bool(forKey: "ayuvoPartnerDump") { await debugDump() }
    }

    private func debugAutoConfirm() async {
        let deadline = Date().addingTimeInterval(600)
        while Date() < deadline {
            if case .confirmCode(let sas, let peerName, let fp) = pairingState {
                NSLog("AyuvoPartnerDebug SAS %@ peer=%@ fingerprint=%@", sas, peerName ?? "nil", fp ?? "nil")
                await confirmPairing(accepted: true, grants: PartnerCatalog.shared.categories)
                NSLog("AyuvoPartnerDebug pairing finished %@", String(describing: pairingState))
                await debugDump()
                return
            }
            if case .failed(let code) = pairingState {
                NSLog("AyuvoPartnerDebug pairing failed %@", code)
                return
            }
            try? await Task.sleep(nanoseconds: 200_000_000)
        }
    }

    private func debugDump() async {
        await reload()
        NSLog("AyuvoPartnerDebug me %@ fingerprint %@", loadIdentity(create: false)?.deviceID ?? "nil", deviceFingerprint ?? "nil")
        for p in partners {
            NSLog("AyuvoPartnerDebug partner %@ '%@' %@ fp=%@ last=%@:%d out=%@ state=%@ records=%d", p.peer.ownerID, p.name, p.peer.platform ?? "nil",
                  PartnerRef.formatFingerprint(p.peer.fingerprint), p.peer.lastHost ?? "nil", p.peer.lastPort ?? 0, p.grantsOut.joined(separator: ","),
                  String(describing: p.state), p.recordCount)
        }
    }

    /// UI-test / screenshot hook: `-ayuvoPartnerSeed <absolute path to shared/partner/fixtures/partner-sample.json>`
    /// makes this install the fixture's recipient (`recipient_device_id`, fresh keys) and stores the fixture sender
    /// (`sender_device_id`, its keys and fingerprint) as a trusted partner named `-ayuvoPartnerSeedName` (default
    /// "Ananya"), sharing nothing back. Existing trust rows are left alone. Compiled out of release builds.
    func seedFixturePartnerIfRequested() async {
        guard let path = defaults.string(forKey: "ayuvoPartnerSeed"), let data = FileManager.default.contents(atPath: path),
              let meta = PartnerJSON.parse(data), let me = meta["recipient_device_id"].string, let sender = meta["sender_device_id"].string,
              let x = meta["sender_x25519"].string, let ed = meta["sender_ed25519"].string else { return }
        if loadIdentity(create: false)?.deviceID != me {
            DeviceIdentity.reset(store: secrets)
            let fresh = DeviceIdentity(deviceID: me, agreementKey: .init(), signingKey: .init())
            secrets.save(DeviceIdentity.x25519Key, fresh.agreementKey.rawRepresentation)
            secrets.save(DeviceIdentity.ed25519Key, fresh.signingKey.rawRepresentation)
            secrets.save(DeviceIdentity.deviceIDKey, Data(me.utf8))
            identity = nil
            coordinator = nil
            pairing = nil
        }
        guard let store = await openStore(create: true), let identity = loadIdentity(create: false) else { return }
        try? await store.setMeta("device_id", identity.deviceID)
        if (try? await store.partner(sender)) == nil {
            let stamp = Self.nowMs
            let peer = PartnerPeer(ownerID: sender, displayName: PartnerRef.cleanName(defaults.string(forKey: "ayuvoPartnerSeedName") ?? "Ananya"),
                                   fingerprint: meta["sender_fingerprint"].string ?? "", x25519Pub: x, ed25519Pub: ed, platform: "android",
                                   pairedMs: stamp, unpairedMs: nil, lastHost: nil, lastPort: nil, updatedMs: stamp)
            try? await store.upsertPartner(peer)
            try? await store.setGrantsOut(sender, granted: [], nowMs: stamp)
        }
        deviceFingerprint = identity.formattedFingerprint
        await reload()
    }
    #endif
}

/// Where the app-wide partner import sheet is.
nonisolated enum PartnerImportSheetPhase: Sendable, Equatable {
    case preview, importing, result
}

/// `BGAppRefreshTask` for partner sync (registered in `AppDelegate` like the Medications / Insights refreshes).
enum PartnerBackgroundRefresh {
    static let taskIdentifier = "com.ayuvo.health.partner.sync"
    static let minimumInterval: TimeInterval = 60 * 60

    /// Must run before the app finishes launching.
    static func register() {
        BGTaskScheduler.shared.register(forTaskWithIdentifier: taskIdentifier, using: nil) { task in
            guard let refresh = task as? BGAppRefreshTask else {
                task.setTaskCompleted(success: false)
                return
            }
            let box = PartnerTaskBox(task: refresh)
            Task { @MainActor in
                let ok = await PartnerManager.shared.performBackgroundRefresh { cancel in
                    box.task.expirationHandler = { cancel() }
                }
                box.task.setTaskCompleted(success: ok)
            }
        }
    }

    static func schedule() {
        let request = BGAppRefreshTaskRequest(identifier: taskIdentifier)
        request.earliestBeginDate = Date(timeIntervalSinceNow: minimumInterval)
        try? BGTaskScheduler.shared.submit(request)
    }
}

private final class PartnerTaskBox: @unchecked Sendable {
    let task: BGAppRefreshTask
    init(task: BGAppRefreshTask) { self.task = task }
}
