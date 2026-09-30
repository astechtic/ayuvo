import HealthKit
import SwiftUI
import WatchKit

/// Sport picker and live workout screen on Apple Watch.
struct WatchWorkoutView: View {
    @State private var manager = WatchWorkoutManager.shared

    var body: some View {
        if manager.isActive {
            WatchWorkoutLiveView(manager: manager)
        } else {
            List {
                Section {
                    ForEach(WatchWorkoutManager.Sport.allCases) { sport in
                        Button {
                            Task { await manager.start(sport) }
                        } label: {
                            Label(sport.title, systemImage: sport.systemImage)
                        }
                    }
                } header: {
                    Text("Start workout")
                } footer: {
                    if let error = manager.errorMessage {
                        Text(error).foregroundStyle(.red)
                    } else {
                        Text("Saved to Apple Health with your route. Your iPhone shows it live.")
                    }
                }
            }
        }
    }
}

private struct WatchWorkoutLiveView: View {
    let manager: WatchWorkoutManager

    var body: some View {
        TabView {
            TimelineView(.periodic(from: .now, by: 1)) { context in
                VStack(alignment: .leading, spacing: 4) {
                    Label(manager.sport.title, systemImage: manager.sport.systemImage)
                        .font(.footnote)
                        .foregroundStyle(.green)
                    Text(duration(manager.elapsed(at: context.date)))
                        .font(.system(.title, design: .rounded).monospacedDigit().weight(.semibold))
                        .foregroundStyle(manager.state == .paused ? .orange : .primary)
                    Text(String(format: "%.2f km", manager.distance / 1000))
                        .font(.title3.monospacedDigit())
                    Text(manager.heartRate.map { "\(Int($0.rounded())) bpm" } ?? "-- bpm")
                        .font(.title3.monospacedDigit())
                        .foregroundStyle(.red)
                    Text("\(Int(manager.energy.rounded())) kcal")
                        .font(.body.monospacedDigit())
                        .foregroundStyle(.secondary)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
            }

            VStack(spacing: 8) {
                if manager.isFinishing {
                    ProgressView("Saving…")
                } else {
                    HStack {
                        if manager.state == .paused {
                            controlButton("Resume", "play.fill", .green) { manager.resume() }
                        } else {
                            controlButton("Pause", "pause.fill", .orange) { manager.pause() }
                        }
                        controlButton("Lap", "flag.fill", .blue) { manager.lap() }
                    }
                    controlButton("End", "stop.fill", .red) { manager.end() }
                }
            }
        }
        .tabViewStyle(.verticalPage)
    }

    private func controlButton(_ title: String, _ icon: String, _ color: Color, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            VStack(spacing: 2) {
                Image(systemName: icon)
                Text(title).font(.caption2)
            }
            .frame(maxWidth: .infinity)
        }
        .tint(color)
    }

    private func duration(_ seconds: TimeInterval) -> String {
        let total = max(0, Int(seconds))
        let h = total / 3600, m = (total % 3600) / 60, s = total % 60
        return h > 0 ? String(format: "%d:%02d:%02d", h, m, s) : String(format: "%d:%02d", m, s)
    }
}

/// Receives workouts started from the iPhone (`HKHealthStore.startWatchApp(with:)`).
final class AyuvoWatchAppDelegate: NSObject, WKApplicationDelegate {
    func handle(_ workoutConfiguration: HKWorkoutConfiguration) {
        WatchWorkoutManager.shared.start(configuration: workoutConfiguration)
    }
}
