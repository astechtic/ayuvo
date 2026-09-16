import Foundation

/// Prescription import (docs §13): candidates come from the Records pipeline's `medication` fields;
/// the user reviews every draft before `createFromCandidates` writes anything.
extension MedicationStore {
    /// Drafts for a record's medication fields (`state != rejected`), pre-filled by `frequency_hint`.
    func candidates(fromRecordID recordID: String, fields: [RecordField]) -> [MedicationDraft] {
        let start = todayLocalDate
        return fields
            .filter { $0.key == .medication && $0.state != .rejected }
            .compactMap { field -> MedicationDraft? in
                guard let value = field.medicationValue else {
                    let text = field.valueText.trimmingCharacters(in: .whitespacesAndNewlines)
                    guard !text.isEmpty else { return nil }
                    return MedicationDraft(candidate: RecordMedicationValue(name: text), startDate: start, relatedRecordID: recordID)
                }
                var draft = MedicationDraft(candidate: value, startDate: start, relatedRecordID: recordID)
                if draft.name.isEmpty { draft.name = field.valueText.trimmingCharacters(in: .whitespacesAndNewlines) }
                return draft
            }
    }

    /// Creates every confirmed draft (all validated first; nothing is written when one is invalid).
    @discardableResult
    func createFromCandidates(_ drafts: [MedicationDraft]) async throws -> [Medication] {
        for draft in drafts {
            let errors = draft.validationErrors
            guard errors.isEmpty else { throw MedicationStoreError.validation(errors) }
        }
        guard let repository = await openIfNeeded() else { throw MedicationStoreError.notOpen }
        var created: [Medication] = []
        let now = nowMs
        for draft in drafts {
            created.append(try await repository.create(draft: draft, nowMs: now, zone: zoneIdentifier))
        }
        if !created.isEmpty {
            bumpRevision()
            await reload()
            replanReminders()
        }
        return created
    }
}
