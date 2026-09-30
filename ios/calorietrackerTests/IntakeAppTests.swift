import Foundation
import Testing
@testable import calorietracker

/// App-side intake wiring (docs/intake-metrics.md): eaten-at persistence, derived nutrition inputs, supplement
/// averaging windows and the labs card copy.
struct IntakeAppTests {
    @Test func foodEntryDecodesWithoutEatenAtAndRoundTripsIt() throws {
        let logged = Date(timeIntervalSince1970: 1_790_000_000)
        var entry = FoodEntry(name: "Oats", calories: 300, protein: 10, carbs: 50, fat: 6, timestamp: logged, source: .manual)
        let legacy = try JSONEncoder().encode(entry)
        #expect(!String(decoding: legacy, as: UTF8.self).contains("eatenAt"))
        let decoded = try JSONDecoder().decode(FoodEntry.self, from: legacy)
        #expect(decoded.eatenAt == nil && decoded.eatenTime == logged)
        entry.eatenAt = logged.addingTimeInterval(-3600)
        let again = try JSONDecoder().decode(FoodEntry.self, from: try JSONEncoder().encode(entry))
        #expect(again.eatenAt == entry.eatenAt)
        #expect(entry.withIngredients([]).eatenAt == entry.eatenAt)
    }

    @Test func teaAndCoffeeDetection() {
        #expect(DerivedIntakeInputs.isTeaOrCoffee(name: "Masala chai", caffeineMg: nil))
        #expect(DerivedIntakeInputs.isTeaOrCoffee(name: "Green tea", caffeineMg: 0))
        #expect(DerivedIntakeInputs.isTeaOrCoffee(name: "Iced coffee", caffeineMg: nil))
        #expect(DerivedIntakeInputs.isTeaOrCoffee(name: "Cola", caffeineMg: 30))
        #expect(!DerivedIntakeInputs.isTeaOrCoffee(name: "Steak and rice", caffeineMg: nil))
    }

    @Test @MainActor func snapshotUsesEatenAtAndDiaryDay() throws {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(identifier: "Asia/Kolkata")!
        let logged = try #require(calendar.date(from: DateComponents(year: 2026, month: 9, day: 20, hour: 21)))
        var entry = FoodEntry(name: "Dal", calories: 400, protein: 20, carbs: 50, fat: 10, timestamp: logged,
                              source: .manual, mealType: .dinner, iron: 4)
        entry.eatenAt = logged.addingTimeInterval(-2 * 3600)
        let snapshot = DerivedIntakeInputs.snapshot(entries: [entry], calendar: calendar)
        let items = try #require(snapshot.itemsByDay["2026-09-20"])
        #expect(items.first?.eatenMs == Int64(entry.eatenAt!.timeIntervalSince1970 * 1000))
        #expect(items.first?.meal == "dinner")
        #expect(snapshot.intakeByDay["2026-09-20"] == 400)
        let day = NutritionDerivation.nutritionDay(.init(timeZone: "Asia/Kolkata", weightKg: 60, items: items,
                                                         bedtimeMs: items[0].eatenMs + 3 * 3_600_000), config: .shared)
        #expect(day.lastMealToBedMin == 180)
    }

    @Test func labMessagesAreAssociational() {
        let labs = [LabLinks.Lab(analyte: "hemoglobin", value: 9.5, refLow: 13, refHigh: 17)]
        let messages = LabNutritionLinks.messages(labs: labs, intakeAvg: ["iron_mg": 6], goals: ["iron_mg": 8],
                                                  supplementNutrients: [])
        #expect(messages.count == 1)
        let text = messages[0].text
        #expect(text.contains("below the report's range"))
        #expect(text.contains("75% of the reference"))
        #expect(text.contains("none of your supplements contain it"))
        #expect(text.hasSuffix("Consider discussing this with your doctor."))
        #expect(!text.lowercased().contains("anaemia") && !text.lowercased().contains("anemia") && !text.lowercased().contains("deficien"))
        #expect(messages[0].nutrientKey == "iron")
    }

    @Test func supplementAverageSpreadsAWeeklyDose() {
        let weekly = NutrientGoals.supplementDaily(amountPerDose: 1500, doses: 4, windowDays: 28, upper: 100)
        #expect(weekly.dailyAverage == 214.3 && weekly.aboveUpper)
    }
}
