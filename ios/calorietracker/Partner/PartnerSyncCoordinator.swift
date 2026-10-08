import Foundation
import Network

/// Automatic sync (docs/partner-sync.md §11): a sync window of at most 60 s (`discovery_window_ms`) that advertises
/// + listens + browses at once while the outbound ledger refreshes alongside (skipped when one finished < 2 min ago),
/// syncs every trusted partner it reaches, then tears everything down. No permanent service, no polling, no socket held open for presence.
///
/// Both sides connect when both browse: the device with the lexicographically smaller `device_id` dials at once and
/// the other only answers, falling back to dialing itself after a grace period (the partner may not be browsing). A
/// second simultaneous session with the same partner is refused with `busy`. When discovery finds nothing, the last
/// address that completed a session is dialled directly.
actor PartnerSyncCoordinator {
    nonisolated struct WindowResult: Sendable, Equatable {
        var synced: [String] = []
        var unavailable: [String] = []
        var failed: [String: String] = [:]
        var localNetworkDenied = false
        var noNetwork = false
        var skipped = false
        var refresh: PartnerLedgerRefresher.Result?
        /// The ledger refresh was skipped: one finished less than `refreshFreshness` ago.
        var refreshSkipped = false
    }

    let store: PartnerDatabase
    let identity: DeviceIdentity
    let local: PartnerLocalInfo
    let makeSources: @Sendable () -> (registry: PartnerSourceRegistry, readers: [PartnerSQLiteReader])
    let now: @Sendable () -> Date

    var windowDuration: TimeInterval = Double(PartnerCatalog.shared.protocolDoc["discovery_window_ms"].pyInt ?? 60_000) / 1000
    var tieBreakGrace: TimeInterval = 15
    var directDialDelay: TimeInterval = 4
    var connectTimeout: TimeInterval = 6
    /// A window skips its ledger refresh when one finished less than this long ago.
    var refreshFreshness: TimeInterval = 120
    /// Real time for the refresh freshness check (`now` is the data clock).
    let clock: @Sendable () -> Date
    /// Notified on every status change (the UI model reloads).
    var onChange: (@Sendable () -> Void)?

    // Window state
    private var running: Task<WindowResult, Never>?
    private var registry: PartnerSourceRegistry?
    private var readers: [PartnerSQLiteReader] = []
    private var listener: PartnerListener?
    private var browser: PartnerBrowser?
    private var endpoints: [NWEndpoint] = []
    private var pending: [String: PartnerPeer] = [:]
    private var synced: Set<String> = []
    private var failed: [String: String] = [:]
    private var rejected: Set<String> = []
    private var dialing: Set<String> = []
    private var graceOver = false
    private var denied = false
    private var cancelled = false
    private var completion: CheckedContinuation<Void, Never>?
    private var timers: [Task<Void, Never>] = []

    // Sessions (also used by pairing and inbound connections outside the window bookkeeping)
    private var activeSessions: [String: PartnerFramedChannel] = [:]

    // The window's ledger refresh, running alongside discovery.
    private var refreshTask: Task<PartnerLedgerRefresher.Result?, Never>?
    private(set) var lastRefreshAt: Date?
    private var staleCleared = false

    init(store: PartnerDatabase, identity: DeviceIdentity, local: PartnerLocalInfo,
         makeSources: @escaping @Sendable () -> (registry: PartnerSourceRegistry, readers: [PartnerSQLiteReader]) = { PartnerSources.live() },
         now: @escaping @Sendable () -> Date = { Date() }, clock: @escaping @Sendable () -> Date = { Date() }) {
        self.store = store
        self.identity = identity
        self.local = local
        self.makeSources = makeSources
        self.now = now
        self.clock = clock
    }

    func setOnChange(_ handler: (@Sendable () -> Void)?) { onChange = handler }

    func configure(windowDuration: TimeInterval? = nil, tieBreakGrace: TimeInterval? = nil, directDialDelay: TimeInterval? = nil,
                   refreshFreshness: TimeInterval? = nil) {
        if let refreshFreshness { self.refreshFreshness = refreshFreshness }
        if let windowDuration { self.windowDuration = windowDuration }
        if let tieBreakGrace { self.tieBreakGrace = tieBreakGrace }
        if let directDialDelay { self.directDialDelay = directDialDelay }
    }

    private var nowMs: Int64 { Int64((now().timeIntervalSince1970 * 1000).rounded()) }

    var isWindowOpen: Bool { running != nil }

    // MARK: - Window

    /// Opens one sync window (single-flight: a caller during a window gets that window's result).
    func runWindow() async -> WindowResult {
        if let running { return await running.value }
        let task = Task {
            await self.clearStaleStatus()
            return await self.window()
        }
        running = task
        let result = await task.value
        running = nil
        return result
    }

    /// Background task expiration / app leaving: stop discovery and close every session now.
    func cancel() async {
        cancelled = true
        finishWait()
        for channel in activeSessions.values { await channel.close() }
    }

    // MARK: - Stale status

    /// Once per coordinator (process), before the first window: a window killed with the app leaves `connecting` /
    /// `syncing` behind, which would show until the next window. Those become `partner_unavailable` (last_sync_ms
    /// and last_error kept); partners with a live session here are left alone.
    func clearStaleStatus() async {
        guard !staleCleared else { return }
        staleCleared = true
        if (try? await Self.settleStaleStatus(store: store, skip: Set(activeSessions.keys))) ?? 0 > 0 { onChange?() }
    }

    /// Rewrites `connecting` / `syncing` to `partner_unavailable`; returns how many rows changed.
    static func settleStaleStatus(store: PartnerDatabase, skip: Set<String> = []) async throws -> Int {
        var n = 0
        for peer in try await store.partners() where !skip.contains(peer.ownerID) {
            guard let st = try await store.syncState(peer.ownerID), st.status == "connecting" || st.status == "syncing" else { continue }
            try await store.updateSyncState(peer.ownerID) { $0.status = "partner_unavailable" }
            n += 1
        }
        return n
    }

    // MARK: - Ledger refresh

    /// Starts the ledger refresh in the background unless one is running or finished less than `refreshFreshness`
    /// ago. Returns whether a refresh is (now) in flight.
    @discardableResult
    func startLedgerRefresh(registry: PartnerSourceRegistry) -> Bool {
        if refreshTask != nil { return true }
        if let last = lastRefreshAt {
            let age = clock().timeIntervalSince(last)
            if age >= 0, age < refreshFreshness { return false }
        }
        let refresher = PartnerLedgerRefresher(store: store, registry: registry, now: now)
        refreshTask = Task {
            let result = try? await refresher.refresh()
            await self.refreshFinished(result)
            return result
        }
        return true
    }

    private func refreshFinished(_ result: PartnerLedgerRefresher.Result?) {
        refreshTask = nil
        if result != nil { lastRefreshAt = clock() }
    }

    var isRefreshingLedger: Bool { refreshTask != nil }

    /// Sessions call this before serving: returns when the in-flight refresh finished, or after `timeout` seconds.
    func waitForLedger(timeout: TimeInterval) async {
        let end = Date().addingTimeInterval(timeout)
        while refreshTask != nil, Date() < end, !Task.isCancelled {
            try? await Task.sleep(nanoseconds: 50_000_000)
        }
    }

    private func setStatus(_ ownerIDs: [String], _ status: String, error: String? = nil) async {
        for id in ownerIDs {
            _ = try? await store.updateSyncState(id) { st in
                st.status = status
                if let error { st.lastError = error }
            }
        }
        onChange?()
    }

    private func window() async -> WindowResult {
        var result = WindowResult()
        cancelled = false
        guard let trusted = try? await store.trustedPeers(), !trusted.isEmpty else {
            result.skipped = true
            return result
        }
        guard PartnerAddresses.hasLocalNetwork else {
            result.noNetwork = true
            await setStatus(trusted.map(\.ownerID), "waiting_for_network")
            return result
        }
        let sources = makeSources()
        registry = sources.registry
        readers = sources.readers
        defer { Task { [readers] in for r in readers { await r.close() } } }
        // The refresh runs alongside discovery instead of before it (a full refresh can take tens of seconds and
        // used to eat the window); sessions wait for it, bounded, before serving (PartnerSessionOptions.ledgerWait).
        result.refreshSkipped = !startLedgerRefresh(registry: sources.registry)
        let refreshing = refreshTask

        pending = Dictionary(uniqueKeysWithValues: trusted.map { ($0.ownerID, $0) })
        synced = []
        failed = [:]
        rejected = []
        dialing = []
        endpoints = []
        graceOver = false
        denied = false
        let started = nowMs
        for p in trusted { _ = try? await store.updateSyncState(p.ownerID) { $0.status = "connecting"; $0.lastAttemptMs = started } }
        onChange?()

        await openDiscovery()
        if !cancelled, !pending.isEmpty {
            let duration = windowDuration
            let grace = tieBreakGrace
            let direct = directDialDelay
            timers = [
                Task { [weak self] in
                    guard (try? await Task.sleep(nanoseconds: UInt64(direct * 1e9))) != nil else { return }
                    await self?.dialPending(includeDirect: true)
                },
                Task { [weak self] in
                    guard (try? await Task.sleep(nanoseconds: UInt64(grace * 1e9))) != nil else { return }
                    await self?.endGrace()
                },
                Task { [weak self] in
                    guard (try? await Task.sleep(nanoseconds: UInt64(duration * 1e9))) != nil else { return }
                    await self?.finishWait()
                },
            ]
            await dialPending(includeDirect: false)
            if !pending.isEmpty, !cancelled {
                await withCheckedContinuation { (c: CheckedContinuation<Void, Never>) in completion = c }
            }
        }
        for t in timers { t.cancel() }
        timers = []
        closeDiscovery()
        // Sessions already running finish (bounded by the 5-minute session cap); cancellation closes them.
        while !activeSessions.isEmpty, !cancelled {
            try? await Task.sleep(nanoseconds: 200_000_000)
        }
        // The refresh finishes before the sources' readers close (a background task keeps running for it).
        // (On expiration it is not waited for: the readers close, the refresh skips what it cannot read.)
        if let refreshing, !cancelled { result.refresh = await refreshing.value }

        let unreached = pending.keys.sorted()
        result.synced = synced.sorted()
        result.failed = failed
        result.localNetworkDenied = denied
        for id in unreached where failed[id] == nil {
            if denied {
                await setStatus([id], "local_network_denied")
            } else if rejected.contains(id) {
                await setStatus([id], "not_trusted", error: "not_trusted")
            } else {
                await setStatus([id], "partner_unavailable")
                result.unavailable.append(id)
            }
        }
        registry = nil
        onChange?()
        return result
    }

    private func openDiscovery() async {
        do {
            var port: NWEndpoint.Port = .any
            var advertise = true
            #if DEBUG
            // `-ayuvoPartnerNoAdvertise YES`: listen without Bonjour, so a test partner can only reach me by direct dial.
            advertise = !UserDefaults.standard.bool(forKey: "ayuvoPartnerNoAdvertise")
            // Cross-platform test hook: `-ayuvoPartnerListenPort P` (a fixed port the Android emulator reaches as 10.0.2.2:P).
            let fixed = UserDefaults.standard.integer(forKey: "ayuvoPartnerListenPort")
            if fixed > 0, fixed <= 65535, let p = NWEndpoint.Port(rawValue: UInt16(fixed)) { port = p }
            #endif
            let l = try PartnerListener(advertise: advertise, port: port)
            listener = l
            let bound = try await l.start(onConnection: { [weak self] conn in
                Task { await self?.handleInbound(conn) }
            }, onEvent: { [weak self] event in
                if case .denied = event { Task { await self?.markDenied() } }
            })
            #if DEBUG
            NSLog("AyuvoPartnerDebug window listening on port %d", Int(bound))
            #else
            _ = bound
            #endif
            let b = PartnerBrowser(ownInstanceName: l.instanceName)
            browser = b
            b.start(onEndpoints: { [weak self] eps in
                Task { await self?.endpointsChanged(eps) }
            }, onDenied: { [weak self] in
                Task { await self?.markDenied() }
            })
        } catch PartnerNetError.localNetworkDenied {
            markDenied()
        } catch {
            // Listening failed (port exhaustion…): browsing / direct dial may still work.
            let b = PartnerBrowser(ownInstanceName: nil)
            browser = b
            b.start(onEndpoints: { [weak self] eps in Task { await self?.endpointsChanged(eps) } },
                    onDenied: { [weak self] in Task { await self?.markDenied() } })
        }
    }

    private func closeDiscovery() {
        listener?.cancel()
        listener = nil
        browser?.cancel()
        browser = nil
    }

    private func markDenied() {
        denied = true
        finishWait()
    }

    private func finishWait() {
        let c = completion
        completion = nil
        c?.resume()
    }

    private func endGrace() async {
        graceOver = true
        await dialPending(includeDirect: true)
    }

    private func endpointsChanged(_ eps: [NWEndpoint]) async {
        endpoints = eps
        await dialPending(includeDirect: false)
    }

    /// Partners I dial now: a larger device id than mine (tie-break), or anyone once the grace period is over.
    private func eligible(_ peer: PartnerPeer) -> Bool {
        graceOver || utf8Less(identity.deviceID, peer.ownerID)
    }

    private func dialPending(includeDirect: Bool) async {
        guard !cancelled, !denied else { return }
        for peer in pending.values.sorted(by: { utf8Less($0.ownerID, $1.ownerID) })
        where eligible(peer) && !dialing.contains(peer.ownerID) && activeSessions[peer.ownerID] == nil {
            var targets: [PartnerDialTarget] = endpoints.map { .endpoint($0) }
            if includeDirect || targets.isEmpty, let host = peer.lastHost, let port = peer.lastPort, port > 0, port <= 65535 {
                targets.append(.hostPort(host, UInt16(port)))
            }
            #if DEBUG
            // Cross-platform test hook: `-ayuvoPartnerDial host:port` is dialled like a last address (direct dial).
            if let text = UserDefaults.standard.string(forKey: "ayuvoPartnerDial"), let hp = PartnerAddresses.split(text) {
                targets.append(.hostPort(hp.host, hp.port))
            }
            #endif
            guard !targets.isEmpty else { continue }
            dialing.insert(peer.ownerID)
            Task { await self.dial(peer, targets: targets) }
        }
    }

    private func dial(_ peer: PartnerPeer, targets: [PartnerDialTarget]) async {
        defer { dialing.remove(peer.ownerID) }
        for target in targets {
            guard !cancelled, pending[peer.ownerID] != nil, activeSessions[peer.ownerID] == nil else { return }
            let conn: PartnerNWConnection
            switch target {
            case .endpoint(let e): conn = PartnerNWConnection(endpoint: e)
            case .hostPort(let h, let p): conn = PartnerNWConnection(host: h, port: p)
            }
            do {
                try await conn.start(timeout: connectTimeout)
            } catch PartnerNetError.localNetworkDenied {
                markDenied()
                return
            } catch {
                continue
            }
            let channel: PartnerNoiseChannel
            do {
                channel = try await PartnerHandshake.kkInitiate(io: conn, identity: identity, peer: peer)
            } catch {
                await conn.close()
                if case .hostPort(let h, _) = target, h == peer.lastHost { rejected.insert(peer.ownerID) }
                continue
            }
            let address = conn.remoteHostPort
            let outcome = await runSession(channel: channel, peer: peer)
            if outcome?.ok == true {
                rejected.remove(peer.ownerID)
                // The last address that completed a session: a direct host:port (Bonjour endpoints resolve to one).
                if case .hostPort(let h, let p) = target {
                    try? await store.setLastAddress(peer.ownerID, host: h, port: Int(p), nowMs: nowMs)
                } else if let address {
                    try? await store.setLastAddress(peer.ownerID, host: address.host, port: address.port, nowMs: nowMs)
                }
            }
            return
        }
    }

    // MARK: - Inbound

    private func handleInbound(_ nwConnection: NWConnection) async {
        let conn = PartnerNWConnection(connection: nwConnection)
        conn.startInbound()
        await respond(io: conn)
    }

    /// KK responder over any frame IO (also used by the loopback test): unknown initiators are rejected before
    /// anything else; a partner that already has a session gets `ERROR busy`.
    func respond(io: PartnerFrameIO) async {
        guard let candidates = try? await store.trustedPeers(), !candidates.isEmpty else {
            await io.close()
            return
        }
        let channel: PartnerNoiseChannel
        let peer: PartnerPeer
        do {
            (channel, peer) = try await PartnerHandshake.kkRespond(io: io, identity: identity, candidates: candidates)
        } catch {
            await io.close()
            return
        }
        _ = await runSession(channel: channel, peer: peer)
    }

    // MARK: - Sessions

    /// Runs one session with `peer` unless one is already running (then the channel gets `ERROR busy`).
    @discardableResult
    func runSession(channel: PartnerFramedChannel, peer: PartnerPeer) async -> PartnerSessionOutcome? {
        if activeSessions[peer.ownerID] != nil {
            try? await channel.send(.obj(["t": .str("ERROR"), "code": .str("busy")]))
            await channel.close()
            return nil
        }
        activeSessions[peer.ownerID] = channel
        let sources = registry.map { ($0, [PartnerSQLiteReader]()) } ?? makeSources()
        if registry == nil {
            _ = try? await PartnerLedgerRefresher(store: store, registry: sources.0, now: now).refresh()
        }
        onChange?()
        let session = PartnerSession(store: store, registry: sources.0, local: local, peerID: peer.ownerID, channel: channel, now: now,
                                     awaitLedger: { [weak self] timeout in await self?.waitForLedger(timeout: timeout) })
        let outcome = await session.run()
        activeSessions[peer.ownerID] = nil
        for r in sources.1 { await r.close() }
        if outcome.ok {
            synced.insert(peer.ownerID)
            pending[peer.ownerID] = nil
            failed[peer.ownerID] = nil
        } else if let error = outcome.error, error != "busy" {
            failed[peer.ownerID] = error
            if error == "not_trusted" { pending[peer.ownerID] = nil }
        }
        if pending.isEmpty { finishWait() }
        onChange?()
        return outcome
    }
}

nonisolated enum PartnerDialTarget: Sendable {
    case endpoint(NWEndpoint)
    case hostPort(String, UInt16)
}
