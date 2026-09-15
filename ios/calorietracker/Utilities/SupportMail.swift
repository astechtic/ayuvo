import Foundation
import UIKit

/// Opens the user's mail client with a support or feedback message whose subject and body are
/// prefilled with the app version, build, iOS version, device model and locale. No server is involved.
enum SupportMail {
    nonisolated enum Kind {
        case support
        case feedback

        var subject: String {
            switch self {
            case .support: String(localized: "Ayuvo support request")
            case .feedback: String(localized: "Ayuvo feedback")
            }
        }
    }

    nonisolated static var deviceModelIdentifier: String {
        var systemInfo = utsname()
        uname(&systemInfo)
        let mirror = Mirror(reflecting: systemInfo.machine)
        let identifier = mirror.children.reduce(into: "") { result, element in
            guard let value = element.value as? Int8, value != 0 else { return }
            result.append(Character(UnicodeScalar(UInt8(value))))
        }
        return identifier.isEmpty ? "unknown" : identifier
    }

    nonisolated static func diagnostics(
        version: String = AppLinks.appVersion,
        build: String = AppLinks.buildNumber,
        systemVersion: String = ProcessInfo.processInfo.operatingSystemVersionString,
        device: String = deviceModelIdentifier,
        locale: String = Locale.current.identifier
    ) -> String {
        """
        ---
        Ayuvo \(version) (\(build))
        iOS \(systemVersion)
        Device \(device)
        Locale \(locale)
        """
    }

    nonisolated static func url(for kind: Kind, diagnostics: String = diagnostics()) -> URL? {
        let allowed = CharacterSet.alphanumerics.union(CharacterSet(charactersIn: "-._~"))
        func encode(_ value: String) -> String {
            value.addingPercentEncoding(withAllowedCharacters: allowed) ?? ""
        }
        let body = "\n\n\n" + diagnostics
        return URL(string: "mailto:\(AppLinks.supportEmail)?subject=\(encode(kind.subject))&body=\(encode(body))")
    }

    /// Opens the mail client. Returns `false` (after copying the address to the pasteboard)
    /// when no mail client can handle `mailto:` so the caller can show an alert.
    @MainActor
    static func open(_ kind: Kind, completion: ((Bool) -> Void)? = nil) {
        guard let url = url(for: kind) else {
            completion?(false)
            return
        }
        UIApplication.shared.open(url) { opened in
            if !opened {
                UIPasteboard.general.string = AppLinks.supportEmail
            }
            completion?(opened)
        }
    }
}
