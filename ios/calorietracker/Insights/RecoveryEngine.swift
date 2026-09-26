import Foundation

// Daily Recovery (0–100): weighted personal-baseline z-scores of last night's signals, a training-load modifier,
// a label and the signals behind it. Port of the "Recovery" section of `scripts/insights_reference.py`.

nonisolated enum RecoveryEngine {
    private static func subscore(_ zDir: Double, _ rc: InsightsConfig.Recovery) -> Double {
        InsightsMath.clamp(rc.subscoreCenter + rc.subscoreSlope * zDir, 0.0, 100.0)
    }

    /// (deviation minutes, sub-score) of last night's midpoint vs the mean midpoint of the prior 14 nights.
    private static func consistency(_ nights: [String: InsightsNight], _ day: String, _ tz: TimeZone,
                                    _ rc: InsightsConfig.Recovery) -> (Double, Double)? {
        var prior: [Double] = []
        for k in stride(from: rc.consistencyWindowDays, to: 0, by: -1) {
            let d = InsightsDay.add(day, -k)
            if let n = nights[d] {
                prior.append(Double(InsightsDay.sleepMidpointMinutes(startMs: n.startMs, endMs: n.endMs, wakeDay: d, tz)))
            }
        }
        guard prior.count >= rc.consistencyMinNights, let today = nights[day] else { return nil }
        let mid = Double(InsightsDay.sleepMidpointMinutes(startMs: today.startMs, endMs: today.endMs, wakeDay: day, tz))
        let dev = abs(mid - InsightsMath.mean(prior))
        return (dev, InsightsMath.clamp(100.0 * (1.0 - dev / rc.consistencyZeroAtMin), 0.0, 100.0))
    }

    /// Morning readiness for `day` (the wake day of last night's sleep). See docs/insights.md.
    static func recovery(_ inputs: InsightsInputs, day: String, config: InsightsConfig) -> RecoveryResult {
        let rc = config.recovery
        let tz = InsightsDay.timeZone(inputs.timeZone)
        let nights = BaselineEngine.validNights(inputs, config: config)
        let sleepSeries = nights.mapValues(\.asleepMin)
        let fallback = Set(inputs.overnightFallback)
        var comps: [RecoveryComponent] = []
        var raws: [String: BaselineEngine.Raw] = [:]
        for c in rc.components {
            guard let m = config.metrics[c.metric] else { continue }
            let s = c.id == "sleep" ? sleepSeries : (inputs.series[c.metric] ?? [:])
            let b = BaselineEngine.baselineRaw(s, day, m)
            var item = RecoveryComponent(
                id: c.id, weight: c.weight, available: false, value: b.recent, baseline: b.mean, delta: b.delta,
                pct: b.pct, z: b.z, subscore: nil, impact: nil, baselineN: b.n, baselineConfidence: b.confidence,
                fallback: fallback.contains(c.metric), consistencyDeviationMin: nil, consistencySubscore: nil)
            if b.isOK, b.recent != nil, let z = b.z {
                var sub: Double
                switch c.mode {
                case "higher": sub = subscore(z, rc)
                case "lower": sub = subscore(-z, rc)
                case "band": sub = subscore(-max(0.0, abs(z) - (c.toleranceZ ?? 0)), rc)
                case "drop_only": sub = subscore(-max(0.0, -z - (c.toleranceZ ?? 0)), rc)
                default:
                    // Sleep: duration vs personal baseline, blended with midpoint consistency when available.
                    sub = subscore(z, rc)
                    if let (dev, cons) = consistency(nights, day, tz, rc) {
                        item.consistencyDeviationMin = dev
                        item.consistencySubscore = cons
                        sub = (c.durationShare ?? 1) * sub + (c.consistencyShare ?? 0) * cons
                    }
                }
                item.available = true
                item.subscore = sub
            }
            comps.append(item)
            raws[c.id] = b
        }
        let sleepB = raws["sleep"]
        let sleepAvailable = comps.first { $0.id == "sleep" }?.available ?? false
        let heart = rc.heartComponents.compactMap { raws[$0] }
        let need = config.metrics["sleep"]?.minPoints ?? 14
        let have = min(sleepB?.n ?? 0, heart.map(\.n).max() ?? 0)
        var out = RecoveryResult(day: day, status: "ok", components: comps, positives: [], negatives: [])
        if !(sleepB?.isOK ?? false) || heart.allSatisfy({ !$0.isOK }) {
            out.status = "collecting"
            out.collecting = InsightsCollecting(have: min(have, need), need: need)
        } else if !sleepAvailable {
            out.status = "no_sleep"
        } else if !comps.contains(where: { rc.heartComponents.contains($0.id) && $0.available }) {
            out.status = "no_heart_data"
        }
        if out.status != "ok" { return rounded(out) }

        var wsum = 0.0
        for i in comps where i.available { wsum += i.weight }
        var score = 0.0
        for idx in comps.indices where comps[idx].available {
            let sub = comps[idx].subscore ?? 0
            score += comps[idx].weight * sub
            comps[idx].impact = (sub - rc.subscoreCenter) * comps[idx].weight / wsum
        }
        score /= wsum
        let loads = TrainingLoadEngine.dailyLoads(inputs.workouts, timeZone: tz, config: config)
        let load = TrainingLoadEngine.loadRaw(loads, InsightsDay.add(day, -1), config: config)
        var mod = 0
        if load.category == "high" {
            let lm = rc.loadModifier
            mod = (load.ratio.map { $0 > lm.veryHighRatio } ?? false) ? lm.veryHigh : lm.high
        }
        let final = min(max(InsightsMath.roundInt(score + Double(mod)), 0), 100)
        let band = rc.bands.first { final >= $0.min } ?? rc.bands[rc.bands.count - 1]
        let avail = comps.filter(\.available)
        let allHigh = avail.allSatisfy { $0.baselineConfidence == "high" }
        let conf = avail.count >= 4 && allHigh ? "high" : (avail.count >= 3 || allHigh ? "medium" : "low")
        var signals: [RecoverySignal] = []
        for i in avail {
            let template = rc.contributors[i.id] ?? i.id
            signals.append(RecoverySignal(id: i.id, impact: i.impact ?? 0,
                                          text: InsightsFormat.fill(template, contributorParams(i))))
        }
        let pos = stableSorted(signals.filter { $0.impact > 0 }) { -$0.impact }
        var neg = stableSorted(signals.filter { $0.impact < 0 }) { $0.impact }
        let loadLabel = config.trainingLoad.labels[load.category] ?? load.category
        if mod < 0 {
            neg.append(RecoverySignal(id: "training_load", impact: Double(mod),
                                      text: InsightsFormat.fill(rc.contributors["training_load"] ?? "", ["category": .text(loadLabel)])))
            neg = stableSorted(neg) { $0.impact }
        }
        out.components = comps
        out.score = final
        out.label = band.id
        out.labelText = band.label
        out.recommendation = band.recommendation
        out.confidence = conf
        out.positives = pos
        out.negatives = neg
        out.load = RecoveryLoad(day: load.day, load: load.load, mean28d: load.mean28d, ratio: load.ratio,
                                category: load.category, label: loadLabel, modifier: mod)
        return rounded(out)
    }

    /// Stable ascending sort by key (ties keep their order).
    static func stableSorted<T>(_ items: [T], by key: (T) -> Double) -> [T] {
        items.enumerated().sorted { a, b in
            let ka = key(a.element), kb = key(b.element)
            return ka != kb ? ka < kb : a.offset < b.offset
        }.map(\.element)
    }

    private static func contributorParams(_ i: RecoveryComponent) -> [String: InsightsParam] {
        let value = i.value ?? 0
        let delta = i.delta ?? 0
        return ["pct": .text(InsightsFormat.signed(i.pct ?? 0.0, 0)), "delta0": .text(InsightsFormat.signed(delta, 0)),
                "delta1": .text(InsightsFormat.signed(delta, 1)),
                "value1": .text(String(format: "%.1f", InsightsMath.roundTo(value, 1))),
                "duration": .text(InsightsFormat.duration(value))]
    }

    private static func rounded(_ result: RecoveryResult) -> RecoveryResult {
        var out = result
        for idx in out.components.indices {
            var i = out.components[idx]
            i.value = InsightsMath.roundTo(i.value, 2)
            i.baseline = InsightsMath.roundTo(i.baseline, 2)
            i.delta = InsightsMath.roundTo(i.delta, 2)
            i.z = InsightsMath.roundTo(i.z, 2)
            i.pct = InsightsMath.roundTo(i.pct, 1)
            i.subscore = InsightsMath.roundTo(i.subscore, 1)
            i.impact = InsightsMath.roundTo(i.impact, 1)
            i.consistencyDeviationMin = InsightsMath.roundTo(i.consistencyDeviationMin, 1)
            i.consistencySubscore = InsightsMath.roundTo(i.consistencySubscore, 1)
            out.components[idx] = i
        }
        out.positives = out.positives.map { var s = $0; s.impact = InsightsMath.roundTo(s.impact, 1); return s }
        out.negatives = out.negatives.map { var s = $0; s.impact = InsightsMath.roundTo(s.impact, 1); return s }
        if var load = out.load {
            load.load = InsightsMath.roundTo(load.load, 1)
            load.mean28d = InsightsMath.roundTo(load.mean28d, 1)
            load.ratio = InsightsMath.roundTo(load.ratio, 2)
            out.load = load
        }
        return out
    }
}
