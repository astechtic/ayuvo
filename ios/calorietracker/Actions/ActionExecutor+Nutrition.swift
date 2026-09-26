import Foundation

/// `nutrition.*` actions on the food diary (`FoodStore`).
extension ActionExecutor {
    private func foods(in range: ActionMath.DateRange, store: FoodStore) -> [FoodEntry] {
        store.entries.filter { range.contains($0.timestamp) }
    }

    static func nutrientValue(_ nutrient: String, _ entry: FoodEntry) -> Double? {
        switch nutrient {
        case "calories": Double(entry.calories)
        case "protein": entry.protein
        case "carbs": entry.carbs
        case "fat": entry.fat
        case "fiber": entry.fiber
        default: nil
        }
    }

    static func nutrientTarget(_ nutrient: String, profile: UserProfile?) -> Double? {
        guard let profile else { return nil }
        switch nutrient {
        case "calories": return Double(profile.effectiveCalories)
        case "protein": return Double(profile.effectiveProtein)
        case "carbs": return Double(profile.effectiveCarbs)
        case "fat": return Double(profile.effectiveFat)
        default: return nil
        }
    }

    static func nutrientName(_ nutrient: String) -> String {
        switch nutrient {
        case "calories": String(localized: "Calories")
        case "protein": String(localized: "Protein")
        case "carbs": String(localized: "Carbs")
        case "fat": String(localized: "Fat")
        case "fiber": String(localized: "Fiber")
        default: nutrient
        }
    }

    static func foodFields(_ entry: FoodEntry) -> [String: ActionField] {
        [
            "id": .string(entry.id.uuidString), "name": .string(entry.name), "calories": .int(entry.calories),
            "protein_g": .number(ActionMath.roundTo(entry.protein, 1)), "carbs_g": .number(ActionMath.roundTo(entry.carbs, 1)),
            "fat_g": .number(ActionMath.roundTo(entry.fat, 1)), "meal": .string(entry.mealType.rawValue),
            "t_ms": .int(Int(ActionEnvironment.ms(entry.timestamp))),
        ]
    }

    /// One row of the "Detailed Nutrition" section of Nutrition Details, in the same order.
    struct DetailNutrient {
        let key: String
        let name: String
        let unit: String
        let goal: OptionalNutrient?
        let value: (FoodEntry) -> Double?

        /// Field name in the action result, e.g. `sodium_mg`, `vitamin_d_mcg`.
        var field: String { "\(key)_\(unit)" }
    }

    static func optionalNutrientValue(_ nutrient: OptionalNutrient, _ e: FoodEntry) -> Double? {
        switch nutrient {
        case .fiber: e.fiber
        case .sugar: e.sugar
        case .addedSugar: e.addedSugar
        case .saturatedFat: e.saturatedFat
        case .cholesterol: e.cholesterol
        case .caffeine: e.caffeine
        case .sodium: e.sodium
        case .potassium: e.potassium
        case .transFat: e.transFat
        case .calcium: e.calcium
        case .iron: e.iron
        case .magnesium: e.magnesium
        case .zinc: e.zinc
        case .vitaminA: e.vitaminA
        case .vitaminC: e.vitaminC
        case .vitaminD: e.vitaminD
        case .vitaminB12: e.vitaminB12
        case .vitaminE: e.vitaminE
        case .vitaminK: e.vitaminK
        case .folate: e.folate
        case .omega3: e.omega3
        case .creatine, .betaAlanine, .lCitrulline, .lCarnitine, .lArginine, .taurine, .betaine, .hmb:
            e.supplementalNutrients[nutrient.rawValue]
        }
    }

    static var detailNutrients: [DetailNutrient] {
        func optional(_ n: OptionalNutrient) -> DetailNutrient {
            DetailNutrient(key: n.jsonKey, name: n.displayName, unit: n.unit, goal: n) { optionalNutrientValue(n, $0) }
        }
        let head: [OptionalNutrient] = [.sugar, .addedSugar, .fiber, .saturatedFat]
        let tail: [OptionalNutrient] = [
            .cholesterol, .caffeine, .sodium, .potassium, .transFat, .calcium, .iron, .magnesium, .zinc,
            .vitaminA, .vitaminC, .vitaminD, .vitaminB12, .vitaminE, .vitaminK, .folate, .omega3,
        ]
        return head.map(optional)
            + [
                DetailNutrient(key: "monounsaturated_fat", name: String(localized: "Monounsaturated Fat"), unit: "g", goal: nil) { $0.monounsaturatedFat },
                DetailNutrient(key: "polyunsaturated_fat", name: String(localized: "Polyunsaturated Fat"), unit: "g", goal: nil) { $0.polyunsaturatedFat },
            ]
            + tail.map(optional)
            + SupplementalNutrient.allCases.map { optional($0.optionalNutrient) }
    }

    /// Everything Nutrition Details shows: calories and macros with targets, water (when tracked) and
    /// every detailed nutrient with its goal. Siri reads the logged values; Shortcuts gets every field.
    func nutritionSummary(_ v: ActionValidation) throws -> ActionResult {
        let r = try range(v)
        let entries = foods(in: r, store: env.foodStore())
        let calories = entries.reduce(0) { $0 + $1.calories }
        let protein = entries.reduce(0) { $0 + $1.protein }
        let carbs = entries.reduce(0) { $0 + $1.carbs }
        let fat = entries.reduce(0) { $0 + $1.fat }
        let profile = env.profile
        let calorieTarget = Self.nutrientTarget("calories", profile: profile)
        let proteinTarget = Self.nutrientTarget("protein", profile: profile)
        let carbsTarget = Self.nutrientTarget("carbs", profile: profile)
        let fatTarget = Self.nutrientTarget("fat", profile: profile)
        let single = r.isSingleDay
        let progress = single ? ActionMath.progress(value: Double(calories), target: calorieTarget) : nil
        let goals = OptionalNutrientGoals.decoded(from: env.defaults.data(forKey: OptionalNutrientGoals.storageKey) ?? Data())

        func amount(_ value: Double, _ target: Double?, _ unit: String, digits: Int = 0) -> String {
            if single, let target, target > 0 {
                return String(localized: "\(Self.number(value, digits: digits)) of \(Self.number(target)) \(unit)")
            }
            return "\(Self.number(value, digits: digits)) \(unit)"
        }

        var fields: [String: ActionField] = [
            "value": .int(calories), "unit": .string("kcal"),
            "calories": .int(calories), "protein_g": .number(ActionMath.roundTo(protein, 1)),
            "carbs_g": .number(ActionMath.roundTo(carbs, 1)), "fat_g": .number(ActionMath.roundTo(fat, 1)),
            "calorie_target": .optional(calorieTarget), "protein_target_g": .optional(proteinTarget),
            "carbs_target_g": .optional(carbsTarget), "fat_target_g": .optional(fatTarget),
            "calories_remaining": .optional(progress?.remaining), "entry_count": .int(entries.count),
        ]
        var lines = [
            String(localized: "Calories: \(amount(Double(calories), calorieTarget, "kcal"))"),
            String(localized: "Protein: \(amount(protein, proteinTarget, "g", digits: 1))"),
            String(localized: "Carbs: \(amount(carbs, carbsTarget, "g", digits: 1))"),
            String(localized: "Fat: \(amount(fat, fatTarget, "g", digits: 1))"),
        ]

        var waterSpoken: String?
        if env.defaults.bool(forKey: WaterSettings.enabledKey) {
            let water = env.waterStore().entries.filter { r.contains($0.date) }.reduce(0) { $0 + $1.milliliters }
            let goal = single ? env.waterGoalMl : nil
            fields["water_ml"] = .int(water)
            fields["water_goal_ml"] = .optional(goal)
            let text = goal.map { String(localized: "\(waterText(water)) of \(waterText($0))") } ?? waterText(water)
            lines.append(String(localized: "Water: \(text)"))
            waterSpoken = String(localized: "Water \(text).")
        }

        var nutrients: [ActionField] = []
        var logged: [String] = []
        for nutrient in Self.detailNutrients {
            let total = entries.reduce(0) { $0 + (nutrient.value($1) ?? 0) }
            let rounded = ActionMath.roundTo(total, 1)
            let goal = nutrient.goal.map { Double(goals.goal(for: $0)) }
            fields[nutrient.field] = .number(rounded)
            nutrients.append(.object([
                "key": .string(nutrient.key), "name": .string(nutrient.name), "value": .number(rounded),
                "unit": .string(nutrient.unit), "goal": .optional(goal),
            ]))
            lines.append("\(nutrient.name): \(amount(total, goal, nutrient.unit, digits: 1))")
            if rounded > 0 { logged.append("\(nutrient.name.lowercased()) \(Self.number(total, digits: 1)) \(nutrient.unit)") }
        }
        fields["nutrients"] = .list(nutrients)
        fields["details_text"] = .string(lines.joined(separator: "\n"))

        let when = Self.rangeText(v.string("range"))
        var dialog: String
        if entries.isEmpty {
            dialog = String(localized: "No food logged \(when).")
        } else {
            dialog = String(localized: "\(when.capitalized): \(amount(Double(calories), calorieTarget, "kcal")).")
            if let remaining = progress?.remaining, v.string("range") ?? "today" == "today" {
                dialog += " " + String(localized: "\(Self.number(remaining)) kcal left.")
            }
            dialog += " " + String(localized: "Protein \(amount(protein, proteinTarget, "g")), carbs \(amount(carbs, carbsTarget, "g")), fat \(amount(fat, fatTarget, "g")).")
        }
        if let waterSpoken { dialog += " " + waterSpoken }
        if !logged.isEmpty {
            dialog += " " + String(localized: "Also \(logged.joined(separator: ", ")).")
        }
        return ActionResult(actionID: v.actionID, fields: fields, dialog: dialog)
    }

    func nutrientGet(_ v: ActionValidation) throws -> ActionResult {
        let r = try range(v)
        let nutrient = v.string("nutrient") ?? "calories"
        let aggregation = v.string("aggregation") ?? "sum"
        let entries = foods(in: r, store: env.foodStore())
        var samples = entries.map { ActionMath.Sample(tMs: ActionEnvironment.ms($0.timestamp), value: Self.nutrientValue(nutrient, $0)) }
        if aggregation == "average" {
            // "average" is per logged day: the mean of the daily totals.
            var totals: [MetricsReference.LocalDay: Double] = [:]
            for sample in samples { if let value = sample.value { totals[env.zone.day(of: sample.tMs), default: 0] += value } }
            samples = totals.keys.sorted().map { ActionMath.Sample(tMs: env.zone.midnight($0), value: totals[$0]) }
        }
        let result = ActionMath.aggregate(samples, aggregation)
        let value = result.value ?? (aggregation == "sum" || aggregation == "count" ? 0 : nil)
        let unit = aggregation == "count" ? "count" : (nutrient == "calories" ? "kcal" : "g")
        let target = Self.nutrientTarget(nutrient, profile: env.profile)
        let dailyComparable = (aggregation == "sum" && r.isSingleDay) || aggregation == "average"
        let progress = (dailyComparable && value != nil) ? ActionMath.progress(value: value ?? 0, target: target) : nil
        let name = Self.nutrientName(nutrient)
        let when = Self.rangeText(v.string("range"))
        var dialog: String
        if let value {
            let amount = aggregation == "count" ? "\(Int(value))" : "\(Self.number(value)) \(unit)"
            dialog = aggregation == "average"
                ? String(localized: "\(name) \(when): \(amount) a day on average.")
                : String(localized: "\(name) \(when): \(amount).")
            if let target = progress?.target, let remaining = progress?.remaining {
                dialog += " " + String(localized: "Target \(Self.number(target)) \(unit), \(Self.number(remaining)) \(unit) to go.")
            }
        } else {
            dialog = String(localized: "No \(name.lowercased()) logged \(when).")
        }
        return ActionResult(actionID: v.actionID, fields: [
            "value": .optional(value), "unit": .string(unit), "target": .optional(progress?.target ?? target),
            "remaining": .optional(progress?.remaining), "percent": .optional(progress?.percent),
        ], dialog: dialog)
    }

    func mealsList(_ v: ActionValidation) throws -> ActionResult {
        let r = try range(v)
        let meal = v.string("meal")
        let limit = v.int("limit") ?? 50
        let entries = foods(in: r, store: env.foodStore())
            .filter { meal == nil || $0.mealType.rawValue == meal }
            .sorted { $0.timestamp > $1.timestamp }
            .prefix(limit)
        let items = entries.map(Self.foodFields)
        let names = entries.prefix(5).map(\.name).joined(separator: ", ")
        let dialog = entries.isEmpty
            ? String(localized: "Nothing logged \(Self.rangeText(v.string("range"))).")
            : String(localized: "\(entries.count) foods: \(names)\(entries.count > 5 ? "…" : "").")
        return ActionResult(actionID: v.actionID, fields: ["count": .int(items.count)], items: Array(items), dialog: dialog)
    }

    func nutritionTargets(_ v: ActionValidation) -> ActionResult {
        let profile = env.profile
        let calories = Self.nutrientTarget("calories", profile: profile)
        let protein = Self.nutrientTarget("protein", profile: profile)
        let carbs = Self.nutrientTarget("carbs", profile: profile)
        let fat = Self.nutrientTarget("fat", profile: profile)
        let dialog = calories.map {
            String(localized: "Daily targets: \(Self.number($0)) kcal, \(Self.number(protein ?? 0)) g protein, \(Self.number(carbs ?? 0)) g carbs, \(Self.number(fat ?? 0)) g fat.")
        } ?? String(localized: "Set up your profile in Ayuvo to get nutrition targets.")
        return ActionResult(actionID: v.actionID, fields: [
            "value": .optional(calories), "calories": .optional(calories), "protein_g": .optional(protein),
            "carbs_g": .optional(carbs), "fat_g": .optional(fat),
        ], dialog: dialog)
    }

    // MARK: - Logging

    func foodLog(_ v: ActionValidation) async throws -> ActionResult {
        let meal = v.string("meal").flatMap(MealType.init(rawValue:)) ?? .currentMeal
        var entry: FoodEntry
        if v.ai, let description = v.string("description") {
            do {
                entry = Self.foodEntry(from: try await env.analyzeFood(description), at: env.nowDate, meal: meal)
            } catch let error as ActionError {
                throw error
            } catch {
                let message = (error as? LocalizedError)?.errorDescription ?? error.localizedDescription
                throw ActionError.unavailable(String(localized: "Ayuvo couldn't estimate that food. \(message)"))
            }
        } else {
            let name = v.string("name") ?? v.string("description") ?? String(localized: "Food")
            entry = FoodEntry(
                name: name, calories: Int((v.double("calories") ?? 0).rounded()),
                protein: v.double("protein") ?? 0, carbs: v.double("carbs") ?? 0, fat: v.double("fat") ?? 0,
                timestamp: env.nowDate, source: .manual, mealType: meal
            )
        }
        let store = env.foodStore()
        guard store.addEntry(entry) else {
            if store.isPersistenceBlocked {
                throw ActionError.unavailable(String(localized: "Your food diary can't be changed right now. Open Ayuvo and try again."))
            }
            throw ActionError.conflict(String(localized: "End or cancel your active fast before logging food."))
        }
        broadcastFoodChange(store, added: entry)
        return ActionResult(actionID: v.actionID, fields: Self.foodFields(entry).merging(["value": .int(entry.calories)]) { a, _ in a },
                            dialog: String(localized: "Logged \(entry.name): \(entry.calories) kcal, \(MacroValueFormatter.string(entry.protein)) g protein."))
    }

    /// Favourites first, then the diary (a recent entry id).
    func savedFood(id: String, store: FoodStore) -> FoodEntry? {
        store.favorites.first { $0.id.uuidString == id || $0.favoriteKey == id }
            ?? store.entries.first { $0.id.uuidString == id }
    }

    func foodLogSaved(_ v: ActionValidation) throws -> ActionResult {
        let store = env.foodStore()
        guard let template = savedFood(id: v.string("food") ?? "", store: store) else {
            throw ActionError.notFound(String(localized: "That saved food isn't in Ayuvo any more."))
        }
        let meal = v.string("meal").flatMap(MealType.init(rawValue:)) ?? .currentMeal
        var entry = template.duplicatedForLogging(at: env.nowDate, mealType: meal)
        let servings = v.double("servings") ?? 1
        if abs(servings - 1) > 0.0001 { entry = Self.scaled(entry, by: servings) }
        guard store.addEntry(entry) else {
            throw ActionError.conflict(String(localized: "End or cancel your active fast before logging food."))
        }
        broadcastFoodChange(store, added: entry)
        return ActionResult(actionID: v.actionID, fields: Self.foodFields(entry).merging(["value": .int(entry.calories)]) { a, _ in a },
                            dialog: String(localized: "Logged \(entry.name): \(entry.calories) kcal."))
    }

    static func scaled(_ entry: FoodEntry, by factor: Double) -> FoodEntry {
        var e = entry
        e.calories = Int((Double(entry.calories) * factor).rounded())
        e.protein *= factor
        e.carbs *= factor
        e.fat *= factor
        let optionals: [WritableKeyPath<FoodEntry, Double?>] = [
            \.sugar, \.addedSugar, \.fiber, \.saturatedFat, \.monounsaturatedFat, \.polyunsaturatedFat, \.cholesterol,
            \.caffeine, \.sodium, \.potassium, \.transFat, \.calcium, \.iron, \.magnesium, \.zinc, \.vitaminA, \.vitaminC,
            \.vitaminD, \.vitaminB12, \.vitaminE, \.vitaminK, \.folate, \.omega3, \.servingSizeGrams,
        ]
        for path in optionals { e[keyPath: path] = entry[keyPath: path].map { $0 * factor } }
        e.supplementalNutrients = entry.supplementalNutrients.mapValues { $0 * factor }
        e.selectedServingQuantity = entry.selectedServingQuantity.map { $0 * factor }
        return e
    }

    static func foodEntry(from analysis: GeminiService.FoodAnalysis, at date: Date, meal: MealType) -> FoodEntry {
        FoodEntry(
            name: analysis.name,
            calories: analysis.calories,
            protein: analysis.protein,
            carbs: analysis.carbs,
            fat: analysis.fat,
            timestamp: date,
            emoji: analysis.emoji,
            source: .textInput,
            mealType: meal,
            sugar: analysis.sugar,
            addedSugar: analysis.addedSugar,
            fiber: analysis.fiber,
            saturatedFat: analysis.saturatedFat,
            monounsaturatedFat: analysis.monounsaturatedFat,
            polyunsaturatedFat: analysis.polyunsaturatedFat,
            cholesterol: analysis.cholesterol,
            caffeine: analysis.caffeine,
            supplementalNutrients: analysis.supplementalNutrients,
            sodium: analysis.sodium,
            potassium: analysis.potassium,
            transFat: analysis.transFat,
            calcium: analysis.calcium,
            iron: analysis.iron,
            magnesium: analysis.magnesium,
            zinc: analysis.zinc,
            vitaminA: analysis.vitaminA,
            vitaminC: analysis.vitaminC,
            vitaminD: analysis.vitaminD,
            vitaminB12: analysis.vitaminB12,
            vitaminE: analysis.vitaminE,
            vitaminK: analysis.vitaminK,
            folate: analysis.folate,
            omega3: analysis.omega3,
            servingSizeGrams: analysis.servingSizeGrams,
            servingUnitOptions: analysis.servingUnitOptions,
            selectedServingUnit: analysis.selectedServingUnit,
            selectedServingQuantity: analysis.selectedServingQuantity
        )
    }
}
