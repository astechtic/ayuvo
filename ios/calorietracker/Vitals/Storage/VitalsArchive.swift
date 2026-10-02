import Foundation

/// Export All Data section `camera_vitals` (docs/camera-vitals.md §7.2): `camera-vitals/manifest.json` plus
/// `scans.ndjson`, `signals.ndjson`, `calibrations.ndjson` and `device_profiles.ndjson`. Rows use the column names,
/// with the `*_json` columns embedded as objects and times as ISO-8601 UTC with milliseconds. Imported after
/// `health_data`, from either platform: insert by id (an id already here, live or tombstoned, is skipped together
/// with its signals), device profiles merge by key with the newer `updated` winning, and nothing goes to Health.
nonisolated enum VitalsArchive {
    static let sectionID = "camera_vitals"
    static let format = "ayuvo-camera-vitals"
    static let formatVersion = 1
    static let directory = "camera-vitals/"
    static let manifestEntry = directory + "manifest.json"
    static let scansEntry = directory + "scans.ndjson"
    static let signalsEntry = directory + "signals.ndjson"
    static let calibrationsEntry = directory + "calibrations.ndjson"
    static let deviceProfilesEntry = directory + "device_profiles.ndjson"
    static let dataEntries = [scansEntry, signalsEntry, calibrationsEntry, deviceProfilesEntry]

    enum ArchiveError: LocalizedError, Equatable {
        case notCameraVitals
        case newerVersion

        var errorDescription: String? {
            switch self {
            case .notCameraVitals: String(localized: "The camera measurements in this export couldn't be read.")
            case .newerVersion: String(localized: "Made by a newer version of Ayuvo.")
            }
        }
    }

    // MARK: Times

    static func timestamp(ms: Int64) -> String {
        let formatter = ISO8601DateFormatter()
        formatter.timeZone = TimeZone(identifier: "UTC")
        formatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        return formatter.string(from: Date(timeIntervalSince1970: Double(ms) / 1000))
    }

    static func ms(_ text: String?) -> Int64? {
        guard let text else { return nil }
        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        if let d = formatter.date(from: text) { return Int64((d.timeIntervalSince1970 * 1000).rounded()) }
        formatter.formatOptions = [.withInternetDateTime]
        return formatter.date(from: text).map { Int64(($0.timeIntervalSince1970 * 1000).rounded()) }
    }

    private static func object(_ text: String?) -> RJ {
        guard let text, let v = try? VitalsJSON.parse(text), v.object != nil else { return .obj([:]) }
        return v
    }

    // MARK: Rows

    static func scanRow(_ r: VitalScanRecord) -> RJ {
        .obj([
            "id": .str(r.id), "mode": .str(r.mode), "session_id": .s(r.sessionID), "start": .str(timestamp(ms: r.startMs)),
            "end": .str(timestamp(ms: r.endMs)), "tz_offset_s": .int(r.tzOffsetS), "local_day": .str(r.localDay),
            "duration_ms": .int(Int(r.durationMs)), "platform": .str(r.platform), "device_model": .str(r.deviceModel),
            "camera": object(r.cameraJSON), "context": .str(r.context), "quality_score": RJ.f(r.qualityScore),
            "reject_reason": .s(r.rejectReason), "quality": object(r.qualityJSON), "results": object(r.resultsJSON),
            "algo_version": .int(r.algoVersion),
            "reference": r.referenceJSON.flatMap { try? VitalsJSON.parse($0) } ?? .null,
            "updated": .str(timestamp(ms: r.updatedMs)),
        ])
    }

    static func scanRecord(_ row: RJ) -> VitalScanRecord? {
        guard let id = row["id"].string, !id.isEmpty, let mode = row["mode"].string, VitalsMode(rawValue: mode) != nil,
              let start = ms(row["start"].string) else { return nil }
        let end = ms(row["end"].string) ?? start
        let reference = row["reference"]
        return VitalScanRecord(
            id: id, mode: mode, sessionID: row["session_id"].string, startMs: start, endMs: end,
            tzOffsetS: Int(row["tz_offset_s"].double ?? 0), localDay: row["local_day"].string ?? "",
            durationMs: Int64(row["duration_ms"].double ?? Double(end - start)), platform: row["platform"].string ?? "",
            deviceModel: row["device_model"].string ?? "", cameraJSON: VitalsJSON.encode(row["camera"].object == nil ? .obj([:]) : row["camera"]),
            context: row["context"].string ?? "resting", qualityScore: row["quality_score"].double,
            rejectReason: row["reject_reason"].string,
            qualityJSON: VitalsJSON.encode(row["quality"].object == nil ? .obj([:]) : row["quality"]),
            resultsJSON: VitalsJSON.encode(row["results"].object == nil ? .obj([:]) : row["results"]),
            algoVersion: Int(row["algo_version"].double ?? 0),
            referenceJSON: reference.object == nil ? nil : VitalsJSON.encode(reference), deleted: 0,
            updatedMs: ms(row["updated"].string) ?? start)
    }

    static func signalRow(scanID: String, _ s: VitalSignal) -> RJ {
        .obj(["scan_id": .str(scanID), "kind": .str(s.kind), "sample_rate": sampleRateJSON(s.sampleRate), "encoding": .str(s.encoding),
              "meta": object(s.metaJSON), "data_base64": .str(s.data.base64EncodedString())])
    }

    /// Whole-number rates as ints (30, like the fixture), others as floats.
    private static func sampleRateJSON(_ rate: Double?) -> RJ {
        guard let rate else { return .null }
        return rate.rounded() == rate && abs(rate) < 1e9 ? .int(Int(rate)) : .num(rate)
    }

    static func signal(_ row: RJ) -> (scanID: String, signal: VitalSignal)? {
        guard let scanID = row["scan_id"].string, let kind = row["kind"].string,
              let b64 = row["data_base64"].string, let data = Data(base64Encoded: b64) else { return nil }
        let meta = row["meta"]
        return (scanID, VitalSignal(kind: kind, sampleRate: row["sample_rate"].double, encoding: row["encoding"].string ?? VitalSignalCodec.encoding,
                                    data: data, metaJSON: meta.object == nil ? nil : VitalsJSON.encode(meta)))
    }

    static func calibrationRow(_ c: VitalCalibration) -> RJ {
        .obj(["id": .str(c.id), "kind": .str(c.kind), "device_model": .str(c.deviceModel), "scan_id": .s(c.scanID),
              "t": .str(timestamp(ms: c.tMs)), "reference": object(c.referenceJSON), "features": object(c.featuresJSON),
              "updated": .str(timestamp(ms: c.updatedMs))])
    }

    static func calibration(_ row: RJ) -> VitalCalibration? {
        guard let id = row["id"].string, !id.isEmpty, let kind = row["kind"].string, kind == "spo2" || kind == "bp",
              let t = ms(row["t"].string) else { return nil }
        return VitalCalibration(id: id, kind: kind, deviceModel: row["device_model"].string ?? "", scanID: row["scan_id"].string, tMs: t,
                                referenceJSON: VitalsJSON.encode(row["reference"].object == nil ? .obj([:]) : row["reference"]),
                                featuresJSON: VitalsJSON.encode(row["features"].object == nil ? .obj([:]) : row["features"]),
                                deleted: 0, updatedMs: ms(row["updated"].string) ?? t)
    }

    static func deviceProfileRow(_ p: VitalDeviceProfile) -> RJ {
        .obj(["device_model": .str(p.deviceModel), "camera_position": .str(p.cameraPosition),
              "capability": object(p.capabilityJSON), "updated": .str(timestamp(ms: p.updatedMs))])
    }

    static func deviceProfile(_ row: RJ) -> VitalDeviceProfile? {
        guard let model = row["device_model"].string, !model.isEmpty, let position = row["camera_position"].string,
              let updated = ms(row["updated"].string) else { return nil }
        return VitalDeviceProfile(deviceModel: model, cameraPosition: position,
                                  capabilityJSON: VitalsJSON.encode(row["capability"].object == nil ? .obj([:]) : row["capability"]),
                                  updatedMs: updated)
    }

    static func ndjson(_ rows: [RJ]) -> Data {
        Data(rows.map { VitalsJSON.encode($0) + "\n" }.joined().utf8)
    }

    static func lines(_ data: Data?) -> [RJ?] {
        guard let data else { return [] }
        return String(decoding: data, as: UTF8.self).split(whereSeparator: \.isNewline)
            .map { $0.trimmingCharacters(in: .whitespaces) }
            .filter { !$0.isEmpty }
            .map { try? VitalsJSON.parse($0) }
    }

    // MARK: Export

    struct Export: Sendable {
        /// Entry name → bytes, manifest first.
        var entries: [(name: String, data: Data)]
        var counts: [String: Int]
        var isEmpty: Bool { (counts["scans"] ?? 0) == 0 && (counts["calibrations"] ?? 0) == 0 }
    }

    /// Live scans (with their signals when `keepSignals`), live calibrations and every device profile.
    static func export(from db: HealthDatabase, keepSignals: Bool) async throws -> Export {
        let scans = try await db.scans().sorted { ($0.startMs, $0.id) < ($1.startMs, $1.id) }
        var signalRows: [RJ] = []
        if keepSignals {
            for scan in scans {
                for s in try await db.signals(scanID: scan.id) { signalRows.append(signalRow(scanID: scan.id, s)) }
            }
        }
        let calibrations = try await db.calibrations()
        let profiles = try await db.deviceProfiles()
        let counts = ["scans": scans.count, "signals": signalRows.count, "calibrations": calibrations.count,
                      "device_profiles": profiles.count]
        let manifest: RJ = .obj(["format": .str(format), "format_version": .int(formatVersion),
                                 "counts": .obj(counts.mapValues { .int($0) })])
        return Export(entries: [
            (manifestEntry, Data((VitalsJSON.encode(manifest) + "\n").utf8)),
            (scansEntry, ndjson(scans.map(scanRow))),
            (signalsEntry, ndjson(signalRows)),
            (calibrationsEntry, ndjson(calibrations.map(calibrationRow))),
            (deviceProfilesEntry, ndjson(profiles.map(deviceProfileRow))),
        ], counts: counts)
    }

    // MARK: Import

    struct ImportResult: Equatable, Sendable {
        var scansAdded = 0
        var scansSkipped = 0
        var signalsAdded = 0
        var calibrationsAdded = 0
        var calibrationsSkipped = 0
        var deviceProfilesUpdated = 0
        var invalidRows = 0
    }

    /// Imports the section from its entries (names as in the zip; a missing data file counts as empty).
    static func importEntries(_ entries: [String: Data], into db: HealthDatabase) async throws -> ImportResult {
        if let manifestData = entries[manifestEntry] {
            guard let manifest = try? VitalsJSON.parse(manifestData), manifest["format"].string == format else {
                throw ArchiveError.notCameraVitals
            }
            if let version = manifest["format_version"].double, Int(version) > formatVersion { throw ArchiveError.newerVersion }
        }
        var result = ImportResult()
        var signalsByScan: [String: [VitalSignal]] = [:]
        for line in lines(entries[signalsEntry]) {
            guard let line, let s = signal(line) else { result.invalidRows += 1; continue }
            signalsByScan[s.scanID, default: []].append(s.signal)
        }
        for line in lines(entries[scansEntry]) {
            guard let line, let record = scanRecord(line) else { result.invalidRows += 1; continue }
            if try await db.scan(id: record.id) != nil {
                result.scansSkipped += 1
                continue
            }
            let signals = signalsByScan[record.id] ?? []
            try await db.saveScan(record, signals: signals)
            result.scansAdded += 1
            result.signalsAdded += signals.count
        }
        let knownCalibrations = Set(try await db.calibrations(includeDeleted: true).map(\.id))
        for line in lines(entries[calibrationsEntry]) {
            guard let line, let c = calibration(line) else { result.invalidRows += 1; continue }
            if knownCalibrations.contains(c.id) {
                result.calibrationsSkipped += 1
                continue
            }
            try await db.saveCalibration(c)
            result.calibrationsAdded += 1
        }
        for line in lines(entries[deviceProfilesEntry]) {
            guard let line, let p = deviceProfile(line) else { result.invalidRows += 1; continue }
            if let local = try await db.deviceProfile(model: p.deviceModel, position: p.cameraPosition), local.updatedMs >= p.updatedMs {
                continue
            }
            try await db.upsertDeviceProfile(p)
            result.deviceProfilesUpdated += 1
        }
        return result
    }
}
