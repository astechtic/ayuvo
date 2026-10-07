import Foundation

/// The common analytics `inputs` object (module docstring of `scripts/analytics_reference.py`).
nonisolated struct AInputs: Sendable {
    var timeZone = "UTC"
    var series: [String: [String: Double]] = [:]
    var contexts: [String: [String: String]] = [:]
    var sources: [String: [String: String]] = [:]
    var nights: [String: ANight] = [:]
    var workouts: [AWorkout] = []
    /// `tracking.workouts` (default true).
    var trackingWorkouts = true
    var overnightFallback: [String] = []
    var scanFallback: [String] = []
    /// `{day: {calories, protein_g}}` (forecast features only).
    var nutrition: [String: (calories: Double?, proteinG: Double?)] = [:]
}

// Recovery Indicator v2 and the multi-signal anomaly state. Port of the matching sections of
// `scripts/analytics_reference.py`.

nonisolated enum AnalyticsRecovery {
    private static func sub(_ zDir: Double, _ rc: AJ) -> Double {
        AMath.clamp((rc["subscore_center"].double ?? 50) + (rc["subscore_slope"].double ?? 20) * zDir, 0.0, 100.0)
    }

    struct SleepPart: Sendable {
        var sub: Double
        var parts: [String: Double]
        var conf: Double
        var asleepMin: Double
        var needMin: Double
    }

    private static func sleepComponent(_ inputs: AInputs, day: String, _ rc: AJ, _ cfg: AnalyticsConfig) -> SleepPart? {
        let sc = rc["sleep"]
        let partsW = rc["components"].array.first { $0["id"].string == "sleep" }!["parts"]
        let nights = AnalyticsSleep.validNights(inputs.nights, cfg)
        guard let last = nights[day] else { return nil }
        let need = AnalyticsSleep.sleepNeed(nights: inputs.nights, day: day, cfg)
        let window = rc["baseline_window_days"].int ?? 60
        let minPoints = rc["min_points"].int ?? 7
        var parts: [String: Double] = [:]
        let deficit = max(0.0, need.needMin - last.asleepMin!)
        parts["duration"] = AMath.clamp(100.0 * (1.0 - deficit / (sc["duration_zero_deficit_min"].double ?? 180)), 0.0, 100.0)
        var eff: [String: Double] = [:]
        for (d, n) in nights { if let e = n.efficiency { eff[d] = e } }
        if last.efficiency != nil {
            let b = AnalyticsCore.robust(eff, day: day, window: window, minPoints: minPoints,
                                         spreadFloor: cfg.metric("sleep_efficiency")["spread_floor"].double ?? 2, cfg)
            if let z = b.z { parts["efficiency"] = sub(z, rc) }
        }
        if let mid = last.midpointClock {
            let prior = AMath.windowDays(day, -(sc["regularity_window_days"].int ?? 14), -1).compactMap { nights[$0]?.midpointClock }
            if prior.count >= (sc["regularity_min_nights"].int ?? 5) {
                let dev = abs(mid - AMath.median(prior))
                parts["regularity"] = AMath.clamp(100.0 * (1.0 - dev / (sc["regularity_zero_at_min"].double ?? 120)), 0.0, 100.0)
            }
        }
        var ws = 0.0, acc = 0.0
        for k in ["duration", "efficiency", "regularity"] {
            if let v = parts[k] {
                let w = partsW[k].double ?? 0
                ws += w
                acc += w * v
            }
        }
        let hist = AMath.windowDays(day, -window, -1).filter { nights[$0] != nil }.count
        let conf = cfg.cap("PROVIDER_DERIVED")
            * AMath.clamp(Double(hist) / (sc["history_target_nights"].double ?? 28), 0.0, 1.0).squareRoot()
            * (need.source == "personal" ? 1.0 : 0.8)
        return SleepPart(sub: acc / ws, parts: parts, conf: conf, asleepMin: last.asleepMin!, needMin: need.needMin)
    }

    private struct Item {
        var id: String
        var weight: Double
        var available = false
        var value: Double?
        var baseline: Double?
        var z: Double?
        var subscore: Double?
        var impact: Double?
        var confidence = 0.0
        var parts: [String: Double]?
        var unit: String?
        var n = 0
        var context: String?

        var json: AJ {
            .obj(["id": .str(id), "weight": .num(weight), "available": .bool(available),
                  "value": .n(AMath.roundTo(value, 2)), "baseline": .n(AMath.roundTo(baseline, 2)),
                  "z": .n(AMath.roundTo(z, 2)), "subscore": .n(AMath.roundTo(subscore, 1)),
                  "impact": .n(AMath.roundTo(impact, 1)), "confidence": .num(AMath.roundTo(confidence, 2)),
                  "parts": parts.map { .obj($0.mapValues { .num(AMath.roundTo($0, 1)) }) } ?? .null,
                  "unit": .s(unit), "n": .i(n), "context": .s(context)])
        }
    }

    private struct Driver {
        var id: String
        var direction: String
        var impact: Double
        var value: Double?
        var baseline: Double?
        var z: Double?
        var unit: String?
        var text: String
        var textKey: String

        var json: AJ {
            .obj(["id": .str(id), "direction": .str(direction), "impact": .num(AMath.roundTo(impact, 1)),
                  "value": .n(AMath.roundTo(value, 2)), "baseline": .n(AMath.roundTo(baseline, 2)),
                  "z": .n(AMath.roundTo(z, 2)), "unit": .s(unit), "text": .str(text), "text_key": .str(textKey)])
        }
    }

    static func recovery(_ inputs: AInputs, day: String, _ cfg: AnalyticsConfig) -> AJ {
        let rc = cfg["recovery"]
        let fallback = Set(inputs.overnightFallback)
        let scans = Set(inputs.scanFallback)
        let window = rc["baseline_window_days"].int ?? 60
        let minPoints = rc["min_points"].int ?? 7
        let heartIDs = rc["heart_components"].array.compactMap(\.string)
        var comps: [Item] = []
        var warnings: [(String, String)] = []
        var totalW = 0.0
        for c in rc["components"].array { totalW += c["weight"].double ?? 0 }
        var heartHist = 0
        for c in rc["components"].array {
            let id = c["id"].string ?? ""
            var item = Item(id: id, weight: c["weight"].double ?? 0)
            let mode = c["mode"].string ?? ""
            if mode == "sleep" {
                item.unit = "min"
                if let s = sleepComponent(inputs, day: day, rc, cfg) {
                    item.available = true
                    item.subscore = s.sub
                    item.confidence = s.conf
                    item.value = s.asleepMin
                    item.baseline = s.needMin
                    item.parts = s.parts
                }
                comps.append(item)
                continue
            }
            let metric = c["metric"].string ?? ""
            let m = cfg.metric(metric)
            item.unit = m["unit"].string
            let b = AnalyticsCore.robust(inputs.series[metric] ?? [:], day: day, window: window, minPoints: minPoints,
                                         spreadFloor: m["spread_floor"].double ?? 0, cfg, contexts: inputs.contexts[metric],
                                         sources: inputs.sources[metric])
            item.value = b.value
            item.baseline = b.median
            item.z = b.z
            item.n = b.n
            item.context = b.context
            if heartIDs.contains(id) && b.median != nil { heartHist += 1 }
            if let z = b.z {
                let tolZ = c["tolerance_z"].double ?? 0
                let s: Double
                switch mode {
                case "higher": s = sub(z, rc)
                case "lower": s = sub(-z, rc)
                case "band": s = sub(-max(0.0, abs(z) - tolZ), rc)
                default: s = sub(-max(0.0, -z - tolZ), rc)
                }
                item.available = true
                item.subscore = s
                item.confidence = AnalyticsCore.confidence(m["classification"].string ?? "PROVIDER_DERIVED",
                                                           coverage: b.coverage, n: b.n,
                                                           targetN: cfg["confidence"]["target_n"].int ?? 28,
                                                           context: b.context, sourceChanged: b.sourceChanged, cfg)
                if b.sourceChanged { warnings.append(("source_changed", id)) }
                if fallback.contains(metric) { warnings.append(("overnight_fallback", id)) }
                if scans.contains(metric) || b.context == "camera" { warnings.append(("camera", id)) }
            }
            comps.append(item)
        }
        let byID = Dictionary(uniqueKeysWithValues: comps.map { ($0.id, $0) })
        let nights = AnalyticsSleep.validNights(inputs.nights, cfg)
        let sleepHist = AMath.windowDays(day, -window, -1).filter { nights[$0] != nil }.count
        var out = AJObject()
        out["algorithm_id"] = rc["algorithm_id"]
        out["algorithm_version"] = rc["algorithm_version"]
        out["weights_version"] = rc["weights_version"]
        out["config_version"] = .i(cfg.configVersion)
        out["day"] = .str(day)
        out["status"] = .str("VALID")
        for k in ["reason", "score", "label", "label_text", "recommendation", "coverage", "collecting", "load", "summary"] {
            out[k] = .null
        }
        out["confidence"] = .num(0)
        out["confidence_band"] = .str("low")
        out["drivers"] = .arr([])
        out["positives"] = .arr([])
        out["negatives"] = .arr([])
        out["warnings"] = .arr([])
        out["classification"] = .str("PERSONALIZED_STATISTICAL")

        func finish() -> AJ {
            out["components"] = .arr(comps.map(\.json))
            return out.value
        }

        if heartHist == 0 || sleepHist < minPoints {
            let heartN = heartIDs.compactMap { byID[$0]?.n }.max() ?? 0
            let have = min(sleepHist, max(heartN, 0))
            out["status"] = .str("INSUFFICIENT_HISTORY")
            out["reason"] = .str("collecting")
            out["collecting"] = .obj(["have": .i(min(have, minPoints)), "need": .i(minPoints)])
            return finish()
        }
        if !(byID["sleep"]?.available ?? false) {
            out["status"] = .str("NO_DATA")
            out["reason"] = .str("no_sleep")
            return finish()
        }
        if !heartIDs.contains(where: { byID[$0]?.available ?? false }) {
            out["status"] = .str("NO_DATA")
            out["reason"] = .str("no_heart_data")
            return finish()
        }
        var wsum = 0.0
        for i in comps where i.available { wsum += i.weight }
        var score = 0.0, conf = 0.0
        let center = rc["subscore_center"].double ?? 50
        for k in comps.indices where comps[k].available {
            score += comps[k].weight * comps[k].subscore!
            conf += comps[k].weight * comps[k].confidence
            comps[k].impact = (comps[k].subscore! - center) * comps[k].weight / wsum
        }
        score /= wsum
        conf /= totalW
        for i in comps where !i.available { warnings.append(("missing", i.id)) }
        let ld = AnalyticsLoad.load(workouts: inputs.workouts, day: AMath.addDays(day, -1), timeZone: inputs.timeZone,
                                    tracking: inputs.trackingWorkouts, cfg)
        var mod = 0.0
        if ld.status == "VALID" || ld.status == "LOW_CONFIDENCE", let st = ld.state {
            mod = rc["load_modifier"][st].double ?? 0
        }
        let final = Int(AMath.clamp(Double(AMath.roundInt(score + mod)), 0, 100))
        let band = rc["bands"].array.first { Double(final) >= ($0["min"].double ?? 0) }!
        let tpl = rc["drivers"]
        let neutral = rc["impact_neutral"].double ?? 0.5
        var drivers: [Driver] = []
        for i in comps where i.available {
            let impact = i.impact!
            let direction = impact > neutral ? "positive" : (impact < -neutral ? "negative" : "neutral")
            var key = direction
            // Never call a short night "adequate" because timing and efficiency were good.
            if i.id == "sleep", direction != "negative", let need = i.baseline, let asleep = i.value,
               need - asleep >= (rc["sleep"]["short_note_deficit_min"].double ?? 30) {
                key = direction + "_short"
            }
            drivers.append(Driver(id: i.id, direction: direction, impact: impact, value: i.value, baseline: i.baseline,
                                  z: i.z, unit: i.unit, text: tpl[i.id][key].string ?? "", textKey: "\(i.id).\(key)"))
        }
        if mod < 0 {
            drivers.append(Driver(id: "training_load", direction: "negative", impact: mod, value: ld.today, baseline: nil,
                                  z: nil, unit: "AU", text: tpl["training_load"]["negative"].string ?? "", textKey: "training_load.negative"))
        }
        drivers.sort { a, b in abs(a.impact) != abs(b.impact) ? abs(a.impact) > abs(b.impact) : DerivedMath.pyLess(a.id, b.id) }
        let neg = drivers.filter { $0.direction == "negative" }
        let pos = drivers.filter { $0.direction == "positive" }
        let st = rc["summary"]
        var parts: [String] = []
        if !neg.isEmpty {
            parts.append((st["lead_negative"].string ?? "").replacingOccurrences(of: "{items}", with: neg.map(\.text).joined(separator: "; ")))
        }
        if !pos.isEmpty {
            parts.append((st["lead_positive"].string ?? "").replacingOccurrences(of: "{items}", with: pos.map(\.text).joined(separator: "; ")))
        }
        var seen = Set<String>()
        var uniq: [AJ] = []
        for (code, metric) in warnings where seen.insert(code + "\u{1}" + metric).inserted {
            uniq.append(.obj(["code": .str(code), "metric": .str(metric)]))
        }
        out["status"] = .str(AnalyticsCore.statusFor(conf, cfg))
        out["score"] = .i(final)
        out["label"] = band["id"]
        out["label_text"] = band["label"]
        out["recommendation"] = band["recommendation"]
        out["confidence"] = .num(AMath.roundTo(conf, 2))
        out["confidence_band"] = .str(AnalyticsCore.confidenceBand(conf, cfg))
        out["coverage"] = .num(AMath.roundTo(wsum / totalW, 2))
        out["drivers"] = .arr(drivers.map(\.json))
        out["positives"] = .arr(pos.map { .str($0.id) })
        out["negatives"] = .arr(neg.map { .str($0.id) })
        out["warnings"] = .arr(uniq)
        out["summary"] = .str(parts.isEmpty ? (st["none"].string ?? "") : parts.joined(separator: " "))
        let pm = ld.primary
        out["load"] = .obj(["day": .str(ld.day), "state": .s(ld.state), "status": .str(ld.status),
                            "method": .s(ld.primaryMethod), "modifier": .num(mod), "acute": .n(pm?.acute),
                            "chronic": .n(pm?.chronic), "ratio": .n(pm?.ratio)])
        return finish()
    }

    // MARK: Anomaly

    private static func signalSeries(_ inputs: AInputs, _ metric: String, _ cfg: AnalyticsConfig) -> [String: Double] {
        if metric == "sleep_duration" { return AnalyticsSleep.validNights(inputs.nights, cfg).mapValues { $0.asleepMin! } }
        if metric == "training_load" {
            return AnalyticsLoad.loadDays(inputs.workouts, timeZone: inputs.timeZone).mapValues(\.minutes)
        }
        return inputs.series[metric] ?? [:]
    }

    private struct Signal {
        var id: String
        var value: Double?
        var median: Double?
        var z: Double
        var zBad: Double
        var flagged: Bool
    }

    private static func anomalyDay(_ inputs: AInputs, day: String, _ ac: AJ, _ cfg: AnalyticsConfig) -> (String?, [Signal]) {
        var sigs: [Signal] = []
        let mild = ac["mild_z"].double ?? 2
        for s in ac["signals"].array {
            let metric = s["metric"].string ?? ""
            let m = cfg.metric(metric)
            let series = signalSeries(inputs, metric, cfg)
            if metric == "training_load" && series[day] == nil { continue }
            let b = AnalyticsCore.robust(series, day: day, window: ac["baseline_window_days"].int ?? 28,
                                         minPoints: ac["min_points"].int ?? 14, spreadFloor: m["spread_floor"].double ?? 0,
                                         cfg, contexts: inputs.contexts[metric])
            guard let z = b.z else { continue }
            let bad = s["bad"].string ?? "band"
            let zb = bad == "low" ? -z : (bad == "high" ? z : abs(z))
            sigs.append(Signal(id: s["id"].string ?? "", value: b.value, median: b.median, z: z, zBad: zb, flagged: zb >= mild))
        }
        let flagged = sigs.filter(\.flagged)
        let state: String?
        if sigs.count < (ac["min_signals"].int ?? 2) {
            state = nil
        } else if flagged.count >= (ac["multi_signal_count"].int ?? 3) {
            state = "MULTI_SIGNAL_DEVIATION"
        } else if sigs.contains(where: { $0.zBad >= (ac["significant_z"].double ?? 3) }) {
            state = "SIGNIFICANT_DEVIATION"
        } else if !flagged.isEmpty {
            state = "MILD_DEVIATION"
        } else {
            state = "NORMAL"
        }
        return (state, sigs)
    }

    static func anomaly(_ inputs: AInputs, day: String, _ cfg: AnalyticsConfig) -> AJ {
        let ac = cfg["anomaly"]
        let (state, sigs) = anomalyDay(inputs, day: day, ac, cfg)
        var out = AJObject()
        out["algorithm_id"] = ac["algorithm_id"]
        out["algorithm_version"] = ac["algorithm_version"]
        out["day"] = .str(day)
        out["status"] = .str("INSUFFICIENT_DATA")
        out["state"] = .null
        out["message"] = .null
        out["persistent"] = .bool(false)
        out["persistent_note"] = .null
        out["flagged_count"] = .i(0)
        out["classification"] = .str("PERSONALIZED_STATISTICAL")
        out["confidence"] = .num(0)
        out["signals"] = .arr(sigs.map {
            .obj(["id": .str($0.id), "value": .n(AMath.roundTo($0.value, 2)), "median": .n(AMath.roundTo($0.median, 2)),
                  "z": .num(AMath.roundTo($0.z, 2)), "z_bad": .num(AMath.roundTo($0.zBad, 2)), "flagged": .bool($0.flagged)])
        })
        guard let state else { return out.value }
        let serious: Set<String> = ["SIGNIFICANT_DEVIATION", "MULTI_SIGNAL_DEVIATION"]
        var persistent = serious.contains(state)
        let days = ac["persistent_days"].int ?? 3
        var k = 1
        while k < days && persistent {
            persistent = anomalyDay(inputs, day: AMath.addDays(day, -k), ac, cfg).0.map(serious.contains) ?? false
            k += 1
        }
        let conf = AMath.clamp(Double(sigs.count) / Double(ac["signals"].array.count), 0.0, 1.0)
            * cfg.cap("PERSONALIZED_STATISTICAL")
        out["status"] = .str(AnalyticsCore.statusFor(conf, cfg))
        out["state"] = .str(state)
        out["message"] = ac["states"][state]
        out["persistent"] = .bool(persistent)
        out["persistent_note"] = persistent ? ac["persistent_note"] : .null
        out["flagged_count"] = .i(sigs.filter(\.flagged).count)
        out["confidence"] = .num(AMath.roundTo(conf, 2))
        return out.value
    }
}
