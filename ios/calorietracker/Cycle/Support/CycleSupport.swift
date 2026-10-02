import Foundation

/// App-level switches for cycle tracking (docs/cycle-tracking.md §1, §8). Everything that shapes estimates and
/// reminders lives in the cycle database (`cycle_settings`); these two only decide visibility and Coach access.
nonisolated enum CycleSettings {
    /// "Show cycle tracking": off hides every entry point without deleting data. Default on (opt-in setup).
    static let enabledKey = "cycleEnabled"
    /// Coach access to the cycle summary. Default off; turned on only through the consent sheet.
    static let coachEnabledKey = "coachCycleEnabled"
    static let coachConsentedAtKey = "coachCycleConsentedAt"

    static func enabled(_ defaults: UserDefaults = .standard) -> Bool {
        defaults.object(forKey: enabledKey) as? Bool ?? true
    }

    static func coachEnabled(_ defaults: UserDefaults = .standard) -> Bool {
        defaults.object(forKey: coachEnabledKey) as? Bool ?? false
    }

    static func setCoachEnabled(_ on: Bool, defaults: UserDefaults = .standard, now: Date = Date()) {
        defaults.set(on, forKey: coachEnabledKey)
        if on { defaults.set(now.timeIntervalSince1970, forKey: coachConsentedAtKey) }
    }
}

/// Display text for the contract catalogues (translated through `Contracts.xcstrings`, English fallback from
/// `cycle_config.json`; keys from `scripts/l10n/l10n_contracts.py`).
nonisolated enum CycleText {
    static var config: CycleConfig { .shared }

    static var disclaimer: String { ContractText.text("cycle.disclaimer", config.disclaimer) }
    static var fertilityNote: String { ContractText.text("cycle.fertility_note", config.fertilityNote) }

    static func phase(_ key: String) -> String {
        let english = config.phases.first { $0.key == key }?.title ?? key
        return ContractText.text("cycle.phases.\(key).title", english)
    }

    static func basisTitle(_ key: String) -> String {
        let english = config.basis.first { $0.key == key }?.title ?? key
        return ContractText.text("cycle.basis.\(key).title", english)
    }

    static func basisAbout(_ key: String) -> String {
        let english = config.basis.first { $0.key == key }?.about ?? ""
        return ContractText.text("cycle.basis.\(key).about", english)
    }

    static func flow(_ key: String) -> String {
        let english = config.flowLevels.first { $0.key == key }?.title ?? key
        return ContractText.text("cycle.flow_levels.\(key).title", english)
    }

    static func symptom(_ key: String) -> String {
        let english = config.symptoms.first { $0.key == key }?.title ?? key
        return ContractText.text("cycle.symptoms.\(key).title", english)
    }

    static func symptomGroup(_ key: String) -> String {
        let english = config.symptomGroups.first { $0.key == key }?.title ?? key
        return ContractText.text("cycle.symptom_groups.\(key).title", english)
    }

    static func mood(_ key: String) -> String {
        let english = config.moods.first { $0.key == key }?.title ?? key
        return ContractText.text("cycle.moods.\(key).title", english)
    }

    static func painLocation(_ key: String) -> String {
        let english = config.painLocations.first { $0.key == key }?.title ?? key
        return ContractText.text("cycle.pain_locations.\(key).title", english)
    }

    /// An insight sentence: the translated template with its `{name}` placeholders filled in.
    static func insight(_ insight: CycleInsight) -> String {
        let english = config.insight(insight.key)?.template ?? ""
        return insight.text(template: ContractText.text("cycle.insights.\(insight.key).template", english))
    }

    static func isProfessional(_ insight: CycleInsight) -> Bool { config.insight(insight.key)?.professional ?? false }
}

/// Local-day helpers between `Date` and the engine's 'yyyy-MM-dd' days.
nonisolated enum CycleDates {
    static func todayString(_ now: Date = Date(), calendar: Calendar = .current) -> String {
        CycleDay.string(CycleDay.today(now, calendar: calendar))
    }

    /// Noon of a local day (safe across DST) in `calendar`'s zone.
    static func date(_ day: String, calendar: Calendar = .current) -> Date {
        guard let n = CycleDay.ordinal(day) else { return Date() }
        return date(ordinal: n, calendar: calendar)
    }

    static func date(ordinal n: Int, calendar: Calendar = .current) -> Date {
        let text = CycleDay.string(n)
        var c = DateComponents()
        c.year = Int(text.prefix(4))
        c.month = Int(text.dropFirst(5).prefix(2))
        c.day = Int(text.dropFirst(8).prefix(2))
        c.hour = 12
        return calendar.date(from: c) ?? Date()
    }

    static func string(_ date: Date, calendar: Calendar = .current) -> String {
        CycleDay.string(CycleDay.today(date, calendar: calendar))
    }

    /// "12 Oct" style.
    static func short(_ day: String) -> String {
        date(day).formatted(.dateTime.day().month(.abbreviated))
    }

    /// "Sunday, 12 October".
    static func long(_ day: String) -> String {
        date(day).formatted(.dateTime.weekday(.wide).day().month(.wide))
    }

    /// "12 Sep – 16 Sep" (an open end reads "now").
    static func range(_ start: String, _ end: String?) -> String {
        let endText = end.map(short) ?? String(localized: "now", comment: "Cycle: an ongoing period's end in a date range")
        return "\(short(start)) – \(endText)"
    }

    static func nowMs(_ now: Date = Date()) -> Int64 { Int64((now.timeIntervalSince1970 * 1000).rounded()) }
}
