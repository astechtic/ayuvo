import Foundation

/// Portable `ayuvo-medications.json` export / import (docs §14).
extension MedicationStore {
    static var appVersion: String {
        Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "1.0"
    }

    func exportArchive() async throws -> MedicationArchive {
        guard let repository = await openIfNeeded() else { throw MedicationStoreError.notOpen }
        return try await repository.exportArchive(nowMs: nowMs, zone: zoneIdentifier, platform: "ios", appVersion: Self.appVersion)
    }

    /// Merges an archive: newer rows win, nothing is deleted. Throws `.archive(code)` for a bad file.
    @discardableResult
    func importArchive(_ data: Data) async throws -> MedicationImportResult {
        let archive = try MedicationArchive(data: data)
        return try await importArchive(archive)
    }

    @discardableResult
    func importArchive(_ archive: MedicationArchive) async throws -> MedicationImportResult {
        guard let repository = await openIfNeeded() else { throw MedicationStoreError.notOpen }
        let result = try await repository.mergeArchive(archive, nowMs: nowMs)
        bumpRevision()
        await materializeMissedAndCompletions()
        await reload()
        replanReminders()
        return result
    }
}
