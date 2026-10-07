import Foundation
import Testing
@testable import calorietracker

/// Schema v5 analytics storage, per-night sleep rollups, the per-metric source policy in rollups and the Phase 0
/// sleep / training-load fixes (docs/health-analytics.md).
struct AnalyticsStorageTests {
    private typealias F = HealthTestFixtures

    private func result(_ metric: String, _ day: String, version: Int, value: Double) -> AnalyticsResultRow {
        AnalyticsResultRow(metricID: metric, periodStart: day, periodEnd: day, algorithmID: "ayuvo.recovery",
                           algorithmVersion: version, configVersion: 1, status: "VALID", classification: "PERSONALIZED_STATISTICAL",
                           value: value, value2: nil, value3: nil, unit: "score", confidence: 0.8, coverage: 1, inputCount: 5,
                           baselineWindowDays: 60, resultJSON: "{}", provenanceJSON: "{}", inputHash: "h\(value)", computedMs: 1)
    }

    @Test func resultsKeepTheTwoNewestAlgorithmVersions() async throws {
        let db = try await HealthDatabase.inMemory()
        try await db.upsertAnalyticsResults([result("recovery_indicator", "2026-09-01", version: 1, value: 50)])
        try await db.upsertAnalyticsResults([result("recovery_indicator", "2026-09-01", version: 2, value: 60)])
        #expect(try await db.latestAnalyticsResult(metric: "recovery_indicator", algorithmVersion: 1)?.value == 50,
                "a new version never overwrites the old one")
        try await db.upsertAnalyticsResults([result("recovery_indicator", "2026-09-01", version: 3, value: 70)])
        #expect(try await db.latestAnalyticsResult(metric: "recovery_indicator", algorithmVersion: 1) == nil)
        #expect(try await db.latestAnalyticsResult(metric: "recovery_indicator", algorithmVersion: 2)?.value == 60)
        let hashes = try await db.analyticsInputHashes(metric: "recovery_indicator", algorithmVersion: 3, fromDay: "2026-09-01", toDay: "2026-09-30")
        #expect(hashes == ["2026-09-01": "h70.0"])
    }

    @Test func modelsAreVersionedNeverReplacedAndStateRoundTrips() async throws {
        let db = try await HealthDatabase.inMemory()
        let model = MLModelRow(modelID: "ayuvo.forecast.hrv", modelVersion: 0, algorithmVersion: 1, target: "hrv",
                               featureSchemaVersion: 1, trainStart: "2026-01-01", trainEnd: "2026-03-01", valStart: nil,
                               valEnd: nil, testStart: nil, testEnd: nil, lambda: 10, coefficientsJSON: "{}",
                               normalizationJSON: "{}", metricsJSON: "{}", baselineMetricsJSON: "{}", deployed: false, createdMs: 1)
        #expect(try await db.insertMLModel(model).modelVersion == 1)
        #expect(try await db.insertMLModel(model).modelVersion == 2)
        #expect(try await db.latestMLModel(modelID: "ayuvo.forecast.hrv")?.modelVersion == 2)
        try await db.setAnalyticsState(AnalyticsStateRow(metricID: "recovery_indicator", algorithmVersion: 2, configVersion: 1,
                                                         lastProcessedDay: "2026-09-02", updatedMs: 5))
        #expect(try await db.analyticsState(metric: "recovery_indicator")?.lastProcessedDay == "2026-09-02")
        try await db.wipeAllData()
        #expect(try await db.latestMLModel(modelID: "ayuvo.forecast.hrv") == nil)
    }

    @Test func napIsKeptOutOfTheNightWindow() throws {
        let bed = F.date(2026, 9, 9, 23, 0)
        let nap = F.date(2026, 9, 10, 15, 0)
        let rows = [
            F.row(id: "n1", type: F.sleep, start: bed, end: bed.addingTimeInterval(7 * 3600), categoryValue: 3),
            F.row(id: "nap", type: F.sleep, start: nap, end: nap.addingTimeInterval(40 * 60), categoryValue: 1),
        ]
        let night = try #require(HealthSleepAnalysis.nights(rows: rows, calendar: F.calendar).first)
        #expect(night.nightOf == "2026-09-10")
        #expect(night.asleepS == 7 * 3600)
        #expect(night.napS == 40 * 60)
        #expect(night.endMs == F.ms(bed.addingTimeInterval(7 * 3600)), "the nap never widens the window")
    }

    @Test func sleepRollupIsTheWholeNightNotTheCalendarDay() async throws {
        let db = try await HealthDatabase.inMemory()
        let bed = F.date(2026, 9, 9, 22, 0)
        let rows = [
            // Ends before midnight → local_day 2026-09-09, but belongs to the night that wakes on 2026-09-10.
            F.row(id: "a", type: F.sleep, start: bed, end: bed.addingTimeInterval(1.5 * 3600), categoryValue: 3),
            F.row(id: "b", type: F.sleep, start: bed.addingTimeInterval(1.5 * 3600), end: bed.addingTimeInterval(8 * 3600), categoryValue: 4),
        ]
        try await db.upsertSamples(rows)
        try await db.rebuildRollups(type: F.sleep, days: ["2026-09-09"], tz: F.calendar.timeZone.identifier, calendar: F.calendar,
                                    ownBundleID: F.bundleID)
        let rollups = try await db.dailyRollups(type: "sleep", fromDay: "2026-09-08", toDay: "2026-09-11")
        #expect(rollups.map(\.day) == ["2026-09-10"])
        let seconds = try #require(rollups.first?.durationS)
        #expect(abs(seconds - 8 * 3600) < 0.001)
    }

    @Test func restingHeartRateRollupUsesOneSourceAndDropsGoogleHealthCopies() throws {
        let rhr = try #require(HealthMetricRegistry.type(id: "resting_heart_rate"))
        let t = F.date(2026, 9, 10, 7)
        var watch = F.row(id: "w", type: rhr, start: t, value: 54, source: "com.apple.health.watch")
        watch.device = "Apple Watch"
        let phone = F.row(id: "p", type: rhr, start: t, value: 60, count: 5, source: "com.phoneapp")
        let ghCopy = F.row(id: "g", type: rhr, start: t.addingTimeInterval(20), value: 54.2,
                           source: "google_health:com.apple.health.watch", origin: 3)
        let rollup = try #require(HealthRollupMath.dailyRollup(rows: [watch, phone, ghCopy], type: rhr, day: watch.localDay,
                                                              tz: "Europe/Berlin", ownBundleID: F.bundleID, calendar: F.calendar))
        #expect(rollup.avg == 54, "the watch wins over the phone; the Google Health copy is the same reading")
        #expect(rollup.count == 1)
    }

    @Test func strengthSessionWithoutEndUsesItsDurationAndEffort() {
        let start = F.date(2026, 9, 10, 6)
        let set = StrengthCompletedSet(setNumber: 1, weight: "60", weightUnit: "kg", reps: "8", rpe: "8", rpeScale: .cr10, completed: true)
        let session = StrengthWorkoutSession(
            diaryDate: start, startedAt: start, completedAt: start, durationSeconds: 3600,
            exercises: [StrengthCompletedExercise(itemID: "x", name: "Squat", targetMuscles: [], equipment: "", sets: [set])]
        )
        #expect(session.activeInterval?.duration == 3600)
        #expect(session.effortCR10() == 8)
        let unrated = StrengthCompletedSet(setNumber: 1, weight: "60", weightUnit: "kg", reps: "8", rpe: "", rpeScale: nil, completed: true)
        let noEffort = StrengthWorkoutSession(
            diaryDate: start, startedAt: start, completedAt: start, durationSeconds: 0,
            exercises: [StrengthCompletedExercise(itemID: "x", name: "Squat", targetMuscles: [], equipment: "", sets: [unrated])]
        )
        #expect(noEffort.effortCR10() == nil, "a missing RPE is never invented")
        #expect(noEffort.activeInterval == nil)
    }
}
