import Foundation

// Transport-agnostic message channel for one partner session (docs/partner-sync.md §5, §12). The session engine only
// sees JSON messages; the network implementation frames Noise transport messages (2-byte big-endian length), and the
// in-memory pair is used by tests.

nonisolated enum PartnerChannelError: Error, Sendable, Equatable {
    case closed
    case timeout
    case tooLarge
    case malformed
    case decryptFailed
}

/// One JSON protocol message per call.
nonisolated protocol PartnerFramedChannel: AnyObject, Sendable {
    func send(_ message: RJ) async throws
    /// The next inbound message; throws `.timeout` after `timeout` seconds without one, `.closed` when the peer is gone.
    func receive(timeout: TimeInterval) async throws -> RJ
    func close() async
}

/// Raw frames (one Noise message each) — implemented over `NWConnection` and by the in-memory pipe.
nonisolated protocol PartnerFrameIO: AnyObject, Sendable {
    func sendFrame(_ frame: Data) async throws
    func receiveFrame(timeout: TimeInterval) async throws -> Data
    func close() async
}

/// Largest JSON message that fits one frame: 65535 − the 16-byte AEAD tag (§5).
nonisolated enum PartnerFraming {
    static let maxFrame = 65535
    static var maxJSONMessage: Int { maxFrame - Noise.tagLen }

    static func header(_ length: Int) -> Data { Data([UInt8((length >> 8) & 0xFF), UInt8(length & 0xFF)]) }
    static func length(_ header: Data) -> Int {
        let b = [UInt8](header)
        return b.count == 2 ? Int(b[0]) << 8 | Int(b[1]) : 0
    }
}

// MARK: - Mailbox (shared by the in-memory channel and frame pipe)

actor PartnerMailbox<Item: Sendable> {
    private var items: [Item] = []
    private var waiter: (id: UInt64, continuation: CheckedContinuation<Item, Error>, timer: Task<Void, Never>?)?
    private var nextID: UInt64 = 0
    private(set) var isClosed = false

    /// Like a TCP send buffer: a write after the reader went away is silently lost (the writer finds out on its
    /// next read), while items queued before a close can still be drained.
    func put(_ item: Item) throws {
        guard !isClosed else { return }
        if let w = waiter {
            waiter = nil
            w.timer?.cancel()
            w.continuation.resume(returning: item)
        } else {
            items.append(item)
        }
    }

    func take(timeout: TimeInterval) async throws -> Item {
        if !items.isEmpty { return items.removeFirst() }
        if isClosed { throw PartnerChannelError.closed }
        nextID += 1
        let id = nextID
        return try await withCheckedThrowingContinuation { (c: CheckedContinuation<Item, Error>) in
            let timer = Task { [weak self] in
                guard (try? await Task.sleep(nanoseconds: UInt64(max(0, timeout) * 1_000_000_000))) != nil else { return }
                await self?.expire(id)
            }
            waiter = (id, c, timer)
        }
    }

    private func expire(_ id: UInt64) {
        guard let w = waiter, w.id == id else { return }
        waiter = nil
        w.continuation.resume(throwing: PartnerChannelError.timeout)
    }

    func close() {
        isClosed = true
        if let w = waiter {
            waiter = nil
            w.timer?.cancel()
            w.continuation.resume(throwing: PartnerChannelError.closed)
        }
    }
}

/// In-memory, already-authenticated message channel pair (tests). Messages round-trip through canonical JSON so
/// they behave exactly like wire messages.
nonisolated final class PartnerMemoryChannel: PartnerFramedChannel, @unchecked Sendable {
    private let inbox: PartnerMailbox<Data>
    private let outbox: PartnerMailbox<Data>
    /// Test hook: called with every outbound message before it is delivered; return false to drop the channel.
    var onSend: (@Sendable (RJ) -> Bool)?

    private init(inbox: PartnerMailbox<Data>, outbox: PartnerMailbox<Data>) {
        self.inbox = inbox
        self.outbox = outbox
    }

    static func pair() -> (PartnerMemoryChannel, PartnerMemoryChannel) {
        let a = PartnerMailbox<Data>(), b = PartnerMailbox<Data>()
        return (PartnerMemoryChannel(inbox: a, outbox: b), PartnerMemoryChannel(inbox: b, outbox: a))
    }

    func send(_ message: RJ) async throws {
        let data = PartnerJSON.canonicalData(message)
        guard data.count <= PartnerFraming.maxJSONMessage else { throw PartnerChannelError.tooLarge }
        if let onSend, !onSend(message) {
            await close()
            throw PartnerChannelError.closed
        }
        try await outbox.put(data)
    }

    func receive(timeout: TimeInterval) async throws -> RJ {
        let data = try await inbox.take(timeout: timeout)
        guard let msg = PartnerJSON.parse(data) else { throw PartnerChannelError.malformed }
        return msg
    }

    func close() async {
        await inbox.close()
        await outbox.close()
    }
}

/// In-memory raw frame pipe (handshake tests without sockets).
nonisolated final class PartnerMemoryFrameIO: PartnerFrameIO, @unchecked Sendable {
    private let inbox: PartnerMailbox<Data>
    private let outbox: PartnerMailbox<Data>

    private init(inbox: PartnerMailbox<Data>, outbox: PartnerMailbox<Data>) {
        self.inbox = inbox
        self.outbox = outbox
    }

    static func pair() -> (PartnerMemoryFrameIO, PartnerMemoryFrameIO) {
        let a = PartnerMailbox<Data>(), b = PartnerMailbox<Data>()
        return (PartnerMemoryFrameIO(inbox: a, outbox: b), PartnerMemoryFrameIO(inbox: b, outbox: a))
    }

    func sendFrame(_ frame: Data) async throws {
        guard frame.count <= PartnerFraming.maxFrame else { throw PartnerChannelError.tooLarge }
        try await outbox.put(frame)
    }

    func receiveFrame(timeout: TimeInterval) async throws -> Data { try await inbox.take(timeout: timeout) }

    func close() async {
        await inbox.close()
        await outbox.close()
    }
}

// MARK: - Noise transport channel

/// One UTF-8 JSON message per encrypted frame after the handshake.
nonisolated final class PartnerNoiseChannel: PartnerFramedChannel, @unchecked Sendable {
    let io: PartnerFrameIO
    private var transport: NoiseTransport
    private let lock = NSLock()

    var handshakeHash: Data { lock.withLock { transport.handshakeHash } }

    init(io: PartnerFrameIO, transport: NoiseTransport) {
        self.io = io
        self.transport = transport
    }

    func send(_ message: RJ) async throws {
        let plain = PartnerJSON.canonicalData(message)
        guard plain.count <= PartnerFraming.maxJSONMessage else { throw PartnerChannelError.tooLarge }
        let frame: Data = try lock.withLock {
            do { return try transport.encrypt(plain) } catch { throw PartnerChannelError.tooLarge }
        }
        try await io.sendFrame(frame)
    }

    func receive(timeout: TimeInterval) async throws -> RJ {
        let frame = try await io.receiveFrame(timeout: timeout)
        let plain: Data = try lock.withLock {
            do { return try transport.decrypt(frame) } catch { throw PartnerChannelError.decryptFailed }
        }
        guard let msg = PartnerJSON.parse(plain) else { throw PartnerChannelError.malformed }
        return msg
    }

    func close() async { await io.close() }
}
