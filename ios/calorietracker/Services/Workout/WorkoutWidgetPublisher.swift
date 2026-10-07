import Foundation
import WidgetKit

/// Writes the Workout widget state (`WorkoutWidgetState`, App Group) from the same content state the
/// Live Activity shows, and reloads the widget. `WorkoutLiveActivityController` calls it on every
/// start / update / end, so the GPS recorder, strength sessions and mirrored Apple Watch workouts all
/// reach the widget, even when Live Activities are turned off. Phase and lap changes are written at
/// once; distance and pace at most every 30 s (widget reloads are budgeted by the system).
@MainActor
final class WorkoutWidgetPublisher {
    static let shared = WorkoutWidgetPublisher()

    private var last: WorkoutWidgetState?
    private let interval: TimeInterval = 30

    private init() {}

    private var useMetric: Bool { Locale.current.measurementSystem == .metric }

    func publish(_ activity: WorkoutActivityAttributes.ContentState, force: Bool = false, now: Date = Date()) {
        let state = WorkoutWidgetState(activity: activity, useMetric: useMetric, updatedAt: now)
        guard force || WorkoutWidgetState.needsWrite(previous: last, next: state, interval: interval) else { return }
        last = state
        WorkoutWidgetState.write(state)
        reload()
    }

    func clear() {
        let hadState = last != nil || WorkoutWidgetState.read() != nil
        last = nil
        WorkoutWidgetState.clear()
        if hadState { reload() }
    }

    private func reload() {
        WidgetCenter.shared.reloadTimelines(ofKind: WorkoutWidgetState.widgetKind)
        WidgetCenter.shared.reloadTimelines(ofKind: WorkoutWidgetState.startWidgetKind)
        WidgetCenter.shared.reloadTimelines(ofKind: WorkoutWidgetState.startWalkWidgetKind)
    }
}
