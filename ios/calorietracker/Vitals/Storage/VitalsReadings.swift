import Foundation

// Phase 4–6 glue over saved scans (docs/camera-vitals.md §6, §7, §7.1): reference readings, calibration pairs,
// finger / face comparison, the validation screen and dataset, the Coach lines and the Insights fallback. Pure
// functions over `VitalScanRecord`s so they are unit-tested without a database.

/// Valid metric values of a saved scan (unavailable values are nil; a rejected scan has none).
nonisolated struct VitalScanValues: Sendable {
    let record: VitalScanRecord
    let results: RJ

    init(_ record: VitalScanRecord) {
        self.record = record
        results = (try? VitalsJSON.parse(record.resultsJSON)) ?? .obj([:])
    }

    var mode: VitalsMode? { VitalsMode(rawValue: record.mode) }

    func envelope(_ id: String) -> RJ {
        if id == "recovery_indicator" || id == "stress_indicator" { return results["indicators"][id] }
        return results["metrics"][id]
    }

    func valid(_ id: String) -> Double? {
        guard record.rejectReason == nil else { return nil }
        let env = envelope(id)
        return env["status"].string == "valid" ? env["value"].double : nil
    }
}

// MARK: - Reference readings

/// User-entered readings from a reference device, stored in `vital_scans.reference_json` in the
/// `ayuvo-vitals-validation` shape: `{"heart_rate", "hrv_rmssd", "respiratory_rate", "spo2", "blood_pressure":
/// [systolic, diastolic], "device"}`. Absent fields are left out.
nonisolated struct VitalsReference: Equatable, Sendable {
    var heartRate: Double?
    var rmssd: Double?
    var respiratoryRate: Double?
    var spo2: Double?
    var systolic: Double?
    var diastolic: Double?
    var device: String?

    init(heartRate: Double? = nil, rmssd: Double? = nil, respiratoryRate: Double? = nil, spo2: Double? = nil,
         systolic: Double? = nil, diastolic: Double? = nil, device: String? = nil) {
        self.heartRate = heartRate
        self.rmssd = rmssd
        self.respiratoryRate = respiratoryRate
        self.spo2 = spo2
        self.systolic = systolic
        self.diastolic = diastolic
        self.device = device
    }

    init(json: RJ) {
        heartRate = json["heart_rate"].double
        rmssd = json["hrv_rmssd"].double
        respiratoryRate = json["respiratory_rate"].double
        spo2 = json["spo2"].double
        let bp = json["blood_pressure"].array ?? []
        systolic = bp.count == 2 ? bp[0].double : nil
        diastolic = bp.count == 2 ? bp[1].double : nil
        device = json["device"].string
    }

    init(referenceJSON: String?) {
        self.init(json: referenceJSON.flatMap { try? VitalsJSON.parse($0) } ?? .null)
    }

    var hasBloodPressure: Bool { systolic != nil && diastolic != nil }

    var isEmpty: Bool {
        heartRate == nil && rmssd == nil && respiratoryRate == nil && spo2 == nil && !hasBloodPressure
    }

    /// nil when no reading is entered (the device name alone is not a reading).
    var json: RJ? {
        guard !isEmpty else { return nil }
        var o: [String: RJ] = [:]
        if let heartRate { o["heart_rate"] = .number(heartRate) }
        if let rmssd { o["hrv_rmssd"] = .number(rmssd) }
        if let respiratoryRate { o["respiratory_rate"] = .number(respiratoryRate) }
        if let spo2 { o["spo2"] = .number(spo2) }
        if let systolic, let diastolic { o["blood_pressure"] = .arr([.number(systolic), .number(diastolic)]) }
        if let device, !device.trimmingCharacters(in: .whitespaces).isEmpty {
            o["device"] = .str(device.trimmingCharacters(in: .whitespaces))
        }
        return .obj(o)
    }

    /// The reference value paired with an engine metric (systolic for blood pressure).
    func value(for metric: String) -> Double? {
        switch metric {
        case "heart_rate": heartRate
        case "hrv_rmssd": rmssd
        case "respiratory_rate": respiratoryRate
        case "spo2": spo2
        case "blood_pressure": hasBloodPressure ? systolic : nil
        default: nil
        }
    }
}

// MARK: - Calibrations (§4, §7.1)

nonisolated enum VitalsCalibrations {
    /// The SpO₂ ratio of ratios of a finger scan, when the engine measured one.
    static func spo2Ratio(_ record: VitalScanRecord) -> Double? {
        guard record.mode == VitalsMode.finger.rawValue, record.rejectReason == nil else { return nil }
        return VitalScanValues(record).envelope("spo2")["ratio"].double
    }

    /// The pulse-morphology features (`bp_features`) of a finger scan, when the engine found them.
    static func bpFeatures(_ record: VitalScanRecord) -> RJ? {
        guard record.mode == VitalsMode.finger.rawValue, record.rejectReason == nil else { return nil }
        let features = VitalScanValues(record).envelope("blood_pressure")["features"]
        guard let o = features.object, !o.isEmpty else { return nil }
        return features
    }

    /// Offered on a finger scan with a ratio, with Experimental estimates on.
    static func canCalibrateSpo2(_ record: VitalScanRecord, experimentalEnabled: Bool) -> Bool {
        experimentalEnabled && spo2Ratio(record) != nil
    }

    /// Offered on a finger scan with morphology features, with Research estimates on.
    static func canCalibrateBp(_ record: VitalScanRecord, researchEnabled: Bool) -> Bool {
        researchEnabled && bpFeatures(record) != nil
    }

    /// Kind `spo2`: features `{"ratio": r}`, reference `{"spo2": v}`, keyed by the scan's device model.
    static func spo2(scan: VitalScanRecord, spo2: Double, id: String = VitalScanRecord.newID(), nowMs: Int64) -> VitalCalibration? {
        guard let ratio = spo2Ratio(scan) else { return nil }
        return VitalCalibration(id: id, kind: "spo2", deviceModel: scan.deviceModel, scanID: scan.id, tMs: scan.startMs,
                                referenceJSON: VitalsJSON.encode(.obj(["spo2": .number(spo2)])),
                                featuresJSON: VitalsJSON.encode(.obj(["ratio": .num(ratio)])), updatedMs: nowMs)
    }

    /// Kind `bp`: the scan's features, reference `{sbp, dbp, scan_gap_min}` (minutes between scan and cuff reading).
    static func bp(scan: VitalScanRecord, systolic: Double, diastolic: Double, scanGapMin: Double,
                   id: String = VitalScanRecord.newID(), nowMs: Int64) -> VitalCalibration? {
        guard let features = bpFeatures(scan) else { return nil }
        let reference: RJ = .obj(["sbp": .number(systolic), "dbp": .number(diastolic), "scan_gap_min": .number(scanGapMin)])
        return VitalCalibration(id: id, kind: "bp", deviceModel: scan.deviceModel, scanID: scan.id, tMs: scan.startMs,
                                referenceJSON: VitalsJSON.encode(reference), featuresJSON: VitalsJSON.encode(features),
                                updatedMs: nowMs)
    }
}

// MARK: - Compare (§6)

nonisolated enum VitalsCompare {
    /// HR, RMSSD and mean pulse interval of one scan (valid values only).
    static func side(_ record: VitalScanRecord) -> VitalsEngine.CompareSide {
        let v = VitalScanValues(record)
        return VitalsEngine.CompareSide(hr: v.valid("heart_rate"), rmssd: v.valid("hrv_rmssd"), ibiMean: v.valid("ibi_mean"))
    }

    /// `compare` on a finger and a face scan: differences and status; never a preferred result.
    static func result(finger: VitalScanRecord, face: VitalScanRecord, _ cfg: VitalsConfig = .shared) -> RJ {
        VitalsEngine.compare(finger: side(finger), face: side(face), cfg)
    }

    static func statusText(_ status: String?) -> String {
        switch status {
        case "consistent": String(localized: "The two measurements agree.")
        case "inconsistent": String(localized: "The measurements are inconsistent. Please repeat the measurement.")
        default: String(localized: "One of the scans has no value to compare.")
        }
    }

    /// The face scan joins the finger scan's session only when it starts within `compare.max_session_gap_s` of the
    /// finger scan being saved; otherwise it starts a new, unlinked session (nil).
    static func linkedSessionID(_ sessionID: String, fingerSavedAt: Date, faceStart: Date, _ cfg: VitalsConfig = .shared) -> String? {
        faceStart.timeIntervalSince(fingerSavedAt) <= cfg.compare.maxSessionGapS ? sessionID : nil
    }

    /// The finger and face scans of a session (the latest of each), when both exist.
    static func pair(_ scans: [VitalScanRecord]) -> (finger: VitalScanRecord, face: VitalScanRecord)? {
        let live = scans.filter { $0.deleted == 0 }.sorted { $0.startMs > $1.startMs }
        guard let finger = live.first(where: { $0.mode == VitalsMode.finger.rawValue }),
              let face = live.first(where: { $0.mode == VitalsMode.face.rawValue }) else { return nil }
        return (finger, face)
    }
}

// MARK: - Validation (§6, §8)

nonisolated enum VitalsValidation {
    /// Metrics on the validation screen; blood pressure compares the systolic value.
    static let metrics = ["heart_rate", "hrv_rmssd", "respiratory_rate", "spo2", "blood_pressure"]
    static let format = "ayuvo-vitals-validation"
    static let fileName = "ayuvo-vitals-validation.json"

    struct Row: Sendable {
        var mode: VitalsMode
        var metric: String
        var stats: RJ
        var id: String { "\(mode.rawValue)/\(metric)" }
    }

    /// `validation_stats` per mode × metric over scans with a reference for that metric, like `vitals_eval.py`:
    /// pairs where the scan has a valid value, failures where it doesn't. Rows without pairs or failures are left out.
    static func rows(_ scans: [VitalScanRecord], _ cfg: VitalsConfig = .shared) -> [Row] {
        var out: [Row] = []
        for mode in VitalsMode.allCases {
            for metric in metrics {
                var pairs: [VitalsEngine.ValidationPair] = []
                var failures = 0
                for scan in scans where scan.mode == mode.rawValue && scan.deleted == 0 {
                    guard let ref = VitalsReference(referenceJSON: scan.referenceJSON).value(for: metric) else { continue }
                    let env = VitalScanValues(scan).envelope(metric)
                    guard env["status"].string == "valid", let measured = env["value"].double else {
                        failures += 1
                        continue
                    }
                    pairs.append(VitalsEngine.ValidationPair(measured: measured, reference: ref, confidence: env["confidence"].double))
                }
                if !pairs.isEmpty || failures > 0 {
                    out.append(Row(mode: mode, metric: metric, stats: VitalsEngine.validationStats(pairs: pairs, failures: failures, cfg)))
                }
            }
        }
        return out
    }

    /// The `ayuvo-vitals-validation` file for `scripts/vitals_eval.py`: only scans that have a reference.
    static func dataset(_ scans: [VitalScanRecord]) -> RJ {
        var rows: [RJ] = []
        for scan in scans.sorted(by: { $0.startMs < $1.startMs }) where scan.deleted == 0 {
            guard let reference = VitalsReference(referenceJSON: scan.referenceJSON).json else { continue }
            let values = VitalScanValues(scan)
            rows.append(.obj([
                "id": .str(scan.id), "mode": .str(scan.mode), "device_model": .str(scan.deviceModel),
                "quality": RJ.f(scan.qualityScore), "reject_reason": .s(scan.rejectReason), "context": .str(scan.context),
                "start": .str(VitalsArchive.timestamp(ms: scan.startMs)), "algo_version": .int(scan.algoVersion),
                "metrics": values.results["metrics"].isNull ? .obj([:]) : values.results["metrics"],
                "reference": reference,
            ]))
        }
        return .obj(["format": .str(format), "version": .int(1), "scans": .arr(rows)])
    }

    /// Writes the dataset to a fresh temp directory (the caller deletes it after sharing).
    static func writeDataset(_ scans: [VitalScanRecord]) throws -> URL {
        let directory = FileManager.default.temporaryDirectory
            .appendingPathComponent("ayuvo-vitals-validation-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        let url = directory.appendingPathComponent(fileName)
        try Data((VitalsJSON.encode(dataset(scans)) + "\n").utf8).write(to: url, options: .atomic)
        return url
    }
}

// MARK: - Coach (Phase 6)

/// Recent camera scans for the Coach's health context: the last 7 days, at most 5 valid scans, each value with its
/// classification. SpO₂ only with Experimental estimates on, BP only with Research estimates on, always labelled.
nonisolated enum VitalsCoachSummary {
    static let maxScans = 5
    static let days = 7

    static func lines(scans: [VitalScanRecord], now: Date, experimentalEnabled: Bool, researchEnabled: Bool,
                      timeZone: TimeZone = .current) -> [String] {
        let nowMs = Int64((now.timeIntervalSince1970 * 1000).rounded())
        let fromMs = nowMs - Int64(days) * 86_400_000
        let recent = scans
            .filter { $0.deleted == 0 && $0.rejectReason == nil && $0.startMs >= fromMs && $0.startMs <= nowMs }
            .sorted { $0.startMs > $1.startMs }
        var out: [String] = []
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.timeZone = timeZone
        formatter.dateFormat = "yyyy-MM-dd HH:mm"
        for scan in recent {
            guard out.count < maxScans else { break }
            let v = VitalScanValues(scan)
            var parts: [String] = []
            func add(_ id: String, _ name: String, _ unit: String, decimals: Int = 0, label: String? = nil) {
                guard let value = v.valid(id) else { return }
                let text = decimals == 0 ? "\(Int(value.rounded()))" : String(format: "%.\(decimals)f", value)
                let cls = label ?? (v.envelope(id)["classification"].string ?? "")
                parts.append("\(name) \(text)\(unit.isEmpty ? "" : " " + unit) (\(cls))")
            }
            add("heart_rate", "HR", "bpm")
            add("hrv_rmssd", "RMSSD", "ms")
            add("respiratory_rate", "resp", "/min", decimals: 1)
            if experimentalEnabled { add("spo2", "SpO2", "%", label: "EXPERIMENTAL camera estimate, not an oxygen saturation measurement") }
            if researchEnabled, let sbp = v.valid("blood_pressure") {
                let dia = v.envelope("blood_pressure")["diastolic"].double.map { "\(Int($0.rounded()))" } ?? "?"
                parts.append("BP \(Int(sbp.rounded()))/\(dia) mmHg (RESEARCH_ONLY estimate, not a blood pressure measurement)")
            }
            guard !parts.isEmpty else { continue }
            let quality = (try? VitalsJSON.parse(scan.qualityJSON))?["grade"].string ?? "unknown"
            let mode = scan.mode == VitalsMode.finger.rawValue ? "finger scan" : "face scan"
            let when = formatter.string(from: Date(timeIntervalSince1970: Double(scan.startMs) / 1000))
            out.append("- \(when) \(mode), \(scan.context.replacingOccurrences(of: "_", with: " ")): " + parts.joined(separator: ", ")
                       + "; quality \(quality)")
        }
        guard !out.isEmpty else { return [] }
        return ["Camera scans in Ayuvo (manual phone-camera pulse measurements for general wellness; finger and face results are separate kinds of measurement and are never written to Apple Health):"]
            + out
            + ["Treat camera scans as wellness readings, never as a diagnosis; experimental and research estimates are not medical measurements."]
    }
}

// MARK: - Insights fallback (Phase 6)

nonisolated enum VitalsInsightsFallback {
    /// The fallback series and their engine inputs, in the reference's shape.
    static let series = ["resting_heart_rate", "hrv", "respiratory_rate"]

    static func fallbackScans(_ records: [VitalScanRecord]) -> [VitalsEngine.FallbackScan] {
        records.filter { $0.deleted == 0 }.map { r in
            VitalsEngine.FallbackScan(localDay: r.localDay, mode: r.mode, context: r.context, qualityScore: r.qualityScore,
                                      rejectReason: r.rejectReason, metrics: VitalScanValues(r).results["metrics"])
        }
    }

    /// Fills days without a platform value from finger scans (`insights_fallback`, the inputs' `hrv_kind`) inside
    /// `from…today` and records which (series, day) came from a scan in `inputs.scanFallback`. Platform days are
    /// never touched; face scans never count.
    static func apply(into inputs: inout InsightsInputs, scans records: [VitalScanRecord], from: String, through today: String,
                      _ cfg: VitalsConfig = .shared) {
        let scans = fallbackScans(records).filter { $0.localDay >= from && $0.localDay <= today }
        guard !scans.isEmpty else { return }
        var platformDays: [String: [String]] = [:]
        for name in series { platformDays[name] = (inputs.series[name] ?? [:]).keys.sorted() }
        let filled = VitalsEngine.insightsFallback(platformDays: platformDays, scans: scans, hrvKind: inputs.hrvKind, cfg)
        for (name, days) in filled where !days.isEmpty {
            var s = inputs.series[name] ?? [:]
            var marked = inputs.scanFallback[name] ?? []
            for (day, value) in days where s[day] == nil {
                s[day] = value
                marked.insert(day)
            }
            inputs.series[name] = s
            inputs.scanFallback[name] = marked
        }
    }
}
