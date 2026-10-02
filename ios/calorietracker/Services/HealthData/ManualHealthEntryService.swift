import Foundation
import HealthKit

/// The two health types the user can log by hand (docs/health-data.md §2.2).
nonisolated enum ManualHealthKind: String, Sendable, CaseIterable, Identifiable {
    case bloodGlucose = "blood_glucose"
    case bodyTemperature = "body_temperature"

    var id: String { rawValue }
    var typeID: String { rawValue }

    /// Canonical storage range (mmol/L or °C).
    var allowedRange: ClosedRange<Double> {
        switch self {
        case .bloodGlucose: return ManualHealthEntryService.glucoseRangeMmol
        case .bodyTemperature: return ManualHealthEntryService.temperatureRangeC
        }
    }

    var quantityTypeIdentifier: HKQuantityTypeIdentifier {
        switch self {
        case .bloodGlucose: return .bloodGlucose
        case .bodyTemperature: return .bodyTemperature
        }
    }

    /// Canonical HealthKit unit (the registry's `unit`).
    var healthKitUnit: HKUnit {
        switch self {
        case .bloodGlucose: return HKUnit.moleUnit(with: .milli, molarMass: HKUnitMolarMassBloodGlucose).unitDivided(by: .liter())
        case .bodyTemperature: return .degreeCelsius()
        }
    }

    init?(typeID: String) {
        self.init(rawValue: typeID)
    }
}

/// One hand-logged reading. `value` is canonical: mmol/L for glucose, °C for temperature.
nonisolated struct ManualHealthEntry: Sendable, Equatable {
    var id: UUID = UUID()
    var kind: ManualHealthKind
    var value: Double
    var date: Date
    /// Glucose: specimen source code (§5; default 2 capillary_blood).
    var specimen: Int = ManualHealthEntryService.defaultSpecimen
    /// Glucose: Health Connect relation to meal (0 unknown, 1 general, 2 fasting, 3 before meal, 4 after meal).
    var relationToMeal: Int = 0
    /// Glucose: Health Connect meal type (0 unknown, 1 breakfast, 2 lunch, 3 dinner, 4 snack).
    var mealType: Int = 0
    /// Temperature: Health Connect `BodyTemperatureMeasurementLocation` (0 unknown … 10 vagina).
    var measurementLocation: Int = 0

    /// `ayuvo_manual_id` metadata value and the uuid part of the row id.
    var tag: String { id.uuidString.lowercased() }
    var rowID: String { ManualHealthEntryService.idPrefix + tag }
}

nonisolated enum ManualHealthEntryError: Error, Equatable {
    case outOfRange
    case futureTime
    case invalidCode
}

/// Pure helpers + the database path for manual health entries (docs/health-data.md §2.2):
/// origin-2 `health_samples` rows with id `local:<uuid>`, committed in one transaction, followed
/// by a rollup rebuild of the touched day. The HealthKit copy is written by `HealthKitManager`
/// with the metadata built here; platform readers skip it by `ayuvo_manual_id`.
nonisolated enum ManualHealthEntryService {
    static let idPrefix = "local:"
    static let metadataKey = HealthSampleMapper.manualEntryMetadataKey
    static let recordingMethodManual = 3
    static let defaultSpecimen = 2
    /// Same factor as `HealthGlucoseUnit.mgPerMmol` (that enum is main-actor isolated).
    static let mgPerMmol = 18.0182

    static let glucoseRangeMmol: ClosedRange<Double> = 1.0...33.3
    static let glucoseRangeMgPerDl: ClosedRange<Double> = 18...600
    static let temperatureRangeC: ClosedRange<Double> = 34.0...42.0
    static let temperatureRangeF: ClosedRange<Double> = 93.2...107.6

    static let relationCodes = 0...4
    static let mealTypeCodes = 0...4
    static let specimenCodes = 0...6
    static let locationCodes = 0...10

    // MARK: Conversions

    static func mmol(fromMgPerDl value: Double) -> Double { value / mgPerMmol }
    static func mgPerDl(fromMmol value: Double) -> Double { value * mgPerMmol }
    static func celsius(fromFahrenheit value: Double) -> Double { (value - 32) * 5 / 9 }
    static func fahrenheit(fromCelsius value: Double) -> Double { value * 9 / 5 + 32 }

    /// Canonical value of a glucose reading typed in `unit`.
    static func canonicalGlucose(_ value: Double, unit: HealthGlucoseUnit) -> Double {
        unit == .mmolPerLiter ? value : mmol(fromMgPerDl: value)
    }

    /// Canonical value of a temperature typed in °C (`celsius == true`) or °F.
    static func canonicalTemperature(_ value: Double, celsius: Bool) -> Double {
        celsius ? value : self.celsius(fromFahrenheit: value)
    }

    /// Range check on the canonical value with a tiny tolerance so the unit-converted bounds
    /// (18 mg/dL, 600 mg/dL, 93.2 °F, 107.6 °F) are accepted.
    static func isInRange(_ canonicalValue: Double, kind: ManualHealthKind) -> Bool {
        guard canonicalValue.isFinite else { return false }
        let range = kind.allowedRange
        let tolerance = kind == .bloodGlucose ? 0.005 : 0.0005
        return canonicalValue >= range.lowerBound - tolerance && canonicalValue <= range.upperBound + tolerance
    }

    /// Locale-aware decimal parsing for the value field ("5,6" and "5.6" both work).
    static func parseDecimal(_ text: String, locale: Locale = .current) -> Double? {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return nil }
        let formatter = NumberFormatter()
        formatter.locale = locale
        formatter.numberStyle = .decimal
        if let number = formatter.number(from: trimmed) { return number.doubleValue }
        return Double(trimmed.replacingOccurrences(of: ",", with: "."))
    }

    static func validate(_ entry: ManualHealthEntry, now: Date = Date()) throws {
        guard isInRange(entry.value, kind: entry.kind) else { throw ManualHealthEntryError.outOfRange }
        // A minute of slack: the picker's "now" is captured when the sheet opens.
        guard entry.date <= now.addingTimeInterval(60) else { throw ManualHealthEntryError.futureTime }
        switch entry.kind {
        case .bloodGlucose:
            guard specimenCodes.contains(entry.specimen), relationCodes.contains(entry.relationToMeal),
                  mealTypeCodes.contains(entry.mealType) else { throw ManualHealthEntryError.invalidCode }
        case .bodyTemperature:
            guard locationCodes.contains(entry.measurementLocation) else { throw ManualHealthEntryError.invalidCode }
        }
    }

    // MARK: Row layout

    static func extraJSON(for entry: ManualHealthEntry) -> String? {
        switch entry.kind {
        case .bloodGlucose:
            return HealthSampleMapper.json(["relation_to_meal": entry.relationToMeal, "meal_type": entry.mealType])
        case .bodyTemperature:
            return HealthSampleMapper.json(["measurement_location": entry.measurementLocation])
        }
    }

    /// The origin-2 row for `entry`. `start_ms = end_ms` = the chosen time; offsets from the device zone.
    static func row(for entry: ManualHealthEntry, sourceID: String, calendar: Calendar, nowMs: Int64) -> HealthSampleRow {
        let ms = HealthSampleMapper.ms(entry.date)
        let offset = calendar.timeZone.secondsFromGMT(for: entry.date)
        let unit = HealthMetricRegistry.type(id: entry.kind.typeID)?.unit ?? (entry.kind == .bloodGlucose ? "mmol/L" : "degC")
        return HealthSampleRow(
            id: entry.rowID,
            typeID: entry.kind.typeID,
            startMs: ms,
            endMs: ms,
            startOffsetS: offset,
            endOffsetS: offset,
            localDay: HealthRollupMath.localDay(
                startMs: ms, endMs: ms, startOffsetS: offset, endOffsetS: offset, attribution: .start, calendar: calendar
            ),
            value: entry.value,
            unit: unit,
            categoryValue: entry.kind == .bloodGlucose ? entry.specimen : 0,
            extraJSON: extraJSON(for: entry),
            count: 1,
            sourceID: sourceID,
            recordingMethod: recordingMethodManual,
            origin: HealthRowOrigin.localAdapter.rawValue,
            updatedMs: nowMs
        )
    }

    static func source(id: String, nowMs: Int64) -> HealthSourceRow {
        HealthSourceRow(id: id, name: "Ayuvo", deviceModel: nil, deviceType: nil, lastSeenMs: nowMs)
    }

    /// Only origin-2 `local:` rows of the two manual types can be deleted (§2.2).
    static func isDeletable(_ row: HealthSampleRow) -> Bool {
        row.origin == HealthRowOrigin.localAdapter.rawValue
            && row.id.hasPrefix(idPrefix)
            && ManualHealthKind(typeID: row.typeID) != nil
            && !row.isDeleted
    }

    /// The `ayuvo_manual_id` tag of a `local:<uuid>` row.
    static func tag(fromRowID id: String) -> String? {
        guard id.hasPrefix(idPrefix) else { return nil }
        let tag = String(id.dropFirst(idPrefix.count))
        return UUID(uuidString: tag) == nil ? nil : tag
    }

    // MARK: HealthKit metadata

    /// HC `BodyTemperatureMeasurementLocation` → `HKBodyTemperatureSensorLocation` (anything without
    /// a HealthKit counterpart → other).
    static func sensorLocation(fromMeasurementLocation code: Int) -> HKBodyTemperatureSensorLocation {
        switch code {
        case 1: return .armpit
        case 2: return .finger
        case 3: return .forehead
        case 4: return .mouth
        case 5: return .rectum
        case 6: return .temporalArtery
        case 7: return .toe
        case 8: return .ear
        default: return .other
        }
    }

    /// Relation to meal 3 → preprandial, 4 → postprandial, otherwise nil (key omitted).
    static func mealTime(fromRelation relation: Int) -> HKBloodGlucoseMealTime? {
        switch relation {
        case 3: return .preprandial
        case 4: return .postprandial
        default: return nil
        }
    }

    static func healthKitMetadata(for entry: ManualHealthEntry, timeZone: TimeZone) -> [String: Any] {
        var metadata: [String: Any] = [
            metadataKey: entry.tag,
            HKMetadataKeyWasUserEntered: true,
            HKMetadataKeyTimeZone: timeZone.identifier,
        ]
        switch entry.kind {
        case .bloodGlucose:
            if let mealTime = mealTime(fromRelation: entry.relationToMeal) {
                metadata[HKMetadataKeyBloodGlucoseMealTime] = NSNumber(value: mealTime.rawValue)
            }
        case .bodyTemperature:
            metadata[HKMetadataKeyBodyTemperatureSensorLocation] = NSNumber(value: sensorLocation(fromMeasurementLocation: entry.measurementLocation).rawValue)
        }
        return metadata
    }

    static func healthKitSample(for entry: ManualHealthEntry, timeZone: TimeZone) -> HKQuantitySample {
        HKQuantitySample(
            type: HKQuantityType(entry.kind.quantityTypeIdentifier),
            quantity: HKQuantity(unit: entry.kind.healthKitUnit, doubleValue: entry.value),
            start: entry.date,
            end: entry.date,
            metadata: healthKitMetadata(for: entry, timeZone: timeZone)
        )
    }

    // MARK: Database

    /// Validates, commits the row (and the "Ayuvo" source) in one transaction, then rebuilds the day's rollups.
    @discardableResult
    static func save(
        _ entry: ManualHealthEntry,
        database: HealthDatabase,
        calendar: Calendar,
        ownBundleID: String,
        now: Date = Date()
    ) async throws -> HealthSampleRow {
        try validate(entry, now: now)
        let nowMs = HealthSampleMapper.ms(now)
        let row = row(for: entry, sourceID: ownBundleID, calendar: calendar, nowMs: nowMs)
        try await database.commitManualEntry(row, source: source(id: ownBundleID, nowMs: nowMs))
        try await rebuildRollups(typeID: row.typeID, day: row.localDay, database: database, calendar: calendar, ownBundleID: ownBundleID)
        return row
    }

    /// Tombstones a manual row and rebuilds its day. Returns the deleted row, or nil when the id is
    /// not a live manual entry (other origins stay read-only).
    @discardableResult
    static func delete(
        rowID: String,
        database: HealthDatabase,
        calendar: Calendar,
        ownBundleID: String,
        now: Date = Date()
    ) async throws -> HealthSampleRow? {
        guard let row = try await database.sample(id: rowID), isDeletable(row) else { return nil }
        guard try await database.tombstoneManualEntry(id: rowID, nowMs: HealthSampleMapper.ms(now)) else { return nil }
        try await rebuildRollups(typeID: row.typeID, day: row.localDay, database: database, calendar: calendar, ownBundleID: ownBundleID)
        return row
    }

    private static func rebuildRollups(typeID: String, day: String, database: HealthDatabase, calendar: Calendar, ownBundleID: String) async throws {
        guard let type = HealthMetricRegistry.type(id: typeID) else { return }
        try await database.rebuildRollups(type: type, days: [day], tz: calendar.timeZone.identifier, calendar: calendar, ownBundleID: ownBundleID)
    }
}

extension HealthDatabase {
    /// One transaction: the origin-2 row (normal upsert rules, §2.1) and its source.
    func commitManualEntry(_ row: HealthSampleRow, source: HealthSourceRow) throws {
        try connection.inTransaction {
            try upsertSamplesInTransaction([row])
            try upsertSourcesInTransaction([source])
        }
    }

    /// `deleted = 1, updated_ms = now` on a live origin-2 row; true when a row changed.
    func tombstoneManualEntry(id: String, nowMs: Int64) throws -> Bool {
        try connection.inTransaction {
            try connection.run(
                "UPDATE health_samples SET deleted=1, updated_ms=MAX(updated_ms, ?) WHERE id=? AND origin=? AND deleted=0",
                [.int(nowMs), .text(id), .int(Int64(HealthRowOrigin.localAdapter.rawValue))]
            )
            return connection.changes > 0
        }
    }
}
