import Foundation

/// Coach access to Medications (docs/coach.md §3, docs/medications.md §20).
///
/// `coachMedicationsEnabled` defaults to **false** and is set only by an affirmative act: the first
/// time the user turns Medications on in the composer's data switcher, a confirmation states that
/// medicine names, strengths and dose times will be sent to their AI provider. Turning it off
/// removes the tools immediately.
extension MedicationStore {
    static let coachEnabledKey = "coachMedicationsEnabled"
    static let coachConsentedAtKey = "coachMedicationsConsentedAt"

    var coachAccessEnabled: Bool {
        defaults.bool(forKey: Self.coachEnabledKey)
    }

    /// Whether there is anything for Coach to read at all — drives the composer's "Nothing to read
    /// yet" state, so an empty Medications tab never offers a consent the user cannot use.
    var hasAnyMedication: Bool { totalCount > 0 }

    var coachConsentedAt: Date? {
        guard let text = defaults.string(forKey: Self.coachConsentedAtKey) else { return nil }
        return ISO8601DateFormatter().date(from: text)
    }

    /// Records the affirmative act. `false` clears the timestamp so a later "on" is consent again.
    func setCoachAccess(_ enabled: Bool) {
        defaults.set(enabled, forKey: Self.coachEnabledKey)
        if enabled {
            let formatter = ISO8601DateFormatter()
            formatter.formatOptions = [.withInternetDateTime]
            defaults.set(formatter.string(from: Date()), forKey: Self.coachConsentedAtKey)
        } else {
            defaults.removeObject(forKey: Self.coachConsentedAtKey)
        }
    }

    /// The medications context for one turn, or nil when access is off or there are no medicines.
    func coachContext(nowMs: Int64 = Int64(Date().timeIntervalSince1970 * 1000)) async -> CoachMedicationsContext? {
        guard coachAccessEnabled else { return nil }
        guard let repository = runtime.repository,
              let snapshot = try? await repository.coachSnapshot() else { return nil }
        let rows = snapshot["medications"].array ?? []
        guard !rows.isEmpty else { return nil }
        return CoachMedicationsContext(
            snapshot: snapshot,
            timeZone: TimeZone.current.identifier,
            nowMs: nowMs,
            count: rows.count,
            activeCount: rows.filter { $0["status"].string == "active" }.count
        )
    }
}
