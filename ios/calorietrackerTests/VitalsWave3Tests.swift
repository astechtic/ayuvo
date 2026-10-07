import Foundation
import Testing
@testable import calorietracker

/// Camera vitals Wave 3 (docs/camera-vitals.md §6, §7, §7.1, §7.2): compare, reference readings, calibrations,
/// validation, Coach lines, the Insights scan fallback, export / restore and the portable preferences.
struct VitalsWave3Tests {
    // MARK: Fixtures

    static let fingerResult: VitalScanResult = {
        let spec = VitalsSynth.Spec(json: try! VitalsJSON.parse(
            #"{"ac": 0.02, "duration_s": 62, "fs": 30, "hr_bpm": 68, "jitter_ms": 3, "noise": 0.0005, "resp_bpm": 14, "rsa_ms": 30, "seed": 5}"#))
        return VitalsEngine.analyzeFinger(.init(frames: VitalsSynth.synthFinger(spec), experimentalEnabled: true, researchEnabled: true),
                                          VitalsConfig.shared)
    }()

    static func metric(_ value: Double?, classification: String = "measured", confidence: Double? = 0.9,
                       extra: [String: RJ] = [:]) -> RJ {
        var o: [String: RJ] = ["value": RJ.f(value), "status": .str(value == nil ? "unavailable" : "valid"),
                               "classification": .str(classification), "confidence": RJ.f(value == nil ? nil : confidence),
                               "reason": value == nil ? .str("low_quality") : .null]
        for (k, v) in extra { o[k] = v }
        return .obj(o)
    }

    static func record(id: String = VitalScanRecord.newID(), mode: VitalsMode = .finger, day: String = "2026-10-01",
                       startMs: Int64 = 1_790_000_000_000, context: String = "resting", quality: Double? = 85,
                       reject: String? = nil, session: String? = nil, metrics: [String: RJ], reference: RJ? = nil) -> VitalScanRecord {
        VitalScanRecord(id: id, mode: mode.rawValue, sessionID: session, startMs: startMs, endMs: startMs + 60_000, tzOffsetS: 0,
                        localDay: day, durationMs: 60_000, platform: "ios", deviceModel: "iPhone15,2", cameraJSON: "{}", context: context,
                        qualityScore: quality, rejectReason: reject, qualityJSON: #"{"grade":"good","score":85.0}"#,
                        resultsJSON: VitalsJSON.encode(.obj(["metrics": .obj(metrics), "reject_reason": .s(reject)])), algoVersion: 1,
                        referenceJSON: reference.map(VitalsJSON.encode), updatedMs: startMs)
    }

    static func engineRecord(id: String = "local:engine", mode: VitalsMode = .finger) -> VitalScanRecord {
        let r = fingerResult
        return VitalScanRecord(id: id, mode: mode.rawValue, sessionID: nil, startMs: 1_790_000_000_000, endMs: 1_790_000_062_000,
                               tzOffsetS: 0, localDay: "2026-09-21", durationMs: 62_000, platform: "ios", deviceModel: "iPhone15,2",
                               cameraJSON: "{}", context: "resting", qualityScore: r.qualityScore, rejectReason: r.rejectReason,
                               qualityJSON: r.qualityJSONText, resultsJSON: r.resultsJSONText, algoVersion: 1, referenceJSON: nil,
                               updatedMs: 1_790_000_100_000)
    }

    // MARK: Compare

    @Test func compareLinksOnlyWithinTheSessionGapAndNeverPicksAWinner() throws {
        let saved = Date(timeIntervalSince1970: 1_000)
        #expect(VitalsCompare.linkedSessionID("s", fingerSavedAt: saved, faceStart: saved.addingTimeInterval(179)) == "s")
        #expect(VitalsCompare.linkedSessionID("s", fingerSavedAt: saved, faceStart: saved.addingTimeInterval(180)) == "s")
        #expect(VitalsCompare.linkedSessionID("s", fingerSavedAt: saved, faceStart: saved.addingTimeInterval(181)) == nil)

        let finger = Self.record(id: "local:f", mode: .finger, session: "s",
                                 metrics: ["heart_rate": Self.metric(64), "hrv_rmssd": Self.metric(40), "ibi_mean": Self.metric(937.5)])
        let face = Self.record(id: "local:g", mode: .face, startMs: 1_790_000_070_000, session: "s",
                               metrics: ["heart_rate": Self.metric(66.04), "hrv_rmssd": Self.metric(58), "ibi_mean": Self.metric(909)])
        let pair = try #require(VitalsCompare.pair([face, finger]))
        #expect(pair.finger.id == "local:f" && pair.face.id == "local:g")
        let result = VitalsCompare.result(finger: finger, face: face)
        #expect(result["hr_diff"].double == 2.0)
        #expect(result["rmssd_diff"].double == 18.0)
        #expect(result["ibi_mean_diff"].double == 28.5)
        #expect(result["status"].string == "inconsistent")
        #expect(VitalsCompare.statusText("inconsistent") == "The measurements are inconsistent. Please repeat the measurement.")
        #expect(VitalsCompare.statusText("consistent") == "The two measurements agree.")
        #expect(VitalsCompare.statusText("incomplete") == "One of the scans has no value to compare.")

        let rejected = Self.record(mode: .face, reject: "motion", session: "s", metrics: ["heart_rate": Self.metric(70)])
        #expect(VitalsCompare.result(finger: finger, face: rejected)["status"].string == "incomplete")
        #expect(VitalsCompare.pair([finger]) == nil)
    }

    // MARK: Reference readings and calibrations

    @Test func referenceReadingsRoundTripInTheValidationShape() throws {
        let ref = VitalsReference(heartRate: 63, rmssd: 41.5, spo2: 98, systolic: 118, diastolic: 76, device: " Polar H10 ")
        let json = try #require(ref.json)
        #expect(VitalsJSON.encode(json) == #"{"blood_pressure":[118,76],"device":"Polar H10","heart_rate":63,"hrv_rmssd":41.5,"spo2":98}"#)
        let back = VitalsReference(json: json)
        #expect(back.heartRate == 63 && back.rmssd == 41.5 && back.systolic == 118 && back.diastolic == 76 && back.device == "Polar H10")
        #expect(back.value(for: "blood_pressure") == 118)
        #expect(VitalsReference(device: "Strap").json == nil, "a device name alone is not a reading")
        #expect(VitalsReference(systolic: 120).json == nil, "blood pressure needs both values")
        // The fixture's reference shape reads back.
        #expect(VitalsReference(referenceJSON: #"{"device": "Polar H10", "heart_rate": 63}"#).heartRate == 63)
    }

    @Test func calibrationsFollowTheScanAndTheToggles() throws {
        let scan = Self.engineRecord()
        let ratio = try #require(VitalsCalibrations.spo2Ratio(scan), "a clean finger scan measures the SpO₂ ratio")
        let features = try #require(VitalsCalibrations.bpFeatures(scan), "and the pulse-morphology features")
        #expect(VitalsCalibrations.canCalibrateSpo2(scan, experimentalEnabled: true))
        #expect(!VitalsCalibrations.canCalibrateSpo2(scan, experimentalEnabled: false))
        #expect(VitalsCalibrations.canCalibrateBp(scan, researchEnabled: true))
        #expect(!VitalsCalibrations.canCalibrateBp(scan, researchEnabled: false))

        let spo2 = try #require(VitalsCalibrations.spo2(scan: scan, spo2: 98, id: "local:c1", nowMs: 5))
        #expect(spo2.kind == "spo2" && spo2.deviceModel == "iPhone15,2" && spo2.scanID == scan.id && spo2.tMs == scan.startMs)
        #expect(spo2.referenceJSON == #"{"spo2":98}"#)
        #expect(try VitalsJSON.parse(spo2.featuresJSON)["ratio"].double == ratio)
        let bp = try #require(VitalsCalibrations.bp(scan: scan, systolic: 118, diastolic: 76, scanGapMin: 2, id: "local:c2", nowMs: 5))
        #expect(bp.referenceJSON == #"{"dbp":76,"sbp":118,"scan_gap_min":2}"#)
        #expect(bp.featuresJSON == VitalsJSON.encode(features))
        // They become engine inputs again.
        #expect(VitalScanRecordBuilder.spo2Calibrations([spo2]) == [VitalsEngine.Spo2Calibration(ratio: ratio, spo2: 98)])
        #expect(VitalScanRecordBuilder.bpCalibrations([bp]).first?.scanGapMin == 2)

        var face = Self.engineRecord(mode: .face)
        face.mode = VitalsMode.face.rawValue
        #expect(VitalsCalibrations.spo2Ratio(face) == nil && VitalsCalibrations.bpFeatures(face) == nil, "face scans never calibrate")
    }

    @Test @MainActor func analysisUsesThisModelsSpo2CalibrationsAndEveryBpCalibration() async throws {
        let db = try await HealthDatabase.inMemory()
        func cal(_ id: String, _ kind: String, _ model: String) -> VitalCalibration {
            VitalCalibration(id: id, kind: kind, deviceModel: model, scanID: nil, tMs: 1_000,
                             referenceJSON: kind == "spo2" ? #"{"spo2":97}"# : #"{"sbp":120,"dbp":80,"scan_gap_min":1}"#,
                             featuresJSON: kind == "spo2" ? #"{"ratio":0.7}"# : #"{"hr":70.0}"#, updatedMs: 1_000)
        }
        try await db.saveCalibration(cal("a", "spo2", "iPhone15,2"))
        try await db.saveCalibration(cal("b", "spo2", "Pixel 7"))
        try await db.saveCalibration(cal("c", "bp", "iPhone15,2"))
        try await db.saveCalibration(cal("d", "bp", "Pixel 7"))
        let store = VitalsStore(database: db)
        let options = await store.analysisOptions(deviceModel: "iPhone15,2", now: Date(timeIntervalSince1970: 2))
        #expect(options.spo2Calibrations == [VitalsEngine.Spo2Calibration(ratio: 0.7, spo2: 97)])
        #expect(options.bpCalibrations.count == 2)
        #expect(options.nowMs == 2_000)
    }

    // MARK: Validation

    @Test func validationPairsReferencesAndCountsFailures() throws {
        let hr = { (m: Double?, ref: Double, conf: Double) in
            Self.record(metrics: ["heart_rate": Self.metric(m, confidence: conf)], reference: .obj(["heart_rate": .number(ref)]))
        }
        let scans = [hr(64, 63, 0.9), hr(70, 72, 0.5), hr(nil, 60, 0.9),
                     Self.record(mode: .face, metrics: ["heart_rate": Self.metric(80)], reference: nil),
                     Self.record(metrics: ["blood_pressure": Self.metric(121, classification: "research")],
                                 reference: .obj(["blood_pressure": .arr([.int(118), .int(76)])]))]
        let rows = VitalsValidation.rows(scans)
        #expect(rows.map(\.id) == ["finger_ppg/heart_rate", "finger_ppg/blood_pressure"])
        let stats = rows[0].stats
        let expected = VitalsEngine.validationStats(pairs: [.init(measured: 64, reference: 63, confidence: 0.9),
                                                            .init(measured: 70, reference: 72, confidence: 0.5)], failures: 1, .shared)
        #expect(VitalsVectorRunner.strictDifference(stats, expected) == nil)
        #expect(stats["n"].double == 2 && stats["failure_rate"].double == 0.333)
        #expect(rows[1].stats["n"].double == 1)

        let dataset = VitalsValidation.dataset(scans)
        #expect(dataset["format"].string == "ayuvo-vitals-validation" && dataset["version"].double == 1)
        let rowsOut = try #require(dataset["scans"].array)
        #expect(rowsOut.count == 4, "only scans with a reference")
        #expect(rowsOut.contains { $0["metrics"]["heart_rate"]["value"].double == 64 && $0["reference"]["heart_rate"].double == 63 })
        #expect(rowsOut.allSatisfy { $0["mode"].string == "finger_ppg" && $0["device_model"].string == "iPhone15,2" })
        let url = try VitalsValidation.writeDataset(scans)
        defer { try? FileManager.default.removeItem(at: url.deletingLastPathComponent()) }
        #expect(url.lastPathComponent == "ayuvo-vitals-validation.json")
        #expect(try VitalsJSON.parse(Data(contentsOf: url))["scans"].array?.count == 4)
    }

    // MARK: Coach

    @Test func coachLinesCarryClassificationsAndGateExperimentalValues() throws {
        let now = Date(timeIntervalSince1970: 1_790_000_000)
        let nowMs: Int64 = 1_790_000_000_000
        let full: [String: RJ] = [
            "heart_rate": Self.metric(64), "hrv_rmssd": Self.metric(42, classification: "calculated"),
            "respiratory_rate": Self.metric(14.2, classification: "estimated"),
            "spo2": Self.metric(97, classification: "experimental"),
            "blood_pressure": Self.metric(118, classification: "research", extra: ["diastolic": .num(76)]),
        ]
        var scans = [Self.record(startMs: nowMs - 3_600_000, metrics: full),
                     Self.record(mode: .face, startMs: nowMs - 7_200_000, reject: "motion", metrics: ["heart_rate": Self.metric(70)]),
                     Self.record(startMs: nowMs - 8 * 86_400_000, metrics: full)]
        let off = VitalsCoachSummary.lines(scans: scans, now: now, experimentalEnabled: false, researchEnabled: false,
                                           timeZone: TimeZone(identifier: "UTC")!)
        #expect(off.count == 3, "header, one scan, guardrail: the rejected and the 8-day-old scans are left out")
        #expect(off[1].contains("finger scan") && off[1].contains("HR 64 bpm (measured)") && off[1].contains("RMSSD 42 ms (calculated)"))
        #expect(off[1].contains("resp 14.2 /min (estimated)") && off[1].contains("quality good"))
        #expect(!off[1].contains("SpO2") && !off[1].contains("BP"))
        let on = VitalsCoachSummary.lines(scans: scans, now: now, experimentalEnabled: true, researchEnabled: true,
                                          timeZone: TimeZone(identifier: "UTC")!)
        #expect(on[1].contains("SpO2 97 % (EXPERIMENTAL camera estimate, not an oxygen saturation measurement)"))
        #expect(on[1].contains("BP 118/76 mmHg (RESEARCH_ONLY estimate, not a blood pressure measurement)"))
        scans = (0..<8).map { Self.record(startMs: nowMs - Int64($0) * 60_000, metrics: full) }
        #expect(VitalsCoachSummary.lines(scans: scans, now: now, experimentalEnabled: false, researchEnabled: false).count == 5 + 2)
        #expect(VitalsCoachSummary.lines(scans: [], now: now, experimentalEnabled: true, researchEnabled: true).isEmpty)
    }

    // MARK: Insights fallback

    @Test func insightsFallbackFillsOnlyMissingDaysFromRestingFingerScans() throws {
        var inputs = InsightsInputs()
        inputs.hrvKind = "sdnn"
        inputs.series["resting_heart_rate"] = ["2026-10-01": 60]
        let scans = [
            Self.record(day: "2026-10-01", metrics: ["heart_rate": Self.metric(70), "hrv_sdnn": Self.metric(50)]),
            Self.record(day: "2026-10-02", metrics: ["heart_rate": Self.metric(64), "hrv_sdnn": Self.metric(48), "hrv_rmssd": Self.metric(30),
                                                     "respiratory_rate": Self.metric(14)]),
            Self.record(mode: .face, day: "2026-10-03", metrics: ["heart_rate": Self.metric(80), "hrv_sdnn": Self.metric(20)]),
            Self.record(day: "2026-10-04", context: "after_activity", metrics: ["heart_rate": Self.metric(95)]),
            Self.record(day: "2026-10-05", quality: 60, metrics: ["heart_rate": Self.metric(66)]),
        ]
        VitalsInsightsFallback.apply(into: &inputs, scans: scans, from: "2026-09-01", through: "2026-10-31")
        // A day with a platform value is untouched.
        #expect(inputs.series["resting_heart_rate"]?["2026-10-01"] == 60)
        #expect(inputs.scanFallback["resting_heart_rate"]?.contains("2026-10-01") != true)
        // A scan-filled day is used and flagged.
        #expect(inputs.series["resting_heart_rate"]?["2026-10-02"] == 64)
        #expect(inputs.series["hrv"]?["2026-10-02"] == 48, "iOS HRV is SDNN")
        #expect(inputs.series["hrv"]?["2026-10-01"] == 50)
        #expect(inputs.series["respiratory_rate"] == ["2026-10-02": 14])
        #expect(inputs.scanFallback["resting_heart_rate"] == ["2026-10-02"])
        #expect(inputs.scanFallback["hrv"] == ["2026-10-01", "2026-10-02"])
        // Face scans, scans after activity and low-quality scans never count.
        #expect(inputs.series["resting_heart_rate"]?["2026-10-03"] == nil)
        #expect(inputs.series["hrv"]?["2026-10-03"] == nil)
        #expect(inputs.series["resting_heart_rate"]?["2026-10-04"] == nil)
        #expect(inputs.series["resting_heart_rate"]?["2026-10-05"] == nil)
        // Outside the window nothing is added.
        var narrow = InsightsInputs()
        VitalsInsightsFallback.apply(into: &narrow, scans: scans, from: "2026-10-10", through: "2026-10-31")
        #expect(narrow.series.isEmpty && narrow.scanFallback.isEmpty)
    }

    @Test @MainActor func insightsDataSourceUsesScansAfterThePlatformSeries() async throws {
        let suite = "VitalsWave3Tests.\(UUID().uuidString)"
        let defaults = try #require(UserDefaults(suiteName: suite))
        defer { defaults.removePersistentDomain(forName: suite) }
        defaults.set(true, forKey: "healthKitEnabled")
        let database = try await HealthDatabase.inMemory()
        let today = InsightsDataSourceTests.today
        try await InsightsTestData.seed(database, today: today, nights: 20)
        let old = InsightsDay.add(today, -40)
        let scans = [
            Self.record(day: old, metrics: ["heart_rate": Self.metric(62)]),
            Self.record(day: today, metrics: ["heart_rate": Self.metric(99)]),
            Self.record(mode: .face, day: InsightsDay.add(today, -41), metrics: ["heart_rate": Self.metric(70)]),
        ]
        let source = InsightsDataSource(
            food: FoodStore(observesExternalChanges: false, defaults: defaults),
            water: WaterStore(defaults: defaults, observesExternalChanges: false),
            fasting: FastingStore(defaults: defaults, observesExternalChanges: false),
            weight: WeightStore(observesExternalChanges: false, defaults: defaults),
            bodyFat: BodyFatStore(defaults: defaults, observesExternalChanges: false),
            workouts: StrengthWorkoutStore(defaults: defaults, observesExternalChanges: false),
            importedWorkouts: ImportedHealthWorkoutStore(defaults: defaults),
            profile: { .default }, defaults: defaults, calendar: InsightsTestData.utcCalendar(),
            healthDatabase: { database }, dailyTotals: { _, _, _ in nil }, vitalScans: { _ in scans })
        let inputs = await source.inputs(today: today)
        #expect(inputs.series["resting_heart_rate"]?[today] == 55, "the wearable value wins")
        #expect(inputs.series["resting_heart_rate"]?[old] == 62, "a day without a wearable value takes the finger scan")
        #expect(inputs.scanFallback["resting_heart_rate"] == [old])
        #expect(inputs.series["resting_heart_rate"]?[InsightsDay.add(today, -41)] == nil, "face scans are never used")
    }

    // MARK: Export / restore (§7.2)

    static var fixtureDirectory: URL {
        HealthTestFixtures.repoRootURL.appendingPathComponent("shared/vitals/fixtures/camera-vitals-sample")
    }

    static func fixtureEntries() throws -> [String: Data] {
        var out: [String: Data] = [:]
        for name in ["manifest.json", "scans.ndjson", "signals.ndjson", "calibrations.ndjson", "device_profiles.ndjson"] {
            out[VitalsArchive.directory + name] = try Data(contentsOf: fixtureDirectory.appendingPathComponent(name))
        }
        return out
    }

    @Test func fixtureImportsWithItsSignalsAndReanalysesToTheStoredHeartRate() async throws {
        let db = try await HealthDatabase.inMemory()
        let entries = try Self.fixtureEntries()
        let manifest = try VitalsJSON.parse(try #require(entries[VitalsArchive.manifestEntry]))
        let result = try await VitalsArchive.importEntries(entries, into: db)
        #expect(result.scansAdded == Int(manifest["counts"]["scans"].double ?? -1))
        #expect(result.signalsAdded == Int(manifest["counts"]["signals"].double ?? -1))
        #expect(result.calibrationsAdded == Int(manifest["counts"]["calibrations"].double ?? -1))
        #expect(result.deviceProfilesUpdated == Int(manifest["counts"]["device_profiles"].double ?? -1))
        #expect(result.invalidRows == 0)

        let finger = try #require(try await db.scan(id: "local:3f2b6c1e-9a4d-4e0b-8f2e-1c5a7d9e0b11"))
        #expect(finger.mode == "finger_ppg" && finger.platform == "ios" && finger.deviceModel == "iPhone15,2")
        #expect(finger.startMs == VitalsArchive.ms("2026-10-01T07:30:00.000Z") && finger.durationMs == 40_000)
        #expect(finger.tzOffsetS == 19_800 && finger.localDay == "2026-10-01" && finger.context == "resting")
        #expect(finger.qualityScore == 93.9 && finger.rejectReason == nil && finger.sessionID == "8c1d2e3f-4a5b-4c6d-8e7f-901a2b3c4d5e")
        #expect(VitalsReference(referenceJSON: finger.referenceJSON).heartRate == 63)
        #expect(try VitalsJSON.parse(finger.cameraJSON)["torch"].bool == true)
        let face = try #require(try await db.scan(id: "local:7d6e5f4a-3b2c-4d1e-9f0a-b1c2d3e4f5a6"))
        #expect(face.rejectReason == "duration_short" && face.referenceJSON == nil && face.platform == "android")
        #expect(try await db.signals(scanID: face.id).isEmpty)
        #expect(try await db.sampleCount() == 0, "nothing is written to Health")

        let signals = try await db.signals(scanID: finger.id)
        #expect(Set(signals.map(\.kind)) == ["frame_stats", "processed", "mask", "beats"])
        let stats = try VitalSignalCodec.decode(try #require(signals.first { $0.kind == "frame_stats" }))
        #expect(stats.columns == VitalSignalCodec.fingerColumns && stats.rows.count == 1200)
        #expect(try VitalSignalCodec.decode(try #require(signals.first { $0.kind == "beats" })).rows.count == 43)
        #expect(signals.first { $0.kind == "processed" }?.sampleRate == 30)

        let expected = try #require(manifest["expected"]["scan1_heart_rate"].double)
        #expect(VitalScanValues(finger).valid("heart_rate") == expected)
        let frames = stats.rows.map { $0.map(Double.init) }
        let reanalysed = VitalsEngine.analyzeFinger(.init(frames: frames), VitalsConfig.shared)
        let hr = try #require(reanalysed.metric("heart_rate").value)
        #expect(abs(hr - expected) <= 0.5, "re-analysed \(hr) vs stored \(expected)")

        let cal = try #require(try await db.calibrations().first)
        #expect(cal.kind == "spo2" && cal.deviceModel == "iPhone15,2" && cal.referenceJSON == #"{"spo2":98}"#)
        #expect(cal.featuresJSON == #"{"ratio":0.6512}"# && cal.tMs == VitalsArchive.ms("2026-10-01T07:31:30.000Z"))
        let profile = try #require(try await db.deviceProfile(model: "iPhone15,2", position: "back"))
        #expect(try VitalsJSON.parse(profile.capabilityJSON)["max_fps"].double == 60)
    }

    @Test func importingTwiceAddsNothingAndTombstonesStayDeleted() async throws {
        let db = try await HealthDatabase.inMemory()
        let entries = try Self.fixtureEntries()
        // A scan and a calibration deleted here before the restore.
        try await db.saveScan(VitalScanStorageTests.record(id: "local:3f2b6c1e-9a4d-4e0b-8f2e-1c5a7d9e0b11", startMs: 1))
        try await db.deleteScan(id: "local:3f2b6c1e-9a4d-4e0b-8f2e-1c5a7d9e0b11", nowMs: 2)
        let first = try await VitalsArchive.importEntries(entries, into: db)
        #expect(first.scansAdded == 1 && first.scansSkipped == 1 && first.signalsAdded == 0)
        #expect(try await db.scan(id: "local:3f2b6c1e-9a4d-4e0b-8f2e-1c5a7d9e0b11")?.deleted == 1)
        #expect(try await db.signals(scanID: "local:3f2b6c1e-9a4d-4e0b-8f2e-1c5a7d9e0b11").isEmpty)
        try await db.deleteCalibration(id: "local:a1b2c3d4-e5f6-4a7b-8c9d-0e1f2a3b4c5d", nowMs: 3)

        let second = try await VitalsArchive.importEntries(entries, into: db)
        #expect(second.scansAdded == 0 && second.signalsAdded == 0 && second.calibrationsAdded == 0)
        #expect(second.scansSkipped == 2 && second.calibrationsSkipped == 1 && second.deviceProfilesUpdated == 0)
        #expect(try await db.calibrations().isEmpty, "a tombstoned calibration stays deleted")
        #expect(try await db.scanCount() == 1)

        // Device profiles: the newer `updated` wins.
        try await db.upsertDeviceProfile(VitalDeviceProfile(deviceModel: "iPhone15,2", cameraPosition: "back", capabilityJSON: #"{"max_fps":120}"#,
                                                            updatedMs: try #require(VitalsArchive.ms("2027-01-01T00:00:00.000Z"))))
        _ = try await VitalsArchive.importEntries(entries, into: db)
        #expect(try await db.deviceProfile(model: "iPhone15,2", position: "back")?.capabilityJSON == #"{"max_fps":120}"#)
    }

    @Test func exportRoundTripsIntoAnEmptyStore() async throws {
        let source = try await HealthDatabase.inMemory()
        _ = try await VitalsArchive.importEntries(try Self.fixtureEntries(), into: source)
        let local = Self.engineRecord(id: "local:new")
        try await source.saveScan(local, signals: try VitalSignalCodec.signals(from: VitalsEngine.analyzeFinger(
            .init(frames: [[0, 200, 40, 30, 5, 0], [33, 201, 40, 30, 5, 0]], includeSignals: true), .shared)))
        try await source.updateReference(id: local.id, json: #"{"heart_rate":67}"#, nowMs: 1_790_000_200_000)
        try await source.saveScan(VitalScanStorageTests.record(id: "local:gone", startMs: 5))
        try await source.deleteScan(id: "local:gone")

        let export = try await VitalsArchive.export(from: source, keepSignals: true)
        #expect(export.entries.map(\.name) == [VitalsArchive.manifestEntry] + VitalsArchive.dataEntries)
        #expect(export.counts == ["scans": 3, "signals": 4 + (try await source.signals(scanID: "local:new")).count,
                                  "calibrations": 1, "device_profiles": 1])
        let scansText = String(decoding: try #require(export.entries.first { $0.name == VitalsArchive.scansEntry }?.data), as: UTF8.self)
        #expect(!scansText.contains("local:gone"), "deleted scans are not exported")
        #expect(scansText.contains(#""start":"2026-10-01T07:30:00.000Z""#))
        let manifest = try VitalsJSON.parse(export.entries[0].data)
        #expect(manifest["format"].string == "ayuvo-camera-vitals" && manifest["format_version"].double == 1)

        let target = try await HealthDatabase.inMemory()
        let entries = Dictionary(uniqueKeysWithValues: export.entries.map { ($0.name, $0.data) })
        let result = try await VitalsArchive.importEntries(entries, into: target)
        #expect(result.scansAdded == 3 && result.invalidRows == 0)
        for scan in try await source.scans() {
            let copy = try #require(try await target.scan(id: scan.id))
            #expect(copy == scan)
            #expect(try await target.signals(scanID: scan.id) == (try await source.signals(scanID: scan.id)))
        }
        #expect(try await target.calibrations() == (try await source.calibrations()))
        #expect(try await target.deviceProfiles() == (try await source.deviceProfiles()))

        // Keep signals off: results only.
        let lean = try await VitalsArchive.export(from: source, keepSignals: false)
        #expect(lean.counts["signals"] == 0)
        #expect(lean.entries.first { $0.name == VitalsArchive.signalsEntry }?.data.isEmpty == true)
    }

    @Test func newerSectionVersionIsRefused() async throws {
        let db = try await HealthDatabase.inMemory()
        var entries = try Self.fixtureEntries()
        entries[VitalsArchive.manifestEntry] = Data(#"{"format":"ayuvo-camera-vitals","format_version":2}"#.utf8)
        await #expect(throws: VitalsArchive.ArchiveError.newerVersion) { try await VitalsArchive.importEntries(entries, into: db) }
        #expect(try await db.scanCount(includeDeleted: true) == 0)
    }

    @Test func allDataPlanImportsCameraVitalsAfterHealthData() throws {
        let manifest: [String: Any] = [
            "app": "Ayuvo", "format": "ayuvo-all-data", "format_version": 1, "platform": "android", "app_version": "1",
            "created_at": "2026-10-01T00:00:00Z", "skipped": [],
            "files": [
                ["name": "medications/ayuvo-medications.json", "section": "medications", "format": "ayuvo-medications", "bytes": 1, "counts": ["medications": 1]],
                ["name": "camera-vitals/scans.ndjson", "section": "camera_vitals", "format": "ayuvo-camera-vitals", "bytes": 1, "counts": ["scans": 2]],
                ["name": "camera-vitals/signals.ndjson", "section": "camera_vitals", "format": "ayuvo-camera-vitals", "bytes": 1, "counts": ["signals": 4]],
                ["name": "health-data/h.zip", "section": "health_data", "format": "ayuvo-health-data", "bytes": 1, "counts": ["samples": 3]],
            ],
        ]
        let data = try JSONSerialization.data(withJSONObject: manifest)
        let names: Set<String> = ["medications/ayuvo-medications.json", "health-data/h.zip", "camera-vitals/scans.ndjson",
                                  "camera-vitals/signals.ndjson", "camera-vitals/calibrations.ndjson", "camera-vitals/manifest.json"]
        let plan = try AllDataImport.plan(manifestData: data, entryNames: names)
        #expect(plan.items.map(\.section) == [.healthData, .cameraVitals, .medications])
        let vitals = try #require(plan.items.first { $0.section == .cameraVitals })
        #expect(vitals.disposition == .importIt, "camera scans import from either platform")
        #expect(vitals.counts == ["scans": 2, "signals": 4])
        #expect(vitals.entryNames == ["camera-vitals/calibrations.ndjson", "camera-vitals/manifest.json",
                                      "camera-vitals/scans.ndjson", "camera-vitals/signals.ndjson"])
        #expect(AllDataImport.countText("scans", 2) == "2 camera scans")
    }

    // MARK: Portable preferences

    @Test @MainActor func vitalsPreferencesTravelInPortableData() throws {
        let suite = "VitalsWave3Tests.portable.\(UUID().uuidString)"
        let defaults = try #require(UserDefaults(suiteName: suite))
        let backups = FileManager.default.temporaryDirectory.appendingPathComponent("vitals-portable-\(UUID().uuidString)")
        defer {
            defaults.removePersistentDomain(forName: suite)
            try? FileManager.default.removeItem(at: backups)
        }
        let json = #"""
        {"app": "Ayuvo", "format": "ayuvo-portable-data", "format_version": 1, "created_at": "2026-10-01T10:00:00.000Z",
         "platform": "android", "app_version": "1.0",
         "preferences": {"vitals_keep_signals": false, "vitals_experimental_enabled": true, "vitals_research_enabled": "yes"}}
        """#
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(identifier: "UTC")!
        _ = try PortableDataImport.restore(data: Data(json.utf8), defaults: defaults, calendar: calendar, backupDirectory: backups)
        #expect(VitalsSettings.keepSignals(defaults) == false)
        #expect(VitalsSettings.experimentalEnabled(defaults) == true)
        #expect(VitalsSettings.researchEnabled(defaults) == false, "a malformed value is ignored")

        defaults.set(true, forKey: VitalsSettings.researchKey)
        let output = try #require(PortableDataExport.build(defaults: defaults, appVersion: "1", calendar: calendar))
        let root = try #require(try JSONSerialization.jsonObject(with: output.data) as? [String: Any])
        let preferences = try #require(root["preferences"] as? [String: Any])
        #expect(preferences["vitals_keep_signals"] as? Bool == false)
        #expect(preferences["vitals_experimental_enabled"] as? Bool == true)
        #expect(preferences["vitals_research_enabled"] as? Bool == true)
    }
}
