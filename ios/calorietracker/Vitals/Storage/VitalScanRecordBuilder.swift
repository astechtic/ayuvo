import Foundation

/// Builds the saved `vital_scans` row and its signals (docs/camera-vitals.md §7.1 Saved record / Signals). Pure.
/// Scans are Ayuvo's own measurements: never HealthKit, never `health_samples`.
nonisolated enum VitalScanRecordBuilder {
    struct Input: Sendable {
        var result: VitalScanResult
        var buffer: VitalsFrameBuffer
        var mode: VitalsMode
        var sessionID: String?
        var context: String
        var startMs: Int64
        var camera: CameraConfiguration
        var deviceModel: String
        var keepSignals: Bool
        var timeZone: TimeZone = .current
        var nowMs: Int64
        var id: String = VitalScanRecord.newID()
    }

    /// The record and (only when `keepSignals`) its `frame_stats`, `processed`, `mask` and `beats` signals.
    /// `history` is every live scan; the indicators use the earlier valid scans of the same mode.
    static func build(_ input: Input, history: [VitalScanRecord], _ cfg: VitalsConfig = .shared) throws -> (record: VitalScanRecord, signals: [VitalSignal]) {
        let result = input.result
        let durationMs = Int64(((result.durationS ?? 0) * 1000).rounded())
        var camera = input.camera
        if camera.achievedFps == nil { camera.achievedFps = CameraConfiguration.achievedFps(input.buffer.tMs) }
        let record = VitalScanRecord(
            id: input.id, mode: input.mode.rawValue, sessionID: input.sessionID, startMs: input.startMs,
            endMs: input.startMs + durationMs, tzOffsetS: input.timeZone.secondsFromGMT(for: Date(timeIntervalSince1970: Double(input.startMs) / 1000)),
            localDay: DerivedDay.localDayOf(input.startMs, input.timeZone), durationMs: durationMs, platform: "ios",
            deviceModel: input.deviceModel, cameraJSON: VitalsJSON.encode(camera.json), context: input.context,
            qualityScore: result.qualityScore, rejectReason: result.rejectReason,
            qualityJSON: VitalsJSON.encode(result.quality),
            resultsJSON: VitalsJSON.encode(resultsJSON(result, mode: input.mode, startMs: input.startMs, history: history, cfg)),
            algoVersion: result.algorithmVersion, referenceJSON: nil, deleted: 0, updatedMs: input.nowMs)
        guard input.keepSignals else { return (record, []) }
        var signals: [VitalSignal] = []
        switch input.mode {
        case .finger: signals.append(try VitalSignalCodec.fingerFrameStats(input.buffer.fingerFrames))
        case .face: signals.append(try VitalSignalCodec.faceFrameStats(input.buffer.faceFrames(rois: cfg.face.rois), roiOrder: cfg.face.rois))
        }
        signals += try VitalSignalCodec.signals(from: result)
        return (record, signals)
    }

    /// `results_json`: the engine output without `quality` and `signals`, plus `indicators`.
    static func resultsJSON(_ result: VitalScanResult, mode: VitalsMode, startMs: Int64, history: [VitalScanRecord],
                            _ cfg: VitalsConfig) -> RJ {
        guard case .obj(var o) = result.json else { return result.json }
        o["quality"] = nil
        o["signals"] = nil
        o["indicators"] = indicators(result, mode: mode, startMs: startMs, history: history, cfg)
        return .obj(o)
    }

    /// Recovery and physiological stress indicator envelopes from `scan_indicator` against earlier valid scans of the
    /// same mode; unavailable with the reason when they can't be computed.
    static func indicators(_ result: VitalScanResult, mode: VitalsMode, startMs: Int64, history: [VitalScanRecord],
                           _ cfg: VitalsConfig) -> RJ {
        let source = mode.rawValue
        if let reject = result.rejectReason {
            return .obj(["recovery_indicator": VitalsEngine.envelope(cfg, "recovery_indicator", source, nil, 0, nil, reject),
                         "stress_indicator": VitalsEngine.envelope(cfg, "stress_indicator", source, nil, 0, nil, reject)])
        }
        let hr = result.metric("heart_rate"), rmssd = result.metric("hrv_rmssd")
        let current = VitalsEngine.IndicatorScan(tMs: Double(startMs), hr: hr.isValid ? hr.value : nil,
                                                 rmssd: rmssd.isValid ? rmssd.value : nil)
        let earlier = history.filter { $0.mode == mode.rawValue && $0.deleted == 0 && $0.rejectReason == nil && $0.startMs < startMs }
        let ind = VitalsEngine.scanIndicator(current: current, history: earlier.compactMap(indicatorScan), nowMs: Double(startMs),
                                             quality: result.qualityScore ?? 0, cfg)
        let reason = ind["reason"].string
        let recovery = ind["recovery"].double, stress = ind["stress"].double
        let confidence = ind["confidence"].double
        let n: RJ = ind["n"]
        func band(_ v: Double?) -> RJ {
            guard let v else { return .null }
            var id = cfg.indicator.bands[0].id
            for b in cfg.indicator.bands where v >= b.min { id = b.id }
            return .str(id)
        }
        var recExtra: [String: RJ] = ["band": band(recovery), "n": n]
        if reason == nil {
            recExtra["z_hr"] = ind["z_hr"]
            recExtra["z_ln_rmssd"] = ind["z_ln_rmssd"]
        }
        return .obj([
            "recovery_indicator": VitalsEngine.envelope(cfg, "recovery_indicator", source, recovery, 0, confidence, reason, recExtra),
            "stress_indicator": VitalsEngine.envelope(cfg, "stress_indicator", source, stress, 0, confidence, reason,
                                                      ["band": band(stress), "n": n]),
        ])
    }

    /// HR and RMSSD of a saved valid scan (nil values when unavailable).
    static func indicatorScan(_ record: VitalScanRecord) -> VitalsEngine.IndicatorScan? {
        guard let json = try? VitalsJSON.parse(record.resultsJSON) else { return nil }
        func valid(_ id: String) -> Double? {
            let m = json["metrics"][id]
            return m["status"].string == "valid" ? m["value"].double : nil
        }
        return VitalsEngine.IndicatorScan(tMs: Double(record.startMs), hr: valid("heart_rate"), rmssd: valid("hrv_rmssd"))
    }

    // MARK: Calibrations → engine inputs

    static func spo2Calibrations(_ rows: [VitalCalibration]) -> [VitalsEngine.Spo2Calibration] {
        rows.compactMap { c in
            guard c.kind == "spo2", let ref = try? VitalsJSON.parse(c.referenceJSON), let feat = try? VitalsJSON.parse(c.featuresJSON),
                  let spo2 = ref["spo2"].double, let ratio = feat["ratio"].double else { return nil }
            return VitalsEngine.Spo2Calibration(ratio: ratio, spo2: spo2)
        }
    }

    static func bpCalibrations(_ rows: [VitalCalibration]) -> [VitalsEngine.BpCalibration] {
        rows.compactMap { c in
            guard c.kind == "bp", let ref = try? VitalsJSON.parse(c.referenceJSON), let feat = try? VitalsJSON.parse(c.featuresJSON),
                  let sbp = ref["sbp"].double, let dbp = ref["dbp"].double else { return nil }
            var features: [String: Double] = [:]
            for (k, v) in feat.object ?? [:] { if let d = v.double { features[k] = d } }
            return VitalsEngine.BpCalibration(tMs: Double(c.tMs), scanGapMin: ref["scan_gap_min"].double ?? 0,
                                              features: features.isEmpty ? nil : features, sbp: sbp, dbp: dbp)
        }
    }
}
