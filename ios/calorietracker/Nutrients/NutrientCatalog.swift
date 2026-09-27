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

    /// Keys a supplement can list (medication form): reference order, then the sports supplements.
    static var supplementKeys: [String] { NutrientsReference.allKeys }

    static func optionalNutrient(_ key: String) -> OptionalNutrient? { OptionalNutrient(jsonKey: key) }

    static func title(_ key: String) -> String {
        if let nutrient = optionalNutrient(key) { return nutrient.displayName }
        switch key {
        case "monounsaturated_fat": return String(localized: "Monounsaturated Fat")
        case "polyunsaturated_fat": return String(localized: "Polyunsaturated Fat")
        default: return NutrientsReference.byKey[key]?.name ?? key
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
        default: return "leaf.fill"
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
    static func lines(_ key: String, profile: UserProfile?, goals: OptionalNutrientGoals = .current) -> NutrientsReference.Lines {
        let custom = optionalNutrient(key).flatMap { goals.customGoal(for: $0, profile: Self.profile(profile)) }.map(Double.init)
        return NutrientsReference.referenceLines(key: key, profile: Self.profile(profile), customGoal: custom)
    }
}
