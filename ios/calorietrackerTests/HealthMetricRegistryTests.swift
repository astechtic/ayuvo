import Foundation
import HealthKit
import Testing
@testable import calorietracker

struct HealthMetricRegistryTests {
    @Test func slugsAndIdentifiersAreUnique() {
        let ids = HealthMetricRegistry.all.map(\.id)
        #expect(Set(ids).count == ids.count)
        var seen: [String: String] = [:]
        for type in HealthMetricRegistry.all {
            for identifier in type.hkIdentifiers {
                #expect(seen[identifier] == nil, "\(identifier) claimed by \(seen[identifier] ?? "") and \(type.id)")
                seen[identifier] = type.id
            }
        }
    }

    @Test func everyUnitIsCanonicalAndHealthKitParsesTheIOSOnes() {
        for type in HealthMetricRegistry.all {
            #expect(HealthMetricType.canonicalUnits.contains(type.unit), "\(type.id) uses non-canonical unit \(type.unit)")
            if !type.isAndroidOnly, type.objectKind == .quantity || type.objectKind == .correlation {
                #expect(type.unit != "days" && type.unit != "none", "\(type.id) uses an Android-only pseudo-unit")
                // Units are built through the typed API (HealthKitUnits) — never HKUnit(from:),
                // which raises an uncatchable ObjC exception on an unknown spelling.
                let unit = HealthKitUnits.unit(for: type.hkUnit)
                #expect(unit != nil, "\(type.id): HealthKitUnits has no constructor for \(type.hkUnit)")
                if let unit, !type.hkUnit.contains("·"), !type.hkUnit.contains("<") {
                    #expect(unit.unitString == type.hkUnit, "\(type.id): HKUnit round trip \(unit.unitString) != \(type.hkUnit)")
                } else if let unit {
                    // Composite spellings are unverified in the shared registry (hk_units_verified: false);
                    // this prints HealthKit's real spelling so the JSON can be updated.
                    print("HealthKit spells \(type.hkUnit) as \(unit.unitString)")
                }
            }
        }
    }

    @Test func quantityTypesAreCompatibleWithTheirUnit() {
        for type in HealthMetricRegistry.iOSTypes where type.objectKind == .quantity {
            for quantityType in HealthMetricRegistry.quantityTypes(for: type) {
                guard let unit = HealthKitUnits.unit(for: type.hkUnit) else {
                    Issue.record("\(type.id): no HKUnit for \(type.hkUnit)")
                    continue
                }
                #expect(quantityType.is(compatibleWith: unit), "\(type.id): \(quantityType.identifier) incompatible with \(type.hkUnit)")
            }
        }
    }

    @Test func readObjectTypesExcludeCorrelationAndPerObjectAuthorization() {
        let types = HealthMetricRegistry.readObjectTypes()
        #expect(!types.isEmpty)
        #expect(!types.contains { $0 is HKCorrelationType })
        #expect(!types.contains { $0.requiresPerObjectAuthorization() })
        #expect(types.contains(HKQuantityType(.bloodPressureSystolic)))
        #expect(types.contains(HKQuantityType(.stepCount)))
        #expect(types.contains(HKObjectType.workoutType()))
        #expect(types.contains(HKCharacteristicType(.bloodType)))
    }

    @Test func syncableTypesResolveAtLeastOneSampleType() {
        let syncable = HealthMetricRegistry.syncableTypes()
        #expect(syncable.contains { $0.id == "steps" })
        #expect(syncable.contains { $0.id == "sleep" })
        #expect(syncable.contains { $0.id == "blood_pressure" })
        #expect(!syncable.contains { $0.isAndroidOnly })
        #expect(!syncable.contains { $0.objectKind == .activitySummary || $0.objectKind == .stateOfMind })
        for type in syncable {
            #expect(!HealthMetricRegistry.sampleTypes(for: type).isEmpty || type.objectKind == .correlation, Comment(rawValue: type.id))
        }
    }

    @Test func tiersPutHomeTypesFirstAndHighVolumeLast() {
        #expect(HealthMetricRegistry.tier(of: HealthTestFixtures.steps) == .t1)
        #expect(HealthMetricRegistry.tier(of: HealthTestFixtures.heartRate) == .t3)
        #expect(HealthMetricRegistry.tier(of: HealthMetricRegistry.type(id: "distance_cycling")!) == .t3)
        #expect(HealthMetricRegistry.tier(of: HealthMetricRegistry.type(id: "vo2_max")!) == .t2)
    }

    @Test func fallbackRegistersUnknownIdentifiersUnderOther() {
        let raw = HealthMetricRegistry.fallback(id: "HKQuantityTypeIdentifierSomethingNew", unit: "count")
        #expect(raw.category == .other)
        #expect(raw.englishName == "Something New")
        #expect(raw.objectKind == .quantity)
        let slug = HealthMetricRegistry.fallback(id: "future_metric", unit: "m")
        #expect(slug.englishName == "Future Metric")
        #expect(slug.isAndroidOnly)
    }

    @Test func dietaryTypesAreNotExportedButHydrationIs() {
        for type in HealthMetricRegistry.all where type.id.hasPrefix("dietary_") || type.id == "nutrition" {
            #expect(!type.exported, Comment(rawValue: type.id))
        }
        #expect(HealthMetricRegistry.type(id: "hydration")?.exported == true)
        #expect(HealthMetricRegistry.all.filter { $0.id.hasPrefix("dietary_") }.count == 38)
        #expect(HealthMetricRegistry.all.filter { $0.id.hasPrefix("symptom_") }.count == 39)
    }

    @Test func parityWithSharedRegistryJSON() throws {
        let url = HealthTestFixtures.sharedRegistryURL
        guard FileManager.default.fileExists(atPath: url.path) else {
            Issue.record("shared/health/metric_registry.json is missing — the contract file has not landed yet")
            return
        }
        let data = try Data(contentsOf: url)
        let json = try JSONSerialization.jsonObject(with: data)
        let entries: [[String: Any]]
        if let array = json as? [[String: Any]] {
            entries = array
        } else if let object = json as? [String: Any] {
            entries = (object["types"] as? [[String: Any]]) ?? (object["entries"] as? [[String: Any]]) ?? (object["metrics"] as? [[String: Any]]) ?? []
        } else {
            entries = []
        }
        #expect(!entries.isEmpty, "registry JSON has no entries")
        var jsonIDs = Set<String>()
        for entry in entries {
            guard let id = entry["id"] as? String else {
                Issue.record("registry entry without id: \(entry)")
                continue
            }
            jsonIDs.insert(id)
            guard let type = HealthMetricRegistry.type(id: id) else {
                Issue.record("JSON slug \(id) missing from the iOS registry")
                continue
            }
            if let category = entry["category"] as? String { #expect(category == type.category.rawValue, "\(id) category") }
            if let kind = entry["kind"] as? String { #expect(kind == type.kind.rawValue, "\(id) kind") }
            if let aggregation = entry["aggregation"] as? String { #expect(aggregation == type.aggregation.rawValue, "\(id) aggregation") }
            if let unit = entry["unit"] as? String { #expect(unit == type.unit, "\(id) unit") }
            // Only literal unit strings are comparable; the contract spells blood glucose as its
            // `HKUnit` constructor. Spellings may differ (`kcal/(kg*hr)` vs HealthKit's own
            // `kcal/hr·kg`), so both sides are compared as resolved `HKUnit`s.
            if let hkUnit = entry["hk_unit"] as? String, !type.isAndroidOnly, !hkUnit.isEmpty,
               !hkUnit.contains("HKUnit."), type.objectKind == .quantity || type.objectKind == .correlation {
                let contractUnit = HealthKitUnits.unit(for: hkUnit)
                #expect(contractUnit != nil, "\(id) hk_unit \(hkUnit) is not a unit the app can construct")
                #expect(contractUnit == HealthKitUnits.unit(for: type.hkUnit), "\(id) hk_unit")
            }
            if let identifiers = entry["hk_identifiers"] as? [String] { #expect(identifiers == type.hkIdentifiers, "\(id) hk_identifiers") }
            if let exported = entry["exported"] as? Bool { #expect(exported == type.exported, "\(id) exported") }
            if let attribution = entry["day_attribution"] as? String { #expect(attribution == type.dayAttribution.rawValue, "\(id) day_attribution") }
        }
        for type in HealthMetricRegistry.all where !jsonIDs.contains(type.id) {
            Issue.record("iOS registry slug \(type.id) missing from metric_registry.json")
        }
    }
}
