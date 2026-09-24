import Foundation
import Security

/// Turns a Google service-account JSON into a short-lived access token (docs/ai-models.md §6).
///
/// No Google SDK: the JWT is signed with the Security framework, exchanged for a token, and cached
/// until shortly before it expires. The JSON never leaves the keychain and is never logged.
enum VertexAuth {

    struct ServiceAccount: Equatable {
        var clientEmail: String
        var privateKeyPEM: String
        var tokenURI: String
        var projectID: String?

        /// Parses the file Google Cloud hands out. Returns nil for anything that is not one.
        init?(json: String) {
            guard let parsed = RJ.parse(json), parsed.object != nil,
                  let email = parsed["client_email"].string, !email.isEmpty,
                  let key = parsed["private_key"].string, !key.isEmpty else { return nil }
            clientEmail = email
            privateKeyPEM = key
            tokenURI = parsed["token_uri"].string ?? "https://oauth2.googleapis.com/token"
            projectID = parsed["project_id"].string
        }
    }

    enum AuthError: LocalizedError {
        case badServiceAccount
        case signingFailed
        case exchangeFailed(String)

        var errorDescription: String? {
            switch self {
            case .badServiceAccount:
                return "That does not look like a Google service-account JSON file."
            case .signingFailed:
                return "The service account's private key could not be used to sign a request."
            case .exchangeFailed(let message):
                return "Google rejected the service account: \(message)"
            }
        }
    }

    // MARK: - Token cache

    private struct CachedToken {
        let value: String
        let expiresAt: Date
    }

    private static let lock = NSLock()
    private nonisolated(unsafe) static var cache: [String: CachedToken] = [:]

    /// A bearer token for this service account, minted or reused.
    ///
    /// `nowProvider` and `exchange` are injected so the whole flow is testable without a network.
    static func accessToken(
        for account: ServiceAccount,
        scope: String = "https://www.googleapis.com/auth/cloud-platform",
        now: Date = Date(),
        exchange: ((URLRequest) async throws -> (Data, URLResponse))? = nil
    ) async throws -> String {
        let key = account.clientEmail + "|" + scope
        lock.lock()
        let cached = cache[key]
        lock.unlock()
        // Refresh a minute early so a request never starts with a token that expires mid-flight.
        if let cached, cached.expiresAt > now.addingTimeInterval(60) { return cached.value }

        let assertion = try signedAssertion(for: account, scope: scope, now: now)
        var request = URLRequest(url: URL(string: account.tokenURI)!)
        request.httpMethod = "POST"
        request.setValue("application/x-www-form-urlencoded", forHTTPHeaderField: "Content-Type")
        let body = "grant_type=urn:ietf:params:oauth:grant-type:jwt-bearer&assertion=" + assertion
        request.httpBody = body.data(using: .utf8)

        let (data, response) = try await (exchange ?? { try await URLSession.shared.data(for: $0) })(request)
        guard let http = response as? HTTPURLResponse, (200..<300).contains(http.statusCode),
              let parsed = RJ.parse(String(data: data, encoding: .utf8) ?? ""),
              let token = parsed["access_token"].string else {
            let detail = RJ.parse(String(data: data, encoding: .utf8) ?? "")?["error_description"].string
                ?? RJ.parse(String(data: data, encoding: .utf8) ?? "")?["error"].string
                ?? "no access token in the reply"
            throw AuthError.exchangeFailed(detail)
        }
        let lifetime = parsed["expires_in"].double ?? 3600
        lock.lock()
        cache[key] = CachedToken(value: token, expiresAt: now.addingTimeInterval(lifetime))
        lock.unlock()
        return token
    }

    static func forget(_ account: ServiceAccount) {
        lock.lock()
        cache = cache.filter { !$0.key.hasPrefix(account.clientEmail + "|") }
        lock.unlock()
    }

    // MARK: - JWT

    static func signedAssertion(for account: ServiceAccount, scope: String,
                                now: Date = Date(), lifetime: TimeInterval = 3600) throws -> String {
        let issued = Int(now.timeIntervalSince1970)
        let header = #"{"alg":"RS256","typ":"JWT"}"#
        let claims = RJ.obj([
            "iss": .str(account.clientEmail),
            "scope": .str(scope),
            "aud": .str(account.tokenURI),
            "iat": .int(issued),
            "exp": .int(issued + Int(lifetime)),
        ]).jsonText
        let signingInput = base64URL(Data(header.utf8)) + "." + base64URL(Data(claims.utf8))
        guard let key = privateKey(fromPEM: account.privateKeyPEM) else { throw AuthError.badServiceAccount }
        var error: Unmanaged<CFError>?
        guard let signature = SecKeyCreateSignature(
            key, .rsaSignatureMessagePKCS1v15SHA256, Data(signingInput.utf8) as CFData, &error
        ) as Data? else {
            error?.release()
            throw AuthError.signingFailed
        }
        return signingInput + "." + base64URL(signature)
    }

    /// JWT base64: URL alphabet, no padding.
    static func base64URL(_ data: Data) -> String {
        data.base64EncodedString()
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
    }

    /// Reads the PKCS#8 PEM the service-account file carries.
    static func privateKey(fromPEM pem: String) -> SecKey? {
        let body = pem
            .replacingOccurrences(of: "\\n", with: "\n")
            .split(separator: "\n")
            .filter { !$0.hasPrefix("-----") }
            .joined()
        guard let der = Data(base64Encoded: body) else { return nil }
        // A PKCS#8 wrapper has to come off before the Security framework will take the key.
        let raw = pkcs1Key(fromPKCS8: der) ?? der
        let attributes: [String: Any] = [
            kSecAttrKeyType as String: kSecAttrKeyTypeRSA,
            kSecAttrKeyClass as String: kSecAttrKeyClassPrivate,
        ]
        return SecKeyCreateWithData(raw as CFData, attributes as CFDictionary, nil)
    }

    /// Strips the PKCS#8 `PrivateKeyInfo` envelope, leaving the PKCS#1 `RSAPrivateKey`.
    ///
    /// Hand-rolled rather than pulled from a library: it is a fixed, well-known prefix, and adding a
    /// dependency to skip 26 bytes would be a poor trade.
    static func pkcs1Key(fromPKCS8 der: Data) -> Data? {
        var index = 0
        func readLength(_ bytes: Data) -> Int? {
            guard index < bytes.count else { return nil }
            let first = bytes[bytes.startIndex + index]
            index += 1
            if first < 0x80 { return Int(first) }
            let count = Int(first & 0x7F)
            guard count > 0, count <= 4, index + count <= bytes.count else { return nil }
            var value = 0
            for _ in 0..<count {
                value = (value << 8) | Int(bytes[bytes.startIndex + index])
                index += 1
            }
            return value
        }
        guard der.count > 2, der[der.startIndex] == 0x30 else { return nil }
        index = 1
        guard readLength(der) != nil else { return nil }
        // INTEGER version
        guard index < der.count, der[der.startIndex + index] == 0x02 else { return nil }
        index += 1
        guard let versionLength = readLength(der) else { return nil }
        index += versionLength
        // SEQUENCE AlgorithmIdentifier
        guard index < der.count, der[der.startIndex + index] == 0x30 else { return nil }
        index += 1
        guard let algorithmLength = readLength(der) else { return nil }
        index += algorithmLength
        // OCTET STRING privateKey
        guard index < der.count, der[der.startIndex + index] == 0x04 else { return nil }
        index += 1
        guard let keyLength = readLength(der), index + keyLength <= der.count else { return nil }
        return der.subdata(in: (der.startIndex + index)..<(der.startIndex + index + keyLength))
    }
}
