import Foundation
import UIKit

/// Shares one or more logged meals as a plain-text summary (plus the meal photo when a single
/// entry has one) through the system share sheet. Nothing is uploaded and no link is minted.
enum MealShare {
    /// Human-readable summary — the text put on the share sheet.
    nonisolated static func shareText(for entries: [FoodEntry]) -> String {
        entries.map { e -> String in
            let macros = "\(Int(e.protein.rounded()))P · \(Int(e.carbs.rounded()))C · \(Int(e.fat.rounded()))F"
            let prefix = e.emoji.map { "\($0) " } ?? ""
            return "\(prefix)\(e.name) — \(e.calories) kcal · \(macros)"
        }.joined(separator: "\n")
    }

    // MARK: - Share sheet

    /// Present the system share sheet directly via UIKit (avoids the blank-page issue of a
    /// SwiftUI `.sheet` nested inside another sheet).
    static func presentShareSheet(for entries: [FoodEntry]) {
        guard !entries.isEmpty else { return }
        var items: [Any] = [shareText(for: entries)]
        if entries.count == 1,
           let filename = entries[0].imageFilename,
           let image = FoodImageStore.shared.loadForViewer(filename: filename) {
            items.append(image)
        }
        presentShareItems(items)
    }

    private static func presentShareItems(_ items: [Any]) {
        let av = UIActivityViewController(activityItems: items, applicationActivities: nil)
        guard let scene = UIApplication.shared.connectedScenes.first(where: { $0.activationState == .foregroundActive }) as? UIWindowScene,
              let root = scene.windows.first(where: { $0.isKeyWindow })?.rootViewController else { return }
        var top = root
        while let presented = top.presentedViewController { top = presented }
        av.popoverPresentationController?.sourceView = top.view
        av.popoverPresentationController?.sourceRect = CGRect(x: top.view.bounds.midX, y: top.view.bounds.midY, width: 0, height: 0)
        av.popoverPresentationController?.permittedArrowDirections = []
        top.present(av, animated: true)
    }
}
