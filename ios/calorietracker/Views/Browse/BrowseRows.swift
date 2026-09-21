import SwiftUI

struct HealthIconBubble: View {
    let systemImage: String
    let tint: Color

    var body: some View {
        CategoryIconView(systemImage: systemImage, tint: tint)
    }
}

/// One data type row: name, latest value + unit, relative time. Cumulative / duration /
/// session types read as the latest day's total ("18,465 · Today"), not the last chunk.
struct HealthMetricRow: View {
    let type: HealthMetricType
    let summary: HealthTypeSummary?
    @Environment(HealthDataStore.self) private var store

    private var dayTotal: HealthDayTotal? {
        guard (summary?.count ?? 0) > 0 else { return nil }
        return store.dayTotal(for: type.id)
    }

    private var latestText: String {
        if let dayTotal {
            return HealthUnitFormatting.display(dayTotal.value, type: type).text
        }
        guard let latest = summary?.latest else { return "—" }
        if type.isBloodPressure {
            return HealthUnitFormatting.bloodPressureText(systolic: latest.value, diastolic: latest.value2) + " mmHg"
        }
        if type.kind == .category, type.isSleep == false {
            return latest.valueText ?? String(localized: "Logged")
        }
        if let value = latest.value {
            return HealthUnitFormatting.display(value, type: type).text
        }
        return latest.valueText ?? "—"
    }

    private var captionText: String? {
        if let dayTotal {
            return dayTotal.isToday ? String(localized: "Today") : HealthUnitFormatting.dayText(dayTotal.day, calendar: store.calendar)
        }
        return summary?.latest.map { HealthUnitFormatting.relativeText($0.endDate) }
    }

    var body: some View {
        HStack(spacing: 12) {
            HealthIconBubble(systemImage: MetricCatalog.descriptor(for: .health(type.id)).systemImage, tint: type.category.tint)
            VStack(alignment: .leading, spacing: 2) {
                Text(type.displayName)
                    .font(.system(.body, design: .rounded, weight: .medium))
                    .lineLimit(2)
                if let captionText {
                    Text(captionText)
                        .font(.system(.caption, design: .rounded))
                        .foregroundStyle(.secondary)
                } else {
                    Text("No data yet")
                        .font(.system(.caption, design: .rounded))
                        .foregroundStyle(.tertiary)
                }
            }
            Spacer()
            Text(latestText)
                .font(.system(.subheadline, design: .rounded, weight: .semibold))
                .foregroundStyle(summary?.latest == nil ? .tertiary : .primary)
                .lineLimit(1)
                .minimumScaleFactor(0.7)
        }
        .accessibilityElement(children: .combine)
    }
}

/// A Browse row for an app metric: icon, title and today's / latest value from the stores.
struct AppMetricLinkRow: View {
    let metric: AppMetric
    var title: String?

    @Environment(HealthDataStore.self) private var healthStore
    @Environment(FoodStore.self) private var foodStore
    @Environment(WaterStore.self) private var waterStore
    @Environment(FastingStore.self) private var fastingStore
    @Environment(WeightStore.self) private var weightStore
    @Environment(BodyFatStore.self) private var bodyFatStore
    @Environment(StrengthWorkoutStore.self) private var workoutStore
    @Environment(ImportedHealthWorkoutStore.self) private var importedWorkoutStore
    @Environment(ProfileStore.self) private var profileStore

    private var reading: (value: String, unit: String, at: Date?)? {
        let sources = MetricDataSources(
            food: foodStore, water: waterStore, fasting: fastingStore, weight: weightStore, bodyFat: bodyFatStore,
            workouts: workoutStore, importedWorkouts: importedWorkoutStore, health: healthStore, profile: profileStore
        )
        let entries = AppMetricSampleExtractor.entries(for: metric, sources: sources)
        let aggregation = MetricCatalog.descriptor(for: .app(metric)).aggregation
        let tile = MetricTileMath.appTile(entries: entries, aggregation: aggregation, now: Date(), calendar: .current)
        guard tile.value != nil else { return nil }
        let display = AppMetricFormat.display(tile.value, metric: metric)
        return (display.value, display.unit, tile.at)
    }

    var body: some View {
        let descriptor = MetricCatalog.descriptor(for: .app(metric))
        let reading = reading
        MetricRow(
            systemImage: descriptor.systemImage,
            tint: descriptor.tint,
            title: title ?? descriptor.title,
            subtitle: reading == nil ? String(localized: "No data yet") : reading?.at.map { HealthUnitFormatting.relativeText($0) },
            value: reading.map { $0.unit.isEmpty ? $0.value : "\($0.value) \($0.unit)" },
            dimmed: reading == nil
        )
    }
}
