import Foundation
import SwiftUI

/// App-side presentation of nutrient keys (the `OptionalNutrient.jsonKey` values plus the two `FoodEntry`
/// fat fields the reference also lists). Numbers and lines come from `NutrientsReference`.
enum NutrientCatalog {
    /// Nutrition Details order: the 23 reference nutrients as the sheet lists them, then the sports supplements.
    static let detailKeys: [String] = [
        "sugar", "added_sugar", "fiber", "saturated_fat", "monounsaturated_fat", "polyunsaturated_fat", "cholesterol",
        "caffeine", "sodium", "potassium", "trans_fat", "calcium", "iron", "magnesium", "zinc", "vitamin_a", "vitamin_c",
        "vitamin_d", "vitamin_b12", "vitamin_e", "vitamin_k", "folate", "omega_3",
    ] + SupplementalNutrient.allCases.map(\.jsonKey)

    /// Keys a supplement can list (medication form, AI label, archive): every reference nutrient in reference
    /// order (`app_tracked` or not), then the sports supplements (docs/nutrients.md §3).
    static var supplementKeys: [String] { NutrientsReference.allKeys }

    /// `app_tracked: false` reference nutrients (copper, iodine, thiamin, …) in reference order: the food log
    /// never records them; their totals and charts are supplements only.
    static var supplementOnlyKeys: [String] {
        NutrientsReference.data.nutrients.filter { !$0.appTracked }.map(\.key)
    }

    /// True when the food log records `key` (reference `food_tracked`).
    static func foodTracked(_ key: String) -> Bool { NutrientsReference.foodTracked(key) }

    /// The medication form's "Add nutrient" menu, grouped: Vitamins, Minerals, Other (carbs, fats, caffeine)
    /// and Sports Supplements, each in reference order.
    static var supplementSections: [(title: String, keys: [String])] {
        let nutrients = NutrientsReference.data.nutrients
        func keys(_ categories: Set<String>) -> [String] { nutrients.filter { categories.contains($0.category) }.map(\.key) }
        return [
            (String(localized: "Vitamins"), keys(["vitamins"])),
            (String(localized: "Minerals"), keys(["minerals"])),
            (String(localized: "Other"), keys(["carbs", "fats", "other"])),
            (String(localized: "Sports Supplements"), NutrientsReference.data.sportsSupplements.map(\.key)),
        ]
    }

    /// Nutrition Details rows for one day (docs/nutrients.md §5b): the food-log rows (`detailKeys`), plus each
    /// `app_tracked: false` nutrient that an active medication lists or that has a supplement part on that day,
    /// placed after the food rows of its category in reference order.
    static func detailRowKeys(activeNutrientKeys: Set<String>, totals: [String: NutrientsReference.DayTotal]) -> [String] {
        var rows = detailKeys
        let byKey = NutrientsReference.byKey
        for key in supplementOnlyKeys where activeNutrientKeys.contains(key) || totals[key]?.supplements != nil {
            let category = byKey[key]?.category
            let anchor = rows.lastIndex { byKey[$0]?.category == category } ?? (rows.lastIndex { byKey[$0] != nil } ?? rows.count - 1)
            rows.insert(key, at: anchor + 1)
        }
        return rows
    }

    /// Goal shown on a Nutrition Details row: the user's goal for food-log nutrients, the reference
    /// `default_goal` for `app_tracked: false` nutrients (no custom goal), nil otherwise.
    static func detailGoal(_ key: String, profile: UserProfile?, goals: OptionalNutrientGoals) -> Int? {
        if let nutrient = optionalNutrient(key) { return goals.goal(for: nutrient, profile: Self.profile(profile)) }
        guard NutrientsReference.isSupplementOnly(key) else { return nil }
        return NutrientsReference.defaultGoalInt(NutrientsReference.defaultGoal(key: key, profile: Self.profile(profile)))
    }

    /// Every key Nutrition Details may show (for one totals pass).
    static var allDetailKeys: [String] { detailKeys + supplementOnlyKeys }

    static func optionalNutrient(_ key: String) -> OptionalNutrient? { OptionalNutrient(jsonKey: key) }

    static func title(_ key: String) -> String {
        if let nutrient = optionalNutrient(key) { return nutrient.displayName }
        switch key {
        case "monounsaturated_fat": return String(localized: "Monounsaturated Fat")
        case "polyunsaturated_fat": return String(localized: "Polyunsaturated Fat")
        case "phosphorus": return String(localized: "Phosphorus", comment: "Nutrient name")
        case "chloride": return String(localized: "Chloride", comment: "Nutrient name")
        case "copper": return String(localized: "Copper", comment: "Nutrient name")
        case "manganese": return String(localized: "Manganese", comment: "Nutrient name")
        case "selenium": return String(localized: "Selenium", comment: "Nutrient name")
        case "chromium": return String(localized: "Chromium", comment: "Nutrient name")
        case "molybdenum": return String(localized: "Molybdenum", comment: "Nutrient name")
        case "iodine": return String(localized: "Iodine", comment: "Nutrient name")
        case "vitamin_b6": return String(localized: "Vitamin B6", comment: "Nutrient name")
        case "thiamin": return String(localized: "Thiamin", comment: "Nutrient name")
        case "riboflavin": return String(localized: "Riboflavin", comment: "Nutrient name")
        case "niacin": return String(localized: "Niacin", comment: "Nutrient name")
        case "biotin": return String(localized: "Biotin", comment: "Nutrient name")
        case "pantothenic_acid": return String(localized: "Pantothenic Acid", comment: "Nutrient name")
        default: return NutrientsReference.byKey[key]?.name ?? key
        }
    }

    /// `items` in supplement-key order (reference order, then sports); unknown keys last.
    static func referenceOrdered<T>(_ items: [T], key: (T) -> String) -> [T] {
        let order = Dictionary(supplementKeys.enumerated().map { ($1, $0) }, uniquingKeysWith: { first, _ in first })
        return items.enumerated().sorted {
            let l = order[key($0.element)] ?? Int.max, r = order[key($1.element)] ?? Int.max
            return l != r ? l < r : $0.offset < $1.offset
        }.map(\.element)
    }

    /// Name as supplement labels print it, for the medication form, review sheet and detail card: the B
    /// vitamins also show their number ("Vitamin B1 (Thiamin)"); every other key is `title`.
    static func labelTitle(_ key: String) -> String {
        switch key {
        case "thiamin": return String(localized: "Vitamin B1 (Thiamin)")
        case "riboflavin": return String(localized: "Vitamin B2 (Riboflavin)")
        case "niacin": return String(localized: "Vitamin B3 (Niacin)")
        case "pantothenic_acid": return String(localized: "Vitamin B5 (Pantothenic Acid)")
        case "biotin": return String(localized: "Vitamin B7 (Biotin)")
        case "folate": return String(localized: "Folate (B9)")
        default: return title(key)
        }
    }

    /// Short row label for Nutrition Details (keeps the sheet's existing wording for the two fats).
    static func rowTitle(_ key: String) -> String {
        switch key {
        case "monounsaturated_fat": return String(localized: "Mono Unsat. Fat")
        case "polyunsaturated_fat": return String(localized: "Poly Unsat. Fat")
        default: return title(key)
        }
    }

    static func unit(_ key: String) -> String {
        NutrientsReference.nutrientUnit(key) ?? optionalNutrient(key)?.unit ?? NutrientsReference.byKey[key]?.unit ?? "g"
    }

    static func iconName(_ key: String) -> String {
        if let nutrient = optionalNutrient(key) { return nutrient.iconName }
        switch key {
        case "monounsaturated_fat": return "drop"
        case "polyunsaturated_fat": return "drop.halffull"
        default:
            switch NutrientsReference.byKey[key]?.category {
            case "minerals": return "circle.hexagongrid.fill"
            case "vitamins": return "pills.fill"
            default: return "leaf.fill"
            }
        }
    }

    static func slug(_ key: String) -> String? { NutrientsReference.byKey[key]?.slug }

    static func isSports(_ key: String) -> Bool { NutrientsReference.sportsByKey[key] != nil }

    /// "—" when there is no value (never 0 for missing data); otherwise up to 2 decimals below 10,
    /// 1 below 100 and none above.
    static func number(_ value: Double?) -> String {
        guard let value, value.isFinite else { return "—" }
        let magnitude = abs(value)
        let digits = magnitude >= 100 ? 0 : (magnitude >= 10 ? 1 : 2)
        return HealthUnitFormatting.number(value, fractionDigits: digits)
    }

    /// "1,504 mcg" / "—".
    static func text(_ value: Double?, key: String) -> String {
        guard value != nil else { return "—" }
        return "\(number(value)) \(unit(key))"
    }

    /// Vitamin D may also show its IU equivalent (docs/nutrients.md §8).
    static func iuText(_ value: Double?, key: String) -> String? {
        guard key == "vitamin_d", let value, let iu = NutrientsReference.byKey[key]?.iu?.mcgPerIU, iu > 0 else { return nil }
        return String(localized: "\((value / iu).formatted(.number.precision(.fractionLength(0)))) IU")
    }

    /// The age / sex / calorie goal the reference lines use, from the profile's effective values.
    static func profile(_ profile: UserProfile?) -> NutrientsReference.Profile {
        guard let profile else { return .unknown }
        let sex: String? = switch profile.gender {
        case .male: "male"
        case .female: "female"
        case .other: nil
        }
        return NutrientsReference.Profile(age: Double(profile.age), sex: sex, calorieGoal: Double(profile.effectiveCalories))
    }

    /// Chart rules for reference lines: Recommended / Your goal in the domain colour, Upper limit and Limit in
    /// the warning colour (docs/nutrients.md §5).
    static func chartLines(_ lines: NutrientsReference.Lines) -> [ChartReferenceLine] {
        var out: [ChartReferenceLine] = []
        if let recommended = lines.recommended {
            out.append(ChartReferenceLine(label: localizedLabel(lines.recommendedLabel), value: recommended))
        }
        if let upper = lines.upperLimit {
            out.append(ChartReferenceLine(label: String(localized: "Upper limit"), value: upper, isWarning: true))
        }
        if let limit = lines.limit {
            let isGoal = lines.limitLabel == NutrientsReference.labelGoal
            out.append(ChartReferenceLine(label: localizedLabel(lines.limitLabel), value: limit, isWarning: !isGoal))
        }
        return out
    }

    static func localizedLabel(_ label: String) -> String {
        switch label {
        case NutrientsReference.labelGoal: return String(localized: "Your goal")
        case NutrientsReference.labelLimit: return String(localized: "Limit")
        default: return String(localized: "Recommended")
        }
    }

    /// Recommended / limit / upper-limit lines for the chart, with the user's custom goal when one is set.
    /// `app_tracked: false` nutrients have no custom goal (optionalNutrient is nil): reference lines only.
    static func lines(_ key: String, profile: UserProfile?, goals: OptionalNutrientGoals = .current) -> NutrientsReference.Lines {
        let custom = optionalNutrient(key).flatMap { goals.customGoal(for: $0, profile: Self.profile(profile)) }.map(Double.init)
        return NutrientsReference.referenceLines(key: key, profile: Self.profile(profile), customGoal: custom)
    }
}
