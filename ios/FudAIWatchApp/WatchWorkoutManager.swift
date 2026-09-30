import CoreLocation
import Foundation
import HealthKit
import Observation

/// Messages exchanged with the iPhone over the mirrored workout session.
/// Keep in sync with `calorietracker/Services/Workout/WatchWorkoutMirror.swift`.
nonisolated struct WatchWorkoutMessage: Codable, Sendable {
    var type: String
    var sessionID: String?
    var sport: String?
    var state: String?
    var elapsed: Double?
    var distance: Double?
    var heartRate: Double?
    var energy: Double?
    var laps: Int?
    var start: Double?
    var end: Double?
    var avgHeartRate: Double?
    var maxHeartRate: Double?
    var command: String?
}

/// Outdoor walk / run / cycle / hike on Apple Watch: HKWorkoutSession + HKLiveWorkoutBuilder (heart rate,
/// distance, energy), GPS route with HKWorkoutRouteBuilder, laps as workout events, and mirroring to the
/// iPhone so the phone shows the live metrics and logs the diary session. The watch saves the HKWorkout.
@MainActor
@Observable
final class WatchWorkoutManager: NSObject {
    static let shared = WatchWorkoutManager()

    enum Sport: String, CaseIterable, Identifiable {
        case walk, run, cycle, hike
        var id: String { rawValue }
        var title: String {
            switch self {
            case .walk: return String(localized: "Outdoor Walk", comment: "Apple Watch workout type")
            case .run: return String(localized: "Outdoor Run", comment: "Apple Watch workout type")
            case .cycle: return String(localized: "Outdoor Cycle", comment: "Apple Watch workout type")
            case .hike: return String(localized: "Hike", comment: "Apple Watch workout type")
            }
        }
        var systemImage: String {
            switch self {
            case .walk: return "figure.walk"
            case .run: return "figure.run"
            case .cycle: return "figure.outdoor.cycle"
            case .hike: return "figure.hiking"
            }
        }
        var activityType: HKWorkoutActivityType {
            switch self {
            case .walk: return .walking
            case .run: return .running
            case .cycle: return .cycling
            case .hike: return .hiking
            }
        }
        var distanceType: HKQuantityType {
            self == .cycle ? HKQuantityType(.distanceCycling) : HKQuantityType(.distanceWalkingRunning)
        }
        init?(activityType: HKWorkoutActivityType) {
            switch activityType {
            case .walking: self = .walk
            case .running: self = .run
            case .cycling: self = .cycle
            case .hiking: self = .hike
            default: return nil
            }
        }
    }

    private(set) var sport: Sport = .run
    private(set) var state: HKWorkoutSessionState = .notStarted
    private(set) var heartRate: Double?
    private(set) var distance: Double = 0
    private(set) var energy: Double = 0
    private(set) var laps = 0
    private(set) var startDate: Date?
    private(set) var errorMessage: String?
    private(set) var isFinishing = false

    var isActive: Bool { state == .running || state == .paused || state == .prepared || isFinishing }

    @ObservationIgnored private let store = HKHealthStore()
    @ObservationIgnored private var session: HKWorkoutSession?
    @ObservationIgnored private var builder: HKLiveWorkoutBuilder?
    @ObservationIgnored private var routeBuilder: HKWorkoutRouteBuilder?
    @ObservationIgnored private let locationManager = CLLocationManager()
    @ObservationIgnored private let locationProxy = WatchLocationProxy()
    @ObservationIgnored private var sessionID = UUID()
    @ObservationIgnored private var lastLap: Date?
    @ObservationIgnored private var heartRates: [Double] = []
    @ObservationIgnored private var lastSent = Date.distantPast

    private override init() {
        super.init()
        locationManager.delegate = locationProxy
        locationProxy.onLocations = { [weak self] locations in self?.insertRoute(locations) }
    }

    func elapsed(at date: Date = Date()) -> TimeInterval {
        builder?.elapsedTime(at: date) ?? 0
    }

    func requestAuthorization() async {
        guard HKHealthStore.isHealthDataAvailable() else { return }
        let share: Set<HKSampleType> = [
            HKObjectType.workoutType(), HKSeriesType.workoutRoute(), HKQuantityType(.activeEnergyBurned),
            HKQuantityType(.distanceWalkingRunning), HKQuantityType(.distanceCycling),
        ]
        let read: Set<HKObjectType> = [
            HKQuantityType(.heartRate), HKQuantityType(.activeEnergyBurned), HKQuantityType(.distanceWalkingRunning),
            HKQuantityType(.distanceCycling), HKObjectType.workoutType(),
        ]
        try? await store.requestAuthorization(toShare: share, read: read)
        if locationManager.authorizationStatus == .notDetermined {
            locationManager.requestWhenInUseAuthorization()
        }
    }

    func start(_ sport: Sport) async {
        guard !isActive else { return }
        errorMessage = nil
        await requestAuthorization()
        let configuration = HKWorkoutConfiguration()
        configuration.activityType = sport.activityType
        configuration.locationType = .outdoor
        do {
            let session = try HKWorkoutSession(healthStore: store, configuration: configuration)
            let builder = session.associatedWorkoutBuilder()
            builder.dataSource = HKLiveWorkoutDataSource(healthStore: store, workoutConfiguration: configuration)
            session.delegate = self
            builder.delegate = self
            self.session = session
            self.builder = builder
            self.sport = sport
            sessionID = UUID()
            distance = 0
            energy = 0
            heartRate = nil
            heartRates = []
            laps = 0
            let start = Date()
            startDate = start
            lastLap = start
            try? await session.startMirroringToCompanionDevice()
            session.startActivity(with: start)
            try await builder.beginCollection(at: start)
            routeBuilder = HKWorkoutRouteBuilder(healthStore: store, device: nil)
            locationManager.activityType = sport == .cycle ? .otherNavigation : .fitness
            locationManager.desiredAccuracy = kCLLocationAccuracyBest
            locationManager.distanceFilter = kCLDistanceFilterNone
            locationManager.startUpdatingLocation()
        } catch {
            errorMessage = String(localized: "Couldn't start the workout.", comment: "Apple Watch workout error")
            session = nil
            builder = nil
        }
    }

    /// Started from the iPhone (`HKHealthStore.startWatchApp(with:)`).
    func start(configuration: HKWorkoutConfiguration) {
        let sport = Sport(activityType: configuration.activityType) ?? .run
        Task { await start(sport) }
    }

    func pause() { session?.pause() }
    func resume() { session?.resume() }

    func lap() {
        guard let builder, state == .running || state == .paused else { return }
        let now = Date()
        let event = HKWorkoutEvent(type: .lap, dateInterval: DateInterval(start: lastLap ?? now, end: now), metadata: nil)
        lastLap = now
        laps += 1
        builder.addWorkoutEvents([event]) { _, _ in }
        sendMetrics(force: true)
    }

    /// Stops the activity, saves the workout and its route, sends the summary to the phone, then ends.
    func end() {
        guard let session, !isFinishing else { return }
        isFinishing = true
        session.stopActivity(with: Date())
    }

    private func finish(at end: Date) async {
        locationManager.stopUpdatingLocation()
        guard let builder, let session else { return }
        let id = sessionID.uuidString
        do {
            try await builder.endCollection(at: end)
            try await builder.addMetadata([
                "ayuvo_workout_session_id": id,
                HKMetadataKeySyncIdentifier: "ayuvo.workout-watch.\(id)",
                HKMetadataKeySyncVersion: 1,
                HKMetadataKeyIndoorWorkout: false,
            ])
            if let workout = try await builder.finishWorkout(), let routeBuilder {
                _ = try? await routeBuilder.finishRoute(with: workout, metadata: nil)
            }
        } catch {
            errorMessage = String(localized: "The workout couldn't be saved to Health.", comment: "Apple Watch workout error")
        }
        let elapsed = builder.elapsedTime(at: end)
        let summary = WatchWorkoutMessage(
            type: "summary", sessionID: id, sport: sport.rawValue, state: "ended", elapsed: elapsed,
            distance: distance, heartRate: heartRate, energy: energy, laps: laps,
            start: startDate?.timeIntervalSince1970, end: end.timeIntervalSince1970,
            avgHeartRate: heartRates.isEmpty ? nil : heartRates.reduce(0, +) / Double(heartRates.count),
            maxHeartRate: heartRates.max()
        )
        send(summary)
        try? await Task.sleep(for: .seconds(1))
        session.end()
    }

    private func reset() {
        session = nil
        builder = nil
        routeBuilder = nil
        startDate = nil
        isFinishing = false
    }

    // MARK: - Messages

    private func send(_ message: WatchWorkoutMessage) {
        guard let session, let data = try? JSONEncoder().encode(message) else { return }
        session.sendToRemoteWorkoutSession(data: data) { _, _ in }
    }

    private func sendMetrics(force: Bool = false) {
        guard force || Date().timeIntervalSince(lastSent) >= 3 else { return }
        lastSent = Date()
        send(WatchWorkoutMessage(
            type: "metrics", sessionID: sessionID.uuidString, sport: sport.rawValue,
            state: state == .paused ? "paused" : "running", elapsed: elapsed(), distance: distance,
            heartRate: heartRate, energy: energy, laps: laps
        ))
    }

    private func handle(_ message: WatchWorkoutMessage) {
        guard message.type == "command" else { return }
        switch message.command {
        case "pause": pause()
        case "resume": resume()
        case "lap": lap()
        case "end": end()
        default: break
        }
    }

    private func stateChanged(_ newState: HKWorkoutSessionState, date: Date) {
        state = newState
        switch newState {
        case .stopped:
            Task { await finish(at: date) }
        case .ended:
            reset()
        default:
            sendMetrics(force: true)
        }
    }

    private func collected(_ types: Set<HKSampleType>) {
        guard let builder else { return }
        for type in types {
            guard let quantityType = type as? HKQuantityType, let stats = builder.statistics(for: quantityType) else { continue }
            switch quantityType {
            case HKQuantityType(.heartRate):
                if let bpm = stats.mostRecentQuantity()?.doubleValue(for: .count().unitDivided(by: .minute())) {
                    heartRate = bpm
                    heartRates.append(bpm)
                }
            case HKQuantityType(.activeEnergyBurned):
                energy = stats.sumQuantity()?.doubleValue(for: .kilocalorie()) ?? energy
            case sport.distanceType:
                distance = stats.sumQuantity()?.doubleValue(for: .meter()) ?? distance
            default:
                break
            }
        }
        sendMetrics()
    }

    private func insertRoute(_ locations: [CLLocation]) {
        guard state == .running, let routeBuilder else { return }
        let good = locations.filter { $0.horizontalAccuracy >= 0 && $0.horizontalAccuracy <= 20 }
        guard !good.isEmpty else { return }
        routeBuilder.insertRouteData(good) { _, _ in }
    }
}

extension WatchWorkoutManager: HKWorkoutSessionDelegate {
    nonisolated func workoutSession(_ workoutSession: HKWorkoutSession, didChangeTo toState: HKWorkoutSessionState,
                                    from fromState: HKWorkoutSessionState, date: Date) {
        Task { @MainActor in WatchWorkoutManager.shared.stateChanged(toState, date: date) }
    }

    nonisolated func workoutSession(_ workoutSession: HKWorkoutSession, didFailWithError error: Error) {}

    nonisolated func workoutSession(_ workoutSession: HKWorkoutSession, didReceiveDataFromRemoteWorkoutSession data: [Data]) {
        let messages = data.compactMap { try? JSONDecoder().decode(WatchWorkoutMessage.self, from: $0) }
        Task { @MainActor in messages.forEach { WatchWorkoutManager.shared.handle($0) } }
    }
}

extension WatchWorkoutManager: HKLiveWorkoutBuilderDelegate {
    nonisolated func workoutBuilderDidCollectEvent(_ workoutBuilder: HKLiveWorkoutBuilder) {}

    nonisolated func workoutBuilder(_ workoutBuilder: HKLiveWorkoutBuilder, didCollectDataOf collectedTypes: Set<HKSampleType>) {
        Task { @MainActor in WatchWorkoutManager.shared.collected(collectedTypes) }
    }
}

private final class WatchLocationProxy: NSObject, CLLocationManagerDelegate {
    var onLocations: (([CLLocation]) -> Void)?

    nonisolated func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        Task { @MainActor [weak self] in self?.onLocations?(locations) }
    }

    nonisolated func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {}
}
