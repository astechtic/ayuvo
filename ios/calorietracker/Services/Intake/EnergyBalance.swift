import Foundation

// Daily energy balance, the adaptive energy estimate (intake and EWMA weight trend, 7,700 kcal per kg) and paired
// exposure/outcome comparisons. Port of `energy_balance` and `paired_difference` in `scripts/intake_reference.py`.

nonisolated enum EnergyBalance {
    struct Result: DerivedOutput {
        var balanceKcal: Double?
        var adaptiveTdee: Double?
        var trendChangeKg: Double?
        var status = "insufficient"

        var jsonObject: [String: Any] {
            let j = DerivedMath.json
            return ["balance_kcal": j(balanceKcal), "adaptive_tdee": j(adaptiveTdee), "trend_change_kg": j(trendChangeKg),
                    "status": status]
        }
    }

    /// `intake` logged days only, `tdee` measured, `weights` weigh-ins; all keyed "YYYY-MM-DD".
    static func energyBalance(day: String, intake: [String: Double], tdee: [String: Double], weights w: [String: Double],
                              config: IntakeConfig) -> Result {
        let th = config.thresholds
        var bal: Double?
        if let i = intake[day], let t = tdee[day] { bal = DerivedMath.roundTo(i - t, 0) }
        let n = th.adaptiveWindowDays
        let start = DerivedDay.addDays(day, -(n - 1))
        var logged: [Double] = []
        for i in 0..<max(n, 0) {
            if let v = intake[DerivedDay.addDays(start, i)] { logged.append(v) }
        }
        let weighins = w.keys.filter { !DerivedMath.pyLess($0, start) && !DerivedMath.pyLess(day, $0) }
        var out = Result(balanceKcal: bal)
        if logged.count < th.adaptiveMinIntakeDays || weighins.count < th.adaptiveMinWeighins { return out }
        guard let first = w.keys.min(by: DerivedMath.pyLess),
              let firstWeighin = weighins.min(by: DerivedMath.pyLess) else { return out }
        var trend: [String: Double] = [:]
        var cur: Double?
        var d = first
        while !DerivedMath.pyLess(day, d) {
            if let v = w[d] { cur = cur.map { $0 + th.ewmaAlpha * (v - $0) } ?? v }
            if let cur { trend[d] = cur }
            d = DerivedDay.addDays(d, 1)
        }
        guard let before = trend[DerivedDay.addDays(start, -1)] ?? trend[firstWeighin], let end = trend[day] else {
            return out
        }
        let change = end - before
        out.trendChangeKg = DerivedMath.roundTo(change, 2)
        out.adaptiveTdee = DerivedMath.roundTo(DerivedMath.mean(logged) - change * th.energyPerKg / Double(n), 0)
        out.status = "ok"
        return out
    }

    // MARK: paired_difference

    struct PairedResult: DerivedOutput {
        var nExposed = 0
        var nUnexposed = 0
        var difference: Double?
        var cohensD: Double?
        var status = "insufficient"

        var jsonObject: [String: Any] {
            let j = DerivedMath.json
            return ["n_exposed": nExposed, "n_unexposed": nUnexposed, "difference": j(difference), "cohens_d": j(cohensD),
                    "status": status]
        }
    }

    /// Compares the outcome `lagDays` after exposed vs unexposed days. Associational only.
    static func pairedDifference(exposure: [String: Bool], outcome: [String: Double], lagDays: Int,
                                 config: IntakeConfig) -> PairedResult {
        let th = config.thresholds
        var ex: [Double] = [], un: [Double] = []
        for d in IntakeMath.sortedKeys(exposure) {
            guard let o = outcome[DerivedDay.addDays(d, lagDays)] else { continue }
            if exposure[d] == true { ex.append(o) } else { un.append(o) }
        }
        var out = PairedResult(nExposed: ex.count, nUnexposed: un.count)
        if ex.count < th.pairMinGroup || un.count < th.pairMinGroup { return out }
        let me = DerivedMath.mean(ex), mu = DerivedMath.mean(un)
        func variance(_ v: [Double], _ m: Double) -> Double {
            var t = 0.0
            for x in v { t += (x - m) * (x - m) }
            return t / Double(v.count - 1)
        }
        let pooled = ((Double(ex.count - 1) * variance(ex, me) + Double(un.count - 1) * variance(un, mu))
            / Double(ex.count + un.count - 2)).squareRoot()
        out.difference = DerivedMath.roundTo(me - mu, 2)
        out.cohensD = pooled > 0 ? DerivedMath.roundTo((me - mu) / pooled, 2) : nil
        out.status = "ok"
        return out
    }
}
