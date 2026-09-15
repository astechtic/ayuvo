import SwiftUI

@main
struct AyuvoWatchApp: App {
    @StateObject private var receiver = WatchSnapshotReceiver()
    @Environment(\.scenePhase) private var scenePhase

    var body: some Scene {
        WindowGroup {
            WatchNutritionView()
                .environmentObject(receiver)
        }
        .onChange(of: scenePhase) { _, phase in
            if phase == .active {
                receiver.activate()
            }
        }
    }
}
