import Foundation
import Observation

/// App-side glue for workouts that run outside a single screen: strength sessions started with
/// Start session (persisted in `StrengthWorkoutStore.activeSession`), the GPS recorder, the workout
/// Live Activity and its buttons. Installed once from the app (`attach`).
@MainActor
@Observable
final class WorkoutSessionCoordinator {
    static let shared = WorkoutSessionCoordinator()

    @ObservationIgnored private weak var store: StrengthWorkoutStore?
    @ObservationIgnored private var bodyWeight: () -> Double = { UserProfile.load()?.weightKg ?? 70 }
    private(set) var isFinishingStrength = false
    /// A widget "Strength" tap that arrived before `attach` (cold launch): started once the store is in.
    @ObservationIgnored private var pendingWidgetStrengthStart = false

    private init() {}

    func attach(store: StrengthWorkoutStore, bodyWeightKg: @escaping () -> Double) {
        self.store = store
        bodyWeight = bodyWeightKg
        let recorder = OutdoorWorkoutRecorder.shared
        recorder.workoutStore = store
        recorder.bodyWeightKg = bodyWeightKg
        recorder.restoreIfNeeded()
        installLiveActivityHandler()
        // Re-adopt the strength Live Activity after a relaunch.
        if let active = store.activeSession, !recorder.isActive {
            WorkoutLiveActivityController.shared.start(title: "Strength session", state: strengthState(active))
        }
        WatchWorkoutMirror.shared.install()
        if pendingWidgetStrengthStart {
            pendingWidgetStrengthStart = false
            startFromWidget(.strength)
        }
        syncWidget()
    }

    /// Rewrites the Workout widget state from what is really running, dropping a state left by a
    /// process that died mid-workout (an interrupted GPS workout shows as paused).
    func syncWidget() {
        let recorder = OutdoorWorkoutRecorder.shared
        if recorder.isActive {
            WorkoutWidgetPublisher.shared.publish(recorder.liveActivityState(), force: true)
        } else if WatchWorkoutMirror.shared.isMirroring {
            return // the next watch metrics message publishes
        } else if let active = store?.activeSession {
            WorkoutWidgetPublisher.shared.publish(strengthState(active), force: true)
        } else {
            WorkoutWidgetPublisher.shared.clear()
        }
    }

    /// Workout widget start (docs/widgets.md "Workout widget"): starts the GPS recorder or a strength
    /// session unless a workout already runs. The app is in the foreground (the widget opened it), so
    /// the When-In-Use location prompt can show. Returns whether a workout of that kind is running now.
    @discardableResult
    func startFromWidget(_ sport: WorkoutWidgetSport) -> Bool {
        let recorder = OutdoorWorkoutRecorder.shared
        // An unfinished GPS workout from a previous process takes precedence over a new one.
        recorder.restoreIfNeeded()
        if recorder.isActive || WatchWorkoutMirror.shared.isMirroring {
            syncWidget()
            return true
        }
        if let outdoor = OutdoorSport(rawValue: sport.rawValue) {
            if store?.activeSession != nil { return false }
            recorder.start(sport: outdoor, cooperTest: false)
            return recorder.isActive
        }
        guard let store else {
            pendingWidgetStrengthStart = true
            return true
        }
        if store.activeSession == nil { startStrengthSession(on: .now) }
        return store.activeSession != nil
    }

    /// Also called from `AppDelegate` at launch: a Live Activity button can launch the app in the
    /// background, where no scene becomes active and `attach` does not run.
    func installLiveActivityHandler() {
        WorkoutLiveActivityBridge.handler = { command in
            await WorkoutSessionCoordinator.shared.handle(command)
        }
    }

    private var weightUnit: WeightUnit {
        WeightUnit(rawValue: UserDefaults.standard.string(forKey: WeightUnit.storageKey) ?? "") ?? .lbs
    }

    func handle(_ command: WorkoutLiveActivityCommand) async {
        let recorder = OutdoorWorkoutRecorder.shared
        // A widget or Live Activity tap can launch a killed app in the background: load the unfinished GPS workout
        // first so End saves it (and Resume restarts it where iOS allows) instead of doing nothing.
        if !recorder.isActive { recorder.restoreIfNeeded() }
        if recorder.isActive {
            recorder.handle(command)
        } else if WatchWorkoutMirror.shared.isMirroring {
            WatchWorkoutMirror.shared.send(command)
        } else if command == .end {
            if store?.activeSession != nil {
                await finishStrengthSession()
            } else if store == nil {
                // Background launch from the Live Activity: finish through a separate store
                // instance and tell the app's live store to reload (as Siri actions do).
                let standalone = StrengthWorkoutStore(observesExternalChanges: false)
                guard standalone.activeSession != nil else {
                    WorkoutLiveActivityController.shared.end()
                    return
                }
                store = standalone
                await finishStrengthSession()
                store = nil
                StrengthWorkoutStore.postExternalChangeNotification()
            }
        }
    }

    // MARK: - Strength sessions

    func startStrengthSession(on date: Date = .now) {
        guard let store, store.activeSession == nil, store.startSession(on: date), let active = store.activeSession else { return }
        if !OutdoorWorkoutRecorder.shared.isActive {
            WorkoutLiveActivityController.shared.start(title: "Strength session", state: strengthState(active))
        }
    }

    func discardStrengthSession() {
        store?.discardActiveSession()
        if !OutdoorWorkoutRecorder.shared.isActive { WorkoutLiveActivityController.shared.end() }
    }

    /// Finishes the running strength session over its real window: heart-rate statistics over the window,
    /// Keytel calories when coverage is sufficient, else the diary's MET estimate, else 5 MET × duration.
    @discardableResult
    func finishStrengthSession(at end: Date = .now) async -> StrengthWorkoutSession? {
        guard let store, let active = store.activeSession, !isFinishingStrength else { return nil }
        isFinishingStrength = true
        defer { isFinishingStrength = false }
        let date = StrengthWorkoutDate.date(for: active.diaryDateKey) ?? active.startedAt
        let start = active.startedAt
        let finish = max(end, start.addingTimeInterval(1))
        let weight = bodyWeight()
        let context = await WorkoutHeartRateSource.context(weightKg: weight)
        let heart = await WorkoutHeartRateSource.summary(start: start, end: finish, context: context)
        let estimate = StrengthWorkoutBurnEstimator.estimate(
            exercises: store.exercises(for: date), bodyWeightKg: weight,
            defaultWeightUnit: weightUnit, defaultRPEScale: store.preferences.rpeScale
        )
        let seconds = finish.timeIntervalSince(start)
        let kcal: Int
        if let keytel = heart?.keytelKcal, keytel > 0 {
            kcal = Int(keytel.rounded())
        } else if let estimate {
            kcal = estimate.calories
        } else {
            kcal = StrengthWorkoutBurnEstimator.metCalories(met: 5.0, bodyWeightKg: weight, seconds: seconds)
        }
        let session = store.completeWorkout(
            on: date, startedAt: start, completedAt: finish, elapsedSeconds: Int(seconds.rounded()),
            weightUnit: weightUnit, caloriesBurned: kcal, heartRate: heart
        )
        if !OutdoorWorkoutRecorder.shared.isActive { WorkoutLiveActivityController.shared.end() }
        return session
    }

    func strengthState(_ active: StrengthWorkoutStore.ActiveStrengthSession) -> WorkoutActivityAttributes.ContentState {
        WorkoutActivityAttributes.ContentState(kind: "strength", sport: "strength", state: "running",
                                               timerStart: active.startedAt, heartRate: nil)
    }
}
