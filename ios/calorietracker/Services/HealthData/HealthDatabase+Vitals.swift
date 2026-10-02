import Compression
import Foundation

// Camera-vitals storage (health schema v4, docs/camera-vitals.md): `vital_scans`, `vital_scan_signals`,
// `vital_calibrations`, `vital_device_profiles`. Scans are Ayuvo's own measurements and are never written to
// `health_samples`, HealthKit or rollups, so they cannot leak into charts or Health. Deletions are tombstones
// (deleted = 1, newer updated_ms) so a restore never resurrects them; a deleted scan's signals are removed.

/// One `vital_scans` row.
nonisolated struct VitalScanRecord: Sendable, Equatable {
    static let idPrefix = "local:"

    var id: String
    var mode: String                 // finger_ppg | face_rppg
    var sessionID: String?
    var startMs: Int64
    var endMs: Int64
    var tzOffsetS: Int
    var localDay: String
    var durationMs: Int64
    var platform: String
    var deviceModel: String
    var cameraJSON: String
    var context: String = "resting"  // resting | after_activity | other
    var qualityScore: Double?
    var rejectReason: String?
    var qualityJSON: String
    var resultsJSON: String
    var algoVersion: Int
    var referenceJSON: String?
    var deleted: Int = 0
    var updatedMs: Int64

    static func newID() -> String { idPrefix + UUID().uuidString.lowercased() }
}

/// One `vital_scan_signals` row: a float32 table (row-major, `meta_json.columns`), raw-deflate compressed.
nonisolated struct VitalSignal: Sendable, Equatable {
    var kind: String                 // frame_stats | processed | mask | beats
    var sampleRate: Double?
    var encoding: String = VitalSignalCodec.encoding
    var data: Data
    var metaJSON: String?
}

/// One `vital_calibrations` row (personal SpO2 / BP calibration pair; experimental / research).
nonisolated struct VitalCalibration: Sendable, Equatable {
    var id: String
    var kind: String                 // spo2 | bp
    var deviceModel: String
    var scanID: String?
    var tMs: Int64
    var referenceJSON: String
    var featuresJSON: String
    var deleted: Int = 0
    var updatedMs: Int64
}

/// One `vital_device_profiles` row.
nonisolated struct VitalDeviceProfile: Sendable, Equatable {
    var deviceModel: String
    var cameraPosition: String       // back | front
    var capabilityJSON: String
    var updatedMs: Int64
}

// MARK: - Signal codec

/// `f32le+deflate`: rows of floats, row-major, as float32 little-endian, compressed with RAW deflate (RFC 1951, no
/// zlib header — Apple's COMPRESSION_ZLIB; Android reads it with `Inflater(nowrap = true)`). `meta_json` is
/// `{"columns":[...],"rows":N}`.
nonisolated enum VitalSignalCodec {
    static let encoding = "f32le+deflate"

    enum CodecError: Error, Equatable { case raggedRows, unsupportedEncoding(String), badMeta, compression, sizeMismatch }

    struct Table: Sendable, Equatable {
        var columns: [String]
        var rows: [[Float]]
    }

    static func encode(columns: [String], rows: [[Float]]) throws -> (data: Data, metaJSON: String) {
        var raw = Data(capacity: rows.count * columns.count * 4)
        for row in rows {
            guard row.count == columns.count else { throw CodecError.raggedRows }
            for v in row {
                var le = v.bitPattern.littleEndian
                withUnsafeBytes(of: &le) { raw.append(contentsOf: $0) }
            }
        }
        let meta: RJ = .obj(["columns": .arr(columns.map { .str($0) }), "rows": .int(rows.count)])
        return (try deflate(raw), VitalsJSON.encode(meta))
    }

    static func signal(kind: String, sampleRate: Double?, columns: [String], rows: [[Float]]) throws -> VitalSignal {
        let (data, meta) = try encode(columns: columns, rows: rows)
        return VitalSignal(kind: kind, sampleRate: sampleRate, data: data, metaJSON: meta)
    }

    static func decode(_ signal: VitalSignal) throws -> Table {
        guard signal.encoding == encoding else { throw CodecError.unsupportedEncoding(signal.encoding) }
        return try decode(data: signal.data, metaJSON: signal.metaJSON ?? "")
    }

    static func decode(data: Data, metaJSON: String) throws -> Table {
        guard let meta = try? VitalsJSON.parse(metaJSON), let cols = meta["columns"].array,
              let rowCount = meta["rows"].double else { throw CodecError.badMeta }
        let columns = cols.compactMap(\.string)
        let n = Int(rowCount)
        let raw = try inflate(data)
        guard raw.count == n * columns.count * 4 else { throw CodecError.sizeMismatch }
        var rows: [[Float]] = []
        rows.reserveCapacity(n)
        raw.withUnsafeBytes { buf in
            var offset = 0
            for _ in 0..<n {
                var row: [Float] = []
                row.reserveCapacity(columns.count)
                for _ in 0..<columns.count {
                    let bits = UInt32(littleEndian: buf.loadUnaligned(fromByteOffset: offset, as: UInt32.self))
                    row.append(Float(bitPattern: bits))
                    offset += 4
                }
                rows.append(row)
            }
        }
        return Table(columns: columns, rows: rows)
    }

    static let fingerColumns = ["t_ms", "r", "g", "b", "r_std", "sat_frac"]
    static let faceColumns = ["t_ms", "motion", "yaw", "pitch", "luma", "face_count", "face_fraction"]

    /// Finger `frame_stats`: columns t_ms,r,g,b,r_std,sat_frac with t_ms relative to the first frame.
    static func fingerFrameStats(_ frames: [[Double]]) throws -> VitalSignal {
        let t0 = frames.first?[0] ?? 0
        let rows = frames.map { f in (0..<6).map { c in Float(c == 0 ? f[0] - t0 : f[c]) } }
        return try signal(kind: "frame_stats", sampleRate: nil, columns: fingerColumns, rows: rows)
    }

    /// Face `frame_stats`: t_ms,motion,yaw,pitch,luma,face_count,face_fraction then `<roi>_r,<roi>_g,<roi>_b,<roi>_skin`
    /// per ROI in `roiOrder` (config order) that the frames have.
    static func faceFrameStats(_ face: VitalsEngine.FaceFrames, roiOrder: [String]) throws -> VitalSignal {
        let rois = roiOrder.filter { face.rois[$0] != nil }
        var columns = faceColumns
        for roi in rois { columns += ["\(roi)_r", "\(roi)_g", "\(roi)_b", "\(roi)_skin"] }
        let t0 = face.tMs.first ?? 0
        var rows: [[Float]] = []
        rows.reserveCapacity(face.tMs.count)
        for i in 0..<face.tMs.count {
            var row: [Float] = [Float(face.tMs[i] - t0), Float(face.motion[i]), Float(face.yaw[i]), Float(face.pitch[i]),
                                Float(face.luma[i]), Float(face.faceCount[i]), Float(face.faceFraction[i])]
            for roi in rois {
                let v = face.rois[roi]![i]
                for k in 0..<4 { row.append(Float(v[k])) }
            }
            rows.append(row)
        }
        return try signal(kind: "frame_stats", sampleRate: nil, columns: columns, rows: rows)
    }

    /// The engine's `signals` (include_signals) as storable rows: processed signal (`x`), motion / artifact mask
    /// (`m`, 0/1) and beats (`t_ms`).
    static func signals(from result: VitalScanResult) throws -> [VitalSignal] {
        let s = result.signals
        guard !s.isNull else { return [] }
        let fs = s["fs"].double
        let processed = (s["processed"].array ?? []).map { [Float($0.double ?? 0)] }
        let mask = (s["mask"].array ?? []).map { [$0.bool == true ? Float(1) : Float(0)] }
        let beats = (s["peaks_ms"].array ?? []).map { [Float($0.double ?? 0)] }
        return [
            try signal(kind: "processed", sampleRate: fs, columns: ["x"], rows: processed),
            try signal(kind: "mask", sampleRate: fs, columns: ["m"], rows: mask),
            try signal(kind: "beats", sampleRate: nil, columns: ["t_ms"], rows: beats),
        ]
    }

    // MARK: Raw deflate (Apple Compression, COMPRESSION_ZLIB = RFC 1951 without header)

    static func deflate(_ input: Data) throws -> Data { try process(input, operation: COMPRESSION_STREAM_ENCODE) }

    static func inflate(_ input: Data) throws -> Data { try process(input, operation: COMPRESSION_STREAM_DECODE) }

    private static func process(_ input: Data, operation: compression_stream_operation) throws -> Data {
        let streamPointer = UnsafeMutablePointer<compression_stream>.allocate(capacity: 1)
        defer { streamPointer.deallocate() }
        guard compression_stream_init(streamPointer, operation, COMPRESSION_ZLIB) == COMPRESSION_STATUS_OK else {
            throw CodecError.compression
        }
        defer { compression_stream_destroy(streamPointer) }
        let chunk = 64 * 1024
        let outBuffer = UnsafeMutablePointer<UInt8>.allocate(capacity: chunk)
        defer { outBuffer.deallocate() }
        var output = Data()
        let source = [UInt8](input)
        return try source.withUnsafeBufferPointer { src -> Data in
            streamPointer.pointee.src_ptr = src.baseAddress ?? UnsafePointer(outBuffer)
            streamPointer.pointee.src_size = src.count
            while true {
                streamPointer.pointee.dst_ptr = outBuffer
                streamPointer.pointee.dst_size = chunk
                let status = compression_stream_process(streamPointer, Int32(COMPRESSION_STREAM_FINALIZE.rawValue))
                let produced = chunk - streamPointer.pointee.dst_size
                if produced > 0 { output.append(outBuffer, count: produced) }
                switch status {
                case COMPRESSION_STATUS_OK:
                    // A decoder that consumed everything and produced nothing would loop forever on truncated input.
                    if produced == 0 && streamPointer.pointee.src_size == 0 { throw CodecError.compression }
                    continue
                case COMPRESSION_STATUS_END:
                    return output
                default:
                    throw CodecError.compression
                }
            }
        }
    }
}

// MARK: - Repository

extension HealthDatabase {
    private nonisolated static let scanColumns = """
    id, mode, session_id, start_ms, end_ms, tz_offset_s, local_day, duration_ms, platform, device_model, camera_json, \
    context, quality_score, reject_reason, quality_json, results_json, algo_version, reference_json, deleted, updated_ms
    """

    nonisolated static func nowMs() -> Int64 { Int64((Date().timeIntervalSince1970 * 1000).rounded()) }

    nonisolated static func decodeScan(_ s: HealthDBStatement) -> VitalScanRecord {
        VitalScanRecord(
            id: s.text(0) ?? "", mode: s.text(1) ?? "", sessionID: s.text(2), startMs: s.int64(3) ?? 0, endMs: s.int64(4) ?? 0,
            tzOffsetS: s.int(5) ?? 0, localDay: s.text(6) ?? "", durationMs: s.int64(7) ?? 0, platform: s.text(8) ?? "",
            deviceModel: s.text(9) ?? "", cameraJSON: s.text(10) ?? "{}", context: s.text(11) ?? "resting",
            qualityScore: s.double(12), rejectReason: s.text(13), qualityJSON: s.text(14) ?? "{}",
            resultsJSON: s.text(15) ?? "{}", algoVersion: s.int(16) ?? 0, referenceJSON: s.text(17), deleted: s.int(18) ?? 0,
            updatedMs: s.int64(19) ?? 0)
    }

    private nonisolated static func scanValues(_ r: VitalScanRecord) -> [SQLValue] {
        [.text(r.id), .text(r.mode), .optionalText(r.sessionID), .int(r.startMs), .int(r.endMs), .int(Int64(r.tzOffsetS)),
         .text(r.localDay), .int(r.durationMs), .text(r.platform), .text(r.deviceModel), .text(r.cameraJSON), .text(r.context),
         .optionalReal(r.qualityScore), .optionalText(r.rejectReason), .text(r.qualityJSON), .text(r.resultsJSON),
         .int(Int64(r.algoVersion)), .optionalText(r.referenceJSON), .int(Int64(r.deleted)), .int(r.updatedMs)]
    }

    // MARK: Scans

    /// Saves a scan and replaces its signals in one transaction (UPDATE-then-INSERT, like every upsert in this file's
    /// schema). A tombstoned scan is never resurrected.
    func saveScan(_ record: VitalScanRecord, signals: [VitalSignal] = []) throws {
        try connection.inTransaction {
            let existingDeleted = try connection.scalarInt64("SELECT deleted FROM vital_scans WHERE id=?", [.text(record.id)])
            if existingDeleted == 1 { return }
            if existingDeleted == nil {
                try connection.run("INSERT INTO vital_scans (\(Self.scanColumns)) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                                   Self.scanValues(record))
            } else {
                var values = Self.scanValues(record)
                let id = values.removeFirst()
                try connection.run("""
                UPDATE vital_scans SET mode=?, session_id=?, start_ms=?, end_ms=?, tz_offset_s=?, local_day=?, duration_ms=?, \
                platform=?, device_model=?, camera_json=?, context=?, quality_score=?, reject_reason=?, quality_json=?, \
                results_json=?, algo_version=?, reference_json=?, deleted=?, updated_ms=? WHERE id=?
                """, values + [id])
            }
            try connection.run("DELETE FROM vital_scan_signals WHERE scan_id=?", [.text(record.id)])
            guard !signals.isEmpty else { return }
            let insert = try connection.prepare(
                "INSERT INTO vital_scan_signals (scan_id, kind, sample_rate, encoding, data, meta_json) VALUES (?,?,?,?,?,?)")
            for s in signals {
                insert.reset()
                try insert.bind([.text(record.id), .text(s.kind), .optionalReal(s.sampleRate), .text(s.encoding), .blob(s.data),
                                 .optionalText(s.metaJSON)])
                _ = try insert.step()
            }
        }
    }

    /// Scans newest first. `mode` nil = both modes; `from` inclusive / `to` exclusive bounds on start_ms.
    func scans(mode: String? = nil, from: Int64? = nil, to: Int64? = nil, includeDeleted: Bool = false,
               limit: Int? = nil) throws -> [VitalScanRecord] {
        var clauses: [String] = []
        var values: [SQLValue] = []
        if !includeDeleted { clauses.append("deleted=0") }
        if let mode { clauses.append("mode=?"); values.append(.text(mode)) }
        if let from { clauses.append("start_ms>=?"); values.append(.int(from)) }
        if let to { clauses.append("start_ms<?"); values.append(.int(to)) }
        var sql = "SELECT \(Self.scanColumns) FROM vital_scans"
        if !clauses.isEmpty { sql += " WHERE " + clauses.joined(separator: " AND ") }
        sql += " ORDER BY start_ms DESC, id DESC"
        if let limit { sql += " LIMIT \(max(0, limit))" }
        var rows: [VitalScanRecord] = []
        try connection.query(sql, values) { rows.append(Self.decodeScan($0)) }
        return rows
    }

    /// The scan with this id, tombstoned or not (check `deleted`).
    func scan(id: String) throws -> VitalScanRecord? {
        var row: VitalScanRecord?
        try connection.query("SELECT \(Self.scanColumns) FROM vital_scans WHERE id=?", [.text(id)]) { row = Self.decodeScan($0) }
        return row
    }

    /// Scans sharing a compare session, oldest first.
    func scans(sessionID: String) throws -> [VitalScanRecord] {
        var rows: [VitalScanRecord] = []
        try connection.query("SELECT \(Self.scanColumns) FROM vital_scans WHERE session_id=? AND deleted=0 ORDER BY start_ms, id",
                             [.text(sessionID)]) { rows.append(Self.decodeScan($0)) }
        return rows
    }

    func signals(scanID: String) throws -> [VitalSignal] {
        var rows: [VitalSignal] = []
        try connection.query(
            "SELECT kind, sample_rate, encoding, data, meta_json FROM vital_scan_signals WHERE scan_id=? ORDER BY kind",
            [.text(scanID)]
        ) { s in
            rows.append(VitalSignal(kind: s.text(0) ?? "", sampleRate: s.double(1), encoding: s.text(2) ?? "",
                                    data: s.blob(3) ?? Data(), metaJSON: s.text(4)))
        }
        return rows
    }

    /// Sets (or clears, with nil) the user-entered reference readings of a live scan.
    func updateReference(id: String, json: String?, nowMs: Int64 = HealthDatabase.nowMs()) throws {
        try connection.inTransaction {
            try connection.run("UPDATE vital_scans SET reference_json=?, updated_ms=? WHERE id=? AND deleted=0",
                               [.optionalText(json), .int(nowMs), .text(id)])
        }
    }

    /// Tombstones a scan, blanks its results, quality and reference readings (privacy: a tombstone keeps only what a
    /// restore needs to stay deleted) and removes its signals.
    func deleteScan(id: String, nowMs: Int64 = HealthDatabase.nowMs()) throws {
        try connection.inTransaction {
            try connection.run(
                "UPDATE vital_scans SET deleted=1, results_json='{}', quality_json='{}', reference_json=NULL, updated_ms=? WHERE id=?",
                [.int(nowMs), .text(id)])
            try connection.run("DELETE FROM vital_scan_signals WHERE scan_id=?", [.text(id)])
        }
    }

    /// Settings → Camera measurements → Delete all scans: tombstones (and blanks, like `deleteScan`) every scan and
    /// removes every signal.
    func deleteAllScans(nowMs: Int64 = HealthDatabase.nowMs()) throws {
        try connection.inTransaction {
            try connection.run(
                "UPDATE vital_scans SET deleted=1, results_json='{}', quality_json='{}', reference_json=NULL, updated_ms=? WHERE deleted=0",
                [.int(nowMs)])
            try connection.exec("DELETE FROM vital_scan_signals")
        }
    }

    func scanCount(includeDeleted: Bool = false) throws -> Int {
        Int(try connection.scalarInt64("SELECT COUNT(*) FROM vital_scans" + (includeDeleted ? "" : " WHERE deleted=0")) ?? 0)
    }

    // MARK: Calibrations

    private nonisolated static let calibrationColumns =
        "id, kind, device_model, scan_id, t_ms, reference_json, features_json, deleted, updated_ms"

    nonisolated static func decodeCalibration(_ s: HealthDBStatement) -> VitalCalibration {
        VitalCalibration(id: s.text(0) ?? "", kind: s.text(1) ?? "", deviceModel: s.text(2) ?? "", scanID: s.text(3),
                         tMs: s.int64(4) ?? 0, referenceJSON: s.text(5) ?? "{}", featuresJSON: s.text(6) ?? "{}",
                         deleted: s.int(7) ?? 0, updatedMs: s.int64(8) ?? 0)
    }

    /// Inserts or updates a calibration; a tombstoned calibration stays deleted.
    func saveCalibration(_ c: VitalCalibration) throws {
        try connection.inTransaction {
            let existingDeleted = try connection.scalarInt64("SELECT deleted FROM vital_calibrations WHERE id=?", [.text(c.id)])
            if existingDeleted == 1 { return }
            if existingDeleted == nil {
                try connection.run("INSERT INTO vital_calibrations (\(Self.calibrationColumns)) VALUES (?,?,?,?,?,?,?,?,?)", [
                    .text(c.id), .text(c.kind), .text(c.deviceModel), .optionalText(c.scanID), .int(c.tMs), .text(c.referenceJSON),
                    .text(c.featuresJSON), .int(Int64(c.deleted)), .int(c.updatedMs),
                ])
            } else {
                try connection.run("""
                UPDATE vital_calibrations SET kind=?, device_model=?, scan_id=?, t_ms=?, reference_json=?, features_json=?, \
                deleted=?, updated_ms=? WHERE id=?
                """, [.text(c.kind), .text(c.deviceModel), .optionalText(c.scanID), .int(c.tMs), .text(c.referenceJSON),
                      .text(c.featuresJSON), .int(Int64(c.deleted)), .int(c.updatedMs), .text(c.id)])
            }
        }
    }

    /// Live calibrations, oldest first. nil filters match everything.
    func calibrations(kind: String? = nil, deviceModel: String? = nil, includeDeleted: Bool = false) throws -> [VitalCalibration] {
        var clauses: [String] = []
        var values: [SQLValue] = []
        if !includeDeleted { clauses.append("deleted=0") }
        if let kind { clauses.append("kind=?"); values.append(.text(kind)) }
        if let deviceModel { clauses.append("device_model=?"); values.append(.text(deviceModel)) }
        var sql = "SELECT \(Self.calibrationColumns) FROM vital_calibrations"
        if !clauses.isEmpty { sql += " WHERE " + clauses.joined(separator: " AND ") }
        sql += " ORDER BY t_ms, id"
        var rows: [VitalCalibration] = []
        try connection.query(sql, values) { rows.append(Self.decodeCalibration($0)) }
        return rows
    }

    func deleteCalibration(id: String, nowMs: Int64 = HealthDatabase.nowMs()) throws {
        try connection.inTransaction {
            try connection.run("UPDATE vital_calibrations SET deleted=1, updated_ms=? WHERE id=?", [.int(nowMs), .text(id)])
        }
    }

    // MARK: Device profiles

    func upsertDeviceProfile(_ p: VitalDeviceProfile) throws {
        try connection.inTransaction {
            let key: [SQLValue] = [.text(p.deviceModel), .text(p.cameraPosition)]
            let exists = try connection.scalarInt64(
                "SELECT COUNT(*) FROM vital_device_profiles WHERE device_model=? AND camera_position=?", key) ?? 0
            if exists > 0 {
                try connection.run("UPDATE vital_device_profiles SET capability_json=?, updated_ms=? WHERE device_model=? AND camera_position=?",
                                   [.text(p.capabilityJSON), .int(p.updatedMs)] + key)
            } else {
                try connection.run("INSERT INTO vital_device_profiles (device_model, camera_position, capability_json, updated_ms) VALUES (?,?,?,?)",
                                   key + [.text(p.capabilityJSON), .int(p.updatedMs)])
            }
        }
    }

    /// Every device profile, by model then camera position.
    func deviceProfiles() throws -> [VitalDeviceProfile] {
        var rows: [VitalDeviceProfile] = []
        try connection.query(
            "SELECT device_model, camera_position, capability_json, updated_ms FROM vital_device_profiles ORDER BY device_model, camera_position"
        ) { s in
            rows.append(VitalDeviceProfile(deviceModel: s.text(0) ?? "", cameraPosition: s.text(1) ?? "",
                                           capabilityJSON: s.text(2) ?? "{}", updatedMs: s.int64(3) ?? 0))
        }
        return rows
    }

    func deviceProfile(model: String, position: String) throws -> VitalDeviceProfile? {
        var row: VitalDeviceProfile?
        try connection.query(
            "SELECT device_model, camera_position, capability_json, updated_ms FROM vital_device_profiles WHERE device_model=? AND camera_position=?",
            [.text(model), .text(position)]
        ) { s in
            row = VitalDeviceProfile(deviceModel: s.text(0) ?? "", cameraPosition: s.text(1) ?? "",
                                     capabilityJSON: s.text(2) ?? "{}", updatedMs: s.int64(3) ?? 0)
        }
        return row
    }
}
