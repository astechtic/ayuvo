import Foundation
import Network

// TCP frames over Network.framework (docs/partner-sync.md §5): 2-byte big-endian length + one Noise message.

nonisolated enum PartnerNetError: Error, Sendable, Equatable {
    case connectFailed(String)
    case localNetworkDenied
    case noNetwork
    case timeout
    case closed
    case notTrusted
    case handshakeFailed
    case busy
}

nonisolated enum PartnerNetwork {
    static let serviceType = PartnerCatalog.shared.protocolDoc["service_type"].string ?? "_ayuvo-partner._tcp"
    static let queue = DispatchQueue(label: "com.ayuvo.partner.net", qos: .userInitiated)

    /// TCP parameters for partner connections (local network only; no peer-to-peer AWDL).
    static func tcpParameters() -> NWParameters {
        let tcp = NWProtocolTCP.Options()
        tcp.noDelay = true
        tcp.connectionTimeout = 8
        let params = NWParameters(tls: nil, tcp: tcp)
        params.includePeerToPeer = false
        params.prohibitedInterfaceTypes = [.cellular]
        return params
    }

    /// `kDNSServiceErr_PolicyDenied`: the Local Network permission is off.
    static let policyDeniedCode: Int32 = -65570

    static func isPolicyDenied(_ error: NWError) -> Bool {
        if case .dns(let code) = error, code == policyDeniedCode { return true }
        return false
    }
}

/// A partner TCP connection that reads and writes whole frames. Idle timeouts cancel the connection.
nonisolated final class PartnerNWConnection: PartnerFrameIO, @unchecked Sendable {
    let connection: NWConnection
    private let lock = NSLock()
    private var readyContinuation: CheckedContinuation<Void, Error>?
    private var isReady = false
    private var failure: Error?
    private(set) var isCancelled = false

    init(connection: NWConnection) {
        self.connection = connection
    }

    convenience init(host: String, port: UInt16) {
        let endpoint = NWEndpoint.hostPort(host: NWEndpoint.Host(host), port: NWEndpoint.Port(rawValue: port) ?? .any)
        self.init(connection: NWConnection(to: endpoint, using: PartnerNetwork.tcpParameters()))
    }

    convenience init(endpoint: NWEndpoint) {
        self.init(connection: NWConnection(to: endpoint, using: PartnerNetwork.tcpParameters()))
    }

    /// Remote "host:port" once connected (for `partners.last_host/last_port`).
    var remoteHostPort: (host: String, port: Int)? {
        guard case .hostPort(let host, let port)? = connection.currentPath?.remoteEndpoint else { return nil }
        var text: String
        switch host {
        case .ipv4(let a): text = "\(a)"
        case .ipv6(let a): text = "\(a)"
        case .name(let n, _): text = n
        @unknown default: return nil
        }
        if let pct = text.firstIndex(of: "%") { text = String(text[..<pct]) }
        return (text, Int(port.rawValue))
    }

    /// Starts the connection and waits until it is ready (or fails / times out).
    func start(timeout: TimeInterval) async throws {
        connection.stateUpdateHandler = { [weak self] state in self?.handle(state) }
        connection.start(queue: PartnerNetwork.queue)
        let timer = DispatchWorkItem { [weak self] in self?.fail(PartnerNetError.timeout) }
        PartnerNetwork.queue.asyncAfter(deadline: .now() + timeout, execute: timer)
        defer { timer.cancel() }
        try await withCheckedThrowingContinuation { (c: CheckedContinuation<Void, Error>) in
            lock.lock()
            if isReady {
                lock.unlock()
                c.resume()
            } else if let failure {
                lock.unlock()
                c.resume(throwing: failure)
            } else {
                readyContinuation = c
                lock.unlock()
            }
        }
    }

    /// For inbound connections handed over by NWListener.
    func startInbound() {
        connection.stateUpdateHandler = { [weak self] state in self?.handle(state) }
        connection.start(queue: PartnerNetwork.queue)
    }

    private func handle(_ state: NWConnection.State) {
        switch state {
        case .ready:
            lock.lock()
            isReady = true
            let c = readyContinuation
            readyContinuation = nil
            lock.unlock()
            c?.resume()
        case .waiting(let error):
            fail(NWPathPolicy.isDenied(error) ? PartnerNetError.localNetworkDenied : PartnerNetError.connectFailed("\(error)"))
        case .failed(let error):
            fail(NWPathPolicy.isDenied(error) ? PartnerNetError.localNetworkDenied : PartnerNetError.connectFailed("\(error)"))
        case .cancelled:
            fail(PartnerNetError.closed)
        default:
            break
        }
    }

    private func fail(_ error: Error) {
        lock.lock()
        if failure == nil { failure = error }
        let c = readyContinuation
        readyContinuation = nil
        let ready = isReady
        lock.unlock()
        c?.resume(throwing: error)
        if !ready || (error as? PartnerNetError) == .timeout { connection.cancel() }
    }

    func sendFrame(_ frame: Data) async throws {
        guard frame.count <= PartnerFraming.maxFrame else { throw PartnerChannelError.tooLarge }
        var data = PartnerFraming.header(frame.count)
        data.append(frame)
        try await withCheckedThrowingContinuation { (c: CheckedContinuation<Void, Error>) in
            connection.send(content: data, completion: .contentProcessed { error in
                if error != nil { c.resume(throwing: PartnerChannelError.closed) } else { c.resume() }
            })
        }
    }

    private func receiveExactly(_ count: Int, timeout: TimeInterval) async throws -> Data {
        guard count > 0 else { return Data() }
        let timer = DispatchWorkItem { [weak self] in self?.timedOut() }
        PartnerNetwork.queue.asyncAfter(deadline: .now() + max(0.05, timeout), execute: timer)
        defer { timer.cancel() }
        return try await withCheckedThrowingContinuation { (c: CheckedContinuation<Data, Error>) in
            connection.receive(minimumIncompleteLength: count, maximumLength: count) { [weak self] data, _, isComplete, error in
                if let data, data.count == count {
                    c.resume(returning: data)
                } else if self?.didTimeOut == true {
                    c.resume(throwing: PartnerChannelError.timeout)
                } else if error != nil || isComplete {
                    c.resume(throwing: PartnerChannelError.closed)
                } else {
                    c.resume(throwing: PartnerChannelError.closed)
                }
            }
        }
    }

    private var timedOutFlag = false
    private var didTimeOut: Bool { lock.withLock { timedOutFlag } }

    private func timedOut() {
        lock.withLock { timedOutFlag = true }
        connection.cancel()
    }

    func receiveFrame(timeout: TimeInterval) async throws -> Data {
        let header = try await receiveExactly(2, timeout: timeout)
        let length = PartnerFraming.length(header)
        guard length > 0 else { throw PartnerChannelError.malformed }
        return try await receiveExactly(length, timeout: timeout)
    }

    func close() async {
        lock.withLock { isCancelled = true }
        connection.cancel()
    }
}

nonisolated enum NWPathPolicy {
    static func isDenied(_ error: NWError) -> Bool {
        if PartnerNetwork.isPolicyDenied(error) { return true }
        if case .posix(let code) = error, code == .EPERM { return true }
        return false
    }
}
