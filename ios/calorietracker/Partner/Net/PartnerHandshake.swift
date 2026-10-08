import CryptoKit
import Foundation

/// Noise handshakes over raw frames (docs/partner-sync.md §5): KK for trusted sessions, IKpsk2 for pairing.
nonisolated enum PartnerHandshake {
    static var prologue: Data { Data(PartnerCatalog.shared.prologue.utf8) }
    static let handshakeTimeout: TimeInterval = 10

    /// KK initiator: I know the responder's static key from `partners`.
    static func kkInitiate(io: PartnerFrameIO, identity: DeviceIdentity, peer: PartnerPeer) async throws -> PartnerNoiseChannel {
        guard let remote = PartnerEncoding.b64urlDecode(peer.x25519Pub), remote.count == 32 else { throw PartnerNetError.notTrusted }
        var hs = try NoiseHandshakeState(pattern: .KK, initiator: true, prologue: prologue, staticKey: identity.agreementKey, remoteStatic: remote)
        try await io.sendFrame(try hs.writeMessage())
        let reply: Data
        do { reply = try await io.receiveFrame(timeout: handshakeTimeout) } catch { throw PartnerNetError.notTrusted }
        do { _ = try hs.readMessage(reply) } catch { throw PartnerNetError.notTrusted }
        return PartnerNoiseChannel(io: io, transport: try hs.split())
    }

    /// KK responder: the initiator's static key is a pre-message, so the first message is tried against every trusted
    /// partner's key (unpaired rows excluded). No match → `not_trusted`: nothing is sent and the caller closes.
    static func kkRespond(io: PartnerFrameIO, identity: DeviceIdentity, candidates: [PartnerPeer]) async throws -> (PartnerNoiseChannel, PartnerPeer) {
        let first: Data
        do { first = try await io.receiveFrame(timeout: handshakeTimeout) } catch { throw PartnerNetError.handshakeFailed }
        for peer in candidates where peer.isTrusted {
            guard let remote = PartnerEncoding.b64urlDecode(peer.x25519Pub), remote.count == 32,
                  var hs = try? NoiseHandshakeState(pattern: .KK, initiator: false, prologue: prologue, staticKey: identity.agreementKey,
                                                    remoteStatic: remote),
                  (try? hs.readMessage(first)) != nil
            else { continue }
            try await io.sendFrame(try hs.writeMessage())
            return (PartnerNoiseChannel(io: io, transport: try hs.split()), peer)
        }
        throw PartnerNetError.notTrusted
    }

    /// IKpsk2 initiator (scanner): the responder's static key and the psk come from the QR.
    static func ikInitiate(io: PartnerFrameIO, identity: DeviceIdentity, responderX25519: Data, psk: Data) async throws -> PartnerNoiseChannel {
        var hs = try NoiseHandshakeState(pattern: .IKpsk2, initiator: true, prologue: prologue, staticKey: identity.agreementKey,
                                         remoteStatic: responderX25519, psk: psk)
        try await io.sendFrame(try hs.writeMessage())
        let reply: Data
        do { reply = try await io.receiveFrame(timeout: handshakeTimeout) } catch { throw PartnerNetError.notTrusted }
        // A wrong psk (expired / other QR) fails here.
        do { _ = try hs.readMessage(reply) } catch { throw PartnerNetError.notTrusted }
        return PartnerNoiseChannel(io: io, transport: try hs.split())
    }

    /// IKpsk2 responder (code shower): learns the initiator's X25519 key from the handshake. A wrong psk is only
    /// detected when the first transport message fails to decrypt.
    static func ikRespond(io: PartnerFrameIO, identity: DeviceIdentity, psk: Data) async throws -> (PartnerNoiseChannel, initiatorX25519: Data) {
        let first = try await io.receiveFrame(timeout: handshakeTimeout)
        var hs = try NoiseHandshakeState(pattern: .IKpsk2, initiator: false, prologue: prologue, staticKey: identity.agreementKey, psk: psk)
        do { _ = try hs.readMessage(first) } catch { throw PartnerNetError.handshakeFailed }
        guard let remote = hs.remoteStatic else { throw PartnerNetError.handshakeFailed }
        try await io.sendFrame(try hs.writeMessage())
        return (PartnerNoiseChannel(io: io, transport: try hs.split()), remote)
    }
}
