import Foundation

/// Entry point of the analytics engine (docs/health-analytics.md): `run(function:input:)` mirrors
/// `analytics_reference.run_case`, including the compact vector encodings, so the shared vectors exercise exactly
/// the code the app runs.
nonisolated enum AnalyticsEngine {
    static let functions = [
        "source_select", "unit_convert", "input_hash", "robust_stats", "baseline", "baselines", "trend", "hrv_rr",
        "hrv_rr_day", "hrv_status", "sleep_need", "sleep_status", "load", "hrr", "recovery", "anomaly", "correlation",
        "response", "energy", "vo2max_trend", "met_intensity", "forecast", "evidence",
    ]

    // MARK: Decoding (vector encodings and plain dicts)

    /// `{"start": day, "values": [...]}` → `{day: value}`; any other object passes through.
    static func decodeSeries(_ x: AJ) -> [String: AJ] {
        let o = x.object
        if o.count == 2, let start = o["start"]?.string, let values = o["values"] {
            var out: [String: AJ] = [:]
            for (i, v) in values.array.enumerated() where !v.isNull { out[AMath.addDays(start, i)] = v }
            return out
        }
        return o.filter { !$0.value.isNull }
    }

    static func numbers(_ x: AJ) -> [String: Double] { decodeSeries(x).compactMapValues(\.double) }
    static func strings(_ x: AJ) -> [String: String] { decodeSeries(x).compactMapValues(\.string) }

    static func decodeNights(_ x: AJ) -> [String: ANight] {
        let o = x.object
        if o.count == 2, let start = o["start"]?.string, let list = o["nights"] {
            var out: [String: ANight] = [:]
            for (i, n) in list.array.enumerated() where !n.isNull {
                out[AMath.addDays(start, i)] = ANight(asleepMin: n[0].double, inBedMin: n[1].double, efficiency: n[2].double,
                                                      bedtimeClock: n[4].double, wakeClock: n[5].double,
                                                      midpointClock: n[3].double)
            }
            return out
        }
        var out: [String: ANight] = [:]
        for (d, n) in o where !n.isNull { out[d] = ANight(n) }
        return out
    }

    static func decodeInputs(_ j: AJ) -> AInputs {
        var inp = AInputs()
        inp.timeZone = j["time_zone"].string ?? "UTC"
        for (k, v) in j["series"].object { inp.series[k] = numbers(v) }
        for (k, v) in j["contexts"].object { inp.contexts[k] = strings(v) }
        for (k, v) in j["sources"].object { inp.sources[k] = strings(v) }
        inp.nights = decodeNights(j["nights"])
        inp.workouts = j["workouts"].array.map(AWorkout.init)
        inp.trackingWorkouts = j["tracking"]["workouts"].bool ?? true
        inp.overnightFallback = j["overnight_fallback"].array.compactMap(\.string)
        inp.scanFallback = j["scan_fallback"].array.compactMap(\.string)
        for (d, f) in j["nutrition"].object { inp.nutrition[d] = (f["calories"].double, f["protein_g"].double) }
        return inp
    }

    // MARK: Dispatch

    static func run(function: String, input inp: AJ, _ cfg: AnalyticsConfig) -> AJ? {
        switch function {
        case "source_select": return sourceSelect(inp, cfg)
        case "unit_convert":
            let r = AnalyticsCore.unitConvert(inp["value"].double, from: inp["from"].string ?? "", to: inp["to"].string ?? "")
            return .obj(["value": .n(r.value), "ok": .bool(r.ok)])
        case "input_hash": return AnalyticsCore.inputHash(inp["value"])
        case "robust_stats": return robustStats(inp)
        case "baseline", "baselines", "trend": return baselineFamily(function, inp, cfg)
        case "hrv_rr": return AnalyticsHRV.hrvRR(ibis(inp["ibis"]), cfg)
        case "hrv_rr_day":
            let series = inp["series"].array.map { AnalyticsHRV.BeatSeries(startMs: $0["start_ms"].int64 ?? 0, ibis: ibis($0["ibis"])) }
            let night = inp["night"].has ? (inp["night"]["start_ms"].int64 ?? 0, inp["night"]["end_ms"].int64 ?? 0) : nil
            return AnalyticsHRV.hrvRRDay(series: series, night: night.map { (startMs: $0.0, endMs: $0.1) }, cfg)
        case "hrv_status":
            return AnalyticsHRV.hrvStatus(series: numbers(inp["series"]), day: inp["day"].string ?? "", kind: inp["kind"].string,
                                          contexts: inp["contexts"].has ? strings(inp["contexts"]) : nil,
                                          sources: inp["sources"].has ? strings(inp["sources"]) : nil, cfg)
        case "sleep_need":
            let day = inp["day"].string ?? ""
            if inp["asleep"].has {
                return AnalyticsSleep.sleepNeedJSON(AnalyticsSleep.sleepNeed(asleep: numbers(inp["asleep"]), day: day, cfg))
            }
            return AnalyticsSleep.sleepNeedJSON(AnalyticsSleep.sleepNeed(nights: decodeNights(inp["nights"]), day: day, cfg))
        case "sleep_status":
            return AnalyticsSleep.sleepStatus(nights: decodeNights(inp["nights"]), day: inp["day"].string ?? "", cfg)
        case "load":
            return AnalyticsLoad.load(workouts: inp["workouts"].array.map(AWorkout.init), day: inp["day"].string ?? "",
                                      timeZone: inp["time_zone"].string ?? "UTC", tracking: inp["tracking"].bool ?? true, cfg).json
        case "hrr":
            let samples = inp["samples"].array.map { ($0[0].int64 ?? 0, $0[1].double ?? 0) }
            return AnalyticsLoad.hrr(samples: samples, endMs: inp["end_ms"].int64 ?? 0, cfg)
        case "recovery": return AnalyticsRecovery.recovery(decodeInputs(inp["inputs"]), day: inp["day"].string ?? "", cfg)
        case "anomaly": return AnalyticsRecovery.anomaly(decodeInputs(inp["inputs"]), day: inp["day"].string ?? "", cfg)
        case "correlation":
            let pairs = inp["pairs"].array.map {
                AnalyticsStats.Pair(id: $0["id"].string ?? "", exposure: numbers($0["exposure"]), outcome: numbers($0["outcome"]))
            }
            return AnalyticsStats.correlation(pairs: pairs, asOf: inp["as_of"].string ?? "", cfg)
        case "response":
            return AnalyticsStats.response(exposure: numbers(inp["exposure"]), outcome: numbers(inp["outcome"]),
                                           lag: inp["lag"].int ?? 0, asOf: inp["as_of"].string ?? "", window: inp["window"].int, cfg)
        case "energy": return AnalyticsStats.energy(energyInput(inp), cfg)
        case "vo2max_trend":
            var readings: [String: [String: Double]] = [:]
            for (k, v) in inp["readings"].object { readings[k] = numbers(v) }
            return AnalyticsStats.vo2maxTrend(readings: readings, day: inp["day"].string ?? "", cfg)
        case "met_intensity":
            let acts = inp["activities"].array.map {
                AnalyticsStats.Activity(startMs: $0["start_ms"].int64 ?? 0, endMs: $0["end_ms"].int64 ?? 0, key: $0["key"].string)
            }
            return AnalyticsStats.metIntensity(activities: acts, day: inp["day"].string ?? "",
                                               timeZone: inp["time_zone"].string ?? "UTC", cfg)
        case "forecast": return forecast(inp, cfg)
        case "evidence":
            return AnalyticsForecast.evidence(recovery: inp["recovery"], anomaly: inp["anomaly"], hrv: inp["hrv"],
                                              sleep: inp["sleep"], load: inp["load"])
        default: return nil
        }
    }

    private static func ibis(_ x: AJ) -> [(Double, Bool)] {
        x.array.map { ($0[0].double ?? 0, $0[1].bool ?? false) }
    }

    private static func sourceSelect(_ inp: AJ, _ cfg: AnalyticsConfig) -> AJ {
        let rows = inp["rows"].array.map {
            AnalyticsCore.SourceRow(id: $0["id"].string ?? "", source: $0["source"].string ?? "", origin: $0["origin"].int ?? 0,
                                    deviceType: $0["device_type"].int, tMs: $0["t_ms"].int64 ?? 0, value: $0["value"].double,
                                    count: $0["count"].int)
        }
        return AnalyticsCore.sourceSelect(metric: inp["metric"].string ?? "", rows: rows, policy: cfg.policy).json
    }

    private static func robustStats(_ inp: AJ) -> AJ {
        let v = inp["values"].array.compactMap(\.double)
        var ts: AJ = .null
        if inp["points"].has && !inp["points"].array.isEmpty {
            let r = AMath.theilSen(inp["points"].array.map { ($0[0].double ?? 0, $0[1].double ?? 0) })
            ts = .arr([.n(AMath.roundTo(r.0, 6)), .n(AMath.roundTo(r.1, 6))])
        }
        return .obj([
            "median": .num(AMath.roundTo(AMath.median(v), 6)), "mad": .num(AMath.roundTo(AMath.mad(v), 6)),
            "p10": .num(AMath.roundTo(AMath.percentile(v, 10), 6)), "p90": .num(AMath.roundTo(AMath.percentile(v, 90), 6)),
            "theil_sen": ts, "ranks": .arr(AMath.ranks(v).map { .num($0) }),
            "normal_cdf": .arr(inp["z"].array.compactMap(\.double).map { .num(AMath.roundTo(AMath.normalCDF($0), 9)) }),
            "bh": .arr(AMath.bhQValues(inp["p"].array.compactMap(\.double)).map { .num(AMath.roundTo($0, 9)) }),
        ])
    }

    private static func baselineFamily(_ function: String, _ inp: AJ, _ cfg: AnalyticsConfig) -> AJ {
        let series = numbers(inp["series"])
        let day = inp["day"].string ?? ""
        let metric = inp["metric"].string ?? ""
        let contexts = inp["contexts"].has ? strings(inp["contexts"]) : nil
        let sources = inp["sources"].has ? strings(inp["sources"]) : nil
        let recent = inp["recent"].string ?? "day"
        switch function {
        case "baseline":
            return AnalyticsCore.baseline(series: series, day: day, metric: metric, window: inp["window"].int ?? 28,
                                          contexts: contexts, sources: sources, recent: recent, cfg)
        case "baselines":
            return AnalyticsCore.baselines(series: series, day: day, metric: metric, contexts: contexts, sources: sources,
                                           recent: recent, cfg)
        default:
            return AnalyticsCore.trend(series: series, day: day, metric: metric, window: inp["window"].int, cfg)
        }
    }

    private static func energyInput(_ inp: AJ) -> AnalyticsStats.EnergyInput {
        let p = inp["profile"]
        return AnalyticsStats.EnergyInput(
            providerBasalKcal: inp["provider_basal_kcal"].double, providerActiveKcal: inp["provider_active_kcal"].double,
            providerWorkoutKcal: inp["provider_workout_kcal"].double,
            ayuvoSessions: inp["ayuvo_sessions"].array.map { ($0["start_ms"].int64 ?? 0, $0["end_ms"].int64 ?? 0, $0["kcal"].double) },
            providerWorkouts: inp["provider_workouts"].array.map { ($0["start_ms"].int64 ?? 0, $0["end_ms"].int64 ?? 0) },
            weightKg: p["weight_kg"].double, heightCm: p["height_cm"].double, age: p["age"].double, sex: p["sex"].string
        )
    }

    private static func forecast(_ inp: AJ, _ cfg: AnalyticsConfig) -> AJ {
        let inputs = decodeInputs(inp["inputs"])
        let asOf = inp["as_of"].string ?? ""
        let target = inp["target"].string ?? ""
        let feats = AnalyticsForecast.features(inputs, from: inp["from"].string ?? asOf, to: asOf, cfg)
        var res = AnalyticsForecast.forecast(target: target, features: feats, targetSeries: inputs.series[target] ?? [:],
                                             asOf: asOf, cfg)
        if inp["include_features"].bool == true, case .obj(var o) = res {
            var sample: [String: AJ] = [:]
            for d in feats.keys.sorted().suffix(2) { sample[d] = AnalyticsForecast.featuresJSON(feats[d]!) }
            o["features_sample"] = .obj(sample)
            res = .obj(o)
        }
        return res
    }
}
