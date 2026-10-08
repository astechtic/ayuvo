import CryptoKit
import Foundation

// Noise Protocol Framework (revision 34) for exactly the two patterns Partner Health Sync uses
// (docs/partner-sync.md §5): Noise_IKpsk2_25519_ChaChaPoly_SHA256 (pairing) and
// Noise_KK_25519_ChaChaPoly_SHA256 (trusted sessions). DH = X25519 (CryptoKit), AEAD = ChaCha20-Poly1305 with
// nonce = 4 zero bytes ‖ 8-byte little-endian counter, HASH = SHA-256, HKDF per §4.3 of the spec.
// Replayed byte for byte against the cacophony vectors in shared/partner/test-vectors/noise_*.json.

nonisolated enum NoiseError: Error, Equatable, Sendable {
    case messageTooLarge
    case messageTooShort
    case decryptFailed
    case nonceExhausted
    case invalidKey
    case wrongState
    case missingKey
}

nonisolated enum NoisePattern: String, Sendable {
    case IKpsk2
    case KK

    var protocolName: String { "Noise_\(rawValue)_25519_ChaChaPoly_SHA256" }

    fileprivate enum Token { case e, s, ee, es, se, ss, psk }

    fileprivate var messages: [[Token]] {
        switch self {
        case .IKpsk2: [[.e, .es, .s, .ss], [.e, .ee, .se, .psk]]
        case .KK: [[.e, .es, .ss], [.e, .ee, .se]]
        }
    }

    fileprivate var isPSK: Bool { self == .IKpsk2 }
    /// Pre-message static keys: (initiator's static known to the responder, responder's static known to the initiator).
    fileprivate var preInitiatorStatic: Bool { self == .KK }
    fileprivate var preResponderStatic: Bool { true }
}

nonisolated enum Noise {
    static let maxMessage = 65535
    static let dhLen = 32
    static let hashLen = 32
    static let tagLen = 16

    static func dh(_ priv: Curve25519.KeyAgreement.PrivateKey, _ pubRaw: Data) throws -> Data {
        guard let pub = try? Curve25519.KeyAgreement.PublicKey(rawRepresentation: pubRaw) else { throw NoiseError.invalidKey }
        let secret = try priv.sharedSecretFromKeyAgreement(with: pub)
        return secret.withUnsafeBytes { Data($0) }
    }

    static func hmac(_ key: Data, _ data: Data) -> Data {
        Data(HMAC<SHA256>.authenticationCode(for: data, using: SymmetricKey(data: key)))
    }

    /// Spec §4.3 HKDF(chaining_key, input_key_material, num_outputs).
    static func hkdf(_ ck: Data, _ ikm: Data, _ n: Int) -> [Data] {
        let temp = hmac(ck, ikm)
        var outs: [Data] = []
        var prev = Data()
        for i in 1...n {
            var input = prev
            input.append(UInt8(i))
            prev = hmac(temp, input)
            outs.append(prev)
        }
        return outs
    }

    static func sha256(_ data: Data) -> Data { Data(SHA256.hash(data: data)) }
}

/// Spec §5.1.
nonisolated struct NoiseCipherState: Sendable {
    private(set) var key: Data?
    private(set) var nonce: UInt64 = 0

    init(key: Data? = nil) { self.key = key }

    var hasKey: Bool { key != nil }

    mutating func initializeKey(_ k: Data?) {
        key = k
        nonce = 0
    }

    private func nonceBytes() -> ChaChaPoly.Nonce {
        var bytes = Data(repeating: 0, count: 4)
        withUnsafeBytes(of: nonce.littleEndian) { bytes.append(contentsOf: $0) }
        return try! ChaChaPoly.Nonce(data: bytes)
    }

    mutating func encryptWithAd(_ ad: Data, _ plaintext: Data) throws -> Data {
        guard let key else { return plaintext }
        guard nonce != UInt64.max else { throw NoiseError.nonceExhausted }
        let box = try ChaChaPoly.seal(plaintext, using: SymmetricKey(data: key), nonce: nonceBytes(), authenticating: ad)
        nonce += 1
        return box.ciphertext + box.tag
    }

    mutating func decryptWithAd(_ ad: Data, _ ciphertext: Data) throws -> Data {
        guard let key else { return ciphertext }
        guard nonce != UInt64.max else { throw NoiseError.nonceExhausted }
        guard ciphertext.count >= Noise.tagLen else { throw NoiseError.messageTooShort }
        let ct = ciphertext.prefix(ciphertext.count - Noise.tagLen)
        let tag = ciphertext.suffix(Noise.tagLen)
        do {
            let box = try ChaChaPoly.SealedBox(nonce: nonceBytes(), ciphertext: ct, tag: tag)
            let plain = try ChaChaPoly.open(box, using: SymmetricKey(data: key), authenticating: ad)
            nonce += 1
            return plain
        } catch {
            throw NoiseError.decryptFailed
        }
    }
}

/// Spec §5.2.
nonisolated struct NoiseSymmetricState: Sendable {
    private(set) var cipher = NoiseCipherState()
    private(set) var ck: Data
    private(set) var h: Data

    init(protocolName: String) {
        let name = Data(protocolName.utf8)
        if name.count <= Noise.hashLen {
            h = name + Data(repeating: 0, count: Noise.hashLen - name.count)
        } else {
            h = Noise.sha256(name)
        }
        ck = h
    }

    mutating func mixKey(_ ikm: Data) {
        let out = Noise.hkdf(ck, ikm, 2)
        ck = out[0]
        cipher.initializeKey(out[1])
    }

    mutating func mixHash(_ data: Data) {
        h = Noise.sha256(h + data)
    }

    mutating func mixKeyAndHash(_ ikm: Data) {
        let out = Noise.hkdf(ck, ikm, 3)
        ck = out[0]
        mixHash(out[1])
        cipher.initializeKey(out[2])
    }

    mutating func encryptAndHash(_ plaintext: Data) throws -> Data {
        let ct = try cipher.encryptWithAd(h, plaintext)
        mixHash(ct)
        return ct
    }

    mutating func decryptAndHash(_ ciphertext: Data) throws -> Data {
        let pt = try cipher.decryptWithAd(h, ciphertext)
        mixHash(ciphertext)
        return pt
    }

    func split() -> (NoiseCipherState, NoiseCipherState) {
        let out = Noise.hkdf(ck, Data(), 2)
        return (NoiseCipherState(key: out[0]), NoiseCipherState(key: out[1]))
    }
}

/// Transport after the handshake: `send` encrypts my messages, `receive` decrypts the peer's.
nonisolated struct NoiseTransport: Sendable {
    var send: NoiseCipherState
    var receive: NoiseCipherState
    let handshakeHash: Data

    mutating func encrypt(_ payload: Data) throws -> Data {
        guard payload.count + Noise.tagLen <= Noise.maxMessage else { throw NoiseError.messageTooLarge }
        return try send.encryptWithAd(Data(), payload)
    }

    mutating func decrypt(_ message: Data) throws -> Data {
        guard message.count <= Noise.maxMessage else { throw NoiseError.messageTooLarge }
        return try receive.decryptWithAd(Data(), message)
    }
}

/// Spec §5.3. Static/ephemeral keys can be injected (tests replay vectors); otherwise ephemerals are fresh.
nonisolated struct NoiseHandshakeState: Sendable {
    let pattern: NoisePattern
    let initiator: Bool
    private var symmetric: NoiseSymmetricState
    private let s: Curve25519.KeyAgreement.PrivateKey
    private var e: Curve25519.KeyAgreement.PrivateKey?
    private var fixedEphemeral: Curve25519.KeyAgreement.PrivateKey?
    private(set) var remoteStatic: Data?
    private(set) var remoteEphemeral: Data?
    private let psk: Data?
    private var messageIndex = 0

    /// - Parameters:
    ///   - remoteStatic: required for the initiator (IK, KK) and for the KK responder.
    ///   - psk: 32 bytes, required for IKpsk2.
    init(pattern: NoisePattern, initiator: Bool, prologue: Data, staticKey: Curve25519.KeyAgreement.PrivateKey,
         remoteStatic: Data? = nil, psk: Data? = nil, ephemeral: Curve25519.KeyAgreement.PrivateKey? = nil) throws {
        self.pattern = pattern
        self.initiator = initiator
        self.s = staticKey
        self.remoteStatic = remoteStatic
        self.psk = psk
        self.fixedEphemeral = ephemeral
        if pattern.isPSK, psk?.count != 32 { throw NoiseError.missingKey }
        symmetric = NoiseSymmetricState(protocolName: pattern.protocolName)
        symmetric.mixHash(prologue)
        // Pre-messages: "-> s" (KK) then "<- s".
        if pattern.preInitiatorStatic {
            if initiator {
                symmetric.mixHash(staticKey.publicKey.rawRepresentation)
            } else {
                guard let remoteStatic, remoteStatic.count == Noise.dhLen else { throw NoiseError.missingKey }
                symmetric.mixHash(remoteStatic)
            }
        }
        if pattern.preResponderStatic {
            if initiator {
                guard let remoteStatic, remoteStatic.count == Noise.dhLen else { throw NoiseError.missingKey }
                symmetric.mixHash(remoteStatic)
            } else {
                symmetric.mixHash(staticKey.publicKey.rawRepresentation)
            }
        }
    }

    var isComplete: Bool { messageIndex >= pattern.messages.count }
    var handshakeHash: Data { symmetric.h }

    private var myTurn: Bool { (messageIndex % 2 == 0) == initiator }

    private func dh(_ token: NoisePattern.Token) throws -> Data {
        // es: initiator e × responder s; se: initiator s × responder e.
        switch token {
        case .ee:
            guard let e, let re = remoteEphemeral else { throw NoiseError.wrongState }
            return try Noise.dh(e, re)
        case .es:
            if initiator {
                guard let e, let rs = remoteStatic else { throw NoiseError.wrongState }
                return try Noise.dh(e, rs)
            }
            guard let re = remoteEphemeral else { throw NoiseError.wrongState }
            return try Noise.dh(s, re)
        case .se:
            if initiator {
                guard let re = remoteEphemeral else { throw NoiseError.wrongState }
                return try Noise.dh(s, re)
            }
            guard let e, let rs = remoteStatic else { throw NoiseError.wrongState }
            return try Noise.dh(e, rs)
        case .ss:
            guard let rs = remoteStatic else { throw NoiseError.wrongState }
            return try Noise.dh(s, rs)
        default:
            throw NoiseError.wrongState
        }
    }

    /// WriteMessage. Returns the handshake message bytes.
    mutating func writeMessage(_ payload: Data = Data()) throws -> Data {
        guard !isComplete, myTurn else { throw NoiseError.wrongState }
        var out = Data()
        for token in pattern.messages[messageIndex] {
            switch token {
            case .e:
                let eph = fixedEphemeral ?? Curve25519.KeyAgreement.PrivateKey()
                fixedEphemeral = nil
                e = eph
                let pub = eph.publicKey.rawRepresentation
                out.append(pub)
                symmetric.mixHash(pub)
                if pattern.isPSK { symmetric.mixKey(pub) }
            case .s:
                out.append(try symmetric.encryptAndHash(s.publicKey.rawRepresentation))
            case .psk:
                symmetric.mixKeyAndHash(psk ?? Data())
            default:
                symmetric.mixKey(try dh(token))
            }
        }
        out.append(try symmetric.encryptAndHash(payload))
        guard out.count <= Noise.maxMessage else { throw NoiseError.messageTooLarge }
        messageIndex += 1
        return out
    }

    /// ReadMessage. Returns the decrypted payload.
    mutating func readMessage(_ message: Data) throws -> Data {
        guard !isComplete, !myTurn else { throw NoiseError.wrongState }
        guard message.count <= Noise.maxMessage else { throw NoiseError.messageTooLarge }
        var rest = message[...]
        for token in pattern.messages[messageIndex] {
            switch token {
            case .e:
                guard rest.count >= Noise.dhLen else { throw NoiseError.messageTooShort }
                let re = Data(rest.prefix(Noise.dhLen))
                rest = rest.dropFirst(Noise.dhLen)
                remoteEphemeral = re
                symmetric.mixHash(re)
                if pattern.isPSK { symmetric.mixKey(re) }
            case .s:
                let len = symmetric.cipher.hasKey ? Noise.dhLen + Noise.tagLen : Noise.dhLen
                guard rest.count >= len else { throw NoiseError.messageTooShort }
                let rs = try symmetric.decryptAndHash(Data(rest.prefix(len)))
                rest = rest.dropFirst(len)
                remoteStatic = rs
            case .psk:
                symmetric.mixKeyAndHash(psk ?? Data())
            default:
                symmetric.mixKey(try dh(token))
            }
        }
        let payload = try symmetric.decryptAndHash(Data(rest))
        messageIndex += 1
        return payload
    }

    /// After the last handshake message: initiator sends with the first key, responder with the second.
    func split() throws -> NoiseTransport {
        guard isComplete else { throw NoiseError.wrongState }
        let (c1, c2) = symmetric.split()
        return initiator
            ? NoiseTransport(send: c1, receive: c2, handshakeHash: symmetric.h)
            : NoiseTransport(send: c2, receive: c1, handshakeHash: symmetric.h)
    }
}
