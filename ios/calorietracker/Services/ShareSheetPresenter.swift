import UIKit

/// Presents the system share sheet directly via UIKit. A `UIActivityViewController` must be
/// *presented*, not embedded in a SwiftUI `.sheet` (doubly so from inside another sheet),
/// which renders blank. Extracted from `ExportDiaryView` so the health export can reuse it.
@MainActor
enum ShareSheetPresenter {
    static func present(url: URL, completion: (() -> Void)? = nil) {
        let controller = UIActivityViewController(activityItems: [url], applicationActivities: nil)
        if let completion {
            controller.completionWithItemsHandler = { _, _, _, _ in completion() }
        }
        guard let scene = UIApplication.shared.connectedScenes
                .compactMap({ $0 as? UIWindowScene })
                .first(where: { $0.activationState == .foregroundActive })
                ?? UIApplication.shared.connectedScenes.compactMap({ $0 as? UIWindowScene }).first,
              let window = scene.windows.first(where: { $0.isKeyWindow }) ?? scene.windows.first,
              var top = window.rootViewController
        else {
            completion?()
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
