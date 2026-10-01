import Foundation

nonisolated enum GoogleHealthAuthError: LocalizedError, Equatable {
    case cancelled
    case stateMismatch
    case missingClientID
    case invalidClientID
    case notConnected
    /// The refresh token was revoked or expired (unverified apps: after 7 days). Reconnect needed.
    case consentLost
    case oauth(String)
    case network(String)

    var errorDescription: String? {
        switch self {
        case .cancelled:
            return String(localized: "Google sign-in was cancelled.", comment: "Google Health connect error")
        case .stateMismatch:
            return String(localized: "Google sign-in returned an unexpected response. Please try again.", comment: "Google Health connect error (OAuth state mismatch)")
        case .missingClientID:
            return String(localized: "This build has no Google Health client ID. Add your own under Advanced.", comment: "Google Health connect error")
        case .invalidClientID:
            return String(localized: "That is not an iOS OAuth client ID (it should end in .apps.googleusercontent.com).", comment: "Google Health connect error")
        case .notConnected:
            return String(localized: "Google Health is not connected.", comment: "Google Health error")
        case .consentLost:
            return String(localized: "Google access has expired or was removed. Reconnect to keep syncing.", comment: "Google Health error")
        case .oauth(let message):
            return String(localized: "Google rejected the sign-in: \(message)", comment: "Google Health connect error; placeholder is Google's message")
        case .network(let message):
            return String(localized: "Could not reach Google: \(message)", comment: "Google Health network error; placeholder is the system message")
        }
    }
}

nonisolated enum GoogleHealthClientMode: String, Sendable, Codable {
    /// The app's own iOS client (`GH_IOS_CLIENT_ID`).
    case bundled
    /// "Use my own client ID" (Advanced).
    case custom
}

/// Where the tokens live. Production is the Keychain; tests use `InMemoryGoogleHealthSecureStore`.
nonisolated protocol GoogleHealthSecureStore: Sendable {
    func load(_ key: String) -> String?
    func save(_ key: String, _ value: String)
    func delete(_ key: String)
}

nonisolated struct KeychainGoogleHealthSecureStore: GoogleHealthSecureStore {
    func load(_ key: String) -> String? { KeychainHelper.load(key: key) }
    func save(_ key: String, _ value: String) { KeychainHelper.save(key: key, value: value) }
    func delete(_ key: String) { KeychainHelper.delete(key: key) }
}

nonisolated final class InMemoryGoogleHealthSecureStore: GoogleHealthSecureStore, @unchecked Sendable {
    private let lock = NSLock()
    private var values: [String: String] = [:]

    init(_ values: [String: String] = [:]) {
        self.values = values
    }

    func load(_ key: String) -> String? { lock.withLock { values[key] } }
    func save(_ key: String, _ value: String) { lock.withLock { values[key] = value } }
    func delete(_ key: String) { lock.withLock { values[key] = nil } }
}

/// Supplies a bearer token to `GoogleHealthClient`; `forceRefresh` after a 401.
nonisolated protocol GoogleHealthTokenProviding: Sendable {
    func accessToken(forceRefresh: Bool) async throws -> String
}

/// Device-local account metadata (docs/google-health.md §3): email, scopes, client mode,
/// connected time and the two toggles. No health values; every key starts with `googleHealth`,
/// which `CloudBackupPolicy` keeps out of the app backup.
nonisolated enum GoogleHealthSettings {
    static let emailKey = "googleHealthAccountEmail"
    static let scopesKey = "googleHealthGrantedScopes"
    static let clientModeKey = "googleHealthClientMode"
    static let customClientIDKey = "googleHealthCustomClientID"
    static let connectedAtKey = "googleHealthConnectedAt"
    static let groupsKey = "googleHealthEnabledGroups"
    static let autoSyncKey = "googleHealthAutoSync"
    static let writeBackKey = "googleHealthWriteBack"
    static let lastSyncAtKey = "googleHealthLastSyncAt"
    static let lastAttemptAtKey = "googleHealthLastAttemptAt"
    static let needsReconnectKey = "googleHealthNeedsReconnect"

    static let allKeys = [
        emailKey, scopesKey, clientModeKey, customClientIDKey, connectedAtKey, groupsKey, autoSyncKey, writeBackKey,
        lastSyncAtKey, lastAttemptAtKey, needsReconnectKey,
    ]

    /// Info.plist `GH_IOS_CLIENT_ID` (from the gitignored `ios/GoogleHealth.xcconfig`); nil when unset.
    static var bundledClientID: String? {
        guard let value = Bundle.main.object(forInfoDictionaryKey: "GH_IOS_CLIENT_ID") as? String else { return nil }
        let trimmed = value.trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty || trimmed.hasPrefix("$(") ? nil : trimmed
    }

    static func isConnected(defaults: UserDefaults = .standard) -> Bool {
        defaults.object(forKey: connectedAtKey) != nil
    }
}

nonisolated struct GoogleHealthAccount: Sendable, Equatable {
    var email: String?
    var grantedScopes: [String]
    var clientMode: GoogleHealthClientMode
    var connectedAt: Date

    static func load(defaults: UserDefaults = .standard) -> GoogleHealthAccount? {
        let connected = defaults.double(forKey: GoogleHealthSettings.connectedAtKey)
        guard connected > 0 else { return nil }
        return GoogleHealthAccount(
            email: defaults.string(forKey: GoogleHealthSettings.emailKey),
            grantedScopes: defaults.stringArray(forKey: GoogleHealthSettings.scopesKey) ?? [],
            clientMode: GoogleHealthClientMode(rawValue: defaults.string(forKey: GoogleHealthSettings.clientModeKey) ?? "") ?? .bundled,
            connectedAt: Date(timeIntervalSince1970: connected)
        )
    }

    func save(defaults: UserDefaults = .standard) {
        defaults.set(email, forKey: GoogleHealthSettings.emailKey)
        defaults.set(grantedScopes, forKey: GoogleHealthSettings.scopesKey)
        defaults.set(clientMode.rawValue, forKey: GoogleHealthSettings.clientModeKey)
        defaults.set(connectedAt.timeIntervalSince1970, forKey: GoogleHealthSettings.connectedAtKey)
    }
}

/// Token exchange, refresh and revoke against `oauth2.googleapis.com`, plus the userinfo email.
/// Tokens are only ever in the secure store; refreshes are coalesced by the actor.
actor GoogleHealthAuth: GoogleHealthTokenProviding {
    nonisolated static let refreshTokenKey = "googleHealth.refreshToken"
    nonisolated static let accessTokenKey = "googleHealth.accessToken"
    nonisolated static let expiresAtKey = "googleHealth.accessTokenExpiresAt"
    nonisolated static let clientIDKey = "googleHealth.clientID"

    nonisolated struct TokenResponse: Sendable, Equatable {
        var accessToken: String
        var expiresIn: Double
        var refreshToken: String?
        var scopes: [String]
    }

    let api: GoogleHealthMap.API
    let store: any GoogleHealthSecureStore
    let session: URLSession
    let now: @Sendable () -> Date
    private var refreshTask: Task<String, Error>?

    init(api: GoogleHealthMap.API, store: any GoogleHealthSecureStore = KeychainGoogleHealthSecureStore(), session: URLSession = .shared, now: @escaping @Sendable () -> Date = { Date() }) {
        self.api = api
        self.store = store
        self.session = session
        self.now = now
    }

    nonisolated var hasRefreshToken: Bool { store.load(Self.refreshTokenKey) != nil }

    // MARK: - Tokens

    func accessToken(forceRefresh: Bool) async throws -> String {
        if !forceRefresh, let token = store.load(Self.accessTokenKey),
           let expires = store.load(Self.expiresAtKey).flatMap(Double.init),
           expires > now().addingTimeInterval(60).timeIntervalSince1970 {
            return token
        }
        if let refreshTask { return try await refreshTask.value }
        let task = Task { try await self.refresh() }
        refreshTask = task
        defer { refreshTask = nil }
        return try await task.value
    }

    private func refresh() async throws -> String {
        guard let refreshToken = store.load(Self.refreshTokenKey), let clientID = store.load(Self.clientIDKey) else {
            throw GoogleHealthAuthError.notConnected
        }
        let response = try await tokenRequest([
            "grant_type": "refresh_token",
            "client_id": clientID,
            "refresh_token": refreshToken,
        ])
        persist(response, clientID: clientID)
        return response.accessToken
    }

    /// Authorization code → tokens (stored), with the scopes Google actually granted.
    func exchange(code: String, verifier: String, clientID: String, redirectURI: String) async throws -> TokenResponse {
        let response = try await tokenRequest([
            "grant_type": "authorization_code",
            "code": code,
            "code_verifier": verifier,
            "client_id": clientID,
            "redirect_uri": redirectURI,
        ])
        persist(response, clientID: clientID)
        return response
    }

    private func persist(_ response: TokenResponse, clientID: String) {
        store.save(Self.accessTokenKey, response.accessToken)
        store.save(Self.expiresAtKey, String(now().addingTimeInterval(response.expiresIn).timeIntervalSince1970))
        store.save(Self.clientIDKey, clientID)
        if let refresh = response.refreshToken {
            store.save(Self.refreshTokenKey, refresh)
        }
    }

    private func tokenRequest(_ fields: [String: String]) async throws -> TokenResponse {
        guard let url = URL(string: api.tokenURL) else { throw GoogleHealthAuthError.oauth("bad token URL") }
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("application/x-www-form-urlencoded", forHTTPHeaderField: "Content-Type")
        request.httpBody = Self.formBody(fields).data(using: .utf8)
        let (data, response): (Data, URLResponse)
        do {
            (data, response) = try await session.data(for: request)
        } catch {
            throw GoogleHealthAuthError.network(error.localizedDescription)
        }
        let json = (try? JSONSerialization.jsonObject(with: data)).map(RJ.from) ?? .null
        guard let http = response as? HTTPURLResponse, (200..<300).contains(http.statusCode),
              let token = json["access_token"].string else {
            let error = json["error"].string ?? "http \((response as? HTTPURLResponse)?.statusCode ?? 0)"
            if error == "invalid_grant" { throw GoogleHealthAuthError.consentLost }
            throw GoogleHealthAuthError.oauth(json["error_description"].string ?? error)
        }
        return TokenResponse(
            accessToken: token,
            expiresIn: json["expires_in"].double ?? 3600,
            refreshToken: json["refresh_token"].string,
            scopes: (json["scope"].string ?? "").split(separator: " ").map(String.init)
        )
    }

    nonisolated static func formBody(_ fields: [String: String]) -> String {
        var allowed = CharacterSet.urlQueryAllowed
        allowed.remove(charactersIn: "+&=/:?")
        return fields.sorted { $0.key < $1.key }
            .map { "\($0.key)=\($0.value.addingPercentEncoding(withAllowedCharacters: allowed) ?? $0.value)" }
            .joined(separator: "&")
    }

    // MARK: - Account label

    /// `email` from the OpenID userinfo endpoint (identity scope `email`).
    func fetchEmail() async -> String? {
        guard let url = URL(string: api.userinfoURL), let token = try? await accessToken(forceRefresh: false) else { return nil }
        var request = URLRequest(url: url)
        request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        guard let (data, response) = try? await session.data(for: request),
              (response as? HTTPURLResponse)?.statusCode == 200,
              let object = try? JSONSerialization.jsonObject(with: data) else { return nil }
        return RJ.from(object)["email"].string
    }

    // MARK: - Disconnect

    /// Revokes the grant at Google (best effort) and clears every stored token.
    func revokeAndClear() async {
        let token = store.load(Self.refreshTokenKey) ?? store.load(Self.accessTokenKey)
        if let token, var components = URLComponents(string: api.revokeURL) {
            components.queryItems = [URLQueryItem(name: "token", value: token)]
            if let url = components.url {
                var request = URLRequest(url: url)
                request.httpMethod = "POST"
                request.setValue("application/x-www-form-urlencoded", forHTTPHeaderField: "Content-Type")
                _ = try? await session.data(for: request)
            }
        }
        clearTokens()
    }

    func clearTokens() {
        for key in [Self.refreshTokenKey, Self.accessTokenKey, Self.expiresAtKey, Self.clientIDKey] {
            store.delete(key)
        }
    }
}
