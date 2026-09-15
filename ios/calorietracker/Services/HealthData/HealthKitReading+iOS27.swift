import Foundation
import HealthKit

/// iOS 27 can grant only "the past 30 days" per type. The boundary API only exists in
/// the iOS 27 SDK, so it is doubly guarded: `#if compiler(>=6.3)` keeps Xcode 26 builds
/// compiling, `#available(iOS 27, *)` keeps older devices safe. Everything else returns
/// an empty map, which the engine reads as "unlimited history".
extension LiveHealthKitReader {
    func earliestAuthorizedSampleDates(types: [HealthMetricType]) async -> [String: Date] {
        #if compiler(>=6.3)
        if #available(iOS 27, *) {
            // One batched query: `earliestAuthorizedSampleDate(for:)` takes the whole set and
            // answers per object type; a metric spanning several identifiers keeps the earliest.
            var objectTypes = Set<HKObjectType>()
            for type in types {
                for sampleType in HealthMetricRegistry.sampleTypes(for: type) {
                    objectTypes.insert(sampleType)
                }
            }
            guard !objectTypes.isEmpty,
                  let dates = try? await store.earliestAuthorizedSampleDate(for: objectTypes)
            else { return [:] }
            var result: [String: Date] = [:]
            for type in types {
                for sampleType in HealthMetricRegistry.sampleTypes(for: type) {
                    guard let date = dates[sampleType] else { continue }
                    if let existing = result[type.id] {
                        result[type.id] = min(existing, date)
                    } else {
                        result[type.id] = date
                    }
                }
            }
            return result
        }
        return [:]
        #else
        return [:]
        #endif
    }
}
