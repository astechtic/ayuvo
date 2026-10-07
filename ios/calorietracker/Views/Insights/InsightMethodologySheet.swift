import SwiftUI

/// Which "How we calculate this" text a screen shows (config `methodology` keys).
enum InsightsTopic: String {
    case recovery, healthAge = "health_age", dailyReview = "daily_review", patterns, baselines, background, analytics
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
                if topic == .recovery || topic == .analytics {
                    AnalyticsMethodologySections(topic: topic)
                } else if let methodology {
                    ForEach(Array(methodology.sections.enumerated()), id: \.offset) { index, section in
                        Section {
                            Text(config.sectionBody(topic.rawValue, index, section))
                                .font(.system(.subheadline, design: .rounded))
                                .fixedSize(horizontal: false, vertical: true)
                        } header: {
                            Text(config.sectionHeading(topic.rawValue, index, section))
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
                        ForEach(Array(background.sections.enumerated()), id: \.offset) { index, section in
                            VStack(alignment: .leading, spacing: 2) {
                                Text(config.sectionHeading("background", index, section)).font(.system(.subheadline, design: .rounded, weight: .semibold))
                                Text(config.sectionBody("background", index, section))
                                    .font(.system(.footnote, design: .rounded))
                                    .foregroundStyle(.secondary)
                                    .fixedSize(horizontal: false, vertical: true)
                            }
                        }
                    } header: {
                        Text(config.methodologyTitle("background") ?? background.title)
                    }
                }

                Section {
                    ForEach(disclaimerKeys, id: \.self) { key in
                        Text(key == "analytics" ? AnalyticsText.disclaimer : config.displayDisclaimer(key))
                            .font(.system(.footnote, design: .rounded))
                            .foregroundStyle(.secondary)
                    }
                }
            }
            .navigationTitle(navigationTitle)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done") { dismiss() }
                }
            }
        }
        .accessibilityIdentifier("insights.info.sheet")
    }

    private var navigationTitle: String {
        switch topic {
        case .recovery: String(localized: "How Recovery is calculated")
        case .analytics: String(localized: "How health signals are calculated")
        default: config.methodologyTitle(topic.rawValue) ?? String(localized: "How we calculate this")
        }
    }

    private var disclaimerKeys: [String] {
        switch topic {
        case .healthAge: ["general", "health_age"]
        case .patterns: ["general", "patterns"]
        case .recovery, .analytics: ["general", "analytics"]
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
            // Recovery Indicator v2 weights (shared/analytics, weights version 1).
            return AnalyticsConfig.shared["recovery"]["components"].array.map { c in
                let id = c["id"].string ?? ""
                return (id == "sleep" ? config.metricLabel("sleep") : AnalyticsText.metricLabel(c["metric"].string ?? id),
                        Self.percent(c["weight"].double ?? 0))
            }
        case .analytics:
            return []
        case .healthAge:
            return config.healthAge.markers.map { (config.markerLabel($0.id), Self.percent($0.weight)) }
        case .dailyReview:
            return config.dailyReview.areas.map { (config.areaLabel($0.id), Self.percent($0.weight)) }
        case .baselines:
            return HealthAnalyticsEngine.trendMetrics.compactMap { id in
                config.metric(id).map { metric in
                    (config.metricLabel(id), String(localized: "\(metric.windowDays) days · min \(metric.minPoints)"))
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

/// Methodology of the analytics-based screens, rendered from `shared/analytics/analytics_config.json`.
struct AnalyticsMethodologySections: View {
    let topic: InsightsTopic
    private var cfg: AnalyticsConfig { .shared }

    var body: some View {
        if topic == .recovery {
            Section {
                Text("Recovery Indicator compares last night's HRV, resting heart rate, sleep, breathing rate, sleeping temperature and blood oxygen with your own previous 60 days, using the median and the median absolute deviation so one unusual night does not shift your baseline. Each signal becomes a 0–100 sub-score (50 = your usual), the available signals are averaged by their weights, and a sharp rise in recent training load can take a few points off.")
                Text("Sleep counts duration against your personal sleep need, sleep efficiency against your own usual, and how close your sleep timing was to your recent nights. Missing signals are left out and the rest re-weighted; confidence goes down when signals are missing, history is short or a value came from a camera scan.")
                Text("It is an indicator built from your own data, not a measurement of biological recovery.")
            } header: {
                Text("Recovery Indicator v\(cfg["recovery"]["algorithm_version"].int ?? 2)")
            }
            .font(.system(.subheadline, design: .rounded))
            Section {
                ForEach(cfg["recovery"]["components"].array.compactMap { $0["id"].string }, id: \.self) { id in
                    VStack(alignment: .leading, spacing: 2) {
                        Text(id == "sleep" ? InsightsConfig.shared.metricLabel("sleep") : AnalyticsText.metricLabel(id))
                            .font(.system(.subheadline, design: .rounded, weight: .semibold))
                        Text(AnalyticsText.componentWhy(id))
                            .font(.system(.footnote, design: .rounded))
                            .foregroundStyle(.secondary)
                    }
                }
            } header: {
                Text("Why each signal counts")
            } footer: {
                Text("The weights are Ayuvo design choices, not universal constants.")
            }
        } else {
            Section {
                ForEach(cfg["algorithms"].array.compactMap { $0["id"].string }, id: \.self) { id in
                    let a = cfg["algorithms"].array.first { $0["id"].string == id } ?? .null
                    VStack(alignment: .leading, spacing: 2) {
                        Text(verbatim: "\(id)@\(a["version"].int ?? 1)")
                            .font(.system(.footnote, design: .monospaced))
                        Text(AnalyticsText.classificationLabel(a["classification"].string ?? ""))
                            .font(.system(.caption, design: .rounded))
                            .foregroundStyle(.secondary)
                    }
                }
            } header: {
                Text("Algorithms and versions")
            }
        }
        Section {
            ForEach(["MEASURED", "PROVIDER_DERIVED", "SCIENTIFIC_DERIVED", "PERSONALIZED_STATISTICAL", "ML_PREDICTED", "EXPERIMENTAL", "RESEARCH_ONLY"], id: \.self) { c in
                VStack(alignment: .leading, spacing: 2) {
                    Text(AnalyticsText.classificationLabel(c)).font(.system(.subheadline, design: .rounded, weight: .semibold))
                    Text(AnalyticsText.classificationAbout(c)).font(.system(.footnote, design: .rounded)).foregroundStyle(.secondary)
                }
            }
        } header: {
            Text("Where numbers come from")
        }
    }
}
