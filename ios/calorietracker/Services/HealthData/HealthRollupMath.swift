import Foundation

/// Rollup rules shared with Android (`docs/health-data.md` §1.3). Pure functions; the
/// database calls `dailyRollup` for every dirty `(type, day)` pair.
nonisolated enum HealthRollupMath {
    /// `yyyy-MM-dd` of `ms` in the zone given by `offsetS` (device zone when nil).
    static func dayKey(ms: Int64, offsetS: Int?, calendar: Calendar) -> String {
        let zone = offsetS.flatMap { TimeZone(secondsFromGMT: $0) } ?? calendar.timeZone
        let date = Date(timeIntervalSince1970: Double(ms) / 1000)
        let components = calendar.dateComponents(in: zone, from: date)
        return String(format: "%04d-%02d-%02d", components.year ?? 1970, components.month ?? 1, components.day ?? 1)
    }

    /// `local_day` per `day_attribution`: start day, end (wake) day, or — for spans —
    /// the start day (Android splits spans into one row per day; iOS has no span types).
    static func localDay(
        startMs: Int64,
        endMs: Int64,
        startOffsetS: Int?,
        endOffsetS: Int?,
        attribution: HealthDayAttribution,
        calendar: Calendar
    ) -> String {
        switch attribution {
        case .start, .span:
            return dayKey(ms: startMs, offsetS: startOffsetS, calendar: calendar)
        case .end:
            // A sample ending exactly at midnight belongs to the day that just finished.
            let adjusted = endMs > startMs ? endMs - 1 : endMs
            return dayKey(ms: adjusted, offsetS: endOffsetS ?? startOffsetS, calendar: calendar)
        }
    }

    /// Day keys touched by `rows` (the caller's dirty set).
    static func dirtyDays(_ rows: [HealthSampleRow]) -> Set<String> {
        Set(rows.map(\.localDay))
    }

    /// Whether `row` is one of Ayuvo's own tagged workout-burn samples.
    static func isOwnWorkoutBurn(_ row: HealthSampleRow, ownBundleID: String) -> Bool {
        row.sourceID == ownBundleID && (row.extraJSON?.contains("ayuvo_workout_session_id") ?? false)
    }

    /// Builds the rollup for one `(type, day)` from that day's non-deleted rows.
    /// Returns nil when there is nothing to summarize (the caller deletes the rollup row).
    static func dailyRollup(
        rows allRows: [HealthSampleRow],
        type: HealthMetricType,
        day: String,
        tz: String,
        ownBundleID: String,
        calendar: Calendar
    ) -> HealthDailyRollupRow? {
        let rows = allRows.filter { !$0.isDeleted && $0.typeID == type.id && $0.localDay == day }
        guard !rows.isEmpty else { return nil }
        var rollup = HealthDailyRollupRow(typeID: type.id, day: day, tz: tz)
        let last = rows.max { lhs, rhs in
            if lhs.endMs == rhs.endMs { return lhs.id < rhs.id }
            return lhs.endMs < rhs.endMs
        }
        rollup.lastAtMs = last?.endMs

        if type.isSleep {
            let nights = HealthSleepAnalysis.nights(rows: rows, calendar: calendar)
            let asleep = nights.reduce(0.0) { $0 + $1.asleepS }
            rollup.sum = asleep
            rollup.durationS = asleep
            rollup.count = rows.count
            rollup.lastValue = last?.value
            return rollup
        }

        switch type.kind {
        case .cumulative:
            let values = rows.compactMap(\.value)
            rollup.sum = values.reduce(0, +)
            rollup.count = rows.reduce(0) { $0 + max(1, $1.count) }
            rollup.avg = rollup.count > 0 ? (rollup.sum ?? 0) / Double(rollup.count) : nil
            rollup.min = values.min()
            rollup.max = values.max()
            rollup.lastValue = last?.value
            if type.id == "active_energy" {
                let own = rows.filter { isOwnWorkoutBurn($0, ownBundleID: ownBundleID) }.compactMap(\.value).reduce(0, +)
                rollup.ownSum = own
            }

        case .discrete, .series:
            var weightedSum = 0.0
            var weight = 0
            var minimum: Double?
            var maximum: Double?
            for row in rows {
                guard let value = row.value else { continue }
                let n = max(1, row.count)
                weightedSum += value * Double(n)
                weight += n
                let rowMin = n > 1 ? (row.value2 ?? value) : value
                let rowMax = n > 1 ? (row.value3 ?? value) : value
                minimum = minimum.map { Swift.min($0, rowMin) } ?? rowMin
                maximum = maximum.map { Swift.max($0, rowMax) } ?? rowMax
            }
            rollup.count = weight
            rollup.avg = weight > 0 ? weightedSum / Double(weight) : nil
            rollup.min = minimum
            rollup.max = maximum
            rollup.lastValue = last?.value
            if type.isBloodPressure {
                let diastolic = rows.compactMap(\.value2)
                if !diastolic.isEmpty {
                    rollup.v2Avg = diastolic.reduce(0, +) / Double(diastolic.count)
                    rollup.v2Min = diastolic.min()
                    rollup.v2Max = diastolic.max()
                }
            }

        case .duration, .session:
            let duration = rows.reduce(0.0) { $0 + $1.durationSeconds }
            rollup.durationS = duration
            rollup.sum = duration
            rollup.count = rows.count
            rollup.avg = rows.isEmpty ? nil : duration / Double(rows.count)
            let durations = rows.map(\.durationSeconds)
            rollup.min = durations.min()
            rollup.max = durations.max()
            rollup.lastValue = last?.value

        case .category:
            rollup.count = rows.reduce(0) { $0 + max(1, $1.count) }
            rollup.lastValue = last?.categoryValue.map(Double.init) ?? last?.value
        }
        return rollup
    }

    /// Hourly buckets of one local day for the D chart (computed from rows on iOS).
    nonisolated struct HourBucket: Sendable, Equatable {
        var hour: Int
        var sum: Double
        var avg: Double?
        var min: Double?
        var max: Double?
        var count: Int
    }

    static func hourlyBuckets(rows: [HealthSampleRow], type: HealthMetricType, dayStart: Date, calendar: Calendar) -> [HourBucket] {
        var buckets: [Int: (sum: Double, weighted: Double, weight: Int, min: Double?, max: Double?, count: Int)] = [:]
        let dayStartMs = Int64(dayStart.timeIntervalSince1970 * 1000)
        for row in rows where !row.isDeleted {
            let hour = Int((row.startMs - dayStartMs) / 3_600_000)
            guard (0..<24).contains(hour) else { continue }
            var bucket = buckets[hour] ?? (0, 0, 0, nil, nil, 0)
            let n = max(1, row.count)
            switch type.kind {
            case .cumulative:
                bucket.sum += row.value ?? 0
            case .duration, .session:
                bucket.sum += row.durationSeconds
            case .category:
                bucket.sum += Double(n)
            case .discrete, .series:
                break
            }
            if let value = row.value {
                bucket.weighted += value * Double(n)
                bucket.weight += n
                let rowMin = n > 1 ? (row.value2 ?? value) : value
                let rowMax = n > 1 ? (row.value3 ?? value) : value
                bucket.min = bucket.min.map { Swift.min($0, rowMin) } ?? rowMin
                bucket.max = bucket.max.map { Swift.max($0, rowMax) } ?? rowMax
            }
            bucket.count += n
            buckets[hour] = bucket
        }
        return buckets.keys.sorted().map { hour in
            let b = buckets[hour]!
            return HourBucket(
                hour: hour,
                sum: b.sum,
                avg: b.weight > 0 ? b.weighted / Double(b.weight) : nil,
                min: b.min,
                max: b.max,
                count: b.count
            )
        }
    }
}
