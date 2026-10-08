import CryptoKit
import Foundation
import Testing
@testable import calorietracker

/// Replays the published cacophony vectors (shared/partner/test-vectors/noise_*.json) byte for byte with
/// injected static and ephemeral keys: every handshake and transport ciphertext and the final handshake hash.
struct PartnerNoiseTests {
    static let files = ["noise_ikpsk2", "noise_kk"]

    static func hex(_ v: RJ) throws -> Data { try #require(PartnerEncoding.fromHex(v.string ?? "x"), "bad hex") }

    static func key(_ v: RJ) throws -> Curve25519.KeyAgreement.PrivateKey {
        try Curve25519.KeyAgreement.PrivateKey(rawRepresentation: hex(v))
    }

    @Test(arguments: files)
    func replaysCacophonyVectors(_ name: String) throws {
        let url = PartnerVectorTests.vectorsDirectory.appendingPathComponent("\(name).json")
        let root = try #require(PartnerJSON.parse(try Data(contentsOf: url)))
        #expect(root["format"].string == "ayuvo-partner-noise-vectors")
        let vectors = root["vectors"].array ?? []
        #expect(!vectors.isEmpty)
        var messagesChecked = 0
        for v in vectors {
            let pattern = try #require(v["protocol_name"].string == NoisePattern.IKpsk2.protocolName ? NoisePattern.IKpsk2
                : v["protocol_name"].string == NoisePattern.KK.protocolName ? NoisePattern.KK : nil, "unsupported \(v["protocol_name"])")
            let initPSK = try v["init_psks"].array?.first.map(Self.hex)
            let respPSK = try v["resp_psks"].array?.first.map(Self.hex)
            var initiator = try NoiseHandshakeState(
                pattern: pattern, initiator: true, prologue: Self.hex(v["init_prologue"]), staticKey: Self.key(v["init_static"]),
                remoteStatic: Self.hex(v["init_remote_static"]), psk: initPSK, ephemeral: Self.key(v["init_ephemeral"]))
            let respRemote: Data? = v["resp_remote_static"].isNull ? nil : try Self.hex(v["resp_remote_static"])
            var responder = try NoiseHandshakeState(
                pattern: pattern, initiator: false, prologue: Self.hex(v["resp_prologue"]), staticKey: Self.key(v["resp_static"]),
                remoteStatic: respRemote, psk: respPSK, ephemeral: Self.key(v["resp_ephemeral"]))
            var initT: NoiseTransport?
            var respT: NoiseTransport?
            for (i, m) in (v["messages"].array ?? []).enumerated() {
                let payload = try Self.hex(m["payload"])
                let expected = try Self.hex(m["ciphertext"])
                let fromInitiator = i % 2 == 0
                let ct: Data
                let received: Data
                if !initiator.isComplete || !responder.isComplete {
                    if fromInitiator {
                        ct = try initiator.writeMessage(payload)
                        received = try responder.readMessage(ct)
                    } else {
                        ct = try responder.writeMessage(payload)
                        received = try initiator.readMessage(ct)
                    }
                    if initiator.isComplete, responder.isComplete {
                        initT = try initiator.split()
                        respT = try responder.split()
                        #expect(PartnerEncoding.hex(initiator.handshakeHash) == v["handshake_hash"].string, "\(name): handshake_hash")
                        #expect(initiator.handshakeHash == responder.handshakeHash)
                    }
                } else if fromInitiator {
                    ct = try initT!.encrypt(payload)
                    received = try respT!.decrypt(ct)
                } else {
                    ct = try respT!.encrypt(payload)
                    received = try initT!.decrypt(ct)
                }
                #expect(PartnerEncoding.hex(ct) == PartnerEncoding.hex(expected), "\(name): message \(i)")
                #expect(received == payload)
                messagesChecked += 1
            }
            #expect(initT != nil)
            #expect(initiator.remoteStatic == (try Self.key(v["resp_static"])).publicKey.rawRepresentation)
            #expect(responder.remoteStatic == (try Self.key(v["init_static"])).publicKey.rawRepresentation)
        }
        print("PARTNER-NOISE \(name).json \(messagesChecked) messages")
    }

    @Test func pairingWithRandomKeysAgreesAndWrongPSKFails() throws {
        let a = Curve25519.KeyAgreement.PrivateKey(), b = Curve25519.KeyAgreement.PrivateKey()
        let token = Data(repeating: 7, count: 32)
        let psk = PartnerRef.pairingPSK(token: token)
        let prologue = Data(PartnerCatalog.shared.prologue.utf8)
        var i = try NoiseHandshakeState(pattern: .IKpsk2, initiator: true, prologue: prologue, staticKey: a,
                                        remoteStatic: b.publicKey.rawRepresentation, psk: psk)
        var r = try NoiseHandshakeState(pattern: .IKpsk2, initiator: false, prologue: prologue, staticKey: b, psk: psk)
        _ = try r.readMessage(try i.writeMessage(Data("hello".utf8)))
        _ = try i.readMessage(try r.writeMessage())
        #expect(PartnerRef.sasCode(handshakeHash: i.handshakeHash) == PartnerRef.sasCode(handshakeHash: r.handshakeHash))
        var ti = try i.split(), tr = try r.split()
        #expect(try tr.decrypt(try ti.encrypt(Data("{}".utf8))) == Data("{}".utf8))

        // Wrong psk: the responder accepts message 1 (psk is mixed in message 2) but the initiator fails to read message 2.
        var i2 = try NoiseHandshakeState(pattern: .IKpsk2, initiator: true, prologue: prologue, staticKey: a,
                                         remoteStatic: b.publicKey.rawRepresentation, psk: psk)
        var r2 = try NoiseHandshakeState(pattern: .IKpsk2, initiator: false, prologue: prologue, staticKey: b,
                                         psk: PartnerRef.pairingPSK(token: Data(repeating: 8, count: 32)))
        _ = try r2.readMessage(try i2.writeMessage())
        let reply = try r2.writeMessage()
        #expect(throws: NoiseError.decryptFailed) { _ = try i2.readMessage(reply) }

        // KK with an unknown initiator key: the responder fails before any payload is accepted.
        let stranger = Curve25519.KeyAgreement.PrivateKey()
        var k1 = try NoiseHandshakeState(pattern: .KK, initiator: true, prologue: prologue, staticKey: stranger, remoteStatic: b.publicKey.rawRepresentation)
        var k2 = try NoiseHandshakeState(pattern: .KK, initiator: false, prologue: prologue, staticKey: b, remoteStatic: a.publicKey.rawRepresentation)
        let first = try k1.writeMessage(Data("x".utf8))
        #expect(throws: NoiseError.decryptFailed) { _ = try k2.readMessage(first) }
    }

    @Test func transportRejectsOversizedMessages() throws {
        let a = Curve25519.KeyAgreement.PrivateKey(), b = Curve25519.KeyAgreement.PrivateKey()
        var i = try NoiseHandshakeState(pattern: .KK, initiator: true, prologue: Data(), staticKey: a, remoteStatic: b.publicKey.rawRepresentation)
        var r = try NoiseHandshakeState(pattern: .KK, initiator: false, prologue: Data(), staticKey: b, remoteStatic: a.publicKey.rawRepresentation)
        _ = try r.readMessage(try i.writeMessage())
        _ = try i.readMessage(try r.writeMessage())
        var t = try i.split()
        #expect(throws: NoiseError.messageTooLarge) { _ = try t.encrypt(Data(count: 65535 - 15)) }
        #expect((try t.encrypt(Data(count: 65535 - 16))).count == 65535)
    }
}
