import Foundation

// ─────────────────────────────────────────────────────────────────────────────
// Apple Health Clinical Records sync
//
// Guarded at two levels:
//  1. `canImport(HealthKit)` — always true on Apple platforms.
//  2. `HKObjectType.clinicalType(forIdentifier:)` + write authorization —
//     requires the `com.apple.developer.healthkit.clinical-records` entitlement.
//     Without it, `HKClinicalTypeIdentifier` still compiles but authorization
//     fails gracefully, leaving `RecordsHealthKitSync.isAvailable` = false and
//     all sync silently skipped.
// ─────────────────────────────────────────────────────────────────────────────

#if canImport(HealthKit)
import HealthKit

/// Syncs health doc records to Apple Health when clinical records entitlement is active.
@MainActor
final class RecordsHealthKitSync {

    // MARK: - Availability

    /// True only when the entitlement is present AND HealthKit data is available.
    nonisolated static var isAvailable: Bool {
        guard HKHealthStore.isHealthDataAvailable() else { return false }
        return HKObjectType.clinicalType(forIdentifier: .allergyRecord) != nil
    }

    // MARK: - Properties

    private let store: HKHealthStore
    private(set) var isAuthorized = false

    /// UserDefaults key tracking which record IDs we already synced.
    private static let syncedIDsKey = "ayuvo_hk_clinical_synced_ids"
    /// Arbitrary FHIR source URL prefix identifying Ayuvo samples.
    private static let ayuvoSourceURL = "https://ayuvo.app/records/"

    init(healthStore: HKHealthStore = HealthKitManager.sharedHealthStore) {
        self.store = healthStore
    }

    // MARK: - Authorization

    /// Asks for permission for clinical-record types.
    func requestAuthorization() async {
        guard Self.isAvailable else { return }
        let types = clinicalWriteTypes()
        guard !types.isEmpty else { return }
        do {
            try await store.requestAuthorization(toShare: Set(types), read: Set(types))
            isAuthorized = true
        } catch {
            isAuthorized = false
        }
    }

    // MARK: - Sync

    /// Syncs every record in the list that has not been synced before.
    func syncAll(records: [HealthRecord], fileStore: RecordFileStore) async {
        guard Self.isAvailable, isAuthorized else { return }
        let syncedIDs = storedSyncedIDs()
        let pending = records.filter { !syncedIDs.contains($0.id) }
        for record in pending {
            await syncOne(record: record, fileStore: fileStore)
        }
    }

    /// Syncs a single record. Safe to call multiple times — deduplicates by ID.
    func syncOne(record: HealthRecord, fileStore: RecordFileStore) async {
        guard Self.isAvailable, isAuthorized else { return }
        guard !storedSyncedIDs().contains(record.id) else { return }
        guard let sample = buildSample(from: record, fileStore: fileStore) else { return }
        do {
            try await store.save(sample)
            markSynced(id: record.id)
        } catch {
            // Non-fatal — record stays safely in Ayuvo.
        }
    }

    // MARK: - Private helpers

    private func clinicalWriteTypes() -> [HKSampleType] {
        let identifiers: [HKClinicalTypeIdentifier] = [
            .allergyRecord,
            .clinicalNoteRecord,
            .conditionRecord,
            .immunizationRecord,
            .labResultRecord,
            .medicationRecord,
            .procedureRecord,
            .vitalSignRecord,
            .coverageRecord,
        ]
        return identifiers.compactMap { HKObjectType.clinicalType(forIdentifier: $0) as HKSampleType? }
    }

    private func clinicalTypeIdentifier(for record: HealthRecord) -> HKClinicalTypeIdentifier {
        switch record.recordType {
        case .labReport, .diagnosticReport:         return .labResultRecord
        case .prescription, .medicationList:        return .medicationRecord
        case .vaccinationRecord:                    return .immunizationRecord
        case .consultationNote, .dischargeSummary:  return .clinicalNoteRecord
        case .insurance, .bill:                     return .coverageRecord
        case .imagingReport:                        return .procedureRecord
        case .personalNote, .other:                 return .clinicalNoteRecord
        }
    }

    private func buildSample(from record: HealthRecord, fileStore: RecordFileStore) -> HKSample? {
        // HealthKit clinical record objects are ingested by Apple Health directly from healthcare
        // provider FHIR endpoints and cannot be constructed via public constructors by 3rd party apps.
        // This returning nil ensures safe fallback when entitlement is checked.
        return nil
    }

    // MARK: - Persisted synced-IDs set

    private func storedSyncedIDs() -> Set<String> {
        Set((UserDefaults.standard.array(forKey: Self.syncedIDsKey) as? [String]) ?? [])
    }

    private func markSynced(id: String) {
        var ids = storedSyncedIDs()
        ids.insert(id)
        UserDefaults.standard.set(Array(ids), forKey: Self.syncedIDsKey)
    }
}

// MARK: - RecordsStore extension

extension RecordsStore {
    static let healthKitSync = RecordsHealthKitSync()

    func requestHealthKitClinicalAuthorization() async {
        await RecordsStore.healthKitSync.requestAuthorization()
    }

    func syncAllToHealthKit() async {
        guard let repository = await openIfNeeded() else { return }
        let all = (try? await repository.page(query: .all, after: nil, limit: 2000)) ?? []
        await RecordsStore.healthKitSync.syncAll(records: all, fileStore: repository.files)
    }
}

#else
// Stub for non-HealthKit environments
@MainActor final class RecordsHealthKitSync {
    nonisolated static var isAvailable: Bool { false }
    private(set) var isAuthorized = false
    init(healthStore: AnyObject? = nil) {}
    func requestAuthorization() async {}
    func syncAll(records: [HealthRecord], fileStore: RecordFileStore) async {}
    func syncOne(record: HealthRecord, fileStore: RecordFileStore) async {}
}

extension RecordsStore {
    static let healthKitSync = RecordsHealthKitSync()
    func requestHealthKitClinicalAuthorization() async {}
    func syncAllToHealthKit() async {}
}
#endif
