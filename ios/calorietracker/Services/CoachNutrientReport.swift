import Foundation

/// `get_nutrient_totals` (docs/coach.md §8): every nutrient the user has data for over a date range — food log,
/// taken supplement doses and (for nutrients the food log does not record) other apps' values from Apple Health —
/// with the personalised reference values. Byte-compatible payload shape with Android's `CoachNutrientReport`.
///
/// Built only from the shared nutrient paths: `NutrientTotals` (`day_totals`), `loggedDayAverage` and
/// `referenceLines` (docs/nutrients.md §4). A nutrient nobody recorded is listed as missing, never as zero.
nonisolated enum CoachNutrientReport {
    /// Per-day rows are included up to this many days; longer ranges return the range totals only.
    static let maxDailyRows = 31
    /// Longest range one call reads.
    static let maxRangeDays = 366

    static let notes: [String] = [
        "Totals are what the user logged. A nutrient missing from a food entry is unknown, not zero: never call a nutrient low or deficient because it is listed in no_data_logged or not_tracked_by_food_log.",
        "supplements counts only doses marked taken in Medications, averaged over their dosing interval. Supplements never add calories.",
        "health_other_apps is from other apps through Apple Health / Health Connect, only for nutrients the Ayuvo food log does not record.",
        "Reference values are NASEM Dietary Reference Intakes for adults 19+ who are not pregnant or breastfeeding; upper_limit_scope says which sources the upper limit covers.",
        "Lab results in Health Records measure blood levels, not intake. Mention them only as a complement to these totals, never instead of them.",
    ]

    struct Input {
        var foods: [FoodEntry]
        var supplements: [NutrientsReference.SupplementEntry]
        var takenDoseMs: [Int64]
        /// Nutrient key → local day (yyyy-MM-dd) → amount in the nutrient's unit, from other apps via Health.
        var health: [String: [String: Double]] = [:]
        var profile: NutrientsReference.Profile
        /// Nutrient key → the user's custom goal (Settings › Nutrient goals).
        var customGoals: [String: Double] = [:]
        var calendar: Calendar = .current
    }

    /// Reference nutrients (37, reference order) whose health type can fill the gaps the food log leaves.
    static var healthBackedKeys: [(key: String, healthType: String)] {
        NutrientsReference.data.nutrients.compactMap { n in
            guard !n.appTracked, let type = n.healthType else { return nil }
            return (n.key, type)
        }
    }

    static func payload(_ input: Input, from: Date, to: Date) -> [String: Any] {
        let calendar = input.calendar
        let zone = MetricsReference.Zone(calendar: calendar)
        let firstDay = calendar.startOfDay(for: from)
        var lastDay = calendar.startOfDay(for: to)
        if let cap = calendar.date(byAdding: .day, value: maxRangeDays - 1, to: firstDay), lastDay > cap { lastDay = cap }
        let endExclusive = calendar.date(byAdding: .day, value: 1, to: lastDay) ?? lastDay.addingTimeInterval(86_400)
        var days: [Date] = []
        var cursor = firstDay
        while cursor <= lastDay {
            days.append(cursor)
            guard let next = calendar.date(byAdding: .day, value: 1, to: cursor) else { break }
            cursor = next
        }
        let dayText = { (date: Date) in zone.day(of: NutrientTotals.ms(date)).text }
        let startMs = NutrientTotals.ms(firstDay), endMs = NutrientTotals.ms(endExclusive)

        let totals = NutrientTotals(foods: input.foods, supplements: input.supplements, takenDoseMs: input.takenDoseMs, calendar: calendar)
        let rangeFoods = input.foods.filter { $0.timestamp >= firstDay && $0.timestamp < endExclusive }
        let loggedDays = totals.loggedDays.filter {
            guard let m = NutrientsReference.localMidnightMs($0, zone: zone) else { return false }
            return startMs <= m && m < endMs
        }
        let reference = NutrientsReference.data.nutrients
        let keys = reference.map(\.key) + NutrientsReference.data.sportsSupplements.map(\.key)

        // Other apps' values sit at local noon of their day so they land in that day for every average.
        func healthEntries(_ key: String) -> [MetricsReference.Entry] {
            (input.health[key] ?? [:]).compactMap { day, value in
                guard value.isFinite, let midnight = NutrientsReference.localMidnightMs(day, zone: zone),
                      startMs <= midnight, midnight < endMs else { return nil }
                return MetricsReference.Entry(tMs: midnight + 43_200_000, value: value)
            }
        }

        var rows: [[String: Any]] = []
        var noDataLogged: [String] = []
        var notTracked: [String] = []
        for key in keys {
            let range = totals.total(key, from: firstDay, to: endExclusive)
            let health = healthEntries(key)
            let healthSum: Double? = health.isEmpty ? nil : health.reduce(0) { $0 + ($1.value ?? 0) }
            let combined: Double? = range.total == nil && healthSum == nil ? nil : (range.total ?? 0) + (healthSum ?? 0)
            guard let combined else {
                if NutrientsReference.sportsByKey[key] != nil { continue }
                if NutrientsReference.foodTracked(key) { noDataLogged.append(key) } else { notTracked.append(key) }
                continue
            }
            let series = totals.entries(key)
            let average = NutrientsReference.loggedDayAverage(entries: series.food + series.supplements + health, loggedDays: loggedDays,
                                                              startMs: startMs, endMs: endMs, zone: zone, key: key)
            let lines = NutrientsReference.referenceLines(key: key, profile: input.profile, customGoal: input.customGoals[key])
            let spec = NutrientsReference.byKey[key]
            var row: [String: Any] = [
                "key": key,
                "name": spec?.name ?? key,
                "unit": lines.unit ?? NutrientsReference.nutrientUnit(key) ?? "",
                "category": spec?.category ?? "sports",
                "food_tracked": NutrientsReference.foodTracked(key),
                "food": number(range.food),
                "supplements": number(range.supplements),
                "total": round(combined),
                "average_per_logged_day": number(average.average),
                "logged_days": average.loggedDays,
            ]
            if let healthSum { row["health_other_apps"] = round(healthSum) }
            if let recommended = lines.recommended {
                row["recommended"] = round(recommended)
                if lines.recommendedLabel == NutrientsReference.labelGoal {
                    row["recommended_kind"] = "custom_goal"
                } else {
                    row["recommended_kind"] = lines.recommendedKind.map { $0 as Any } ?? NSNull()
                }
                if let avg = average.average, recommended > 0 {
                    row["percent_of_recommended"] = NutrientsReference.roundHalfUpInt(avg / recommended * 100)
                }
            }
            if let limit = lines.limit {
                row["limit"] = round(limit)
                row["limit_kind"] = lines.limitLabel == NutrientsReference.labelGoal ? "custom_goal" : "reference"
                if let avg = average.average { row["above_limit"] = avg > limit }
            }
            if let upper = lines.upperLimit {
                row["upper_limit"] = round(upper)
                row["upper_limit_scope"] = lines.upperLimitScope.map { $0 as Any } ?? NSNull()
                if let note = spec?.upperLimit?.note { row["upper_limit_note"] = note }
                // A supplements-only UL (magnesium, vitamin E, niacin, folic acid) is compared with the supplement part.
                let supplementsOnly = ["supplements_only", "folic_acid_only"].contains(lines.upperLimitScope ?? "")
                let basis: Double? = supplementsOnly
                    ? (range.supplements.map { $0 / Double(max(average.loggedDays, 1)) } ?? 0)
                    : average.average
                if let basis { row["above_upper_limit"] = basis > upper }
            }
            rows.append(row)
        }

        var macroValues: [String: Double] = ["calories": 0, "protein": 0, "carbs": 0, "fat": 0]
        for food in rangeFoods {
            macroValues["calories", default: 0] += Double(food.calories)
            macroValues["protein", default: 0] += food.protein
            macroValues["carbs", default: 0] += food.carbs
            macroValues["fat", default: 0] += food.fat
        }
        let foodDays = Set(rangeFoods.map { dayText($0.timestamp) }).count
        let macros: [String: Any] = Dictionary(uniqueKeysWithValues: [("calories", "calories_kcal"), ("protein", "protein_g"), ("carbs", "carbs_g"), ("fat", "fat_g")].map { key, name in
            let total = macroValues[key] ?? 0
            let value: [String: Any] = rangeFoods.isEmpty
                ? ["total": NSNull(), "average_per_logged_day": NSNull()]
                : ["total": round(total), "average_per_logged_day": round(total / Double(max(foodDays, 1)))]
            return (name, value)
        })

        var payload: [String: Any] = [
            "from": dayText(firstDay),
            "to": dayText(lastDay),
            "days_in_range": days.count,
            "logged_days": loggedDays.count,
            "food_entries": rangeFoods.count,
            "profile": [
                "sex": NutrientsReference.sex(input.profile) ?? "unspecified",
                "age_band": NutrientsReference.bandForAge(input.profile.age),
            ],
            "macros": macros,
            "nutrients": rows,
            "no_data_logged": noDataLogged,
            "not_tracked_by_food_log": notTracked,
            "notes": notes,
        ]
        if days.count <= maxDailyRows {
            let withData = Set(rows.compactMap { $0["key"] as? String })
            payload["days"] = days.map { day -> [String: Any] in
                let text = dayText(day)
                let dayTotals = totals.totals(Array(withData), on: day)
                var values: [String: Double] = [:]
                for key in withData {
                    let healthValue = input.health[key]?[text]
                    let value: Double? = dayTotals[key]?.total == nil && healthValue == nil ? nil : (dayTotals[key]?.total ?? 0) + (healthValue ?? 0)
                    if let value { values[key] = round(value) }
                }
                return ["date": text, "food_entries": rangeFoods.filter { calendar.isDate($0.timestamp, inSameDayAs: day) }.count, "totals": values]
            }
        } else {
            payload["days_omitted"] = "Range longer than \(maxDailyRows) days: per-day rows omitted; ask for a shorter range for day-by-day detail."
        }
        return payload
    }

    private static func round(_ value: Double) -> Double { NutrientsReference.roundTo(value, 2) }

    private static func number(_ value: Double?) -> Any { value.map { round($0) } ?? NSNull() }
}
