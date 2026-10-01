import Foundation
import HealthKit
import UIKit

/// Main-actor façade for Settings › Google Health (docs/google-health.md): connect / disconnect,
/// Sync now, the opt-in app-open sync (throttled by `auto_sync_min_interval_s`), the Apple
/// Health write-back, and the per-group status the UI shows. Rows land in the shared health
/// mirror (origin 3); this store only keeps account metadata in `googleHealth*` defaults.
@Observable
@MainActor
final class GoogleHealthStore {
    /// One consent group's state for the connected screen.
    enum GroupStatus: Equatable {
        case notGranted
        case waiting
        case syncing
        case synced(Date?)
        case partial(unsupported: Int)
        case error(String)
    }

    private let runtime: HealthDataRuntime
    private let defaults: UserDefaults
    let map: GoogleHealthMap?
    private weak var healthDataStore: HealthDataStore?

    private(set) var account: GoogleHealthAccount?
    private(set) var isSyncing = false
    private(set) var progress: GoogleHealthSyncProgress?
    private(set) var lastSyncAt: Date?
    private(set) var lastOutcome: GoogleHealthSyncOutcome?
    private(set) var lastMirror: GoogleHealthMirrorOutcome?
    private(set) var needsReconnect: Bool
    private(set) var syncStates: [String: GoogleHealthSyncStateRow] = [:]
    private(set) var mirrorCounts: [String: Int] = [:]
    private(set) var rowCount = 0
    private(set) var autoSyncEnabled: Bool
    private(set) var writeBackEnabled: Bool
    /// Groups ticked in the setup flow (a subset may end up granted).
    private(set) var enabledGroups: Set<String>
    private(set) var customClientID: String

    private var syncTask: Task<GoogleHealthSyncOutcome, Never>?
    private var backgroundTaskID: UIBackgroundTaskIdentifier = .invalid
    private var lastAttemptAt: Date?
    private var authInstance: GoogleHealthAuth?

    init(runtime: HealthDataRuntime = .shared, defaults: UserDefaults = .standard, map: GoogleHealthMap? = GoogleHealthMap.bundled) {
        self.runtime = runtime
        self.defaults = defaults
        self.map = map
        self.account = GoogleHealthAccount.load(defaults: defaults)
        self.needsReconnect = defaults.bool(forKey: GoogleHealthSettings.needsReconnectKey)
        self.autoSyncEnabled = defaults.bool(forKey: GoogleHealthSettings.autoSyncKey)
        self.writeBackEnabled = (defaults.object(forKey: GoogleHealthSettings.writeBackKey) as? Bool) ?? true
        let groups = defaults.stringArray(forKey: GoogleHealthSettings.groupsKey).map(Set.init)
        self.enabledGroups = groups ?? Set(map?.scopeGroups.map(\.id) ?? [])
        self.customClientID = defaults.string(forKey: GoogleHealthSettings.customClientIDKey) ?? ""
        let last = defaults.double(forKey: GoogleHealthSettings.lastSyncAtKey)
        self.lastSyncAt = last > 0 ? Date(timeIntervalSince1970: last) : nil
    }

    // MARK: - Derived state

    var isConnected: Bool { account != nil }
    var isAvailable: Bool { map != nil }
    var bundledClientID: String? { GoogleHealthSettings.bundledClientID }
    var hasBundledClient: Bool { bundledClientID != nil }
    var grantedScopes: Set<String> { Set(account?.grantedScopes ?? []) }
    var grantedGroups: Set<String> { map?.grantedGroups(scopes: grantedScopes) ?? [] }
    var mirroredCount: Int { mirrorCounts[GoogleHealthMirrorStatus.mirrored.rawValue] ?? 0 }
    var pendingMirrorCount: Int { mirrorCounts[GoogleHealthMirrorStatus.pending.rawValue] ?? 0 }

    /// Status of one consent group, folded from its types' `google_health_sync_state` rows.
    func status(ofGroup groupID: String) -> GroupStatus {
        guard grantedGroups.contains(groupID) else { return .notGranted }
        let types = map?.types.filter { $0.scopeGroup == groupID } ?? []
        let states = types.compactMap { syncStates[$0.ghType] }
        if isSyncing, states.contains(where: { $0.status == "syncing" }) { return .syncing }
        if let error = states.first(where: { $0.isError && !$0.isScopeMissing }) {
            return .error(error.lastError ?? error.status)
        }
        guard !states.isEmpty else { return .waiting }
        let unsupported = states.filter(\.isUnsupported).count
        let last = states.compactMap(\.lastSyncMs).max().map { Date(timeIntervalSince1970: Double($0) / 1000) }
        if unsupported > 0, unsupported == states.count { return .partial(unsupported: unsupported) }
        return .synced(last)
    }

    /// Short line for the Health Sync pane under Sync Now.
    var statusLine: String {
        if needsReconnect { return String(localized: "Reconnect needed", comment: "Google Health status line") }
        if isSyncing { return String(localized: "Syncing…") }
        if case .failed? = lastOutcome { return String(localized: "Last sync failed", comment: "Google Health status line") }
        if let lastSyncAt { return HealthUnitFormatting.relativeText(lastSyncAt) }
        return String(localized: "Never")
    }

    // MARK: - Wiring

    /// Runs Google Health after every manual Sync Now of the Apple Health mirror.
    func attach(to store: HealthDataStore) {
        healthDataStore = store
        store.afterManualSync = { [weak self] in
            guard let self else { return }
            _ = await self.runSync(trigger: .manual, refreshHub: false)
        }
    }

    private func auth() -> GoogleHealthAuth? {
        guard let map else { return nil }
        if let authInstance { return authInstance }
        let created = GoogleHealthAuth(api: map.api)
        authInstance = created
        return created
    }

    // MARK: - Connect

    /// Steps 2–3 of the setup flow: OAuth for the chosen groups, token exchange, account label.
    /// Returns the account (its scopes may be a subset of the ones asked for).
    func connect(groups: Set<String>, customClientID rawCustomID: String?) async throws -> GoogleHealthAccount {
        guard let map, let auth = auth() else { throw GoogleHealthAuthError.oauth("map missing") }
        let custom = rawCustomID?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        let mode: GoogleHealthClientMode = custom.isEmpty ? .bundled : .custom
        guard let clientID = mode == .custom ? custom : bundledClientID else { throw GoogleHealthAuthError.missingClientID }
        guard let scheme = GoogleOAuthPKCE.callbackScheme(clientID: clientID),
              let redirect = GoogleOAuthPKCE.redirectURI(clientID: clientID) else { throw GoogleHealthAuthError.invalidClientID }
        let pkce = GoogleOAuthPKCE.make()
        guard let url = GoogleOAuthPKCE.authorizationURL(
            authURL: map.api.authURL, clientID: clientID, scopes: map.requestedScopes(groups: groups), pkce: pkce, loginHint: account?.email
        ) else { throw GoogleHealthAuthError.invalidClientID }

        let callback = try await GoogleOAuthWebSession().authorize(url: url, callbackScheme: scheme)
        let code = try GoogleOAuthPKCE.code(fromCallback: callback, expectedState: pkce.state)
        let tokens = try await auth.exchange(code: code, verifier: pkce.verifier, clientID: clientID, redirectURI: redirect)
        let email = await auth.fetchEmail()

        let connected = GoogleHealthAccount(
            email: email ?? account?.email,
            grantedScopes: tokens.scopes,
            clientMode: mode,
            connectedAt: account?.connectedAt ?? Date()
        )
        connected.save(defaults: defaults)
        account = connected
        enabledGroups = groups
        defaults.set(Array(groups).sorted(), forKey: GoogleHealthSettings.groupsKey)
        self.customClientID = custom
        defaults.set(custom.isEmpty ? nil : custom, forKey: GoogleHealthSettings.customClientIDKey)
        needsReconnect = false
        defaults.removeObject(forKey: GoogleHealthSettings.needsReconnectKey)
        // Scope changes: types that were skipped for scope are tried again.
        await refreshStatus()
        return connected
    }

    /// Disconnect: revoke, clear tokens and cursors; optionally delete the local origin-3 rows.
    /// Records already written to Apple Health stay there.
    func disconnect(deleteLocalData: Bool) async {
        syncTask?.cancel()
        if let running = syncTask { _ = await running.value }
        await auth()?.revokeAndClear()
        if await runtime.openIfNeeded(), let writer = runtime.writer {
            let touched = (try? await writer.clearGoogleHealth(deleteRows: deleteLocalData)) ?? [:]
            await rebuildRollups(touched, writer: writer)
        }
        for key in GoogleHealthSettings.allKeys {
            defaults.removeObject(forKey: key)
        }
        account = nil
        needsReconnect = false
        lastSyncAt = nil
        lastOutcome = nil
        lastMirror = nil
        autoSyncEnabled = false
        writeBackEnabled = true
        enabledGroups = Set(map?.scopeGroups.map(\.id) ?? [])
        customClientID = ""
        await refreshStatus()
        await healthDataStore?.refreshSnapshots()
    }

    /// Delete All Data: local only (no network), tokens and preferences gone.
    func deleteAllData() async {
        syncTask?.cancel()
        if let running = syncTask { _ = await running.value }
        await auth()?.clearTokens()
        for key in GoogleHealthSettings.allKeys {
            defaults.removeObject(forKey: key)
        }
        account = nil
        needsReconnect = false
        lastSyncAt = nil
        lastOutcome = nil
        syncStates = [:]
        mirrorCounts = [:]
        rowCount = 0
    }

    // MARK: - Preferences

    func setAutoSync(_ enabled: Bool) {
        autoSyncEnabled = enabled
        defaults.set(enabled, forKey: GoogleHealthSettings.autoSyncKey)
    }

    func setWriteBack(_ enabled: Bool) {
        writeBackEnabled = enabled
        defaults.set(enabled, forKey: GoogleHealthSettings.writeBackKey)
        Task {
            guard await runtime.openIfNeeded(), let writer = runtime.writer else { return }
            try? await writer.setMirrorWriteBack(enabled: enabled)
            await refreshStatus()
        }
    }

    // MARK: - Sync

    /// Scene-active: only with Auto-sync on, at most every `auto_sync_min_interval_s`.
    func syncIfNeeded() {
        guard isConnected, autoSyncEnabled, !needsReconnect, syncTask == nil, let map else { return }
        let lastAttempt = defaults.double(forKey: GoogleHealthSettings.lastAttemptAtKey)
        let last = max(lastSyncAt?.timeIntervalSince1970 ?? 0, lastAttempt)
        if last > 0, Date().timeIntervalSince1970 - last < TimeInterval(map.api.autoSyncMinIntervalS) { return }
        Task { _ = await runSync(trigger: .appOpen, refreshHub: true) }
    }

    /// Settings › Google Health › Sync now (and step 4 of the setup).
    @discardableResult
    func sync() async -> GoogleHealthSyncOutcome {
        await runSync(trigger: .manual, refreshHub: true)
    }

    private func runSync(trigger: HealthSyncTrigger, refreshHub: Bool) async -> GoogleHealthSyncOutcome {
        guard let map, let account, let auth = auth() else { return .skipped(reason: "not connected") }
        if let running = syncTask {
            return await running.value
        }
        // Manual taps are throttled too (`manual_sync_min_interval_s`) so a double tap is one sync.
        if trigger == .manual, let lastAttemptAt, Date().timeIntervalSince(lastAttemptAt) < TimeInterval(map.api.manualSyncMinIntervalS) {
            return .skipped(reason: "throttled")
        }
        guard await runtime.openIfNeeded(), let writer = runtime.writer else {
            lastOutcome = .failed("database unavailable")
            return .failed("database unavailable")
        }
        lastAttemptAt = Date()
        defaults.set(Date().timeIntervalSince1970, forKey: GoogleHealthSettings.lastAttemptAtKey)
        isSyncing = true
        progress = GoogleHealthSyncProgress(typesTotal: map.types.count)
        beginBackgroundTask()

        let client = GoogleHealthClient(api: map.api, tokens: auth)
        var configuration = GoogleHealthSyncEngine.Configuration(
            initialBackfillDays: map.api.initialBackfillDays,
            overlapDays: map.api.overlapDays,
            maxConcurrentTypes: map.api.maxConcurrentTypes
        )
        configuration.writeBack = writeBackEnabled
        let engine = GoogleHealthSyncEngine(
            fetcher: client, database: writer, map: map, grantedScopes: Set(account.grantedScopes), configuration: configuration
        )
        let writeBack = writeBackEnabled && HKHealthStore.isHealthDataAvailable()
        let mirrorWriter = GoogleHealthMirrorWriter(database: writer, map: map)

        let task = Task<GoogleHealthSyncOutcome, Never> { [weak self] in
            let detached = Task.detached(priority: .utility) { () -> (GoogleHealthSyncOutcome, GoogleHealthMirrorOutcome?) in
                let outcome = await engine.sync { update in
                    Task { @MainActor [weak self] in self?.progress = update }
                }
                guard writeBack, outcome != .cancelled, outcome != .reauthRequired else { return (outcome, nil) }
                Task { @MainActor [weak self] in self?.progress?.mirroring = true }
                let mirror = await mirrorWriter.run { written in
                    Task { @MainActor [weak self] in self?.progress?.mirrored = written }
                }
                return (outcome, mirror)
            }
            let (outcome, mirror) = await withTaskCancellationHandler {
                await detached.value
            } onCancel: {
                detached.cancel()
            }
            await MainActor.run { self?.lastMirror = mirror }
            return outcome
        }
        syncTask = task
        let outcome = await task.value
        syncTask = nil
        isSyncing = false
        progress = nil
        lastOutcome = outcome
        switch outcome {
        case .synced:
            let now = Date()
            lastSyncAt = now
            defaults.set(now.timeIntervalSince1970, forKey: GoogleHealthSettings.lastSyncAtKey)
            DerivedMetricsService.shared.scheduleRefresh()
        case .reauthRequired:
            needsReconnect = true
            defaults.set(true, forKey: GoogleHealthSettings.needsReconnectKey)
        default:
            break
        }
        await refreshStatus()
        if refreshHub {
            await healthDataStore?.refreshSnapshots()
        }
        endBackgroundTask()
        return outcome
    }

    func cancelSync() {
        syncTask?.cancel()
    }

    /// Reads the per-type states, mirror counts and row count for the Settings screen.
    func refreshStatus() async {
        guard await runtime.openIfNeeded(), let reader = runtime.reader ?? runtime.writer else { return }
        let states = (try? await reader.allGoogleSyncStates()) ?? []
        syncStates = Dictionary(states.map { ($0.ghType, $0) }, uniquingKeysWith: { first, _ in first })
        mirrorCounts = (try? await reader.mirrorStatusCounts()) ?? [:]
        rowCount = (try? await reader.googleSampleCount()) ?? 0
    }

    private func rebuildRollups(_ touched: [String: Set<String>], writer: HealthDatabase) async {
        guard !touched.isEmpty else { return }
        let calendar = Calendar.current
        let bundleID = Bundle.main.bundleIdentifier ?? ""
        await Task.detached(priority: .utility) {
            for (typeID, days) in touched {
                guard let type = HealthMetricRegistry.type(id: typeID) else { continue }
                _ = try? await writer.rebuildRollups(type: type, days: days.sorted(), tz: calendar.timeZone.identifier, calendar: calendar, ownBundleID: bundleID)
            }
        }.value
    }

    // MARK: - Background task

    private func beginBackgroundTask() {
        guard backgroundTaskID == .invalid else { return }
        backgroundTaskID = UIApplication.shared.beginBackgroundTask(withName: "GoogleHealthSync") { [weak self] in
            self?.cancelSync()
            self?.endBackgroundTask()
        }
    }

    private func endBackgroundTask() {
        guard backgroundTaskID != .invalid else { return }
        UIApplication.shared.endBackgroundTask(backgroundTaskID)
        backgroundTaskID = .invalid
    }
}
