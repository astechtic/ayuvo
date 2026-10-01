import Foundation
import HealthKit

nonisolated struct GoogleHealthMirrorOutcome: Sendable, Equatable {
    var written = 0
    var failed = 0
    var unsupported = 0
    /// Rows left pending because Apple Health write access is missing for their type.
    var notAuthorized = 0
}

/// Writes `pending` origin-3 rows into Apple Health (docs/google-health.md §4) through the
/// app's shared `HKHealthStore`. Every object carries `ayuvo_ghealth_id`, so the HealthKit
/// mirror reader skips it (the origin-3 row is the canonical copy). A row written before is
/// deleted by that tag first, so re-writing a changed point never duplicates it.
nonisolated final class GoogleHealthMirrorWriter: @unchecked Sendable {
    static let metadataKey = HealthSampleMapper.googleHealthMetadataKey
    static let batchSize = 500
    /// Attempts per row before it stays `error` (retried only after a re-fetch changes it).
    static let maxAttempts = 3

    let store: HKHealthStore
    let database: HealthDatabase
    let map: GoogleHealthMap
    let maxRowsPerRun: Int
    let now: @Sendable () -> Date

    init(
        store: HKHealthStore = HealthKitManager.sharedHealthStore,
        database: HealthDatabase,
        map: GoogleHealthMap,
        maxRowsPerRun: Int = 20_000,
        now: @escaping @Sendable () -> Date = { Date() }
    ) {
        self.store = store
        self.database = database
        self.map = map
        self.maxRowsPerRun = maxRowsPerRun
        self.now = now
    }

    // MARK: - Targets

    /// Every type the write-back can save, for the step-4 permission request.
    static func shareTypes(map: GoogleHealthMap) -> Set<HKSampleType> {
        var types = Set<HKSampleType>()
        for type in map.types {
            guard let identifier = type.hk?.identifier else { continue }
            switch identifier {
            case HealthMetricRegistry.workoutIdentifier:
                types.insert(HKObjectType.workoutType())
            case HKCorrelationTypeIdentifier.food.rawValue:
                // Correlations are authorized through their member types.
                for slug in foodSlugs {
                    if let quantity = foodQuantityType(slug) { types.insert(quantity) }
                }
            default:
                if let object = HealthMetricRegistry.objectType(forRawIdentifier: identifier) as? HKSampleType {
                    types.insert(object)
                }
            }
        }
        return types
    }

    /// Whether `row` has a HealthKit shape (sleep "out of bed" and empty food entries do not).
    static func canWrite(_ row: HealthSampleRow, target: GoogleHealthMap.HKTarget?) -> Bool {
        guard let target else { return false }
        switch target.identifier {
        case HKCategoryTypeIdentifier.sleepAnalysis.rawValue:
            return sleepValue(for: row) != nil
        case HealthMetricRegistry.workoutIdentifier:
            return row.endMs > row.startMs
        case HKCorrelationTypeIdentifier.food.rawValue:
            return !foodAmounts(row).isEmpty
        default:
            return row.value != nil && target.unit.flatMap(HealthKitUnits.unit(for:)) != nil
        }
    }

    static func ghealthID(_ row: HealthSampleRow) -> String {
        row.clientRecordID ?? GoogleHealthMapper.clientRecordPrefix + row.id.dropFirst(GoogleHealthMapper.idPrefix.count)
    }

    static func ghType(_ row: HealthSampleRow) -> String? {
        (row.extra["gh"] as? [String: Any])?["type"] as? String
    }

    // MARK: - Run

    func run(progress: @escaping @Sendable (Int) -> Void = { _ in }) async -> GoogleHealthMirrorOutcome {
        var outcome = GoogleHealthMirrorOutcome()
        guard HKHealthStore.isHealthDataAvailable() else { return outcome }
        try? await database.retryMirrorErrors(maxAttempts: Self.maxAttempts)
        var processed = 0
        // Types without Apple Health write access stay pending and drop out of later batches.
        var blockedTypeIDs = Set<String>()
        while processed < maxRowsPerRun, !Task.isCancelled {
            let limit = min(Self.batchSize, maxRowsPerRun - processed)
            guard let candidates = try? await database.pendingMirrorCandidates(limit: limit, excludingTypeIDs: blockedTypeIDs),
                  !candidates.isEmpty else { break }
            let result = await write(candidates)
            blockedTypeIDs.formUnion(result.blocked)
            outcome.written += result.outcome.written
            outcome.failed += result.outcome.failed
            outcome.unsupported += result.outcome.unsupported
            outcome.notAuthorized += result.outcome.notAuthorized
            processed += candidates.count
            progress(outcome.written)
        }
        return outcome
    }

    /// Every candidate ends up marked, or its type blocked, so `run` always makes progress.
    private func write(_ batch: [GoogleHealthMirrorCandidate]) async -> (outcome: GoogleHealthMirrorOutcome, blocked: Set<String>) {
        var outcome = GoogleHealthMirrorOutcome()
        var blocked = Set<String>()
        let nowMs = HealthSampleMapper.ms(now())
        var unsupported: [String] = []
        var objects: [(id: String, object: HKObject, types: [HKSampleType], rewrite: Bool)] = []
        var workouts: [(candidate: GoogleHealthMirrorCandidate, activity: HKWorkoutActivityType)] = []

        for candidate in batch {
            let row = candidate.row
            guard let ghType = Self.ghType(row), let target = map.type(ghType)?.hk else {
                unsupported.append(row.id)
                continue
            }
            let built = build(row, target: target)
            switch built {
            case .unsupported:
                unsupported.append(row.id)
            case .workout(let activity):
                guard store.authorizationStatus(for: HKObjectType.workoutType()) == .sharingAuthorized else {
                    blocked.insert(row.typeID)
                    outcome.notAuthorized += 1
                    continue
                }
                workouts.append((candidate, activity))
            case .object(let object, let types):
                guard types.allSatisfy({ store.authorizationStatus(for: $0) == .sharingAuthorized }) else {
                    blocked.insert(row.typeID)
                    outcome.notAuthorized += 1
                    continue
                }
                objects.append((row.id, object, types, candidate.platformID != nil || candidate.attempts > 0))
            }
        }

        try? await database.markMirror(unsupported, status: .unsupported, nowMs: nowMs)
        outcome.unsupported = unsupported.count

        // Re-writes: remove the earlier copy by tag before saving the new one.
        let rewrites = objects.filter(\.rewrite)
        if !rewrites.isEmpty {
            await deleteTagged(rewrites.map { ($0.id, $0.types) })
        }

        if !objects.isEmpty {
            do {
                try await store.save(objects.map(\.object))
                try? await database.markMirror(
                    objects.map(\.id), status: .mirrored,
                    platformIDs: Dictionary(uniqueKeysWithValues: objects.map { ($0.id, $0.object.uuid.uuidString.lowercased()) }),
                    nowMs: nowMs
                )
                outcome.written += objects.count
            } catch {
                // One bad object fails the whole batch: retry one by one to isolate it.
                for item in objects {
                    do {
                        try await store.save(item.object)
                        try? await database.markMirror([item.id], status: .mirrored, platformIDs: [item.id: item.object.uuid.uuidString.lowercased()], nowMs: nowMs)
                        outcome.written += 1
                    } catch {
                        try? await database.markMirror([item.id], status: .error, error: error.localizedDescription, nowMs: nowMs)
                        outcome.failed += 1
                    }
                }
            }
        }

        for (candidate, activity) in workouts {
            let row = candidate.row
            if candidate.platformID != nil || candidate.attempts > 0 {
                await deleteTagged([(row.id, [HKObjectType.workoutType()])], ghealthIDs: [Self.ghealthID(row)])
            }
            do {
                let uuid = try await saveWorkout(row, activity: activity)
                try? await database.markMirror([row.id], status: .mirrored, platformIDs: [row.id: uuid], nowMs: nowMs)
                outcome.written += 1
            } catch {
                try? await database.markMirror([row.id], status: .error, error: error.localizedDescription, nowMs: nowMs)
                outcome.failed += 1
            }
        }
        return (outcome, blocked)
    }

    private func deleteTagged(_ items: [(id: String, types: [HKSampleType])], ghealthIDs: [String]? = nil) async {
        let tags = ghealthIDs ?? items.compactMap { item in
            item.id.hasPrefix(GoogleHealthMapper.idPrefix) ? Self.tag(forRowID: item.id) : nil
        }
        guard !tags.isEmpty else { return }
        let predicate = HKQuery.predicateForObjects(withMetadataKey: Self.metadataKey, allowedValues: tags)
        let types = Set(items.flatMap(\.types))
        for type in types {
            _ = try? await store.deleteObjects(of: type, predicate: predicate)
        }
    }

    /// Tag of a row id: the client record id rule (`ayuvo_gh_` + id without `gh:`).
    static func tag(forRowID id: String) -> String {
        GoogleHealthMapper.clientRecordPrefix + id.dropFirst(GoogleHealthMapper.idPrefix.count)
    }

    // MARK: - Building objects

    private enum Built {
        case object(HKObject, [HKSampleType])
        case workout(HKWorkoutActivityType)
        case unsupported
    }

    private func build(_ row: HealthSampleRow, target: GoogleHealthMap.HKTarget) -> Built {
        let metadata = Self.metadata(row)
        let start = row.startDate
        let end = max(row.endDate, row.startDate)
        switch target.identifier {
        case HKCategoryTypeIdentifier.sleepAnalysis.rawValue:
            guard let value = Self.sleepValue(for: row) else { return .unsupported }
            let type = HKCategoryType(.sleepAnalysis)
            return .object(HKCategorySample(type: type, value: value.rawValue, start: start, end: end, metadata: metadata), [type])
        case HealthMetricRegistry.workoutIdentifier:
            guard row.endMs > row.startMs else { return .unsupported }
            return .workout(GoogleHealthWorkoutTypes.activityType(forExerciseType: row.title) ?? .other)
        case HKCorrelationTypeIdentifier.food.rawValue:
            var members = Set<HKSample>()
            var types: [HKSampleType] = []
            for (slug, amount) in Self.foodAmounts(row) {
                guard let type = Self.foodQuantityType(slug), let registry = HealthMetricRegistry.type(id: slug),
                      let unit = HealthKitUnits.unit(for: registry.hkUnit), type.is(compatibleWith: unit) else { continue }
                members.insert(HKQuantitySample(type: type, quantity: HKQuantity(unit: unit, doubleValue: amount), start: start, end: end, metadata: metadata))
                types.append(type)
            }
            guard !members.isEmpty else { return .unsupported }
            var foodMetadata = metadata
            if let title = row.title { foodMetadata[HKMetadataKeyFoodType] = title }
            return .object(HKCorrelation(type: HKCorrelationType(.food), start: start, end: end, objects: members, metadata: foodMetadata), types)
        default:
            guard let value = row.value, let unitName = target.unit, let unit = HealthKitUnits.unit(for: unitName),
                  let type = HealthMetricRegistry.objectType(forRawIdentifier: target.identifier) as? HKQuantityType,
                  type.is(compatibleWith: unit) else { return .unsupported }
            // The mirror stores percentages as 0–100; HealthKit wants fractions.
            let amount = unitName == "%" ? value / 100 : value
            guard amount.isFinite, amount >= 0 else { return .unsupported }
            return .object(HKQuantitySample(type: type, quantity: HKQuantity(unit: unit, doubleValue: amount), start: start, end: end, metadata: metadata), [type])
        }
    }

    private func saveWorkout(_ row: HealthSampleRow, activity: HKWorkoutActivityType) async throws -> String {
        let configuration = HKWorkoutConfiguration()
        configuration.activityType = activity
        let builder = HKWorkoutBuilder(healthStore: store, configuration: configuration, device: nil)
        try await builder.beginCollection(at: row.startDate)
        try await builder.addMetadata(Self.metadata(row))
        try await builder.endCollection(at: row.endDate)
        guard let workout = try await builder.finishWorkout() else {
            throw GoogleHealthAPIError.invalidResponse
        }
        return workout.uuid.uuidString.lowercased()
    }

    static func metadata(_ row: HealthSampleRow) -> [String: Any] {
        var metadata: [String: Any] = [metadataKey: ghealthID(row)]
        if let offset = row.startOffsetS ?? row.endOffsetS, let zone = TimeZone(secondsFromGMT: offset) {
            metadata[HKMetadataKeyTimeZone] = zone.identifier
        }
        return metadata
    }

    // MARK: - Sleep

    /// Session row → in bed; stage rows → the HealthKit stage; "out of bed" has no HealthKit value.
    static func sleepValue(for row: HealthSampleRow) -> HKCategoryValueSleepAnalysis? {
        let isStage = row.extra["session_id"] != nil
        guard isStage else { return .inBed }
        switch HealthSleepStage(rawValue: row.categoryValue ?? 1) {
        case .awake: return .awake
        case .light: return .asleepCore
        case .deep: return .asleepDeep
        case .rem: return .asleepREM
        case .asleepUnspecified: return .asleepUnspecified
        case .inBed: return .inBed
        case .outOfBed, .none: return nil
        }
    }

    // MARK: - Food

    static let foodSlugs: [String] = HealthMetricRegistry.all.filter { $0.id.hasPrefix("dietary_") }.map(\.id)

    static func foodQuantityType(_ slug: String) -> HKQuantityType? {
        HealthMetricRegistry.type(id: slug).flatMap { HealthMetricRegistry.quantityTypes(for: $0).first }
    }

    /// Energy (value), protein (value2), carbohydrates (value3) and every `dietary_*` key of
    /// `extra_json`, in each slug's canonical unit.
    static func foodAmounts(_ row: HealthSampleRow) -> [(String, Double)] {
        var amounts: [String: Double] = [:]
        let extra = row.extra
        for slug in foodSlugs {
            if let number = extra[slug] as? NSNumber { amounts[slug] = number.doubleValue }
        }
        if let value = row.value { amounts["dietary_energy"] = value }
        if let protein = row.value2 { amounts["dietary_protein"] = protein }
        if let carbs = row.value3 { amounts["dietary_carbohydrates"] = carbs }
        return amounts.filter { $0.value.isFinite && $0.value >= 0 }.sorted { $0.key < $1.key }
    }
}
