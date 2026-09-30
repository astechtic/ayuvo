import CoreLocation
import Foundation
import HealthKit

/// Saves an `HKWorkout` over a real interval with `HKWorkoutBuilder` (pauses and laps as workout events,
/// one tagged active-energy sample, optional distance) and, when locations are given, its route with
/// `HKWorkoutRouteBuilder`. Used by strength sessions with a real window and by the GPS recorder.
enum WorkoutHealthWriter {
    struct Request {
        var activity: HKWorkoutActivityType
        var start: Date
        var end: Date
        var energyKcal: Double?
        var distanceM: Double?
        var distanceType: HKQuantityTypeIdentifier?
        var pauses: [DateInterval] = []
        var laps: [Date] = []
        /// Workout metadata (sync identifier / version, Ayuvo session id ...).
        var metadata: [String: Any]
        /// Metadata of the energy sample (the burn keys that keep it out of measured energy).
        var energyMetadata: [String: Any]
        var route: [CLLocation] = []
        var indoor: Bool = false
    }

    private static var store: HKHealthStore { HealthKitManager.sharedHealthStore }

    static var canSaveWorkouts: Bool {
        HKHealthStore.isHealthDataAvailable()
            && store.authorizationStatus(for: HKObjectType.workoutType()) == .sharingAuthorized
    }

    static func activityType(forConfigName name: String) -> HKWorkoutActivityType {
        switch name {
        case "walking": return .walking
        case "running": return .running
        case "cycling": return .cycling
        case "hiking": return .hiking
        case "traditionalStrengthTraining": return .traditionalStrengthTraining
        default: return .other
        }
    }

    static func distanceType(for activity: HKWorkoutActivityType) -> HKQuantityTypeIdentifier {
        activity == .cycling ? .distanceCycling : .distanceWalkingRunning
    }

    /// Returns the saved workout, or nil when Health refused or failed.
    @discardableResult
    static func save(_ request: Request) async -> HKWorkout? {
        guard canSaveWorkouts, request.end > request.start else { return nil }
        let configuration = HKWorkoutConfiguration()
        configuration.activityType = request.activity
        configuration.locationType = request.indoor ? .indoor : .outdoor
        let builder = HKWorkoutBuilder(healthStore: store, configuration: configuration, device: .local())
        do {
            try await builder.beginCollection(at: request.start)
            var samples: [HKSample] = []
            let energyType = HKQuantityType(.activeEnergyBurned)
            if let kcal = request.energyKcal, kcal > 0, store.authorizationStatus(for: energyType) == .sharingAuthorized {
                samples.append(HKQuantitySample(type: energyType, quantity: HKQuantity(unit: .kilocalorie(), doubleValue: kcal),
                                                start: request.start, end: request.end, metadata: request.energyMetadata))
            }
            if let meters = request.distanceM, meters > 0, let id = request.distanceType {
                let type = HKQuantityType(id)
                if store.authorizationStatus(for: type) == .sharingAuthorized {
                    samples.append(HKQuantitySample(type: type, quantity: HKQuantity(unit: .meter(), doubleValue: meters),
                                                    start: request.start, end: request.end, metadata: request.energyMetadata))
                }
            }
            if !samples.isEmpty { try await builder.addSamples(samples) }
            var events: [HKWorkoutEvent] = []
            for pause in request.pauses where pause.start >= request.start && pause.end <= request.end {
                events.append(HKWorkoutEvent(type: .pause, dateInterval: DateInterval(start: pause.start, duration: 0), metadata: nil))
                events.append(HKWorkoutEvent(type: .resume, dateInterval: DateInterval(start: pause.end, duration: 0), metadata: nil))
            }
            var lapStart = request.start
            for lap in request.laps.sorted() where lap > lapStart && lap <= request.end {
                events.append(HKWorkoutEvent(type: .lap, dateInterval: DateInterval(start: lapStart, end: lap), metadata: nil))
                lapStart = lap
            }
            if !events.isEmpty { try await builder.addWorkoutEvents(events) }
            if !request.metadata.isEmpty { try await builder.addMetadata(request.metadata) }
            try await builder.endCollection(at: request.end)
            guard let workout = try await builder.finishWorkout() else { return nil }
            if !request.route.isEmpty,
               store.authorizationStatus(for: HKSeriesType.workoutRoute()) == .sharingAuthorized {
                let routeBuilder = HKWorkoutRouteBuilder(healthStore: store, device: .local())
                // insertRouteData accepts batches; keep them modest.
                var index = 0
                while index < request.route.count {
                    let batch = Array(request.route[index..<min(index + 500, request.route.count)])
                    try await routeBuilder.insertRouteData(batch)
                    index += 500
                }
                _ = try? await routeBuilder.finishRoute(with: workout, metadata: nil)
            }
            return workout
        } catch {
            builder.discardWorkout()
            return nil
        }
    }

    /// Deletes Ayuvo-owned workouts tagged with `key == value`.
    static func deleteOwnedWorkouts(metadataKey key: String, value: String) async -> Bool {
        let predicate = NSCompoundPredicate(andPredicateWithSubpredicates: [
            HKQuery.predicateForObjects(withMetadataKey: key, operatorType: .equalTo, value: value),
            HKQuery.predicateForObjects(from: .default()),
        ])
        return await withCheckedContinuation { continuation in
            store.deleteObjects(of: HKObjectType.workoutType(), predicate: predicate) { success, _, _ in
                continuation.resume(returning: success)
            }
        }
    }
}
