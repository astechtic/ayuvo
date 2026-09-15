import Foundation

/// Single source of truth for Ayuvo's public URLs, support address and store identity.
/// `scripts/brand_residue_ios.py` fails if a placeholder value is left here.
nonisolated enum AppLinks {
    static let siteURL = URL(string: "https://ayuvo-health.web.app")!
    static let privacyURL = URL(string: "https://ayuvo-health.web.app/privacy")!
    static let termsURL = URL(string: "https://ayuvo-health.web.app/terms")!
    static let supportURL = URL(string: "https://ayuvo-health.web.app/support")!

    /// Support mailbox used by Help & Support.
    static let supportEmail = "yaaratech@gmail.com"

    /// App Store numeric identifier (App Store Connect record).
    static let appStoreID = "6811947230"

    /// True once `appStoreID` holds a real numeric App Store id.
    static var hasAppStoreID: Bool {
        !appStoreID.isEmpty && appStoreID.allSatisfy(\.isNumber)
    }

    static var appStoreURL: URL {
        URL(string: "https://apps.apple.com/app/id\(appStoreID)") ?? siteURL
    }

    static var lookupURL: URL {
        URL(string: "https://itunes.apple.com/lookup?id=\(appStoreID)&country=us")
            ?? URL(string: "https://itunes.apple.com/lookup")!
    }

    static var appVersion: String {
        Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "1.0"
    }

    static var buildNumber: String {
        Bundle.main.infoDictionary?["CFBundleVersion"] as? String ?? "1"
    }

    /// User-Agent sent to third-party data services (Open Food Facts).
    static var userAgent: String {
        "Ayuvo/\(appVersion) (\(siteURL.absoluteString))"
    }
}
