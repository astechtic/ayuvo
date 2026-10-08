import Darwin
import Foundation
import Network

// Discovery (docs/partner-sync.md §11): `_ayuvo-partner._tcp` with a random instance name and TXT `v=1` only — no
// names or ids are advertised. Listener and browser exist only while a sync window or a pairing screen is open.

/// A TCP listener on an ephemeral port, optionally advertised over Bonjour.
nonisolated final class PartnerListener: @unchecked Sendable {
    enum Event: Sendable {
        case ready(port: UInt16)
        case denied
        case failed(String)
    }

    let listener: NWListener
    let instanceName: String
    private let lock = NSLock()
    private var readyContinuation: CheckedContinuation<UInt16, Error>?
    private var result: Result<UInt16, Error>?

    init(advertise: Bool, port: NWEndpoint.Port = .any) throws {
        instanceName = String(UUID().uuidString.lowercased().replacingOccurrences(of: "-", with: "").prefix(16))
        listener = try NWListener(using: PartnerNetwork.tcpParameters(), on: port)
        if advertise {
            var txt = NWTXTRecord()
            txt["v"] = PartnerCatalog.shared.protocolDoc["txt"]["v"].string ?? "1"
            listener.service = NWListener.Service(name: instanceName, type: PartnerNetwork.serviceType, domain: nil, txtRecord: txt)
        }
    }

    /// Starts listening and waits for the bound port. `onConnection` receives every inbound connection.
    func start(onConnection: @escaping @Sendable (NWConnection) -> Void, onEvent: (@Sendable (Event) -> Void)? = nil) async throws -> UInt16 {
        listener.newConnectionHandler = onConnection
        listener.stateUpdateHandler = { [weak self] state in
            guard let self else { return }
            switch state {
            case .ready:
                let port = self.listener.port?.rawValue ?? 0
                onEvent?(.ready(port: port))
                self.finish(.success(port))
            case .waiting(let error), .failed(let error):
                let denied = NWPathPolicy.isDenied(error)
                onEvent?(denied ? .denied : .failed("\(error)"))
                self.finish(.failure(denied ? PartnerNetError.localNetworkDenied : PartnerNetError.connectFailed("\(error)")))
            case .cancelled:
                self.finish(.failure(PartnerNetError.closed))
            default:
                break
            }
        }
        listener.start(queue: PartnerNetwork.queue)
        return try await withCheckedThrowingContinuation { (c: CheckedContinuation<UInt16, Error>) in
            lock.lock()
            if let result {
                lock.unlock()
                c.resume(with: result)
            } else {
                readyContinuation = c
                lock.unlock()
            }
        }
    }

    private func finish(_ r: Result<UInt16, Error>) {
        lock.lock()
        if result == nil { result = r }
        let c = readyContinuation
        readyContinuation = nil
        lock.unlock()
        c?.resume(with: r)
    }

    func cancel() {
        listener.newConnectionHandler = nil
        listener.cancel()
    }
}

/// Bonjour browser for partner services. Reports endpoints (minus my own instance) and local-network denial.
nonisolated final class PartnerBrowser: @unchecked Sendable {
    let browser: NWBrowser
    private let ownName: String?

    init(ownInstanceName: String?) {
        ownName = ownInstanceName
        browser = NWBrowser(for: .bonjour(type: PartnerNetwork.serviceType, domain: nil), using: PartnerNetwork.tcpParameters())
    }

    func start(onEndpoints: @escaping @Sendable ([NWEndpoint]) -> Void, onDenied: @escaping @Sendable () -> Void) {
        let own = ownName
        browser.browseResultsChangedHandler = { results, _ in
            let endpoints = results.compactMap { result -> NWEndpoint? in
                if case .service(let name, _, _, _) = result.endpoint, name == own { return nil }
                return result.endpoint
            }
            onEndpoints(endpoints)
        }
        browser.stateUpdateHandler = { state in
            switch state {
            case .waiting(let error), .failed(let error):
                if NWPathPolicy.isDenied(error) { onDenied() }
            default:
                break
            }
        }
        browser.start(queue: PartnerNetwork.queue)
    }

    func cancel() {
        browser.browseResultsChangedHandler = nil
        browser.cancel()
    }
}

nonisolated enum PartnerAddresses {
    /// Current Wi-Fi / Ethernet / hotspot addresses (IPv4 first, then routable IPv6; link-local IPv6 is skipped
    /// because its scope id is meaningless on the other phone). Cellular, VPN and loopback interfaces are excluded.
    static func local() -> [String] {
        var v4: [String] = []
        var v6: [String] = []
        var ifaddr: UnsafeMutablePointer<ifaddrs>?
        guard getifaddrs(&ifaddr) == 0, let first = ifaddr else { return [] }
        defer { freeifaddrs(ifaddr) }
        var cursor: UnsafeMutablePointer<ifaddrs>? = first
        while let p = cursor {
            defer { cursor = p.pointee.ifa_next }
            let flags = Int32(p.pointee.ifa_flags)
            guard (flags & IFF_UP) != 0, (flags & IFF_LOOPBACK) == 0, let addr = p.pointee.ifa_addr else { continue }
            let name = String(cString: p.pointee.ifa_name)
            guard name.hasPrefix("en") || name.hasPrefix("bridge") else { continue }
            let family = Int32(addr.pointee.sa_family)
            guard family == AF_INET || family == AF_INET6 else { continue }
            var host = [CChar](repeating: 0, count: Int(NI_MAXHOST))
            let len = socklen_t(family == AF_INET ? MemoryLayout<sockaddr_in>.size : MemoryLayout<sockaddr_in6>.size)
            guard getnameinfo(addr, len, &host, socklen_t(host.count), nil, 0, NI_NUMERICHOST) == 0 else { continue }
            var text = String(cString: host)
            if let pct = text.firstIndex(of: "%") { text = String(text[..<pct]) }
            if family == AF_INET {
                if !text.hasPrefix("169.254.") { v4.append(text) }
            } else if !text.lowercased().hasPrefix("fe80") {
                v6.append(text)
            }
        }
        var seen = Set<String>()
        return (v4 + v6).filter { seen.insert($0).inserted }
    }

    /// QR `hosts` entries (`ip:port`, at most 8).
    static func hosts(port: UInt16, addresses: [String] = local()) -> [String] {
        Array(addresses.prefix(8)).map { "\($0):\(port)" }
    }

    /// Splits `ip:port` at the last colon.
    static func split(_ hostPort: String) -> (host: String, port: UInt16)? {
        guard let i = hostPort.lastIndex(of: ":"), let port = UInt16(hostPort[hostPort.index(after: i)...]), port > 0 else { return nil }
        let host = String(hostPort[..<i])
        return host.isEmpty ? nil : (host, port)
    }

    static var hasLocalNetwork: Bool { !local().isEmpty }
}
