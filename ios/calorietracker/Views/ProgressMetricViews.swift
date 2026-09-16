import Charts
import SwiftUI

enum ProgressHistoryCountText {
    static func localized(_ count: Int) -> String {
        String(localized: "\(count) entry · tap to view or delete")
    }
}

enum ProgressMetric: String, CaseIterable, Identifiable, Equatable {
    case weight
    case bodyFat
    case workouts

    var id: String { rawValue }

    var title: String {
        switch self {
        case .weight: String(localized: "Weight")
        case .bodyFat: String(localized: "Body Fat")
        case .workouts: String(localized: "Workouts")
        }
    }

    var icon: String {
        switch self {
        case .weight: "scalemass.fill"
        case .bodyFat: "percent"
        case .workouts: "dumbbell.fill"
        }
    }

    static func available(
        bodyFatAvailable: Bool,
        workoutBurnAvailable: Bool,
        importedWorkoutsAvailable: Bool = false
    ) -> [ProgressMetric] {
        var metrics: [ProgressMetric] = [.weight]
        if bodyFatAvailable { metrics.append(.bodyFat) }
        if workoutBurnAvailable || importedWorkoutsAvailable { metrics.append(.workouts) }
        return metrics
    }
}

/// Segmented capsule for the Health tab. Segments share the track equally and the gradient pill
/// slides between them via matched geometry. With five or more panes the unselected segments show
/// their icon only (the selected one keeps icon + title); every button still carries the title as
/// its accessibility label, so `app.buttons["Health Data"]` keeps working in UI tests.
struct ProgressOverviewModeSelector: View {
    @Binding var selection: ProgressOverviewMode
    @Namespace private var pillNamespace

    private var compact: Bool { ProgressOverviewMode.allCases.count >= 5 }

    var body: some View {
        HStack(spacing: 4) {
            ForEach(ProgressOverviewMode.allCases) { mode in
                segment(for: mode)
            }
        }
        .padding(4)
        .background(AppColors.appCard, in: Capsule())
        .overlay {
            Capsule().stroke(AppColors.calorie.opacity(0.12), lineWidth: 0.75)
        }
        .accessibilityElement(children: .contain)
        .accessibilityLabel(String(localized: "Progress view"))
    }

    private func segment(for mode: ProgressOverviewMode) -> some View {
        let isSelected = selection == mode
        return Button {
            guard selection != mode else { return }
            withAnimation(.snappy) { selection = mode }
        } label: {
            Label(compact ? mode.compactTitle : mode.title, systemImage: mode.icon)
                .labelStyle(SelectorLabelStyle(showsTitle: !compact || isSelected, compact: compact))
                .font(.system(.subheadline, design: .rounded, weight: .semibold))
                .foregroundStyle(isSelected ? Color.white : Color.secondary)
                .lineLimit(1)
                .minimumScaleFactor(compact ? 0.6 : 0.8)
                .padding(.horizontal, compact ? 4 : 10)
                .frame(maxWidth: .infinity, minHeight: 44)
                .background {
                    if isSelected {
                        Capsule()
                            .fill(
                                LinearGradient(
                                    colors: AppColors.calorieGradient,
                                    startPoint: .leading,
                                    endPoint: .trailing
                                )
                            )
                            .matchedGeometryEffect(id: "selectedPill", in: pillNamespace)
                    }
                }
                .contentShape(Capsule())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(mode.title)
        .accessibilityAddTraits(isSelected ? .isSelected : [])
    }
}

/// Icon-only or icon + title, switchable per segment without changing the view identity
/// (so the matched-geometry pill animation is unaffected).
private struct SelectorLabelStyle: LabelStyle {
    let showsTitle: Bool
    var compact = false

    func makeBody(configuration: Configuration) -> some View {
        HStack(spacing: compact ? 3 : 5) {
            configuration.icon
            if showsTitle {
                if compact {
                    configuration.title
                        .font(.system(.footnote, design: .rounded, weight: .semibold))
                } else {
                    configuration.title
                }
            }
        }
    }
}

struct ProgressMetricSelector: View {
    let metrics: [ProgressMetric]
    @Binding var selection: ProgressMetric

    var body: some View {
        Picker("Progress metric", selection: $selection) {
            ForEach(metrics) { metric in
                Text(metric.title).tag(metric)
            }
        }
        .pickerStyle(.segmented)
        .tint(AppColors.calorie)
        .accessibilityLabel("Progress metric")
        .onChange(of: metrics) { _, available in
            if !available.contains(selection), let first = available.first {
                selection = first
            }
        }
    }
}

struct WorkoutBurnDay: Identifiable, Equatable {
    let date: Date
    let calories: Int
    var id: Date { date }
}

enum WorkoutBurnAggregation {
    static let reliableCalories = 1...5_000

    static func isReliable(_ calories: Int?) -> Bool {
        guard let calories else { return false }
        return reliableCalories.contains(calories)
    }

    /// The burn calculator owns one estimate per diary day. Older data or a
    /// restore race can still contain duplicates, so choose the newest/highest
    /// sync-version record instead of summing and overstating the workout.
    static func daily(
        sessions: [StrengthWorkoutSession],
        in range: ClosedRange<Date>,
        calendar: Calendar = .current
    ) -> [WorkoutBurnDay] {
        var preferredByDay: [String: StrengthWorkoutSession] = [:]
        for session in sessions {
            guard isReliable(session.caloriesBurned) else { continue }
            let day = calendar.startOfDay(for: session.calendarDiaryDate)
            guard range.contains(day) else { continue }
            let key = session.stableDiaryDateKey
            if let current = preferredByDay[key], !shouldPrefer(session, over: current) { continue }
            preferredByDay[key] = session
        }
        return preferredByDay.values.compactMap { session in
            guard let calories = session.caloriesBurned else { return nil }
            return WorkoutBurnDay(
                date: calendar.startOfDay(for: session.calendarDiaryDate),
                calories: calories
            )
        }
        .sorted { $0.date < $1.date }
    }

    private static func shouldPrefer(
        _ candidate: StrengthWorkoutSession,
        over current: StrengthWorkoutSession
    ) -> Bool {
        let candidateVersion = candidate.healthSyncVersion ?? 0
        let currentVersion = current.healthSyncVersion ?? 0
        if candidateVersion != currentVersion { return candidateVersion > currentVersion }
        return candidate.completedAt > current.completedAt
    }
}

struct WorkoutBurnChartSection: View {
    let sessions: [StrengthWorkoutSession]
    let dateRange: ClosedRange<Date>

    @Environment(\.dynamicTypeSize) private var dynamicTypeSize

    private var days: [WorkoutBurnDay] {
        WorkoutBurnAggregation.daily(sessions: sessions, in: dateRange)
    }

    private var total: Int { days.reduce(0) { $0 + $1.calories } }
    private var average: Int { days.isEmpty ? 0 : Int((Double(total) / Double(days.count)).rounded()) }
    private var latest: Int { days.last?.calories ?? 0 }

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Label("Calculated Workout Burn", systemImage: "flame.fill")
                .font(.system(.headline, design: .rounded, weight: .semibold))

            if days.isEmpty {
                ProgressMetricEmptyState("No calculated workout burn in this range")
            } else {
                workoutStatBadges

                Chart(days) { day in
                    BarMark(
                        x: .value("Date", day.date, unit: .day),
                        y: .value("Calculated calories burned", day.calories)
                    )
                    .foregroundStyle(
                        LinearGradient(
                            colors: AppColors.calorieGradient,
                            startPoint: .bottom,
                            endPoint: .top
                        )
                    )
                    .clipShape(.rect(cornerRadius: 4))
                }
                .chartXAxis {
                    AxisMarks(values: .automatic(desiredCount: 5)) { _ in
                        AxisGridLine(stroke: StrokeStyle(lineWidth: 0.6, dash: [3, 4]))
                            .foregroundStyle(Color.primary.opacity(0.11))
                        AxisValueLabel(format: .dateTime.month(.abbreviated).day())
                            .foregroundStyle(Color.secondary)
                    }
                }
                .chartYAxis {
                    AxisMarks(position: .trailing, values: .automatic(desiredCount: 5)) {
                        AxisGridLine(stroke: StrokeStyle(lineWidth: 0.6))
                            .foregroundStyle(Color.primary.opacity(0.10))
                        AxisValueLabel()
                            .foregroundStyle(Color.secondary)
                    }
                }
                .chartPlotStyle { plotArea in
                    plotArea.background(
                        AppColors.calorie.opacity(0.025),
                        in: RoundedRectangle(cornerRadius: 10, style: .continuous)
                    )
                }
                .frame(height: 190)
            }

            Text("Only workout burns calculated in Ayuvo are shown. Workouts without a burn estimate are not included.")
                .font(.system(.caption2, design: .rounded))
                .foregroundStyle(.secondary)
        }
        .padding()
        .progressMetricCardStyle()
    }

    @ViewBuilder
    private var workoutStatBadges: some View {
        if dynamicTypeSize.isAccessibilitySize {
            LazyVGrid(
                columns: [GridItem(.flexible()), GridItem(.flexible())],
                spacing: 8
            ) {
                workoutStatBadgeContent
            }
        } else {
            HStack(spacing: 8) {
                workoutStatBadgeContent
            }
        }
    }

    @ViewBuilder
    private var workoutStatBadgeContent: some View {
        StatBadge(label: "Total", value: "\(total.formatted()) kcal")
        StatBadge(label: "Average", value: "\(average.formatted()) kcal")
        StatBadge(label: "Latest", value: "\(latest.formatted()) kcal")
        StatBadge(label: "Days", value: days.count.formatted())
    }
}

struct ImportedHealthWorkoutDay: Identifiable, Equatable {
    let date: Date
    let sessionCount: Int
    let totalCalories: Int
    var id: Date { date }
}

enum ImportedHealthWorkoutAggregation {
    static func daily(
        workouts: [ImportedHealthWorkout],
        in range: ClosedRange<Date>,
        calendar: Calendar = .current
    ) -> [ImportedHealthWorkoutDay] {
        var grouped: [String: (date: Date, count: Int, calories: Int)] = [:]
        for workout in workouts {
            let day = calendar.startOfDay(for: workout.calendarDiaryDate)
            guard range.contains(day) else { continue }
            var bucket = grouped[workout.diaryDateKey] ?? (day, 0, 0)
            bucket.count += 1
            bucket.calories += workout.totalEnergyBurned ?? 0
            grouped[workout.diaryDateKey] = bucket
        }
        return grouped.values.map {
            ImportedHealthWorkoutDay(date: $0.date, sessionCount: $0.count, totalCalories: $0.calories)
        }
        .sorted { $0.date < $1.date }
    }
}

struct ImportedHealthWorkoutChartSection: View {
    let workouts: [ImportedHealthWorkout]
    let dateRange: ClosedRange<Date>

    @Environment(\.dynamicTypeSize) private var dynamicTypeSize

    private var days: [ImportedHealthWorkoutDay] {
        ImportedHealthWorkoutAggregation.daily(workouts: workouts, in: dateRange)
    }

    private var totalSessions: Int { days.reduce(0) { $0 + $1.sessionCount } }
    private var totalCalories: Int { days.reduce(0) { $0 + $1.totalCalories } }
    private var averageCalories: Int {
        let daysWithCalories = days.filter { $0.totalCalories > 0 }
        guard !daysWithCalories.isEmpty else { return 0 }
        let sum = daysWithCalories.reduce(0) { $0 + $1.totalCalories }
        return Int((Double(sum) / Double(daysWithCalories.count)).rounded())
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Label("Apple Health Workouts", systemImage: "applewatch")
                .font(.system(.headline, design: .rounded, weight: .semibold))

            if days.isEmpty {
                ProgressMetricEmptyState("No Apple Health workouts in this range")
            } else {
                workoutStatBadges

                Chart(days) { day in
                    BarMark(
                        x: .value("Date", day.date, unit: .day),
                        y: .value("Sessions", day.sessionCount)
                    )
                    .foregroundStyle(
                        LinearGradient(
                            colors: [AppColors.calorie.opacity(0.55), AppColors.calorie],
                            startPoint: .bottom,
                            endPoint: .top
                        )
                    )
                    .clipShape(.rect(cornerRadius: 4))
                }
                .chartXAxis {
                    AxisMarks(values: .automatic(desiredCount: 5)) { _ in
                        AxisGridLine(stroke: StrokeStyle(lineWidth: 0.6, dash: [3, 4]))
                            .foregroundStyle(Color.primary.opacity(0.11))
                        AxisValueLabel(format: .dateTime.month(.abbreviated).day())
                            .foregroundStyle(Color.secondary)
                    }
                }
                .chartYAxis {
                    AxisMarks(position: .trailing, values: .automatic(desiredCount: 4)) {
                        AxisGridLine(stroke: StrokeStyle(lineWidth: 0.6))
                            .foregroundStyle(Color.primary.opacity(0.10))
                        AxisValueLabel()
                            .foregroundStyle(Color.secondary)
                    }
                }
                .chartPlotStyle { plotArea in
                    plotArea.background(
                        AppColors.calorie.opacity(0.025),
                        in: RoundedRectangle(cornerRadius: 10, style: .continuous)
                    )
                }
                .frame(height: 190)
            }

            Text("Imported from Apple Health and Apple Watch. Read-only here — edit or delete them in the Health app. These sessions do not affect Energy Burn goals.")
                .font(.system(.caption2, design: .rounded))
                .foregroundStyle(.secondary)
        }
        .padding()
        .progressMetricCardStyle()
    }

    @ViewBuilder
    private var workoutStatBadges: some View {
        if dynamicTypeSize.isAccessibilitySize {
            LazyVGrid(
                columns: [GridItem(.flexible()), GridItem(.flexible())],
                spacing: 8
            ) {
                workoutStatBadgeContent
            }
        } else {
            HStack(spacing: 8) {
                workoutStatBadgeContent
            }
        }
    }

    @ViewBuilder
    private var workoutStatBadgeContent: some View {
        StatBadge(label: "Sessions", value: totalSessions.formatted())
        StatBadge(label: "Calories", value: "\(totalCalories.formatted()) kcal")
        StatBadge(label: "Average", value: "\(averageCalories.formatted()) kcal")
        StatBadge(label: "Days", value: days.count.formatted())
    }
}

struct ProgressMetricEmptyState: View {
    let message: LocalizedStringKey

    init(_ message: LocalizedStringKey) {
        self.message = message
    }

    var body: some View {
        Text(message)
            .font(.system(.subheadline, design: .rounded))
            .foregroundStyle(.secondary)
            .multilineTextAlignment(.center)
            .frame(maxWidth: .infinity, minHeight: 80)
    }
}

private struct ProgressMetricCardStyle: ViewModifier {
    func body(content: Content) -> some View {
        let shape = RoundedRectangle(cornerRadius: 20, style: .continuous)
        content
            .background(AppColors.appCard)
            .overlay { shape.stroke(AppColors.calorie.opacity(0.09), lineWidth: 0.75) }
            .compositingGroup()
            .clipShape(shape)
    }
}

private extension View {
    func progressMetricCardStyle() -> some View {
        modifier(ProgressMetricCardStyle())
    }
}
