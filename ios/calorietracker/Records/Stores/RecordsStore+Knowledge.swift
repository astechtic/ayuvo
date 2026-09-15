import Foundation

// Phase 3 façade: observation edits, trends, links and entities. Every database call hops to the
// `RecordsDatabase` actor, so the main thread only receives finished values.
extension RecordsStore {
    // MARK: - Observations

    func editObservation(_ observation: RecordObservation, edit: RecordObservationEdit) async {
        guard let repository = await openIfNeeded() else { return }
        _ = try? await repository.database.editObservation(id: observation.id, edit: edit)
        await refreshProcessingState(recordIDs: [observation.recordID])
    }

    func removeObservation(_ observation: RecordObservation) async {
        guard let repository = await openIfNeeded() else { return }
        try? await repository.database.removeObservation(id: observation.id)
        await refreshProcessingState(recordIDs: [observation.recordID])
    }

    func addObservation(recordID: String, draft: RecordObservationDraft) async {
        guard let repository = await openIfNeeded() else { return }
        _ = try? await repository.database.addUserObservation(recordID: recordID, draft: draft)
        showBanner(String(localized: "Value added"))
        await refreshProcessingState(recordIDs: [recordID])
    }

    /// Other unmapped values with the same printed name (offered when remembering a mapping).
    func unmappedCount(sameNameAs observation: RecordObservation) async -> Int {
        guard let repository = await openIfNeeded() else { return 0 }
        return (try? await repository.database.unmappedObservations(sameNameAs: observation.rawName, excluding: observation.id).count) ?? 0
    }

    func trend(analyteID: String) async -> RecordAnalyteTrend? {
        guard let repository = await openIfNeeded() else { return nil }
        return try? await repository.database.trend(analyteID: analyteID)
    }

    func observation(id: String) async -> RecordObservation? {
        guard let repository = await openIfNeeded() else { return nil }
        return try? await repository.database.observation(id: id)
    }

    // MARK: - Links

    func linkRecords(_ recordID: String, _ otherIDs: [String], kind: RecordLinkKind) async {
        guard let repository = await openIfNeeded() else { return }
        for other in otherIDs where other != recordID {
            try? await repository.database.setUserLink(recordID, other, kind: kind)
        }
        showBanner(otherIDs.count == 1 ? String(localized: "Records linked") : String(localized: "\(otherIDs.count) records linked"), systemImage: "link")
        await refreshProcessingState(recordIDs: [recordID] + otherIDs)
    }

    func unlink(_ recordID: String, _ otherID: String) async {
        guard let repository = await openIfNeeded() else { return }
        try? await repository.database.unlink(recordID, otherID)
        await refreshProcessingState(recordIDs: [recordID, otherID])
    }

    func acceptSuggestion(_ recordID: String, _ otherID: String) async {
        guard let repository = await openIfNeeded() else { return }
        try? await repository.database.acceptSuggestion(recordID, otherID)
        await refreshProcessingState(recordIDs: [recordID, otherID])
    }

    /// Link sheet candidates: FTS search when text is entered, else recent records.
    func linkCandidates(for recordID: String, text: String) async -> [HealthRecord] {
        guard let repository = await openIfNeeded() else { return [] }
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        let rows: [HealthRecord]
        if trimmed.isEmpty {
            rows = (try? await repository.page(query: RecordQuery(), after: nil, limit: 40)) ?? []
        } else {
            rows = (try? await repository.page(query: RecordQuery(text: trimmed), after: nil, limit: 40)) ?? []
        }
        return rows.filter { $0.id != recordID }
    }
}
