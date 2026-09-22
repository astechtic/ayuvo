import Foundation
import Observation
import WidgetKit

/// Pure assembly of `WidgetDashboardSnapshot` from already-read values (docs/widgets.md).
/// Formatting follows the Summary rings: values are never invented, a missing reading is "—".
enum WidgetDashboardBuilder {
    struct Input {
        var now: Date
        var calendar: Calendar = .current
        var caloriesToday: Int
        var calorieGoal: Int
        var healthConnected: Bool
        var stepsToday: Int?
        var stepGoal: Int
        var waterEnabled: Bool
        var waterMlToday: Int
        var waterGoalMl: Int
        var waterUnit: WaterUnit
        var fastingEnabled: Bool
        var activeFast: FastingSession?
        /// Nil when there is no medications database.
        var medications: MedicationTodayTimeline?
        var weight: WidgetDashboardSnapshot.Row?
        var bodyFat: WidgetDashboardSnapshot.Row?
        var workoutToday: WidgetDashboardSnapshot.Row?
        var metrics: [WidgetDashboardSnapshot.Metric]
    }

    static func build(_ input: Input) -> WidgetDashboardSnapshot {
        var doses: WidgetDashboardSnapshot.Medications?
        if let timeline = input.medications { doses = medications(timeline) }
        return WidgetDashboardSnapshot(
            version: WidgetDashboardSnapshot.currentVersion,
            generatedAt: input.now,
            dayStart: input.calendar.startOfDay(for: input.now),
            rings: rings(input),
            fasting: WidgetDashboardSnapshot.Fasting(
                enabled: input.fastingEnabled,
                activeStartedAt: input.fastingEnabled ? input.activeFast?.startedAt : nil,
                goalMinutes: input.fastingEnabled ? input.activeFast?.goalMinutes : nil
            ),
            medications: doses,
            weight: input.weight,
            bodyFat: input.bodyFat,
            workoutToday: input.workoutToday,
            metrics: input.metrics,
            waterTrackingEnabled: input.waterEnabled,
            fastingTrackingEnabled: input.fastingEnabled
        )
    }

    static func rings(_ input: Input) -> WidgetDashboardSnapshot.Rings {
        let eat = MetricsReference.ringProgress(value: Double(input.caloriesToday), goal: Double(input.calorieGoal))
        let eatRing = WidgetDashboardSnapshot.Ring(
            progress: eat.progress ?? 0,
            valueText: input.caloriesToday.formatted(),
            goalText: input.calorieGoal > 0 ? String(localized: "\(input.calorieGoal.formatted()) kcal") : "",
            state: eat.state == .value ? .value : .noGoal,
            emptyValueText: 0.formatted()
        )

        let move = MetricsReference.ringProgress(value: input.stepsToday.map(Double.init), goal: Double(input.stepGoal))
        let moveState: WidgetDashboardSnapshot.Ring.State
        if !input.healthConnected {
            moveState = .connect
        } else {
            moveState = move.state == .value ? .value : .noData
        }
        let moveRing = WidgetDashboardSnapshot.Ring(
            progress: input.healthConnected ? (move.progress ?? 0) : 0,
            valueText: input.healthConnected ? (input.stepsToday?.formatted() ?? "—") : "—",
            goalText: String(localized: "\(input.stepGoal.formatted()) steps"),
            state: moveState,
            emptyValueText: "—"
        )

        var drinkRing: WidgetDashboardSnapshot.Ring?
        if input.waterEnabled {
            let drink = MetricsReference.ringProgress(value: Double(input.waterMlToday), goal: Double(input.waterGoalMl))
            drinkRing = WidgetDashboardSnapshot.Ring(
                progress: drink.progress ?? 0,
                valueText: input.waterUnit.displayValue(forMilliliters: input.waterMlToday),
                goalText: input.waterUnit.formatted(milliliters: input.waterGoalMl),
                state: drink.state == .value ? .value : .noGoal,
                emptyValueText: input.waterUnit.displayValue(forMilliliters: 0)
            )
        }
        return WidgetDashboardSnapshot.Rings(eat: eatRing, move: moveRing, drink: drinkRing)
    }

    static func medications(_ timeline: MedicationTodayTimeline) -> WidgetDashboardSnapshot.Medications {
        let doses = timeline.slots
            .flatMap(\.items)
            .filter { $0.kind == .scheduled }
            .map { item in
                WidgetDashboardSnapshot.Dose(
                    name: timeline.medication(item.medicationID)?.displayName ?? "",
                    scheduledAt: Date(timeIntervalSince1970: Double(item.scheduledAtMs) / 1000),
                    status: item.status.rawValue
                )
            }
            .sorted { $0.scheduledAt < $1.scheduledAt }
        return WidgetDashboardSnapshot.Medications(doses: doses, taken: timeline.summary.taken, total: timeline.summary.total)
    }
}

/// Keeps the dashboard snapshot current and reloads the new widgets (Today, My Metrics,
/// Quick Log). Observes store revisions and `UserDefaults` changes, debounced by one second,
/// and writes only when the content changed.
@MainActor
final class WidgetDashboardWriter {
    static let kinds = ["TodayWidget", "MyMetricsWidget", "QuickLogWidget"]
    static let debounce: Duration = .seconds(1)

    private let sources: MetricDataSources
    private let medicationStore: MedicationStore
    private let healthKitManager: HealthKitManager
    private var started = false
    private var pending: Task<Void, Never>?
    private var defaultsObserver: NSObjectProtocol?

    init(sources: MetricDataSources, medicationStore: MedicationStore, healthKitManager: HealthKitManager) {
        self.sources = sources
        self.medicationStore = medicationStore
        self.healthKitManager = healthKitManager
    }

    /// Idempotent; call once the app has a profile.
    func start() {
        guard !started else {
            schedule()
            return
        }
        started = true
        observeStores()
        defaultsObserver = NotificationCenter.default.addObserver(
            forName: UserDefaults.didChangeNotification, object: nil, queue: .main
        ) { [weak self] _ in
            MainActor.assumeIsolated { self?.schedule() }
        }
        schedule()
    }

    func schedule() {
        pending?.cancel()
        pending = Task { [weak self] in
            try? await Task.sleep(for: Self.debounce)
            guard !Task.isCancelled else { return }
            await self?.publish()
        }
    }

    private func observeStores() {
        withObservationTracking {
            _ = sources.food.revision
            _ = sources.water.revision
            _ = sources.fasting.revision
            _ = sources.weight.revision
            _ = sources.bodyFat.revision
            _ = sources.workouts.revision
            _ = sources.importedWorkouts.revision
            _ = sources.health.snapshotRevision
            _ = sources.profile.profile
            _ = medicationStore.revision
            _ = medicationStore.today
        } onChange: {
            Task { @MainActor [weak self] in
                self?.schedule()
                self?.observeStores()
            }
        }
    }

    func publish(now: Date = Date()) async {
        guard UserProfile.load() != nil else {
            if WidgetDashboardSnapshot.read() != nil {
                WidgetDashboardSnapshot.clear()
                Self.reload()
            }
            return
        }
        let snapshot = await makeSnapshot(now: now)
        if let current = WidgetDashboardSnapshot.read(), current.hasSameContent(as: snapshot) { return }
        WidgetDashboardSnapshot.write(snapshot)
        Self.reload()
    }

    static func reload() {
        for kind in kinds { WidgetCenter.shared.reloadTimelines(ofKind: kind) }
    }

    static func clear() {
        WidgetDashboardSnapshot.clear()
        reload()
    }

    // MARK: - Assembly

    private func makeSnapshot(now: Date) async -> WidgetDashboardSnapshot {
        let defaults = UserDefaults.standard
        let calendar = Calendar.current
        let healthConnected = defaults.bool(forKey: "healthKitEnabled")
        let steps = healthConnected ? await healthKitManager.fetchStepsForDay(now) : nil
        let stepGoal = ActivitySettings.dailyStepGoal(defaults: defaults)
        let waterEnabled = defaults.bool(forKey: WaterSettings.enabledKey)
        let fastingEnabled = defaults.bool(forKey: FastingSettings.enabledKey)
        let storedWaterGoal = defaults.integer(forKey: WaterSettings.dailyGoalKey)

        // Medications: only when the database already exists (never create it for a widget).
        var timeline: MedicationTodayTimeline?
        if medicationStore.runtime.databaseExists || medicationStore.isOpen {
            if !medicationStore.hasLoadedOnce {
                await medicationStore.reload()
            }
            timeline = medicationStore.today
        }

        let healthIDs = WidgetMetricOption.allCases.map(\.rawValue).filter { MetricKey(pinID: $0).map(Self.isHealth) ?? false }
        let healthTiles = await sources.health.widgetTiles(for: healthIDs)

        return WidgetDashboardBuilder.build(.init(
            now: now,
            calendar: calendar,
            caloriesToday: sources.food.calories(for: now),
            calorieGoal: sources.profile.profile.effectiveCalories,
            healthConnected: healthConnected,
            stepsToday: steps,
            stepGoal: stepGoal,
            waterEnabled: waterEnabled,
            waterMlToday: sources.water.total(on: now),
            waterGoalMl: storedWaterGoal > 0 ? storedWaterGoal : WaterSettings.defaultDailyGoalMl,
            waterUnit: MetricCatalog.waterUnit,
            fastingEnabled: fastingEnabled,
            activeFast: sources.fasting.activeSession,
            medications: timeline,
            weight: latestRow(.weight, title: String(localized: "Weight"), now: now, calendar: calendar),
            bodyFat: latestRow(.bodyFat, title: String(localized: "Body Fat"), now: now, calendar: calendar),
            workoutToday: workoutRow(now: now),
            metrics: metrics(now: now, calendar: calendar, healthTiles: healthTiles, steps: steps, stepGoal: stepGoal,
                             waterEnabled: waterEnabled, fastingEnabled: fastingEnabled)
        ))
    }

    private static func isHealth(_ key: MetricKey) -> Bool {
        if case .health = key { return true }
        return false
    }

    private func latestRow(_ metric: AppMetric, title: String, now: Date, calendar: Calendar) -> WidgetDashboardSnapshot.Row? {
        let entries = AppMetricSampleExtractor.entries(for: metric, sources: sources, now: now, calendar: calendar)
        let tile = MetricTileMath.appTile(entries: entries, aggregation: .last, now: now, calendar: calendar)
        guard let value = tile.value else { return nil }
        return WidgetDashboardSnapshot.Row(title: title, valueText: AppMetricFormat.text(value, metric: metric), at: tile.at)
    }

    /// Same wording as the Summary "Workout today" card.
    private func workoutRow(now: Date) -> WidgetDashboardSnapshot.Row? {
        if let session = sources.workouts.latestSession(on: now) {
            let exercises = session.exercises.count
            var parts = [exercises == 1 ? String(localized: "1 exercise") : String(localized: "\(exercises) exercises")]
            if let burn = session.caloriesBurned { parts.append(String(localized: "\(burn.formatted()) kcal")) }
            return WidgetDashboardSnapshot.Row(title: String(localized: "Workout today"), valueText: parts.joined(separator: " · "), at: session.completedAt)
        }
        let imported = sources.importedWorkouts.workouts(on: now)
        guard !imported.isEmpty else { return nil }
        let text = imported.count == 1
            ? String(localized: "1 workout from Apple Health")
            : String(localized: "\(imported.count) workouts from Apple Health")
        return WidgetDashboardSnapshot.Row(title: String(localized: "Workout today"), valueText: text, at: nil)
    }

    private func metrics(
        now: Date, calendar: Calendar, healthTiles: [HealthHomeTileModel], steps: Int?, stepGoal: Int,
        waterEnabled: Bool, fastingEnabled: Bool
    ) -> [WidgetDashboardSnapshot.Metric] {
        WidgetMetricOption.allCases.compactMap { option in
            if option == .nextDose {
                return WidgetDashboardSnapshot.Metric(
                    key: option.rawValue, title: option.title, systemImage: option.systemImage,
                    tintHex: Self.tintHex("medications"), valueText: "—", unitText: "", progress: nil,
                    dayScoped: true, at: nil, caption: nil
                )
            }
            guard let key = MetricKey(pinID: option.rawValue) else { return nil }
            let descriptor = MetricCatalog.descriptor(for: key)
            let tint = Self.tintHex(descriptor.domainID)
            switch key {
            case .app(let metric):
                let off = (metric == .water && !waterEnabled) || (metric == .fasting && !fastingEnabled)
                let entries = AppMetricSampleExtractor.entries(for: metric, sources: sources, now: now, calendar: calendar)
                let tile = MetricTileMath.appTile(entries: entries, aggregation: descriptor.aggregation, now: now, calendar: calendar)
                let display = AppMetricFormat.display(off ? nil : tile.value, metric: metric)
                var progress: Double?
                if !off, let value = tile.value, let goal = sources.goal(for: descriptor.goalSource), goal > 0,
                   descriptor.aggregation != .last {
                    progress = min(1, max(0, value / goal))
                }
                return WidgetDashboardSnapshot.Metric(
                    key: option.rawValue, title: descriptor.title, systemImage: descriptor.systemImage, tintHex: tint,
                    valueText: display.value, unitText: display.unit, progress: progress,
                    dayScoped: descriptor.aggregation != .last && descriptor.aggregation != .avg,
                    at: off ? nil : tile.at, caption: off ? String(localized: "Off") : nil
                )
            case .health(let typeID):
                let tile = healthTiles.first { $0.typeID == typeID }
                let type = sources.health.metricType(for: typeID)
                var progress: Double?
                if typeID == "steps", let steps, stepGoal > 0 {
                    progress = min(1, Double(steps) / Double(stepGoal))
                }
                let cumulative = type.kind == .cumulative || type.kind == .duration || type.kind == .session
                return WidgetDashboardSnapshot.Metric(
                    key: option.rawValue, title: type.displayName, systemImage: descriptor.systemImage, tintHex: tint,
                    valueText: tile?.valueText ?? "—", unitText: tile?.unitText ?? HealthUnitFormatting.unitLabel(for: type),
                    progress: progress, dayScoped: cumulative && !type.isSleep, at: tile?.at, caption: nil
                )
            }
        }
    }

    private static func tintHex(_ domainID: String) -> String {
        MetricCatalogData.shared.domain(domainID)?.colourHex ?? "#8E8E93"
    }
}
