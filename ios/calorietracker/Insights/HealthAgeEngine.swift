import Foundation

// Ayuvo Health Age: each marker's 90-day average turned into a capped years offset, weighted over the available
// markers, plus a 12-week pace. Port of the "Health Age" section of `scripts/insights_reference.py`.

nonisolated enum HealthAgeEngine {
    private struct MarkerValue {
        var value: Double?
        var secondary: Double?
        var basis: String?
        var days: Int
        var equivalentAge: Double?
        var offset: Double?
    }

    /// Sex-specific table; any other or missing sex → the element-wise mean of the male and female tables.
    private static func sexTable(_ tables: [String: [[Double]]], _ sex: String?) -> [[Double]] {
        if let sex, sex == "male" || sex == "female", let t = tables[sex] { return t }
        let m = tables["male"] ?? [], f = tables["female"] ?? []
        return zip(m, f).map { [$0[0], ($0[1] + $1[1]) / 2.0] }
    }

    private static func window(_ series: [String: Double], _ asOf: String, _ days: Int) -> [Double] {
        BaselineEngine.values(series, asOf, -(days - 1), 0).map(\.1)
    }

    private static func marker(_ mk: InsightsConfig.HealthAge.Marker, _ inputs: InsightsInputs, _ asOf: String,
                               _ profile: InsightsProfile, _ actual: Double, _ config: InsightsConfig) -> MarkerValue {
        let ha = config.healthAge
        let win = ha.windowDays
        let tz = InsightsDay.timeZone(inputs.timeZone)
        let sex = profile.sex
        switch mk.method {
        case "age_norm", "dose_response":
            let vals = window(inputs.series[mk.series ?? mk.id] ?? [:], asOf, win)
            if vals.count < mk.minDays { return MarkerValue(days: vals.count) }
            let v = InsightsMath.mean(vals)
            if mk.method == "dose_response" {
                return MarkerValue(value: v, days: vals.count, offset: InsightsMath.interp(mk.points ?? [[0, 0]], v))
            }
            let tables = mk.tablesByKind.map { $0[inputs.hrvKind] ?? [:] } ?? mk.tables ?? [:]
            let eq = InsightsMath.inverseAge(sexTable(tables, sex), v, ha.ageMin, ha.ageMax)
            return MarkerValue(value: v, days: vals.count, equivalentAge: eq, offset: eq - actual)
        case "sleep":
            let nights = BaselineEngine.validNights(inputs, config: config)
            var use: [(String, InsightsNight)] = []
            for k in stride(from: win - 1, through: 0, by: -1) {
                let d = InsightsDay.add(asOf, -k)
                if let n = nights[d] { use.append((d, n)) }
            }
            if use.count < mk.minDays { return MarkerValue(days: use.count) }
            let hours = InsightsMath.mean(use.map { $0.1.asleepMin / 60.0 })
            let mids = use.map { Double(InsightsDay.sleepMidpointMinutes(startMs: $0.1.startMs, endMs: $0.1.endMs, wakeDay: $0.0, tz)) }
            let sd = InsightsMath.sampleSD(mids, InsightsMath.mean(mids))
            return MarkerValue(value: hours, secondary: sd, days: use.count,
                               offset: InsightsMath.interp(mk.durationPoints ?? [[0, 0]], hours)
                                   + InsightsMath.interp(mk.regularityPoints ?? [[0, 0]], sd))
        case "workouts":
            let wear = window(inputs.series["steps"] ?? [:], asOf, win).count
            if !inputs.tracking.workouts || wear < mk.minDays { return MarkerValue(days: wear) }
            let loads = TrainingLoadEngine.dailyLoads(inputs.workouts, timeZone: tz, config: config)
            let weeks = mk.weeks ?? 12
            var total = 0.0
            var active = 0
            for w in stride(from: weeks - 1, through: 0, by: -1) {
                var block = 0.0
                for k in stride(from: 6, through: 0, by: -1) {
                    block += loads[InsightsDay.add(asOf, -(7 * w + k))]?.minutes ?? 0.0
                }
                total += block
                if block > 0 { active += 1 }
            }
            let perWeek = total / Double(weeks)
            let share = Double(active) / Double(weeks)
            return MarkerValue(value: perWeek, secondary: share, days: wear,
                               offset: InsightsMath.interp(mk.minutesPoints ?? [[0, 0]], perWeek)
                                   + InsightsMath.interp(mk.activeSharePoints ?? [[0, 0]], share))
        case "body_composition":
            let fat = window(inputs.series["body_fat"] ?? [:], asOf, win)
            if let sex, sex == "male" || sex == "female", fat.count >= mk.minDays, let points = mk.bodyFatPoints?[sex] {
                let v = InsightsMath.mean(fat)
                return MarkerValue(value: v, days: fat.count, offset: InsightsMath.interp(points, v))
            }
            var bmis = window(inputs.series["bmi"] ?? [:], asOf, win)
            if bmis.isEmpty, let height = profile.heightCm, height != 0 {
                let h = height / 100.0
                bmis = window(inputs.series["weight"] ?? [:], asOf, win).map { $0 / (h * h) }
            }
            if bmis.count < mk.minDays { return MarkerValue(days: bmis.count) }
            let v = InsightsMath.mean(bmis)
            return MarkerValue(value: v, basis: "bmi", days: bmis.count, offset: InsightsMath.interp(mk.bmiPoints ?? [[0, 0]], v))
        default:
            return MarkerValue(days: 0)
        }
    }

    /// Unrounded Health Age as of `asOf`.
    static func healthAgeRaw(_ inputs: InsightsInputs, asOf: String, profile: InsightsProfile, config: InsightsConfig) -> HealthAgeResult {
        let ha = config.healthAge
        var out = HealthAgeResult(asOf: asOf, status: "ok", markers: [], markersAvailable: 0, markersNeeded: ha.minMarkers)
        guard let bday = profile.birthday, !bday.isEmpty else {
            out.status = "no_birthday"
            return out
        }
        let actual = Double(InsightsDay.between(bday, asOf)) / ha.daysPerYear
        out.actualAge = actual
        if actual < ha.minActualAge {
            out.status = "unsupported_age"
            return out
        }
        var wsum = 0.0
        for mk in ha.markers {
            let r = marker(mk, inputs, asOf, profile, actual, config)
            var item = HealthAgeMarker(
                id: mk.id, method: mk.method, available: r.offset != nil, value: r.value, secondaryValue: r.secondary,
                basis: r.basis, days: r.days, neededDays: mk.minDays, equivalentAge: r.equivalentAge, offsetYears: nil,
                weight: mk.weight, contributionYears: nil)
            if mk.method == "body_composition", r.offset != nil, item.basis == nil { item.basis = "body_fat" }
            if let off = r.offset {
                item.offsetYears = InsightsMath.clamp(off, -mk.capYears, mk.capYears)
                wsum += mk.weight
            }
            out.markers.append(item)
        }
        let avail = out.markers.filter(\.available)
        out.markersAvailable = avail.count
        let core = Set(ha.coreMarkers)
        if avail.count < ha.minMarkers || !avail.contains(where: { core.contains($0.id) }) {
            let need = ha.collectingDays
            let best = (out.markers.filter { core.contains($0.id) }.map(\.days) + [0]).max() ?? 0
            out.status = "collecting"
            out.collecting = InsightsCollecting(have: min(best, need), need: need)
            return out
        }
        var acc = 0.0
        for idx in out.markers.indices where out.markers[idx].available {
            let c = out.markers[idx].weight * (out.markers[idx].offsetYears ?? 0) / wsum
            out.markers[idx].contributionYears = c
            acc += c
        }
        let diff = InsightsMath.clamp(acc, -ha.totalCapYears, ha.totalCapYears)
        let ids = Set(avail.map(\.id))
        let c = ha.confidence
        let conf: String
        if avail.count >= c.highMinMarkers, ids.contains(c.highRequires) {
            conf = "high"
        } else if avail.count >= c.mediumMinMarkers {
            conf = "medium"
        } else {
            conf = "low"
        }
        out.healthAge = actual + diff
        out.difference = diff
        out.confidence = conf
        return out
    }

    /// Ayuvo Health Age as of `asOf` (inclusive 90-day window).
    static func healthAge(_ inputs: InsightsInputs, asOf: String, profile: InsightsProfile, config: InsightsConfig) -> HealthAgeResult {
        var r = healthAgeRaw(inputs, asOf: asOf, profile: profile, config: config)
        r.actualAge = InsightsMath.roundTo(r.actualAge, 1)
        r.healthAge = InsightsMath.roundTo(r.healthAge, 1)
        r.difference = InsightsMath.roundTo(r.difference, 1)
        for idx in r.markers.indices {
            r.markers[idx].value = InsightsMath.roundTo(r.markers[idx].value, 2)
            r.markers[idx].secondaryValue = InsightsMath.roundTo(r.markers[idx].secondaryValue, 2)
            r.markers[idx].equivalentAge = InsightsMath.roundTo(r.markers[idx].equivalentAge, 1)
            r.markers[idx].offsetYears = InsightsMath.roundTo(r.markers[idx].offsetYears, 2)
            r.markers[idx].contributionYears = InsightsMath.roundTo(r.markers[idx].contributionYears, 2)
        }
        return r
    }

    /// Health Age as of each of the last `weeks` week-ends; pace = least-squares slope in years per calendar year.
    static func pace(_ inputs: InsightsInputs, asOf: String, profile: InsightsProfile, config: InsightsConfig) -> HealthAgePaceResult {
        let p = config.healthAge.pace
        var pts: [(Double, String, HealthAgeResult)] = []
        for k in stride(from: p.weeks, through: 0, by: -1) {
            let d = InsightsDay.add(asOf, -7 * k)
            let r = healthAgeRaw(inputs, asOf: d, profile: profile, config: config)
            if r.status == "ok" { pts.append((Double(-7 * k), d, r)) }
        }
        var out = HealthAgePaceResult(
            status: "insufficient", have: pts.count, needed: p.minPoints,
            points: pts.map { HealthAgePacePoint(day: $0.1, healthAge: InsightsMath.roundTo($0.2.healthAge, 1),
                                                 difference: InsightsMath.roundTo($0.2.difference, 1)) })
        guard pts.count >= p.minPoints,
              let slope = InsightsMath.olsSlope(pts.map { ($0.0, $0.2.healthAge ?? 0) }) else { return out }
        let pace = slope * config.healthAge.daysPerYear
        out.status = "ok"
        out.pace = InsightsMath.roundTo(pace, 2)
        out.direction = pace < p.improvingBelow ? "improving" : (pace > p.decliningAbove ? "declining" : "stable")
        return out
    }
}
