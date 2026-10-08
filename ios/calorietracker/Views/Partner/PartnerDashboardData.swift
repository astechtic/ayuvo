import Foundation

// Pure, testable models behind the Partner screens. Everything here is built only from rows stored in
// `partner_records` (docs/partner-sync.md §1: nothing is fabricated; a value appears only when a row exists).

/// How one received category shows on the dashboard (§9).
nonisolated enum PartnerSectionState: Sendable, Equatable {
    /// Never shared and nothing stored: the section is hidden.
    case hidden
    /// Currently shared: data, or an empty state.
    case shared
    /// Shared before and turned off: kept data is labelled "No longer shared".
    case revoked

    static func of(_ category: String, grants: [PartnerGrantReceived], categoriesWithData: Set<String>) -> PartnerSectionState {
        if let grant = grants.first(where: { $0.category == category }) {
            if grant.granted { return .shared }
            if grant.revokedMs != nil { return .revoked }
        }
        // Rows without a grant row (e.g. a later revoke lost on an old install) are still shown, labelled.
        return categoriesWithData.contains(category) ? .revoked : .hidden
    }
}

/// One headline metric of the Summary card (`summary_metrics`, §15).
nonisolated struct PartnerSummaryMetric: Sendable, Equatable, Identifiable {
    let key: String
    let category: String
    let value: Int
    let unit: String?
    let day: String?
    /// False when the metric's category is no longer shared (the row is kept and shown dimmed).
    let shared: Bool

    var id: String { key }

    init(key: String, category: String, value: Int, unit: String?, day: String?, shared: Bool) {
        self.key = key
        self.category = category
        self.value = value
        self.unit = unit
        self.day = day
        self.shared = shared
    }

    init?(rj: RJ) {
        guard let key = rj["key"].string, let category = rj["category"].string, let value = rj["value"].pyInt else { return nil }
        self.init(key: key, category: category, value: value, unit: rj["unit"].string, day: rj["day"].string, shared: rj["shared"].bool ?? true)
    }

    /// Medicines: the dose total carried in the unit (`of:<total>`).
    var ofTotal: Int? {
        guard let unit, unit.hasPrefix("of:") else { return nil }
        return Int(unit.dropFirst(3))
    }

    /// Up to `limit` metrics for one partner from the stored rows of today and yesterday (§15).
    static func compute(rows: [PartnerRecordRow], grants: [PartnerGrantReceived], today: String, yesterday: String, limit: Int = 3) -> [PartnerSummaryMetric] {
        let rj = rows.map { RJ.obj(["type": .str($0.type), "day": RJ.string($0.day), "data": $0.data]) }
        let granted = grants.filter(\.granted).map { RJ.str($0.category) }
        return PartnerRef.summaryMetrics(rows: rj, today: .str(today), yesterday: .str(yesterday), grantsReceived: granted, limit: limit)
            .compactMap(PartnerSummaryMetric.init(rj:))
    }

    static let sourceTypes = ["analytics_day", "sleep_night", "metric_day", "dose_log"]
}

/// Everything the partner dashboard shows for one selected day plus the latest values, trends and reports.
nonisolated struct PartnerDashboardData: Sendable {
    nonisolated struct Recovery: Sendable, Equatable {
        let value: Double
        let classification: String?
        let day: String
    }

    nonisolated struct Sleep: Sendable, Equatable {
        let day: String
        let asleepMin: Int
        let inBedMin: Int?
        let deepMin: Int?
        let remMin: Int?
        let lightMin: Int?
        let awakeMin: Int?
        let avgHR: Double?
        let startMs: Int64?
        let endMs: Int64?

        var hasStages: Bool { [deepMin, remMin, lightMin, awakeMin].contains { ($0 ?? 0) > 0 } }
    }

    nonisolated struct Food: Sendable, Equatable, Identifiable {
        let id: String
        let name: String
        let meal: String?
        let emoji: String?
        let calories: Double
        let proteinG: Double?
        let carbsG: Double?
        let fatG: Double?
        let loggedMs: Int64?
    }

    nonisolated struct Workout: Sendable, Equatable, Identifiable {
        let id: String
        let activity: String
        let title: String?
        let startMs: Int64?
        let durationS: Double
        let kcal: Double?
        let distanceM: Double?
        let avgHR: Double?
    }

    nonisolated struct Dose: Sendable, Equatable, Identifiable {
        let id: String
        let medication: String?
        let status: String
        let scheduledMs: Int64?
        let quantity: Double?
        let unit: String?
    }

    nonisolated struct Vital: Sendable, Equatable, Identifiable {
        /// resting_hr, hrv, spo2, blood_pressure, weight
        let key: String
        let value: Double
        let value2: Double?
        let unit: String?
        let day: String
        var id: String { key }
    }

    nonisolated struct TrendPoint: Sendable, Equatable {
        let day: String
        let value: Double
    }

    nonisolated struct Trend: Sendable, Equatable, Identifiable {
        /// heart_rate, resting_hr, weight, sleep, steps, activity
        let key: String
        let category: String
        let unit: String?
        let points: [TrendPoint]
        var id: String { key }

        func points(from day: String) -> [TrendPoint] { points.filter { $0.day >= day } }
    }

    nonisolated struct Report: Sendable, Identifiable {
        let id: String
        let day: String?
        let data: RJ

        var title: String { data["title"].string ?? "" }
        var reportDate: String? { data["report_date"].string ?? day }
        var abnormalCount: Int { data["abnormal"].array?.count ?? 0 }
        var resultCount: Int { data["results"].array?.count ?? 0 }
    }

    var day: String
    var states: [String: PartnerSectionState] = [:]
    var recovery: Recovery?
    var sleep: Sleep?
    var food: [Food] = []
    var waterMl: Double?
    var workouts: [Workout] = []
    var doses: [Dose] = []
    var vitals: [Vital] = []
    var trends: [Trend] = []
    var reports: [Report] = []

    func state(_ category: String) -> PartnerSectionState { states[category] ?? .hidden }

    /// "Rest day" only when workouts are shared right now and none was logged that day.
    var showsRestDay: Bool { state("workouts") == .shared && workouts.isEmpty }

    var calories: Double? { food.isEmpty ? nil : food.reduce(0) { $0 + $1.calories } }
    func macroTotal(_ key: KeyPath<Food, Double?>) -> Double? {
        let values = food.compactMap { $0[keyPath: key] }
        return values.isEmpty ? nil : values.reduce(0, +)
    }
    var dosesTaken: Int { doses.filter { $0.status == "taken" }.count }

    // MARK: Building

    /// Types read for the selected-day sections and the trends (bounded by the 30-day window).
    static let windowTypes = ["metric_day", "analytics_day", "sleep_night", "food_entry", "water_day", "workout", "dose_log", "medication", "weight"]
    /// Types whose rows make a day "have data" for the default day.
    static let dailyTypes: Set<String> = ["food_entry", "water_day", "workout", "dose_log", "sleep_night", "analytics_day"]

    /// The day the dashboard opens on: today when it has data, else the most recent day with data on or after
    /// `earliest`, else today.
    static func defaultDay(rows: [PartnerRecordRow], today: String, earliest: String) -> String {
        let days = rows.filter { dailyTypes.contains($0.type) }.compactMap(\.day).filter { $0 <= today && $0 >= earliest }
        return days.max() ?? today
    }

    /// `latest` holds the newest row of each vital (any age); `rows` the window rows; `reports` every overview.
    static func build(day: String, rows: [PartnerRecordRow], latest: [PartnerRecordRow], reports: [PartnerRecordRow],
                      grants: [PartnerGrantReceived], categoriesWithData: Set<String>) -> PartnerDashboardData {
        var out = PartnerDashboardData(day: day)
        for c in PartnerCatalog.shared.categories {
            out.states[c] = PartnerSectionState.of(c, grants: grants, categoriesWithData: categoriesWithData)
        }
        let parsed = rows.map { (row: $0, data: $0.data) }
        func num(_ v: RJ) -> Double? { v.pyNumber.flatMap { v.isPyNum ? $0 : nil } }
        func int64(_ v: RJ) -> Int64? { num(v).map { Int64($0) } }

        // Recovery and sleep of the selected day.
        if let r = parsed.first(where: { $0.row.type == "analytics_day" && $0.row.day == day && $0.data["metric_id"].string == "recovery_indicator" }),
           let v = num(r.data["value"]) {
            out.recovery = Recovery(value: v, classification: r.data["classification"].string, day: day)
        }
        if let s = parsed.first(where: { $0.row.type == "sleep_night" && $0.row.day == day }), let asleep = num(s.data["asleep_min"]) {
            func m(_ k: String) -> Int? { num(s.data[k]).map { Int($0.rounded()) } }
            out.sleep = Sleep(day: day, asleepMin: Int(asleep.rounded()), inBedMin: m("in_bed_min"), deepMin: m("deep_min"), remMin: m("rem_min"),
                              lightMin: m("light_min"), awakeMin: m("awake_min"), avgHR: num(s.data["avg_hr"]),
                              startMs: int64(s.data["start_ms"]), endMs: int64(s.data["end_ms"]))
        }

        // Nutrition, workouts and medicines of the selected day.
        out.food = parsed.filter { $0.row.type == "food_entry" && $0.row.day == day }.map { p in
            Food(id: p.row.recordID, name: p.data["name"].string ?? "", meal: p.data["meal"].string, emoji: p.data["emoji"].string,
                 calories: num(p.data["calories"]) ?? 0, proteinG: num(p.data["protein_g"]), carbsG: num(p.data["carbs_g"]),
                 fatG: num(p.data["fat_g"]), loggedMs: int64(p.data["logged_ms"]))
        }.sorted { ($0.loggedMs ?? 0, $0.id) < ($1.loggedMs ?? 0, $1.id) }
        out.waterMl = parsed.first { $0.row.type == "water_day" && $0.row.day == day }.flatMap { num($0.data["total_ml"]) }
        out.workouts = parsed.filter { $0.row.type == "workout" && $0.row.day == day }.map { p in
            Workout(id: p.row.recordID, activity: p.data["activity"].string ?? "", title: p.data["title"].string, startMs: int64(p.data["start_ms"]),
                    durationS: num(p.data["duration_s"]) ?? 0, kcal: num(p.data["kcal"]), distanceM: num(p.data["distance_m"]),
                    avgHR: num(p.data["avg_hr"]))
        }.sorted { ($0.startMs ?? 0, $0.id) < ($1.startMs ?? 0, $1.id) }
        var medicationNames: [String: String] = [:]
        for p in parsed where p.row.type == "medication" {
            if let name = p.data["name"].string { medicationNames[p.row.recordID] = name }
        }
        out.doses = parsed.filter { $0.row.type == "dose_log" && $0.row.day == day }.map { p in
            Dose(id: p.row.recordID, medication: p.data["medication_id"].string.flatMap { medicationNames[$0] }, status: p.data["status"].string ?? "",
                 scheduledMs: int64(p.data["scheduled_at_ms"]), quantity: num(p.data["dose_quantity"]), unit: p.data["dose_unit"].string)
        }.sorted { ($0.scheduledMs ?? 0, $0.id) < ($1.scheduledMs ?? 0, $1.id) }

        // Latest vitals (any age), each with its own day.
        let latestParsed = latest.map { (row: $0, data: $0.data) }
        func latestMetric(_ typeIDs: [String]) -> (row: PartnerRecordRow, data: RJ)? {
            latestParsed.filter { $0.row.type == "metric_day" && typeIDs.contains($0.data["type_id"].string ?? "") && num($0.data["avg"]) != nil }
                .max { ($0.row.day ?? "") < ($1.row.day ?? "") }
        }
        if let r = latestMetric(["resting_heart_rate"]), let day = r.row.day, let v = num(r.data["avg"]) {
            out.vitals.append(Vital(key: "resting_hr", value: v, value2: nil, unit: r.data["unit"].string, day: day))
        }
        if let r = latestMetric(["daily_hrv", "hrv_sdnn", "hrv_rmssd"]), let day = r.row.day, let v = num(r.data["avg"]) {
            out.vitals.append(Vital(key: "hrv", value: v, value2: nil, unit: r.data["unit"].string, day: day))
        }
        if let r = latestMetric(["daily_blood_oxygen", "blood_oxygen"]), let day = r.row.day, let v = num(r.data["avg"]) {
            out.vitals.append(Vital(key: "spo2", value: v, value2: nil, unit: r.data["unit"].string, day: day))
        }
        if let r = latestMetric(["blood_pressure"]), let day = r.row.day, let v = num(r.data["avg"]) {
            out.vitals.append(Vital(key: "blood_pressure", value: v, value2: num(r.data["v2_avg"]), unit: r.data["unit"].string, day: day))
        }
        // Weight: the newer of the Health rollup and the app's own weight entries.
        var weight: Vital?
        if let r = latestParsed.filter({ $0.row.type == "metric_day" && $0.data["type_id"].string == "weight" })
            .max(by: { ($0.row.day ?? "") < ($1.row.day ?? "") }), let day = r.row.day, let v = num(r.data["last_value"]) ?? num(r.data["avg"]) {
            weight = Vital(key: "weight", value: v, value2: nil, unit: r.data["unit"].string ?? "kg", day: day)
        }
        if let r = latestParsed.filter({ $0.row.type == "weight" }).max(by: { ($0.row.day ?? "") < ($1.row.day ?? "") }),
           let day = r.row.day, let kg = num(r.data["kg"]), day >= (weight?.day ?? "") {
            weight = Vital(key: "weight", value: kg, value2: nil, unit: "kg", day: day)
        }
        if let weight { out.vitals.append(weight) }

        // Trends over the window (the view picks 7 or 30 days).
        func series(_ key: String, typeID: String, field: [String]) -> Trend? {
            var byDay: [String: Double] = [:]
            var unit: String?
            var category = "vitals"
            for p in parsed where p.row.type == "metric_day" && p.data["type_id"].string == typeID {
                guard let d = p.row.day, let v = field.lazy.compactMap({ num(p.data[$0]) }).first else { continue }
                byDay[d] = v
                unit = unit ?? p.data["unit"].string
                category = p.row.category
            }
            guard !byDay.isEmpty else { return nil }
            return Trend(key: key, category: category, unit: unit, points: byDay.keys.sorted().map { TrendPoint(day: $0, value: byDay[$0]!) })
        }
        var trends: [Trend] = []
        if let t = series("resting_hr", typeID: "resting_heart_rate", field: ["avg"]) { trends.append(t) }
        if let t = series("heart_rate", typeID: "heart_rate", field: ["avg"]) { trends.append(t) }
        var sleepDays: [String: Double] = [:]
        for p in parsed where p.row.type == "sleep_night" {
            if let d = p.row.day, let v = num(p.data["asleep_min"]) { sleepDays[d] = v }
        }
        if !sleepDays.isEmpty {
            trends.append(Trend(key: "sleep", category: "sleep", unit: "min", points: sleepDays.keys.sorted().map { TrendPoint(day: $0, value: sleepDays[$0]!) }))
        }
        if let t = series("steps", typeID: "steps", field: ["sum"]) { trends.append(t) }
        if let t = series("activity", typeID: "exercise_minutes", field: ["sum"]) { trends.append(t) }
        var weightDays: [String: Double] = [:]
        for p in parsed where p.row.type == "metric_day" && p.data["type_id"].string == "weight" {
            if let d = p.row.day, let v = num(p.data["last_value"]) ?? num(p.data["avg"]) { weightDays[d] = v }
        }
        for p in parsed where p.row.type == "weight" {
            if let d = p.row.day, let v = num(p.data["kg"]) { weightDays[d] = v }
        }
        if !weightDays.isEmpty {
            trends.append(Trend(key: "weight", category: "vitals", unit: "kg", points: weightDays.keys.sorted().map { TrendPoint(day: $0, value: weightDays[$0]!) }))
        }
        out.trends = trends

        out.reports = reports.filter { $0.type == "report_overview" }.map { Report(id: $0.recordID, day: $0.day, data: $0.data) }
            .sorted { ($0.reportDate ?? "", $0.id) > ($1.reportDate ?? "", $1.id) }
        return out
    }
}
