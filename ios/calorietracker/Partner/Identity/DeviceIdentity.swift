import CryptoKit
import Foundation

/// Where the identity bytes live. The app uses the Keychain (device-only); tests use memory.
nonisolated protocol PartnerSecretStore: Sendable {
    func load(_ key: String) -> Data?
    @discardableResult func save(_ key: String, _ data: Data) -> Bool
    func delete(_ key: String)
}

/// Keychain storage with `kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly` (docs/partner-sync.md §2): the
/// keys are never exported, so a restored or new phone is a new partner device.
nonisolated struct KeychainPartnerSecretStore: PartnerSecretStore {
    func load(_ key: String) -> Data? { KeychainHelper.loadData(key: key) }
    @discardableResult func save(_ key: String, _ data: Data) -> Bool { KeychainHelper.saveDeviceOnly(key: key, data: data) }
    func delete(_ key: String) { KeychainHelper.delete(key: key) }
}

nonisolated final class InMemoryPartnerSecretStore: PartnerSecretStore, @unchecked Sendable {
    private let lock = NSLock()
    private var items: [String: Data] = [:]

    init() {}

    func load(_ key: String) -> Data? { lock.withLock { items[key] } }
    @discardableResult func save(_ key: String, _ data: Data) -> Bool { lock.withLock { items[key] = data }; return true }
    func delete(_ key: String) { lock.withLock { items[key] = nil } }
}

/// This installation's Partner identity (docs §3): a lowercase UUID `device_id`, an X25519 static key pair
/// (Noise) and an Ed25519 signing key pair (packages). Created lazily on first use of Partner, never at startup.
nonisolated struct DeviceIdentity: Sendable {
    static let deviceIDKey = "partner.identity.device_id"
    static let x25519Key = "partner.identity.x25519"
    static let ed25519Key = "partner.identity.ed25519"

    nonisolated enum IdentityError: Error, Sendable { case keychainWriteFailed }

    let deviceID: String
    let agreementKey: Curve25519.KeyAgreement.PrivateKey
    let signingKey: Curve25519.Signing.PrivateKey

    var x25519Public: Data { agreementKey.publicKey.rawRepresentation }
    var ed25519Public: Data { signingKey.publicKey.rawRepresentation }
    var x25519PublicB64: String { PartnerEncoding.b64urlEncode(x25519Public) }
    var ed25519PublicB64: String { PartnerEncoding.b64urlEncode(ed25519Public) }

    /// Hex fingerprint (first 16 bytes of SHA-256(x25519_pub ‖ ed25519_pub)).
    var fingerprint: String { PartnerRef.fingerprint(x25519: x25519Public, ed25519: ed25519Public) }
    var formattedFingerprint: String { PartnerRef.formatFingerprint(fingerprint) }

    init(deviceID: String, agreementKey: Curve25519.KeyAgreement.PrivateKey, signingKey: Curve25519.Signing.PrivateKey) {
        self.deviceID = deviceID
        self.agreementKey = agreementKey
        self.signingKey = signingKey
    }

    /// Existing identity, or a freshly generated one persisted to `store`. A partially stored identity is
    /// regenerated as a whole so the device id and keys always belong together.
    static func loadOrCreate(store: PartnerSecretStore = KeychainPartnerSecretStore()) throws -> DeviceIdentity {
        if let existing = load(store: store) { return existing }
        let identity = DeviceIdentity(
            deviceID: UUID().uuidString.lowercased(),
            agreementKey: Curve25519.KeyAgreement.PrivateKey(),
            signingKey: Curve25519.Signing.PrivateKey()
        )
        guard store.save(x25519Key, identity.agreementKey.rawRepresentation),
              store.save(ed25519Key, identity.signingKey.rawRepresentation),
              store.save(deviceIDKey, Data(identity.deviceID.utf8))
        else {
            reset(store: store)
            throw IdentityError.keychainWriteFailed
        }
        return identity
    }

    static func load(store: PartnerSecretStore = KeychainPartnerSecretStore()) -> DeviceIdentity? {
        guard let idData = store.load(deviceIDKey), let id = String(data: idData, encoding: .utf8), PyStr.isUUID(id),
              let x = store.load(x25519Key), let agreement = try? Curve25519.KeyAgreement.PrivateKey(rawRepresentation: x),
              let ed = store.load(ed25519Key), let signing = try? Curve25519.Signing.PrivateKey(rawRepresentation: ed)
        else { return nil }
        return DeviceIdentity(deviceID: id, agreementKey: agreement, signingKey: signing)
    }

    static func reset(store: PartnerSecretStore = KeychainPartnerSecretStore()) {
        store.delete(deviceIDKey)
        store.delete(x25519Key)
        store.delete(ed25519Key)
    }

    func sign(_ message: Data) throws -> Data {
        try signingKey.signature(for: message)
    }

    /// Ed25519 verification with a peer's raw (32-byte) public key.
    static func verify(signature: Data, message: Data, publicKey: Data) -> Bool {
        guard let key = try? Curve25519.Signing.PublicKey(rawRepresentation: publicKey) else { return false }
        return key.isValidSignature(signature, for: message)
    }

    static func verify(signatureB64: String, message: Data, publicKeyB64: String) -> Bool {
        guard let sig = PartnerEncoding.b64urlDecode(signatureB64), let key = PartnerEncoding.b64urlDecode(publicKeyB64) else { return false }
        return verify(signature: sig, message: message, publicKey: key)
    }
}
