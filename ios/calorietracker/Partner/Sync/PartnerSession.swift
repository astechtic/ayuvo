import Foundation

/// Who I am in a session (HELLO / PAIR_CONFIRM fields).
nonisolated struct PartnerLocalInfo: Sendable {
    var deviceID: String
    var name: String
    var platform: String = "ios"
    var appVersion: String = PartnerLocalInfo.bundleVersion

    static var bundleVersion: String {
        (Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String) ?? "0"
    }
}

nonisolated struct PartnerSessionOptions: Sendable {
    var idleTimeout: TimeInterval = Double(PartnerCatalog.shared.protocolDoc["idle_timeout_ms"].pyInt ?? 20_000) / 1000
    var sessionTimeout: TimeInterval = Double(PartnerCatalog.shared.protocolDoc["session_timeout_ms"].pyInt ?? 300_000) / 1000
    var pageRecords = PartnerCatalog.shared.batchMax
    /// ≤ 512 KB per page and never more than one frame (65535 − 16).
    var pageBytes = min(PartnerCatalog.shared.batchBytesMax, PartnerFraming.maxJSONMessage)
    var transport = "network"
    /// Longest wait for the window's in-flight ledger refresh before serving (under the peer's 20 s idle timeout).
    var ledgerWait: TimeInterval = 15
}

nonisolated struct PartnerSessionOutcome: Sendable, Equatable {
    var ok = false
    /// Protocol error code (protocol.json `error_codes`), `incomplete` (interrupted) or nil.
    var error: String?
    var pagesReceived = 0
    var pagesSent = 0
    var recordsReceived = 0
    var recordsSent = 0
    var oversizeSkipped = 0
    var counts: [String: Int] = [:]
    /// Diagnostic description of the error that ended an incomplete session (never sent or stored).
    var detail: String?
}

/// One trusted session over an authenticated channel (docs/partner-sync.md §12): HELLO both ways, one SYNC_REQ each,
/// CHANGES pages served from `ledger_delta` with an ACK after each page, DONE when my pull ended and my pages are
/// acknowledged. Received batches go through `merge_apply` and are written with the cursor in ONE transaction before
/// the ACK, so an interrupted session resumes from the last committed page with no duplicates.
actor PartnerSession {
    let store: PartnerDatabase
    let registry: PartnerSourceRegistry
    let local: PartnerLocalInfo
    /// The peer authenticated by the Noise handshake (owner id).
    let peerID: String
    let channel: PartnerFramedChannel
    let options: PartnerSessionOptions
    let now: @Sendable () -> Date
    /// Waits (at most the given seconds) for the sync window's in-flight ledger refresh; nil outside a window.
    let awaitLedger: (@Sendable (TimeInterval) async -> Void)?

    private var outcome = PartnerSessionOutcome()
    private var deadline = Date.distantFuture

    init(store: PartnerDatabase, registry: PartnerSourceRegistry, local: PartnerLocalInfo, peerID: String,
         channel: PartnerFramedChannel, options: PartnerSessionOptions = PartnerSessionOptions(),
         now: @escaping @Sendable () -> Date = { Date() }, awaitLedger: (@Sendable (TimeInterval) async -> Void)? = nil) {
        self.store = store
        self.registry = registry
        self.local = local
        self.peerID = peerID
        self.channel = channel
        self.options = options
        self.now = now
        self.awaitLedger = awaitLedger
    }

    private var nowMs: Int64 { Int64((now().timeIntervalSince1970 * 1000).rounded()) }

    private struct Abort: Error {
        let code: String
        let sendError: Bool
    }

    private func receive() async throws -> RJ {
        let remaining = deadline.timeIntervalSinceNow
        guard remaining > 0 else { throw PartnerChannelError.timeout }
        return try await channel.receive(timeout: min(options.idleTimeout, remaining))
    }

    private func send(_ message: RJ) async throws {
        try await channel.send(message)
    }

    private func sendError(_ code: String) async {
        try? await send(.obj(["t": .str("ERROR"), "code": .str(code)]))
    }

    func hello() async throws -> RJ {
        let grants = try await store.grantsOut(peerID)
        return .obj([
            "t": .str("HELLO"), "protocol": .str(PartnerCatalog.shared.protocolID), "v": .int(PartnerCatalog.shared.protocolVersion),
            "device_id": .str(local.deviceID), "platform": .str(local.platform), "app_version": .str(local.appVersion),
            "grants": .arr(grants.map(RJ.str)), "name": .str(PartnerRef.cleanName(local.name)),
        ])
    }

    /// Runs the whole session and records status / timestamps. Never throws; the channel is closed at the end.
    func run() async -> PartnerSessionOutcome {
        // Timeouts use the real clock (`now` is the data clock: rev timestamps, merge clamping).
        deadline = Date().addingTimeInterval(options.sessionTimeout)
        let started = nowMs
        _ = try? await store.updateSyncState(peerID) { $0.lastAttemptMs = started; $0.status = "syncing" }
        do {
            try await exchange()
            outcome.ok = true
            let done = nowMs
            _ = try? await store.updateSyncState(peerID) {
                $0.status = "up_to_date"
                $0.lastSyncMs = done
                $0.lastError = nil
                $0.lastTransport = self.options.transport
            }
        } catch let abort as Abort {
            if abort.sendError { await sendError(abort.code) }
            outcome.error = abort.code
            await recordFailure(abort.code)
        } catch {
            outcome.error = "incomplete"
            outcome.detail = String(describing: error)
            await recordFailure("incomplete")
        }
        _ = try? await store.retentionPrune(nowMs: nowMs)
        await channel.close()
        return outcome
    }

    private func recordFailure(_ code: String) async {
        // `busy`: another session with this partner is running and owns the status.
        guard code != "busy" else { return }
        let status = code == "not_trusted" ? "not_trusted" : "sync_failed"
        _ = try? await store.updateSyncState(peerID) {
            $0.status = status
            $0.lastError = code
            $0.lastTransport = self.options.transport
        }
    }

    private func validated(_ msg: RJ) throws -> String {
        let v = PartnerRef.messageValidate(msg)
        if v["ok"].bool != true {
            let code = v["error"].string == "unsupported_version" ? "unsupported_version" : "malformed"
            throw Abort(code: code, sendError: true)
        }
        let t = msg["t"].string ?? ""
        if t == "ERROR" {
            let code = msg["code"].string ?? "internal"
            throw Abort(code: PartnerCatalog.shared.protocolDoc["error_codes"].array?.contains(where: { $0.string == code }) == true ? code : "internal",
                        sendError: false)
        }
        return t
    }

    private func exchange() async throws {
        try await send(try await hello())

        // 1. HELLO from the peer.
        let peerHello = try await receive()
        guard try validated(peerHello) == "HELLO" else { throw Abort(code: "malformed", sendError: true) }
        guard peerHello["device_id"].string == peerID else { throw Abort(code: "not_trusted", sendError: true) }
        let grantedNow = (peerHello["grants"].array ?? []).compactMap(\.string)
        try await store.updateGrantsReceived(peerID, grantedNow: grantedNow, nowMs: nowMs)

        // 2. My pull request.
        try await send(.obj(["t": .str("SYNC_REQ"), "cursor": .int(Int(try await store.cursor(peerID)))]))

        var pullDone = false
        var servedDone = false
        var awaitingAck = false
        var lastPage: (toRev: Int64, hasMore: Bool)?
        var serveGrants: [String] = []
        var sentDone = false
        var peerDone = false
        var ledgerWaited = false

        while !(sentDone && peerDone) {
            let msg = try await receive()
            switch try validated(msg) {
            case "SYNC_REQ":
                // The window's ledger refresh runs alongside discovery (docs §10–§11), so it may still be running.
                // HELLO and my SYNC_REQ are already out, so the wait sits here, before my first CHANGES page, and
                // lasts at most `ledgerWait` (15 s): the peer, waiting for that page or for the ACK of its own first
                // page, gives up after its 20 s idle timeout, and 15 s leaves room for the delta query and rendering.
                // (Waiting before HELLO would cost the peer the same budget and also hold back the grants update.) A
                // refresh still running then is not waited for: ledger_delta serves the ledger as it stands (a
                // refresh commits in one transaction, so it is consistent) and the next window carries the rest.
                if let awaitLedger, !ledgerWaited {
                    ledgerWaited = true
                    await awaitLedger(options.ledgerWait)
                }
                // A second SYNC_REQ only follows a cursor_gap on the peer: restart serving from its committed cursor.
                serveGrants = try await store.grantsOut(peerID)
                servedDone = false
                let page = try await sendPage(cursor: Int64(msg["cursor"].pyInt ?? 0), grants: serveGrants)
                lastPage = page
                awaitingAck = true
            case "ACK":
                guard awaitingAck, let page = lastPage else { throw Abort(code: "malformed", sendError: true) }
                awaitingAck = false
                let committed = Int64(msg["committed_rev"].pyInt ?? 0)
                try await store.updateSyncState(peerID) { $0.ackedRev = committed }
                if page.hasMore {
                    lastPage = try await sendPage(cursor: page.toRev, grants: serveGrants)
                    awaitingAck = true
                } else {
                    servedDone = true
                }
            case "CHANGES":
                guard !pullDone else { throw Abort(code: "malformed", sendError: true) }
                let batch: RJ = .obj(["from_rev": msg["from_rev"], "to_rev": msg["to_rev"], "records": msg["records"]])
                let result = try await store.mergeBatch(ownerID: peerID, batch: batch, nowMs: nowMs)
                outcome.pagesReceived += 1
                if !result.accepted {
                    if result.error == "cursor_gap" {
                        try await send(.obj(["t": .str("SYNC_REQ"), "cursor": .int(Int(try await store.cursor(peerID)))]))
                        continue
                    }
                    throw Abort(code: "malformed", sendError: true)
                }
                outcome.recordsReceived += (msg["records"].array ?? []).count
                for (k, v) in result.counts { outcome.counts[k, default: 0] += v }
                try await send(.obj(["t": .str("ACK"), "committed_rev": .int(Int(result.cursor))]))
                if msg["has_more"].bool == false { pullDone = true }
            case "DONE":
                guard pullDone else { throw Abort(code: "malformed", sendError: true) }
                peerDone = true
            default:
                throw Abort(code: "malformed", sendError: true)
            }
            if pullDone, servedDone, !sentDone {
                try await send(.obj(["t": .str("DONE")]))
                sentDone = true
            }
        }
    }

    /// One CHANGES page from `ledger_delta`, rendered from the sources (a vanished record is a tombstone) and cut so
    /// the serialized message stays within `pageBytes` (one frame). A single record too large for any frame is
    /// skipped (its rev is still covered) and counted in `oversizeSkipped`.
    private func sendPage(cursor: Int64, grants: [String]) async throws -> (toRev: Int64, hasMore: Bool) {
        let delta = try await store.ledgerDeltaPage(cursor: cursor, grants: grants, limit: options.pageRecords)
        let stamp = nowMs
        let base = PartnerJSON.canonicalData(.obj(["t": .str("CHANGES"), "from_rev": .int(Int(delta.fromRev)),
                                                  "to_rev": .int(Int(max(delta.toRev, delta.fromRev))), "has_more": .bool(false),
                                                  "records": .arr([])])).count + 24
        var size = base
        var records: [RJ] = []
        var cutAt: Int64?
        var lastCovered = delta.fromRev
        for row in delta.rows {
            let env = await registry.envelope(type: row.type, recordID: row.recordID, ledgerCategory: row.category, rev: row.rev,
                                              deleted: row.deleted, nowMs: stamp)
            let bytes = PartnerJSON.canonicalData(env).count + 1
            if size + bytes > options.pageBytes {
                if base + bytes > options.pageBytes {
                    outcome.oversizeSkipped += 1
                    lastCovered = row.rev
                    continue
                }
                cutAt = lastCovered
                break
            }
            size += bytes
            records.append(env)
            lastCovered = row.rev
        }
        let toRev = cutAt ?? delta.toRev
        let hasMore = cutAt != nil || delta.hasMore
        try await send(.obj(["t": .str("CHANGES"), "from_rev": .int(Int(delta.fromRev)), "to_rev": .int(Int(max(toRev, delta.fromRev))),
                             "has_more": .bool(hasMore), "records": .arr(records)]))
        outcome.pagesSent += 1
        outcome.recordsSent += records.count
        return (max(toRev, delta.fromRev), hasMore)
    }
}
