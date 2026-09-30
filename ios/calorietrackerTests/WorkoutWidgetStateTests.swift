import Foundation
import SwiftUI
import Testing
@testable import calorietracker

/// Workout widget state (docs/widgets.md "Workout widget"): the Codable file the app writes, the
/// mapping from the Live Activity state, the write throttle and the widget's deep links.
@MainActor
struct WorkoutWidgetStateTests {
    private let t0 = Date(timeIntervalSince1970: 1_790_000_000)

    private func activity(
        kind: String = "gps", sport: String = "run", state: String = "running",
        pausedElapsed: Double? = nil, source: String? = nil
    ) -> WorkoutActivityAttributes.ContentState {
        WorkoutActivityAttributes.ContentState(
            kind: kind, sport: sport, state: state, timerStart: t0, pausedElapsed: pausedElapsed,
            distanceM: 5_000, paceSecondsPerKm: 300, speedMps: 3.3, heartRate: 150, lapCount: 2, source: source
        )
    }

    // MARK: Codable

    @Test func encodeDecodeRoundTrips() throws {
        let state = WorkoutWidgetState(activity: activity(state: "paused", pausedElapsed: 900), useMetric: false, updatedAt: t0)
        let data = try JSONEncoder().encode(state)
        #expect(WorkoutWidgetState.decode(data) == state)
    }

    @Test func decodeRejectsNewerVersionsAndGarbage() throws {
        var state = WorkoutWidgetState(activity: activity(), useMetric: true, updatedAt: t0)
        state.version = WorkoutWidgetState.currentVersion + 1
        #expect(WorkoutWidgetState.decode(try JSONEncoder().encode(state)) == nil)
        #expect(WorkoutWidgetState.decode(Data("{}".utf8)) == nil)
        #expect(WorkoutWidgetState.decode(Data("not json".utf8)) == nil)
        // An unknown sport id (a newer app) reads as "no workout" instead of a wrong one.
        let json = String(decoding: try JSONEncoder().encode(WorkoutWidgetState(activity: activity(), useMetric: true, updatedAt: t0)), as: UTF8.self)
            .replacingOccurrences(of: "\"run\"", with: "\"rowing\"")
        #expect(WorkoutWidgetState.decode(Data(json.utf8)) == nil)
    }

    // MARK: Mapping from the Live Activity state

    @Test func gpsStatesMapToPhases() {
        let running = WorkoutWidgetState(activity: activity(), useMetric: true, updatedAt: t0)
        #expect(running.sport == .run)
        #expect(running.phase == .recording)
        #expect(running.isGPS)
        #expect(running.isTicking)
        #expect(running.distanceM == 5_000)
        #expect(running.paceSecondsPerKm == 300)
        #expect(running.lapCount == 2)
        #expect(running.timerStart == t0)
        #expect(!running.fromWatch)

        let paused = WorkoutWidgetState(activity: activity(sport: "cycle", state: "paused", pausedElapsed: 600), useMetric: true)
        #expect(paused.sport == .cycle)
        #expect(paused.phase == .paused)
        #expect(!paused.isTicking)
        #expect(paused.elapsed(at: t0.addingTimeInterval(5_000)) == 600)

        let recovery = WorkoutWidgetState(activity: activity(sport: "hike", state: "recovery", pausedElapsed: 1_200), useMetric: true)
        #expect(recovery.phase == .recovery)
        #expect(recovery.elapsed(at: t0.addingTimeInterval(9_999)) == 1_200)

        let unknownState = WorkoutWidgetState(activity: activity(sport: "walk", state: "something"), useMetric: true)
        #expect(unknownState.sport == .walk)
        #expect(unknownState.phase == .recording)
        #expect(unknownState.elapsed(at: t0.addingTimeInterval(90)) == 90)
    }

    @Test func strengthDropsGpsFieldsAndUnknownGpsSportFallsBackToRun() {
        let strength = WorkoutWidgetState(activity: activity(kind: "strength", sport: "strength"), useMetric: true)
        #expect(strength.sport == .strength)
        #expect(!strength.isGPS)
        #expect(strength.distanceM == nil)
        #expect(strength.paceSecondsPerKm == nil)
        #expect(strength.lapCount == 0)

        #expect(WorkoutWidgetState(activity: activity(sport: "rowing"), useMetric: true).sport == .run)
        #expect(WorkoutWidgetState(activity: activity(sport: "strength"), useMetric: true).sport == .run)
        #expect(WorkoutWidgetState(activity: activity(source: "watch"), useMetric: true).fromWatch)
    }

    @Test func coordinatorStrengthStateMapsToStrength() {
        let active = StrengthWorkoutStore.ActiveStrengthSession(startedAt: t0, diaryDateKey: StrengthWorkoutDate.key(for: t0))
        let state = WorkoutWidgetState(activity: WorkoutSessionCoordinator.shared.strengthState(active), useMetric: true, updatedAt: t0)
        #expect(state.sport == .strength)
        #expect(state.phase == .recording)
        #expect(state.timerStart == t0)
    }

    // MARK: Write throttle

    @Test func phaseChangesWriteAtOnceDistanceEveryThirtySeconds() {
        let first = WorkoutWidgetState(activity: activity(), useMetric: true, updatedAt: t0)
        #expect(WorkoutWidgetState.needsWrite(previous: nil, next: first))

        var moved = WorkoutWidgetState(activity: activity(), useMetric: true, updatedAt: t0.addingTimeInterval(10))
        moved.distanceM = 5_050
        #expect(!WorkoutWidgetState.needsWrite(previous: first, next: moved))
        moved.updatedAt = t0.addingTimeInterval(30)
        #expect(WorkoutWidgetState.needsWrite(previous: first, next: moved))

        let paused = WorkoutWidgetState(activity: activity(state: "paused", pausedElapsed: 10), useMetric: true, updatedAt: t0.addingTimeInterval(1))
        #expect(WorkoutWidgetState.needsWrite(previous: first, next: paused))

        var lapped = WorkoutWidgetState(activity: activity(), useMetric: true, updatedAt: t0.addingTimeInterval(1))
        lapped.lapCount = 3
        #expect(WorkoutWidgetState.needsWrite(previous: first, next: lapped))

        var resumed = WorkoutWidgetState(activity: activity(), useMetric: true, updatedAt: t0.addingTimeInterval(1))
        resumed.timerStart = t0.addingTimeInterval(45) // a pause shifted the anchor
        #expect(WorkoutWidgetState.needsWrite(previous: first, next: resumed))
    }

    @Test func stateGoesStaleAfterADay() {
        let state = WorkoutWidgetState(activity: activity(), useMetric: true, updatedAt: t0)
        #expect(!state.isStale(at: t0.addingTimeInterval(23 * 3600)))
        #expect(state.isStale(at: t0.addingTimeInterval(25 * 3600)))
    }

    // MARK: Formatting

    @Test func distanceAndPaceFollowUnits() {
        let metric = WorkoutWidgetState(activity: activity(), useMetric: true)
        #expect(metric.distanceText == "5.00 km")
        #expect(metric.paceText == "5:00 /km")
        let imperial = WorkoutWidgetState(activity: activity(), useMetric: false)
        #expect(imperial.distanceText == "3.11 mi")
        #expect(imperial.paceText == "8:02 /mi")
        let cycle = WorkoutWidgetState(activity: activity(sport: "cycle"), useMetric: true)
        #expect(cycle.paceText == "11.9 km/h")
        var noPace = metric
        noPace.paceSecondsPerKm = nil
        noPace.distanceM = nil
        #expect(noPace.paceText == "—")
        #expect(noPace.distanceText == "—")
        #expect(WorkoutWidgetState.duration(3_725) == "1:02:05")
    }

    // MARK: Sports and deep links

    @Test func gpsSportsMatchTheRecorder() {
        let gps = WorkoutWidgetSport.allCases.filter(\.isGPS)
        #expect(gps.map(\.rawValue) == OutdoorSport.allCases.map(\.rawValue))
        for sport in gps {
            #expect(sport.systemImage == OutdoorSport(rawValue: sport.rawValue)?.systemImage)
        }
    }

    @Test func workoutLinksParseAndRoundTrip() {
        for sport in WorkoutWidgetSport.allCases {
            #expect(WidgetDeepLink(url: sport.startURL) == .startWorkout(sport))
            #expect(sport.startURL.absoluteString == "ayuvo://workout/start?sport=\(sport.rawValue)")
            let link = WidgetDeepLink.startWorkout(sport)
            #expect(WidgetDeepLink(storageValue: link.storageValue) == link)
            #expect(WidgetRouteAction.resolve(link) == .startWorkout(sport))
        }
        #expect(WidgetDeepLink(url: WorkoutWidgetSport.openURL) == .openWorkout)
        #expect(WidgetDeepLink(storageValue: WidgetDeepLink.openWorkout.storageValue) == .openWorkout)
        #expect(WidgetRouteAction.resolve(.openWorkout) == .openWorkout)

        #expect(WidgetDeepLink(url: URL(string: "ayuvo://workout/start?sport=rowing")!) == nil)
        #expect(WidgetDeepLink(url: URL(string: "ayuvo://workout/start")!) == nil)
        #expect(WidgetDeepLink(url: URL(string: "ayuvo://workout/stop?sport=run")!) == nil)
        #expect(WidgetDeepLink(storageValue: "workout:start:rowing") == nil)
    }

    @Test func openWorkoutLandsOnTheWorkoutLog() {
        let navigator = AppNavigator()
        navigator.openWorkout(starting: nil)
        #expect(navigator.selectedTab == .browse)
        #expect(navigator.browsePath.count == 2)
        #expect(navigator.workoutLogSession.liveWorkoutRequested)
        #expect(!navigator.workoutLogSession.gpsStartRequested)
    }
}
