import UIKit

/// Presents the system share sheet directly via UIKit. A `UIActivityViewController` must be
/// *presented*, not embedded in a SwiftUI `.sheet` (doubly so from inside another sheet),
/// which renders blank. Extracted from `ExportDiaryView` so the health export can reuse it.
@MainActor
enum ShareSheetPresenter {
    static func present(url: URL, completion: (() -> Void)? = nil) {
        present(urls: [url]) { _ in completion?() }
    }

    /// Several files at once (the Health Records share flow produces a summary plus originals).
    /// `completion` receives the system's `completed` flag, so a cancelled share can be told from
    /// a finished one (Health Records only counts a finished share).
    static func present(urls: [URL], completion: ((Bool) -> Void)? = nil) {
        present(items: urls, completion: completion)
    }

    /// Arbitrary activity items (Health Records sends the summary text alongside the files).
    static func present(items: [Any], completion: ((Bool) -> Void)? = nil) {
        guard !items.isEmpty else {
            completion?(false)
            return
        }
        let controller = UIActivityViewController(activityItems: items, applicationActivities: nil)
        if let completion {
            controller.completionWithItemsHandler = { _, completed, _, _ in completion(completed) }
        }
        guard let scene = UIApplication.shared.connectedScenes
                .compactMap({ $0 as? UIWindowScene })
                .first(where: { $0.activationState == .foregroundActive })
                ?? UIApplication.shared.connectedScenes.compactMap({ $0 as? UIWindowScene }).first,
              let window = scene.windows.first(where: { $0.isKeyWindow }) ?? scene.windows.first,
              var top = window.rootViewController
        else {
            completion?(false)
            return
        }
        while let presented = top.presentedViewController { top = presented }
        if let pop = controller.popoverPresentationController {
            pop.sourceView = top.view
            pop.sourceRect = CGRect(x: top.view.bounds.midX, y: top.view.bounds.maxY - 40, width: 0, height: 0)
            pop.permittedArrowDirections = []
        }
        top.present(controller, animated: true)
    }
}
