import CoreLocation
import CoreMotion
import Foundation
import HealthKit
import Observation

/// Records a GPS outdoor workout (walk, run, cycle, hike): location with background updates, barometric
/// relative altitude when available, manual pause/resume and laps, live heart rate from HealthKit, and
/// live metrics from re-running `GpsTrack.gpsTrack` on the collected points. The in-progress track is
/// persisted so a crash or relaunch can resume or save it. On finish it saves the diary session, the
/// route file and (unless an Apple Watch session is recording the same workout) an HKWorkout with route.
@MainActor
@Observable
final class OutdoorWorkoutRecorder {
    static let shared = OutdoorWorkoutRecorder()

    enum Phase: Equatable {
        case idle
        case recording
        case paused
        /// Ended; collecting two minutes of heart rate for HRR1.
        case recovery(until: Date)
        case saving
        /// Found an unfinished workout from a previous process.
        case interrupted
    }

    /// Everything needed to resume or save after a relaunch.
    struct InProgress: Codable {
        var sessionID: UUID
        var sport: OutdoorSport
        var cooperTest: Bool
        var startedAt: Date
        var points: [OutdoorRoute.Point]
        var gpsAltitudes: [Double?]
        var pauses: [[Int64]]
        var pauseStartedAt: Date?
        var laps: [Date]
        var heartRate: [OutdoorRoute.HeartSample]
    }

    static let cooperDurationSeconds: Double = 12 * 60
    private static let minute: Int64 = 60_000

    private(set) var phase: Phase = .idle
    private(set) var sport: OutdoorSport = .run
    private(set) var cooperTest = false
    private(set) var startedAt: Date?
    private(set) var endedAt: Date?
    private(set) var points: [GpsTrack.Point] = []
    private var gpsAltitudes: [Double?] = []
    private(set) var pauses: [(Int64, Int64)] = []
    private(set) var pauseStartedAt: Date?
    private(set) var laps: [Date] = []
    private(set) var heartRateSamples: [HeartRateWorkout.Sample] = []
    private(set) var live: GpsTrack.Result?
    private(set) var currentPaceSecondsPerKm: Double?
    private(set) var currentSpeedMps: Double?
    private(set) var currentAltitude: Double?
    private(set) var locationDenied = false
    private(set) var lastFinishedSessionID: UUID?
    private(set) var sessionID = UUID()
    /// Set by `WatchWorkoutMirror` while a mirrored Apple Watch workout is running: the watch saves the
    /// HKWorkout, so this recorder only logs the diary session.
    var watchSessionActive = false
    /// Live metrics from a mirrored Apple Watch workout (shown when the phone is not recording itself).
    var watchHeartRate: Int?

    /// Providers installed by the app (`WorkoutSessionCoordinator.attach`).
    @ObservationIgnored weak var workoutStore: StrengthWorkoutStore?
    @ObservationIgnored var bodyWeightKg: () -> Double = { UserProfile.load()?.weightKg ?? 70 }

    @ObservationIgnored private let manager = CLLocationManager()
    @ObservationIgnored private let locationProxy = LocationDelegateProxy()
    @ObservationIgnored private let altimeter = CMAltimeter()
    @ObservationIgnored private var relativeAltitude: Double?
    @ObservationIgnored private var backgroundActivity: CLBackgroundActivitySession?
    @ObservationIgnored private var heartRateQuery: HKAnchoredObjectQuery?
    @ObservationIgnored private var tickTask: Task<Void, Never>?
    @ObservationIgnored private var recoveryTask: Task<Void, Never>?
    @ObservationIgnored private var lastRecompute = Date.distantPast
    @ObservationIgnored private var lastPersist = Date.distantPast
    @ObservationIgnored private var needsRecompute = false

    private init() {
        manager.delegate = locationProxy
        locationProxy.onLocations = { [weak self] locations in self?.receive(locations) }
        locationProxy.onAuthorization = { [weak self] status in self?.authorizationChanged(status) }
    }

    var isActive: Bool {
        switch phase {
        case .idle: return false
        default: return true
        }
    }

    var isRecordingOrPaused: Bool { phase == .recording || phase == .paused }

    // MARK: - Time

    private func ms(_ date: Date) -> Int64 { Int64((date.timeIntervalSince1970 * 1000).rounded()) }

    /// Closed pauses plus the open one.
    private func pausedSeconds(at now: Date) -> Double {
        var total = pauses.reduce(0.0) { $0 + Double($1.1 - $1.0) / 1000 }
        if let pauseStartedAt { total += max(0, now.timeIntervalSince(pauseStartedAt)) }
        return total
    }

    /// Elapsed time without manual pauses.
    func activeElapsed(at now: Date = Date()) -> Double {
        guard let startedAt else { return 0 }
        let end = endedAt ?? now
        return max(0, end.timeIntervalSince(startedAt) - pausedSeconds(at: end))
    }

    /// Anchor for `Text(timerInterval:)`: now − active elapsed.
    var timerAnchor: Date {
        guard let startedAt else { return Date() }
        return startedAt.addingTimeInterval(pauses.reduce(0.0) { $0 + Double($1.1 - $1.0) / 1000 })
    }

    var currentHeartRate: Int? {
        if let last = heartRateSamples.last, Date().timeIntervalSince1970 * 1000 - Double(last.tMs) < 120_000 {
            return Int(last.bpm.rounded())
        }
        return watchHeartRate
    }

    /// Distance and time within the current kilometre.
    var currentSplit: (index: Int, seconds: Double, meters: Double)? {
        guard let live else { return nil }
        let done = live.splits.reduce(0.0) { $0 + $1.seconds }
        return (live.splits.count + 1, max(0, live.movingS - done), live.distanceM - Double(live.splits.count) * 1000)
    }

    /// Distance and active time since the last lap mark.
    var currentLap: (index: Int, seconds: Double)? {
        guard let startedAt else { return nil }
        let lapStart = laps.last ?? startedAt
        let now = endedAt ?? Date()
        let pausedSinceLap = pauses.filter { Double($0.0) >= lapStart.timeIntervalSince1970 * 1000 }
            .reduce(0.0) { $0 + Double($1.1 - $1.0) / 1000 } + (pauseStartedAt.map { now.timeIntervalSince($0) } ?? 0)
        return (laps.count + 1, max(0, now.timeIntervalSince(lapStart) - pausedSinceLap))
    }

    // MARK: - Control

    /// Asks for When-In-Use location (never Always) and starts recording.
    func start(sport: OutdoorSport, cooperTest: Bool) {
        guard phase == .idle else { return }
        let status = manager.authorizationStatus
        if status == .denied || status == .restricted {
            locationDenied = true
            return
        }
        locationDenied = false
        self.sport = sport
        self.cooperTest = cooperTest && sport.supportsVO2max
        sessionID = UUID()
        startedAt = Date()
        endedAt = nil
        points = []
        gpsAltitudes = []
        pauses = []
        pauseStartedAt = nil
        laps = []
        heartRateSamples = []
        live = nil
        currentPaceSecondsPerKm = nil
        currentSpeedMps = nil
        lastFinishedSessionID = nil
        phase = .recording
        if status == .notDetermined { manager.requestWhenInUseAuthorization() }
        startSensors()
        WorkoutLiveActivityController.shared.start(title: sport.title, state: liveActivityState())
        persist(force: true)
    }

    func pause() {
        guard phase == .recording else { return }
        pauseStartedAt = Date()
        phase = .paused
        recompute(force: true)
        WorkoutLiveActivityController.shared.update(liveActivityState(), force: true)
        persist(force: true)
    }

    func resume() {
        guard phase == .paused || phase == .interrupted else { return }
        let now = Date()
        if phase == .interrupted {
            // The gap since the last recorded point counts as a pause.
            let lastT = points.last.map { Date(timeIntervalSince1970: Double($0.tMs) / 1000) } ?? startedAt ?? now
            pauseStartedAt = pauseStartedAt ?? lastT
            startSensors()
            WorkoutLiveActivityController.shared.start(title: sport.title, state: liveActivityState())
        }
        if let pauseStartedAt {
            pauses.append((ms(pauseStartedAt), ms(now)))
        }
        pauseStartedAt = nil
        phase = .recording
        recompute(force: true)
        WorkoutLiveActivityController.shared.update(liveActivityState(), force: true)
        persist(force: true)
    }

    func lap() {
        guard phase == .recording || phase == .paused else { return }
        laps.append(Date())
        WorkoutLiveActivityController.shared.update(liveActivityState(), force: true)
        persist(force: true)
    }

    /// Ends the workout. With live heart rate it first collects two minutes of recovery.
    func end() {
        guard phase == .recording || phase == .paused || phase == .interrupted else { return }
        let now = phase == .interrupted
            ? (points.last.map { Date(timeIntervalSince1970: Double($0.tMs) / 1000) } ?? Date())
            : Date()
        if let pauseStartedAt {
            pauses.append((ms(pauseStartedAt), ms(max(now, pauseStartedAt))))
            self.pauseStartedAt = nil
        }
        endedAt = now
        stopLocation()
        recompute(force: true)
        persist(force: true)
        let hasLiveHeartRate = heartRateSamples.contains { Double($0.tMs) >= now.timeIntervalSince1970 * 1000 - 120_000 }
        if hasLiveHeartRate, phase != .interrupted {
            let until = now.addingTimeInterval(125)
            phase = .recovery(until: until)
            WorkoutLiveActivityController.shared.update(liveActivityState(), force: true)
            recoveryTask = Task { [weak self] in
                try? await Task.sleep(for: .seconds(125))
                guard !Task.isCancelled else { return }
                await self?.finish()
            }
        } else {
            Task { await finish() }
        }
    }

    /// Skips the rest of the heart-rate recovery window.
    func skipRecovery() {
        guard case .recovery = phase else { return }
        recoveryTask?.cancel()
        Task { await finish() }
    }

    func discard() {
        stopLocation()
        stopHeartRate()
        tickTask?.cancel()
        recoveryTask?.cancel()
        WorkoutLiveActivityController.shared.end()
        Self.deleteInProgress()
        reset()
    }

    func handle(_ command: WorkoutLiveActivityCommand) {
        switch command {
        case .pause: pause()
        case .resume: resume()
        case .lap: lap()
        case .end: end()
        }
    }

    func clearFinishedSession() { lastFinishedSessionID = nil }

    // MARK: - Relaunch

    /// Loads an unfinished workout left by a previous process.
    func restoreIfNeeded() {
        guard phase == .idle, let saved = Self.loadInProgress() else { return }
        sessionID = saved.sessionID
        sport = saved.sport
        cooperTest = saved.cooperTest
        startedAt = saved.startedAt
        points = saved.points.map { GpsTrack.Point(tMs: $0.t, lat: $0.lat, lon: $0.lon, altM: $0.alt, hAccM: $0.acc, speedMps: $0.speed) }
        gpsAltitudes = saved.gpsAltitudes.count == saved.points.count ? saved.gpsAltitudes : saved.points.map(\.alt)
        pauses = saved.pauses.compactMap { $0.count == 2 ? ($0[0], $0[1]) : nil }
        pauseStartedAt = saved.pauseStartedAt
        laps = saved.laps
        heartRateSamples = saved.heartRate.map { HeartRateWorkout.Sample(tMs: $0.t, bpm: $0.bpm) }
        endedAt = nil
        phase = .interrupted
        recompute(force: true)
    }

    // MARK: - Sensors

    private func startSensors() {
        switch sport {
        case .cycle: manager.activityType = .otherNavigation
        default: manager.activityType = .fitness
        }
        manager.desiredAccuracy = kCLLocationAccuracyBestForNavigation
        manager.distanceFilter = kCLDistanceFilterNone
        manager.pausesLocationUpdatesAutomatically = false
        manager.allowsBackgroundLocationUpdates = true
        manager.showsBackgroundLocationIndicator = true
        backgroundActivity = CLBackgroundActivitySession()
        manager.startUpdatingLocation()

        relativeAltitude = nil
        if CMAltimeter.isRelativeAltitudeAvailable() {
            altimeter.startRelativeAltitudeUpdates(to: .main) { [weak self] data, _ in
                guard let data else { return }
                let value = data.relativeAltitude.doubleValue
                MainActor.assumeIsolated { self?.relativeAltitude = value }
            }
        }
        startHeartRate()
        tickTask?.cancel()
        tickTask = Task { [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(for: .seconds(1))
                self?.tick()
            }
        }
    }

    private func stopLocation() {
        manager.stopUpdatingLocation()
        manager.allowsBackgroundLocationUpdates = false
        backgroundActivity?.invalidate()
        backgroundActivity = nil
        altimeter.stopRelativeAltitudeUpdates()
    }

    private func startHeartRate() {
        guard heartRateQuery == nil, HKHealthStore.isHealthDataAvailable(),
              UserDefaults.standard.bool(forKey: "healthKitEnabled"),
              let startedAt else { return }
        let predicate = HKQuery.predicateForSamples(withStart: startedAt.addingTimeInterval(-60), end: nil, options: [])
        let handler: @Sendable (HKAnchoredObjectQuery, [HKSample]?, [HKDeletedObject]?, HKQueryAnchor?, Error?) -> Void = { _, samples, _, _, _ in
            let unit = HKUnit.count().unitDivided(by: .minute())
            let values = (samples as? [HKQuantitySample] ?? []).map {
                HeartRateWorkout.Sample(tMs: Int64($0.startDate.timeIntervalSince1970 * 1000), bpm: $0.quantity.doubleValue(for: unit))
            }
            guard !values.isEmpty else { return }
            Task { @MainActor in OutdoorWorkoutRecorder.shared.appendHeartRate(values) }
        }
        let query = HKAnchoredObjectQuery(type: HKQuantityType(.heartRate), predicate: predicate, anchor: nil,
                                          limit: HKObjectQueryNoLimit, resultsHandler: handler)
        query.updateHandler = handler
        heartRateQuery = query
        HealthKitManager.sharedHealthStore.execute(query)
    }

    private func stopHeartRate() {
        if let heartRateQuery { HealthKitManager.sharedHealthStore.stop(heartRateQuery) }
        heartRateQuery = nil
    }

    private func appendHeartRate(_ values: [HeartRateWorkout.Sample]) {
        let known = Set(heartRateSamples.map(\.tMs))
        let fresh = values.filter { !known.contains($0.tMs) }
        guard !fresh.isEmpty else { return }
        heartRateSamples.append(contentsOf: fresh)
        heartRateSamples.sort { $0.tMs < $1.tMs }
    }

    private func authorizationChanged(_ status: CLAuthorizationStatus) {
        guard phase == .recording || phase == .paused else { return }
        if status == .denied || status == .restricted {
            locationDenied = true
        }
    }

    private func receive(_ locations: [CLLocation]) {
        guard phase == .recording || phase == .paused else { return }
        for location in locations where location.horizontalAccuracy >= 0 {
            let t = ms(location.timestamp)
            if let last = points.last, t <= last.tMs { continue }
            let gpsAlt: Double? = location.verticalAccuracy > 0 ? location.altitude : nil
            points.append(GpsTrack.Point(tMs: t, lat: location.coordinate.latitude, lon: location.coordinate.longitude,
                                         altM: relativeAltitude ?? gpsAlt, hAccM: location.horizontalAccuracy,
                                         speedMps: location.speed >= 0 ? location.speed : nil))
            gpsAltitudes.append(gpsAlt)
            currentAltitude = relativeAltitude ?? gpsAlt
        }
        needsRecompute = true
    }

    private func tick() {
        guard phase == .recording || phase == .paused else { return }
        if needsRecompute { recompute() }
        if cooperTest, phase == .recording, activeElapsed() >= Self.cooperDurationSeconds {
            end()
            return
        }
        WorkoutLiveActivityController.shared.update(liveActivityState())
        persist()
    }

    // MARK: - Live metrics

    private func trackInput(end: Date) -> GpsTrack.Input? {
        guard let startedAt else { return nil }
        var allPauses = pauses
        if let pauseStartedAt { allPauses.append((ms(pauseStartedAt), ms(end) + 1)) }
        return GpsTrack.Input(sport: sport.rawValue, points: points, pauses: allPauses, startMs: ms(startedAt), endMs: ms(end))
    }

    private func recompute(force: Bool = false) {
        let now = Date()
        guard force || now.timeIntervalSince(lastRecompute) >= 2 else { return }
        lastRecompute = now
        needsRecompute = false
        guard let input = trackInput(end: endedAt ?? now) else { return }
        live = GpsTrack.gpsTrack(input, config: WorkoutConfig.shared)
        updateCurrentPace(now: endedAt ?? now)
    }

    /// Pace over the last 30 s of accurate points.
    private func updateCurrentPace(now: Date) {
        let th = WorkoutConfig.shared.thresholds
        let from = ms(now) - 30_000
        let recent = points.filter { $0.tMs >= from && ($0.hAccM ?? .infinity) <= th.maxHAccuracyM }
        guard recent.count >= 2, phase == .recording else {
            currentPaceSecondsPerKm = nil
            currentSpeedMps = nil
            return
        }
        var d = 0.0
        for i in 1..<recent.count {
            d += WorkoutMath.haversineM(recent[i - 1].lat, recent[i - 1].lon, recent[i].lat, recent[i].lon)
        }
        let dt = Double(recent.last!.tMs - recent.first!.tMs) / 1000
        guard dt > 0 else { return }
        let v = d / dt
        currentSpeedMps = v
        let autoPause = WorkoutConfig.shared.sports[sport.rawValue]?.autoPauseSpeedMps ?? 0.3
        currentPaceSecondsPerKm = v >= autoPause ? 1000 / v : nil
    }

    func liveActivityState() -> WorkoutActivityAttributes.ContentState {
        let stateName: String
        switch phase {
        case .paused, .interrupted: stateName = "paused"
        case .recovery, .saving: stateName = "recovery"
        default: stateName = "running"
        }
        return WorkoutActivityAttributes.ContentState(
            kind: "gps", sport: sport.rawValue, state: stateName, timerStart: timerAnchor,
            pausedElapsed: stateName == "running" ? nil : activeElapsed(),
            distanceM: live?.distanceM, paceSecondsPerKm: currentPaceSecondsPerKm ?? live?.avgPaceSecondsPerKmValue,
            speedMps: currentSpeedMps ?? live?.avgSpeedMps, heartRate: currentHeartRate, lapCount: laps.count,
            source: watchSessionActive ? "watch" : nil
        )
    }

    // MARK: - Finish

    private func finish() async {
        guard let startedAt, let endedAt else { return }
        guard phase != .saving else { return }
        phase = .saving
        stopHeartRate()
        tickTask?.cancel()
        let config = WorkoutConfig.shared
        let startMs = ms(startedAt), endMs = ms(endedAt)
        let track = GpsTrack.gpsTrack(GpsTrack.Input(sport: sport.rawValue, points: points, pauses: pauses,
                                                     startMs: startMs, endMs: endMs), config: config)
        let weight = bodyWeightKg()
        let context = await WorkoutHeartRateSource.context(weightKg: weight)
        var samples = heartRateSamples
        if samples.filter({ $0.tMs >= startMs && $0.tMs < endMs }).isEmpty {
            samples = await WorkoutHeartRateSource.samples(from: startedAt, to: endedAt.addingTimeInterval(130))
        }
        var heart = await WorkoutHeartRateSource.summary(start: startedAt, end: endedAt, context: context, samples: samples)
        let recovery = HeartRateWorkout.hrRecovery(samples: samples, endMs: endMs, config: config)
        if heart != nil, let hrr1 = recovery.hrr1 {
            heart?.hrr1 = hrr1
            heart?.hrr1FlagLow = recovery.flagLow
        }

        let active = activeElapsed(at: endedAt)
        let moving = track?.movingS ?? 0
        let energyMethod: String
        let kcal: Int
        if let keytel = heart?.keytelKcal, keytel > 0 {
            kcal = Int(keytel.rounded())
            energyMethod = "keytel"
        } else {
            let metID = config.sports[sport.rawValue]?.metItemId ?? "Walking_Outdoor"
            let met = StrengthWorkoutBurnEstimator.cardioMET(itemID: metID, intensity: intensity(speed: track?.avgSpeedMps))
            kcal = StrengthWorkoutBurnEstimator.metCalories(met: met, bodyWeightKg: weight, seconds: moving > 0 ? moving : active)
            energyMethod = "met"
        }

        var vo2: CardioFitness.GpsResult?
        if sport.supportsVO2max {
            vo2 = CardioFitness.vo2maxGps(segments: steadySegments(samples: samples), rhr: context.rhr,
                                          hrMax: context.hrMax, config: config)
        }
        var cooperVO2: Double?
        var cooperDistance: Double?
        if cooperTest, active >= Self.cooperDurationSeconds - 1 {
            cooperDistance = track?.distanceM
            cooperVO2 = CardioFitness.cooper(distanceM: cooperDistance).vo2max
        }

        let lapOffsets = laps.map { $0.timeIntervalSince(startedAt) }
        let summary = OutdoorWorkoutSummary(
            sport: sport.rawValue, distanceM: track?.distanceM ?? 0, movingSeconds: moving,
            elapsedSeconds: active, avgSpeedMps: track?.avgSpeedMps, avgPaceSecondsPerKm: track?.avgPaceSPerKm,
            maxSpeedMps: track?.maxSpeedMps,
            splits: (track?.splits ?? []).map { OutdoorWorkoutSplit(km: $0.km, seconds: $0.seconds) },
            elevationGainM: track?.elevationGainM ?? 0, elevationLossM: track?.elevationLossM ?? 0,
            lapOffsets: lapOffsets.isEmpty ? nil : lapOffsets, vo2max: vo2?.vo2max, vo2maxSegments: vo2?.segmentsUsed,
            cooperVO2max: cooperVO2, cooperDistanceM: cooperDistance, recordedOn: "phone",
            hasRoute: !points.isEmpty, energyMethod: energyMethod
        )
        let session = StrengthWorkoutSession(
            id: sessionID,
            diaryDate: Calendar.current.startOfDay(for: startedAt),
            diaryDateKey: StrengthWorkoutDate.key(for: startedAt),
            startedAt: startedAt,
            completedAt: endedAt,
            durationSeconds: max(1, Int(active.rounded())),
            exercises: [],
            caloriesBurned: kcal,
            healthSyncVersion: 1,
            heartRate: heart,
            outdoor: summary
        )
        let route = OutdoorRoute(
            sport: sport.rawValue,
            points: points.map { OutdoorRoute.Point(t: $0.tMs, lat: $0.lat, lon: $0.lon, alt: $0.altM, acc: $0.hAccM, speed: $0.speedMps) },
            heartRate: samples.filter { $0.tMs >= startMs - 60_000 && $0.tMs <= endMs + 130_000 }
                .map { OutdoorRoute.HeartSample(t: $0.tMs, bpm: $0.bpm) },
            pauses: pauses.map { [$0.0, $0.1] }
        )
        if !points.isEmpty { OutdoorRouteStore.save(route, sessionID: sessionID) }
        workoutStore?.addOutdoorSession(session)

        if !watchSessionActive, UserDefaults.standard.bool(forKey: "healthKitEnabled") {
            await saveToHealth(session: session, summary: summary, kcal: kcal)
        }
        if summary.bestVO2max != nil { DerivedMetricsService.shared.scheduleRefresh() }

        WorkoutLiveActivityController.shared.end(final: liveActivityState())
        Self.deleteInProgress()
        let finishedID = sessionID
        reset()
        lastFinishedSessionID = finishedID
    }

    private func saveToHealth(session: StrengthWorkoutSession, summary: OutdoorWorkoutSummary, kcal: Int) async {
        let config = WorkoutConfig.shared
        let activity = WorkoutHealthWriter.activityType(forConfigName: config.sports[sport.rawValue]?.hkActivity ?? "")
        let id = session.id.uuidString
        var metadata: [String: Any] = [
            "ayuvo_workout_session_id": id,
            HKMetadataKeySyncIdentifier: "ayuvo.workout-gps.\(id)",
            HKMetadataKeySyncVersion: 1,
            HKMetadataKeyIndoorWorkout: false,
        ]
        if summary.elevationGainM > 0 {
            metadata[HKMetadataKeyElevationAscended] = HKQuantity(unit: .meter(), doubleValue: summary.elevationGainM)
        }
        // The id key keeps this estimate out of measured active energy, like the strength burns.
        let energyMetadata: [String: Any] = ["ayuvo_workout_session_id": id]
        let locations: [CLLocation] = zip(points, gpsAltitudes).compactMap { point, alt in
            guard let acc = point.hAccM, acc <= config.thresholds.maxHAccuracyM else { return nil }
            return CLLocation(coordinate: CLLocationCoordinate2D(latitude: point.lat, longitude: point.lon),
                              altitude: alt ?? 0, horizontalAccuracy: acc, verticalAccuracy: alt == nil ? -1 : 10,
                              course: -1, speed: point.speedMps ?? -1,
                              timestamp: Date(timeIntervalSince1970: Double(point.tMs) / 1000))
        }
        let pauseIntervals = pauses.map {
            DateInterval(start: Date(timeIntervalSince1970: Double($0.0) / 1000),
                         end: Date(timeIntervalSince1970: Double(max($0.0, $0.1)) / 1000))
        }
        await WorkoutHealthWriter.save(.init(
            activity: activity, start: session.startedAt, end: session.completedAt,
            energyKcal: Double(kcal), distanceM: summary.distanceM, distanceType: WorkoutHealthWriter.distanceType(for: activity),
            pauses: pauseIntervals, laps: laps, metadata: metadata, energyMetadata: energyMetadata, route: locations
        ))
    }

    private func intensity(speed: Double?) -> StrengthWorkoutIntensity {
        guard let speed else { return .moderate }
        let cuts: (Double, Double)
        switch sport {
        case .walk: cuts = (1.1, 1.6)
        case .run: cuts = (2.5, 3.3)
        case .cycle: cuts = (4.5, 7.0)
        case .hike: return .moderate
        }
        return speed < cuts.0 ? .light : (speed > cuts.1 ? .vigorous : .moderate)
    }

    /// Consecutive moving stretches of at least `min_segment_s` (no pause, above the auto-pause speed),
    /// with their mean speed, grade and heart rate: the input of `CardioFitness.vo2maxGps`.
    private func steadySegments(samples: [HeartRateWorkout.Sample]) -> [CardioFitness.Segment] {
        let config = WorkoutConfig.shared
        guard let sp = config.sports[sport.rawValue], !samples.isEmpty else { return [] }
        let kept = points.filter { ($0.hAccM ?? .infinity) <= config.thresholds.maxHAccuracyM }
        var segments: [CardioFitness.Segment] = []
        var segStart: Int?
        var dist = 0.0, time = 0.0
        func close(_ endIndex: Int) {
            guard let s = segStart, time >= config.thresholds.minSegmentS, dist > 0 else { return }
            let a = kept[s], b = kept[endIndex]
            let grade = (a.altM != nil && b.altM != nil) ? (b.altM! - a.altM!) / dist : 0
            let hr = samples.filter { $0.tMs >= a.tMs && $0.tMs <= b.tMs }.map(\.bpm)
            guard !hr.isEmpty else { return }
            segments.append(.init(speedMps: dist / time, grade: grade, hr: DerivedMath.mean(hr), durationS: time))
        }
        for i in 0..<max(0, kept.count - 1) {
            let q = kept[i], p = kept[i + 1]
            let dt = Double(p.tMs - q.tMs) / 1000
            let d = WorkoutMath.haversineM(q.lat, q.lon, p.lat, p.lon)
            let moving = dt > 0 && dt <= 30 && d / dt >= sp.autoPauseSpeedMps && d / dt <= sp.maxSpeedMps
                && !WorkoutMath.inPause(q.tMs, pauses) && !WorkoutMath.inPause(p.tMs, pauses)
            if moving {
                if segStart == nil { segStart = i; dist = 0; time = 0 }
                dist += d
                time += dt
                if time >= config.thresholds.minSegmentS {
                    close(i + 1)
                    segStart = nil
                }
            } else {
                segStart = nil
            }
        }
        return segments
    }

    private func reset() {
        phase = .idle
        startedAt = nil
        endedAt = nil
        points = []
        gpsAltitudes = []
        pauses = []
        pauseStartedAt = nil
        laps = []
        heartRateSamples = []
        live = nil
        currentPaceSecondsPerKm = nil
        currentSpeedMps = nil
        currentAltitude = nil
        cooperTest = false
    }

    // MARK: - Persistence

    private static var inProgressURL: URL { OutdoorRouteStore.directory.appendingPathComponent("in-progress.json") }

    private func persist(force: Bool = false) {
        guard let startedAt, force || Date().timeIntervalSince(lastPersist) >= 15 else { return }
        lastPersist = Date()
        let state = InProgress(
            sessionID: sessionID, sport: sport, cooperTest: cooperTest, startedAt: startedAt,
            points: points.map { OutdoorRoute.Point(t: $0.tMs, lat: $0.lat, lon: $0.lon, alt: $0.altM, acc: $0.hAccM, speed: $0.speedMps) },
            gpsAltitudes: gpsAltitudes, pauses: pauses.map { [$0.0, $0.1] }, pauseStartedAt: pauseStartedAt, laps: laps,
            heartRate: heartRateSamples.map { OutdoorRoute.HeartSample(t: $0.tMs, bpm: $0.bpm) }
        )
        guard let data = try? JSONEncoder().encode(state) else { return }
        try? FileManager.default.createDirectory(at: OutdoorRouteStore.directory, withIntermediateDirectories: true)
        try? data.write(to: Self.inProgressURL, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
    }

    private static func loadInProgress() -> InProgress? {
        guard let data = try? Data(contentsOf: inProgressURL) else { return nil }
        return try? JSONDecoder().decode(InProgress.self, from: data)
    }

    private static func deleteInProgress() {
        try? FileManager.default.removeItem(at: inProgressURL)
    }
}

private extension GpsTrack.Result {
    var avgPaceSecondsPerKmValue: Double? { avgPaceSPerKm }
}

/// CLLocationManager delegate; callbacks arrive on the main run loop (the manager is created there).
private final class LocationDelegateProxy: NSObject, CLLocationManagerDelegate {
    var onLocations: (([CLLocation]) -> Void)?
    var onAuthorization: ((CLAuthorizationStatus) -> Void)?

    nonisolated func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        MainActor.assumeIsolated { onLocations?(locations) }
    }

    nonisolated func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        let status = manager.authorizationStatus
        MainActor.assumeIsolated { onAuthorization?(status) }
    }

    nonisolated func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {}
}
