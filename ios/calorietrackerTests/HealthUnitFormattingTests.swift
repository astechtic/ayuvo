import Foundation
import Testing
@testable import calorietracker

@MainActor
struct HealthUnitFormattingTests {
    @Test func durationsReadNaturally() {
        #expect(HealthUnitFormatting.durationText(seconds: 30) == "30 s")
        #expect(HealthUnitFormatting.durationText(seconds: 45 * 60) == "45 min")
        #expect(HealthUnitFormatting.durationText(seconds: 2 * 3600) == "2 h")
        #expect(HealthUnitFormatting.durationText(seconds: 7 * 3600 + 32 * 60 + 10) == "7 h 32 min")
    }

    @Test func bloodPressureReadsAsPair() {
        #expect(HealthUnitFormatting.bloodPressureText(systolic: 120.4, diastolic: 79.6) == "120/80")
        #expect(HealthUnitFormatting.bloodPressureText(systolic: nil, diastolic: 80) == "—")
        #expect(HealthUnitFormatting.bloodPressureText(systolic: 118, diastolic: nil) == "118")
    }

    @Test func glucoseUnitDefaultsByLocaleAndHonoursThePreference() {
        let suite = "HealthUnitFormattingTests-\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suite)!
        defer { defaults.removePersistentDomain(forName: suite) }
        #expect(HealthGlucoseUnit.current(defaults: defaults, locale: Locale(identifier: "en_US")) == .mgPerDeciliter)
        #expect(HealthGlucoseUnit.current(defaults: defaults, locale: Locale(identifier: "de_DE")) == .mmolPerLiter)
        defaults.set(HealthGlucoseUnit.mmolPerLiter.rawValue, forKey: HealthGlucoseUnit.storageKey)
        #expect(HealthGlucoseUnit.current(defaults: defaults, locale: Locale(identifier: "en_US")) == .mmolPerLiter)
    }

    @Test func weightFollowsTheAppUnitPreference() {
        let previous = UserDefaults.standard.string(forKey: WeightUnit.storageKey)
        defer {
            if let previous { UserDefaults.standard.set(previous, forKey: WeightUnit.storageKey) } else { UserDefaults.standard.removeObject(forKey: WeightUnit.storageKey) }
        }
        let weight = HealthTestFixtures.weight
        UserDefaults.standard.set(WeightUnit.kg.rawValue, forKey: WeightUnit.storageKey)
        #expect(HealthUnitFormatting.display(80, type: weight).unit == "kg")
        #expect(HealthUnitFormatting.convertedValue(80, type: weight) == 80)
        UserDefaults.standard.set(WeightUnit.lbs.rawValue, forKey: WeightUnit.storageKey)
        #expect(HealthUnitFormatting.display(80, type: weight).unit == "lb")
        #expect(abs(HealthUnitFormatting.convertedValue(80, type: weight) - 176.37) < 0.01)
    }

    @Test func percentAndCountsKeepCanonicalScale() {
        let spo2 = HealthMetricRegistry.type(id: "blood_oxygen")!
        #expect(HealthUnitFormatting.display(97.4, type: spo2).text == "97.4 %")
        let steps = HealthTestFixtures.steps
        #expect(HealthUnitFormatting.display(8412, type: steps).value.contains("8"))
        #expect(HealthUnitFormatting.text(nil, type: steps) == "—")
        #expect(HealthUnitFormatting.relativeText(Date()) == "Just now")
    }
}
