import Foundation
import Testing

/// The widget extension compiles its own copies of the shared widget model files (the targets
/// share no sources); they must stay byte-identical to the app's originals (docs/widgets.md).
struct WidgetSharedCopiesTests {
    private var iosRoot: URL {
        URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
    }

    @Test(arguments: ["WidgetOptions.swift", "WidgetDashboardSnapshot.swift"])
    func copiesAreByteIdentical(_ name: String) throws {
        let original = try Data(contentsOf: iosRoot.appendingPathComponent("calorietracker/Widgets/Shared/\(name)"))
        let copy = try Data(contentsOf: iosRoot.appendingPathComponent("FudAIWidgets/Shared/\(name)"))
        #expect(original == copy, "FudAIWidgets/Shared/\(name) differs from calorietracker/Widgets/Shared/\(name)")
    }
}
