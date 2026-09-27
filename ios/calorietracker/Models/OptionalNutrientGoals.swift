import Foundation

enum OptionalNutrient: String, CaseIterable, Identifiable, Codable {
    case fiber
    case sugar
    case addedSugar
    case saturatedFat
    case cholesterol
    case caffeine
    case sodium
    case potassium
    case transFat
    case calcium
    case iron
    case magnesium
    case zinc
    case vitaminA
    case vitaminC
    case vitaminD
    case vitaminB12
    case vitaminE
    case vitaminK
    case folate
    case omega3
    case creatine
    case betaAlanine
    case lCitrulline
    case lCarnitine
    case lArginine
    case taurine
    case betaine
    case hmb

    var id: String { rawValue }

    var jsonKey: String {
        switch self {
        case .fiber: "fiber"
        case .sugar: "sugar"
        case .addedSugar: "added_sugar"
        case .saturatedFat: "saturated_fat"
        case .cholesterol: "cholesterol"
        case .caffeine: "caffeine"
        case .creatine: "creatine"
        case .betaAlanine: "beta_alanine"
        case .lCitrulline: "l_citrulline"
        case .lCarnitine: "l_carnitine"
        case .lArginine: "l_arginine"
        case .taurine: "taurine"
        case .betaine: "betaine"
        case .hmb: "hmb"
        case .sodium: "sodium"
        case .potassium: "potassium"
        case .transFat: "trans_fat"
        case .calcium: "calcium"
        case .iron: "iron"
        case .magnesium: "magnesium"
        case .zinc: "zinc"
        case .vitaminA: "vitamin_a"
        case .vitaminC: "vitamin_c"
        case .vitaminD: "vitamin_d"
        case .vitaminB12: "vitamin_b12"
        case .vitaminE: "vitamin_e"
        case .vitaminK: "vitamin_k"
        case .folate: "folate"
        case .omega3: "omega_3"
        }
    }

    init?(jsonKey: String) {
        switch jsonKey {
        case "fiber": self = .fiber
        case "sugar": self = .sugar
        case "added_sugar": self = .addedSugar
        case "saturated_fat": self = .saturatedFat
        case "cholesterol": self = .cholesterol
        case "caffeine": self = .caffeine
        case "creatine": self = .creatine
        case "beta_alanine": self = .betaAlanine
        case "l_citrulline": self = .lCitrulline
        case "l_carnitine": self = .lCarnitine
        case "l_arginine": self = .lArginine
        case "taurine": self = .taurine
        case "betaine": self = .betaine
        case "hmb": self = .hmb
        case "sodium": self = .sodium
        case "potassium": self = .potassium
        case "trans_fat": self = .transFat
        case "calcium": self = .calcium
        case "iron": self = .iron
        case "magnesium": self = .magnesium
        case "zinc": self = .zinc
        case "vitamin_a": self = .vitaminA
        case "vitamin_c": self = .vitaminC
        case "vitamin_d": self = .vitaminD
        case "vitamin_b12": self = .vitaminB12
        case "vitamin_e": self = .vitaminE
        case "vitamin_k": self = .vitaminK
        case "folate": self = .folate
        case "omega_3": self = .omega3
        default: return nil
        }
    }

    var displayName: String {
        switch self {
        case .fiber: LocalizedDisplayText.text("Fiber", polish: "Błonnik")
        case .sugar: LocalizedDisplayText.text("Sugar", polish: "Cukier")
        case .addedSugar: LocalizedDisplayText.text("Added Sugar", polish: "Cukier dodany")
        case .saturatedFat: LocalizedDisplayText.text("Saturated Fat", polish: "Tłuszcze nasycone")
        case .cholesterol: LocalizedDisplayText.text("Cholesterol", polish: "Cholesterol")
        case .caffeine: LocalizedDisplayText.text("Caffeine", polish: "Kofeina")
        case .creatine: LocalizedDisplayText.text("Creatine", polish: "Kreatyna")
        case .betaAlanine: LocalizedDisplayText.text("Beta-Alanine", polish: "Beta-alanina")
        case .lCitrulline: LocalizedDisplayText.text("L-Citrulline", polish: "L-cytrulina")
        case .lCarnitine: LocalizedDisplayText.text("L-Carnitine", polish: "L-karnityna")
        case .lArginine: LocalizedDisplayText.text("L-Arginine", polish: "L-arginina")
        case .taurine: LocalizedDisplayText.text("Taurine", polish: "Tauryna")
        case .betaine: LocalizedDisplayText.text("Betaine", polish: "Betaina")
        case .hmb: "HMB"
        case .sodium: LocalizedDisplayText.text("Sodium", polish: "Sód")
        case .potassium: LocalizedDisplayText.text("Potassium", polish: "Potas")
        case .transFat: LocalizedDisplayText.text("Trans Fat", polish: "Tłuszcze trans")
        case .calcium: LocalizedDisplayText.text("Calcium", polish: "Wapń")
        case .iron: LocalizedDisplayText.text("Iron", polish: "Żelazo")
        case .magnesium: LocalizedDisplayText.text("Magnesium", polish: "Magnez")
        case .zinc: LocalizedDisplayText.text("Zinc", polish: "Cynk")
        case .vitaminA: LocalizedDisplayText.text("Vitamin A", polish: "Witamina A")
        case .vitaminC: LocalizedDisplayText.text("Vitamin C", polish: "Witamina C")
        case .vitaminD: LocalizedDisplayText.text("Vitamin D", polish: "Witamina D")
        case .vitaminB12: LocalizedDisplayText.text("Vitamin B12", polish: "Witamina B12")
        case .vitaminE: LocalizedDisplayText.text("Vitamin E", polish: "Witamina E")
        case .vitaminK: LocalizedDisplayText.text("Vitamin K", polish: "Witamina K")
        case .folate: LocalizedDisplayText.text("Folate", polish: "Foliany")
        case .omega3: LocalizedDisplayText.text("Omega-3", polish: "Omega-3")
        }
    }

    var shortDisplayName: String {
        switch self {
        case .saturatedFat: LocalizedDisplayText.text("Sat Fat", polish: "Nasyc.")
        case .addedSugar: LocalizedDisplayText.text("Added", polish: "Dodany")
        case .transFat: LocalizedDisplayText.text("Trans", polish: "Trans")
        case .caffeine: LocalizedDisplayText.text("Caffeine", polish: "Kofeina")
        case .vitaminA: LocalizedDisplayText.text("Vit A", polish: "Wit. A")
        case .vitaminC: LocalizedDisplayText.text("Vit C", polish: "Wit. C")
        case .vitaminD: LocalizedDisplayText.text("Vit D", polish: "Wit. D")
        case .vitaminB12: LocalizedDisplayText.text("B12", polish: "B12")
        case .vitaminE: LocalizedDisplayText.text("Vit E", polish: "Wit. E")
        case .vitaminK: LocalizedDisplayText.text("Vit K", polish: "Wit. K")
        case .omega3: LocalizedDisplayText.text("Omega", polish: "Omega")
        case .creatine, .betaAlanine, .lCitrulline, .lCarnitine, .lArginine, .taurine, .betaine, .hmb: displayName
        default: displayName
        }
    }

    var localizedGoalStyle: String {
        switch self {
        case .fiber, .potassium, .calcium, .iron, .magnesium, .zinc, .vitaminA, .vitaminC, .vitaminD, .vitaminB12, .vitaminE, .vitaminK, .folate, .omega3, .creatine, .betaAlanine, .lCitrulline, .lCarnitine, .lArginine, .taurine, .betaine, .hmb:
            LocalizedDisplayText.text("Target", polish: "Cel")
        case .sugar, .addedSugar, .saturatedFat, .cholesterol, .caffeine, .sodium, .transFat:
            LocalizedDisplayText.text("Limit", polish: "Limit")
        }
    }

    var iconName: String {
        switch self {
        case .fiber: "leaf.fill"
        case .sugar: "cube.fill"
        case .addedSugar: "plus.circle.fill"
        case .saturatedFat: "circle.lefthalf.filled"
        case .cholesterol: "heart.fill"
        case .caffeine: "cup.and.saucer.fill"
        case .creatine, .betaAlanine, .lCitrulline, .lCarnitine, .lArginine, .taurine, .betaine, .hmb: "figure.strengthtraining.traditional"
        case .sodium: "circle.grid.2x2.fill"
        case .potassium: "bolt.fill"
        case .transFat: "drop.fill"
        case .calcium: "figure.strengthtraining.traditional"
        case .iron: "bolt.fill"
        case .magnesium: "sparkles"
        case .zinc: "shield.lefthalf.filled"
        case .vitaminA: "a.circle.fill"
        case .vitaminC: "c.circle.fill"
        case .vitaminD: "d.circle.fill"
        case .vitaminB12: "b.circle.fill"
        case .vitaminE: "e.circle.fill"
        case .vitaminK: "k.circle.fill"
        case .folate: "leaf.fill"
        case .omega3: "drop.fill"
        }
    }

    var unit: String {
        switch self {
        case .cholesterol, .caffeine, .sodium, .potassium, .calcium, .iron, .magnesium, .zinc, .vitaminC, .vitaminE: "mg"
        case .vitaminA, .vitaminD, .vitaminB12, .vitaminK, .folate: "mcg"
        default: "g"
        }
    }

    /// The fixed default goal every build stored before goals were personalised (docs/nutrients.md §4.3).
    /// A stored value equal to it counts as "not customised" and follows `personalizedDefaultGoal(for:)`.
    var defaultGoal: Int {
        switch self {
        case .fiber: 30
        case .sugar: 50
        case .addedSugar: 25
        case .saturatedFat: 20
        case .cholesterol: 300
        case .caffeine: 400
        case .sodium: 2_300
        case .potassium: 3_500
        case .transFat: 0
        case .calcium: 1_000
        case .iron: 18
        case .magnesium: 400
        case .zinc: 11
        case .vitaminA: 900
        case .vitaminC: 90
        case .vitaminD: 20
        case .vitaminB12: 3
        case .vitaminE: 15
        case .vitaminK: 120
        case .folate: 400
        case .omega3: 2
        case .creatine, .betaAlanine, .lCitrulline, .lCarnitine, .lArginine, .taurine, .betaine, .hmb: 0
        }
    }

    var range: ClosedRange<Int> {
        switch self {
        case .fiber: 5...100
        case .sugar: 0...200
        case .addedSugar: 0...100
        case .saturatedFat: 0...80
        case .cholesterol: 0...1_000
        case .caffeine: 0...1_000
        case .sodium: 500...6_000
        case .potassium: 1_000...6_000
        case .transFat: 0...10
        case .calcium: 300...2_000
        case .iron: 5...45
        case .magnesium: 100...800
        case .zinc: 3...40
        case .vitaminA: 300...3_000
        case .vitaminC: 20...500
        case .vitaminD: 5...100
        case .vitaminB12: 1...20
        case .vitaminE: 5...100
        case .vitaminK: 30...300
        case .folate: 100...1_000
        case .omega3: 0...10
        case .creatine, .betaAlanine, .lCitrulline, .lCarnitine, .lArginine, .taurine, .betaine, .hmb: 0...50
        }
    }

    var step: Int {
        switch self {
        case .sodium, .potassium, .calcium, .vitaminA, .folate: 50
        case .magnesium: 25
        case .vitaminC, .vitaminK: 10
        case .cholesterol: 50
        case .caffeine: 25
        case .iron, .zinc, .vitaminD, .vitaminB12, .vitaminE, .transFat, .omega3, .creatine, .betaAlanine, .lCitrulline, .lCarnitine, .lArginine, .taurine, .betaine, .hmb: 1
        default: 5
        }
    }

    var goalStyle: String {
        switch self {
        case .fiber, .potassium, .calcium, .iron, .magnesium, .zinc, .vitaminA, .vitaminC, .vitaminD, .vitaminB12, .vitaminE, .vitaminK, .folate, .omega3, .creatine, .betaAlanine, .lCitrulline, .lCarnitine, .lArginine, .taurine, .betaine, .hmb:
            "target"
        case .sugar, .addedSugar, .saturatedFat, .cholesterol, .caffeine, .sodium, .transFat:
            "limit"
        }
    }

    /// Reference style: `target`, `limit` or `info` (sports supplements are `target` without a reference).
    var referenceStyle: String { NutrientsReference.byKey[jsonKey]?.style ?? "target" }

    /// The default goal for this profile (reference `default_goal_int`); nil for info-style nutrients and
    /// sports supplements, which have no default goal line.
    func personalizedDefaultGoal(for profile: NutrientsReference.Profile) -> Int? {
        NutrientsReference.defaultGoalInt(NutrientsReference.defaultGoal(key: jsonKey, profile: profile))
    }

    /// The reference upper intake level for this profile (non-blocking guidance for custom goals); nil when the
    /// reference sets none. Its scope note says what it applies to.
    func referenceUpperLimit(for profile: NutrientsReference.Profile) -> Int? {
        NutrientsReference.referenceLines(key: jsonKey, profile: profile).upperLimit.map { Int($0.rounded()) }
    }

    func customValueDetail(for value: Int) -> String? {
        guard self == .vitaminD else { return nil }
        return "\(value * 40) IU"
    }
}

struct OptionalNutrientGoals: Codable, Equatable {
    static let storageKey = "optionalNutrientGoals"
    static let maximumCustomGoal = 999_999

    private var values: [String: Int]

    init(values: [String: Int] = [:]) {
        self.values = values
    }

    static let defaults = OptionalNutrientGoals(
        values: Dictionary(uniqueKeysWithValues: OptionalNutrient.allCases.map { ($0.rawValue, $0.defaultGoal) })
    )

    static var current: OptionalNutrientGoals {
        guard let data = UserDefaults.standard.data(forKey: storageKey) else {
            return .defaults
        }
        return decoded(from: data)
    }

    static func decoded(from data: Data) -> OptionalNutrientGoals {
        guard !data.isEmpty,
              let goals = try? JSONDecoder().decode(OptionalNutrientGoals.self, from: data)
        else {
            return .defaults
        }
        return goals.mergedWithDefaults()
    }

    var encodedData: Data {
        (try? JSONEncoder().encode(mergedWithDefaults())) ?? Data()
    }

    /// The goal in effect: the user's own value, else the personalised default for `profile`, else 0 (no goal).
    func goal(for nutrient: OptionalNutrient, profile: NutrientsReference.Profile = .unknown) -> Int {
        customGoal(for: nutrient) ?? nutrient.personalizedDefaultGoal(for: profile) ?? 0
    }

    /// The user's own goal, or nil when the stored value is missing or equals the old fixed default.
    func customGoal(for nutrient: OptionalNutrient, profile: NutrientsReference.Profile = .unknown) -> Int? {
        guard let value = values[nutrient.rawValue], value != nutrient.defaultGoal else { return nil }
        return value
    }

    func isCustomized(_ nutrient: OptionalNutrient) -> Bool { customGoal(for: nutrient) != nil }

    /// The raw stored value (the old fixed default when never customised), for portable exports: importing it
    /// elsewhere keeps "not customised" meaning the same.
    func storedGoal(for nutrient: OptionalNutrient) -> Int {
        values[nutrient.rawValue] ?? nutrient.defaultGoal
    }

    /// Stores `value`; a value equal to the personalised default is stored as "not customised" so it keeps
    /// following the profile.
    func settingGoal(_ value: Int, for nutrient: OptionalNutrient, profile: NutrientsReference.Profile) -> OptionalNutrientGoals {
        if let personal = nutrient.personalizedDefaultGoal(for: profile), personal == value {
            return resettingGoal(for: nutrient)
        }
        return settingGoal(value, for: nutrient)
    }

    /// Back to the personalised default.
    func resettingGoal(for nutrient: OptionalNutrient) -> OptionalNutrientGoals {
        var copy = self
        copy.values[nutrient.rawValue] = nutrient.defaultGoal
        return copy
    }

    mutating func setGoal(_ value: Int, for nutrient: OptionalNutrient) {
        values[nutrient.rawValue] = Self.sanitized(value, for: nutrient)
    }

    func settingGoal(_ value: Int, for nutrient: OptionalNutrient) -> OptionalNutrientGoals {
        var copy = self
        copy.setGoal(value, for: nutrient)
        return copy
    }

    func mergedWithDefaults() -> OptionalNutrientGoals {
        var merged = OptionalNutrientGoals.defaults.values
        for nutrient in OptionalNutrient.allCases {
            if let value = values[nutrient.rawValue] {
                merged[nutrient.rawValue] = Self.sanitized(value, for: nutrient)
            }
        }
        return OptionalNutrientGoals(values: merged)
    }

    static func save(_ goals: OptionalNutrientGoals) {
        UserDefaults.standard.set(goals.encodedData, forKey: storageKey)
    }

    static func sanitized(_ value: Int, for nutrient: OptionalNutrient) -> Int {
        min(max(value, 0), maximumCustomGoal)
    }
}
