import Foundation

// Per-user ridge forecast with a temporal split and a deployment gate, its feature builder, and the Coach evidence
// object. Port of the "Forecast" and "Structured evidence" sections of `scripts/analytics_reference.py`.

nonisolated enum AnalyticsForecast {
    typealias Features = [String: [String: Double?]]

    struct FRow {
        var day: String
        var x: [String: Double?]
        var y: Double
    }

    struct Norm {
        var median: Double
        var mean: Double
        var sd: Double
        var indicator: Bool
    }

    struct Model {
        var norm: [String: Norm]
        var cols: [(String, String)]
        var beta: [Double]
        var intercept: Double
        var lambda: Double
    }

    private static func value(_ x: [String: Double?], _ f: String) -> Double? { x[f] ?? nil }

    private static func design(_ names: [String], _ fitRows: [FRow]) -> ([String: Norm], [(String, String)]) {
        var norm: [String: Norm] = [:]
        for f in names {
            let vals = fitRows.compactMap { value($0.x, f) }
            if vals.isEmpty { continue }
            let med = AMath.median(vals)
            let imputed = fitRows.map { value($0.x, f) ?? med }
            let mu = AMath.mean(imputed)
            let sd = imputed.count >= 2 ? AMath.sampleSD(imputed, mu) : 0.0
            if sd == 0 { continue }
            norm[f] = Norm(median: med, mean: mu, sd: sd, indicator: vals.count < fitRows.count)
        }
        var cols: [(String, String)] = []
        for f in names {
            guard let n = norm[f] else { continue }
            cols.append((f, "value"))
            if n.indicator { cols.append((f, "missing")) }
        }
        return (norm, cols)
    }

    private static func vector(_ x: [String: Double?], _ norm: [String: Norm], _ cols: [(String, String)]) -> [Double] {
        cols.map { f, kind in
            let raw = value(x, f)
            if kind == "missing" { return raw == nil ? 1.0 : 0.0 }
            let n = norm[f]!
            return ((raw ?? n.median) - n.mean) / n.sd
        }
    }

    static func fit(_ rows: [FRow], _ names: [String], _ lam: Double) -> Model? {
        let (norm, cols) = design(names, rows)
        let xs = rows.map { vector($0.x, norm, cols) }
        let yMean = AMath.mean(rows.map(\.y))
        let p = cols.count
        var a = [[Double]](repeating: [Double](repeating: 0, count: p), count: p)
        var b = [Double](repeating: 0, count: p)
        for (x, r) in zip(xs, rows) {
            let yc = r.y - yMean
            for i in 0..<p {
                b[i] += x[i] * yc
                for j in 0..<p { a[i][j] += x[i] * x[j] }
            }
        }
        for i in 0..<p { a[i][i] += lam }
        let beta: [Double]
        if p > 0 {
            guard let solved = AMath.choleskySolve(a, b) else { return nil }
            beta = solved
        } else {
            beta = []
        }
        return Model(norm: norm, cols: cols, beta: beta, intercept: yMean, lambda: lam)
    }

    static func predict(_ m: Model, _ x: [String: Double?]) -> Double {
        let v = vector(x, m.norm, m.cols)
        var s = m.intercept
        for (bi, xi) in zip(m.beta, v) { s += bi * xi }
        return s
    }

    private static func metrics(_ preds: [Double], _ ys: [Double]) -> [String: Double?] {
        let n = Double(ys.count)
        var ae = 0.0, se = 0.0, bias = 0.0
        for (p, y) in zip(preds, ys) {
            ae += abs(p - y)
            se += (p - y) * (p - y)
            bias += p - y
        }
        let my = AMath.mean(ys)
        var sst = 0.0
        for y in ys { sst += (y - my) * (y - my) }
        return ["mae": ae / n, "rmse": (se / n).squareRoot(), "bias": bias / n, "r2": sst > 0 ? 1.0 - se / sst : nil]
    }

    static func forecast(target: String, features feats: Features, targetSeries ts: [String: Double], asOf: String,
                         _ cfg: AnalyticsConfig) -> AJ {
        let fc = cfg["forecast"]
        let names = fc["features"].array.compactMap(\.string)
        var rows: [FRow] = []
        for d in feats.keys.sorted() {
            let nxt = AMath.addDays(d, 1)
            if nxt <= asOf, let y = ts[nxt] { rows.append(FRow(day: d, x: feats[d]!, y: y)) }
        }
        var out: [String: AJ] = [
            "model_id": .str((fc["algorithm_id"].string ?? "") + "." + target), "model_version": fc["model_version"],
            "feature_schema_version": fc["feature_schema_version"], "target": .str(target),
            "status": .str("INSUFFICIENT_HISTORY"), "n_rows": .i(rows.count), "n_train": .i(0), "n_val": .i(0),
            "n_test": .i(0), "lambda": .null, "train_start": .null, "train_end": .null, "val_start": .null,
            "val_end": .null, "test_start": .null, "test_end": .null, "features_used": .arr([]), "coefficients": .obj([:]),
            "intercept": .null, "normalization": .obj([:]), "metrics": .null, "baselines": .null, "deployed": .bool(false),
            "prediction": .null, "interval_low": .null, "interval_high": .null, "classification": .str("ML_PREDICTED"),
        ]
        let n = rows.count
        if n < (fc["min_rows"].int ?? 90) { return .obj(out) }
        let split = fc["split"].array.compactMap(\.double)
        let nTr = Int((split[0] * Double(n)).rounded(.down))
        let nVa = Int((split[1] * Double(n)).rounded(.down))
        let nTe = n - nTr - nVa
        if nTe < (fc["min_test_rows"].int ?? 14) { return .obj(out) }
        let tr = Array(rows[0..<nTr]), va = Array(rows[nTr..<(nTr + nVa)]), te = Array(rows[(nTr + nVa)...])
        var best: Model?
        var bestMAE: Double?
        for lamJ in fc["lambdas"].array {
            guard let lam = lamJ.double, let m = fit(tr, names, lam) else { continue }
            let mae = metrics(va.map { predict(m, $0.x) }, va.map(\.y))["mae"]!!
            if bestMAE == nil || mae <= bestMAE! {
                best = m
                bestMAE = mae
            }
        }
        guard let best else {
            out["status"] = .str("INVALID_INPUT")
            return .obj(out)
        }
        let lam = best.lambda
        let resid = va.map { abs(predict(best, $0.x) - $0.y) }.sorted()
        let half = AMath.percentile(resid, fc["interval_percentile"].double ?? 80)
        guard let m2 = fit(tr + va, names, lam), let final = fit(rows, names, lam) else {
            out["status"] = .str("INVALID_INPUT")
            return .obj(out)
        }
        let preds = te.map { predict(m2, $0.x) }
        let ys = te.map(\.y)
        var met = metrics(preds, ys)
        var covered = 0
        for (p, y) in zip(preds, ys) where abs(p - y) <= half { covered += 1 }
        met["coverage80"] = Double(covered) / Double(ys.count)
        let medDays = fc["baseline_median_days"].int ?? 28
        var persist: [Double?] = [], med28: [Double?] = []
        for r in te {
            let win = AMath.windowDays(r.day, -(medDays - 1), 0)
            let prev = win.compactMap { ts[$0] }
            persist.append(prev.last)
            med28.append(prev.isEmpty ? nil : AMath.median(prev))
        }
        func baseMAE(_ ps: [Double?]) -> Double? {
            let pairs = zip(ps, ys).compactMap { p, y in p.map { ($0, y) } }
            return pairs.isEmpty ? nil : metrics(pairs.map(\.0), pairs.map(\.1))["mae"]!
        }
        let bP = baseMAE(persist), bM = baseMAE(med28)
        let refs = [bP, bM].compactMap { $0 }
        let deployed = !refs.isEmpty && met["mae"]!! <= (1.0 - (fc["min_improvement"].double ?? 0.05)) * refs.min()!
        let pred: Double? = feats[asOf].map { predict(final, $0) }
        out["status"] = .str(deployed ? "VALID" : "LOW_CONFIDENCE")
        out["n_train"] = .i(nTr)
        out["n_val"] = .i(nVa)
        out["n_test"] = .i(nTe)
        out["lambda"] = .num(lam)
        out["train_start"] = .str(tr[0].day)
        out["train_end"] = .str(tr[tr.count - 1].day)
        out["val_start"] = .str(va[0].day)
        out["val_end"] = .str(va[va.count - 1].day)
        out["test_start"] = .str(te[0].day)
        out["test_end"] = .str(te[te.count - 1].day)
        out["features_used"] = .arr(names.filter { final.norm[$0] != nil }.map { .str($0) })
        var coef: [String: AJ] = [:]
        for (c, b) in zip(final.cols, final.beta) { coef["\(c.0):\(c.1)"] = .num(AMath.roundTo(b, 6)) }
        out["coefficients"] = .obj(coef)
        out["intercept"] = .num(AMath.roundTo(final.intercept, 6))
        out["normalization"] = .obj(final.norm.mapValues {
            .obj(["median": .num(AMath.roundTo($0.median, 6)), "mean": .num(AMath.roundTo($0.mean, 6)),
                  "sd": .num(AMath.roundTo($0.sd, 6)), "indicator": .bool($0.indicator)])
        })
        out["metrics"] = .obj(met.mapValues { .n(AMath.roundTo($0, 4)) })
        out["baselines"] = .obj(["persistence_mae": .n(AMath.roundTo(bP, 4)), "median28_mae": .n(AMath.roundTo(bM, 4))])
        out["deployed"] = .bool(deployed)
        out["prediction"] = .n(AMath.roundTo(pred, 2))
        out["interval_low"] = .n(pred.map { AMath.roundTo($0 - half, 2) })
        out["interval_high"] = .n(pred.map { AMath.roundTo($0 + half, 2) })
        return .obj(out)
    }

    /// Feature rows (schema v1) for every day from…to.
    static func features(_ inputs: AInputs, from: String, to: String, _ cfg: AnalyticsConfig) -> Features {
        let nights = AnalyticsSleep.validNights(inputs.nights, cfg)
        let daysLoad = AnalyticsLoad.loadDays(inputs.workouts, timeZone: inputs.timeZone)
        let minPoints28 = cfg.minPoints(window: 28)
        var out: Features = [:]
        var d = from
        while d <= to {
            var row: [String: Double?] = [:]
            for f in ["hrv", "resting_heart_rate", "steps", "active_energy", "respiratory_rate", "wrist_temperature", "weight"] {
                row[f] = .some(inputs.series[f]?[d])
            }
            for f in ["hrv", "resting_heart_rate"] {
                let b = AnalyticsCore.robust(inputs.series[f] ?? [:], day: d, window: 28, minPoints: minPoints28,
                                             spreadFloor: cfg.metric(f)["spread_floor"].double ?? 0, cfg)
                row[f + "_z"] = .some(b.z.map { AMath.roundTo($0, 6) })
            }
            let n = nights[d]
            row["sleep_duration"] = .some(n?.asleepMin)
            row["sleep_efficiency"] = .some(n?.efficiency)
            row["sleep_midpoint"] = .some(n?.midpointClock)
            let ld = AnalyticsLoad.load(workouts: inputs.workouts, day: d, timeZone: inputs.timeZone, tracking: true, cfg)
            let pm = ld.primary
            row["load_acute"] = .some(pm?.acute)
            row["load_chronic"] = .some(pm?.chronic)
            row["load_ratio"] = .some(pm?.ratio)
            row["workout_minutes"] = .some(daysLoad[d]?.minutes ?? (ld.primaryMethod != nil ? 0.0 : nil))
            let food = inputs.nutrition[d]
            row["energy_intake"] = .some(food?.calories ?? nil)
            row["protein_g"] = .some(food?.proteinG ?? nil)
            out[d] = row
            d = AMath.addDays(d, 1)
        }
        return out
    }

    static func featuresJSON(_ row: [String: Double?]) -> AJ { .obj(row.mapValues { .n($0) }) }

    // MARK: Evidence

    static func evidence(recovery r: AJ, anomaly a: AJ, hrv h: AJ, sleep s: AJ, load ld: AJ) -> AJ {
        var items: [AJ] = []
        if r.has {
            var item: [String: AJ] = [
                "metric": .str("recovery_indicator"), "status": r["status"],
                "algorithm": .str("\(r["algorithm_id"].string ?? "")@\(r["algorithm_version"].int ?? 0)"),
                "classification": r["classification"], "confidence": r["confidence"],
            ]
            if r["score"].has {
                item["score"] = r["score"]
                item["label"] = r["label"]
                item["drivers"] = .arr(r["drivers"].array.map { d in
                    .obj(["metric": d["id"], "direction": d["direction"], "value": d["value"], "baseline": d["baseline"],
                          "z": d["z"], "unit": d["unit"]])
                })
                item["warnings"] = .arr(r["warnings"].array.map { .str(($0["code"].string ?? "") + ":" + ($0["metric"].string ?? "")) })
            }
            items.append(.obj(item))
        }
        if a.has {
            items.append(.obj([
                "metric": .str("multi_signal_deviation"), "status": a["status"], "state": a["state"],
                "algorithm": .str("\(a["algorithm_id"].string ?? "")@\(a["algorithm_version"].int ?? 0)"),
                "classification": a["classification"], "confidence": a["confidence"], "persistent": a["persistent"],
                "signals": .arr(a["signals"].array.map { .obj(["metric": $0["id"], "z": $0["z"], "flagged": $0["flagged"]]) }),
            ]))
        }
        if h.has {
            let b = h["baseline"]
            items.append(.obj([
                "metric": .str("hrv"), "kind": h["kind"], "status": b["status"], "value": b["value"], "baseline": b["median"],
                "z": b["z"], "trend": h["trend"]["label"], "classification": h["classification"],
                "confidence": b["confidence"], "unit": .str("ms"),
            ]))
        }
        if s.has {
            items.append(.obj([
                "metric": .str("sleep"), "status": s["status"], "asleep_min": s["asleep_min"], "need_min": s["need_min"],
                "need_source": s["need_source"], "debt_min": s["debt_min"], "efficiency": s["efficiency"],
                "bedtime_sd": s["bedtime_sd"], "confidence": s["confidence"],
            ]))
        }
        if ld.has {
            let pm = ld["primary_method"]
            let m = pm.string.map { ld["methods"][$0] } ?? .null
            items.append(.obj([
                "metric": .str("training_load"), "status": ld["status"], "state": ld["state"], "method": pm,
                "acute": m["acute"], "chronic": m["chronic"], "ratio": m["ratio"], "classification": ld["classification"],
            ]))
        }
        return .obj(["evidence_version": .i(1), "items": .arr(items)])
    }
}
