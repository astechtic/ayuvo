import AuthenticationServices
import CryptoKit
import Foundation
import UIKit

/// OAuth 2.0 authorization-code + PKCE (S256) for an iOS Google client (docs/google-health.md §2).
/// No Google SDK, same as `VertexAuth`: the consent page runs in `ASWebAuthenticationSession`,
/// which catches the `com.googleusercontent.apps.<id>:/oauth2redirect` callback itself, so no
/// Info.plist URL type is needed.
nonisolated struct GoogleOAuthPKCE: Sendable, Equatable {
    let verifier: String
    let challenge: String
    let state: String

    static let iOSClientSuffix = ".apps.googleusercontent.com"

    /// 32 random bytes → 43-character verifier (RFC 7636 §4.1), S256 challenge, random state.
    static func make() -> GoogleOAuthPKCE {
        let verifier = base64URL(randomBytes(32))
        return GoogleOAuthPKCE(verifier: verifier, challenge: challenge(for: verifier), state: base64URL(randomBytes(16)))
    }

    static func challenge(for verifier: String) -> String {
        base64URL(Data(SHA256.hash(data: Data(verifier.utf8))))
    }

    static func base64URL(_ data: Data) -> String {
        data.base64EncodedString()
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
    }

    private static func randomBytes(_ count: Int) -> Data {
        var bytes = [UInt8](repeating: 0, count: count)
        if SecRandomCopyBytes(kSecRandomDefault, count, &bytes) != errSecSuccess {
            var generator = SystemRandomNumberGenerator()
            bytes = (0..<count).map { _ in UInt8.random(in: .min ... .max, using: &generator) }
        }
        return Data(bytes)
    }

    // MARK: - Client id → redirect

    /// True for an iOS-type client id (`<number>-<hash>.apps.googleusercontent.com`).
    static func isValidIOSClientID(_ clientID: String) -> Bool {
        let trimmed = clientID.trimmingCharacters(in: .whitespacesAndNewlines)
        guard trimmed.hasSuffix(iOSClientSuffix), trimmed.count > iOSClientSuffix.count else { return false }
        let prefix = trimmed.dropLast(iOSClientSuffix.count)
        return prefix.allSatisfy { $0.isLetter || $0.isNumber || $0 == "-" }
    }

    /// The reversed client id Google registers for iOS clients.
    static func callbackScheme(clientID: String) -> String? {
        let trimmed = clientID.trimmingCharacters(in: .whitespacesAndNewlines)
        guard isValidIOSClientID(trimmed) else { return nil }
        return "com.googleusercontent.apps." + trimmed.dropLast(iOSClientSuffix.count)
    }

    static func redirectURI(clientID: String) -> String? {
        callbackScheme(clientID: clientID).map { $0 + ":/oauth2redirect" }
    }

    // MARK: - URLs

    static func authorizationURL(authURL: String, clientID: String, scopes: [String], pkce: GoogleOAuthPKCE, loginHint: String? = nil) -> URL? {
        guard let redirect = redirectURI(clientID: clientID), var components = URLComponents(string: authURL) else { return nil }
        var items = [
            URLQueryItem(name: "client_id", value: clientID.trimmingCharacters(in: .whitespacesAndNewlines)),
            URLQueryItem(name: "redirect_uri", value: redirect),
            URLQueryItem(name: "response_type", value: "code"),
            URLQueryItem(name: "scope", value: scopes.joined(separator: " ")),
            URLQueryItem(name: "code_challenge", value: pkce.challenge),
            URLQueryItem(name: "code_challenge_method", value: "S256"),
            URLQueryItem(name: "state", value: pkce.state),
            URLQueryItem(name: "access_type", value: "offline"),
            URLQueryItem(name: "prompt", value: "consent"),
            URLQueryItem(name: "include_granted_scopes", value: "true"),
        ]
        if let loginHint, !loginHint.isEmpty {
            items.append(URLQueryItem(name: "login_hint", value: loginHint))
        }
        components.queryItems = items
        return components.url
    }

    /// The authorization code from the redirect, after the `state` check.
    static func code(fromCallback url: URL, expectedState: String) throws -> String {
        let items = URLComponents(url: url, resolvingAgainstBaseURL: false)?.queryItems ?? []
        func item(_ name: String) -> String? { items.first { $0.name == name }?.value }
        if let error = item("error") {
            throw error == "access_denied" ? GoogleHealthAuthError.cancelled : GoogleHealthAuthError.oauth(error)
        }
        guard item("state") == expectedState else { throw GoogleHealthAuthError.stateMismatch }
        guard let code = item("code"), !code.isEmpty else { throw GoogleHealthAuthError.oauth("no code") }
        return code
    }
}

/// Presents the Google consent page and returns the callback URL.
@MainActor
final class GoogleOAuthWebSession: NSObject, ASWebAuthenticationPresentationContextProviding {
    private var session: ASWebAuthenticationSession?

    func authorize(url: URL, callbackScheme: String) async throws -> URL {
        try await withCheckedThrowingContinuation { continuation in
            let session = ASWebAuthenticationSession(url: url, callbackURLScheme: callbackScheme) { callback, error in
                if let callback {
                    continuation.resume(returning: callback)
                } else if let error = error as? ASWebAuthenticationSessionError, error.code == .canceledLogin {
                    continuation.resume(throwing: GoogleHealthAuthError.cancelled)
                } else {
                    continuation.resume(throwing: error ?? GoogleHealthAuthError.cancelled)
                }
            }
            session.presentationContextProvider = self
            self.session = session
            if !session.start() {
                continuation.resume(throwing: GoogleHealthAuthError.oauth("could not open the sign-in page"))
            }
        }
    }

    nonisolated func presentationAnchor(for session: ASWebAuthenticationSession) -> ASPresentationAnchor {
        MainActor.assumeIsolated {
            let scenes = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }
            let window = scenes.flatMap(\.windows).first { $0.isKeyWindow } ?? scenes.first?.windows.first
            return window ?? ASPresentationAnchor()
        }
    }
}
