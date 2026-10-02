import Foundation
import Testing
@testable import calorietracker

/// The saved `vital_scans` record (docs/camera-vitals.md §7.1 Saved record / Signals).
struct VitalsRecordBuilderTests {
    static let cfg = VitalsConfig.shared

    static func fingerSpec(_ json: String) throws -> VitalsSynth.Spec {
        VitalsSynth.Spec(json: try VitalsJSON.parse(json))
    }

    static func buffer(_ frames: [[Double]]) -> VitalsFrameBuffer {
        var b = VitalsFrameBuffer(mode: .finger)
        for f in frames { b.append(.finger(f)) }
        return b
    }

    /// An earlier saved scan with the given HR / RMSSD.
    static func earlier(_ i: Int, mode: VitalsMode = .finger, hr: Double, rmssd: Double, startMs: Int64,
                        reject: String? = nil) -> VitalScanRecord {
        let metrics: RJ = .obj([
            "heart_rate": .obj(["status": .str("valid"), "value": .num(hr)]),
            "hrv_rmssd": .obj(["status": .str("valid"), "value": .num(rmssd)]),
        ])
        return VitalScanRecord(id: "local:h\(i)", mode: mode.rawValue, sessionID: nil, startMs: startMs, endMs: startMs + 60_000,
                               tzOffsetS: 0, localDay: "2026-10-01", durationMs: 60_000, platform: "ios", deviceModel: "iPhone15,2",
                               cameraJSON: "{}", context: "resting", qualityScore: 90, rejectReason: reject, qualityJSON: "{}",
                               resultsJSON: VitalsJSON.encode(.obj(["metrics": metrics])), algoVersion: 1, referenceJSON: nil,
                               updatedMs: startMs)
    }

    static func input(_ result: VitalScanResult, _ buffer: VitalsFrameBuffer, keep: Bool, startMs: Int64) -> VitalScanRecordBuilder.Input {
        VitalScanRecordBuilder.Input(
            result: result, buffer: buffer, mode: .finger, sessionID: "s1", context: "resting", startMs: startMs,
            camera: CameraConfiguration(position: "back", lens: "wide", width: 640, height: 480, targetFps: 30, achievedFps: nil,
                                        exposureMs: 16.6, iso: 100, whiteBalanceLocked: true, torch: true),
            deviceModel: "iPhone15,2", keepSignals: keep, timeZone: TimeZone(identifier: "Asia/Kolkata")!, nowMs: startMs + 70_000,
            id: "local:new")
    }

    @Test func resultsJSONHasIndicatorsAndNoQualityOrSignals() throws {
        let spec = try Self.fingerSpec(#"{"ac": 0.02, "duration_s": 62, "fs": 30, "hr_bpm": 72, "jitter_ms": 3, "noise": 0.0005, "resp_bpm": 15, "rsa_ms": 30, "seed": 7}"#)
        let frames = VitalsSynth.synthFinger(spec)
        let buffer = Self.buffer(frames)
        let result = VitalsAnalyzer.analyze(buffer, options: .init(), Self.cfg)
        #expect(result.rejectReason == nil)
        let start: Int64 = 1_790_000_000_000
        let day: Int64 = 86_400_000
        var history: [VitalScanRecord] = []
        for i in 0..<6 {
            history.append(Self.earlier(i, hr: 70 + Double(i % 3), rmssd: 40 + Double(i), startMs: start - Int64(i + 1) * day))
        }
        // Not counted: a face scan, a rejected scan and a later scan.
        history.append(Self.earlier(10, mode: .face, hr: 90, rmssd: 10, startMs: start - day))
        history.append(Self.earlier(11, hr: 120, rmssd: 5, startMs: start - day, reject: "motion"))
        history.append(Self.earlier(12, hr: 120, rmssd: 5, startMs: start + day))

        let built = try VitalScanRecordBuilder.build(Self.input(result, buffer, keep: true, startMs: start), history: history)
        let r = built.record
        #expect(r.id == "local:new" && r.mode == "finger_ppg" && r.platform == "ios" && r.sessionID == "s1")
        #expect(r.tzOffsetS == 19_800)
        #expect(r.localDay == DerivedDay.localDayOf(start, TimeZone(identifier: "Asia/Kolkata")!))
        #expect(r.durationMs == Int64(((result.durationS ?? 0) * 1000).rounded()) && r.endMs == start + r.durationMs)
        #expect(r.qualityScore == result.qualityScore && r.rejectReason == nil && r.algoVersion == Self.cfg.algoVersion)
        #expect(r.qualityJSON == VitalsJSON.encode(result.quality))

        let results = try VitalsJSON.parse(r.resultsJSON)
        #expect(results["quality"].isNull && results["signals"].isNull)
        #expect(results["metrics"]["heart_rate"]["value"].double == result.metric("heart_rate").value)
        let rec = results["indicators"]["recovery_indicator"]
        let stress = results["indicators"]["stress_indicator"]
        #expect(rec["status"].string == "valid" && rec["classification"].string == "estimated" && rec["unit"].string == "score")
        #expect(rec["n"].double == 6)
        let recovery = try #require(rec["value"].double)
        #expect(stress["value"].double == 100 - recovery)
        #expect(rec["band"].string != nil && stress["band"].string != nil)

        let camera = try VitalsJSON.parse(r.cameraJSON)
        for key in ["position", "lens", "width", "height", "target_fps", "achieved_fps", "exposure_ms", "iso", "white_balance_locked", "torch"] {
            #expect(camera.object?[key] != nil, "camera_json.\(key)")
        }
        #expect(abs((camera["achieved_fps"].double ?? 0) - 30) < 1)

        // Signals: frame_stats reproduces the engine input to float32; processed / mask / beats from the result.
        #expect(built.signals.map(\.kind) == ["frame_stats", "processed", "mask", "beats"])
        let stats = try VitalSignalCodec.decode(built.signals[0])
        #expect(stats.columns == ["t_ms", "r", "g", "b", "r_std", "sat_frac"])
        let t0 = frames[0][0]
        #expect(stats.rows.count == frames.count)
        for (row, f) in zip(stats.rows, frames) {
            #expect(row == [Float(f[0] - t0), Float(f[1]), Float(f[2]), Float(f[3]), Float(f[4]), Float(f[5])])
        }
        #expect(try VitalSignalCodec.decode(built.signals[1]).columns == ["x"])
        #expect(try VitalSignalCodec.decode(built.signals[2]).columns == ["m"])
        #expect(try VitalSignalCodec.decode(built.signals[3]).columns == ["t_ms"])
    }

    @Test func noSignalsWhenKeepSignalsIsOff() throws {
        let spec = try Self.fingerSpec(#"{"ac": 0.02, "duration_s": 62, "fs": 30, "hr_bpm": 66, "jitter_ms": 3, "noise": 0.0005, "resp_bpm": 12, "rsa_ms": 20, "seed": 3}"#)
        let buffer = Self.buffer(VitalsSynth.synthFinger(spec))
        let result = VitalsAnalyzer.analyze(buffer, options: .init(), Self.cfg)
        let built = try VitalScanRecordBuilder.build(Self.input(result, buffer, keep: false, startMs: 1_000_000), history: [])
        #expect(built.signals.isEmpty)
        let ind = try VitalsJSON.parse(built.record.resultsJSON)["indicators"]
        #expect(ind["recovery_indicator"]["status"].string == "unavailable")
        #expect(ind["recovery_indicator"]["reason"].string == "short_history")
        #expect(ind["stress_indicator"]["reason"].string == "short_history")
    }

    @Test func rejectedScanHasUnavailableIndicatorsWithTheRejectReason() throws {
        let spec = try Self.fingerSpec(#"{"ac": 0.02, "duration_s": 40, "events": [{"amp": 0, "end_s": 40, "kind": "no_finger", "start_s": 0}], "fs": 30, "hr_bpm": 65, "jitter_ms": 3, "noise": 0.0005, "resp_bpm": 15, "rsa_ms": 30, "seed": 5}"#)
        let buffer = Self.buffer(VitalsSynth.synthFinger(spec))
        let result = VitalsAnalyzer.analyze(buffer, options: .init(), Self.cfg)
        #expect(result.rejectReason == "no_finger")
        let built = try VitalScanRecordBuilder.build(Self.input(result, buffer, keep: true, startMs: 5_000), history: [])
        #expect(built.record.rejectReason == "no_finger")
        let ind = try VitalsJSON.parse(built.record.resultsJSON)["indicators"]
        #expect(ind["recovery_indicator"]["reason"].string == "no_finger")
        #expect(ind["stress_indicator"]["status"].string == "unavailable")
    }

    @Test func faceFrameStatsColumnsFollowConfigOrder() throws {
        var spec = VitalsSynth.Spec(fs: 30, durationS: 3, hrBpm: 70)
        for name in Self.cfg.face.rois { spec.rois[name] = VitalsSynth.Roi(ac: 0.004, noise: 0.002) }
        let face = VitalsSynth.synthFace(spec)
        let signal = try VitalSignalCodec.faceFrameStats(face, roiOrder: Self.cfg.face.rois)
        let table = try VitalSignalCodec.decode(signal)
        #expect(Array(table.columns.prefix(7)) == ["t_ms", "motion", "yaw", "pitch", "luma", "face_count", "face_fraction"])
        #expect(Array(table.columns[7..<11]) == ["forehead_r", "forehead_g", "forehead_b", "forehead_skin"])
        #expect(table.columns.count == 7 + 4 * Self.cfg.face.rois.count)
        #expect(table.rows.count == face.tMs.count)
        #expect(table.rows[0][0] == 0)
    }

    @Test func calibrationRowsBecomeEngineInputs() {
        let spo2 = VitalCalibration(id: "c1", kind: "spo2", deviceModel: "m", scanID: nil, tMs: 1, referenceJSON: #"{"spo2":98}"#,
                                    featuresJSON: #"{"ratio":0.62}"#, updatedMs: 1)
        let bp = VitalCalibration(id: "c2", kind: "bp", deviceModel: "m", scanID: nil, tMs: 2,
                                  referenceJSON: #"{"sbp":118,"dbp":76,"scan_gap_min":2}"#, featuresJSON: #"{"hr":70.0,"rise_ms":220}"#,
                                  updatedMs: 2)
        #expect(VitalScanRecordBuilder.spo2Calibrations([spo2, bp]) == [.init(ratio: 0.62, spo2: 98)])
        #expect(VitalScanRecordBuilder.bpCalibrations([spo2, bp]) == [.init(tMs: 2, scanGapMin: 2, features: ["hr": 70, "rise_ms": 220], sbp: 118, dbp: 76)])
    }
}
