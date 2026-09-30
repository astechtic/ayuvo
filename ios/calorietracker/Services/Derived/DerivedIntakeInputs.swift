import Foundation

/// Builds the inputs of the intake engines that feed derived metrics (docs/intake-metrics.md §1): one
/// `nutrition_day` per diary day (catalog category "nutrition") and `energy_balance` (energy balance and the
/// adaptive energy estimate). `DerivedMetricsService.compute` is nonisolated, so the food diary reaches it as a
/// Sendable `Snapshot` built on the main actor.
nonisolated enum DerivedIntakeInputs {
    /// The food diary reduced to what the engines read: items per diary day (the log timestamp's local day), each
    /// at its eaten-at time.
    struct Snapshot: Sendable {
        var itemsByDay: [String: [NutritionDerivation.Item]] = [:]

        static let empty = Snapshot()

        /// Logged kcal per diary day (days with at least one entry).
        var intakeByDay: [String: Double] {
            itemsByDay.reduce(into: [:]) { out, kv in
                var t = 0.0
                for i in kv.value { t += i.calories ?? 0 }
                out[kv.key] = t
            }
        }
    }

    /// Tea, coffee or chai: any caffeine, or a name word that says so.
    static func isTeaOrCoffee(name: String, caffeineMg: Double?) -> Bool {
        if (caffeineMg ?? 0) > 0 { return true }
        let words = name.lowercased().split { !$0.isLetter }
        return words.contains { $0.contains("coffee") || $0.contains("chai") || $0 == "tea" || $0 == "teas" || $0.hasSuffix("tea") }
    }

    @MainActor
    static func snapshot(entries: [FoodEntry], calendar: Calendar) -> Snapshot {
        var out = Snapshot()
        for e in entries {
            let day = InsightsDay.key(for: e.timestamp, calendar: calendar)
            let item = NutritionDerivation.Item(
                eatenMs: Int64((e.eatenTime.timeIntervalSince1970 * 1000).rounded()),
                meal: e.mealType.rawValue, calories: Double(e.calories), proteinG: e.protein, carbsG: e.carbs,
                fatG: e.fat, saturatedFatG: e.saturatedFat, fiberG: e.fiber, sodiumMg: e.sodium,
                potassiumMg: e.potassium, ironMg: e.iron, caffeineMg: e.caffeine,
                isTeaOrCoffee: isTeaOrCoffee(name: e.name, caffeineMg: e.caffeine)
            )
            out.itemsByDay[day, default: []].append(item)
        }
        return out
    }

    /// Runs the intake engines for each day of `DerivedMetricsService.compute`.
    struct Runner {
        let snapshot: Snapshot
        let timeZone: String
        let weights: [String: Double]
        let intake: [String: Double]
        let config: IntakeConfig
        /// Measured TDEE per day: stored `tdee` values, replaced by the value computed in this run.
        private(set) var tdee: [String: Double]

        init(db: HealthDatabase, snapshot: Snapshot, timeZone: String, weights: [String: Double], from: String,
             to: String, config: IntakeConfig = .shared) async {
            self.snapshot = snapshot
            self.timeZone = timeZone
            self.weights = weights
            self.intake = snapshot.intakeByDay
            self.config = config
            let stored = snapshot.itemsByDay.isEmpty ? [] : ((try? await db.derivedValues(metric: "tdee", fromDay: from, toDay: to)) ?? [])
            self.tdee = stored.reduce(into: [:]) { out, r in if let v = r.value { out[r.day] = v } }
        }

        /// Adds `nutrition_day` and `energy_balance` for `day`. `bedtimeMs` is the start of the next night's sleep
        /// window (the night whose wake day is `day` + 1).
        mutating func add(into out: inout [String: [String: Any]], day: String, bedtimeMs: Int64?) {
            if let v = out["energy_day"]?["tdee"] as? Double { tdee[day] = v }
            guard !snapshot.itemsByDay.isEmpty else { return }
            if let items = snapshot.itemsByDay[day], !items.isEmpty {
                let input = NutritionDerivation.DayInput(timeZone: timeZone, weightKg: DerivedMetricsService.weightOn(weights, day),
                                                         items: items, bedtimeMs: bedtimeMs)
                out["nutrition_day"] = NutritionDerivation.nutritionDay(input, config: config).jsonObject
            }
            out["energy_balance"] = EnergyBalance.energyBalance(day: day, intake: intake, tdee: tdee, weights: weights,
                                                                config: config).jsonObject
        }
    }
}
