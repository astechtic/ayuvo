import Foundation
import Observation

/// Computes derived metrics (docs/derived-metrics.md §3) from the Health mirror and stores them in
/// `derived_daily_values`. Engines are the pure ports of `scripts/derived_reference.py`; this class builds their inputs,
/// applies "native wins" to the inputs other metrics depend on (an Apple Health resting heart rate is used for zones
/// before Ayuvo's estimate) and writes one row per enabled metric and day. Values are never written to Apple Health.
/// Mirrors Android `DerivedMetricsService`.
@MainActor
@Observable
final class DerivedMetricsService {
    static let shared = DerivedMetricsService()

    static let historyDays = 200
    private static let rangePad = 16
    private static let streakWindow = 90
    private static let rhrRefDays = 14
    private static let minute: Int64 = 60_000

    /// Bumped after every write; views observing it reload their series.
    private(set) var revision = 0
    private(set) var isComputing = false

    private var pending: Task<Void, Never>?
    private let defaults: UserDefaults
    private let calendar: Calendar

    init(defaults: UserDefaults = .standard, calendar: Calendar = .current) {
        self.defaults = defaults
        self.calendar = calendar
    }

    /// A switch changed: drop disabled metrics and recompute soon.
    func settingsDidChange() { scheduleRefresh() }

    /// Debounced refresh (sync completion, switches, app launch).
    func scheduleRefresh(delay: Duration = .milliseconds(1500)) {
        pending?.cancel()
        pending = Task { [weak self] in
            try? await Task.sleep(for: delay)
            guard !Task.isCancelled else { return }
            await self?.refresh()
        }
    }

    func refresh(historyDays: Int = DerivedMetricsService.historyDays) async {
        guard defaults.bool(forKey: "healthKitEnabled") else { return }
        let runtime = HealthDataRuntime.shared
        guard await runtime.openIfNeeded(), let db = runtime.writer else { return }
        let catalog = DerivedCatalog.shared
        let enabled = DerivedSettings.enabledIDs(catalog: catalog, defaults: defaults)
        let off = catalog.metrics.map(\.id).filter { !enabled.contains($0) }
        try? await db.deleteDerivedValues(metricIDs: off)
        let config = DerivedConfig.shared
        guard !enabled.isEmpty else {
            revision += 1
            return
        }
        isComputing = true
        defer { isComputing = false }
        let today = InsightsDay.key(for: Date(), calendar: calendar)
        let from = InsightsDay.add(today, -(historyDays - 1))
        let profile = UserProfile.load()
        let weights = WeightStore(observesExternalChanges: false, defaults: defaults).entries
            .sorted { $0.date < $1.date }
            .reduce(into: [String: Double]()) { $0[InsightsDay.key(for: $1.date, calendar: calendar)] = $1.weightKg }
        let steps = await InsightsHealthKitTotals.daily(
            typeID: "steps",
            from: InsightsDay.date(InsightsDay.add(from, -Self.streakWindow), calendar: calendar) ?? Date(),
            to: Date(), calendar: calendar, defaults: defaults
        ) ?? [:]
        var inputs = Inputs(
            timeZone: calendar.timeZone.identifier,
            sex: profile.map { $0.gender == .male ? "male" : ($0.gender == .female ? "female" : nil) } ?? nil,
            birthday: profile?.birthday,
            heightCm: profile?.heightCm,
            goalKg: profile?.goalWeightKg,
            stepGoal: Double(ActivitySettings.dailyStepGoal(defaults: defaults)),
            bmiScheme: DerivedSettings.bmiScheme(defaults),
            weights: weights,
            stepsTotal: steps,
            intake: DerivedIntakeInputs.snapshot(entries: FoodStore(observesExternalChanges: false, defaults: defaults).entries,
                                                 calendar: calendar)
        )
        inputs.gpsVO2max = Self.gpsVO2maxByDay(defaults: defaults)
        let rows = await Self.compute(db: db, config: config, catalog: catalog, enabled: enabled, from: from, to: today,
                                      inputs: inputs)
        let days = (0..<historyDays).map { InsightsDay.add(from, $0) }
        try? await db.replaceDerivedValues(rows, metricIDs: enabled.sorted(), days: days)
        revision += 1
    }

    // MARK: - Compute (off the main actor)

    struct Inputs: Sendable {
        var timeZone: String
        var sex: String?
        var birthday: Date?
        var heightCm: Double?
        var goalKg: Double?
        var stepGoal: Double
        var bmiScheme: String
        var weights: [String: Double]
        var stepsTotal: [String: Double]
        /// Food diary for the nutrition metrics (DerivedIntakeInputs).
        var intake: DerivedIntakeInputs.Snapshot = .empty
        /// VO₂max from GPS workouts by diary day (WorkoutVO2maxInputs); preferred over Uth–Sørensen.
        var gpsVO2max: [String: Double] = [:]
    }

    nonisolated static func compute(
        db: HealthDatabase, config: DerivedConfig, catalog: DerivedCatalog, enabled: Set<String>,
        from: String, to: String, inputs: Inputs
    ) async -> [DerivedDailyValueRow] {
        let tz = DerivedDay.timeZone(inputs.timeZone)
        let th = config.thresholds
        let age: Double? = inputs.birthday.map { birthday in
            var cal = Calendar(identifier: .gregorian)
            cal.timeZone = tz
            let end = Date(timeIntervalSince1970: Double(DerivedDay.dayStartMs(to, tz)) / 1000)
            return Double(cal.dateComponents([.year], from: birthday, to: end).year ?? 0)
        }
        let lookFrom = InsightsDay.add(from, -th.hrMaxLookbackDays)
        let padFrom = InsightsDay.add(from, -rangePad)

        // Low-volume inputs for the whole range at once.
        let sleepRows = ((try? await db.rowsForDays(type: "sleep", fromDay: padFrom, toDay: to)) ?? [])
            .filter { !$0.isDeleted && $0.categoryValue != nil }
            .map { SleepDerivation.SourceRow(startMs: $0.startMs, endMs: $0.endMs, code: $0.categoryValue!, source: $0.sourceID) }
        let nights = SleepDerivation.sleepNights(timeZone: inputs.timeZone, rows: sleepRows, config: config).nights
        let nativeRHR = dailyAvg(await (try? db.dailyRollups(type: "resting_heart_rate", fromDay: lookFrom, toDay: to)) ?? [])
        let activeByDay = Dictionary(uniqueKeysWithValues: ((try? await db.dailyRollups(type: "active_energy", fromDay: from, toDay: to)) ?? []).map { ($0.day, $0) })
        let restingByDay = dailySum((try? await db.dailyRollups(type: "resting_energy", fromDay: from, toDay: to)) ?? [])
        let gaitRows: [String: [HealthSampleRow]] = await {
            var out: [String: [HealthSampleRow]] = [:]
            for type in ["walking_speed", "walking_double_support", "walking_asymmetry"] {
                out[type] = ((try? await db.rowsForDays(type: type, fromDay: InsightsDay.add(from, -7), toDay: to)) ?? []).filter { !$0.isDeleted }
            }
            return out
        }()
        let audioRows = ((try? await db.rowsForDays(type: "headphone_audio_exposure", fromDay: from, toDay: to)) ?? []).filter { !$0.isDeleted }
        var storedObserved = values((try? await db.derivedValues(metric: "observed_max_hr", fromDay: lookFrom, toDay: to)) ?? [])
        var storedRHR = values((try? await db.derivedValues(metric: "resting_hr_derived", fromDay: lookFrom, toDay: to)) ?? [])
        var intake = await DerivedIntakeInputs.Runner(db: db, snapshot: inputs.intake, timeZone: inputs.timeZone,
                                                      weights: inputs.weights, from: from, to: to)

        var rows: [DerivedDailyValueRow] = []
        let computed = Int64((Date().timeIntervalSince1970 * 1000).rounded())
        var day = from
        while day <= to {
            if Task.isCancelled { break }
            var out: [String: [String: Any]] = [:]
            let night = nights[day]
            let window = night?.window.map { HeartDerivation.Night(startMs: $0.startMs, endMs: $0.endMs) }
            let d0 = DerivedDay.dayStartMs(day, tz)
            let d1 = DerivedDay.dayStartMs(InsightsDay.add(day, 1), tz)
            let winStart = min(window?.startMs ?? Int64.max, d0) - minute

            // Heart
            let hrRows = ((try? await db.rows(type: "heart_rate", startMs: winStart, endMs: d1)) ?? []).filter { !$0.isDeleted && $0.value != nil }
            let stepRows = ((try? await db.rows(type: "steps", startMs: winStart, endMs: d1)) ?? []).filter { !$0.isDeleted && $0.value != nil }
            let hr = heartRateMinutes(hrRows, d0: d0, d1: d1, from: winStart)
            let minuteSteps = wearableMinuteSteps(stepRows, from: d0 - 18 * 3_600_000, to: d1)
            if let hr {
                let first = HeartDerivation.heartDay(.init(timeZone: inputs.timeZone, day: day, sex: inputs.sex, hr: hr,
                                                           steps: minuteSteps, night: window, hrMax: 220, rhrRef: nil), config: config)
                if let v = first.restingHr { storedRHR[day] = v }
                if let v = first.observedMax { storedObserved[day] = v }
                let rhrRef = nativeRHR[day] ?? (enabled.contains("resting_hr_derived") ? recentRHR(storedRHR, day) : nil)
                var heart = first
                if let age {
                    let lookback = InsightsDay.add(day, -th.hrMaxLookbackDays)
                    let observed = storedObserved.filter { $0.key <= day && $0.key >= lookback }.map { Optional($0.value) }
                    let hrMax = HeartDerivation.hrMax(age: age, observed: observed, rhr: rhrRef, config: config)
                    heart = HeartDerivation.heartDay(.init(timeZone: inputs.timeZone, day: day, sex: inputs.sex, hr: hr,
                                                           steps: minuteSteps, night: window, hrMax: hrMax.hrMax, rhrRef: rhrRef), config: config)
                    out["hr_max"] = hrMax.jsonObject
                    out["vo2max_uth"] = HeartDerivation.vo2maxUth(hrMax: hrMax.hrMax, hrMaxMethod: hrMax.method, rhr: rhrRef, config: config).jsonObject
                }
                var json = heart.jsonObject
                json["resting_hr"] = DerivedMath.json(first.restingHr)
                out["heart_day"] = json
            }
            let rhrSeries = storedRHR.merging(nativeRHR) { _, native in native }.filter { $0.key <= day }
            out["rhr_strain"] = HeartDerivation.rhrStrain(series: rhrSeries, day: day, config: config).jsonObject

            // Sleep
            if let night {
                out["sleep_night"] = SleepDerivation.sleepNight(timeZone: inputs.timeZone, wakeDay: day, rows: night.rows, config: config).jsonObject
            }
            let first = InsightsDay.add(day, -(th.regularityWindowDays - 1))
            let windowNights = nights.filter { $0.key <= day && $0.key >= first }.mapValues(\.rows)
            if !windowNights.isEmpty {
                out["sleep_regularity"] = SleepDerivation.sleepRegularity(timeZone: inputs.timeZone, day: day, needMin: nil,
                                                                          nights: windowNights, config: config).jsonObject
            }

            // Activity
            let hourly = hourlyBySource(stepRows, d0: d0, d1: d1, tz: tz)
            if !hourly.isEmpty {
                out["activity_day"] = ActivityDerivation.activityDay(.init(timeZone: inputs.timeZone, day: day, sources: hourly,
                                                                           minuteSteps: minuteSteps, wear: hr,
                                                                           stepsTotal: inputs.stepsTotal[day]), config: config).jsonObject
            }
            if inputs.stepGoal > 0, inputs.stepsTotal[day] != nil {
                out["step_streak"] = ActivityDerivation.stepStreak(series: inputs.stepsTotal, day: day, goal: inputs.stepGoal,
                                                                   windowDays: streakWindow).jsonObject
            }
            let phone = stepRows.filter { kind(of: $0) == "phone" && $0.startMs >= d0 && $0.startMs < d1 }
            if !phone.isEmpty {
                let distance = ((try? await db.rows(type: "distance", startMs: d0, endMs: d1)) ?? [])
                    .filter { !$0.isDeleted && kind(of: $0) == "phone" }
                var phoneSteps = 0.0, phoneDistance = 0.0
                for r in phone { phoneSteps += r.value ?? 0 }
                for r in distance { phoneDistance += r.value ?? 0 }
                out["stride"] = ActivityDerivation.stride(distanceM: distance.isEmpty ? nil : phoneDistance, steps: phoneSteps,
                                                          heightCm: inputs.heightCm, sex: inputs.sex, config: config).jsonObject
            }

            // Energy
            if let resting = restingByDay[day] {
                let a = activeByDay[day]
                let active = a?.sum.map { $0 - (a?.ownSum ?? 0) }
                out["energy_day"] = EnergyDerivation.energyDay(.init(restingKcal: resting, activeKcal: active,
                                                                     weightKg: weightOn(inputs.weights, day), heightCm: inputs.heightCm,
                                                                     age: age, sex: inputs.sex), config: config).jsonObject
            }

            // Mobility and hearing
            let weekStart = DerivedDay.dayStartMs(InsightsDay.add(day, -6), tz)
            func week(_ type: String) -> [Double?] {
                (gaitRows[type] ?? []).filter { $0.startMs >= weekStart && $0.startMs < d1 }.map(\.value)
            }
            if !(gaitRows["walking_speed"] ?? []).isEmpty || !(gaitRows["walking_double_support"] ?? []).isEmpty {
                out["gait_week"] = MobilityDerivation.gaitWeek(walkingSpeed: week("walking_speed"),
                                                               doubleSupport: week("walking_double_support"),
                                                               asymmetry: week("walking_asymmetry"), config: config).jsonObject
            }
            let audio = audioRows.filter { $0.startMs >= d0 && $0.startMs < d1 }
            if !audio.isEmpty {
                out["audio_day"] = MobilityDerivation.audioDay(samples: audio.compactMap { r in
                    r.value.map { MobilityDerivation.AudioSample(startMs: Double(r.startMs), endMs: Double(r.endMs), db: $0) }
                }, config: config).jsonObject
            }

            // Body
            if !inputs.weights.isEmpty {
                out["body_trend"] = BodyDerivation.bodyTrend(weights: inputs.weights, day: day,
                                                             heightM: inputs.heightCm.flatMap { $0 > 0 ? $0 / 100 : nil },
                                                             goalKg: inputs.goalKg, scheme: inputs.bmiScheme, config: config).jsonObject
            }

            // Nutrition and energy balance (docs/intake-metrics.md)
            intake.add(into: &out, day: day, bedtimeMs: nights[InsightsDay.add(day, 1)]?.window?.startMs)

            // A recent GPS workout's VO₂max (steady segments or Cooper test) wins over Uth–Sørensen.
            if let gps = latestGPSVO2max(inputs.gpsVO2max, day) {
                out["vo2max_uth"] = ["vo2max": gps, "confidence": "gps"]
            }

            rows.append(contentsOf: rowsFor(catalog: catalog, enabled: enabled, out: out, day: day,
                                            algoVersion: config.algoVersion, computed: computed))
            day = InsightsDay.add(day, 1)
        }
        return rows
    }

    /// One row per enabled metric whose function ran for `day` and produced a value.
    nonisolated static func rowsFor(catalog: DerivedCatalog, enabled: Set<String>, out: [String: [String: Any]], day: String,
                                    algoVersion: Int, computed: Int64) -> [DerivedDailyValueRow] {
        func number(_ any: Any?) -> Double? {
            switch any {
            case let v as Double: v
            case let v as Int: Double(v)
            case let v as Int64: Double(v)
            default: nil
            }
        }
        var rows: [DerivedDailyValueRow] = []
        for m in catalog.metrics where enabled.contains(m.id) && m.requires.allSatisfy(enabled.contains) {
            guard let result = out[m.function], let value = number(result[m.field]) else { continue }
            let quality: Double
            if m.function == "vo2max_uth" {
                switch result["confidence"] as? String {
                case "gps": quality = 0.7
                case "medium": quality = 0.6
                default: quality = 0.3
                }
            } else {
                quality = 1.0
            }
            rows.append(DerivedDailyValueRow(
                metricID: m.id, day: day, value: value,
                value2: m.value2Field.flatMap { number(result[$0]) }, value3: m.value3Field.flatMap { number(result[$0]) },
                quality: quality, algoVersion: algoVersion, computedMs: computed
            ))
        }
        return rows
    }

    // MARK: - Input builders

    /// Minute heart rate from the single source with the most minutes on the day (never averaged across devices).
    nonisolated static func heartRateMinutes(_ rows: [HealthSampleRow], d0: Int64, d1: Int64, from: Int64) -> DerivedMinuteSeries? {
        var bySource: [String: [Int64: [Double]]] = [:]
        for r in rows {
            guard let v = r.value else { continue }
            bySource[r.sourceID, default: [:]][r.startMs / minute * minute, default: []].append(v)
        }
        let best = bySource.max { lhs, rhs in
            let l = lhs.value.keys.filter { $0 >= d0 && $0 < d1 }.count, r = rhs.value.keys.filter { $0 >= d0 && $0 < d1 }.count
            return l != r ? l < r : lhs.key > rhs.key
        }
        guard let best else { return nil }
        let minutes = best.value.mapValues { DerivedMath.mean($0) }
        return series(minutes, from: from / minute * minute, to: d1)
    }

    /// Wearable minute steps: the non-phone source with the most steps, each record spread evenly over its minutes.
    nonisolated static func wearableMinuteSteps(_ rows: [HealthSampleRow], from: Int64, to: Int64) -> DerivedMinuteSeries? {
        let wear = rows.filter { kind(of: $0) == "wearable" }
        let totals = Dictionary(grouping: wear, by: \.sourceID).mapValues { list in list.reduce(0.0) { $0 + ($1.value ?? 0) } }
        guard let source = totals.max(by: { $0.value != $1.value ? $0.value < $1.value : $0.key > $1.key })?.key else { return nil }
        var minutes: [Int64: Double] = [:]
        for r in wear where r.sourceID == source {
            let s = r.startMs / minute * minute
            let n = max(1, (r.endMs - s + minute - 1) / minute)
            let per = (r.value ?? 0) / Double(n)
            for i in 0..<n { minutes[s + i * minute, default: 0] += per }
        }
        return series(minutes, from: from, to: to)
    }

    /// 24 hourly totals per source (by source name) for the local day, attributed to each record's start hour.
    nonisolated static func hourlyBySource(_ rows: [HealthSampleRow], d0: Int64, d1: Int64, tz: TimeZone) -> [String: ActivityDerivation.Source] {
        var hours: [String: [Double?]] = [:]
        var kinds: [String: String] = [:]
        for r in rows where r.startMs >= d0 && r.startMs < d1 {
            let h = DerivedDay.localTime(r.startMs, tz).hour
            var arr = hours[r.sourceID] ?? Array(repeating: nil, count: 24)
            arr[h] = (arr[h] ?? 0) + (r.value ?? 0)
            hours[r.sourceID] = arr
            kinds[r.sourceID] = kind(of: r)
        }
        return hours.reduce(into: [:]) { $0[$1.key] = ActivityDerivation.Source(kind: kinds[$1.key] ?? "wearable", hourly: $1.value) }
    }

    /// "phone" for iPhone-recorded rows (device model), "wearable" otherwise.
    nonisolated static func kind(of r: HealthSampleRow) -> String {
        (r.device ?? "").lowercased().contains("iphone") ? "phone" : "wearable"
    }

    nonisolated static func series(_ minutes: [Int64: Double], from: Int64, to: Int64) -> DerivedMinuteSeries? {
        guard let lo = minutes.keys.min(), let hi = minutes.keys.max() else { return nil }
        let start = max(from, lo), end = min(to, hi + minute)
        guard end > start else { return nil }
        let n = Int((end - start) / minute)
        return DerivedMinuteSeries(startMs: start, values: (0..<n).map { minutes[start + Int64($0) * minute] })
    }

    nonisolated static func recentRHR(_ series: [String: Double], _ day: String) -> Double? {
        let recent = (0..<rhrRefDays).compactMap { series[InsightsDay.add(day, -$0)] }
        return recent.isEmpty ? nil : DerivedMath.median(recent)
    }

    nonisolated static func weightOn(_ weights: [String: Double], _ day: String) -> Double? {
        weights.filter { $0.key <= day }.max { $0.key < $1.key }?.value
    }

    nonisolated static func dailyAvg(_ rollups: [HealthDailyRollupRow]) -> [String: Double] {
        rollups.reduce(into: [:]) { out, r in if r.count > 0, let v = r.avg { out[r.day] = v } }
    }

    nonisolated static func dailySum(_ rollups: [HealthDailyRollupRow]) -> [String: Double] {
        rollups.reduce(into: [:]) { out, r in if let v = r.sum, r.count > 0 || r.fromPlatformAggregate != 0 { out[r.day] = v } }
    }

    nonisolated static func values(_ rows: [DerivedDailyValueRow]) -> [String: Double] {
        rows.reduce(into: [:]) { out, r in if let v = r.value { out[r.day] = v } }
    }
}
