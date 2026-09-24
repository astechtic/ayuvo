import Foundation

/// Runs the three medication tools against the conversation's snapshot
/// (docs/medications.md §20). Payloads come straight from `MR`, the port of
/// `scripts/medications_reference.py`, so a tool result and the app's own adherence screen can never
/// disagree.
extension CoachTools {
    func executeMedicationTool(name: String, arguments: [String: Any]) -> String {
        guard let medications, medications.toolsAvailable else {
            return Self.medicationsUnavailableJSON
        }
        let args = RJ.from(arguments)
        let payload: RJ
        switch name {
        case "get_medications":
            payload = MR.coachMedicationsPayload(snapshot: medications.snapshot, args: args)
        case "get_dose_history":
            payload = MR.coachDoseHistoryPayload(snapshot: medications.snapshot, args: args,
                                                 now: Int(medications.nowMs), zone: medications.timeZone)
        case "get_medication_adherence":
            payload = MR.coachAdherencePayload(snapshot: medications.snapshot, args: args,
                                               now: Int(medications.nowMs), zone: medications.timeZone)
        default:
            return Self.medicationsUnavailableJSON
        }
        return payload.jsonText
    }

    /// `errors.unavailable` — returned when the user turned the source off while a call was in
    /// flight, the same way the records executor handles it.
    static var medicationsUnavailableJSON: String {
        MR.coachError("unavailable").jsonText
    }
}
