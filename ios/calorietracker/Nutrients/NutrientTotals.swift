import Foundation

/// The one nutrient-totals path (docs/nutrients.md §4.6, §6): food entries plus the contributions of taken
/// supplement doses, `total(key, day)` → {food, supplements, total}. Totals are recomputed on every read and
/// never stored. Supplements never add calories and are never written to HealthKit.
struct NutrientTotals {
    let foods: [FoodEntry]
    let supplements: [NutrientsReference.SupplementEntry]
    /// Instants of every taken dose (any medicine), for "logged day" counting.
    let takenDoseMs: [Int64]
    let calendar: Calendar

    init(foods: [FoodEntry], supplements: [NutrientsReference.SupplementEntry] = [], takenDoseMs: [Int64] = [], calendar: Calendar = .current) {
        self.foods = foods
        self.supplements = supplements
        self.takenDoseMs = takenDoseMs
        self.calendar = calendar
    }

    init(foodStore: FoodStore, medicationStore: MedicationStore?, calendar: Calendar = .current) {
        self.init(foods: foodStore.entries, supplements: medicationStore?.supplementEntries ?? [],
                  takenDoseMs: medicationStore?.takenDoseMs ?? [], calendar: calendar)
    }

    var zone: MetricsReference.Zone { MetricsReference.Zone(calendar: calendar) }

    static func ms(_ date: Date) -> Int64 { Int64((date.timeIntervalSince1970 * 1000).rounded()) }

    /// A food entry's value for a nutrient key (nil = not recorded): the `OptionalNutrient` fields through
    /// `ActionExecutor.optionalNutrientValue`, plus the mono- and polyunsaturated fat fields.
    static func foodValue(_ key: String, _ entry: FoodEntry) -> Double? {
        switch key {
        case "monounsaturated_fat": return entry.monounsaturatedFat
        case "polyunsaturated_fat": return entry.polyunsaturatedFat
        default: return OptionalNutrient(jsonKey: key).flatMap { ActionExecutor.optionalNutrientValue($0, entry) }
        }
    }

    private func foodInputs(_ keys: [String], _ entries: [FoodEntry]) -> [NutrientsReference.FoodInput] {
        entries.map { entry in
            var values: [String: Double?] = [:]
            for key in keys {
                if let value = Self.foodValue(key, entry) { values[key] = value }
            }
            return NutrientsReference.FoodInput(tMs: Self.ms(entry.timestamp), nutrients: values)
        }
    }

    /// Totals of every key in `keys` for the local day of `date` (keys without data map to all-nil).
    func totals(_ keys: [String], on date: Date) -> [String: NutrientsReference.DayTotal] {
        let dayFoods = foods.filter { calendar.isDate($0.timestamp, inSameDayAs: date) }
        let start = calendar.startOfDay(for: date)
        let end = calendar.date(byAdding: .day, value: 1, to: start) ?? start.addingTimeInterval(86_400)
        let daySupplements = supplements.filter { $0.tMs >= Self.ms(start) && $0.tMs < Self.ms(end) && keys.contains($0.nutrientKey) }
        let day = zone.day(of: Self.ms(date)).text
        let result = NutrientsReference.dayTotals(food: foodInputs(keys, dayFoods), supplements: daySupplements, day: day, zone: zone)
        var out: [String: NutrientsReference.DayTotal] = [:]
        for key in keys { out[key] = result[key] ?? .empty }
        return out
    }

    func total(_ key: String, on date: Date) -> NutrientsReference.DayTotal {
        totals([key], on: date)[key] ?? .empty
    }

    /// Food and supplement parts summed over `[from, to)` (a range of days); nil parts stay nil.
    func total(_ key: String, from: Date, to: Date) -> NutrientsReference.DayTotal {
        let lo = Self.ms(from), hi = Self.ms(to)
        var food: Double?
        for entry in foods {
            let t = Self.ms(entry.timestamp)
            guard t >= lo, t < hi, let value = Self.foodValue(key, entry) else { continue }
            food = (food ?? 0) + value
        }
        var supplement: Double?
        for entry in supplements where entry.nutrientKey == key && entry.tMs >= lo && entry.tMs < hi {
            supplement = (supplement ?? 0) + entry.value
        }
        let total: Double? = food == nil && supplement == nil ? nil : (food ?? 0) + (supplement ?? 0)
        let d = NutrientsReference.amountDecimals
        return NutrientsReference.DayTotal(food: NutrientsReference.roundOptional(food, d),
                                           supplements: NutrientsReference.roundOptional(supplement, d),
                                           total: NutrientsReference.roundOptional(total, d))
    }

    /// Series entries of one nutrient: food entries of that field (null skipped), then its supplement entries.
    func entries(_ key: String) -> (food: [MetricsReference.Entry], supplements: [MetricsReference.Entry]) {
        let food = foods.compactMap { entry -> MetricsReference.Entry? in
            Self.foodValue(key, entry).map { MetricsReference.Entry(tMs: Self.ms(entry.timestamp), value: $0) }
        }
        let supplement = supplements.filter { $0.nutrientKey == key }.map { MetricsReference.Entry(tMs: $0.tMs, value: $0.value) }
        return (food, supplement)
    }

    /// Local days with any food entry or any taken dose (docs/nutrients.md §4.7).
    var loggedDays: [String] {
        let z = zone
        var days = Set(foods.map { z.day(of: Self.ms($0.timestamp)).text })
        for t in takenDoseMs { days.insert(z.day(of: t).text) }
        return days.sorted()
    }

    /// Supplement contributions of one medication today (the medication detail card).
    func supplementTotal(_ key: String, medicationID: String, on date: Date) -> Double? {
        let start = calendar.startOfDay(for: date)
        let end = calendar.date(byAdding: .day, value: 1, to: start) ?? start.addingTimeInterval(86_400)
        let values = supplements.filter { $0.medicationID == medicationID && $0.nutrientKey == key && $0.tMs >= Self.ms(start) && $0.tMs < Self.ms(end) }
        guard !values.isEmpty else { return nil }
        return NutrientsReference.roundTo(values.reduce(0) { $0 + $1.value }, NutrientsReference.amountDecimals)
    }
}
