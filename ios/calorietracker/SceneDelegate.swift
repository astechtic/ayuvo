import UIKit

/// Receives Home Screen quick actions for the scene-based SwiftUI lifecycle and asks for the
/// medication reminder background refresh whenever the app goes to the background.
final class SceneDelegate: NSObject, UIWindowSceneDelegate {
    func sceneDidEnterBackground(_ scene: UIScene) {
        MedicationBackgroundRefresh.schedule()
    }

    func scene(
        _ scene: UIScene,
        willConnectTo session: UISceneSession,
        options connectionOptions: UIScene.ConnectionOptions
    ) {
        guard let shortcutItem = connectionOptions.shortcutItem else { return }
        _ = QuickActionCoordinator.handle(shortcutItem)
    }

    func windowScene(
        _ windowScene: UIWindowScene,
        performActionFor shortcutItem: UIApplicationShortcutItem,
        completionHandler: @escaping (Bool) -> Void
    ) {
        completionHandler(QuickActionCoordinator.handle(shortcutItem))
    }
}
