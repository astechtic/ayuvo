import ActivityKit
import Foundation

/// Starts, updates and ends the workout Live Activity. One activity at a time: a GPS workout or a
/// strength session. Updates are throttled (state changes go through immediately). Every call also
/// feeds the home-screen Workout widget (`WorkoutWidgetPublisher`), before the Live Activity checks.
@MainActor
final class WorkoutLiveActivityController {
    static let shared = WorkoutLiveActivityController()

    private var activity: Activity<WorkoutActivityAttributes>?
    private var lastState: WorkoutActivityAttributes.ContentState?
    private var lastUpdate = Date.distantPast
    private let minimumInterval: TimeInterval = 5

    var isActive: Bool { activity != nil }

    func start(title: String, state: WorkoutActivityAttributes.ContentState) {
        WorkoutWidgetPublisher.shared.publish(state, force: true)
        guard ActivityAuthorizationInfo().areActivitiesEnabled else { return }
        // Adopt (or clear) activities left over from a previous process.
        if activity == nil, let existing = Activity<WorkoutActivityAttributes>.activities.first {
            activity = existing
        }
        if activity != nil {
            update(state, force: true)
            return
        }
        let attributes = WorkoutActivityAttributes(title: title, useMetric: Locale.current.measurementSystem == .metric)
        do {
            activity = try Activity.request(attributes: attributes, content: .init(state: state, staleDate: nil), pushType: nil)
            lastState = state
            lastUpdate = Date()
        } catch {
            activity = nil
        }
    }

    func update(_ state: WorkoutActivityAttributes.ContentState, force: Bool = false) {
        WorkoutWidgetPublisher.shared.publish(state, force: force)
        guard let activity else { return }
        let stateChanged = lastState?.state != state.state || lastState?.lapCount != state.lapCount
        guard force || stateChanged || Date().timeIntervalSince(lastUpdate) >= minimumInterval else { return }
        lastState = state
        lastUpdate = Date()
        Task { await activity.update(.init(state: state, staleDate: nil)) }
    }

    func end(final state: WorkoutActivityAttributes.ContentState? = nil) {
        WorkoutWidgetPublisher.shared.clear()
        let current = activity
        activity = nil
        lastState = nil
        Task {
            let content = state.map { ActivityContent(state: $0, staleDate: nil) }
            await current?.end(content, dismissalPolicy: .immediate)
            // Also end any orphan from a crashed process.
            for orphan in Activity<WorkoutActivityAttributes>.activities where orphan.id != current?.id {
                await orphan.end(nil, dismissalPolicy: .immediate)
            }
        }
    }
}
