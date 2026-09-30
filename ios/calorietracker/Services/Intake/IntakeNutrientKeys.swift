import Foundation

/// Maps the intake contract's snake keys (`iron_mg`, `vitamin_d_mcg`) to the app's nutrient keys (`iron`,
/// `vitamin_d`, the `OptionalNutrient.jsonKey` / nutrient reference keys) and provides the DRI default goals
/// (docs/intake-metrics.md §3: default goals come from `dri_goals`; goals the person set are never overwritten).
nonisolated enum IntakeNutrientKeys {
    /// `iron_mg` → `iron`, `vitamin_b12_mcg` → `vitamin_b12`.
    static func appKey(_ intakeKey: String) -> String {
        guard let underscore = intakeKey.lastIndex(of: "_") else { return intakeKey }
        let suffix = intakeKey[intakeKey.index(after: underscore)...]
        return ["mg", "mcg", "g"].contains(String(suffix)) ? String(intakeKey[..<underscore]) : intakeKey
    }

    /// `iron` → `iron_mg` for the nutrients the intake config knows (goals, upper limits and lab links).
    static func intakeKey(_ appKey: String, config: IntakeConfig = .shared) -> String? {
        let known = Set(config.dri.goals.keys).union(config.dri.upper.keys).union(config.labLinks.map(\.nutrient))
        return known.first { self.appKey($0) == appKey }
    }

    /// App key → DRI goal.
    static func optionalGoals(from dri: NutrientGoals.DRIResult) -> [String: Double] {
        dri.goals.reduce(into: [:]) { $0[appKey($1.key)] = $1.value }
    }

    /// App key → upper intake level.
    static func upperLimits(from dri: NutrientGoals.DRIResult) -> [String: Double] {
        dri.upper.reduce(into: [:]) { $0[appKey($1.key)] = $1.value }
    }

    /// `sex` for `dri_goals`: male / female, anything else "other" (the higher of the two values).
    static func driSex(_ sex: String?) -> String {
        sex == "male" || sex == "female" ? sex! : "other"
    }

    /// The DRI default goal for a nutrient the DRI table covers, for a profile with a known age; nil otherwise
    /// (callers fall back to the nutrient reference, which also covers limits and unknown ages).
    static func driDefaultGoal(key: String, profile: NutrientsReference.Profile, config: IntakeConfig = .shared) -> Double? {
        guard let age = profile.age, age.isFinite else { return nil }
        let dri = NutrientGoals.driGoals(sex: driSex(profile.sex), age: floor(age), config: config)
        return optionalGoals(from: dri)[key]
    }

    /// The source line shown where goals are explained.
    static var sourceLine: String { IntakeConfig.shared.dri.source }
}
