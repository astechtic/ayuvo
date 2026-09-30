import Foundation
import Testing
@testable import calorietracker

/// Strength sessions with a real start/end, outdoor (GPS) sessions and their coexistence with the
/// day's calculated burn.
@MainActor
struct WorkoutSessionIntervalTests {
    private func loggedStore(_ fixture: WorkoutTestFixture, date: Date) throws -> StrengthWorkoutStore {
        let store = fixture.makeStore()
        store.toggleExercise(WorkoutTestFixture.exercise(id: "squat", name: "Back Squat"), on: date)
        let exercise = try #require(store.exercises(for: date).first)
        let set = try #require(exercise.sets.first)
        store.updateSet(exerciseID: exercise.id, setID: set.id, on: date, weight: "100", weightUnit: .kg, reps: "8", rpe: "8")
        return store
    }

    private func outdoorSession(on date: Date, id: UUID = UUID()) -> StrengthWorkoutSession {
        let start = date.addingTimeInterval(7 * 3600)
        return StrengthWorkoutSession(
            id: id, diaryDate: Calendar.current.startOfDay(for: date), diaryDateKey: StrengthWorkoutDate.key(for: date),
            startedAt: start, completedAt: start.addingTimeInterval(1800), durationSeconds: 1800, exercises: [],
            caloriesBurned: 300, healthSyncVersion: 1,
            outdoor: OutdoorWorkoutSummary(sport: "run", distanceM: 5000, movingSeconds: 1790, elapsedSeconds: 1800,
                                           avgSpeedMps: 2.79, avgPaceSecondsPerKm: 358, maxSpeedMps: 3.4,
                                           splits: [.init(km: 1, seconds: 360)], elevationGainM: 12, elevationLossM: 10,
                                           vo2max: 48.2, recordedOn: "phone", hasRoute: false, energyMethod: "met"))
    }

    @Test func confirmedWindowStoresRealIntervalAndHeartRate() throws {
        let fixture = WorkoutTestFixture()
        defer { fixture.cleanUp() }
        let date = WorkoutTestFixture.date(2026, 7, 19)
        let store = try loggedStore(fixture, date: date)
        let start = date.addingTimeInterval(18 * 3600)
        let interval = DateInterval(start: start, duration: 3600)
        let heart = WorkoutHeartRateSummary(HeartRateWorkout.WorkoutResult(
            avgHr: 120, maxHr: 160, coveragePct: 90, zoneSeconds: [0, 600, 1800, 1200, 0], trimp: 55, kcal: 410,
            zoneMethod: "hrr"))

        let session = try #require(store.upsertCalculatedWorkout(on: date, caloriesBurned: 410, weightUnit: .kg,
                                                                 interval: interval, heartRate: heart))
        #expect(session.hasRealInterval)
        #expect(session.durationSeconds == 3600)
        #expect(session.startedAt == start)

        // A later recalculation without a window keeps the confirmed interval.
        let recalculated = try #require(store.upsertCalculatedWorkout(on: date, caloriesBurned: 300, weightUnit: .kg))
        #expect(recalculated.id == session.id)
        #expect(recalculated.startedAt == start)
        #expect(recalculated.heartRate?.keytelKcal == 410)
        #expect(fixture.makeStore().workoutBurnSessions.first?.heartRate?.trimp == 55)
    }

    @Test func startAndFinishSessionPersistsAcrossRelaunchAndBecomesTheDayBurn() throws {
        let fixture = WorkoutTestFixture()
        defer { fixture.cleanUp() }
        let date = WorkoutTestFixture.date(2026, 7, 19)
        let store = try loggedStore(fixture, date: date)
        let start = date.addingTimeInterval(17 * 3600)

        #expect(store.startSession(on: date, at: start))
        #expect(!store.startSession(on: date, at: start.addingTimeInterval(60)))
        let relaunched = fixture.makeStore()
        #expect(relaunched.activeSession?.startedAt == start)

        var exported: [StrengthWorkoutSession] = []
        relaunched.onWorkoutBurnUpserted = { exported.append($0) }
        let finished = try #require(relaunched.completeWorkout(
            on: date, startedAt: start, completedAt: start.addingTimeInterval(2700), elapsedSeconds: 2700,
            weightUnit: .kg, caloriesBurned: 350))
        #expect(finished.hasRealInterval)
        #expect(relaunched.activeSession == nil)
        #expect(relaunched.caloriesBurned(on: date) == 350)
        #expect(exported.map(\.id) == [finished.id])
        #expect(fixture.makeStore().activeSession == nil)
    }

    @Test func outdoorSessionsSurviveTheDailyBurnAndStayOutOfBurnSync() throws {
        let fixture = WorkoutTestFixture()
        defer { fixture.cleanUp() }
        let date = WorkoutTestFixture.date(2026, 7, 19)
        let store = try loggedStore(fixture, date: date)
        var exported: [StrengthWorkoutSession] = []
        store.onWorkoutBurnUpserted = { exported.append($0) }

        let run = outdoorSession(on: date)
        #expect(store.addOutdoorSession(run))
        _ = try #require(store.upsertCalculatedWorkout(on: date, caloriesBurned: 200, weightUnit: .kg))

        #expect(store.outdoorSessions(on: date).map(\.id) == [run.id])
        #expect(store.workoutBurnSessions.count == 1)
        #expect(store.workoutBurnSessions.first?.outdoor == nil)
        #expect(store.caloriesBurned(on: date) == 200)
        #expect(exported.allSatisfy { $0.outdoor == nil })
        let reloaded = fixture.makeStore().session(id: run.id)
        #expect(reloaded?.outdoor?.distanceM == 5000)
        #expect(reloaded?.outdoor?.splits.first?.seconds == 360)
    }

    @Test func sessionsWithoutNewFieldsStillDecode() throws {
        let json = #"{"id":"6F9619FF-8B86-D011-B42D-00C04FC964FF","diaryDate":0,"startedAt":0,"completedAt":0,"durationSeconds":0,"exercises":[],"caloriesBurned":120}"#
        let session = try JSONDecoder().decode(StrengthWorkoutSession.self, from: Data(json.utf8))
        #expect(session.heartRate == nil)
        #expect(session.outdoor == nil)
        #expect(!session.hasRealInterval)
    }

    @Test func gpsVO2maxIsPreferredWithinLookback() {
        let series = ["2026-07-01": 47.5, "2026-07-10": 49.0]
        #expect(DerivedMetricsService.latestGPSVO2max(series, "2026-07-05") == 47.5)
        #expect(DerivedMetricsService.latestGPSVO2max(series, "2026-07-12") == 49.0)
        #expect(DerivedMetricsService.latestGPSVO2max(series, "2026-06-30") == nil)
        #expect(DerivedMetricsService.latestGPSVO2max(series, "2027-06-30") == nil)
    }

    @Test func tanakaHRMaxAndMinuteSeries() {
        #expect(WorkoutHeartRateSource.tanakaHRMax(age: 40) == 180)
        let samples = [HeartRateWorkout.Sample(tMs: 0, bpm: 100), .init(tMs: 30_000, bpm: 110), .init(tMs: 120_000, bpm: 130)]
        let series = WorkoutHeartRateSource.minuteSeries(samples, from: 0, to: 180_000)
        #expect(series?.startMs == 0)
        #expect(series?.values.count == 3)
        #expect(series?.values[0] == 105)
        #expect(series?.values[1] == nil)
        #expect(series?.values[2] == 130)
    }
}
