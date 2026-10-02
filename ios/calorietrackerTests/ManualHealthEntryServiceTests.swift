import Foundation
import HealthKit
import Testing
@testable import calorietracker

/// Manual blood glucose / body temperature entries (docs/health-data.md §2.2).
struct ManualHealthEntryServiceTests {
    private typealias F = HealthTestFixtures
    private typealias S = ManualHealthEntryService

    private static let fixedID = UUID(uuidString: "9F1C2B3A-1111-4222-8333-944455556666")!
    private static let now = F.date(2026, 9, 10, 18, 0)

    private func extra(_ row: HealthSampleRow) -> [String: Int] {
        row.extra.compactMapValues { ($0 as? NSNumber)?.intValue }
    }

    // MARK: Conversions and validation

    @Test func glucoseMgPerDlConvertsToMmolPerLiter() {
        #expect(abs(S.mmol(fromMgPerDl: 100) - 5.5500) < 0.001)
        #expect(abs(S.canonicalGlucose(180.182, unit: .mgPerDeciliter) - 10) < 1e-9)
        #expect(S.canonicalGlucose(6.2, unit: .mmolPerLiter) == 6.2)
        #expect(abs(S.mgPerDl(fromMmol: S.mmol(fromMgPerDl: 123)) - 123) < 1e-9)
    }

    @Test func temperatureFahrenheitConvertsToCelsius() {
        #expect(abs(S.celsius(fromFahrenheit: 98.6) - 37) < 1e-9)
        #expect(abs(S.canonicalTemperature(212, celsius: false) - 100) < 1e-9)
        #expect(S.canonicalTemperature(36.6, celsius: true) == 36.6)
        #expect(abs(S.fahrenheit(fromCelsius: 37) - 98.6) < 1e-9)
    }

    @Test func rangesMatchTheContractInBothUnits() {
        #expect(S.isInRange(1.0, kind: .bloodGlucose))
        #expect(S.isInRange(33.3, kind: .bloodGlucose))
        #expect(!S.isInRange(0.9, kind: .bloodGlucose))
        #expect(!S.isInRange(33.4, kind: .bloodGlucose))
        #expect(S.isInRange(S.mmol(fromMgPerDl: 18), kind: .bloodGlucose))
        #expect(S.isInRange(S.mmol(fromMgPerDl: 600), kind: .bloodGlucose))
        #expect(!S.isInRange(S.mmol(fromMgPerDl: 17), kind: .bloodGlucose))
        #expect(!S.isInRange(S.mmol(fromMgPerDl: 602), kind: .bloodGlucose))
        #expect(!S.isInRange(.nan, kind: .bloodGlucose))

        #expect(S.isInRange(34.0, kind: .bodyTemperature))
        #expect(S.isInRange(42.0, kind: .bodyTemperature))
        #expect(!S.isInRange(33.9, kind: .bodyTemperature))
        #expect(!S.isInRange(42.1, kind: .bodyTemperature))
        #expect(S.isInRange(S.celsius(fromFahrenheit: 93.2), kind: .bodyTemperature))
        #expect(S.isInRange(S.celsius(fromFahrenheit: 107.6), kind: .bodyTemperature))
        #expect(!S.isInRange(S.celsius(fromFahrenheit: 108), kind: .bodyTemperature))
    }

    @Test func validationRejectsOutOfRangeFutureAndBadCodes() throws {
        let ok = ManualHealthEntry(kind: .bloodGlucose, value: 5.5, date: Self.now)
        try S.validate(ok, now: Self.now)
        #expect(throws: ManualHealthEntryError.outOfRange) {
            try S.validate(ManualHealthEntry(kind: .bloodGlucose, value: 40, date: Self.now), now: Self.now)
        }
        #expect(throws: ManualHealthEntryError.futureTime) {
            try S.validate(ManualHealthEntry(kind: .bodyTemperature, value: 37, date: Self.now.addingTimeInterval(3600)), now: Self.now)
        }
        var badRelation = ok
        badRelation.relationToMeal = 5
        #expect(throws: ManualHealthEntryError.invalidCode) { try S.validate(badRelation, now: Self.now) }
        var badLocation = ManualHealthEntry(kind: .bodyTemperature, value: 37, date: Self.now)
        badLocation.measurementLocation = 11
        #expect(throws: ManualHealthEntryError.invalidCode) { try S.validate(badLocation, now: Self.now) }
    }

    @Test func decimalParsingAcceptsCommaAndDot() {
        #expect(S.parseDecimal("5,6", locale: Locale(identifier: "de_DE")) == 5.6)
        #expect(S.parseDecimal("5.6", locale: Locale(identifier: "en_US")) == 5.6)
        #expect(S.parseDecimal("  ") == nil)
        #expect(S.parseDecimal("abc") == nil)
    }

    // MARK: Row layout

    @Test func glucoseRowLayout() throws {
        var entry = ManualHealthEntry(id: Self.fixedID, kind: .bloodGlucose, value: S.mmol(fromMgPerDl: 110), date: Self.now)
        entry.relationToMeal = 3
        let row = S.row(for: entry, sourceID: F.bundleID, calendar: F.calendar, nowMs: 42)
        #expect(row.id == "local:9f1c2b3a-1111-4222-8333-944455556666")
        #expect(row.origin == HealthRowOrigin.localAdapter.rawValue)
        #expect(row.typeID == "blood_glucose")
        #expect(row.unit == "mmol/L")
        #expect(abs((row.value ?? 0) - 110 / 18.0182) < 1e-9)
        #expect(row.categoryValue == 2, "default specimen = capillary_blood")
        #expect(extra(row) == ["relation_to_meal": 3, "meal_type": 0])
        #expect(row.recordingMethod == 3)
        #expect(row.startMs == row.endMs)
        #expect(row.startMs == F.ms(Self.now))
        #expect(row.startOffsetS == 7200, "Berlin summer time")
        #expect(row.localDay == "2026-09-10")
        #expect(row.sourceID == F.bundleID)
        #expect(row.updatedMs == 42)
        #expect(row.count == 1)
        #expect(row.deleted == 0)
    }

    @Test func temperatureRowLayout() {
        var entry = ManualHealthEntry(id: Self.fixedID, kind: .bodyTemperature, value: S.celsius(fromFahrenheit: 100.4), date: Self.now)
        entry.measurementLocation = 4
        let row = S.row(for: entry, sourceID: F.bundleID, calendar: F.calendar, nowMs: 1)
        #expect(row.typeID == "body_temperature")
        #expect(row.unit == "degC")
        #expect(abs((row.value ?? 0) - 38) < 1e-9)
        #expect(row.categoryValue == 0)
        #expect(extra(row) == ["measurement_location": 4])
        #expect(row.origin == 2)
        #expect(row.id.hasPrefix("local:"))
    }

    @Test func onlyLiveManualRowsAreDeletable() {
        let entry = ManualHealthEntry(id: Self.fixedID, kind: .bloodGlucose, value: 5, date: Self.now)
        let row = S.row(for: entry, sourceID: F.bundleID, calendar: F.calendar, nowMs: 1)
        #expect(S.isDeletable(row))
        var platform = row
        platform.origin = HealthRowOrigin.platform.rawValue
        #expect(!S.isDeletable(platform))
        var imported = row
        imported.origin = HealthRowOrigin.fileImport.rawValue
        #expect(!S.isDeletable(imported))
        var otherType = row
        otherType.typeID = "weight"
        #expect(!S.isDeletable(otherType))
        var tombstoned = row
        tombstoned.deleted = 1
        #expect(!S.isDeletable(tombstoned))
        #expect(S.tag(fromRowID: row.id) == "9f1c2b3a-1111-4222-8333-944455556666")
        #expect(S.tag(fromRowID: "gh:abc") == nil)
    }

    // MARK: Database

    @Test func saveCommitsRowAndRollupThenDeleteTombstonesAndRebuilds() async throws {
        let db = try await HealthDatabase.inMemory()
        let entry = ManualHealthEntry(id: Self.fixedID, kind: .bloodGlucose, value: 6.0, date: Self.now)
        let saved = try await S.save(entry, database: db, calendar: F.calendar, ownBundleID: F.bundleID, now: Self.now)
        let stored = try #require(try await db.sample(id: saved.id))
        #expect(stored.origin == 2)
        #expect(stored.value == 6.0)
        let rollups = try await db.dailyRollups(type: "blood_glucose", fromDay: "2026-09-10", toDay: "2026-09-10")
        #expect(rollups.count == 1)
        #expect(rollups.first?.avg == 6.0)
        #expect(try await db.allSources().contains { $0.id == F.bundleID && $0.name == "Ayuvo" })

        // A platform row on the same day is never deletable through the manual path.
        let platform = F.row(type: try #require(HealthMetricRegistry.type(id: "blood_glucose")), start: Self.now, value: 8.0)
        try await db.upsertSamples([platform])
        #expect(try await S.delete(rowID: platform.id, database: db, calendar: F.calendar, ownBundleID: F.bundleID) == nil)

        let later = Self.now.addingTimeInterval(60)
        let deleted = try await S.delete(rowID: saved.id, database: db, calendar: F.calendar, ownBundleID: F.bundleID, now: later)
        #expect(deleted?.id == saved.id)
        let tombstone = try #require(try await db.sample(id: saved.id))
        #expect(tombstone.deleted == 1)
        #expect(tombstone.updatedMs == F.ms(later))
        let afterRollups = try await db.dailyRollups(type: "blood_glucose", fromDay: "2026-09-10", toDay: "2026-09-10")
        #expect(afterRollups.first?.avg == 8.0, "the day is rebuilt without the deleted entry")
        #expect(try await S.delete(rowID: saved.id, database: db, calendar: F.calendar, ownBundleID: F.bundleID) == nil, "already deleted")

        // A tombstone wins: re-saving the same id never resurrects it.
        _ = try? await S.save(entry, database: db, calendar: F.calendar, ownBundleID: F.bundleID, now: later)
        #expect(try await db.sample(id: saved.id)?.deleted == 1)
    }

    @Test func saveRejectsInvalidEntriesWithoutWriting() async throws {
        let db = try await HealthDatabase.inMemory()
        let entry = ManualHealthEntry(kind: .bodyTemperature, value: 45, date: Self.now)
        await #expect(throws: ManualHealthEntryError.outOfRange) {
            try await S.save(entry, database: db, calendar: F.calendar, ownBundleID: F.bundleID, now: Self.now)
        }
        #expect(try await db.sampleCount() == 0)
    }

    // MARK: HealthKit

    @Test func healthKitMetadataForGlucose() {
        var entry = ManualHealthEntry(id: Self.fixedID, kind: .bloodGlucose, value: 5.5, date: Self.now)
        entry.relationToMeal = 4
        let zone = TimeZone(identifier: "Europe/Berlin")!
        let metadata = S.healthKitMetadata(for: entry, timeZone: zone)
        #expect(metadata["ayuvo_manual_id"] as? String == "9f1c2b3a-1111-4222-8333-944455556666")
        #expect(metadata[HKMetadataKeyWasUserEntered] as? Bool == true)
        #expect(metadata[HKMetadataKeyTimeZone] as? String == "Europe/Berlin")
        #expect((metadata[HKMetadataKeyBloodGlucoseMealTime] as? NSNumber)?.intValue == HKBloodGlucoseMealTime.postprandial.rawValue)
        #expect(metadata[HKMetadataKeyBodyTemperatureSensorLocation] == nil)

        entry.relationToMeal = 3
        #expect((S.healthKitMetadata(for: entry, timeZone: zone)[HKMetadataKeyBloodGlucoseMealTime] as? NSNumber)?.intValue == HKBloodGlucoseMealTime.preprandial.rawValue)
        for relation in [0, 1, 2] {
            entry.relationToMeal = relation
            #expect(S.healthKitMetadata(for: entry, timeZone: zone)[HKMetadataKeyBloodGlucoseMealTime] == nil)
        }
    }

    @Test func healthKitMetadataForTemperature() {
        var entry = ManualHealthEntry(id: Self.fixedID, kind: .bodyTemperature, value: 37, date: Self.now)
        let zone = TimeZone(identifier: "UTC")!
        let expected: [Int: HKBodyTemperatureSensorLocation] = [
            0: .other, 1: .armpit, 2: .finger, 3: .forehead, 4: .mouth, 5: .rectum,
            6: .temporalArtery, 7: .toe, 8: .ear, 9: .other, 10: .other,
        ]
        for (code, location) in expected {
            entry.measurementLocation = code
            let metadata = S.healthKitMetadata(for: entry, timeZone: zone)
            #expect((metadata[HKMetadataKeyBodyTemperatureSensorLocation] as? NSNumber)?.intValue == location.rawValue, "code \(code)")
            #expect(metadata[HKMetadataKeyBloodGlucoseMealTime] == nil)
            #expect(metadata["ayuvo_manual_id"] as? String == entry.tag)
        }
    }

    @Test func healthKitSampleUsesCanonicalUnits() {
        let glucose = S.healthKitSample(for: ManualHealthEntry(kind: .bloodGlucose, value: 5.5, date: Self.now), timeZone: .current)
        #expect(glucose.quantityType == HKQuantityType(.bloodGlucose))
        let mgdl = glucose.quantity.doubleValue(for: HKUnit.gramUnit(with: .milli).unitDivided(by: .literUnit(with: .deci)))
        #expect(abs(mgdl - 5.5 * 18.0156) < 0.1)
        let temperature = S.healthKitSample(for: ManualHealthEntry(kind: .bodyTemperature, value: 37, date: Self.now), timeZone: .current)
        #expect(abs(temperature.quantity.doubleValue(for: .degreeFahrenheit()) - 98.6) < 1e-6)
        #expect(temperature.startDate == Self.now && temperature.endDate == Self.now)
    }

    // MARK: Reader skip

    @Test func readerSkipsAyuvoManualEntries() {
        let entry = ManualHealthEntry(kind: .bloodGlucose, value: 5.5, date: Self.now)
        let tagged = S.healthKitSample(for: entry, timeZone: .current)
        let plain = HKQuantitySample(
            type: HKQuantityType(.bloodGlucose),
            quantity: HKQuantity(unit: ManualHealthKind.bloodGlucose.healthKitUnit, doubleValue: 5.5),
            start: Self.now, end: Self.now
        )
        let type = HealthMetricRegistry.type(id: "blood_glucose")!
        #expect(HealthSampleMapper.isAyuvoManualEntry(tagged))
        #expect(HealthSampleMapper.isSkippedOwnWrite(tagged))
        #expect(HealthSampleMapper.row(from: tagged, type: type, calendar: F.calendar, nowMs: 1) == nil)
        #expect(HealthSampleMapper.row(from: plain, type: type, calendar: F.calendar, nowMs: 1) != nil)
        #expect(HealthSampleMapper.sources(from: [tagged], nowMs: 1).isEmpty)
        #expect(HealthSampleMapper.manualEntryMetadataKey == "ayuvo_manual_id")
    }
}
