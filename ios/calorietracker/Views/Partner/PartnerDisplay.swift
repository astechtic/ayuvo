import SwiftUI

/// The six shareable categories as the UI shows them (titles, what each one sends, icon, domain colour).
enum PartnerCategoryInfo {
    static func title(_ category: String) -> String {
        switch category {
        case "vitals": String(localized: "partner.category.vitals", defaultValue: "Vitals & activity", comment: "Partner sharing category")
        case "sleep": String(localized: "partner.category.sleep", defaultValue: "Sleep", comment: "Partner sharing category")
        case "nutrition": String(localized: "partner.category.nutrition", defaultValue: "Food & water", comment: "Partner sharing category")
        case "workouts": String(localized: "partner.category.workouts", defaultValue: "Workouts", comment: "Partner sharing category")
        case "medicines": String(localized: "partner.category.medicines", defaultValue: "Medicines", comment: "Partner sharing category")
        case "report_overviews": String(localized: "partner.category.report_overviews", defaultValue: "Health report overviews", comment: "Partner sharing category")
        default: category
        }
    }

    static func subtitle(_ category: String) -> String {
        switch category {
        case "vitals": String(localized: "partner.category.vitals.detail", defaultValue: "Heart rate, HRV, blood pressure, weight, steps and Recovery", comment: "What the Vitals partner category sends")
        case "sleep": String(localized: "partner.category.sleep.detail", defaultValue: "Nights, sleep stages and sleep heart rate", comment: "What the Sleep partner category sends")
        case "nutrition": String(localized: "partner.category.nutrition.detail", defaultValue: "Food diary with calories and macros (no photos) and water", comment: "What the Food partner category sends")
        case "workouts": String(localized: "partner.category.workouts.detail", defaultValue: "Activity, duration, calories and heart rate (no GPS routes)", comment: "What the Workouts partner category sends")
        case "medicines": String(localized: "partner.category.medicines.detail", defaultValue: "Medicines, schedules and doses taken", comment: "What the Medicines partner category sends")
        case "report_overviews": String(localized: "partner.category.report_overviews.detail", defaultValue: "Summary, results and flags only. Documents stay on this phone.", comment: "What the report overviews partner category sends")
        default: ""
        }
    }

    static func systemImage(_ category: String) -> String {
        switch category {
        case "vitals": "heart.fill"
        case "sleep": "bed.double.fill"
        case "nutrition": "fork.knife"
        case "workouts": "figure.run"
        case "medicines": "pills.fill"
        case "report_overviews": "doc.text.fill"
        default: "circle.fill"
        }
    }

    static func tint(_ category: String) -> Color {
        switch category {
        case "vitals": AyuvoPalette.heart
        case "sleep": AyuvoPalette.sleep
        case "nutrition": AyuvoPalette.nutrition
        case "workouts": AyuvoPalette.activity
        case "medicines": AyuvoPalette.medications
        case "report_overviews": AyuvoPalette.records
        default: AyuvoPalette.other
        }
    }
}

/// Text for the Partner screens: freshness, statuses and metric values.
enum PartnerDisplay {
    // MARK: Freshness

    /// "just now", "3 min. ago", "yesterday", "3 days ago", or a short date after a week.
    static func relative(_ date: Date, now: Date, locale: Locale = .autoupdatingCurrent, calendar: Calendar = .autoupdatingCurrent) -> String {
        let interval = now.timeIntervalSince(date)
        if interval < 60 {
            return String(localized: "partner.relative.just_now", defaultValue: "just now", comment: "Partner freshness: synced less than a minute ago (\"Updated just now\")")
        }
        let formatter = RelativeDateTimeFormatter()
        formatter.locale = locale
        formatter.calendar = calendar
        formatter.unitsStyle = .short
        formatter.dateTimeStyle = .named
        let days = calendar.dateComponents([.day], from: calendar.startOfDay(for: date), to: calendar.startOfDay(for: now)).day ?? 0
        if days <= 0 {
            if interval < 3600 { return formatter.localizedString(from: DateComponents(minute: -Int(interval / 60))) }
            return formatter.localizedString(from: DateComponents(hour: -Int(interval / 3600)))
        }
        if days < 7 { return formatter.localizedString(from: DateComponents(day: -days)) }
        var style = Date.FormatStyle.dateTime.month(.abbreviated).day()
        style.locale = locale
        style.calendar = calendar
        return date.formatted(style)
    }

    /// "Updated 3 min. ago", or "Not synced yet".
    static func updated(lastSyncMs: Int64?, now: Date = .now, locale: Locale = .autoupdatingCurrent, calendar: Calendar = .autoupdatingCurrent) -> String {
        guard let lastSyncMs else {
            return String(localized: "partner.freshness.never", defaultValue: "Not synced yet", comment: "Partner freshness before the first sync or import")
        }
        let when = relative(Date(timeIntervalSince1970: Double(lastSyncMs) / 1000), now: now, locale: locale, calendar: calendar)
        return String(localized: "partner.freshness.updated", defaultValue: "Updated \(when)", comment: "Partner freshness; the placeholder is a relative time such as \"3 min. ago\" or \"yesterday\"")
    }

    /// A short status after the freshness, nil when everything is fine (up to date).
    static func shortStatus(_ status: String, trusted: Bool) -> String? {
        guard trusted else {
            return String(localized: "partner.short.unpaired", defaultValue: "No longer paired", comment: "Partner row status after Unpair (data kept)")
        }
        switch status {
        case "up_to_date": return nil
        case "connecting", "syncing": return PartnerStatusText.status(status)
        case "partner_unavailable": return String(localized: "partner.short.offline", defaultValue: "Partner offline", comment: "Partner row status: the other phone was not found during the last sync attempt")
        case "waiting_for_network": return String(localized: "partner.short.no_wifi", defaultValue: "Waiting for Wi-Fi", comment: "Partner row status")
        case "local_network_denied": return String(localized: "partner.short.local_network", defaultValue: "Local Network off", comment: "Partner row status: iOS Local Network permission is off")
        case "not_trusted", "pairing_required": return String(localized: "partner.short.pair_again", defaultValue: "Pair again", comment: "Partner row status")
        default: return String(localized: "partner.short.failed", defaultValue: "Sync didn't finish", comment: "Partner row status")
        }
    }

    /// "Updated yesterday · Partner offline".
    static func freshness(lastSyncMs: Int64?, status: String, trusted: Bool, now: Date = .now,
                          locale: Locale = .autoupdatingCurrent, calendar: Calendar = .autoupdatingCurrent) -> String {
        [updated(lastSyncMs: lastSyncMs, now: now, locale: locale, calendar: calendar), shortStatus(status, trusted: trusted)]
            .compactMap { $0 }
            .joined(separator: " · ")
    }

    static func freshness(_ item: PartnerManager.PartnerItem, now: Date = .now) -> String {
        freshness(lastSyncMs: item.state?.lastSyncMs, status: item.status, trusted: item.peer.isTrusted, now: now)
    }

    /// First two groups of the fingerprint ("5CBE 8B6C"), enough to tell partners apart.
    static func shortFingerprint(_ hex: String) -> String {
        PartnerRef.formatFingerprint(hex).split(separator: " ").prefix(2).joined(separator: " ")
    }

    static func initial(_ name: String) -> String {
        name.first.map { String($0).uppercased() } ?? "?"
    }

    // MARK: Days and values

    /// "Today", "Yesterday" or "Mon, Oct 5".
    static func dayTitle(_ day: String, today: String, calendar: Calendar = .autoupdatingCurrent) -> String {
        if day == today { return String(localized: "Today") }
        if day == PartnerDay.adding(-1, to: today, timeZone: calendar.timeZone) { return String(localized: "Yesterday") }
        guard let date = PartnerDay.date(day, timeZone: calendar.timeZone) else { return day }
        return date.formatted(.dateTime.weekday(.abbreviated).month(.abbreviated).day())
    }

    /// "7 h 24 min", "45 min".
    static func duration(minutes: Int) -> String {
        let h = minutes / 60
        let m = minutes % 60
        if h == 0 { return String(localized: "\(m) min") }
        if m == 0 { return String(localized: "\(h) h") }
        return String(localized: "\(h) h \(m) min")
    }

    static func number(_ value: Double, fraction: Int = 0) -> String {
        value.formatted(.number.precision(.fractionLength(0...fraction)))
    }

    // MARK: Summary metrics

    static func metricTitle(_ key: String) -> String {
        switch key {
        case "recovery": String(localized: "Recovery")
        case "sleep": String(localized: "Sleep")
        case "resting_hr": String(localized: "partner.metric.resting_hr", defaultValue: "Resting heart rate", comment: "Partner metric title")
        case "activity": String(localized: "partner.metric.activity", defaultValue: "Exercise", comment: "Partner metric title: exercise minutes today")
        case "steps": String(localized: "Steps")
        case "medicines": String(localized: "partner.category.medicines", defaultValue: "Medicines", comment: "Partner sharing category")
        default: key
        }
    }

    static func metricSystemImage(_ key: String) -> String {
        switch key {
        case "recovery": "bolt.heart.fill"
        case "sleep": "bed.double.fill"
        case "resting_hr": "heart.fill"
        case "activity": "flame.fill"
        case "steps": "figure.walk"
        case "medicines": "pills.fill"
        default: "circle.fill"
        }
    }

    static func metricTint(_ key: String) -> Color {
        switch key {
        case "recovery": AyuvoPalette.insights
        case "sleep": AyuvoPalette.sleep
        case "resting_hr": AyuvoPalette.heart
        case "activity", "steps": AyuvoPalette.activity
        case "medicines": AyuvoPalette.medications
        default: AyuvoPalette.other
        }
    }

    /// "82", "7 h 24 min", "58 bpm", "32 min", "8,421 steps", "1 of 2 taken".
    static func metricValue(_ m: PartnerSummaryMetric) -> String {
        switch m.key {
        case "recovery": return m.value.formatted()
        case "sleep": return duration(minutes: m.value)
        case "resting_hr": return String(localized: "partner.value.bpm", defaultValue: "\(m.value) bpm", comment: "Heart rate value in beats per minute")
        case "activity": return String(localized: "\(m.value) min")
        case "steps": return String(localized: "partner.value.steps", defaultValue: "\(m.value) steps", comment: "Step count")
        case "medicines":
            let total = m.ofTotal ?? m.value
            return String(localized: "partner.value.doses_taken", defaultValue: "\(m.value) of \(total) taken", comment: "Medicine doses taken today out of the total")
        default: return m.value.formatted()
        }
    }

    // MARK: Vitals and trends

    static func vitalTitle(_ key: String) -> String {
        switch key {
        case "resting_hr": metricTitle("resting_hr")
        case "hrv": String(localized: "partner.metric.hrv", defaultValue: "Heart rate variability", comment: "Partner metric title")
        case "spo2": String(localized: "partner.metric.spo2", defaultValue: "Blood oxygen", comment: "Partner metric title")
        case "blood_pressure": String(localized: "partner.metric.blood_pressure", defaultValue: "Blood pressure", comment: "Partner metric title")
        case "weight": String(localized: "Weight")
        case "heart_rate": String(localized: "partner.metric.heart_rate", defaultValue: "Heart rate", comment: "Partner metric title")
        case "sleep": String(localized: "Sleep")
        case "steps": String(localized: "Steps")
        case "activity": metricTitle("activity")
        default: key
        }
    }

    static func vitalSystemImage(_ key: String) -> String {
        switch key {
        case "resting_hr", "heart_rate": "heart.fill"
        case "hrv": "waveform.path.ecg"
        case "spo2": "lungs.fill"
        case "blood_pressure": "heart.text.square.fill"
        case "weight": "scalemass.fill"
        case "sleep": "bed.double.fill"
        case "steps": "figure.walk"
        case "activity": "flame.fill"
        default: "circle.fill"
        }
    }

    static func vitalTint(_ key: String) -> Color {
        switch key {
        case "resting_hr", "heart_rate", "hrv": AyuvoPalette.heart
        case "spo2": AyuvoPalette.respiratory
        case "blood_pressure": AyuvoPalette.vitals
        case "weight": AyuvoPalette.body
        case "sleep": AyuvoPalette.sleep
        case "steps", "activity": AyuvoPalette.activity
        default: AyuvoPalette.other
        }
    }

    static var usesPounds: Bool { WeightUnit.current == .lbs }

    /// Value text of a latest vital or a trend point, in the user's weight unit.
    static func value(key: String, value: Double, value2: Double? = nil, unit: String?, pounds: Bool = usesPounds) -> String {
        switch key {
        case "resting_hr", "heart_rate":
            return String(localized: "partner.value.bpm", defaultValue: "\(Int(value.rounded())) bpm", comment: "Heart rate value in beats per minute")
        case "hrv":
            return String(localized: "partner.value.ms", defaultValue: "\(Int(value.rounded())) ms", comment: "Heart rate variability in milliseconds")
        case "spo2":
            // Apple Health stores a fraction (0.97); Health Connect a percentage (97).
            let percent = unit == "%" && value <= 1 ? value * 100 : value
            return String(localized: "partner.value.percent", defaultValue: "\(number(percent))%", comment: "Blood oxygen percentage")
        case "blood_pressure":
            let sys = Int(value.rounded())
            guard let value2 else { return String(localized: "partner.value.mmhg_single", defaultValue: "\(sys) mmHg", comment: "Systolic blood pressure only") }
            return String(localized: "partner.value.mmhg", defaultValue: "\(sys)/\(Int(value2.rounded())) mmHg", comment: "Blood pressure systolic/diastolic")
        case "weight":
            if pounds { return String(localized: "\((value * 2.20462).formatted(.number.precision(.fractionLength(1)))) lbs", comment: "Weight in pounds") }
            return String(localized: "\(value.formatted(.number.precision(.fractionLength(1)))) kg", comment: "Weight in kilograms")
        case "sleep":
            return duration(minutes: Int(value.rounded()))
        case "steps":
            return String(localized: "partner.value.steps", defaultValue: "\(Int(value.rounded())) steps", comment: "Step count")
        case "activity":
            return String(localized: "\(Int(value.rounded())) min")
        default:
            return number(value, fraction: 1)
        }
    }

    // MARK: Medicines and workouts

    static func doseStatus(_ status: String) -> String {
        switch status {
        case "taken": String(localized: "partner.dose.taken", defaultValue: "Taken", comment: "Partner dose status")
        case "skipped": String(localized: "partner.dose.skipped", defaultValue: "Skipped", comment: "Partner dose status")
        case "missed": String(localized: "partner.dose.missed", defaultValue: "Missed", comment: "Partner dose status")
        default: String(localized: "partner.dose.pending", defaultValue: "Not logged yet", comment: "Partner dose status: scheduled dose with no answer")
        }
    }

    static func doseTint(_ status: String) -> Color {
        switch status {
        case "taken": AyuvoPalette.nutrition
        case "skipped", "missed": AyuvoPalette.activity
        default: AyuvoPalette.other
        }
    }

    /// The partner's workout name (their own title, else the activity id made readable).
    static func workoutTitle(_ w: PartnerDashboardData.Workout) -> String {
        if let title = w.title, !title.isEmpty { return title }
        let readable = w.activity.replacingOccurrences(of: "_", with: " ")
        return readable.isEmpty ? String(localized: "partner.workout.untitled", defaultValue: "Workout", comment: "Partner workout without a title") : readable.capitalized
    }

    static func time(ms: Int64) -> String {
        Date(timeIntervalSince1970: Double(ms) / 1000).formatted(date: .omitted, time: .shortened)
    }

    // MARK: Reports

    static func flagText(_ flag: String) -> String {
        switch flag {
        case "low": String(localized: "partner.flag.low", defaultValue: "Low", comment: "Lab result flag")
        case "high": String(localized: "partner.flag.high", defaultValue: "High", comment: "Lab result flag")
        case "critical": String(localized: "partner.flag.critical", defaultValue: "Critical", comment: "Lab result flag")
        case "abnormal": String(localized: "partner.flag.abnormal", defaultValue: "Abnormal", comment: "Lab result flag")
        case "normal": String(localized: "partner.flag.normal", defaultValue: "Normal", comment: "Lab result flag")
        default: ""
        }
    }

    static func flagTint(_ flag: String) -> Color {
        switch flag {
        case "low", "high", "abnormal": AyuvoPalette.activity
        case "critical": AyuvoPalette.heart
        case "normal": AyuvoPalette.nutrition
        default: AyuvoPalette.other
        }
    }

    /// "12–15 g/dL", "< 200", or the report's own reference text.
    static func referenceRange(_ r: RJ) -> String? {
        let unit = r["unit"].string.map { " \($0)" } ?? ""
        let low = r["ref_low"].isPyNum ? r["ref_low"].pyNumber : nil
        let high = r["ref_high"].isPyNum ? r["ref_high"].pyNumber : nil
        switch (low, high) {
        case let (l?, h?): return "\(number(l, fraction: 2))–\(number(h, fraction: 2))\(unit)"
        case let (l?, nil): return "≥ \(number(l, fraction: 2))\(unit)"
        case let (nil, h?): return "≤ \(number(h, fraction: 2))\(unit)"
        default: return r["ref_text"].string
        }
    }

    static func resultValue(_ r: RJ) -> String {
        let value = r["value"].string ?? (r["value_num"].isPyNum ? number(r["value_num"].pyNumber ?? 0, fraction: 2) : "")
        guard let unit = r["unit"].string, !unit.isEmpty else { return value }
        return "\(value) \(unit)"
    }
}
