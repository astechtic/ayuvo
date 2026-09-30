import Foundation
import HealthKit

/// Heart-rate inputs for workouts: minute heart rate from the Health mirror (HealthKit as a fallback when
/// the mirror has not synced the window yet), resting heart rate (Apple Health's value wins over Ayuvo's
/// `resting_hr_derived`), and HRmax by Tanaka (208 − 0.7 × age) from the profile.
@MainActor
enum WorkoutHeartRateSource {
    struct Context: Sendable {
        var rhr: Double?
        var hrMax: Double
        var sex: String?
        var age: Double?
        var weightKg: Double?
    }

    nonisolated private static let minute: Int64 = 60_000

    static func tanakaHRMax(age: Double?) -> Double {
        guard let age, age > 0 else { return 190 }
        return DerivedMath.roundTo(208 - 0.7 * age, 0)
    }

    static func context(weightKg: Double? = nil) async -> Context {
        let profile = UserProfile.load()
        let sex: String? = profile.flatMap { $0.gender == .male ? "male" : ($0.gender == .female ? "female" : nil) }
        let age = profile.map { Double($0.age) }
        return Context(rhr: await restingHeartRate(), hrMax: tanakaHRMax(age: age), sex: sex, age: age,
                       weightKg: weightKg ?? profile?.weightKg)
    }

    /// Latest native resting heart rate of the last 14 days, else Ayuvo's latest derived value.
    static func restingHeartRate() async -> Double? {
        guard let db = await mirror() else { return nil }
        let today = InsightsDay.key(for: Date())
        let native = DerivedMetricsService.dailyAvg((try? await db.dailyRollups(type: "resting_heart_rate",
                                                                               fromDay: InsightsDay.add(today, -14),
                                                                               toDay: today)) ?? [])
        if let latest = native.max(by: { $0.key < $1.key })?.value { return latest }
        return (try? await db.latestDerivedValue(metric: "resting_hr_derived"))?.value
    }

    /// Heart-rate samples in [start, end], time ordered.
    static func samples(from start: Date, to end: Date) async -> [HeartRateWorkout.Sample] {
        let s = Int64(start.timeIntervalSince1970 * 1000), e = Int64(end.timeIntervalSince1970 * 1000)
        if let db = await mirror() {
            let rows = ((try? await db.rows(type: "heart_rate", startMs: s, endMs: e)) ?? [])
                .filter { !$0.isDeleted && $0.value != nil }
            // One source only (the one with the most samples): never interleave devices.
            let bySource = Dictionary(grouping: rows, by: \.sourceID)
            if let best = bySource.max(by: { $0.value.count < $1.value.count })?.value, !best.isEmpty {
                return best.sorted { $0.startMs < $1.startMs }.map { HeartRateWorkout.Sample(tMs: $0.startMs, bpm: $0.value!) }
            }
        }
        return await healthKitSamples(from: start, to: end)
    }

    /// Candidate strength-session windows on `date` from sustained elevated heart rate.
    static func proposeWindows(on date: Date, context: Context) async -> [HeartRateWorkout.Window] {
        let calendar = Calendar.current
        let dayStart = calendar.startOfDay(for: date)
        guard let dayEnd = calendar.date(byAdding: .day, value: 1, to: dayStart) else { return [] }
        let end = min(dayEnd, Date())
        guard end > dayStart else { return [] }
        let samples = await samples(from: dayStart, to: end)
        guard let series = minuteSeries(samples, from: Int64(dayStart.timeIntervalSince1970 * 1000),
                                        to: Int64(end.timeIntervalSince1970 * 1000)) else { return [] }
        return HeartRateWorkout.workoutWindows(hr: series, rhr: context.rhr, hrMax: context.hrMax,
                                               config: WorkoutConfig.shared).windows
    }

    /// `hrWorkout` over [start, end]; nil when there is no heart rate in the window.
    static func summary(start: Date, end: Date, context: Context,
                        samples provided: [HeartRateWorkout.Sample]? = nil) async -> WorkoutHeartRateSummary? {
        let samples: [HeartRateWorkout.Sample]
        if let provided { samples = provided } else { samples = await self.samples(from: start, to: end) }
        let result = HeartRateWorkout.hrWorkout(.init(
            samples: samples, startMs: Int64(start.timeIntervalSince1970 * 1000), endMs: Int64(end.timeIntervalSince1970 * 1000),
            hrMax: context.hrMax, rhr: context.rhr, sex: context.sex, age: context.age, weightKg: context.weightKg
        ), config: WorkoutConfig.shared)
        guard result.avgHr != nil else { return nil }
        return WorkoutHeartRateSummary(result)
    }

    /// Minute means of the samples, `DerivedMinuteSeries` from the first to the last minute with data.
    nonisolated static func minuteSeries(_ samples: [HeartRateWorkout.Sample], from: Int64, to: Int64) -> DerivedMinuteSeries? {
        var buckets: [Int64: [Double]] = [:]
        for s in samples { buckets[s.tMs / minute * minute, default: []].append(s.bpm) }
        return DerivedMetricsService.series(buckets.mapValues { DerivedMath.mean($0) }, from: from / minute * minute, to: to)
    }

    // MARK: - Sources

    private static func mirror() async -> HealthDatabase? {
        guard UserDefaults.standard.bool(forKey: "healthKitEnabled") else { return nil }
        let runtime = HealthDataRuntime.shared
        guard await runtime.openIfNeeded() else { return nil }
        return runtime.reader
    }

    static func healthKitSamples(from start: Date, to end: Date) async -> [HeartRateWorkout.Sample] {
        guard HKHealthStore.isHealthDataAvailable(), end > start else { return [] }
        let predicate = HKQuery.predicateForSamples(withStart: start, end: end, options: [])
        let sort = NSSortDescriptor(key: HKSampleSortIdentifierStartDate, ascending: true)
        return await withCheckedContinuation { continuation in
            let query = HKSampleQuery(sampleType: HKQuantityType(.heartRate), predicate: predicate,
                                      limit: HKObjectQueryNoLimit, sortDescriptors: [sort]) { _, results, _ in
                let unit = HKUnit.count().unitDivided(by: .minute())
                let samples = (results as? [HKQuantitySample] ?? []).map {
                    HeartRateWorkout.Sample(tMs: Int64($0.startDate.timeIntervalSince1970 * 1000),
                                            bpm: $0.quantity.doubleValue(for: unit))
                }
                continuation.resume(returning: samples)
            }
            HealthKitManager.sharedHealthStore.execute(query)
        }
    }
}
