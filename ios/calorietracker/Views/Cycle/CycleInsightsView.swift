import Charts
import SwiftUI

/// Statistics and trends from the user's own logs (docs/cycle-tracking.md §5): averages, cycle and period length,
/// pain per cycle, top symptoms, flow by period day and the rule-based notes. Every chart has a spoken summary.
struct CycleInsightsView: View {
    @State private var store = CycleStore.shared

    private var stats: CycleStats? { store.snapshot?.stats }
    private var trends: CycleTrends? { store.trends }

    /// Completed cycles with a length, oldest first, labelled by start.
    private var completed: [CycleTrendCycle] { (trends?.cycles ?? []).filter { $0.cycleLength != nil } }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                statTiles
                insightNotes
                if completed.count >= 1 { cycleLengthChart }
                if (trends?.cycles ?? []).contains(where: { $0.periodLength != nil }) { periodLengthChart }
                if (trends?.cycles ?? []).contains(where: { $0.painMax != nil }) { painChart }
                if let symptoms = trends?.symptomFrequency, !symptoms.isEmpty { symptomChart(symptoms) }
                if let flow = trends?.flowPattern, !flow.isEmpty { flowChart(flow) }
                explainer
                CycleDisclaimer()
            }
            .padding(16)
        }
        .ayuvoScreenBackground()
        .navigationTitle("Insights")
        .navigationBarTitleDisplayMode(.inline)
        .task { await store.refreshIfDayChanged() }
        .accessibilityIdentifier("cycle.insights")
    }

    // MARK: Stats

    private var statTiles: some View {
        let columns = [GridItem(.flexible(), spacing: 12), GridItem(.flexible(), spacing: 12)]
        return LazyVGrid(columns: columns, spacing: 12) {
            tile(String(localized: "Average cycle"), value: stats?.cycleMedian.map { "\(Int($0.rounded()))" } ?? "—", unit: String(localized: "days"))
            tile(String(localized: "Typical range"), value: stats?.cycleRange.map { "\($0[0])–\($0[1])" } ?? "—", unit: String(localized: "days"))
            tile(String(localized: "Average period"), value: stats?.periodMedian.map { "\(Int($0.rounded()))" } ?? "—", unit: String(localized: "days"))
            tile(String(localized: "Cycles logged"), value: "\(stats?.cycleCount ?? 0)", unit: "")
        }
    }

    private func tile(_ title: String, value: String, unit: String) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(title)
                .font(.system(.subheadline, design: .rounded, weight: .semibold))
                .foregroundStyle(CycleStyle.period)
            HStack(alignment: .firstTextBaseline, spacing: 3) {
                Text(value).font(.ayuvoNumber(.title2)).lineLimit(1).minimumScaleFactor(0.6)
                if !unit.isEmpty { Text(unit).font(.caption).foregroundStyle(.secondary) }
            }
        }
        .frame(maxWidth: .infinity, minHeight: 72, alignment: .topLeading)
        .ayuvoCard(padding: 12)
        .accessibilityElement(children: .combine)
    }

    @ViewBuilder
    private var insightNotes: some View {
        if let insights = store.snapshot?.insights, !insights.isEmpty {
            VStack(alignment: .leading, spacing: 10) {
                AyuvoSectionHeader("Notes")
                ForEach(insights, id: \.self) { insight in
                    Label {
                        Text(CycleText.insight(insight)).font(.system(.subheadline, design: .rounded))
                    } icon: {
                        Image(systemName: CycleText.isProfessional(insight) ? "stethoscope" : "info.circle").foregroundStyle(.secondary)
                    }
                    .accessibilityIdentifier("cycle.insight.\(insight.key)")
                }
                if store.snapshot?.stats.variability == "high" {
                    Text("Estimates are less precise when cycles vary.")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .ayuvoCard()
        }
    }

    // MARK: Charts

    private func label(_ start: String) -> String { CycleDates.short(start) }

    private var cycleLengthChart: some View {
        let median = stats?.cycleMedian
        let values = completed.compactMap { $0.cycleLength }
        let summary = String(localized: "Cycle lengths \(values.map(String.init).joined(separator: ", ")) days")
        return chartCard(String(localized: "Cycle length"), summary: summary) {
            Chart {
                ForEach(completed, id: \.start) { c in
                    BarMark(x: .value("Cycle", label(c.start)), y: .value("Days", c.cycleLength ?? 0))
                        .foregroundStyle(CycleStyle.period.gradient)
                        .annotation(position: .top) { Text("\(c.cycleLength ?? 0)").font(.caption2).foregroundStyle(.secondary) }
                }
                if let median {
                    RuleMark(y: .value("Median", median))
                        .lineStyle(StrokeStyle(lineWidth: 1, dash: [4, 3]))
                        .foregroundStyle(.secondary)
                        .annotation(position: .top, alignment: .leading) {
                            Text("Median \(Int(median.rounded()))").font(.caption2).foregroundStyle(.secondary)
                        }
                }
            }
        }
    }

    private var periodLengthChart: some View {
        let cycles = (trends?.cycles ?? []).filter { $0.periodLength != nil }
        let summary = String(localized: "Period lengths \(cycles.compactMap(\.periodLength).map(String.init).joined(separator: ", ")) days")
        return chartCard(String(localized: "Period length"), summary: summary) {
            Chart(cycles, id: \.start) { c in
                BarMark(x: .value("Cycle", label(c.start)), y: .value("Days", c.periodLength ?? 0))
                    .foregroundStyle(CycleStyle.period.opacity(0.6))
            }
        }
    }

    private var painChart: some View {
        let cycles = (trends?.cycles ?? []).filter { $0.painMax != nil }
        let summary = String(localized: "Highest pain per cycle \(cycles.compactMap(\.painMax).map(String.init).joined(separator: ", ")) out of 10")
        return chartCard(String(localized: "Pain per cycle"), summary: summary) {
            Chart(cycles, id: \.start) { c in
                LineMark(x: .value("Cycle", label(c.start)), y: .value("Highest pain", c.painMax ?? 0))
                    .foregroundStyle(AyuvoPalette.symptoms)
                PointMark(x: .value("Cycle", label(c.start)), y: .value("Highest pain", c.painMax ?? 0))
                    .foregroundStyle(AyuvoPalette.symptoms)
                    .symbol(.circle)
            }
            .chartYScale(domain: 0...10)
        }
    }

    private func symptomChart(_ symptoms: [CycleFrequency]) -> some View {
        let top = Array(symptoms.prefix(6))
        let window = trends?.windowCycles ?? 0
        let summary = top.map { "\(CycleText.symptom($0.key)) \($0.cycles)" }.joined(separator: ", ")
        return chartCard(String(localized: "Top symptoms"), subtitle: String(localized: "Cycles with the symptom, of the last \(window)"), summary: summary) {
            Chart(top, id: \.key) { f in
                BarMark(x: .value("Cycles", f.cycles), y: .value("Symptom", CycleText.symptom(f.key)))
                    .foregroundStyle(AyuvoPalette.symptoms.gradient)
            }
            .chartXScale(domain: 0...max(window, 1))
            .frame(height: CGFloat(max(top.count, 1)) * 34)
        }
    }

    private func flowChart(_ pattern: [Double]) -> some View {
        let names = CycleConfig.shared.flowLevels
        let summary = pattern.enumerated().map { "\(String(localized: "day \($0.offset + 1)")) \(String(format: "%.1f", $0.element))" }.joined(separator: ", ")
        return chartCard(String(localized: "Flow by period day"), subtitle: String(localized: "Average of your recent periods (1 spotting – 5 very heavy)"), summary: summary) {
            Chart(Array(pattern.enumerated()), id: \.offset) { item in
                BarMark(x: .value("Period day", "\(item.offset + 1)"), y: .value("Flow", item.element))
                    .foregroundStyle(CycleStyle.period.opacity(0.75))
            }
            .chartYScale(domain: 0...Double(names.count))
        }
    }

    private func chartCard<Content: View>(_ title: String, subtitle: String? = nil, summary: String, @ViewBuilder content: () -> Content) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(title).font(.system(.headline, design: .rounded))
            if let subtitle { Text(subtitle).font(.caption).foregroundStyle(.secondary) }
            content()
                .frame(minHeight: 160)
                .accessibilityElement(children: .ignore)
                .accessibilityLabel(Text(title))
                .accessibilityValue(Text(summary))
        }
        .ayuvoCard()
    }

    private var explainer: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text("How estimates work").font(.system(.headline, design: .rounded))
            Text("The next period is estimated from the median length of your recent cycles (or your settings until you have logged enough). The fertile window and ovulation are counted back from the next estimated period. These are calendar estimates; cycles can shift for many reasons.")
                .font(.system(.subheadline, design: .rounded))
                .foregroundStyle(.secondary)
            if store.showFertility {
                Text(CycleText.fertilityNote).font(.caption).foregroundStyle(.secondary)
            }
        }
        .ayuvoCard()
    }
}
