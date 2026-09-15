import Foundation

/// Coach suggestion chips that need Health Records data (§27, reference `latest_lab_selection`).
struct CoachRecordsSuggestions: Equatable {
    var latestLabs: [ChatRecordRef] = []
    /// Latest lab report + its previous report, when that pair exists.
    var comparePair: [ChatRecordRef] = []

    var isEmpty: Bool { latestLabs.isEmpty }
}

// Phase 4 façade: Coach context, entry points and the consent state (docs/health-records.md §26–§30).
extension RecordsStore {
    /// Records context for one Coach message. Without access the context is `disabled` (the prompt may
    /// still say records are unavailable); with access it carries the database, the refreshed selection,
    /// the prompt pieces and the packed on-device block.
    func coachContext(selected: [ChatRecordRef], session: CoachRecordsSession) async -> CoachRecordsContext {
        guard coachAccessEnabled, let repository = await openIfNeeded() else {
            var context = CoachRecordsContext.disabled
            context.session = session
            return context
        }
        let database = repository.database
        let ids = selected.map(\.recordID)
        let (prompt, packed, current) = await Task.detached(priority: .userInitiated) { () -> (RJ, RJ, [HealthRecord]) in
            let prompt = (try? await database.coachPromptLines(accessEnabled: true, selectedIDs: ids)) ?? .null
            let packed = ids.isEmpty ? RJ.null : ((try? await database.coachPack(selectedIDs: ids)) ?? .null)
            let records = (try? await database.coachRecords(ids: ids)) ?? []
            return (prompt, packed, records)
        }.value
        return CoachRecordsContext(
            enabled: true,
            // Selected records deleted since they were chosen drop out; titles and dates are refreshed.
            selected: current.map(ChatRecordRef.init),
            database: database,
            today: RecordDates.dayString(from: Date()),
            prompt: prompt,
            packed: packed,
            session: session
        )
    }

    /// §27 partner for "Compare with previous report".
    func compareCandidate(for recordID: String) async -> HealthRecord? {
        guard let repository = await openIfNeeded() else { return nil }
        return try? await repository.database.coachCompareCandidate(recordID: recordID)
    }

    func coachSuggestions() async -> CoachRecordsSuggestions {
        guard coachAccessEnabled, let repository = await openIfNeeded() else { return CoachRecordsSuggestions() }
        guard let selection = try? await repository.database.coachLatestLabSelection() else { return CoachRecordsSuggestions() }
        return CoachRecordsSuggestions(latestLabs: selection.latest.map(ChatRecordRef.init), comparePair: selection.compare.map(ChatRecordRef.init))
    }

    /// Picker candidates: search results when text is entered, else recent records.
    func coachPickerCandidates(text: String) async -> [HealthRecord] {
        guard let repository = await openIfNeeded() else { return [] }
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmed.isEmpty {
            return (try? await repository.page(query: RecordQuery(), after: nil, limit: 40)) ?? []
        }
        return (try? await repository.page(query: RecordQuery(text: trimmed), after: nil, limit: 40)) ?? []
    }

    /// "Explain this trend": every record in the series, newest first, max 10.
    func trendRecordRefs(analyteID: String) async -> [ChatRecordRef] {
        guard let trend = await trend(analyteID: analyteID) else { return [] }
        var seen = Set<String>()
        var refs: [ChatRecordRef] = []
        for item in trend.items where !item.observation.excludedFromTrends && seen.insert(item.record.id).inserted {
            refs.append(ChatRecordRef(item.record))
        }
        return Array(refs.prefix(RecordsCoach.maxSelected))
    }

    func recordRefs(ids: [String]) async -> [ChatRecordRef] {
        guard let repository = await openIfNeeded() else { return [] }
        let database = repository.database
        let records = (try? await database.coachRecords(ids: ids)) ?? []
        return records.sorted { ($0.effectiveDate, $0.createdMs) > ($1.effectiveDate, $1.createdMs) }.map(ChatRecordRef.init)
    }
}

/// §27 prompts prefilled by the entry points.
enum CoachRecordsPrompts {
    static var explainReport: String { String(localized: "Explain this report: what stands out and what should I ask my doctor?") }
    static var compareWithPrevious: String { String(localized: "Compare this report with the previous one. What changed?") }
    static func explainTrend(_ analyte: String) -> String { String(localized: "Explain how my \(analyte) has changed over time.") }
    static var analyzeLatest: String { String(localized: "Analyze my latest blood reports") }
    static var findAbnormal: String { String(localized: "Find abnormal values") }
    static var compareChip: String { String(localized: "Compare with previous report") }
}
