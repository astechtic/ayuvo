import SwiftUI

/// Which "How we calculate this" text a screen shows (config `methodology` keys).
enum InsightsTopic: String {
    case recovery, healthAge = "health_age", dailyReview = "daily_review", patterns, baselines, background
}

/// One "Your inputs" line: what was used, or why it is missing.
struct InsightsInputRow: Identifiable, Hashable {
    let id: String
    let title: String
    let value: String
    var detail: String?
    var missing = false
}

/// "How we calculate this": the config methodology text for the topic, its weights table, and the person's
/// actual inputs (values used and the components that were missing). Rendered from the same config the
/// engines use, so the explanation always matches the math.
struct InsightMethodologySheet: View {
    let topic: InsightsTopic
    let inputs: [InsightsInputRow]
    @Environment(\.dismiss) private var dismiss

    private var config: InsightsConfig { .shared }
    private var methodology: InsightsConfig.Methodology? { config.methodology[topic.rawValue] }

    var body: some View {
        NavigationStack {
            List {
                if let methodology {
                    ForEach(methodology.sections, id: \.self) { section in
                        Section {
                            Text(section.body)
                                .font(.system(.subheadline, design: .rounded))
                                .fixedSize(horizontal: false, vertical: true)
                        } header: {
                            Text(section.heading)
                        }
                    }
                }

                let weights = weightRows
                if !weights.isEmpty {
                    Section {
                        ForEach(weights, id: \.0) { row in
                            HStack {
                                Text(row.0)
                                Spacer()
                                Text(row.1)
                                    .monospacedDigit()
                                    .foregroundStyle(.secondary)
                            }
                            .font(.system(.subheadline, design: .rounded))
                        }
                    } header: {
                        Text(weightsHeader)
                    }
                }

                Section {
                    if inputs.isEmpty {
                        Text("No inputs yet. Values appear here once your data has synced.")
                            .foregroundStyle(.secondary)
                    }
                    ForEach(inputs) { row in
                        VStack(alignment: .leading, spacing: 2) {
                            HStack(alignment: .firstTextBaseline) {
                                Text(row.title)
                                Spacer()
                                Text(row.value)
                                    .monospacedDigit()
                                    .foregroundStyle(row.missing ? .secondary : .primary)
                            }
                            if let detail = row.detail {
                                Text(detail)
                                    .font(.system(.caption, design: .rounded))
                                    .foregroundStyle(.secondary)
                            }
                        }
                        .font(.system(.subheadline, design: .rounded))
                        .accessibilityElement(children: .combine)
                    }
                } header: {
                    Text("Your inputs today")
                }
                .accessibilityIdentifier("insights.info.inputs")

                if topic != .background, let background = config.methodology["background"] {
                    Section {
                        ForEach(background.sections, id: \.self) { section in
                            VStack(alignment: .leading, spacing: 2) {
                                Text(section.heading).font(.system(.subheadline, design: .rounded, weight: .semibold))
                                Text(section.body)
                                    .font(.system(.footnote, design: .rounded))
                                    .foregroundStyle(.secondary)
                                    .fixedSize(horizontal: false, vertical: true)
                            }
                        }
                    } header: {
                        Text(background.title)
                    }
                }

                Section {
                    ForEach(disclaimerKeys, id: \.self) { key in
                        Text(config.disclaimer(key))
                            .font(.system(.footnote, design: .rounded))
                            .foregroundStyle(.secondary)
                    }
                }
            }
            .navigationTitle(methodology?.title ?? String(localized: "How we calculate this"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done") { dismiss() }
                }
            }
        }
        .accessibilityIdentifier("insights.info.sheet")
    }

    private var disclaimerKeys: [String] {
        switch topic {
        case .healthAge: ["general", "health_age"]
        case .patterns: ["general", "patterns"]
        default: ["general"]
        }
    }

    private var weightsHeader: String {
        switch topic {
        case .baselines: String(localized: "Windows and minimum data")
        case .patterns: String(localized: "Pairs checked")
        default: String(localized: "Weights")
        }
    }

    private static func percent(_ weight: Double) -> String {
        weight.rounded() == weight ? "\(Int(weight))%" : "\(weight.formatted(.number.precision(.fractionLength(1))))%"
    }

    /// (label, value) rows from the config.
    private var weightRows: [(String, String)] {
        switch topic {
        case .recovery:
            return config.recovery.components.map { component in
                (config.metric(component.metric)?.label ?? component.id, Self.percent(component.weight))
            }
        case .healthAge:
            return config.healthAge.markers.map { ($0.label, Self.percent($0.weight)) }
        case .dailyReview:
            return config.dailyReview.areas.map { ($0.label, Self.percent($0.weight)) }
        case .baselines:
            return HealthAnalyticsEngine.trendMetrics.compactMap { id in
                config.metric(id).map { metric in
                    (metric.label, String(localized: "\(metric.windowDays) days · min \(metric.minPoints)"))
                }
            }
        case .patterns:
            return config.patterns.pairs.map { pair in
                (InsightsPatternText.title(pair.id),
                 String(localized: "≥ \(config.patterns.minGroup) days each"))
            }
        case .background:
            return []
        }
    }
}

/// Short names of the pattern pairs.
enum InsightsPatternText {
    static func title(_ id: String) -> String {
        switch id {
        case "late_workout_sleep": String(localized: "Late intense workouts → sleep")
        case "high_load_recovery": String(localized: "High training load → next-day recovery")
        case "water_goal_recovery": String(localized: "Water goal met → next-day recovery")
        case "protein_strength_volume": String(localized: "Protein target met → next-day strength volume")
        case "short_sleep_steps": String(localized: "Short night → steps")
        default: id.replacingOccurrences(of: "_", with: " ")
        }
    }
}
