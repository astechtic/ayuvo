import CryptoKit
import Foundation
import Network

/// Where the pairing screen is (observed by the future UI through `PartnerManager.pairingState`).
nonisolated enum PartnerPairingState: Sendable, Equatable {
    case idle
    /// "Show code": the QR text and when it expires (10 min).
    case showingCode(qr: String, expiresMs: Int64)
    case connecting
    /// Both screens show the same 6 digits; the user confirms only if they match.
    case confirmCode(sas: String, peerName: String?, fingerprint: String?)
    /// I confirmed; waiting for the other phone's answer.
    case waitingForPartner
    /// Paired; the initial sync runs on the same connection.
    case syncing(name: String)
    case paired(ownerID: String, name: String)
    case declined
    /// `qr_parse` codes, `not_trusted` (wrong / used code), `partner_unavailable`, `local_network_denied`,
    /// `waiting_for_network`, `timeout`, `internal`.
    case failed(String)
}

/// QR pairing (docs/partner-sync.md §4): Noise_IKpsk2 with the psk from the single-use QR token, a 6-digit SAS both
/// users compare, PAIR_CONFIRM both ways, then the normal session (HELLO…) on the same connection as the initial sync.
actor PartnerPairing {
    let store: PartnerDatabase
    let identity: DeviceIdentity
    let coordinator: PartnerSyncCoordinator
    let now: @Sendable () -> Date
    private let onState: @Sendable (PartnerPairingState) -> Void

    static let confirmTimeout: TimeInterval = 180
    static var ttl: TimeInterval { Double(PartnerCatalog.shared.qrTTLms) / 1000 }

    private(set) var state: PartnerPairingState = .idle
    private var listener: PartnerListener?
    private var expiryTimer: Task<Void, Never>?
    private var psk: Data?
    private var channel: PartnerNoiseChannel?
    /// Responder: the initiator's X25519 key from the IK handshake. Initiator: the parsed QR payload.
    private var initiatorX25519: Data?
    private var qrPayload: RJ?
    private var finished = false

    init(store: PartnerDatabase, identity: DeviceIdentity, coordinator: PartnerSyncCoordinator,
         now: @escaping @Sendable () -> Date = { Date() }, onState: @escaping @Sendable (PartnerPairingState) -> Void) {
        self.store = store
        self.identity = identity
        self.coordinator = coordinator
        self.now = now
        self.onState = onState
    }

    private var nowMs: Int64 { Int64((now().timeIntervalSince1970 * 1000).rounded()) }

    private func set(_ s: PartnerPairingState) {
        state = s
        onState(s)
    }

    // MARK: Show code (responder)

    /// Opens the 10-minute pairing listener and returns the QR text with the current Wi-Fi / hotspot addresses.
    func showCode(name: String) async -> String? {
        await cancel()
        finished = false
        let addresses = PartnerAddresses.local()
        guard !addresses.isEmpty else {
            set(.failed("waiting_for_network"))
            return nil
        }
        var token = Data(count: 32)
        let ok = token.withUnsafeMutableBytes { SecRandomCopyBytes(kSecRandomDefault, 32, $0.baseAddress!) == errSecSuccess }
        guard ok else {
            set(.failed("internal"))
            return nil
        }
        psk = PartnerRef.pairingPSK(token: token)
        let port: UInt16
        do {
            let l = try PartnerListener(advertise: true)
            listener = l
            port = try await l.start(onConnection: { [weak self] conn in
                Task { await self?.accept(PartnerNWConnection(connection: conn)) }
            })
        } catch PartnerNetError.localNetworkDenied {
            set(.failed("local_network_denied"))
            return nil
        } catch {
            set(.failed("internal"))
            return nil
        }
        let exp = nowMs + Int64(PartnerCatalog.shared.qrTTLms)
        let payload: RJ = .obj([
            "protocol": .str(PartnerCatalog.shared.protocolID), "v": .int(PartnerCatalog.shared.protocolVersion),
            "device_id": .str(identity.deviceID), "name": .str(name), "x25519": .str(identity.x25519PublicB64),
            "ed25519": .str(identity.ed25519PublicB64), "token": .str(PartnerEncoding.b64urlEncode(token)), "exp_ms": .int(Int(exp)),
            "hosts": .arr(PartnerAddresses.hosts(port: port, addresses: addresses).map(RJ.str)),
        ])
        let qr = PartnerRef.qrEncode(payload)
        set(.showingCode(qr: qr, expiresMs: exp))
        let ttl = Self.ttl
        expiryTimer = Task { [weak self] in
            guard (try? await Task.sleep(nanoseconds: UInt64(ttl * 1e9))) != nil else { return }
            await self?.expire()
        }
        return qr
    }

    private func expire() async {
        guard !finished else { return }
        await closeListener()
        if channel == nil { set(.failed("expired")) }
    }

    /// Responder side of the handshake for each inbound connection while the code is shown.
    func accept(_ io: PartnerFrameIO) async {
        if let conn = io as? PartnerNWConnection { conn.startInbound() }
        guard channel == nil, !finished, let psk else {
            await io.close()
            return
        }
        do {
            let (ch, remote) = try await PartnerHandshake.ikRespond(io: io, identity: identity, psk: psk)
            guard channel == nil else {
                await ch.close()
                return
            }
            channel = ch
            initiatorX25519 = remote
            set(.confirmCode(sas: PartnerRef.sasCode(handshakeHash: ch.handshakeHash), peerName: nil, fingerprint: nil))
        } catch {
            await io.close()
        }
    }

    // MARK: Scan (initiator)

    /// Parses the scanned text, connects to the QR hosts (then Bonjour results) and runs the IK handshake.
    func scan(_ text: String) async {
        await cancel()
        finished = false
        let parsed = PartnerRef.qrParse(.str(text), nowMs: Int(nowMs), selfDeviceID: .str(identity.deviceID))
        guard parsed["ok"].bool == true else {
            set(.failed(parsed["error"].string ?? "malformed"))
            return
        }
        let payload = parsed["payload"]
        guard let x = PartnerEncoding.b64urlDecode(payload["x25519"]), let token = PartnerEncoding.b64urlDecode(payload["token"]) else {
            set(.failed("bad_key"))
            return
        }
        qrPayload = payload
        set(.connecting)
        let psk = PartnerRef.pairingPSK(token: token)
        var targets: [PartnerDialTarget] = (payload["hosts"].array ?? []).compactMap { $0.string.flatMap(PartnerAddresses.split) }
            .map { .hostPort($0.host, $0.port) }
        if let ch = await connect(targets, x: x, psk: psk) {
            didConnect(ch, payload: payload)
            return
        }
        // Some routers drop direct connections to the QR addresses: try Bonjour for a few seconds.
        targets = await browse(seconds: 6).map { .endpoint($0) }
        if let ch = await connect(targets, x: x, psk: psk) {
            didConnect(ch, payload: payload)
            return
        }
        set(.failed(PartnerAddresses.hasLocalNetwork ? "partner_unavailable" : "waiting_for_network"))
    }

    private func didConnect(_ ch: PartnerNoiseChannel, payload: RJ) {
        channel = ch
        set(.confirmCode(sas: PartnerRef.sasCode(handshakeHash: ch.handshakeHash), peerName: payload["name"].string,
                         fingerprint: payload["fingerprint"].string.map(PartnerRef.formatFingerprint)))
    }

    private func connect(_ targets: [PartnerDialTarget], x: Data, psk: Data) async -> PartnerNoiseChannel? {
        for target in targets {
            let conn: PartnerNWConnection
            switch target {
            case .endpoint(let e): conn = PartnerNWConnection(endpoint: e)
            case .hostPort(let h, let p): conn = PartnerNWConnection(host: h, port: p)
            }
            do {
                try await conn.start(timeout: 5)
                return try await PartnerHandshake.ikInitiate(io: conn, identity: identity, responderX25519: x, psk: psk)
            } catch {
                await conn.close()
            }
        }
        return nil
    }

    private func browse(seconds: TimeInterval) async -> [NWEndpoint] {
        let box = PartnerEndpointBox()
        let browser = PartnerBrowser(ownInstanceName: nil)
        browser.start(onEndpoints: { box.set($0) }, onDenied: {})
        try? await Task.sleep(nanoseconds: UInt64(seconds * 1e9))
        browser.cancel()
        return box.get()
    }

    /// For tests and the in-process path: run the initiator handshake on an existing frame IO.
    func scan(_ text: String, io: PartnerFrameIO) async {
        finished = false
        let parsed = PartnerRef.qrParse(.str(text), nowMs: Int(nowMs), selfDeviceID: .str(identity.deviceID))
        guard parsed["ok"].bool == true else {
            set(.failed(parsed["error"].string ?? "malformed"))
            return
        }
        let payload = parsed["payload"]
        guard let x = PartnerEncoding.b64urlDecode(payload["x25519"]), let token = PartnerEncoding.b64urlDecode(payload["token"]) else { return }
        qrPayload = payload
        do {
            let ch = try await PartnerHandshake.ikInitiate(io: io, identity: identity, responderX25519: x, psk: PartnerRef.pairingPSK(token: token))
            didConnect(ch, payload: payload)
        } catch {
            set(.failed("not_trusted"))
        }
    }

    /// Test hook: the QR token's psk for an in-process responder.
    func setPSK(_ psk: Data) { self.psk = psk }

    // MARK: Confirm (both sides)

    /// After the user compared the code: sends PAIR_CONFIRM {accepted, name, grants, device_id, ed25519, platform},
    /// reads the partner's, and on mutual accept stores the partner + grants and runs the initial sync.
    func confirm(accepted: Bool, grants: [String], name: String) async {
        guard let channel, case .confirmCode = state else { return }
        let message: RJ = .obj([
            "t": .str("PAIR_CONFIRM"), "accepted": .bool(accepted), "name": .str(PartnerRef.cleanName(name)),
            "grants": .arr(PartnerCatalog.shared.categories.filter(grants.contains).map(RJ.str)),
            "device_id": .str(identity.deviceID), "ed25519": .str(identity.ed25519PublicB64), "platform": .str("ios"),
        ])
        do {
            try await channel.send(message)
        } catch {
            await fail("partner_unavailable")
            return
        }
        guard accepted else {
            await finish(.declined)
            return
        }
        set(.waitingForPartner)
        let reply: RJ
        do {
            reply = try await channel.receive(timeout: Self.confirmTimeout)
        } catch PartnerChannelError.decryptFailed {
            await fail("not_trusted")
            return
        } catch PartnerChannelError.timeout {
            await fail("timeout")
            return
        } catch {
            await fail("partner_unavailable")
            return
        }
        guard PartnerRef.messageValidate(reply)["ok"].bool == true, reply["t"].string == "PAIR_CONFIRM" else {
            await fail(reply["t"].string == "ERROR" ? (reply["code"].string ?? "internal") : "malformed")
            return
        }
        guard reply["accepted"].bool == true else {
            await finish(.declined)
            return
        }
        guard let ownerID = reply["device_id"].string, let ed = reply["ed25519"].string else {
            await fail("malformed")
            return
        }
        let x25519: String
        if let qr = qrPayload {
            // Initiator: the reply must come from the phone whose QR I scanned.
            guard ownerID == qr["device_id"].string, ed == qr["ed25519"].string, let x = qr["x25519"].string else {
                await fail("not_trusted")
                return
            }
            x25519 = x
        } else if let remote = initiatorX25519 {
            x25519 = PartnerEncoding.b64urlEncode(remote)
        } else {
            await fail("internal")
            return
        }
        guard ownerID != identity.deviceID else {
            await fail("self")
            return
        }
        let peerName = PartnerRef.cleanName(reply["name"].string ?? qrPayload?["name"].string)
        let fingerprint = PartnerRef.fingerprint(x25519B64: x25519, ed25519B64: ed) ?? ""
        let stamp = nowMs
        do {
            if let existing = try await store.partner(ownerID), existing.x25519Pub != x25519 || existing.ed25519Pub != ed {
                // Re-pairing with new keys: data and cursors are not kept (§4 rules).
                try await store.resetForNewKeys(ownerID)
            }
            let peer = PartnerPeer(ownerID: ownerID, displayName: peerName, fingerprint: fingerprint, x25519Pub: x25519, ed25519Pub: ed,
                                   platform: reply["platform"].string, pairedMs: stamp, unpairedMs: nil, lastHost: nil, lastPort: nil,
                                   updatedMs: stamp)
            try await store.upsertPartner(peer)
            try await store.setMeta("device_id", identity.deviceID)
            try await store.setGrantsOut(ownerID, granted: grants, nowMs: stamp)
            try await store.updateGrantsReceived(ownerID, grantedNow: (reply["grants"].array ?? []).compactMap(\.string), nowMs: stamp)
            await closeListener()
            finished = true
            set(.syncing(name: peerName))
            self.channel = nil
            _ = await coordinator.runSession(channel: channel, peer: peer)
            set(.paired(ownerID: ownerID, name: peerName))
        } catch {
            await fail("internal")
        }
    }

    private func fail(_ code: String) async {
        await finish(.failed(code))
    }

    private func finish(_ s: PartnerPairingState) async {
        finished = true
        if let channel { await channel.close() }
        channel = nil
        await closeListener()
        set(s)
    }

    private func closeListener() async {
        expiryTimer?.cancel()
        expiryTimer = nil
        listener?.cancel()
        listener = nil
    }

    /// Leaves the pairing screen: listener, timer and any half-open connection are closed; the token dies.
    func cancel() async {
        if let channel { await channel.close() }
        channel = nil
        psk = nil
        initiatorX25519 = nil
        qrPayload = nil
        await closeListener()
        if state != .idle { set(.idle) }
    }
}

nonisolated final class PartnerEndpointBox: @unchecked Sendable {
    private let lock = NSLock()
    private var endpoints: [NWEndpoint] = []
    func set(_ e: [NWEndpoint]) { lock.withLock { endpoints = e } }
    func get() -> [NWEndpoint] { lock.withLock { endpoints } }
}
