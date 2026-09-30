import Foundation
import HealthKit
import Observation

/// Messages between the Apple Watch workout and the iPhone over the mirrored `HKWorkoutSession`.
/// Keep in sync with `FudAIWatchApp/WatchWorkoutManager.swift`.
nonisolated struct WatchWorkoutMessage: Codable, Sendable {
    /// "metrics", "summary" or "command".
    var type: String
    var sessionID: String?
    var sport: String?
    var state: String?
    var elapsed: Double?
    var distance: Double?
    var heartRate: Double?
    var energy: Double?
    var laps: Int?
    var start: Double?
    var end: Double?
    var avgHeartRate: Double?
    var maxHeartRate: Double?
    var command: String?
}

/// iPhone side of an Apple Watch workout: receives the mirrored session, shows its metrics in the app and the
/// Live Activity, forwards Pause/Resume/Lap/End, and logs the diary session when the watch finishes. The watch
/// saves the HKWorkout, so while it runs the phone recorder does not save its own.
@MainActor
@Observable
final class WatchWorkoutMirror: NSObject {
    static let shared = WatchWorkoutMirror()

    private(set) var isMirroring = false
    private(set) var latest: WatchWorkoutMessage?
    @ObservationIgnored private var session: HKWorkoutSession?
    @ObservationIgnored private var installed = false
    @ObservationIgnored private var receivedAt = Date()

    /// Installs the mirroring start handler. Must run early at launch so a mirrored session started while
    /// the app was not running is delivered.
    func install() {
        guard !installed, HKHealthStore.isHealthDataAvailable() else { return }
        installed = true
        HealthKitManager.sharedHealthStore.workoutSessionMirroringStartHandler = { mirrored in
            Task { @MainActor in WatchWorkoutMirror.shared.begin(mirrored) }
        }
    }

    /// Launches the Ayuvo watch app into a workout of `sport` (the watch starts and mirrors it).
    func startOnWatch(sport: OutdoorSport) async -> Bool {
        let configuration = HKWorkoutConfiguration()
        configuration.activityType = WorkoutHealthWriter.activityType(
            forConfigName: WorkoutConfig.shared.sports[sport.rawValue]?.hkActivity ?? "")
        configuration.locationType = .outdoor
        return await withCheckedContinuation { continuation in
            HealthKitManager.sharedHealthStore.startWatchApp(with: configuration) { success, _ in
                continuation.resume(returning: success)
            }
        }
    }

    func send(_ command: WorkoutLiveActivityCommand) {
        guard let session else { return }
        let message = WatchWorkoutMessage(type: "command", command: command.rawValue)
        guard let data = try? JSONEncoder().encode(message) else { return }
        session.sendToRemoteWorkoutSession(data: data) { _, _ in }
    }

    private func begin(_ mirrored: HKWorkoutSession) {
        session = mirrored
        mirrored.delegate = self
        isMirroring = true
        OutdoorWorkoutRecorder.shared.watchSessionActive = true
    }

    private func receive(_ message: WatchWorkoutMessage) {
        receivedAt = Date()
        switch message.type {
        case "metrics":
            latest = message
            OutdoorWorkoutRecorder.shared.watchHeartRate = message.heartRate.map { Int($0.rounded()) }
            let recorder = OutdoorWorkoutRecorder.shared
            if recorder.isActive {
                WorkoutLiveActivityController.shared.update(recorder.liveActivityState())
            } else {
                let state = liveActivityState(message)
                if WorkoutLiveActivityController.shared.isActive {
                    WorkoutLiveActivityController.shared.update(state)
                } else {
                    let title = OutdoorSport(rawValue: message.sport ?? "")?.title ?? "Workout"
                    WorkoutLiveActivityController.shared.start(title: title, state: state)
                }
            }
        case "summary":
            logDiarySession(message)
        default:
            break
        }
    }

    private func liveActivityState(_ m: WatchWorkoutMessage) -> WorkoutActivityAttributes.ContentState {
        let elapsed = m.elapsed ?? 0
        let paused = m.state == "paused"
        let distance = m.distance ?? 0
        return WorkoutActivityAttributes.ContentState(
            kind: "gps", sport: m.sport ?? "run", state: paused ? "paused" : "running",
            timerStart: receivedAt.addingTimeInterval(-elapsed), pausedElapsed: paused ? elapsed : nil,
            distanceM: m.distance, paceSecondsPerKm: distance > 10 && elapsed > 0 ? elapsed / (distance / 1000) : nil,
            speedMps: elapsed > 0 ? distance / elapsed : nil, heartRate: m.heartRate.map { Int($0.rounded()) },
            lapCount: m.laps ?? 0, source: "watch"
        )
    }

    /// The watch saved the HKWorkout; the diary gets its own session unless the phone recorded GPS itself.
    private func logDiarySession(_ m: WatchWorkoutMessage) {
        guard !OutdoorWorkoutRecorder.shared.isActive, let startValue = m.start, let endValue = m.end,
              endValue > startValue, let store = OutdoorWorkoutRecorder.shared.workoutStore else { return }
        let start = Date(timeIntervalSince1970: startValue), end = Date(timeIntervalSince1970: endValue)
        let id = m.sessionID.flatMap(UUID.init(uuidString:)) ?? UUID()
        guard store.session(id: id) == nil else { return }
        let elapsed = m.elapsed ?? end.timeIntervalSince(start)
        let distance = m.distance ?? 0
        var heart: WorkoutHeartRateSummary?
        if let avg = m.avgHeartRate {
            heart = WorkoutHeartRateSummary(HeartRateWorkout.WorkoutResult(
                avgHr: avg, maxHr: m.maxHeartRate, coveragePct: 100, zoneSeconds: [0, 0, 0, 0, 0], trimp: nil, kcal: nil,
                zoneMethod: "hrmax"))
        }
        let sport = m.sport ?? "run"
        let weight = OutdoorWorkoutRecorder.shared.bodyWeightKg()
        let metID = WorkoutConfig.shared.sports[sport]?.metItemId ?? "Walking_Outdoor"
        let kcal = m.energy.map { Int($0.rounded()) }.flatMap { $0 > 0 ? $0 : nil }
            ?? StrengthWorkoutBurnEstimator.metCalories(
                met: StrengthWorkoutBurnEstimator.cardioMET(itemID: metID, intensity: .moderate),
                bodyWeightKg: weight, seconds: elapsed)
        let summary = OutdoorWorkoutSummary(
            sport: sport, distanceM: distance, movingSeconds: elapsed, elapsedSeconds: elapsed,
            avgSpeedMps: elapsed > 0 ? distance / elapsed : nil,
            avgPaceSecondsPerKm: distance > 0 ? (elapsed / (distance / 1000)).rounded() : nil,
            maxSpeedMps: nil, splits: [], elevationGainM: 0, elevationLossM: 0, recordedOn: "watch",
            hasRoute: false, energyMethod: m.energy != nil ? "watch" : "met")
        let session = StrengthWorkoutSession(
            id: id, diaryDate: Calendar.current.startOfDay(for: start), diaryDateKey: StrengthWorkoutDate.key(for: start),
            startedAt: start, completedAt: end, durationSeconds: max(1, Int(elapsed.rounded())), exercises: [],
            caloriesBurned: kcal, healthSyncVersion: 1, heartRate: heart, outdoor: summary)
        store.addOutdoorSession(session)
    }

    private func ended() {
        isMirroring = false
        session = nil
        latest = nil
        OutdoorWorkoutRecorder.shared.watchSessionActive = false
        OutdoorWorkoutRecorder.shared.watchHeartRate = nil
        if !OutdoorWorkoutRecorder.shared.isActive { WorkoutLiveActivityController.shared.end() }
    }
}

extension WatchWorkoutMirror: HKWorkoutSessionDelegate {
    nonisolated func workoutSession(_ workoutSession: HKWorkoutSession, didChangeTo toState: HKWorkoutSessionState,
                                    from fromState: HKWorkoutSessionState, date: Date) {
        guard toState == .ended || toState == .stopped else { return }
        // Give the final summary message a moment to arrive before tearing down.
        Task { @MainActor in
            try? await Task.sleep(for: .seconds(toState == .ended ? 3 : 10))
            WatchWorkoutMirror.shared.ended()
        }
    }

    nonisolated func workoutSession(_ workoutSession: HKWorkoutSession, didFailWithError error: Error) {}

    nonisolated func workoutSession(_ workoutSession: HKWorkoutSession, didReceiveDataFromRemoteWorkoutSession data: [Data]) {
        let messages = data.compactMap { try? JSONDecoder().decode(WatchWorkoutMessage.self, from: $0) }
        Task { @MainActor in messages.forEach { WatchWorkoutMirror.shared.receive($0) } }
    }

    nonisolated func workoutSession(_ workoutSession: HKWorkoutSession, didDisconnectFromRemoteDeviceWithError error: Error?) {
        Task { @MainActor in WatchWorkoutMirror.shared.ended() }
    }
}
