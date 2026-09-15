import SwiftUI

/// Apple-Health-style detail: D/W/M/6M/Y picker, ‹ › anchor navigation, highlights, chart,
/// Show All Data / Data Sources & Access / Unit / Show on Home.
struct HealthMetricDetailView: View {
    let typeID: String
    @Environment(HealthDataStore.self) private var store
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    @State private var range: HealthDetailRange = .week
    @State private var anchor = Date()
    @State private var series: HealthChartSeries?
    @State private var isLoading = false
    @State private var selected: HealthChartPoint?

    private var type: HealthMetricType { store.metricType(for: typeID) }
    private var summary: HealthTypeSummary? { store.summary(for: typeID) }
    private var calendar: Calendar { store.calendar }
    private var interval: DateInterval { range.interval(containing: anchor, calendar: calendar) }
    private var canGoForward: Bool { interval.end <= Date() }
    private var hasUnitPicker: Bool {
        ["weight", "height", "waist_circumference", "blood_glucose", "body_temperature", "basal_body_temperature", "hydration", "distance", "distance_cycling", "distance_swimming"].contains(typeID)
    }

    private var loadKey: String {
        "\(typeID)|\(range.rawValue)|\(Int(anchor.timeIntervalSince1970))|\(store.snapshotRevision)"
    }

    var body: some View {
        List {
            Section {
                VStack(alignment: .leading, spacing: 14) {
                    Picker("Range", selection: $range) {
                        ForEach(HealthDetailRange.allCases) { option in
                            Text(option.rawValue).tag(option)
                        }
                    }
                    .pickerStyle(.segmented)

                    HStack {
                        Button {
                            step(-1)
                        } label: {
                            Image(systemName: "chevron.left")
                                .frame(width: 32, height: 32)
                        }
                        .buttonStyle(.plain)
                        .accessibilityLabel(Text("Previous \(range.englishTitle)"))
                        Spacer()
                        Text(rangeTitle)
                            .font(.system(.subheadline, design: .rounded, weight: .semibold))
                            .foregroundStyle(.secondary)
                        Spacer()
                        Button {
                            step(1)
                        } label: {
                            Image(systemName: "chevron.right")
                                .frame(width: 32, height: 32)
                        }
                        .buttonStyle(.plain)
                        .disabled(!canGoForward)
                        .opacity(canGoForward ? 1 : 0.35)
                        .accessibilityLabel(Text("Next \(range.englishTitle)"))
                    }

                    highlightBadges

                    if let series, !series.isEmpty {
                        HealthMetricChart(type: type, series: series, selected: $selected, calendar: calendar)
                    } else if isLoading {
                        ProgressView()
                            .frame(maxWidth: .infinity)
                            .frame(height: 210)
                    } else {
                        ContentUnavailableView {
                            Label("No data in this range", systemImage: type.category.systemImage)
                        } description: {
                            Text(store.isEnabled ? "Try another range, or pull to refresh on the Health Data screen." : "Health sync is off. Existing data stays here read-only.")
                        }
                        .frame(height: 210)
                    }
                }
                .padding(.vertical, 4)
            }
            .listRowBackground(AppColors.appCard)

            Section {
                NavigationLink(value: HealthRoute.allData(typeID)) {
                    Label("Show All Data", systemImage: "list.bullet.rectangle")
                }
                NavigationLink(value: HealthRoute.sources(typeID)) {
                    Label("Data Sources & Access", systemImage: "square.stack.3d.up")
                }
                if hasUnitPicker {
                    NavigationLink(value: HealthRoute.unit(typeID)) {
                        LabeledContent {
                            Text(HealthUnitFormatting.unitLabel(for: type))
                        } label: {
                            Label("Unit", systemImage: "ruler")
                        }
                    }
                }
                Toggle(isOn: Binding(
                    get: { store.isPinned(typeID) },
                    set: { _ in store.togglePin(typeID) }
                )) {
                    Label("Show on Home", systemImage: "house")
                }
                .tint(AppColors.calorie)
            } header: {
                Text("Options")
            } footer: {
                VStack(alignment: .leading, spacing: 6) {
                    if let summary {
                        Text("\(summary.count) records mirrored from Apple Health. Read-only here — edit or delete them in the Health app.")
                    }
                    if let limited = store.limitedHistoryBefore(typeID) {
                        Text("History before \(limited.formatted(date: .abbreviated, time: .omitted)) isn't shared with Ayuvo — Health › Sharing › Apps › Ayuvo")
                    }
                }
                .font(.system(.caption2, design: .rounded))
            }
            .listRowBackground(AppColors.appCard)
        }
        .scrollContentBackground(.hidden)
        .background(AppColors.appBackground)
        .navigationTitle(type.displayName)
        .navigationBarTitleDisplayMode(.inline)
        .task(id: loadKey) {
            await load()
        }
        .onChange(of: range) { _, _ in
            selected = nil
            anchor = Date()
        }
    }

    // MARK: - Highlights

    @ViewBuilder
    private var highlightBadges: some View {
        if dynamicTypeSize.isAccessibilitySize {
            LazyVGrid(columns: [GridItem(.flexible()), GridItem(.flexible())], spacing: 8) {
                highlightContent
            }
        } else {
            HStack(spacing: 8) {
                highlightContent
            }
        }
    }

    @ViewBuilder
    private var highlightContent: some View {
        let highlights = series?.highlights ?? HealthHighlights()
        switch type.kind {
        case .cumulative, .duration, .session:
            StatBadge(label: "Total", value: valueText(highlights.total))
            StatBadge(label: "Average", value: valueText(highlights.average))
            StatBadge(label: "Latest", value: valueText(highlights.latest))
        case .discrete, .series:
            StatBadge(label: "Average", value: type.isBloodPressure ? bloodPressureAverage : valueText(highlights.average))
            StatBadge(label: "Range", value: rangeText(highlights))
            StatBadge(label: "Latest", value: type.isBloodPressure ? bloodPressureLatest : valueText(highlights.latest))
        case .category:
            StatBadge(label: "Entries", value: "\(highlights.count)")
            StatBadge(label: "Latest", value: summary?.latest?.valueText ?? (summary?.latest.map { HealthUnitFormatting.relativeText($0.endDate) } ?? "—"))
        }
    }

    private func valueText(_ value: Double?) -> String {
        HealthUnitFormatting.text(value, type: type)
    }

    private func rangeText(_ highlights: HealthHighlights) -> String {
        guard let min = highlights.min, let max = highlights.max else { return "—" }
        if type.isBloodPressure {
            return "\(HealthUnitFormatting.number(min, fractionDigits: 0))–\(HealthUnitFormatting.number(max, fractionDigits: 0))"
        }
        return "\(HealthUnitFormatting.display(min, type: type).value)–\(HealthUnitFormatting.display(max, type: type).text)"
    }

    private var bloodPressureAverage: String {
        guard let points = series?.points, !points.isEmpty else { return "—" }
        let systolic = points.compactMap(\.value)
        let diastolic = points.compactMap(\.value2)
        guard !systolic.isEmpty else { return "—" }
        return HealthUnitFormatting.bloodPressureText(
            systolic: systolic.reduce(0, +) / Double(systolic.count),
            diastolic: diastolic.isEmpty ? nil : diastolic.reduce(0, +) / Double(diastolic.count)
        )
    }

    private var bloodPressureLatest: String {
        guard let latest = summary?.latest else { return "—" }
        return HealthUnitFormatting.bloodPressureText(systolic: latest.value, diastolic: latest.value2)
    }

    // MARK: - Range navigation

    private var rangeTitle: String {
        let start = interval.start
        let end = interval.end.addingTimeInterval(-1)
        switch range {
        case .day:
            return start.formatted(date: .abbreviated, time: .omitted)
        case .week:
            return "\(start.formatted(.dateTime.month(.abbreviated).day())) – \(end.formatted(.dateTime.month(.abbreviated).day().year()))"
        case .month:
            return start.formatted(.dateTime.month(.wide).year())
        case .sixMonths:
            return "\(start.formatted(.dateTime.month(.abbreviated))) – \(end.formatted(.dateTime.month(.abbreviated).year()))"
        case .year:
            return start.formatted(.dateTime.year())
        }
    }

    private func step(_ direction: Int) {
        guard let next = calendar.date(byAdding: range.stepComponent, value: direction * range.stepCount, to: anchor) else { return }
        if direction > 0, !canGoForward { return }
        selected = nil
        anchor = next
    }

    private func load() async {
        isLoading = true
        series = await store.series(typeID: typeID, range: range, anchor: anchor)
        isLoading = false
    }
}
