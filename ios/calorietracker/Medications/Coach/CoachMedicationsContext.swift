import Foundation

/// What Coach may read about the user's medicines for one turn (docs/coach.md §3,
/// docs/medications.md §20). Built by `MedicationStore.coachContext()`; nil when the user has no
/// medicines or has not turned the source on.
nonisolated struct CoachMedicationsContext: Sendable {
    /// `{medications, schedules, dose_logs}` — the same three tables the archive uses.
    var snapshot: RJ
    var timeZone: String
    var nowMs: Int64
    var count: Int
    var activeCount: Int

    var toolsAvailable: Bool { count > 0 }

    /// `## Data available` lines when the tools are advertised.
    var promptLines: [String] {
        let lines = MR.coachPromptLines(snapshot: snapshot, accessEnabled: true)
        guard lines["advertise_tools"].truthy else { return [] }
        var out: [String] = []
        if let available = lines["available_line"].string { out.append(available) }
        if let guardrails = lines["guardrails"].string { out.append(guardrails) }
        return out
    }

    /// The line shown when the user asks about medicines with the source off.
    static var notAvailableLine: String {
        MedicationsCoachContract.shared.prompt["not_available_line"] ?? ""
    }

    /// Did the user actually ask about medicines? The word list lives in
    /// `shared/medications/coach_tools.json` so both platforms answer this identically; the message
    /// is folded first, so case and accents do not matter.
    static func mentionsMedicines(_ message: String) -> Bool {
        let words = Set(MedicationsCoachContract.shared.mentionsWords)
        guard !words.isEmpty else { return false }
        return RR.words(RR.fold(message)).contains { words.contains($0) }
    }

    /// At most 12 lines for providers without tool calling (docs/coach.md §3): one per active
    /// medicine, name and schedule only. Never a dose recommendation.
    func onDeviceBlock() -> String? {
        let payload = MR.coachMedicationsPayload(snapshot: snapshot, args: .obj([:]))
        let rows = payload["medications"].array ?? []
        guard !rows.isEmpty else { return nil }
        var lines = ["## Medications"]
        for row in rows.prefix(12) {
            var parts: [String] = []
            let name = row["name"].string ?? ""
            let strength = row["strength"].string
            parts.append(strength.map { "\(name) \($0)" } ?? name)
            if row["is_prn"].truthy {
                parts.append("as needed")
            } else if let schedule = row["schedule"].object {
                let times = (schedule["times"]?.array ?? []).compactMap(\.string)
                if !times.isEmpty { parts.append(times.joined(separator: ", ")) }
            }
            lines.append("- " + parts.joined(separator: " · "))
        }
        if rows.count > 12 {
            lines.append("- (+\(rows.count - 12) more)")
        }
        return lines.joined(separator: "\n")
    }
}
