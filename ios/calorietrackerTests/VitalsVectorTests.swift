import Foundation
import Testing
@testable import calorietracker

/// Runs every case of `shared/vitals/test-vectors/*.json` through the Swift camera-vitals engine
/// (docs/camera-vitals.md) and requires equality with `scripts/vitals_reference.py`: `firstDifference` (numbers by
/// value, keys unordered, absent == null) and, stricter, the same int / float kind and bit-identical doubles.
struct VitalsVectorTests {
    /// One file per function in `vitals_reference.FUNCTIONS`.
    nonisolated static let vectorFiles = [
        "analyze_face", "analyze_finger", "bandpass", "baselines", "bp_research", "compare", "effective_config",
        "finger_detect", "hrv_freq", "insights_fallback", "jacobi", "pulses", "resp_rate", "scan_control", "scan_indicator", "spectrum",
        "spo2_estimate", "synth", "validation_stats",
    ]

    static var vectorsDirectory: URL {
        HealthTestFixtures.repoRootURL.appendingPathComponent("shared/vitals/test-vectors")
    }

    @Test func sharedVectorFilesMatchRunnersExactly() throws {
        let names = try FileManager.default.contentsOfDirectory(atPath: Self.vectorsDirectory.path)
            .filter { $0.hasSuffix(".json") }
            .map { String($0.dropLast(5)) }
        #expect(!names.isEmpty)
        for name in names.sorted() {
            #expect(Self.vectorFiles.contains(name), "no Swift runner for test-vectors/\(name).json")
        }
        for name in Self.vectorFiles {
            #expect(names.contains(name), "runner \(name) has no test-vectors/\(name).json")
        }
        #expect(Set(names) == Set(Self.vectorFiles))
    }

    @Test(arguments: vectorFiles)
    func vectorFileMatchesReference(_ name: String) throws {
        let url = Self.vectorsDirectory.appendingPathComponent("\(name).json")
        let root = try VitalsJSON.parse(try Data(contentsOf: url))
        #expect(root["format"].string == "ayuvo-vitals-vectors")
        #expect(root["version"].double == 1)
        let function = try #require(root["function"].string)
        #expect(function == name)
        let cases = root["cases"].array ?? []
        #expect(!cases.isEmpty)
        var passed = 0
        var failures: [String] = []
        var strictFailures: [String] = []
        var timings: [String] = []
        for c in cases {
            let caseName = c["name"].string ?? "?"
            do {
                let start = ContinuousClock.now
                let actual = try VitalsVectorRunner.run(function: function, input: c["input"], config: VitalsConfig.shared)
                let elapsed = ContinuousClock.now - start
                timings.append("\(caseName)=\(elapsed.components.seconds * 1000 + elapsed.components.attoseconds / 1_000_000_000_000_000)ms")
                if let diff = InsightsVectorRunner.firstDifference(actual, c["expected"]) {
                    failures.append("\(caseName): \(diff)")
                } else {
                    passed += 1
                }
                if let diff = VitalsVectorRunner.strictDifference(actual, c["expected"]) {
                    strictFailures.append("\(caseName): \(diff)")
                }
            } catch {
                failures.append("\(caseName): threw \(error)")
            }
        }
        print("VITALS-VECTORS \(name).json \(passed)/\(cases.count) strict-failures \(strictFailures.count) timings \(timings.joined(separator: " "))")
        let report = "\(name).json \(passed)/\(cases.count) passed\n" + failures.joined(separator: "\n")
        #expect(failures.isEmpty, Comment(rawValue: report))
        #expect(strictFailures.isEmpty, Comment(rawValue: "strict (kind + bit-identical) differences:\n" + strictFailures.joined(separator: "\n")))
    }

    @Test func bundledConfigIsByteIdenticalToShared() throws {
        let shared = try Data(contentsOf: HealthTestFixtures.repoRootURL.appendingPathComponent("shared/vitals/vitals_config.json"))
        // The app sources sit next to this test target's folder (`<app>Tests` → `<app>`).
        let testsFolder = URL(fileURLWithPath: #filePath).deletingLastPathComponent()
        let source = testsFolder.deletingLastPathComponent()
            .appendingPathComponent(testsFolder.lastPathComponent.replacingOccurrences(of: "Tests", with: ""))
            .appendingPathComponent("Services/Vitals/Resources/vitals_config.json")
        #expect(try Data(contentsOf: source) == shared, "Services/Vitals/Resources/vitals_config.json differs from shared/vitals")
        let bundled = try Data(contentsOf: try #require(VitalsConfig.bundledURL, "vitals_config.json is bundled"))
        #expect(bundled == shared, "bundled vitals_config.json differs from shared/vitals")
    }

    @Test func bundledConfigDecodes() throws {
        let config = try #require(VitalsConfig.load())
        #expect(config.format == "ayuvo-vitals-config")
        #expect(config.configVersion == 1)
        #expect(config.face.methods.first == "green")
        #expect(config.metric("heart_rate").classification == "measured")
        #expect(config.metric("spo2").classification == "experimental")
        #expect(config.metric("blood_pressure").classification == "research")
        // The shipped config has no device overrides: the effective config is the config itself.
        #expect(config.effective(deviceModel: "iPhone15,2").signal.fs == config.signal.fs)
    }

    @Test func deviceOverrideMergesIntoTypedConfig() throws {
        guard case .obj(var raw) = VitalsConfig.shared.raw else { Issue.record("config is not an object"); return }
        raw["device_overrides"] = try VitalsJSON.parse(#"{"Pixel 7": {"finger": {"min_red": 70}}}"#)
        let config = try VitalsConfig(raw: .obj(raw))
        let merged = config.effective(deviceModel: "Pixel 7")
        #expect(merged.finger.minRed == 70)
        #expect(merged.finger.maxSpatialStd == config.finger.maxSpatialStd)
        #expect(config.effective(deviceModel: "other").finger.minRed == config.finger.minRed)
    }

    @Test func jsonEncodingFollowsPythonDumps() throws {
        let value: RJ = .obj(["b": .num(3.0), "a": .arr([.int(1), .num(0.1), .num(1e-05), .null, .bool(true)]), "é": .str("x\"y\n")])
        let escapedKey = "\\" + "u00e9"
        #expect(VitalsJSON.encode(value) == #"{"a":[1,0.1,1e-05,null,true],"b":3.0,""# + escapedKey + #"":"x\"y\n"}"#)
        let parsed = try VitalsJSON.parse(#"{"i": 30, "f": 30.0, "e": 1e2, "s": "é"}"#)
        if case .int(30) = parsed["i"] {} else { Issue.record("30 should parse as int") }
        if case .num(30.0) = parsed["f"] {} else { Issue.record("30.0 should parse as float") }
        if case .num(100.0) = parsed["e"] {} else { Issue.record("1e2 should parse as float") }
        #expect(parsed["s"].string == "é")
    }

    @Test func roundToIsHalfUpWithoutNegativeZero() {
        #expect(VitalsMath.roundTo(2.5, 0) == 3.0)
        #expect(VitalsMath.roundTo(-0.0001, 2) == 0.0 && VitalsMath.roundTo(-0.0001, 2).sign == .plus)
        #expect(VitalsEngine.movingAverage([1.0, 2.0, 3.0, 4.0], 3) == [1.5, 2.0, 3.0, 3.5])
        var lcg = VitalsLcg(seed: 1)
        #expect(lcg.uniform() == Double((1103515245 + 12345) % 2147483648) / 2147483648.0)
    }

    @Test func storedResultJSONOmitsSignalsAndRoundTrips() throws {
        let spec = VitalsSynth.Spec(json: try VitalsJSON.parse(
            #"{"ac": 0.02, "duration_s": 40, "fs": 30, "hr_bpm": 72, "jitter_ms": 3, "noise": 0.0005, "resp_bpm": 15, "rsa_ms": 30, "seed": 7}"#))
        let input = VitalsEngine.FingerScanInput(frames: VitalsSynth.synthFinger(spec), includeSignals: true)
        let result = VitalsEngine.analyzeFinger(input, VitalsConfig.shared)
        #expect(result.rejectReason == nil)
        #expect(result.metric("heart_rate").isValid)
        #expect(!result.signals.isNull)
        let stored = try VitalsJSON.parse(result.resultsJSONText)
        #expect(stored["signals"].isNull)
        #expect(VitalsVectorRunner.strictDifference(stored, VitalsVectorRunner.dropping("signals", result.json)) == nil)
        let signals = try VitalSignalCodec.signals(from: result)
        #expect(signals.map(\.kind) == ["processed", "mask", "beats"])
        let processed = try VitalSignalCodec.decode(signals[0])
        #expect(processed.rows.count == result.signals["processed"].array?.count)
    }
}


/// Decodes the vectors' plain inputs into typed engine inputs and runs one case.
nonisolated enum VitalsVectorRunner {
    enum Failure: Error { case unknownFunction(String) }

    typealias E = VitalsEngine
    typealias M = VitalsMath

    static func doubles(_ x: RJ) -> [Double] { (x.array ?? []).compactMap(\.double) }

    static func rows(_ x: RJ) -> [[Double]] { (x.array ?? []).map { doubles($0) } }

    static func dropping(_ key: String, _ x: RJ) -> RJ {
        guard case .obj(var o) = x else { return x }
        o[key] = nil
        return .obj(o)
    }

    static func faceFrames(_ f: RJ) -> E.FaceFrames {
        var rois: [String: [[Double]]] = [:]
        for (name, r) in f["rois"].object ?? [:] { rois[name] = rows(r) }
        return E.FaceFrames(tMs: doubles(f["t_ms"]), rois: rois, motion: doubles(f["motion"]), yaw: doubles(f["yaw"]),
                            pitch: doubles(f["pitch"]), luma: doubles(f["luma"]), faceCount: doubles(f["face_count"]),
                            faceFraction: doubles(f["face_fraction"]))
    }

    static func features(_ x: RJ) -> [String: Double]? {
        guard let o = x.object else { return nil }
        return o.compactMapValues(\.double)
    }

    static func bpCalibrations(_ x: RJ) -> [E.BpCalibration] {
        (x.array ?? []).map {
            E.BpCalibration(tMs: $0["t_ms"].double ?? 0, scanGapMin: $0["scan_gap_min"].double ?? 0,
                            features: features($0["features"]), sbp: $0["sbp"].double ?? 0, dbp: $0["dbp"].double ?? 0)
        }
    }

    static func spo2Calibrations(_ x: RJ) -> [E.Spo2Calibration] {
        (x.array ?? []).map { E.Spo2Calibration(ratio: $0["ratio"].double ?? 0, spo2: $0["spo2"].double ?? 0) }
    }

    static func run(function: String, input c: RJ, config cfg: VitalsConfig) throws -> RJ {
        switch function {
        case "analyze_finger":
            let frames = c["frames"].isNull ? VitalsSynth.synthFinger(VitalsSynth.Spec(json: c["synth"])) : rows(c["frames"])
            let inp = E.FingerScanInput(frames: frames, experimentalEnabled: c["experimental_enabled"].truthy,
                                        researchEnabled: c["research_enabled"].truthy,
                                        spo2Calibrations: spo2Calibrations(c["spo2_calibrations"]),
                                        bpCalibrations: bpCalibrations(c["bp_calibrations"]), nowMs: c["now_ms"].double ?? 0,
                                        includeSignals: c["include_signals"].truthy)
            return E.analyzeFinger(inp, cfg).json
        case "analyze_face":
            let face = c["face"].isNull ? VitalsSynth.synthFace(VitalsSynth.Spec(json: c["synth"])) : faceFrames(c["face"])
            let inp = E.FaceScanInput(face: face, experimentalEnabled: c["experimental_enabled"].truthy,
                                      researchEnabled: c["research_enabled"].truthy, includeSignals: c["include_signals"].truthy)
            return E.analyzeFace(inp, cfg).json
        case "bandpass":
            let y = E.bandpass(doubles(c["x"]), c["fs"].double ?? 0, c["lo"].double ?? 0, c["hi"].double ?? 0)
            return .obj(["y": .fs(M.roundList(y, 6))])
        case "baselines":
            let values = (c["values"].array ?? []).map { E.TimedValue(tMs: $0["t_ms"].double ?? 0, value: $0["value"].double ?? 0) }
            return E.baselines(values: values, nowMs: c["now_ms"].double ?? 0,
                               windowsDays: (c["windows_days"].array ?? []).map(\.double), cfg)
        case "bp_research":
            return E.bpResearch(features: features(c["features"]), calibrations: bpCalibrations(c["calibrations"]),
                                nowMs: c["now_ms"].double ?? 0, quality: c["quality"].double ?? 0, cfg).json
        case "compare":
            func side(_ s: RJ) -> E.CompareSide { E.CompareSide(hr: s["hr"].double, rmssd: s["rmssd"].double, ibiMean: s["ibi_mean"].double) }
            return E.compare(finger: side(c["finger"]), face: side(c["face"]), cfg)
        case "effective_config":
            let overrides: RJ? = c.object?["device_overrides"]
            return E.effectiveConfig(deviceModel: c["device_model"].string ?? "", deviceOverrides: overrides, cfg)
        case "finger_detect":
            return .arr(rows(c["frames"]).map { .str(E.fingerDetect($0, cfg)) })
        case "hrv_freq":
            let ibi = doubles(c["ibi_ms"])
            guard let r = E.hrvFreq(ibiMs: ibi, ibiTMs: doubles(c["ibi_t_ms"]), accepted: [Bool](repeating: true, count: ibi.count), cfg)
            else { return .null }
            return .obj(["lf_hf": .num(M.roundTo(r.lfHf, 3)), "lf_nu": .num(M.roundTo(r.lfNu, 2)), "hf_nu": .num(M.roundTo(r.hfNu, 2))])
        case "insights_fallback":
            var platformDays: [String: [String]] = [:]
            for (series, days) in c["platform_days"].object ?? [:] { platformDays[series] = (days.array ?? []).compactMap(\.string) }
            let scans = (c["scans"].array ?? []).map {
                E.FallbackScan(localDay: $0["local_day"].string ?? "", mode: $0["mode"].string ?? "", context: $0["context"].string ?? "",
                               qualityScore: $0["quality_score"].double, rejectReason: $0["reject_reason"].string, metrics: $0["metrics"])
            }
            let out = E.insightsFallback(platformDays: platformDays, scans: scans, hrvKind: c["hrv_kind"].string ?? "", cfg)
            return .obj(out.mapValues { days in .obj(days.mapValues { .num($0) }) })
        case "jacobi":
            let (values, vectors) = E.jacobiEigen(rows(c["m"]))
            return .obj(["values": .fs(M.roundList(values, 6)), "vectors": .arr(vectors.map { .fs(M.roundList($0, 6)) })])
        case "pulses":
            let fs = cfg.signal.fs
            let x = doubles(c["x"])
            let peaks = E.pulseDetect(x, fs, [Bool](repeating: false, count: x.count), cfg)
            let ibi = E.ibiClean(peaks, cfg)
            var hrv: RJ = .null
            if let ht = E.hrvTime(ibiMs: ibi.ibiMs, accepted: ibi.accepted) {
                hrv = .obj(["mean_nn": .num(M.roundTo(ht.meanNN, 1)), "n": .int(ht.n), "pnn50": .num(M.roundTo(ht.pnn50, 1)),
                            "rmssd": .num(M.roundTo(ht.rmssd, 1)), "sdnn": .f(M.roundTo(ht.sdnn, 1))])
            }
            return .obj(["peaks_ms": .fs(peaks.map { M.roundTo($0.tMs, 1) }), "corr": .fs(peaks.map { M.roundTo($0.corr, 3) }),
                         "ibi_ms": .fs(ibi.ibiMs), "ibi_quality": .fs(ibi.ibiQuality), "accepted_fraction": .num(ibi.acceptedFraction),
                         "hrv": hrv])
        case "resp_rate":
            let series = (c["series"].array ?? []).map { s -> (tMs: [Double], values: [Double]) in
                let a = s.array ?? []
                return (doubles(a.count > 0 ? a[0] : .null), doubles(a.count > 1 ? a[1] : .null))
            }
            let r = E.respRate(series, cfg)
            return .obj(["value": .f(M.roundTo(r.value, 1)), "estimates": .fs(r.estimates), "spread": .f(M.roundTo(r.spread, 2))])
        case "scan_control":
            return .obj(["action": .str(E.scanControl(elapsedS: c["elapsed_s"].double ?? 0, quality: c["quality"].double, cfg))])
        case "scan_indicator":
            let cur = E.IndicatorScan(hr: c["current"]["hr"].double, rmssd: c["current"]["rmssd"].double)
            let history = (c["history"].array ?? []).map {
                E.IndicatorScan(tMs: $0["t_ms"].double ?? 0, hr: $0["hr"].double, rmssd: $0["rmssd"].double)
            }
            return E.scanIndicator(current: cur, history: history, nowMs: c["now_ms"].double ?? 0,
                                   quality: c["quality"].double ?? 0, cfg)
        case "spectrum":
            let s = E.hrSpectrum(doubles(c["x"]), cfg.signal.fs, cfg)
            return .obj(["hr_bpm": .num(M.roundTo(s.hrBpm, 2)), "snr_db": .num(M.roundTo(s.snrDb, 2))])
        case "spo2_estimate":
            let r = E.spo2Estimate(ratio: c["ratio"].double, quality: c["quality"].double ?? 0,
                                   calibrations: spo2Calibrations(c["calibrations"]), cfg)
            return .obj(["value": .f(M.roundTo(r.value, 1)), "confidence": .f(M.roundTo(r.confidence, 2)), "reason": .s(r.reason)])
        case "synth":
            return VitalsSynth.synth(kind: c["kind"].string ?? "", spec: VitalsSynth.Spec(json: c["spec"]))
        case "validation_stats":
            let pairs = (c["pairs"].array ?? []).map {
                E.ValidationPair(measured: $0["measured"].double ?? 0, reference: $0["reference"].double ?? 0,
                                 confidence: $0["confidence"].double)
            }
            return E.validationStats(pairs: pairs, failures: Int(c["failures"].double ?? 0), cfg)
        default:
            throw Failure.unknownFunction(function)
        }
    }

    /// Stricter than `firstDifference`: same int / float kind and bit-identical doubles (absent == null still holds).
    static func strictDifference(_ actual: RJ, _ expected: RJ, path: String = "$") -> String? {
        switch (actual, expected) {
        case (.obj(let x), .obj(let y)):
            for key in Set(x.keys).union(y.keys).sorted() {
                let xv = x[key] ?? .null, yv = y[key] ?? .null
                if let diff = strictDifference(xv, yv, path: "\(path).\(key)") { return diff }
            }
            return nil
        case (.arr(let x), .arr(let y)):
            guard x.count == y.count else { return "\(path) count actual \(x.count) expected \(y.count)" }
            for (i, pair) in zip(x, y).enumerated() {
                if let diff = strictDifference(pair.0, pair.1, path: "\(path)[\(i)]") { return diff }
            }
            return nil
        case (.int(let a), .int(let b)):
            return a == b ? nil : "\(path): actual \(a) expected \(b)"
        case (.num(let a), .num(let b)):
            return a == b ? nil : "\(path): actual \(VitalsJSON.number(a)) expected \(VitalsJSON.number(b)) (ulps \(abs(Int64(bitPattern: a.bitPattern) - Int64(bitPattern: b.bitPattern))))"
        case (.null, .null): return nil
        case (.bool(let a), .bool(let b)): return a == b ? nil : "\(path): actual \(a) expected \(b)"
        case (.str(let a), .str(let b)): return a == b ? nil : "\(path): actual \(a) expected \(b)"
        default:
            return "\(path): kind differs, actual \(VitalsJSON.encode(actual)) expected \(VitalsJSON.encode(expected))"
        }
    }
}
