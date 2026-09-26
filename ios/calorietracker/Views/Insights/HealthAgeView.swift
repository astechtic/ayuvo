import SwiftUI

/// Ayuvo Health Age: the estimate next to actual age, the pace, each marker's contribution in years and the
/// 12-week trend. Ayuvo's own estimate, not a clinical or biological age.
struct HealthAgeView: View {
    @Environment(InsightsStore.self) private var store

    var body: some View {
        InsightsScreen(title: "Health Age", topic: .healthAge, disclaimers: ["general", "health_age"],
                       inputs: { Self.inputRows(store.healthAge) }) {
            if let result = store.healthAge {
                HealthAgeHeaderCard(result: result, pace: store.pace)
                if !result.markers.isEmpty {
                    markersCard(result)
                }
                if let pace = store.pace {
                    trendCard(pace)
                }
                if result.isReady {
                    qualityCard(result)
                    InsightsExplainSection(kind: .healthAge, summary: store.report?.summary())
                }
            }
        }
    }

    private func markersCard(_ result: HealthAgeResult) -> some View {
        VStack(alignment: .leading, spacing: 12) {
            AyuvoSectionHeader("Markers")
            ForEach(result.markers) { marker in
                VStack(alignment: .leading, spacing: 2) {
                    HStack(alignment: .firstTextBaseline) {
                        Text(Self.label(marker.id))
                            .font(.system(.subheadline, design: .rounded, weight: .medium))
                        Spacer()
                        if let contribution = marker.contributionYears {
                            InsightsChip(text: InsightsText.years(contribution),
                                         tint: contribution <= 0 ? AyuvoPalette.nutrition : AyuvoPalette.heart)
                        } else {
                            Text(InsightsText.missing).foregroundStyle(.secondary)
                        }
                    }
                    Text(Self.markerDetail(marker))
                        .font(.system(.caption, design: .rounded))
                        .foregroundStyle(.secondary)
                }
                .accessibilityElement(children: .combine)
            }
        }
        .ayuvoCard()
        .accessibilityIdentifier("healthAge.markers")
    }

    @ViewBuilder
    private func trendCard(_ pace: HealthAgePaceResult) -> some View {
        let points = pace.points.compactMap { point -> InsightsTrendChart.Point? in
            guard let value = point.healthAge, let date = InsightsDay.date(point.day) else { return nil }
            return InsightsTrendChart.Point(day: point.day, date: date, value: value)
        }
        VStack(alignment: .leading, spacing: 8) {
            AyuvoSectionHeader("Last 12 weeks")
            if points.count < 2 {
                Text("The weekly trend appears once there are a few weeks of estimates.")
                    .font(.system(.subheadline, design: .rounded))
                    .foregroundStyle(.secondary)
            } else {
                InsightsTrendChart(points: points, tint: AyuvoPalette.insights, bars: false, yDomain: nil) {
                    String(localized: "\($0.formatted(.number.precision(.fractionLength(1)))) years")
                }
            }
            if pace.status != "ok" {
                Text("Pace needs \(pace.needed) weekly estimates (\(pace.have) so far).")
                    .font(.system(.caption, design: .rounded))
                    .foregroundStyle(.secondary)
            }
        }
        .ayuvoCard()
    }

    private func qualityCard(_ result: HealthAgeResult) -> some View {
        HStack {
            Label("Data quality", systemImage: "checkmark.seal")
                .font(.system(.subheadline, design: .rounded, weight: .medium))
            Spacer()
            Text("\(result.markersAvailable) of \(result.markers.count) markers")
                .font(.system(.subheadline, design: .rounded))
                .foregroundStyle(.secondary)
            if let confidence = InsightsText.confidence(result.confidence) {
                InsightsChip(text: confidence, tint: AyuvoPalette.other)
            }
        }
        .ayuvoCard()
    }

    // MARK: Text

    static func label(_ id: String) -> String {
        InsightsConfig.shared.marker(id)?.label ?? id
    }

    static func markerValue(_ marker: HealthAgeMarker) -> String {
        guard let value = marker.value else { return InsightsText.missing }
        switch marker.id {
        case "sleep": return String(localized: "\(value.formatted(.number.precision(.fractionLength(1)))) h a night")
        case "workouts": return String(localized: "\(Int(value.rounded())) min a week")
        case "body_composition":
            return marker.basis == "bmi" ? String(localized: "BMI \(value.formatted(.number.precision(.fractionLength(1))))")
                : String(localized: "Body fat \(value.formatted(.number.precision(.fractionLength(1))))%")
        default: return InsightsText.value(value, metric: marker.id)
        }
    }

    static func markerDetail(_ marker: HealthAgeMarker) -> String {
        guard marker.available else {
            return String(localized: "Not enough data (\(marker.days)/\(marker.neededDays) days)")
        }
        var parts = [String(localized: "90-day average \(markerValue(marker))")]
        if let age = marker.equivalentAge {
            parts.append(String(localized: "typical at age \(age.formatted(.number.precision(.fractionLength(0))))"))
        }
        return parts.joined(separator: " · ")
    }

    static func inputRows(_ result: HealthAgeResult?) -> [InsightsInputRow] {
        guard let result else { return [] }
        var rows: [InsightsInputRow] = []
        if let actual = result.actualAge {
            rows.append(InsightsInputRow(id: "actual", title: String(localized: "Actual age"),
                                         value: actual.formatted(.number.precision(.fractionLength(1)))))
        }
        for marker in result.markers {
            rows.append(InsightsInputRow(
                id: marker.id, title: label(marker.id),
                value: marker.available ? markerValue(marker) : String(localized: "Missing"),
                detail: marker.available
                    ? String(localized: "\(InsightsText.years(marker.offsetYears)) · weight \(marker.weight.formatted())%")
                    : markerDetail(marker),
                missing: !marker.available
            ))
        }
        return rows
    }
}

struct HealthAgeHeaderCard: View {
    let result: HealthAgeResult
    let pace: HealthAgePaceResult?

    var body: some View {
        switch result.status {
        case "collecting":
            InsightsCollectingView(
                title: String(localized: "Collecting data (\(result.collecting?.have ?? 0)/\(result.collecting?.need ?? 30) days)"),
                detail: String(localized: "Health Age needs at least \(result.markersNeeded) markers, including VO2 max, resting heart rate or HRV, with about a month of data."),
                collecting: result.collecting
            )
        case "no_birthday":
            InsightsCollectingView(title: String(localized: "Add your birthday"),
                                   detail: String(localized: "Health Age compares your markers with your actual age. Add your birthday in Settings › Personal Info."),
                                   collecting: nil)
        case "unsupported_age":
            InsightsCollectingView(title: String(localized: "Available from age 18"),
                                   detail: String(localized: "Ayuvo Health Age uses adult reference values."),
                                   collecting: nil)
        default:
            VStack(alignment: .leading, spacing: 10) {
                HStack(alignment: .firstTextBaseline, spacing: 18) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text("Ayuvo Health Age")
                            .font(.system(.caption, design: .rounded, weight: .semibold))
                            .foregroundStyle(.secondary)
                        Text(result.healthAge.map { $0.formatted(.number.precision(.fractionLength(1))) } ?? InsightsText.missing)
                            .font(.ayuvoNumber(.largeTitle, weight: .bold))
                            .foregroundStyle(AyuvoPalette.insights)
                    }
                    VStack(alignment: .leading, spacing: 2) {
                        Text("Actual age")
                            .font(.system(.caption, design: .rounded, weight: .semibold))
                            .foregroundStyle(.secondary)
                        Text(result.actualAge.map { $0.formatted(.number.precision(.fractionLength(1))) } ?? InsightsText.missing)
                            .font(.ayuvoNumber(.title2))
                    }
                }
                HStack(spacing: 8) {
                    if let difference = result.difference {
                        InsightsChip(text: InsightsText.years(difference),
                                     tint: difference <= 0 ? AyuvoPalette.nutrition : AyuvoPalette.heart)
                    }
                    HealthAgePaceChip(pace: pace)
                }
            }
            .ayuvoCard()
            .accessibilityElement(children: .combine)
            .accessibilityIdentifier("healthAge.header")
        }
    }
}

/// "Improving · 0.7 years/year" (or nothing until 8 weekly points exist).
struct HealthAgePaceChip: View {
    let pace: HealthAgePaceResult?

    var body: some View {
        if let pace, pace.status == "ok", let value = pace.pace {
            let text = String(localized: "\(Self.direction(pace.direction)) · pace \(value.formatted(.number.precision(.fractionLength(1))))")
            InsightsChip(text: text, tint: pace.direction == "declining" ? AyuvoPalette.heart : AyuvoPalette.nutrition)
        }
    }

    static func direction(_ direction: String?) -> String {
        switch direction {
        case "improving": String(localized: "Improving")
        case "declining": String(localized: "Rising")
        default: String(localized: "Steady")
        }
    }
}
