import Foundation
import Observation

/// Camera scans for the UI, over the health database's `vital_*` tables (opened through `HealthDataRuntime`).
/// Never touches HealthKit or `health_samples`.
@Observable
@MainActor
final class VitalsStore {
    static let shared = VitalsStore()

    private(set) var scans: [VitalScanRecord] = []
    private(set) var hasLoaded = false
    /// Bumped after every save or delete.
    private(set) var revision = 0

    @ObservationIgnored private let runtime: HealthDataRuntime
    @ObservationIgnored private var testDatabase: HealthDatabase?

    init(runtime: HealthDataRuntime? = nil, database: HealthDatabase? = nil) {
        self.runtime = runtime ?? .shared
        testDatabase = database
    }

    func database() async -> HealthDatabase? {
        if let testDatabase { return testDatabase }
        guard await runtime.openIfNeeded() else { return nil }
        return runtime.writer
    }

    func reload() async {
        guard let db = await database() else { return }
        scans = (try? await db.scans()) ?? []
        hasLoaded = true
    }

    func scans(mode: VitalsMode) -> [VitalScanRecord] { scans.filter { $0.mode == mode.rawValue } }

    func latest(_ mode: VitalsMode) -> VitalScanRecord? { scans.first { $0.mode == mode.rawValue } }

    func save(_ record: VitalScanRecord, signals: [VitalSignal]) async throws {
        guard let db = await database() else { throw CocoaError(.fileWriteUnknown) }
        try await db.saveScan(record, signals: signals)
        revision += 1
        await reload()
    }

    func delete(id: String) async {
        guard let db = await database() else { return }
        try? await db.deleteScan(id: id)
        revision += 1
        await reload()
    }

    func deleteAll() async {
        guard let db = await database() else { return }
        try? await db.deleteAllScans()
        revision += 1
        await reload()
    }

    func scan(id: String) async -> VitalScanRecord? {
        guard let db = await database() else { return nil }
        return try? await db.scan(id: id)
    }

    func signals(scanID: String) async -> [VitalSignal] {
        guard let db = await database() else { return [] }
        return (try? await db.signals(scanID: scanID)) ?? []
    }

    /// Engine inputs from the settings and the calibrations (docs/camera-vitals.md §7.1): SpO₂ calibrations are
    /// keyed by this device model (the camera and torch differ per model); BP calibrations are the user's own cuff
    /// pairs from any phone (`bp_research` itself drops expired or distant ones).
    func analysisOptions(deviceModel: String = VitalsDevice.model, now: Date = Date()) async -> VitalsAnalysisOptions {
        var options = VitalsAnalysisOptions()
        options.experimentalEnabled = VitalsSettings.experimentalEnabled()
        options.researchEnabled = VitalsSettings.researchEnabled()
        options.nowMs = (now.timeIntervalSince1970 * 1000).rounded()
        if let db = await database() {
            let spo2 = (try? await db.calibrations(kind: "spo2", deviceModel: deviceModel)) ?? []
            let bp = (try? await db.calibrations(kind: "bp")) ?? []
            options.spo2Calibrations = VitalScanRecordBuilder.spo2Calibrations(spo2)
            options.bpCalibrations = VitalScanRecordBuilder.bpCalibrations(bp)
        }
        return options
    }

    // MARK: Reference readings and calibrations

    /// Stores the reference readings of a saved scan and, when asked, its calibration pairs.
    func saveReference(scan: VitalScanRecord, reference: RJ?, calibrations: [VitalCalibration]) async throws {
        guard let db = await database() else { throw CocoaError(.fileWriteUnknown) }
        try await db.updateReference(id: scan.id, json: reference.map(VitalsJSON.encode))
        for c in calibrations { try await db.saveCalibration(c) }
        revision += 1
        await reload()
    }

    /// After Import All Data wrote scans straight into the database.
    func didImport() async {
        revision += 1
        await reload()
    }

    func calibrations() async -> [VitalCalibration] {
        guard let db = await database() else { return [] }
        return (try? await db.calibrations()) ?? []
    }

    func deleteCalibration(id: String) async {
        guard let db = await database() else { return }
        try? await db.deleteCalibration(id: id)
        revision += 1
    }

    /// Live scans sharing a compare session, oldest first.
    func scans(sessionID: String) async -> [VitalScanRecord] {
        guard let db = await database() else { return [] }
        return (try? await db.scans(sessionID: sessionID)) ?? []
    }

    func upsertDeviceProfile(position: String, capability: RJ) async {
        guard let db = await database() else { return }
        try? await db.upsertDeviceProfile(VitalDeviceProfile(deviceModel: VitalsDevice.model, cameraPosition: position,
                                                             capabilityJSON: VitalsJSON.encode(capability),
                                                             updatedMs: HealthDatabase.nowMs()))
    }

    /// Builds and saves a finished session's scan. Returns the saved record.
    func save(session: ScanSession) async throws -> VitalScanRecord {
        guard let result = session.result, let buffer = session.analysedBuffer else { throw CocoaError(.fileWriteUnknown) }
        if !hasLoaded { await reload() }
        let startMs = Int64(((session.startedAt ?? Date()).timeIntervalSince1970 * 1000).rounded())
        let input = VitalScanRecordBuilder.Input(
            result: result, buffer: buffer, mode: session.mode, sessionID: session.sessionID, context: session.context,
            startMs: startMs, camera: session.cameraConfiguration ?? session.source?.cameraConfiguration()
                ?? CameraConfiguration(position: session.mode == .finger ? "back" : "front", lens: "wide", width: 0, height: 0,
                                       targetFps: CameraManager.targetFps, achievedFps: nil, exposureMs: nil, iso: nil,
                                       whiteBalanceLocked: false, torch: false),
            deviceModel: VitalsDevice.model, keepSignals: VitalsSettings.keepSignals(), nowMs: HealthDatabase.nowMs())
        let built = try VitalScanRecordBuilder.build(input, history: scans, session.config)
        try await save(built.record, signals: built.signals)
        return built.record
    }
}
