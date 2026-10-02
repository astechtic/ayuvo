import Foundation

/// Camera vitals engine: exact Swift port of `scripts/vitals_reference.py` (docs/camera-vitals.md). Finger PPG (rear
/// camera + torch) and face rPPG (front camera) signal processing over per-frame statistics, never images. Pure
/// functions: no clock, no storage, no camera. `VitalsVectorTests` requires every case of
/// `shared/vitals/test-vectors` to match the reference, so the reference wins over prose and nothing here may change
/// operation order (accumulation order, `(a*b)/c` vs `a*(b/c)`) without changing the reference first.
///
/// Results are `RJ` values shaped exactly like the reference's dicts (same keys, nulls, rounding and int / float
/// kinds); `VitalsJSON.encode` serialises them for `vital_scans.results_json` / `quality_json`.
nonisolated enum VitalsEngine {
    private typealias M = VitalsMath

    static let channelIndex: [String: Int] = ["r": 0, "g": 1, "b": 2]

    // MARK: Inputs

    /// A finger scan. `frames[i] = [t_ms, r, g, b, r_std, sat_frac]`: mean RGB (0-255) of the fingertip ROI, spatial
    /// SD of red and the fraction of saturated red pixels.
    struct FingerScanInput: Sendable {
        var frames: [[Double]]
        var experimentalEnabled = false
        var researchEnabled = false
        var spo2Calibrations: [Spo2Calibration] = []
        var bpCalibrations: [BpCalibration] = []
        var nowMs: Double = 0
        var includeSignals = false
    }

    struct FaceScanInput: Sendable {
        var face: FaceFrames
        var experimentalEnabled = false
        var researchEnabled = false
        var includeSignals = false
    }

    // MARK: Quality and metric envelopes

    static func grade(_ score: Double, _ cfg: VitalsConfig) -> String {
        var g = cfg.quality.grades[0].id
        for item in cfg.quality.grades where score >= item.min { g = item.id }
        return g
    }

    /// `quality_score`: weighted 0-100 score that keeps each (rounded) component.
    static func qualityScore(_ components: [String: Double], _ cfg: VitalsConfig) -> (score: Double, json: [String: RJ]) {
        let w = cfg.quality.weights
        var acc = 0.0
        for k in w.keys.sorted() { acc += w[k]! * components[k]! }
        let score = M.roundTo(100.0 * acc, 1)
        var rounded: [String: RJ] = [:]
        for k in components.keys.sorted() { rounded[k] = .num(M.roundTo(components[k]!, 3)) }
        return (score, ["score": .num(score), "grade": .str(grade(score, cfg)), "components": .obj(rounded)])
    }

    /// The per-metric result envelope (value, unit, confidence, classification, algorithm version, source, status,
    /// reason) plus any extra keys.
    static func envelope(_ cfg: VitalsConfig, _ metricID: String, _ source: String, _ value: Double?, _ decimals: Int,
                         _ confidence: Double?, _ reason: String?, _ extra: [String: RJ]? = nil) -> RJ {
        let m = cfg.metric(metricID)
        let ok = value != nil && reason == nil
        var out: [String: RJ] = [
            "value": ok ? .num(M.roundTo(value!, decimals)) : .null,
            "unit": .str(m.unit),
            "confidence": ok && confidence != nil ? .num(M.roundTo(confidence!, 2)) : .null,
            "confidence_label": ok && confidence != nil ? .s(M.confidenceLabel(M.roundTo(confidence!, 2))) : .null,
            "classification": .str(m.classification),
            "algorithm_version": .int(cfg.algoVersion),
            "source": .str(source),
            "status": .str(ok ? "valid" : "unavailable"),
            "reason": ok ? .null : .str(reason ?? "low_quality"),
        ]
        if let extra, !extra.isEmpty {
            for (k, v) in extra { out[k] = v }
        }
        return .obj(out)
    }

    static func unavailableAll(_ cfg: VitalsConfig, _ source: String, _ reason: String) -> [String: RJ] {
        var out: [String: RJ] = [:]
        for m in cfg.metrics {
            if m.id == "recovery_indicator" || m.id == "stress_indicator" { continue }
            var r = reason
            if m.id == "spo2" && source == "face_rppg" { r = "face_not_supported" }
            out[m.id] = envelope(cfg, m.id, source, nil, 0, nil, r)
        }
        return out
    }

    // MARK: Shared tail: pulse signal -> beats -> HR / IBI / HRV / respiration

    struct Tail {
        var quality: [String: RJ]
        var score: Double
        var metrics: [String: RJ]
        var ibi: RJ
        var peaks: [Peak]
    }

    static func beatsAndMetrics(_ sigX: [Double], _ fs: Double, _ mask: [Bool], _ t0Ms: Double, _ source: String,
                                _ cfg: VitalsConfig, signalFraction: Double, maskedFraction: Double, maxMasked: Double,
                                respExtra: [Double]?, durationS: Double) -> Tail {
        let spec = hrSpectrum(sigX, fs, cfg)
        let peaks = pulseDetect(sigX, fs, mask, cfg, hrHintBpm: spec.hrBpm)
        let ibi = ibiClean(peaks, cfg)
        var hrBeats: Double?
        if ibi.acceptedCount >= 5 {
            var vals: [Double] = []
            for k in 0..<ibi.ibiMs.count where ibi.accepted[k] { vals.append(ibi.ibiMs[k]) }
            hrBeats = 60000.0 / M.mean(vals)
        }
        let q = cfg.quality
        let snrC = M.clamp((spec.snrDb - q.snrDbLow) / (q.snrDbHigh - q.snrDbLow), 0.0, 1.0)
        var corrs: [Double] = []
        for p in peaks {
            if let c = p.corr, !p.masked { corrs.append(c) }
        }
        let templateC = corrs.isEmpty ? 0.0 : M.clamp(M.mean(corrs), 0.0, 1.0)
        var agreementC = 0.0
        if let hrBeats {
            agreementC = 1.0 - M.clamp(abs(hrBeats - spec.hrBpm) / q.hrAgreementBpm, 0.0, 1.0)
        }
        let motionC = 1.0 - M.clamp(maskedFraction / maxMasked, 0.0, 1.0)
        let components = ["agreement": agreementC, "ibi": ibi.acceptedFraction, "motion": motionC,
                          "signal": signalFraction, "snr": snrC, "template": templateC]
        let scored = qualityScore(components, cfg)
        let score = scored.score
        var quality = scored.json
        quality["snr_db"] = .num(M.roundTo(spec.snrDb, 2))
        quality["hr_spectral_bpm"] = .num(M.roundTo(spec.hrBpm, 1))
        quality["hr_beats_bpm"] = .f(M.roundTo(hrBeats, 1))
        quality["masked_fraction"] = .num(M.roundTo(maskedFraction, 3))
        quality["beats"] = .int(peaks.count)

        var metrics: [String: RJ] = [:]
        // The spectral and beat-to-beat rates must agree; a disagreement means the pulse train is unreliable.
        let hrReason: String? = score >= q.minHrQuality && agreementC > 0.0 ? nil : "low_quality"
        let hrConf = score / 100.0 * (0.5 + 0.5 * agreementC)
        metrics["heart_rate"] = envelope(cfg, "heart_rate", source, hrBeats, 1, hrConf, hrReason)

        let hv = cfg.hrv
        var hrvReason: String?
        if hrReason != nil {
            hrvReason = hrReason
        } else if durationS < hv.minS {
            hrvReason = "duration_short"
        } else if Double(ibi.acceptedCount) < hv.minBeats || ibi.acceptedFraction < hv.minAcceptedFraction {
            hrvReason = "few_beats"
        } else if score < (hv.minQuality[source] ?? 0) {
            hrvReason = "low_quality"
        }
        let ht = hrvReason == nil ? hrvTime(ibiMs: ibi.ibiMs, accepted: ibi.accepted) : nil
        if hrvReason == nil && ht == nil { hrvReason = "few_beats" }
        let hrvConf = score / 100.0 * ibi.acceptedFraction
        let ibiReason: String? = ibi.acceptedCount >= 5 && hrReason == nil ? nil : (hrReason ?? "few_beats")
        var ibiMean: Double?
        if ibiReason == nil {
            var acc: [Double] = []
            for k in 0..<ibi.ibiMs.count where ibi.accepted[k] { acc.append(ibi.ibiMs[k]) }
            ibiMean = M.mean(acc)
        }
        metrics["ibi_mean"] = envelope(cfg, "ibi_mean", source, ibiMean, 1, hrvConf, ibiReason, ["count": .int(ibi.acceptedCount)])
        metrics["hrv_rmssd"] = envelope(cfg, "hrv_rmssd", source, ht?.rmssd, 1, hrvConf, hrvReason)
        metrics["hrv_sdnn"] = envelope(cfg, "hrv_sdnn", source, ht?.sdnn, 1, hrvConf, hrvReason)
        metrics["hrv_pnn50"] = envelope(cfg, "hrv_pnn50", source, ht?.pnn50, 1, hrvConf, hrvReason)
        var freqReason = hrvReason
        if freqReason == nil && durationS < hv.freqMinS { freqReason = "duration_short" }
        let hf = freqReason == nil ? hrvFreq(ibiMs: ibi.ibiMs, ibiTMs: ibi.ibiTMs, accepted: ibi.accepted, cfg) : nil
        if freqReason == nil && hf == nil { freqReason = "few_beats" }
        metrics["hrv_lf_hf"] = envelope(cfg, "hrv_lf_hf", source, hf?.lfHf, 2, hrvConf * 0.7, freqReason,
                                        ["lf_nu": .f(M.roundTo(hf?.lfNu, 1)), "hf_nu": .f(M.roundTo(hf?.hfNu, 1))])

        let rp = cfg.respiration
        var respReason: String?
        if hrReason != nil {
            respReason = hrReason
        } else if durationS < rp.minS {
            respReason = "duration_short"
        } else if score < q.minRespQuality {
            respReason = "low_quality"
        } else if ibi.acceptedCount < 8 {
            respReason = "few_beats"
        }
        var resp = RespResult(value: nil, estimates: [], spread: nil)
        if respReason == nil {
            var series: [(tMs: [Double], values: [Double])] = []
            var accPeaks: [Peak] = []
            if peaks.count > 1 {
                for k in 1..<peaks.count where ibi.accepted[k - 1] { accPeaks.append(peaks[k]) }
            }
            if accPeaks.count >= 8 {
                var tList: [Double] = [], ampList: [Double] = [], ibiT: [Double] = [], ibiV: [Double] = []
                for p in accPeaks {
                    tList.append(p.tMs)
                    ampList.append(p.amp - p.foot)
                }
                for k in 0..<ibi.ibiMs.count where ibi.accepted[k] {
                    ibiT.append(ibi.ibiTMs[k])
                    ibiV.append(ibi.ibiMs[k])
                }
                series.append((tList, ampList))
                series.append((ibiT, ibiV))
                if let respExtra {
                    var riiv: [Double] = []
                    for p in accPeaks { riiv.append(respExtra[p.footI]) }
                    series.append((tList, riiv))
                }
                resp = respRate(series, cfg)
            }
            if resp.value == nil { respReason = "resp_disagree" }
        }
        var respConf: Double?
        if resp.value != nil {
            respConf = score / 100.0 * (1.0 - M.clamp(resp.spread! / rp.agreementPerMin, 0.0, 1.0) * 0.5)
        }
        metrics["respiratory_rate"] = envelope(cfg, "respiratory_rate", source, resp.value, 1, respConf, respReason,
                                               ["estimates": .fs(resp.estimates)])
        var ibiT: [RJ] = []
        for t in ibi.ibiTMs { ibiT.append(.num(M.roundTo(t + t0Ms, 1))) }
        let ibiOut: RJ = .obj(["ibi_ms": .fs(ibi.ibiMs), "ibi_quality": .fs(ibi.ibiQuality), "ibi_t_ms": .arr(ibiT)])
        return Tail(quality: quality, score: score, metrics: metrics, ibi: ibiOut, peaks: peaks)
    }

    // MARK: Finger PPG

    /// One frame -> "ok" | "no_finger" | "pressure" (saturated).
    static func fingerDetect(_ frame: [Double], _ cfg: VitalsConfig) -> String {
        let fc = cfg.finger
        let r = frame[1], g = frame[2]
        if r < fc.minRed || r < g * fc.minRedGreenRatio || frame[4] > fc.maxSpatialStd { return "no_finger" }
        if frame[5] > fc.maxSaturatedFraction { return "pressure" }
        return "ok"
    }

    static func fingerFramesState(_ frames: [[Double]], _ cfg: VitalsConfig) -> (states: [String], valid: [Bool]) {
        var states: [String] = []
        states.reserveCapacity(frames.count)
        for f in frames { states.append(fingerDetect(f, cfg)) }
        let jump = cfg.finger.stepJumpFraction
        var valid: [Bool] = []
        valid.reserveCapacity(frames.count)
        for i in 0..<frames.count {
            var ok = states[i] == "ok"
            if ok && i > 0 && frames[i - 1][1] > 0.0 {
                if abs(frames[i][1] - frames[i - 1][1]) / frames[i - 1][1] > jump { ok = false }
            }
            valid.append(ok)
        }
        return (states, valid)
    }

    static func gateFlag(experimental: Bool, research: Bool, _ metricID: String) -> String? {
        if metricID == "spo2" && !experimental { return "experimental_off" }
        if metricID == "blood_pressure" && !research { return "research_off" }
        return nil
    }

    /// `analyze_finger`: the scan result envelope (docs/camera-vitals.md §Result). Needs at least one frame.
    static func analyzeFinger(_ inp: FingerScanInput, _ cfg: VitalsConfig) -> VitalScanResult {
        let frames = inp.frames
        let fs = cfg.signal.fs
        let source = "finger_ppg"
        if frames.count < 2 { return emptyResult(cfg, source, frames.count) }
        let (states, valid) = fingerFramesState(frames, cfg)
        var tMs: [Double] = []
        tMs.reserveCapacity(frames.count)
        for f in frames { tMs.append(f[0]) }
        let durationS = (tMs[tMs.count - 1] - tMs[0]) / 1000.0
        var okFrames = 0
        for v in valid where v { okFrames += 1 }
        let signalFraction = Double(okFrames) / Double(frames.count)
        var grids: [String: [Double]] = [:]
        var mask: [Bool] = []
        for name in ["r", "g", "b"] {
            var col: [Double] = []
            col.reserveCapacity(frames.count)
            for f in frames { col.append(f[1 + channelIndex[name]!]) }
            let (grid, m) = resampleUniform(tMs, col, valid, fs, cfg.signal.maxGapMs)
            mask = m
            grids[name] = grid
        }
        var channels: [String: [Double]] = [:]
        for name in ["r", "g", "b"] { channels[name] = fillMasked(grids[name]!, mask) }
        if signalFraction >= 0.5 {
            // Second pass: mask motion bursts found in the pulse band, then re-fill from the raw grids.
            let art = amplitudeMask(preprocess(channels["r"]!, fs, cfg, invert: true), fs, cfg)
            mask = unionMask(mask, art)
            for name in ["r", "g", "b"] { channels[name] = fillMasked(grids[name]!, mask) }
        }
        let maskedFraction = maskedShare(mask)
        var result: [String: RJ] = ["algorithm_version": .int(cfg.algoVersion), "mode": .str(source),
                                    "duration_s": .num(M.roundTo(durationS, 2)), "frames": .int(frames.count),
                                    "reject_reason": .null]
        var pressure = 0
        for s in states where s == "pressure" { pressure += 1 }
        var reject: String?
        if signalFraction < 0.5 {
            reject = pressure * 2 < frames.count - okFrames ? "no_finger" : "pressure"
        } else if maskedFraction > cfg.finger.maxMaskedFraction {
            reject = "motion"
        } else if durationS < cfg.scan.minS {
            reject = "duration_short"
        }
        if let reject {
            result["reject_reason"] = .str(reject)
            result["quality"] = .obj(["score": .num(0.0), "grade": .str("poor"),
                                      "components": .obj(["signal": .num(M.roundTo(signalFraction, 3))]),
                                      "masked_fraction": .num(M.roundTo(maskedFraction, 3))])
            result["metrics"] = .obj(unavailableAll(cfg, source, reject))
            result["ibi"] = .obj(["ibi_ms": .arr([]), "ibi_quality": .arr([]), "ibi_t_ms": .arr([])])
            return VitalScanResult(json: .obj(result))
        }
        var candidates: [String: (x: [Double], snr: Double)] = [:]
        for name in ["r", "g"] {
            let x = preprocess(channels[name]!, fs, cfg, invert: true)
            candidates[name] = (x, hrSpectrum(x, fs, cfg).snrDb)
        }
        let chosen = candidates["r"]!.snr >= candidates["g"]!.snr ? "r" : "g"
        let x = candidates[chosen]!.x
        var riiv: [Double] = []
        let raw = channels[chosen]!
        riiv.reserveCapacity(raw.count)
        for v in raw { riiv.append(-v) }
        let tail = beatsAndMetrics(x, fs, mask, tMs[0], source, cfg, signalFraction: signalFraction,
                                   maskedFraction: maskedFraction, maxMasked: cfg.finger.maxMaskedFraction,
                                   respExtra: riiv, durationS: durationS)
        var quality = tail.quality
        quality["channel"] = .str(chosen)
        quality["channel_snr_db"] = .obj(["g": .num(M.roundTo(candidates["g"]!.snr, 2)),
                                          "r": .num(M.roundTo(candidates["r"]!.snr, 2))])
        var metrics = tail.metrics
        // Experimental SpO2: ratio of ratios between the two channels, only against a personal calibration.
        let spReason = gateFlag(experimental: inp.experimentalEnabled, research: inp.researchEnabled, "spo2")
        var ratio: Double?
        if tail.score >= cfg.research.spo2MinQuality {
            ratio = spo2Ratio(channels, tail.peaks, fs, cfg)
        }
        let sp = spo2Estimate(ratio: ratio, quality: tail.score, calibrations: inp.spo2Calibrations, cfg)
        metrics["spo2"] = envelope(cfg, "spo2", source, sp.value, 0, sp.confidence, spReason ?? sp.reason,
                                   ["ratio": .f(M.roundTo(ratio, 4))])
        let hrValue = metrics["heart_rate"]!["value"].double
        var feats: [String: Double]?
        if let hrValue, hrValue != 0 {
            feats = bpFeatures(x: x, peaks: tail.peaks, fs: fs, hr: hrValue, cfg)
        }
        let bp = bpResearch(features: feats, calibrations: inp.bpCalibrations, nowMs: inp.nowMs, quality: tail.score, cfg)
        let bpReason = gateFlag(experimental: inp.experimentalEnabled, research: inp.researchEnabled, "blood_pressure")
        let bpFinal = bpReason ?? bp.reason
        metrics["blood_pressure"] = envelope(cfg, "blood_pressure", source, bp.sbp, 0, bp.confidence, bpFinal,
                                             ["diastolic": bpFinal == nil ? .f(bp.dbp) : .null,
                                              "features": featuresJSON(feats)])
        result["quality"] = .obj(quality)
        result["metrics"] = .obj(metrics)
        result["ibi"] = tail.ibi
        if inp.includeSignals {
            result["signals"] = signalsJSON(cfg, processed: x, mask: mask, peaks: tail.peaks)
        }
        return VitalScanResult(json: .obj(result))
    }

    /// `_empty_result`: fewer than two frames (or no face ROI) — nothing to analyse.
    static func emptyResult(_ cfg: VitalsConfig, _ source: String, _ frames: Int) -> VitalScanResult {
        let result: [String: RJ] = [
            "algorithm_version": .int(cfg.algoVersion), "mode": .str(source), "duration_s": .num(0.0),
            "frames": .int(frames), "reject_reason": .str("duration_short"),
            "quality": .obj(["score": .num(0.0), "grade": .str("poor"), "components": .obj(["signal": .num(0.0)]),
                             "masked_fraction": .num(0.0)]),
            "metrics": .obj(unavailableAll(cfg, source, "duration_short")),
            "ibi": .obj(["ibi_ms": .arr([]), "ibi_quality": .arr([]), "ibi_t_ms": .arr([])]),
        ]
        return VitalScanResult(json: .obj(result))
    }

    static func featuresJSON(_ feats: [String: Double]?) -> RJ {
        guard let feats else { return .null }
        return .obj(feats.mapValues { .num($0) })
    }

    static func signalsJSON(_ cfg: VitalsConfig, processed: [Double], mask: [Bool], peaks: [Peak]) -> RJ {
        .obj(["fs": cfg.fsJSON, "processed": .fs(processed), "mask": .arr(mask.map { .bool($0) }),
              "peaks_ms": .fs(peaks.map(\.tMs))])
    }

    // MARK: Face rPPG

    /// `analyze_face`. Needs at least one frame and one configured ROI.
    static func analyzeFace(_ inp: FaceScanInput, _ cfg: VitalsConfig) -> VitalScanResult {
        let face = inp.face
        let fs = cfg.signal.fs
        let source = "face_rppg"
        var roiNames: [String] = []
        for name in cfg.face.rois where face.rois[name] != nil { roiNames.append(name) }
        let tMs = face.tMs
        let nFrames = tMs.count
        if nFrames < 2 || roiNames.isEmpty { return emptyResult(cfg, source, nFrames) }
        var valid: [Bool] = [], gates: [String: Int] = [:]
        for i in 0..<nFrames {
            let g = faceFrameGate(face, i, roiNames, cfg)
            valid.append(g == nil)
            if let g { gates[g, default: 0] += 1 }
        }
        var ok = 0
        for v in valid where v { ok += 1 }
        let signalFraction = Double(ok) / Double(nFrames)
        let durationS = (tMs[nFrames - 1] - tMs[0]) / 1000.0
        var result: [String: RJ] = ["algorithm_version": .int(cfg.algoVersion), "mode": .str(source),
                                    "duration_s": .num(M.roundTo(durationS, 2)), "frames": .int(nFrames),
                                    "reject_reason": .null]
        let gateCounts: RJ = .obj(gates.mapValues { .int($0) })
        var rawRoi: [String: [[Double]]] = [:]
        var mask: [Bool] = []
        for roi in roiNames {
            var rgb: [[Double]] = []
            let rows = face.rois[roi]!
            for c in 0..<3 {
                var col: [Double] = []
                col.reserveCapacity(nFrames)
                for i in 0..<nFrames { col.append(rows[i][c]) }
                let (grid, m) = resampleUniform(tMs, col, valid, fs, cfg.signal.maxGapMs)
                mask = m
                rgb.append(grid)
            }
            rawRoi[roi] = rgb
        }
        if signalFraction >= 0.5 {
            // Second pass: artifact mask from the ROI-averaged green signal.
            var green = [Double](repeating: 0.0, count: mask.count)
            for roi in roiNames {
                let filled = fillMasked(rawRoi[roi]![1], mask)
                for k in 0..<green.count { green[k] += filled[k] / Double(roiNames.count) }
            }
            mask = unionMask(mask, amplitudeMask(preprocess(green, fs, cfg, invert: true), fs, cfg))
        }
        var perRoi: [String: [[Double]]] = [:]
        for roi in roiNames {
            var rgb: [[Double]] = []
            for c in 0..<3 { rgb.append(fillMasked(rawRoi[roi]![c], mask)) }
            perRoi[roi] = rgb
        }
        let maskedFraction = maskedShare(mask)
        let maxMasked = cfg.face.gates.maxMaskedFraction
        var reject: String?
        if maskedFraction > maxMasked {
            var dominant: String?
            var domN = -1
            for k in gates.keys.sorted() where gates[k]! > domN {
                dominant = k
                domN = gates[k]!
            }
            if dominant == "light_dark" || dominant == "light_bright" {
                reject = "lighting"
            } else if dominant == nil || dominant == "hold_still" {
                reject = "motion"
            } else {
                reject = "no_face"
            }
        } else if durationS < cfg.scan.minS {
            reject = "duration_short"
        }
        if let reject {
            result["reject_reason"] = .str(reject)
            result["quality"] = .obj(["score": .num(0.0), "grade": .str("poor"),
                                      "components": .obj(["signal": .num(M.roundTo(signalFraction, 3))]),
                                      "masked_fraction": .num(M.roundTo(maskedFraction, 3)), "gates": gateCounts])
            result["metrics"] = .obj(unavailableAll(cfg, source, reject))
            result["ibi"] = .obj(["ibi_ms": .arr([]), "ibi_quality": .arr([]), "ibi_t_ms": .arr([])])
            return VitalScanResult(json: .obj(result))
        }
        var methods: [String: [String: [Double]?]] = [:]
        for roi in roiNames { methods[roi] = rppgMethods(perRoi[roi]!, fs, cfg) }
        let comb = roiCombine(methods, fs, cfg)
        let tail = beatsAndMetrics(comb.signal, fs, mask, tMs[0], source, cfg, signalFraction: signalFraction,
                                   maskedFraction: maskedFraction, maxMasked: maxMasked, respExtra: nil, durationS: durationS)
        var quality = tail.quality
        quality["method"] = .str(comb.method)
        quality["rois"] = .arr(comb.rois.map { .str($0) })
        quality["roi_weights"] = .obj(comb.weights.mapValues { .num($0) })
        quality["method_snr_db"] = .obj(comb.snrDb.mapValues { row in .obj(row.mapValues { RJ.f($0) }) })
        quality["gates"] = gateCounts
        var metrics = tail.metrics
        metrics["spo2"] = envelope(cfg, "spo2", source, nil, 0, nil, "face_not_supported")
        metrics["blood_pressure"] = envelope(cfg, "blood_pressure", source, nil, 0, nil,
                                             gateFlag(experimental: inp.experimentalEnabled, research: inp.researchEnabled,
                                                      "blood_pressure") ?? "face_not_supported")
        result["quality"] = .obj(quality)
        result["metrics"] = .obj(metrics)
        result["ibi"] = tail.ibi
        if inp.includeSignals {
            result["signals"] = signalsJSON(cfg, processed: comb.signal, mask: mask, peaks: tail.peaks)
        }
        return VitalScanResult(json: .obj(result))
    }

    // MARK: Live control, comparison, baselines, indicators, validation

    /// "continue" until target_s; past it, "extend" while quality is below extend_below_quality and elapsed < max_s;
    /// otherwise "finish".
    static func scanControl(elapsedS e: Double, quality q: Double?, _ cfg: VitalsConfig) -> String {
        let sc = cfg.scan
        if e < sc.targetS { return "continue" }
        if e >= sc.maxS { return "finish" }
        if q == nil || q! < sc.extendBelowQuality { return "extend" }
        return "finish"
    }

    /// One side of a finger / face comparison; nil for unavailable values.
    struct CompareSide: Sendable {
        var hr: Double?
        var rmssd: Double?
        var ibiMean: Double?
    }

    /// Never picks a winner: reports the differences and whether they agree within the configured limits.
    static func compare(finger f: CompareSide, face c: CompareSide, _ cfg: VitalsConfig) -> RJ {
        let cp = cfg.compare
        var out: [String: RJ] = ["hr_diff": .null, "rmssd_diff": .null, "ibi_mean_diff": .null, "status": .str("incomplete")]
        guard let fh = f.hr, let ch = c.hr else { return .obj(out) }
        let hrDiff = M.roundTo(abs(fh - ch), 1)
        out["hr_diff"] = .num(hrDiff)
        var consistent = hrDiff <= cp.maxHrDiffBpm
        if let fr = f.rmssd, let cr = c.rmssd {
            let d = M.roundTo(abs(fr - cr), 1)
            out["rmssd_diff"] = .num(d)
            consistent = consistent && d <= cp.maxRmssdDiffMs
        }
        if let fi = f.ibiMean, let ci = c.ibiMean {
            out["ibi_mean_diff"] = .num(M.roundTo(abs(fi - ci), 1))
        }
        out["status"] = .str(consistent ? "consistent" : "inconsistent")
        return .obj(out)
    }

    struct TimedValue: Sendable {
        var tMs: Double
        var value: Double
    }

    /// Per window (days; nil = all history): {n, median, mad} or null below baseline.min_n.
    static func baselines(values: [TimedValue], nowMs: Double, windowsDays: [Double?], _ cfg: VitalsConfig) -> RJ {
        var out: [String: RJ] = [:]
        for w in windowsDays {
            var vals: [Double] = []
            for v in values {
                if v.tMs > nowMs { continue }
                if let w, nowMs - v.tMs > w * 86400000.0 { continue }
                vals.append(v.value)
            }
            let key = w.map { "\(Int($0))d" } ?? "all"
            if Double(vals.count) < cfg.baselineMinN {
                out[key] = .null
            } else {
                out[key] = .obj(["n": .int(vals.count), "median": .num(M.roundTo(M.median(vals), 1)),
                                 "mad": .num(M.roundTo(M.mad(vals), 2))])
            }
        }
        return .obj(out)
    }

    struct IndicatorScan: Sendable {
        var tMs: Double = 0
        var hr: Double?
        var rmssd: Double?
    }

    /// Recovery / physiological stress indicator from one scan against earlier scans of the same mode (robust z-scores).
    static func scanIndicator(current cur: IndicatorScan, history: [IndicatorScan], nowMs: Double, quality: Double,
                              _ cfg: VitalsConfig) -> RJ {
        let ic = cfg.indicator
        var hist: [IndicatorScan] = []
        for h in history {
            guard h.hr != nil, let r = h.rmssd, r > 0.0 else { continue }
            if h.tMs >= nowMs || nowMs - h.tMs > ic.windowDays * 86400000.0 { continue }
            hist.append(h)
        }
        var none: [String: RJ] = ["recovery": .null, "stress": .null, "band": .null, "confidence": .null, "reason": .null,
                                  "n": .int(hist.count)]
        guard let curHr = cur.hr, let curR = cur.rmssd, curR > 0.0 else {
            none["reason"] = .str("few_beats")
            return .obj(none)
        }
        if Double(hist.count) < ic.minHistory {
            none["reason"] = .str("short_history")
            return .obj(none)
        }
        var lnR: [Double] = [], hrs: [Double] = []
        for h in hist {
            lnR.append(log(h.rmssd!))
            hrs.append(h.hr!)
        }
        var spR = 1.4826 * M.mad(lnR)
        if spR < ic.minSpreadLnRmssd { spR = ic.minSpreadLnRmssd }
        var spH = 1.4826 * M.mad(hrs)
        if spH < ic.minSpreadHr { spH = ic.minSpreadHr }
        let zR = (log(curR) - M.median(lnR)) / spR
        let zH = (curHr - M.median(hrs)) / spH
        var rec = M.clamp(50.0 + ic.scale * (ic.hrvWeight * zR - ic.hrWeight * zH), 0.0, 100.0)
        rec = M.roundTo(rec, 0)
        var band = ic.bands[0].id
        for b in ic.bands where rec >= b.min { band = b.id }
        let conf = M.clamp(Double(hist.count) / 14.0, 0.0, 1.0) * quality / 100.0
        return .obj(["recovery": .num(rec), "stress": .num(M.roundTo(100.0 - rec, 0)), "band": .str(band),
                     "confidence": .num(M.roundTo(conf, 2)), "reason": .null, "n": .int(hist.count),
                     "z_hr": .num(M.roundTo(zH, 2)), "z_ln_rmssd": .num(M.roundTo(zR, 2))])
    }

    struct ValidationPair: Sendable {
        var measured: Double
        var reference: Double
        var confidence: Double?
    }

    /// MAE, RMSE, bias, SD of differences, Bland-Altman limits, Pearson r, failure rate and MAE per confidence label.
    static func validationStats(pairs: [ValidationPair], failures fails: Int, _ cfg: VitalsConfig) -> RJ {
        let n = pairs.count
        var out: [String: RJ] = ["n": .int(n),
                                 "failure_rate": n + fails > 0 ? .num(M.roundTo(Double(fails) / Double(n + fails), 3)) : .null]
        if n < 2 {
            for k in ["mae", "rmse", "bias", "sd", "loa_low", "loa_high", "r"] { out[k] = .null }
            out["by_confidence"] = .obj([:])
            return .obj(out)
        }
        var diffs: [Double] = [], absd: [Double] = [], ms: [Double] = [], rs: [Double] = []
        var sq = 0.0
        for p in pairs {
            let d = p.measured - p.reference
            diffs.append(d)
            absd.append(abs(d))
            sq += d * d
            ms.append(p.measured)
            rs.append(p.reference)
        }
        let bias = M.mean(diffs)
        let sd = M.sampleStd(diffs)!
        out["mae"] = .num(M.roundTo(M.mean(absd), 2))
        out["rmse"] = .num(M.roundTo((sq / Double(n)).squareRoot(), 2))
        out["bias"] = .num(M.roundTo(bias, 2))
        out["sd"] = .num(M.roundTo(sd, 2))
        out["loa_low"] = .num(M.roundTo(bias - 1.96 * sd, 2))
        out["loa_high"] = .num(M.roundTo(bias + 1.96 * sd, 2))
        out["r"] = .num(M.roundTo(M.pearson(ms, rs), 3))
        var groups: [String: [Double]] = [:]
        for p in pairs {
            let label = M.confidenceLabel(p.confidence) ?? "unknown"
            groups[label, default: []].append(abs(p.measured - p.reference))
        }
        var by: [String: RJ] = [:]
        for label in groups.keys.sorted() {
            by[label] = .obj(["n": .int(groups[label]!.count), "mae": .num(M.roundTo(M.mean(groups[label]!), 2))])
        }
        out["by_confidence"] = .obj(by)
        return .obj(out)
    }

    /// `effective_config`: deep-merges device_overrides[deviceModel] (the given overrides, else the config's) and
    /// returns the merged sections that changed. Use `VitalsConfig.effective(deviceModel:)` for the merged config.
    static func effectiveConfig(deviceModel: String, deviceOverrides: RJ?, _ cfg: VitalsConfig) -> RJ {
        let overrides = deviceOverrides ?? cfg.deviceOverrides
        let patch = overrides[deviceModel]
        guard case .obj(let p) = patch else {
            return .obj(["device_model": .str(deviceModel), "overridden": .arr([])])
        }
        let merged = VitalsConfig.merge(cfg.raw, patch)
        let changed = p.keys.sorted()
        var sections: [String: RJ] = [:]
        for k in changed { sections[k] = merged[k] }
        return .obj(["device_model": .str(deviceModel), "overridden": .arr(changed.map { .str($0) }), "sections": .obj(sections)])
    }
}

/// One scan result (`analyze_finger` / `analyze_face` output) with typed accessors over the reference-shaped JSON.
nonisolated struct VitalScanResult: Sendable {
    let json: RJ

    var mode: String? { json["mode"].string }
    var rejectReason: String? { json["reject_reason"].string }
    var algorithmVersion: Int { Int(json["algorithm_version"].double ?? 0) }
    var durationS: Double? { json["duration_s"].double }
    var quality: RJ { json["quality"] }
    var qualityScore: Double? { quality["score"].double }
    var metrics: RJ { json["metrics"] }
    var ibi: RJ { json["ibi"] }
    /// `signals` (processed signal, mask, peaks) when the scan ran with include_signals.
    var signals: RJ { json["signals"] }

    func metric(_ id: String) -> Metric { Metric(json: metrics[id]) }

    /// `results_json` for `vital_scans`: the result without the bulky signals (those go to `vital_scan_signals`).
    var resultsJSONText: String {
        guard case .obj(var o) = json else { return VitalsJSON.encode(json) }
        o["signals"] = nil
        return VitalsJSON.encode(.obj(o))
    }

    var qualityJSONText: String { VitalsJSON.encode(quality) }

    struct Metric: Sendable {
        let json: RJ
        var value: Double? { json["value"].double }
        var unit: String? { json["unit"].string }
        var confidence: Double? { json["confidence"].double }
        var confidenceLabel: String? { json["confidence_label"].string }
        var classification: String? { json["classification"].string }
        var status: String? { json["status"].string }
        var reason: String? { json["reason"].string }
        var isValid: Bool { status == "valid" }
    }
}
