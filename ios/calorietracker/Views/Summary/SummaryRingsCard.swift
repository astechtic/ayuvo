import SwiftUI

/// Eat · Move · Drink. The Move ring asks to connect Apple Health when sync is off, and the
/// Drink ring is hidden while water tracking is off. Values are never invented.
struct SummaryRingsCard: View {
    @Environment(AppNavigator.self) private var navigator
    @Environment(FoodStore.self) private var foodStore
    @Environment(WaterStore.self) private var waterStore
    @Environment(ProfileStore.self) private var profileStore
    @Environment(HealthKitManager.self) private var healthKitManager
    @Environment(\.scenePhase) private var scenePhase
    @AppStorage("healthKitEnabled") private var healthKitEnabled = false
    @AppStorage(WaterSettings.enabledKey) private var waterTrackingEnabled = false
    @AppStorage(WaterSettings.dailyGoalKey) private var waterDailyGoal = WaterSettings.defaultDailyGoalMl
    @AppStorage(ActivitySettings.dailyStepGoalKey) private var stepGoal = ActivitySettings.defaultDailyStepGoal

    @State private var steps: Int?
    @State private var animationEpoch = 0

    private var waterUnit: WaterUnit { MetricCatalog.waterUnit }

    private var rings: [RingSpec] {
        var specs: [RingSpec] = []

        let eaten = foodStore.calories(for: .now)
        let calorieGoal = profileStore.profile.effectiveCalories
        let eat = MetricsReference.ringProgress(value: Double(eaten), goal: Double(calorieGoal))
        specs.append(RingSpec(
            id: "eat",
            title: String(localized: "Eat"),
            progress: eat.progress ?? 0,
            tint: AyuvoPalette.nutrition,
            valueText: eaten.formatted(),
            goalText: calorieGoal > 0 ? String(localized: "\(calorieGoal.formatted()) kcal") : "",
            state: eat.state == .value ? .value : .noGoal,
            action: { navigator.summaryPath.append(MetricRoute.detail(.app(.calories))) }
        ))

        let stepValue = steps.map(Double.init)
        let move = MetricsReference.ringProgress(value: stepValue, goal: Double(stepGoal))
        specs.append(RingSpec(
            id: "move",
            title: String(localized: "Move"),
            progress: move.progress ?? 0,
            tint: AyuvoPalette.activity,
            valueText: steps?.formatted() ?? "—",
            goalText: String(localized: "\(stepGoal.formatted()) steps"),
            state: healthKitEnabled ? (move.state == .value ? .value : .noData) : .connect,
            action: {
                if healthKitEnabled {
                    navigator.summaryPath.append(MetricRoute.detail(.health("steps")))
                } else {
                    navigator.resetBrowse()
                    navigator.selectedTab = .browse
                }
            }
        ))

        if waterTrackingEnabled {
            let ml = waterStore.total(on: .now)
            let drink = MetricsReference.ringProgress(value: Double(ml), goal: Double(waterDailyGoal))
            specs.append(RingSpec(
                id: "drink",
                title: String(localized: "Drink"),
                progress: drink.progress ?? 0,
                tint: AyuvoPalette.hydration,
                valueText: waterUnit.displayValue(forMilliliters: ml),
                goalText: waterUnit.formatted(milliliters: waterDailyGoal),
                state: drink.state == .value ? .value : .noGoal,
                action: { navigator.summaryPath.append(MetricRoute.detail(.app(.water))) }
            ))
        }
        return specs
    }

    var body: some View {
        RingTrioView(rings: rings, animationEpoch: animationEpoch)
            .frame(maxWidth: .infinity, alignment: .leading)
            .ayuvoCard()
            // `contain` keeps each ring's own identifier addressable.
            .accessibilityElement(children: .contain)
            .accessibilityIdentifier("summary.rings")
            .task(id: healthKitEnabled) { await refreshSteps() }
            .onChange(of: scenePhase) { _, phase in
                guard phase == .active else { return }
                animationEpoch += 1
                Task { await refreshSteps() }
            }
    }

    private func refreshSteps() async {
        guard healthKitEnabled else {
            steps = nil
            return
        }
        steps = await healthKitManager.fetchStepsForDay(Date())
    }
}
