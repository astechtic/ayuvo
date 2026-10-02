import Compression
import Foundation
import Testing
@testable import calorietracker

/// Camera-vitals storage (health schema v4): scans, signals, calibrations, device profiles and the
/// `f32le+deflate` signal codec. Scans never touch `health_samples`.
struct VitalScanStorageTests {
    static func record(id: String = VitalScanRecord.newID(), mode: String = "finger_ppg", startMs: Int64,
                       session: String? = nil, updatedMs: Int64 = 1_000) -> VitalScanRecord {
        VitalScanRecord(id: id, mode: mode, sessionID: session, startMs: startMs, endMs: startMs + 60_000, tzOffsetS: 19_800,
                        localDay: "2026-10-02", durationMs: 60_000, platform: "ios", deviceModel: "iPhone15,2",
                        cameraJSON: #"{"position":"back","torch":true}"#, context: "resting", qualityScore: 88.5,
                        rejectReason: nil, qualityJSON: #"{"score":88.5}"#, resultsJSON: #"{"metrics":{}}"#, algoVersion: 1,
                        referenceJSON: nil, deleted: 0, updatedMs: updatedMs)
    }

    @Test func saveListAndFilterNewestFirst() async throws {
        let db = try await HealthDatabase.inMemory()
        try await db.saveScan(Self.record(id: "local:a", startMs: 1_000))
        try await db.saveScan(Self.record(id: "local:b", mode: "face_rppg", startMs: 3_000))
        try await db.saveScan(Self.record(id: "local:c", startMs: 2_000))
        #expect(try await db.scans().map(\.id) == ["local:b", "local:c", "local:a"])
        #expect(try await db.scans(mode: "finger_ppg").map(\.id) == ["local:c", "local:a"])
        #expect(try await db.scans(from: 2_000, to: 3_000).map(\.id) == ["local:c"])
        #expect(try await db.scans(limit: 1).map(\.id) == ["local:b"])
        let stored = try #require(try await db.scan(id: "local:a"))
        #expect(stored == Self.record(id: "local:a", startMs: 1_000))
        #expect(try await db.scanCount() == 3)
        // Scans are never health samples.
        #expect(try await db.sampleCount() == 0)
    }

    @Test func saveReplacesFieldsAndSignalsInOneTransaction() async throws {
        let db = try await HealthDatabase.inMemory()
        var r = Self.record(id: "local:x", startMs: 5_000)
        let s1 = try VitalSignalCodec.signal(kind: "processed", sampleRate: 30, columns: ["x"], rows: [[1], [2], [3]])
        let s2 = try VitalSignalCodec.signal(kind: "beats", sampleRate: nil, columns: ["t_ms"], rows: [[100], [900]])
        try await db.saveScan(r, signals: [s1, s2])
        #expect(try await db.signals(scanID: "local:x").map(\.kind) == ["beats", "processed"])
        r.context = "after_activity"
        r.qualityScore = nil
        r.updatedMs = 2_000
        try await db.saveScan(r, signals: [s1])
        let stored = try #require(try await db.scan(id: "local:x"))
        #expect(stored.context == "after_activity" && stored.qualityScore == nil && stored.updatedMs == 2_000)
        let signals = try await db.signals(scanID: "local:x")
        #expect(signals == [s1])
        #expect(try VitalSignalCodec.decode(signals[0]).rows == [[1], [2], [3]])
    }

    @Test func deleteTombstonesAndDropsSignals() async throws {
        let db = try await HealthDatabase.inMemory()
        let signal = try VitalSignalCodec.signal(kind: "mask", sampleRate: 30, columns: ["m"], rows: [[0], [1]])
        var withReference = Self.record(id: "local:d", startMs: 1_000)
        withReference.referenceJSON = #"{"hr":61}"#
        try await db.saveScan(withReference, signals: [signal])
        try await db.saveScan(Self.record(id: "local:e", startMs: 2_000), signals: [signal])
        try await db.deleteScan(id: "local:d", nowMs: 9_000)
        let tomb = try #require(try await db.scan(id: "local:d"))
        #expect(tomb.deleted == 1 && tomb.updatedMs == 9_000)
        // Privacy: the tombstone keeps no results, quality or reference readings.
        #expect(tomb.resultsJSON == "{}" && tomb.qualityJSON == "{}" && tomb.referenceJSON == nil)
        #expect(tomb.mode == "finger_ppg" && tomb.startMs == 1_000)
        #expect(try await db.signals(scanID: "local:d").isEmpty)
        #expect(try await db.scans().map(\.id) == ["local:e"])
        #expect(try await db.scans(includeDeleted: true).count == 2)
        // A tombstone is never resurrected by a later save (e.g. a restore).
        try await db.saveScan(Self.record(id: "local:d", startMs: 1_000, updatedMs: 99_000), signals: [signal])
        #expect(try await db.scan(id: "local:d")?.deleted == 1)
        #expect(try await db.signals(scanID: "local:d").isEmpty)
        // Reference readings can't be attached to a deleted scan.
        try await db.updateReference(id: "local:d", json: #"{"hr":60}"#, nowMs: 10_000)
        #expect(try await db.scan(id: "local:d")?.referenceJSON == nil)

        try await db.deleteAllScans(nowMs: 20_000)
        #expect(try await db.scans().isEmpty)
        #expect(try await db.scanCount(includeDeleted: true) == 2)
        #expect(try await db.signals(scanID: "local:e").isEmpty)
        #expect(try await db.scan(id: "local:e")?.updatedMs == 20_000)
        #expect(try await db.scan(id: "local:e")?.resultsJSON == "{}")
    }

    @Test func referenceReadingsAndSessions() async throws {
        let db = try await HealthDatabase.inMemory()
        try await db.saveScan(Self.record(id: "local:f", startMs: 1_000, session: "s1"))
        try await db.saveScan(Self.record(id: "local:g", mode: "face_rppg", startMs: 70_000, session: "s1"))
        try await db.updateReference(id: "local:f", json: #"{"hr":64,"source":"chest_strap"}"#, nowMs: 5_000)
        let f = try #require(try await db.scan(id: "local:f"))
        #expect(f.referenceJSON == #"{"hr":64,"source":"chest_strap"}"# && f.updatedMs == 5_000)
        #expect(try await db.scans(sessionID: "s1").map(\.id) == ["local:f", "local:g"])
    }

    @Test func codecRoundTripsRawDeflate() throws {
        let rows: [[Float]] = (0..<500).map { i in [Float(i) * 33.3, Float(sin(Double(i) / 7)), -Float(i), .leastNonzeroMagnitude] }
        let (data, meta) = try VitalSignalCodec.encode(columns: ["t_ms", "r", "g", "b"], rows: rows)
        #expect(meta == #"{"columns":["t_ms","r","g","b"],"rows":500}"#)
        let table = try VitalSignalCodec.decode(data: data, metaJSON: meta)
        #expect(table.columns == ["t_ms", "r", "g", "b"])
        #expect(table.rows == rows)
        // Raw deflate: no zlib header (0x78 ..), and the stored bytes are float32 little-endian.
        #expect(data.first != 0x78)
        let raw = try VitalSignalCodec.inflate(data)
        #expect(raw.count == 500 * 4 * 4)
        #expect(Array(raw[4..<8]) == [0, 0, 0, 0])            // row 0, column r: sin(0) = 0
        let bits = Float(33.3).bitPattern                         // row 1, column t_ms: 33.3f, little-endian
        #expect(Array(raw[16..<20]) == [UInt8(bits & 0xFF), UInt8((bits >> 8) & 0xFF), UInt8((bits >> 16) & 0xFF), UInt8(bits >> 24)])
        // Empty tables and decoding errors.
        let empty = try VitalSignalCodec.encode(columns: ["v"], rows: [])
        #expect(try VitalSignalCodec.decode(data: empty.data, metaJSON: empty.metaJSON).rows.isEmpty)
        #expect(throws: VitalSignalCodec.CodecError.raggedRows) { try VitalSignalCodec.encode(columns: ["a", "b"], rows: [[1]]) }
        #expect(throws: VitalSignalCodec.CodecError.sizeMismatch) {
            try VitalSignalCodec.decode(data: data, metaJSON: #"{"columns":["t_ms"],"rows":500}"#)
        }
        var other = try VitalSignalCodec.signal(kind: "x", sampleRate: nil, columns: ["v"], rows: [[1]])
        other.encoding = "f32le"
        #expect(throws: VitalSignalCodec.CodecError.unsupportedEncoding("f32le")) { try VitalSignalCodec.decode(other) }
    }

    @Test func codecReadsIndependentRawDeflate() throws {
        // Bytes compressed by a different encoder call path (compression_encode_buffer, the same raw RFC 1951 format
        // Android's Deflater(nowrap = true) writes) decode through the codec.
        var floats: [Float] = [1.5, -2.25, 1000]
        let raw = Data(bytes: &floats, count: 12)
        var dst = [UInt8](repeating: 0, count: 256)
        let n = raw.withUnsafeBytes { src in
            compression_encode_buffer(&dst, dst.count, src.bindMemory(to: UInt8.self).baseAddress!, raw.count, nil, COMPRESSION_ZLIB)
        }
        #expect(n > 0)
        let table = try VitalSignalCodec.decode(data: Data(dst[0..<n]), metaJSON: #"{"columns":["v"],"rows":3}"#)
        #expect(table.rows == [[1.5], [-2.25], [1000]])
    }

    @Test func calibrationsFilterAndTombstone() async throws {
        let db = try await HealthDatabase.inMemory()
        func cal(_ id: String, _ kind: String, _ model: String, _ t: Int64) -> VitalCalibration {
            VitalCalibration(id: id, kind: kind, deviceModel: model, scanID: "local:s", tMs: t,
                             referenceJSON: kind == "spo2" ? #"{"spo2":98}"# : #"{"sbp":118,"dbp":76,"scan_gap_min":2}"#,
                             featuresJSON: kind == "spo2" ? #"{"ratio":0.66}"# : #"{"hr":70.0}"#, updatedMs: t)
        }
        try await db.saveCalibration(cal("c2", "spo2", "iPhone15,2", 2_000))
        try await db.saveCalibration(cal("c1", "spo2", "iPhone15,2", 1_000))
        try await db.saveCalibration(cal("c3", "spo2", "Pixel 7", 3_000))
        try await db.saveCalibration(cal("c4", "bp", "iPhone15,2", 4_000))
        #expect(try await db.calibrations(kind: "spo2", deviceModel: "iPhone15,2").map(\.id) == ["c1", "c2"])
        #expect(try await db.calibrations(kind: "bp").map(\.id) == ["c4"])
        #expect(try await db.calibrations().count == 4)
        var edited = cal("c1", "spo2", "iPhone15,2", 1_000)
        edited.referenceJSON = #"{"spo2":97}"#
        try await db.saveCalibration(edited)
        #expect(try await db.calibrations(kind: "spo2", deviceModel: "iPhone15,2").first?.referenceJSON == #"{"spo2":97}"#)
        try await db.deleteCalibration(id: "c2", nowMs: 9_000)
        #expect(try await db.calibrations(kind: "spo2", deviceModel: "iPhone15,2").map(\.id) == ["c1"])
        let tomb = try #require(try await db.calibrations(includeDeleted: true).first { $0.id == "c2" })
        #expect(tomb.deleted == 1 && tomb.updatedMs == 9_000)
        try await db.saveCalibration(cal("c2", "spo2", "iPhone15,2", 2_000))
        #expect(try await db.calibrations(kind: "spo2", deviceModel: "iPhone15,2").map(\.id) == ["c1"])
    }

    @Test func deviceProfileUpsert() async throws {
        let db = try await HealthDatabase.inMemory()
        #expect(try await db.deviceProfile(model: "iPhone15,2", position: "back") == nil)
        try await db.upsertDeviceProfile(VitalDeviceProfile(deviceModel: "iPhone15,2", cameraPosition: "back",
                                                            capabilityJSON: #"{"max_fps":60}"#, updatedMs: 1))
        try await db.upsertDeviceProfile(VitalDeviceProfile(deviceModel: "iPhone15,2", cameraPosition: "front",
                                                            capabilityJSON: #"{"max_fps":30}"#, updatedMs: 1))
        try await db.upsertDeviceProfile(VitalDeviceProfile(deviceModel: "iPhone15,2", cameraPosition: "back",
                                                            capabilityJSON: #"{"max_fps":120}"#, updatedMs: 2))
        let back = try #require(try await db.deviceProfile(model: "iPhone15,2", position: "back"))
        #expect(back.capabilityJSON == #"{"max_fps":120}"# && back.updatedMs == 2)
        #expect(try await db.deviceProfile(model: "iPhone15,2", position: "front")?.capabilityJSON == #"{"max_fps":30}"#)
    }

    @Test func engineResultStoresAndReloads() async throws {
        let db = try await HealthDatabase.inMemory()
        let spec = VitalsSynth.Spec(json: try VitalsJSON.parse(
            #"{"ac": 0.02, "duration_s": 35, "fs": 30, "hr_bpm": 66, "jitter_ms": 3, "noise": 0.0005, "resp_bpm": 12, "rsa_ms": 20, "seed": 3}"#))
        let result = VitalsEngine.analyzeFinger(.init(frames: VitalsSynth.synthFinger(spec), includeSignals: true), VitalsConfig.shared)
        var r = Self.record(id: "local:engine", startMs: 1_000)
        r.qualityScore = result.qualityScore
        r.qualityJSON = result.qualityJSONText
        r.resultsJSON = result.resultsJSONText
        r.rejectReason = result.rejectReason
        try await db.saveScan(r, signals: try VitalSignalCodec.signals(from: result))
        let stored = try #require(try await db.scan(id: "local:engine"))
        let reloaded = try VitalsJSON.parse(stored.resultsJSON)
        #expect(reloaded["metrics"]["heart_rate"]["value"].double == result.metric("heart_rate").value)
        let processed = try #require(try await db.signals(scanID: "local:engine").first { $0.kind == "processed" })
        let table = try VitalSignalCodec.decode(processed)
        let original = result.signals["processed"].array?.compactMap(\.double) ?? []
        #expect(table.rows.map { Double($0[0]) } == original.map { Double(Float($0)) })
        #expect(try await db.sampleCount() == 0)
    }
}
