import SwiftUI

/// One detail screen for app metrics and health types (docs/ui-structure.md §6): range picker,
/// headline, ‹ interval ›, chart, Options (favourite, all data, sources, unit, log) and About.
struct MetricDetailView: View {
    let key: MetricKey

    @Environment(HealthDataStore.self) private var healthStore
    @Environment(FoodStore.self) private var foodStore
    @Environment(WaterStore.self) private var waterStore
    @Environment(FastingStore.self) private var fastingStore
    @Environment(WeightStore.self) private var weightStore
    @Environment(BodyFatStore.self) private var bodyFatStore
    @Environment(StrengthWorkoutStore.self) private var workoutStore
    @Environment(ImportedHealthWorkoutStore.self) private var importedWorkoutStore
    @Environment(ProfileStore.self) private var profileStore
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    @AppStorage(WeightUnit.storageKey) private var weightUnitRaw = WeightUnit.lbs.rawValue
    @AppStorage(WaterSettings.unitKey) private var waterUnitRaw = WaterUnit.defaultUnit.rawValue
    @AppStorage(ActivitySettings.weekStartsOnMondayKey) private var weekStartsOnMonday = true

    @State private var model: MetricDetailModel
    @State private var showLogWeight = false
    @State private var showLogBodyFat = false
    @State private var showAllWeights = false
    @State private var showAllBodyFat = false

    init(key: MetricKey) {
        self.key = key
        let descriptor = MetricCatalog.descriptor(for: key)
        _model = State(initialValue: MetricDetailModel(key: key, range: descriptor.ranges.contains(.week) ? .week : (descriptor.ranges.first ?? .week)))
    }

    private var descriptor: MetricDescriptor { MetricCatalog.descriptor(for: key) }
    private var calendar: Calendar { healthStore.calendar }
    private var healthType: HealthMetricType? {
        if case .health(let id) = key { return healthStore.metricType(for: id) }
        return nil
    }

    private var sources: MetricDataSources {
        MetricDataSources(
            food: foodStore, water: waterStore, fasting: fastingStore, weight: weightStore, bodyFat: bodyFatStore,
            workouts: workoutStore, importedWorkouts: importedWorkoutStore, health: healthStore, profile: profileStore
        )
    }

    private var loadKey: String {
        "\(model.loadKey(sources: sources, calendar: calendar))|\(weightUnitRaw)|\(waterUnitRaw)|\(weekStartsOnMonday)"
    }

    private var hasHealthUnitPicker: Bool {
        guard case .health(let id) = key else { return false }
        return ["weight", "height", "waist_circumference", "blood_glucose", "body_temperature", "basal_body_temperature", "hydration", "distance", "distance_cycling", "distance_swimming"].contains(id)
    }

    var body: some View {
        @Bindable var model = model
        List {
            Section {
                VStack(alignment: .leading, spacing: 14) {
                    if descriptor.ranges.count > 1 {
                        RangePicker(selection: $model.range, options: descriptor.ranges)
                    }
                    headline
                    intervalRow
                    chartContent
                    badges
                }
                .padding(.vertical, 4)
            }

            optionsSection

            Section("About") {
                Text(descriptor.about)
                    .font(.system(.subheadline, design: .rounded))
                    .foregroundStyle(.secondary)
                    .accessibilityIdentifier("metric.about")
            }
        }
        .listStyle(.insetGrouped)
        .navigationTitle(descriptor.title)
        .navigationBarTitleDisplayMode(.inline)
        .task(id: loadKey) {
            await model.load(sources: sources, calendar: calendar)
        }
        .onChange(of: model.range) { _, _ in
            model.rangeChanged()
        }
        .sheet(isPresented: $showLogWeight) {
            LogWeightSheet(currentWeightKg: weightStore.latestEntry?.weightKg ?? profileStore.profile.weightKg) { weightKg in
                weightStore.addEntry(WeightEntry(weightKg: weightKg))
            }
        }
        .sheet(isPresented: $showLogBodyFat) {
            let seed = bodyFatStore.latestEntry?.bodyFatFraction ?? profileStore.profile.bodyFatPercentage ?? 0.20
            LogBodyFatSheet(currentFraction: seed) { fraction in
                bodyFatStore.addEntry(BodyFatEntry(bodyFatFraction: fraction))
            }
        }
        .sheet(isPresented: $showAllWeights) {
            AllWeightHistoryView(
                entries: weightStore.entries.sorted { $0.date > $1.date },
                useMetric: weightUnitRaw == WeightUnit.kg.rawValue,
                onDelete: { entry in weightStore.deleteEntry(entry) }
            )
        }
        .sheet(isPresented: $showAllBodyFat) {
            AllBodyFatHistoryView(
                entries: bodyFatStore.entries.sorted { $0.date > $1.date },
                onDelete: { entry in bodyFatStore.deleteEntry(entry) }
            )
        }
    }

    // MARK: - Header

    @ViewBuilder
    private var headline: some View {
        if healthType?.isSleep == true, model.series?.range == .day, let window = model.series?.sleepWindow {
            // Apple's sleep D header: TIME IN BED next to TIME ASLEEP (docs/charts.md › Sleep).
            SleepDayHeader(window: window)
        } else if healthType?.isSleep == true, let series = model.series {
            let parts = sleepHeadlineParts(series)
            HeadlineStat(label: parts.label, value: parts.value, unit: "", caption: parts.caption)
                .accessibilityIdentifier("metric.headline")
        } else if let selected = model.selected, let series = model.series {
            let parts = selectedHeadlineParts(selected, range: series.range)
            HeadlineStat(label: parts.label, value: parts.value, unit: parts.unit, caption: parts.caption)
                .accessibilityIdentifier("metric.headline")
        } else if let headline = model.series?.headline {
            let parts = headlineParts(headline)
            HeadlineStat(label: parts.label, value: parts.value, unit: parts.unit, caption: parts.caption)
                .accessibilityIdentifier("metric.headline")
        }
    }

    /// Headline while a bucket is selected: its value and date (docs/charts.md › Selection).
    private func selectedHeadlineParts(_ point: HealthChartPoint, range: HealthDetailRange) -> (label: String, value: String, unit: String, caption: String) {
        let summed: Bool
        if let type = healthType {
            summed = type.kind == .cumulative || type.kind == .duration || type.kind == .session || type.kind == .category
        } else {
            summed = isSummed
        }
        let label: String
        if summed {
            label = range == .sixMonths || range == .year ? String(localized: "Daily Average") : String(localized: "Total")
        } else if descriptor.aggregation == .last {
            label = String(localized: "Latest")
        } else {
            label = String(localized: "Average")
        }
        let shown: (value: String, unit: String)
        if let type = healthType, type.isBloodPressure {
            shown = (HealthUnitFormatting.bloodPressureText(systolic: point.value, diastolic: point.value2 ?? point.min), "mmHg")
        } else {
            shown = valueParts(point.value)
        }
        return (label, shown.value, shown.unit, ChartAxisStyle.bucketTitle(point, range: range))
    }

    /// TIME ASLEEP (D), AVG TIME ASLEEP (W+), IN BED for an in-bed-only night; never an invented value.
    private func sleepHeadlineParts(_ series: HealthChartSeries) -> (label: String, value: String, caption: String) {
        let time = Date.FormatStyle.dateTime.hour().minute()
        if series.range == .day {
            guard let window = series.sleepWindow else {
                return (String(localized: "Time Asleep"), "—", model.rangeTitle(calendar: calendar))
            }
            let span = "\(ChartAxisStyle.date(window.bedtimeMs).formatted(time)) – \(ChartAxisStyle.date(window.wakeMs).formatted(time))"
            if window.asleepS > 0 {
                return (String(localized: "Time Asleep"), HealthUnitFormatting.durationText(seconds: Double(window.asleepS)), span)
            }
            return (String(localized: "In Bed"), HealthUnitFormatting.durationText(seconds: Double(window.inBedS)), span)
        }
        if let selected = model.selected, let asleep = selected.value {
            var caption = ChartAxisStyle.bucketTitle(selected, range: series.range)
            if let bed = selected.min, let wake = selected.max {
                caption += " · \(SleepChart.clockText(bed, calendar: calendar))–\(SleepChart.clockText(wake, calendar: calendar))"
            }
            return (String(localized: "Time Asleep"), HealthUnitFormatting.durationText(seconds: asleep), caption)
        }
        guard let head = series.sleepRange?.headline, head.nights > 0, let asleep = head.asleepS else {
            return (String(localized: "Avg Time Asleep"), "—", model.rangeTitle(calendar: calendar))
        }
        var caption = model.rangeTitle(calendar: calendar)
        if let bed = head.bedOffsetMin, let wake = head.wakeOffsetMin {
            caption += " · \(SleepChart.clockText(bed, calendar: calendar))–\(SleepChart.clockText(wake, calendar: calendar))"
        }
        return (String(localized: "Avg Time Asleep"), HealthUnitFormatting.durationText(seconds: asleep), caption)
    }

    private func headlineParts(_ headline: MetricsReference.Headline) -> (label: String, value: String, unit: String, caption: String) {
        let label: String
        switch headline.kind {
        case .total: label = String(localized: "Total")
        case .average: label = String(localized: "Average")
        case .latest: label = String(localized: "Latest")
        }
        var caption = model.rangeTitle(calendar: calendar)
        if headline.kind == .latest, headline.value != nil {
            caption = Date(timeIntervalSince1970: Double(headline.fromMs) / 1000).formatted(date: .abbreviated, time: .shortened)
        } else if headline.kind == .average, headline.daysWithData > 0, isSummed {
            caption = headline.daysWithData == 1
                ? String(localized: "\(caption) · 1 day with data")
                : String(localized: "\(caption) · \(headline.daysWithData) days with data")
        }
        let shown = valueParts(headline.value)
        return (label, shown.value, shown.unit, caption)
    }

    private var isSummed: Bool { descriptor.aggregation.isSummed }

    private func valueParts(_ value: Double?) -> (value: String, unit: String) {
        switch key {
        case .app(let metric):
            return AppMetricFormat.display(value, metric: metric)
        case .health:
            guard let type = healthType else { return ("—", "") }
            guard let value else { return ("—", HealthUnitFormatting.unitLabel(for: type)) }
            if type.unit == "s" { return (HealthUnitFormatting.durationText(seconds: value), "") }
            if type.isBloodPressure { return (HealthUnitFormatting.number(value, fractionDigits: 0), "mmHg") }
            let display = HealthUnitFormatting.display(value, type: type)
            return (display.value, display.unit)
        }
    }

    private func valueText(_ value: Double?) -> String {
        let parts = valueParts(value)
        return parts.unit.isEmpty || parts.value == "—" ? parts.value : "\(parts.value) \(parts.unit)"
    }

    private var intervalRow: some View {
        let canGoForward = model.canGoForward(calendar: calendar)
        return HStack {
            Button {
                model.step(-1, calendar: calendar)
            } label: {
                Image(systemName: "chevron.left")
                    .frame(width: 32, height: 32)
            }
            .buttonStyle(.plain)
            .accessibilityLabel(Text("Previous \(model.range.englishTitle)"))
            .accessibilityIdentifier("metric.prev")
            Spacer()
            Text(model.rangeTitle(calendar: calendar))
                .font(.system(.subheadline, design: .rounded, weight: .semibold))
                .foregroundStyle(.secondary)
            Spacer()
            Button {
                model.step(1, calendar: calendar)
            } label: {
                Image(systemName: "chevron.right")
                    .frame(width: 32, height: 32)
            }
            .buttonStyle(.plain)
            .disabled(!canGoForward)
            .opacity(canGoForward ? 1 : 0.35)
            .accessibilityLabel(Text("Next \(model.range.englishTitle)"))
            .accessibilityIdentifier("metric.next")
        }
    }

    // MARK: - Chart

    @ViewBuilder
    private var chartContent: some View {
        @Bindable var model = model
        if let series = model.series, !series.isEmpty {
            switch key {
            case .app(let metric):
                MetricChart(
                    metric: metric,
                    chartKind: descriptor.chartKind,
                    tint: descriptor.tint,
                    series: series,
                    selected: $model.selected,
                    goal: sources.goal(for: descriptor.goalSource),
                    calendar: calendar,
                    onTap: tapBucket
                )
            case .health:
                if let type = healthType {
                    if type.isSleep {
                        SleepChart(series: series, selected: $model.selected, calendar: calendar, onTap: tapBucket)
                            .accessibilityIdentifier("metric.chart")
                    } else {
                        HealthMetricChart(type: type, series: series, selected: $model.selected, calendar: calendar,
                                          goal: sources.goal(for: descriptor.goalSource), onTap: tapBucket)
                            .accessibilityIdentifier("metric.chart")
                    }
                }
            }
        } else if model.isLoading {
            ProgressView()
                .frame(maxWidth: .infinity)
                .frame(height: 210)
        } else {
            EmptyStateView("No data in this range", systemImage: descriptor.systemImage, description: emptyDescription)
                .frame(height: 210)
        }
    }

    private func tapBucket(_ point: HealthChartPoint) {
        withAnimation(.easeOut(duration: 0.2)) {
            model.tap(point, ranges: descriptor.ranges, calendar: calendar)
        }
    }

    private var emptyDescription: LocalizedStringKey {
        switch key {
        case .app: return "Log an entry to see your trend here."
        case .health: return healthStore.isEnabled ? "Try another range, or pull to refresh on the Health Data screen." : "Health sync is off. Existing data stays here read-only."
        }
    }

    /// Sleep D hides them: Total / Average / Latest would all repeat the header (docs/charts.md).
    private var hidesBadges: Bool { healthType?.isSleep == true && model.range == .day }

    @ViewBuilder
    private var badges: some View {
        if hidesBadges {
            EmptyView()
        } else if dynamicTypeSize.isAccessibilitySize {
            LazyVGrid(columns: [GridItem(.flexible()), GridItem(.flexible())], spacing: 8) { badgeContent }
        } else {
            HStack(spacing: 8) { badgeContent }
        }
    }

    @ViewBuilder
    private var badgeContent: some View {
        let highlights = model.series?.highlights ?? HealthHighlights()
        if let type = healthType {
            switch type.kind {
            case .cumulative, .duration, .session:
                StatBadge(label: "Total", value: valueText(highlights.total))
                StatBadge(label: "Average", value: valueText(highlights.average))
                StatBadge(label: "Latest", value: valueText(highlights.latest))
            case .discrete, .series:
                StatBadge(label: "Average", value: valueText(highlights.average))
                StatBadge(label: "Range", value: rangeText(highlights))
                StatBadge(label: "Latest", value: type.isBloodPressure ? bloodPressureLatest : valueText(highlights.latest))
            case .category:
                StatBadge(label: "Entries", value: "\(highlights.count)")
                StatBadge(label: "Latest", value: healthStore.summary(for: type.id)?.latest.map { HealthUnitFormatting.relativeText($0.endDate) } ?? "—")
            }
        } else if isSummed {
            StatBadge(label: "Total", value: valueText(highlights.total))
            StatBadge(label: "Average", value: valueText(highlights.average))
            StatBadge(label: "Latest", value: valueText(highlights.latest))
        } else {
            StatBadge(label: "Average", value: valueText(highlights.average))
            StatBadge(label: "Range", value: rangeText(highlights))
            StatBadge(label: "Latest", value: valueText(highlights.latest))
        }
    }

    private func rangeText(_ highlights: HealthHighlights) -> String {
        guard let min = highlights.min, let max = highlights.max else { return "—" }
        let low = valueParts(min), high = valueParts(max)
        return high.unit.isEmpty ? "\(low.value)–\(high.value)" : "\(low.value)–\(high.value) \(high.unit)"
    }

    private var bloodPressureLatest: String {
        guard case .health(let id) = key, let latest = healthStore.summary(for: id)?.latest else { return "—" }
        return HealthUnitFormatting.bloodPressureText(systolic: latest.value, diastolic: latest.value2)
    }

    // MARK: - Options

    private var optionsSection: some View {
        Section {
            Toggle(isOn: Binding(
                get: { healthStore.isPinned(key.id) },
                set: { _ in healthStore.togglePin(key.id) }
            )) {
                Label("Add to Favourites", systemImage: "star")
            }
            .accessibilityIdentifier("metric.favourite")

            allDataRow

            if case .health(let id) = key {
                NavigationLink(value: HealthRoute.sources(id)) {
                    Label("Data Sources & Access", systemImage: "square.stack.3d.up")
                }
                .accessibilityIdentifier("metric.sources")
                if hasHealthUnitPicker, let type = healthType {
                    NavigationLink(value: HealthRoute.unit(id)) {
                        LabeledContent {
                            Text(HealthUnitFormatting.unitLabel(for: type))
                        } label: {
                            Label("Unit", systemImage: "ruler")
                        }
                    }
                    .accessibilityIdentifier("metric.unit")
                }
            }

            if case .app(let metric) = key {
                appUnitRow(metric)
                appLogRow(metric)
            }
        } header: {
            Text("Options")
        } footer: {
            if case .health(let id) = key {
                VStack(alignment: .leading, spacing: 6) {
                    if let summary = healthStore.summary(for: id) {
                        Text("\(summary.count) records mirrored from Apple Health. Read-only here — edit or delete them in the Health app.")
                    }
                    if let limited = healthStore.limitedHistoryBefore(id) {
                        Text("History before \(limited.formatted(date: .abbreviated, time: .omitted)) isn't shared with Ayuvo — Health › Sharing › Apps › Ayuvo")
                    }
                }
                .font(.system(.caption2, design: .rounded))
            }
        }
    }

    @ViewBuilder
    private var allDataRow: some View {
        switch key {
        case .health(let id):
            NavigationLink(value: HealthRoute.allData(id)) {
                Label("Show All Data", systemImage: "list.bullet.rectangle")
            }
            .accessibilityIdentifier("metric.allData")
        case .app(.weight):
            Button { showAllWeights = true } label: {
                Label("Show All Data", systemImage: "list.bullet.rectangle")
            }
            .accessibilityIdentifier("metric.allData")
        case .app(.bodyFat):
            Button { showAllBodyFat = true } label: {
                Label("Show All Data", systemImage: "list.bullet.rectangle")
            }
            .accessibilityIdentifier("metric.allData")
        case .app(let metric):
            NavigationLink(value: MetricRoute.allData(metric)) {
                Label("Show All Data", systemImage: "list.bullet.rectangle")
            }
            .accessibilityIdentifier("metric.allData")
        }
    }

    @ViewBuilder
    private func appUnitRow(_ metric: AppMetric) -> some View {
        switch metric {
        case .weight:
            Picker(selection: $weightUnitRaw) {
                Text("kg").tag(WeightUnit.kg.rawValue)
                Text("lbs").tag(WeightUnit.lbs.rawValue)
            } label: {
                Label("Unit", systemImage: "ruler")
            }
            .accessibilityIdentifier("metric.unit")
        case .water:
            Picker(selection: $waterUnitRaw) {
                ForEach(WaterUnit.allCases) { unit in
                    Text(unit.symbol).tag(unit.rawValue)
                }
            } label: {
                Label("Unit", systemImage: "ruler")
            }
            .accessibilityIdentifier("metric.unit")
        default:
            EmptyView()
        }
    }

    @ViewBuilder
    private func appLogRow(_ metric: AppMetric) -> some View {
        switch metric {
        case .weight:
            Button { showLogWeight = true } label: {
                Label("Log Weight", systemImage: "plus.circle")
            }
            .accessibilityIdentifier("metric.log")
        case .bodyFat:
            Button { showLogBodyFat = true } label: {
                Label("Log Body Fat", systemImage: "plus.circle")
            }
            .accessibilityIdentifier("metric.log")
        case .water:
            Menu {
                ForEach([250, 500, 750], id: \.self) { amount in
                    Button(WaterUnit(rawValue: waterUnitRaw)?.formatted(milliliters: amount) ?? "\(amount) ml") {
                        _ = waterStore.add(milliliters: amount, on: Date())
                    }
                }
            } label: {
                Label("Log Water", systemImage: "plus.circle")
            }
            .accessibilityIdentifier("metric.log")
        default:
            EmptyView()
        }
    }
}

/// Daily values of an app metric (or its raw water entries), newest first.
struct AppMetricAllDataView: View {
    let metric: AppMetric

    @Environment(HealthDataStore.self) private var healthStore
    @Environment(FoodStore.self) private var foodStore
    @Environment(WaterStore.self) private var waterStore
    @Environment(FastingStore.self) private var fastingStore
    @Environment(WeightStore.self) private var weightStore
    @Environment(BodyFatStore.self) private var bodyFatStore
    @Environment(StrengthWorkoutStore.self) private var workoutStore
    @Environment(ImportedHealthWorkoutStore.self) private var importedWorkoutStore
    @Environment(ProfileStore.self) private var profileStore

    private var sources: MetricDataSources {
        MetricDataSources(
            food: foodStore, water: waterStore, fasting: fastingStore, weight: weightStore, bodyFat: bodyFatStore,
            workouts: workoutStore, importedWorkouts: importedWorkoutStore, health: healthStore, profile: profileStore
        )
    }

    private struct DayRow: Identifiable {
        let day: Date
        let value: Double
        var id: Date { day }
    }

    private var days: [DayRow] {
        let calendar = Calendar.current
        let aggregation = MetricCatalog.descriptor(for: .app(metric)).aggregation
        var totals: [Date: (sum: Double, count: Int, last: (Int64, Double)?)] = [:]
        for entry in AppMetricSampleExtractor.entries(for: metric, sources: sources, calendar: calendar) {
            guard let value = entry.value else { continue }
            let day = calendar.startOfDay(for: Date(timeIntervalSince1970: Double(entry.tMs) / 1000))
            var bucket = totals[day] ?? (0, 0, nil)
            bucket.sum += aggregation == .count ? 1 : value
            bucket.count += 1
            if bucket.last == nil || entry.tMs >= bucket.last!.0 { bucket.last = (entry.tMs, value) }
            totals[day] = bucket
        }
        return totals.map { day, bucket in
            let value: Double
            switch aggregation {
            case .avg: value = bucket.sum / Double(bucket.count)
            case .last: value = bucket.last?.1 ?? bucket.sum
            case .sum, .count, .duration: value = bucket.sum
            }
            return DayRow(day: day, value: value)
        }
        .sorted { $0.day > $1.day }
    }

    var body: some View {
        List {
            if metric == .water {
                Section {
                    ForEach(waterStore.entries.sorted { $0.date > $1.date }) { entry in
                        LabeledContent(entry.date.formatted(date: .abbreviated, time: .shortened)) {
                            Text(AppMetricFormat.text(Double(entry.milliliters), metric: .water))
                        }
                    }
                    .onDelete { offsets in
                        let sorted = waterStore.entries.sorted { $0.date > $1.date }
                        for index in offsets { waterStore.delete(id: sorted[index].id) }
                    }
                }
            } else {
                Section {
                    ForEach(days) { row in
                        LabeledContent(row.day.formatted(date: .abbreviated, time: .omitted)) {
                            Text(AppMetricFormat.text(row.value, metric: metric))
                        }
                    }
                } footer: {
                    Text("Daily values from what you logged in Ayuvo.")
                }
            }
            if days.isEmpty {
                EmptyStateView("No data yet", systemImage: MetricCatalog.descriptor(for: .app(metric)).systemImage)
            }
        }
        .listStyle(.insetGrouped)
        .navigationTitle(MetricCatalog.descriptor(for: .app(metric)).title)
        .navigationBarTitleDisplayMode(.inline)
    }
}
