import SwiftUI

@main
struct AyuvoWatchApp: App {
    @WKApplicationDelegateAdaptor(AyuvoWatchAppDelegate.self) private var appDelegate
    @StateObject private var receiver = WatchSnapshotReceiver()
    @Environment(\.scenePhase) private var scenePhase
    @State private var workout = WatchWorkoutManager.shared

    var body: some Scene {
        WindowGroup {
            Group {
                if workout.isActive {
                    WatchWorkoutView()
                } else {
                    TabView {
                        WatchNutritionView()
                            .environmentObject(receiver)
                        WatchWorkoutView()
                    }
                    .tabViewStyle(.verticalPage)
                }
            }
        }
        .onChange(of: scenePhase) { _, phase in
            if phase == .active {
                receiver.activate()
            }
        }
    }
}
