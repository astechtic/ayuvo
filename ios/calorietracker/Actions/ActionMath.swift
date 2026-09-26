import Foundation

/// Date ranges, unit conversion and the small calculations action outputs share with Android —
/// Swift port of `resolve_date_range` and `compute` in `scripts/actions_reference.py`
/// (vectors: `shared/actions/test-vectors/date_ranges.json`, `compute.json`).
nonisolated enum ActionMath {
    typealias Zone = MetricsReference.Zone
    typealias LocalDay = MetricsReference.LocalDay

    // MARK: - Rounding (half up, same formula on every platform)

    static func roundTo(_ x: Double, _ decimals: Int) -> Double {
        let scale = pow(10, Double(decimals))
        return floor(x * scale + 0.5) / scale
    }

    static func percent(_ part: Double, _ whole: Double) -> Int {
        Int(floor(part * 100.0 / whole + 0.5))
    }

    // MARK: - Date ranges: half-open [fromMs, toMs) in the user's time zone

    struct DateRange: Equatable, Sendable {
        let fromMs: Int64
        let toMs: Int64
        let firstDay: LocalDay
        let dayCount: Int

        var from: Date { Date(timeIntervalSince1970: Double(fromMs) / 1000) }
        var to: Date { Date(timeIntervalSince1970: Double(toMs) / 1000) }
        var isSingleDay: Bool { dayCount == 1 }

        func contains(_ date: Date) -> Bool {
            let ms = Int64((date.timeIntervalSince1970 * 1000).rounded())
            return ms >= fromMs && ms < toMs
        }

        func contains(ms: Int64) -> Bool { ms >= fromMs && ms < toMs }
    }

    static func resolveDateRange(_ preset: String, nowMs: Int64, zone: Zone, weekStart: MetricsReference.WeekStart) -> DateRange? {
        let d = zone.day(of: nowMs)
        let days: (LocalDay, LocalDay)
        switch preset {
        case "today": days = (d, d.adding(days: 1))
        case "yesterday": days = (d.adding(days: -1), d)
        case "this_week", "last_week":
            let ws = MetricsReference.weekStartOf(d, weekStart)
            days = preset == "this_week" ? (ws, ws.adding(days: 7)) : (ws.adding(days: -7), ws)
        case "last_7_days": days = (d.adding(days: -6), d.adding(days: 1))
        case "last_30_days": days = (d.adding(days: -29), d.adding(days: 1))
        case "this_month", "last_month":
            let first = LocalDay(year: d.year, month: d.month, day: 1)
            days = preset == "this_month" ? (first, first.addingMonths(1)) : (first.addingMonths(-1), first)
        default: return nil
        }
        let (a, b) = days
        return DateRange(fromMs: zone.midnight(a), toMs: zone.midnight(b), firstDay: a, dayCount: b.ordinal - a.ordinal)
    }

    // MARK: - Units

    static func toCanonical(_ value: Double, family: ActionCatalog.UnitFamily, unit: String?) -> Double {
        value * (family.factors[unit ?? family.canonical] ?? 1)
    }

    static func convert(_ value: Double, family: ActionCatalog.UnitFamily, from: String, to: String) -> Double {
        roundTo(value * (family.factors[from] ?? 1) / (family.factors[to] ?? 1), 3)
    }

    // MARK: - Calculations

    struct Sample: Sendable, Equatable {
        let tMs: Int64
        let value: Double?
    }

    struct Aggregate: Equatable, Sendable {
        let value: Double?
        let count: Int
    }

    static func aggregate(_ entries: [Sample], _ aggregation: String) -> Aggregate {
        let values = entries.compactMap { e in e.value.map { (e.tMs, $0) } }
        let n = values.count
        if aggregation == "count" { return Aggregate(value: Double(n), count: n) }
        guard let head = values.first else { return Aggregate(value: nil, count: 0) }
        switch aggregation {
        case "latest":
            var best = head
            for item in values.dropFirst() where item.0 >= best.0 { best = item }
            return Aggregate(value: best.1, count: n)
        case "sum":
            var total = 0.0
            for item in values { total += item.1 }
            return Aggregate(value: total, count: n)
        case "average":
            var total = 0.0
            for item in values { total += item.1 }
            return Aggregate(value: roundTo(total / Double(n), 2), count: n)
        case "min":
            return Aggregate(value: values.map(\.1).min(), count: n)
        case "max":
            return Aggregate(value: values.map(\.1).max(), count: n)
        default:
            return Aggregate(value: nil, count: n)
        }
    }

    struct WaterStatus: Equatable, Sendable {
        let intakeMl: Int
        let goalMl: Int?
        let remainingMl: Int?
        let percent: Int?
    }

    static func waterStatus(intakeMl: Int, goalMl: Int?) -> WaterStatus {
        guard let goalMl, goalMl > 0 else { return WaterStatus(intakeMl: intakeMl, goalMl: nil, remainingMl: nil, percent: nil) }
        return WaterStatus(intakeMl: intakeMl, goalMl: goalMl, remainingMl: max(0, goalMl - intakeMl),
                           percent: percent(Double(intakeMl), Double(goalMl)))
    }

    struct Progress: Equatable, Sendable {
        let value: Double
        let target: Double?
        let remaining: Double?
        let percent: Int?
    }

    static func progress(value: Double, target: Double?) -> Progress {
        guard let target, target > 0 else { return Progress(value: roundTo(value, 1), target: nil, remaining: nil, percent: nil) }
        return Progress(value: roundTo(value, 1), target: roundTo(target, 1),
                        remaining: roundTo(max(0.0, target - value), 1), percent: percent(value, target))
    }

    static func bmi(weightKg: Double?, heightCm: Double?) -> Double? {
        guard let weightKg, let heightCm, weightKg > 0, heightCm > 0 else { return nil }
        let m = heightCm / 100.0
        return roundTo(weightKg / (m * m), 1)
    }

    struct WeightChange: Equatable, Sendable {
        let firstKg: Double?
        let lastKg: Double?
        let changeKg: Double?
        let count: Int
    }

    static func weightChange(_ entries: [(tMs: Int64, kg: Double)]) -> WeightChange {
        let ordered = entries.enumerated().sorted { ($0.element.tMs, $0.offset) < ($1.element.tMs, $1.offset) }.map(\.element)
        guard let first = ordered.first, let last = ordered.last else {
            return WeightChange(firstKg: nil, lastKg: nil, changeKg: nil, count: 0)
        }
        return WeightChange(firstKg: roundTo(first.kg, 1), lastKg: roundTo(last.kg, 1),
                            changeKg: roundTo(last.kg - first.kg, 1), count: ordered.count)
    }

    struct SetVolume: Equatable, Sendable {
        let sets: Int
        let reps: Int
        let volumeKg: Double
    }

    static func setVolume(_ sets: [(weightKg: Double, reps: Int)]) -> SetVolume {
        var reps = 0
        var volume = 0.0
        for set in sets {
            reps += set.reps
            volume += set.weightKg * Double(set.reps)
        }
        return SetVolume(sets: sets.count, reps: reps, volumeKg: roundTo(volume, 1))
    }

    struct FastingProgress: Equatable, Sendable {
        let elapsedS: Int64
        let goalS: Int64
        let remainingS: Int64
        let percent: Int?
        let reached: Bool
    }

    static func fastingProgress(startedMs: Int64, goalMinutes: Int, nowMs: Int64) -> FastingProgress {
        let elapsed = max(0, (nowMs - startedMs) / 1000)
        let goal = Int64(goalMinutes) * 60
        return FastingProgress(elapsedS: elapsed, goalS: goal, remainingS: max(0, goal - elapsed),
                               percent: goal > 0 ? percent(Double(elapsed), Double(goal)) : nil,
                               reached: goal > 0 && elapsed >= goal)
    }

    // MARK: - Vector adapter (`compute` in the reference)

    static func compute(catalog: ActionCatalog, op: String, args: [String: Any]) -> [String: Any]? {
        func double(_ key: String) -> Double? { (args[key] as? NSNumber)?.doubleValue }
        func null(_ value: Any?) -> Any { value ?? NSNull() }
        switch op {
        case "convert":
            guard let familyName = args["family"] as? String, let family = catalog.units[familyName],
                  let from = args["from"] as? String, let to = args["to"] as? String, let value = double("value") else { return nil }
            return ["value": convert(value, family: family, from: from, to: to)]
        case "aggregate":
            let entries = (args["entries"] as? [[String: Any]] ?? []).map {
                Sample(tMs: ($0["t_ms"] as? NSNumber)?.int64Value ?? 0, value: ($0["value"] as? NSNumber)?.doubleValue)
            }
            let result = aggregate(entries, args["aggregation"] as? String ?? "")
            return ["value": null(result.value), "count": result.count]
        case "water_status":
            let status = waterStatus(intakeMl: (args["intake_ml"] as? NSNumber)?.intValue ?? 0,
                                     goalMl: (args["goal_ml"] as? NSNumber)?.intValue)
            return ["intake_ml": status.intakeMl, "goal_ml": null(status.goalMl), "remaining_ml": null(status.remainingMl),
                    "percent": null(status.percent)]
        case "progress":
            let p = progress(value: double("value") ?? 0, target: double("target"))
            return ["value": p.value, "target": null(p.target), "remaining": null(p.remaining), "percent": null(p.percent)]
        case "bmi":
            return ["bmi": null(bmi(weightKg: double("weight_kg"), heightCm: double("height_cm")))]
        case "weight_change":
            let entries = (args["entries"] as? [[String: Any]] ?? []).map {
                (tMs: ($0["t_ms"] as? NSNumber)?.int64Value ?? 0, kg: ($0["kg"] as? NSNumber)?.doubleValue ?? 0)
            }
            let c = weightChange(entries)
            return ["first_kg": null(c.firstKg), "last_kg": null(c.lastKg), "change_kg": null(c.changeKg), "count": c.count]
        case "set_volume":
            let sets = (args["sets"] as? [[String: Any]] ?? []).map {
                (weightKg: ($0["weight_kg"] as? NSNumber)?.doubleValue ?? 0, reps: ($0["reps"] as? NSNumber)?.intValue ?? 0)
            }
            let v = setVolume(sets)
            return ["sets": v.sets, "reps": v.reps, "volume_kg": v.volumeKg]
        case "fasting_progress":
            let p = fastingProgress(startedMs: (args["started_ms"] as? NSNumber)?.int64Value ?? 0,
                                    goalMinutes: (args["goal_minutes"] as? NSNumber)?.intValue ?? 0,
                                    nowMs: (args["now_ms"] as? NSNumber)?.int64Value ?? 0)
            return ["elapsed_s": p.elapsedS, "goal_s": p.goalS, "remaining_s": p.remainingS, "percent": null(p.percent),
                    "reached": p.reached]
        default:
            return nil
        }
    }
}
