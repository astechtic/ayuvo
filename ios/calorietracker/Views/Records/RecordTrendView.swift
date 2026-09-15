import Charts
import SwiftUI

/// Full analyte trend (§21, plan §2 Trend row): Swift Charts line + points coloured by flag over the
/// most recent report's reference band, a unit series picker, scrub inspection, point tap → source
/// record, and a points table with remove / exclude / re-map.
struct RecordTrendView: View {
    let analyteID: String
    @Environment(RecordsStore.self) private var store
    @Environment(ChatStore.self) private var chatStore
    @State private var trend: RecordAnalyteTrend?
    @State private var didLoad = false
    @State private var seriesID: String?
    @State private var inspected: RecordTrendPoint?
    @State private var editing: RecordObservationItem?
    @State private var path: RecordsRoute?

    private var analyte: AnalyteDefinition? { AnalyteCatalog.shared.analyte(id: analyteID) }
    private var title: String { analyte?.displayName ?? analyteID }

    private var currentSeries: RecordTrendSeries? {
        guard let trend else { return nil }
        return trend.series.first { $0.id == seriesID } ?? trend.series.first
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                if let trend {
                    content(trend)
                } else if didLoad {
                    ContentUnavailableView("No values yet", systemImage: "chart.xyaxis.line")
                } else {
                    ProgressView().frame(maxWidth: .infinity, minHeight: 240)
                }
            }
            .padding()
        }
        .background(AppColors.appBackground)
        .navigationTitle(title)
        .navigationBarTitleDisplayMode(.inline)
        .toolbar(.visible, for: .navigationBar)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                if trend?.items.isEmpty == false {
                    Button {
                        Task {
                            let refs = await store.trendRecordRefs(analyteID: analyteID)
                            chatStore.requestHandoff(records: refs, prompt: CoachRecordsPrompts.explainTrend(title))
                        }
                    } label: {
                        Label("Explain this trend", systemImage: "bubble.left.and.text.bubble.right")
                    }
                    .accessibilityIdentifier("records.trend.explain")
                }
            }
        }
        .task(id: store.revision) {
            let loaded = await store.trend(analyteID: analyteID)
            trend = loaded
            if let loaded, !loaded.series.contains(where: { $0.id == seriesID }) { seriesID = loaded.series.first?.id }
            didLoad = true
        }
        .navigationDestination(item: $path) { route in
            switch route {
            case .detailSource(let recordID, let observationID):
                RecordDetailView(recordID: recordID, initialObservationID: observationID)
            case .detail(let id):
                RecordDetailView(recordID: id)
            case .trend(let id):
                RecordTrendView(analyteID: id)
            }
        }
        .sheet(item: $editing) { item in
            RecordObservationEditSheet(observation: item.observation, record: item.record, onSource: { _ in
                editing = nil
                path = .detailSource(recordID: item.record.id, observationID: item.observation.id)
            })
        }
    }

    @ViewBuilder
    private func content(_ trend: RecordAnalyteTrend) -> some View {
        let series = currentSeries
        header(series)
        if trend.series.count > 1 {
            VStack(alignment: .leading, spacing: 6) {
                Picker("Unit", selection: Binding(get: { seriesID ?? trend.series.first?.id ?? "" }, set: { seriesID = $0; inspected = nil })) {
                    ForEach(trend.series) { s in
                        Text(s.unit.isEmpty ? String(localized: "No unit") : s.unit).tag(s.id)
                    }
                }
                .pickerStyle(.segmented)
                .accessibilityIdentifier("records.trend.unitPicker")
                Text("Values in units that can't be converted are shown as separate series.")
                    .font(.system(.caption, design: .rounded))
                    .foregroundStyle(.secondary)
            }
        }
        if let series, series.points.count >= 2 {
            RecordsCard {
                chart(series)
                legend(series)
            }
        } else {
            RecordsCard {
                Label("Add another report with \(title) to see a trend", systemImage: "chart.line.uptrend.xyaxis")
                    .font(.system(.subheadline, design: .rounded, weight: .medium))
                    .foregroundStyle(.secondary)
                if let series, let point = series.points.first {
                    Text("\(format(point.value)) \(series.unit) · \(dateText(point.date))")
                        .font(.system(.headline, design: .rounded))
                }
            }
            .accessibilityIdentifier("records.trend.empty")
        }
        Button {
            Task {
                let refs = await store.trendRecordRefs(analyteID: analyteID)
                chatStore.requestHandoff(records: refs, prompt: CoachRecordsPrompts.explainTrend(title))
            }
        } label: {
            Label("Explain this trend", systemImage: "bubble.left.and.text.bubble.right.fill")
                .font(.system(.subheadline, design: .rounded, weight: .semibold))
                .frame(maxWidth: .infinity)
        }
        .buttonStyle(.borderedProminent)
        .tint(AppColors.calorie)
        .controlSize(.large)
        .accessibilityIdentifier("records.trend.explainButton")
        pointsTable(trend)
    }

    private func header(_ series: RecordTrendSeries?) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            if let last = series?.points.last {
                HStack(alignment: .firstTextBaseline, spacing: 6) {
                    Text(format(last.value))
                        .font(.system(size: 34, weight: .bold, design: .rounded))
                        .monospacedDigit()
                    Text(series?.unit ?? "")
                        .font(.system(.headline, design: .rounded))
                        .foregroundStyle(.secondary)
                    if let flag = last.flag.shortTitle, last.flag != .normal {
                        Text(flag)
                            .font(.system(.caption, design: .rounded, weight: .bold))
                            .foregroundStyle(last.flag.tint)
                            .padding(.horizontal, 7)
                            .padding(.vertical, 2)
                            .background(last.flag.tint.opacity(0.12), in: Capsule())
                    }
                }
                Text("Latest · \(dateText(last.date))")
                    .font(.system(.subheadline, design: .rounded))
                    .foregroundStyle(.secondary)
                if let series, series.points.count >= 2 {
                    let previous = series.points[series.points.count - 2]
                    let delta = last.value - previous.value
                    Text("\(delta >= 0 ? "+" : "−")\(format(abs(delta))) \(series.unit) since \(dateText(previous.date))")
                        .font(.system(.footnote, design: .rounded, weight: .medium))
                        .foregroundStyle(.secondary)
                        .accessibilityIdentifier("records.trend.change")
                }
            }
            if let analyte, !analyte.panels.isEmpty {
                Text(analyte.aliases.prefix(3).joined(separator: " · "))
                    .font(.system(.caption, design: .rounded))
                    .foregroundStyle(.tertiary)
            }
        }
    }

    private func chart(_ series: RecordTrendSeries) -> some View {
        let points = series.points
        let first = points.first!.dayDate
        let last = points.last!.dayDate
        let span = max(last.timeIntervalSince(first), 86_400 * 7)
        let domainStart = first.addingTimeInterval(-span * 0.06)
        let domainEnd = last.addingTimeInterval(span * 0.06)
        let values = points.map(\.value) + [series.bandLow, series.bandHigh].compactMap { $0 }
        let lo = values.min() ?? 0
        let hi = values.max() ?? 1
        let pad = max((hi - lo) * 0.15, abs(hi) * 0.05, 0.5)
        return Chart {
            if let low = series.bandLow, let high = series.bandHigh, high > low {
                RectangleMark(
                    xStart: .value("Start", domainStart),
                    xEnd: .value("End", domainEnd),
                    yStart: .value("Low", low),
                    yEnd: .value("High", high)
                )
                .foregroundStyle(Color.green.opacity(0.12))
            } else if let high = series.bandHigh {
                RuleMark(y: .value("High", high))
                    .foregroundStyle(Color.green.opacity(0.4))
                    .lineStyle(StrokeStyle(lineWidth: 1, dash: [4, 4]))
            } else if let low = series.bandLow {
                RuleMark(y: .value("Low", low))
                    .foregroundStyle(Color.green.opacity(0.4))
                    .lineStyle(StrokeStyle(lineWidth: 1, dash: [4, 4]))
            }
            ForEach(points) { point in
                LineMark(x: .value("Date", point.dayDate), y: .value(series.unit, point.value))
                    .foregroundStyle(AppColors.calorie.opacity(0.7))
                    .interpolationMethod(.monotone)
                    .lineStyle(StrokeStyle(lineWidth: 2.5, lineCap: .round))
            }
            ForEach(points) { point in
                PointMark(x: .value("Date", point.dayDate), y: .value(series.unit, point.value))
                    .foregroundStyle(point.flag.isAbnormal ? point.flag.tint : Color.green)
                    .symbolSize(inspected == point ? 150 : 80)
                    .annotation(position: .top, spacing: 4) {
                        if points.count <= 8 {
                            Text(format(point.value))
                                .font(.system(.caption2, design: .rounded, weight: .semibold))
                                .foregroundStyle(.secondary)
                        }
                    }
            }
            if let inspected {
                RuleMark(x: .value("Selected", inspected.dayDate))
                    .foregroundStyle(Color.primary.opacity(0.25))
                    .lineStyle(StrokeStyle(lineWidth: 1, dash: [3, 3]))
            }
        }
        .chartXScale(domain: domainStart...domainEnd)
        .chartYScale(domain: (lo - pad)...(hi + pad))
        .chartXAxis {
            AxisMarks(values: .automatic(desiredCount: 4)) {
                AxisGridLine(stroke: StrokeStyle(lineWidth: 0.6, dash: [3, 4])).foregroundStyle(Color.primary.opacity(0.11))
                AxisValueLabel(format: .dateTime.month(.abbreviated).day()).foregroundStyle(Color.secondary)
            }
        }
        .chartYAxis {
            AxisMarks(position: .trailing, values: .automatic(desiredCount: 4)) {
                AxisGridLine(stroke: StrokeStyle(lineWidth: 0.6)).foregroundStyle(Color.primary.opacity(0.10))
                AxisValueLabel().foregroundStyle(Color.secondary)
            }
        }
        .chartOverlay { proxy in
            ChartScrubOverlay(proxy: proxy, points: points, date: { $0.dayDate }, selected: $inspected, onTap: { point in
                open(point)
            }) { point in
                VStack(spacing: 2) {
                    Text("\(format(point.value)) \(series.unit)")
                        .font(.system(.footnote, design: .rounded, weight: .semibold))
                    Text(dateText(point.date))
                        .font(.system(.caption2, design: .rounded))
                        .foregroundStyle(.secondary)
                    if let range = rangeText(point, unit: series.unit) {
                        Text(range)
                            .font(.system(.caption2, design: .rounded))
                            .foregroundStyle(.secondary)
                    }
                }
                .padding(.horizontal, 10)
                .padding(.vertical, 6)
                .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 10, style: .continuous))
            }
        }
        .animation(.snappy(duration: 0.16), value: inspected?.id)
        .frame(height: 240)
        .accessibilityIdentifier("records.trend.chart")
        .accessibilityLabel(Text("\(title) trend"))
        .accessibilityValue(Text(points.map { "\(format($0.value)) \(series.unit) \(dateText($0.date))" }.joined(separator: ", ")))
    }

    private func legend(_ series: RecordTrendSeries) -> some View {
        HStack(spacing: 12) {
            if series.bandLow != nil || series.bandHigh != nil {
                HStack(spacing: 4) {
                    RoundedRectangle(cornerRadius: 2).fill(Color.green.opacity(0.25)).frame(width: 14, height: 8)
                    Text("Latest report range").font(.system(.caption2, design: .rounded)).foregroundStyle(.secondary)
                }
            }
            HStack(spacing: 4) {
                Circle().fill(Color.orange).frame(width: 7, height: 7)
                Text("Outside range").font(.system(.caption2, design: .rounded)).foregroundStyle(.secondary)
            }
            Spacer(minLength: 0)
            Text("Tap a point to open its report")
                .font(.system(.caption2, design: .rounded))
                .foregroundStyle(.tertiary)
        }
    }

    // MARK: Table

    private func pointsTable(_ trend: RecordAnalyteTrend) -> some View {
        RecordsCard {
            RecordsSectionTitle(title: "Values", systemImage: "list.bullet", trailing: "\(trend.items.count)")
            ForEach(trend.items) { item in
                let o = item.observation
                HStack(spacing: 8) {
                    Button {
                        path = .detailSource(recordID: item.record.id, observationID: o.id)
                    } label: {
                        HStack(alignment: .firstTextBaseline, spacing: 8) {
                            VStack(alignment: .leading, spacing: 2) {
                                Text(o.observedDate.map(dateText) ?? String(localized: "No date"))
                                    .font(.system(.subheadline, design: .rounded, weight: .semibold))
                                Text(item.record.title)
                                    .font(.system(.caption, design: .rounded))
                                    .foregroundStyle(.secondary)
                                    .lineLimit(1)
                                if o.excludedFromTrends {
                                    Text("Not shown in trend")
                                        .font(.system(.caption2, design: .rounded, weight: .semibold))
                                        .foregroundStyle(.secondary)
                                }
                            }
                            Spacer(minLength: 4)
                            HStack(spacing: 3) {
                                Text(o.valueWithUnit)
                                    .font(.system(.subheadline, design: .rounded, weight: .bold))
                                    .monospacedDigit()
                                    .foregroundStyle(o.flag.isAbnormal ? o.flag.tint : .primary)
                                if let symbol = o.flag.symbol { Text(symbol).foregroundStyle(o.flag.tint) }
                                if o.isConfirmed {
                                    Image(systemName: "checkmark.seal.fill").font(.caption2).foregroundStyle(.green)
                                }
                            }
                        }
                        .opacity(o.excludedFromTrends ? 0.5 : 1)
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    .accessibilityIdentifier("records.trend.point.\(o.observedDate ?? o.id)")
                    Menu {
                        Button {
                            Task { await store.editObservation(o, edit: RecordObservationEdit(excludedFromTrends: !o.excludedFromTrends)) }
                        } label: {
                            Label(o.excludedFromTrends ? "Show in trend" : "Remove from trend", systemImage: o.excludedFromTrends ? "eye" : "eye.slash")
                        }
                        Button {
                            editing = item
                        } label: {
                            Label("Edit or change test", systemImage: "pencil")
                        }
                        Button(role: .destructive) {
                            Task { await store.removeObservation(o) }
                        } label: {
                            Label("Remove value", systemImage: "trash")
                        }
                    } label: {
                        Image(systemName: "ellipsis.circle")
                            .font(.system(size: 18))
                            .foregroundStyle(.secondary)
                            .frame(width: 32, height: 32)
                    }
                    .accessibilityIdentifier("records.trend.pointMenu.\(o.observedDate ?? o.id)")
                }
                if item.id != trend.items.last?.id { Divider() }
            }
        }
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("records.trend.table")
    }

    // MARK: Helpers

    private func open(_ point: RecordTrendPoint) {
        guard let observationID = point.observationIDs.first, let recordID = point.recordIDs.first else { return }
        path = .detailSource(recordID: recordID, observationID: observationID)
    }

    private func format(_ value: Double) -> String {
        RecordTrendBuilder.format(value, decimals: analyte?.decimals)
    }

    private func dateText(_ day: String) -> String {
        RecordDates.date(fromDay: day)?.formatted(date: .abbreviated, time: .omitted) ?? day
    }

    private func rangeText(_ point: RecordTrendPoint, unit: String) -> String? {
        switch (point.refLow, point.refHigh) {
        case let (low?, high?): String(localized: "Range \(format(low))–\(format(high))")
        case let (nil, high?): String(localized: "Range up to \(format(high))")
        case let (low?, nil): String(localized: "Range from \(format(low))")
        default: point.refText
        }
    }
}
