import Foundation
import HealthKit

/// One anchored page, already mapped to Sendable rows (no `HKSample` crosses an actor).
nonisolated struct HealthKitPage: Sendable {
    var rows: [HealthSampleRow] = []
    var deletedIDs: [String] = []
    var sources: [HealthSourceRow] = []
    /// Archived `HKQueryAnchor` to persist as `health_sync_state.cursor` (base64 on write).
    var anchor: Data?

    var isEmpty: Bool { rows.isEmpty && deletedIDs.isEmpty }
}

/// Everything the sync engine needs from HealthKit. `LiveHealthKitReader` is the only
/// production conformer; tests use `FakeHealthKitReader`.
nonisolated protocol HealthKitReading: Sendable {
    func anchoredPage(type: HealthMetricType, anchor: Data?, limit: Int) async throws -> HealthKitPage
    func recentRows(type: HealthMetricType, since: Date, limit: Int) async throws -> [HealthSampleRow]
    /// iOS 27 limited-history boundary per type id; empty when the API is unavailable.
    func earliestAuthorizedSampleDates(types: [HealthMetricType]) async -> [String: Date]
    /// `true` when HealthKit says a request would show the sheet; nil when unknown.
    func authorizationRequestNeeded(types: [HealthMetricType]) async -> Bool?
    /// Preferred unit strings keyed by type id (only for granted quantity types).
    func preferredUnitStrings(types: [HealthMetricType]) async -> [String: String]
}

/// Production reader. `HKHealthStore` is thread-safe; the descriptor APIs are native
/// async and run on the caller's executor (the detached sync task).
nonisolated final class LiveHealthKitReader: HealthKitReading, @unchecked Sendable {
    let store: HKHealthStore
    let calendar: Calendar

    init(store: HKHealthStore = HealthKitManager.sharedHealthStore, calendar: Calendar = .current) {
        self.store = store
        self.calendar = calendar
    }

    func anchoredPage(type: HealthMetricType, anchor: Data?, limit: Int) async throws -> HealthKitPage {
        let anchorObject = anchor.flatMap(Self.unarchiveAnchor)
        let result: (added: [HKSample], deleted: [HKDeletedObject], anchor: HKQueryAnchor?)
        if type.objectKind == .correlation {
            let predicates = [HKSamplePredicate.correlation(type: HKCorrelationType(.bloodPressure))]
            result = try await fetchAnchored(predicates, anchor: anchorObject, limit: limit)
        } else {
            let sampleTypes = HealthMetricRegistry.sampleTypes(for: type)
            guard !sampleTypes.isEmpty else { return HealthKitPage(anchor: anchor) }
            let predicates = sampleTypes.map { HKSamplePredicate.sample(type: $0) }
            result = try await fetchAnchored(predicates, anchor: anchorObject, limit: limit)
        }
        let nowMs = HealthSampleMapper.ms(Date())
        var page = HealthKitPage()
        autoreleasepool {
            page.rows = HealthSampleMapper.rows(from: result.added, type: type, calendar: calendar, nowMs: nowMs)
            page.deletedIDs = HealthSampleMapper.deletedIDs(result.deleted)
            page.sources = HealthSampleMapper.sources(from: result.added, nowMs: nowMs)
        }
        page.anchor = result.anchor.flatMap(Self.archiveAnchor) ?? anchor
        return page
    }

    func recentRows(type: HealthMetricType, since: Date, limit: Int) async throws -> [HealthSampleRow] {
        let datePredicate = HKQuery.predicateForSamples(withStart: since, end: nil, options: [])
        let samples: [HKSample]
        if type.objectKind == .correlation {
            let descriptor = HKSampleQueryDescriptor(
                predicates: [HKSamplePredicate.correlation(type: HKCorrelationType(.bloodPressure), predicate: datePredicate)],
                sortDescriptors: [SortDescriptor(\.startDate, order: .reverse)],
                limit: limit
            )
            samples = try await descriptor.result(for: store)
        } else {
            let sampleTypes = HealthMetricRegistry.sampleTypes(for: type)
            guard !sampleTypes.isEmpty else { return [] }
            let descriptor = HKSampleQueryDescriptor(
                predicates: sampleTypes.map { HKSamplePredicate.sample(type: $0, predicate: datePredicate) },
                sortDescriptors: [SortDescriptor(\.startDate, order: .reverse)],
                limit: limit
            )
            samples = try await descriptor.result(for: store)
        }
        let nowMs = HealthSampleMapper.ms(Date())
        return autoreleasepool {
            HealthSampleMapper.rows(from: samples, type: type, calendar: calendar, nowMs: nowMs)
        }
    }

    func authorizationRequestNeeded(types: [HealthMetricType]) async -> Bool? {
        // Ask about exactly the set the permission sheet requests. The raw per-type object
        // types include the blood-pressure correlation and per-object-authorization types,
        // which HealthKit rejects with an uncatchable NSException (`_throwIfAuthorizationDisallowed…`).
        guard !types.isEmpty else { return nil }
        let objectTypes = HealthMetricRegistry.readObjectTypes()
        guard !objectTypes.isEmpty else { return nil }
        do {
            let status = try await store.statusForAuthorizationRequest(toShare: [], read: objectTypes)
            switch status {
            case .unnecessary: return false
            case .shouldRequest: return true
            default: return nil
            }
        } catch {
            return nil
        }
    }

    func preferredUnitStrings(types: [HealthMetricType]) async -> [String: String] {
        var result: [String: String] = [:]
        for type in types {
            let quantityTypes = HealthMetricRegistry.quantityTypes(for: type)
            guard let first = quantityTypes.first else { continue }
            if let units = try? await store.preferredUnits(for: [first]), let unit = units[first] {
                result[type.id] = unit.unitString
            }
        }
        return result
    }

    // MARK: - Helpers

    private func fetchAnchored<T: HKSample>(
        _ predicates: [HKSamplePredicate<T>],
        anchor: HKQueryAnchor?,
        limit: Int
    ) async throws -> (added: [HKSample], deleted: [HKDeletedObject], anchor: HKQueryAnchor?) {
        let descriptor = HKAnchoredObjectQueryDescriptor(predicates: predicates, anchor: anchor, limit: limit)
        let result = try await descriptor.result(for: store)
        return (result.addedSamples as [HKSample], result.deletedObjects, result.newAnchor)
    }

    nonisolated static func archiveAnchor(_ anchor: HKQueryAnchor) -> Data? {
        try? NSKeyedArchiver.archivedData(withRootObject: anchor, requiringSecureCoding: true)
    }

    nonisolated static func unarchiveAnchor(_ data: Data) -> HKQueryAnchor? {
        try? NSKeyedUnarchiver.unarchivedObject(ofClass: HKQueryAnchor.self, from: data)
    }
}
