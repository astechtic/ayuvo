import AppIntents
import SwiftUI
import WidgetKit

/// Lock Screen starters (docs/widgets.md "Lock Screen starters"): a circular "Start workout" widget
/// for one chosen sport and, on iOS 18+, a Control for Control Center or the Lock Screen. Both open
/// Ayuvo (GPS needs When-In-Use location in the foreground) and run the Workout widget's start path.

struct StartWorkoutWidgetConfiguration: WidgetConfigurationIntent {
    static var title: LocalizedStringResource = "Start Workout"
    static var description = IntentDescription("Choose the workout this Lock Screen button starts.")

    @Parameter(title: LocalizedStringResource("Workout", comment: "Lock Screen start widget parameter: which workout to start"), default: .run)
    var sport: WorkoutWidgetSport
}

struct WorkoutStartEntry: TimelineEntry {
    let date: Date
    let sport: WorkoutWidgetSport
    /// The running workout; the circle then shows its timer and opens it.
    let running: WorkoutWidgetState?
}

struct WorkoutStartProvider: AppIntentTimelineProvider {
    func placeholder(in context: Context) -> WorkoutStartEntry {
        WorkoutStartEntry(date: Date(), sport: .run, running: nil)
    }

    func snapshot(for configuration: StartWorkoutWidgetConfiguration, in context: Context) async -> WorkoutStartEntry {
        WorkoutStartEntry(date: Date(), sport: configuration.sport, running: WorkoutWidgetState.read())
    }

    func timeline(for configuration: StartWorkoutWidgetConfiguration, in context: Context) async -> Timeline<WorkoutStartEntry> {
        let now = Date()
        let entry = WorkoutStartEntry(date: now, sport: configuration.sport, running: WorkoutWidgetState.read(now: now))
        // The app reloads on every workout phase change; the fallback only catches a stale state.
        return Timeline(entries: [entry], policy: .after(now.addingTimeInterval(30 * 60)))
    }
}

struct WorkoutStartWidget: Widget {
    var body: some WidgetConfiguration {
        AppIntentConfiguration(kind: WorkoutWidgetState.startWidgetKind, intent: StartWorkoutWidgetConfiguration.self,
                               provider: WorkoutStartProvider()) { entry in
            WorkoutStartCircle(entry: entry)
                .widgetURL(entry.running == nil ? entry.sport.startURL : WorkoutWidgetSport.openURL)
                .containerBackground(.clear, for: .widget)
        }
        .configurationDisplayName("Start Workout")
        .description("Start a walk, run, ride, hike or strength session from your Lock Screen.")
        .supportedFamilies([.accessoryCircular])
    }
}

/// Lock Screen "Start Walk": the same circle fixed to Walk, so it shows in the Lock Screen gallery
/// without editing a Start Workout circle.
struct WalkStartProvider: TimelineProvider {
    func placeholder(in context: Context) -> WorkoutStartEntry {
        WorkoutStartEntry(date: Date(), sport: .walk, running: nil)
    }

    func getSnapshot(in context: Context, completion: @escaping (WorkoutStartEntry) -> Void) {
        completion(WorkoutStartEntry(date: Date(), sport: .walk, running: WorkoutWidgetState.read()))
    }

    func getTimeline(in context: Context, completion: @escaping (Timeline<WorkoutStartEntry>) -> Void) {
        let now = Date()
        let entry = WorkoutStartEntry(date: now, sport: .walk, running: WorkoutWidgetState.read(now: now))
        completion(Timeline(entries: [entry], policy: .after(now.addingTimeInterval(30 * 60))))
    }
}

struct WalkStartWidget: Widget {
    var body: some WidgetConfiguration {
        StaticConfiguration(kind: WorkoutWidgetState.startWalkWidgetKind, provider: WalkStartProvider()) { entry in
            WorkoutStartCircle(entry: entry)
                .widgetURL(entry.running == nil ? entry.sport.startURL : WorkoutWidgetSport.openURL)
                .containerBackground(.clear, for: .widget)
        }
        .configurationDisplayName("Start Walk")
        .description("Start a walk from your Lock Screen with one tap.")
        .supportedFamilies([.accessoryCircular])
    }
}

struct WorkoutStartCircle: View {
    let entry: WorkoutStartEntry

    var body: some View {
        ZStack {
            AccessoryWidgetBackground()
            if let running = entry.running {
                VStack(spacing: 0) {
                    Image(systemName: running.sport.systemImage)
                        .font(.system(size: 13, weight: .semibold))
                    Group {
                        if running.isTicking {
                            Text(timerInterval: running.timerStart...Date.distantFuture, countsDown: false)
                        } else {
                            Text(WorkoutWidgetState.duration(running.elapsed(at: running.updatedAt)))
                        }
                    }
                    .font(.system(size: 12, weight: .bold, design: .rounded))
                    .monospacedDigit()
                    .multilineTextAlignment(.center)
                    .lineLimit(1)
                    .minimumScaleFactor(0.6)
                    .widgetAccentable()
                }
                .padding(.horizontal, 4)
                .accessibilityElement(children: .combine)
                .accessibilityLabel(Text("Open \(running.sport.title)"))
            } else {
                VStack(spacing: 1) {
                    Image(systemName: entry.sport.systemImage)
                        .font(.system(size: 20, weight: .semibold))
                        .widgetAccentable()
                    Text(entry.sport.title)
                        .font(.system(size: 9, weight: .semibold, design: .rounded))
                        .lineLimit(1)
                        .minimumScaleFactor(0.6)
                }
                .padding(.horizontal, 4)
                .accessibilityElement(children: .combine)
                .accessibilityLabel(Text("Start \(entry.sport.title)"))
            }
        }
    }
}

// MARK: - Control (iOS 18+)

@available(iOS 18.0, *)
struct StartWorkoutControlConfiguration: ControlConfigurationIntent {
    static var title: LocalizedStringResource = "Start Workout"

    @Parameter(title: LocalizedStringResource("Workout", comment: "Start workout control parameter: which workout to start"), default: .run)
    var sport: WorkoutWidgetSport
}

/// Control Center / Lock Screen control. `StartWorkoutFromWidgetIntent` opens Ayuvo and leaves the
/// start route in the App Group, like the small Workout widget's buttons.
@available(iOS 18.0, *)
struct StartWorkoutControl: ControlWidget {
    static let kind = "com.ayuvo.health.StartWorkoutControl"

    var body: some ControlWidgetConfiguration {
        AppIntentControlConfiguration(kind: Self.kind, intent: StartWorkoutControlConfiguration.self) { configuration in
            ControlWidgetButton(action: StartWorkoutFromWidgetIntent(sport: configuration.sport)) {
                Label(configuration.sport.title, systemImage: configuration.sport.systemImage)
            }
        }
        .displayName("Start Workout")
        .description("Start a walk, run, ride, hike or strength session.")
    }
}

/// Control Center / Lock Screen control fixed to Walk.
@available(iOS 18.0, *)
struct StartWalkControl: ControlWidget {
    static let kind = "com.ayuvo.health.StartWalkControl"

    var body: some ControlWidgetConfiguration {
        StaticControlConfiguration(kind: Self.kind) {
            ControlWidgetButton(action: StartWorkoutFromWidgetIntent(sport: .walk)) {
                Label(WorkoutWidgetSport.walk.title, systemImage: WorkoutWidgetSport.walk.systemImage)
            }
        }
        .displayName("Start Walk")
        .description("Start a walk with one tap.")
    }
}

#if DEBUG
#Preview("Start workout", as: .accessoryCircular) {
    WorkoutStartWidget()
} timeline: {
    WorkoutStartEntry(date: .now, sport: .run, running: nil)
    WorkoutStartEntry(date: .now, sport: .strength, running: nil)
}
#endif
