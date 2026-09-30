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
    @Environment(MedicationStore.self) private var medicationStore
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    @AppStorage(OptionalNutrientGoals.storageKey) private var optionalNutrientGoalsData = Data()
    @AppStorage(WeightUnit.storageKey) private var weightUnitRaw = WeightUnit.lbs.rawValue
    @AppStorage(WaterSettings.unitKey) private var waterUnitRaw = WaterUnit.defaultUnit.rawValue
    @AppStorage(ActivitySettings.weekStartsOnMondayKey) private var weekStartsOnMonday = true

    @State private var model: MetricDetailModel
    @State private var showLogWeight = false
    @State private var showLogBodyFat = false
    @State private var showAllWeights = false
    @State private var showAllBodyFat = false
    /// Bumped when the "Calculate this metric" switch changes (UserDefaults is not observed).
    @State private var derivedSwitchRevision = 0

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
            workouts: workoutStore, importedWorkouts: importedWorkoutStore, health: healthStore, profile: profileStore,
            medications: medicationStore
        )
    }

    private var nutrientKey: String? {
        if case .nutrient(let key) = key { return key }
        return nil
    }

    /// Catalog entry of a `derived:<id>` metric.
    private var derivedInfo: DerivedMetricInfo? {
        if case .derived(let id) = key { return DerivedCatalog.shared.byID[id] }
        return nil
    }

    /// The reference nutrient behind the chart: `nutrient:<key>` metrics and the health nutrition types
    /// (`dietary_vitamin_d` → `vitamin_d`, from `resolve_metric`, docs/nutrients.md §5a).
    private var referenceNutrientKey: String? { descriptor.nutrientKey }

    /// Reference lines for the nutrient, personalised from the profile's age, sex and calorie goal, with the
    /// user's own goal when the nutrient is app-tracked and has one (docs/nutrients.md §4.2).
    private var nutrientLines: NutrientsReference.Lines? {
        referenceNutrientKey.map { NutrientCatalog.lines($0, profile: profileStore.profile, goals: OptionalNutrientGoals.decoded(from: optionalNutrientGoalsData)) }
    }

    /// Dashed rules of the chart: nutrient reference lines, else the single goal of the metric's goal source.
    private var chartReferenceLines: [ChartReferenceLine] {
        if let lines = nutrientLines { return NutrientCatalog.chartLines(lines) }
        return ChartReferenceLine.goal(sources.goal(for: descriptor.goalSource))
    }

    /// `nutrient:<key>` of a nutrient the food log does not record (docs/nutrients.md §5).
    @ViewBuilder private var supplementsOnlyNote: some View {
        if let nutrientKey, descriptor.foodTracked == false {
            Label {
                Text("Food isn't recorded for \(NutrientCatalog.title(nutrientKey)): this chart counts supplements only.")
                    .font(.system(.footnote, design: .rounded))
                    .foregroundStyle(.secondary)
            } icon: {
                Image(systemName: "pills.fill")
                    .foregroundStyle(AyuvoPalette.domain("medications"))
            }
            .accessibilityIdentifier("metric.nutrient.supplementsOnly")
        }
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
                    supplementsOnlyNote
                    intervalRow
                    chartContent
                    derivedSourceLine
                    badges
                }
                .padding(.vertical, 4)
            }

            if let referenceNutrientKey, let lines = nutrientLines {
                NutrientDetailSections(
                    nutrientKey: referenceNutrientKey, lines: lines, learnSlug: descriptor.learnSlug,
                    context: nutrientKey != nil
                        ? .app(extras: model.nutrientExtras, periodTitle: model.rangeTitle(calendar: calendar),
                               foodTracked: descriptor.foodTracked ?? true)
                        : .health(appChart: descriptor.nutrientMetric, foodTracked: descriptor.foodTracked ?? true)
                )
            }

            optionsSection

            if referenceNutrientKey == nil {
                Section("About") {
                    Text(descriptor.about)
                        .font(.system(.subheadline, design: .rounded))
                        .foregroundStyle(.secondary)
                        .accessibilityIdentifier("metric.about")
                }
            }

            if let info = derivedInfo {
                derivedMethodSection(info)
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
            HeadlineStat(label: parts.label, value: parts.value, unit: parts.unit, caption: nutrientDayCaption(headline) ?? parts.caption)
                .accessibilityIdentifier("metric.headline")
        }
    }

    /// Nutrient D (app and health nutrition types): the lines are hidden on hourly bars, so the header says
    /// "Today X of Y" (docs/nutrients.md §5, §5a).
    private func nutrientDayCaption(_ headline: MetricsReference.Headline) -> String? {
        guard let nutrientKey = referenceNutrientKey, let lines = nutrientLines, model.series?.range == .day else { return nil }
        let day = calendar.isDateInToday(model.anchor) ? String(localized: "Today") : model.rangeTitle(calendar: calendar)
        let value = NutrientCatalog.number(headline.value)
        if let recommended = lines.recommended {
            return String(localized: "\(day): \(value) of \(NutrientCatalog.text(recommended, key: nutrientKey)) \(NutrientCatalog.localizedLabel(lines.recommendedLabel).lowercased())")
        }
        if let limit = lines.limit {
            return String(localized: "\(day): \(value) of \(NutrientCatalog.text(limit, key: nutrientKey)) \(NutrientCatalog.localizedLabel(lines.limitLabel).lowercased())")
        }
        return day
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
            let span = String(localized: "\(ChartAxisStyle.date(window.bedtimeMs).formatted(time)) – \(ChartAxisStyle.date(window.wakeMs).formatted(time))", comment: "Time range: bedtime – wake time")
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
            let at = Date(timeIntervalSince1970: Double(headline.fromMs) / 1000)
            // Derived values are daily: the date alone.
            caption = derivedInfo != nil ? at.formatted(date: .abbreviated, time: .omitted) : at.formatted(date: .abbreviated, time: .shortened)
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
        case .nutrient(let nutrientKey):
            return (NutrientCatalog.number(value), NutrientCatalog.unit(nutrientKey))
        case .derived:
            guard let info = derivedInfo else { return ("—", "") }
            return DerivedMetricFormat.display(value, info: info)
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
            case .nutrient(let nutrientKey):
                MetricChart(
                    format: .nutrient(nutrientKey),
                    chartKind: descriptor.chartKind,
                    tint: descriptor.tint,
                    series: series,
                    selected: $model.selected,
                    referenceLines: chartReferenceLines,
                    calendar: calendar,
                    onTap: tapBucket
                )
            case .derived:
                if let info = derivedInfo {
                    MetricChart(
                        format: .derived(info),
                        chartKind: descriptor.chartKind,
                        tint: descriptor.tint,
                        series: series,
                        selected: $model.selected,
                        referenceLines: [],
                        calendar: calendar,
                        onTap: tapBucket
                    )
                }
            case .health:
                if let type = healthType {
                    if type.isSleep {
                        SleepChart(series: series, selected: $model.selected, calendar: calendar, onTap: tapBucket)
                            .accessibilityIdentifier("metric.chart")
                    } else {
                        HealthMetricChart(type: type, series: series, selected: $model.selected, calendar: calendar,
                                          referenceLines: chartReferenceLines, onTap: tapBucket)
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
        case .nutrient: return "Log food with this nutrient, or mark a supplement dose as taken, to see your trend here."
        case .derived:
            if case .derived(let id) = key, !DerivedSettings.isEnabled(id) {
                return "Calculation is off for this metric. Turn it on below to estimate it from your Apple Health data."
            }
            return "Ayuvo estimates this from your Apple Health data once enough readings are available."
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
                StatBadge(label: String(localized: "Total", comment: "Metric detail stat badge"), value: valueText(highlights.total))
                // Past D the health average is the total over days with a value: for a nutrient that is the
                // same "Average per logged day" the nutrient charts show (docs/nutrients.md §5a).
                if referenceNutrientKey != nil, model.range != .day {
                    StatBadge(label: String(localized: "Average per logged day", comment: "Metric detail stat badge"), value: valueText(highlights.average))
                        .accessibilityIdentifier("metric.nutrient.loggedDayAverage")
                } else {
                    StatBadge(label: String(localized: "Average", comment: "Metric detail stat badge"), value: valueText(highlights.average))
                }
                StatBadge(label: String(localized: "Latest", comment: "Metric detail stat badge"), value: valueText(highlights.latest))
            case .discrete, .series:
                StatBadge(label: String(localized: "Average", comment: "Metric detail stat badge"), value: valueText(highlights.average))
                StatBadge(label: String(localized: "Range", comment: "Metric detail stat badge"), value: rangeText(highlights))
                StatBadge(label: String(localized: "Latest", comment: "Metric detail stat badge"), value: type.isBloodPressure ? bloodPressureLatest : valueText(highlights.latest))
            case .category:
                StatBadge(label: String(localized: "Entries", comment: "Metric detail stat badge"), value: "\(highlights.count)")
                StatBadge(label: String(localized: "Latest", comment: "Metric detail stat badge"), value: healthStore.summary(for: type.id)?.latest.map { HealthUnitFormatting.relativeText($0.endDate) } ?? "—")
            }
        } else if nutrientKey != nil {
            StatBadge(label: String(localized: "Total", comment: "Metric detail stat badge"), value: valueText(highlights.total))
            StatBadge(label: String(localized: "Average per logged day", comment: "Metric detail stat badge"), value: valueText(model.nutrientExtras?.average.average))
                .accessibilityIdentifier("metric.nutrient.loggedDayAverage")
            StatBadge(label: String(localized: "Latest", comment: "Metric detail stat badge"), value: valueText(highlights.latest))
        } else if isSummed {
            StatBadge(label: String(localized: "Total", comment: "Metric detail stat badge"), value: valueText(highlights.total))
            StatBadge(label: String(localized: "Average", comment: "Metric detail stat badge"), value: valueText(highlights.average))
            StatBadge(label: String(localized: "Latest", comment: "Metric detail stat badge"), value: valueText(highlights.latest))
        } else {
            StatBadge(label: String(localized: "Average", comment: "Metric detail stat badge"), value: valueText(highlights.average))
            StatBadge(label: String(localized: "Range", comment: "Metric detail stat badge"), value: rangeText(highlights))
            StatBadge(label: String(localized: "Latest", comment: "Metric detail stat badge"), value: valueText(highlights.latest))
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

            if case .derived(let id) = key {
                derivedSwitch(id)
            }

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
            } else if case .derived(let id) = key {
                derivedSwitchFooter(id)
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
        case .nutrient, .derived:
            EmptyView()
        }
    }

    // MARK: - Derived metrics (docs/derived-metrics.md)

    /// Where the selected bucket's value (or the latest day's) came from: Apple Health's source, or Ayuvo's estimate.
    @ViewBuilder
    private var derivedSourceLine: some View {
        if derivedInfo != nil {
            let days: [DerivedDayValue] = {
                if let selected = model.selected {
                    return DerivedMetricSeries.days(in: selected, from: model.derivedDays, calendar: calendar)
                }
                return model.derivedDays.last.map { [$0] } ?? []
            }()
            if let text = DerivedMetricFormat.sourceText(days) {
                let native = days.allSatisfy(\.isNative)
                Label {
                    Text(model.selected == nil ? String(localized: "Latest: \(text)") : text)
                        .font(.system(.footnote, design: .rounded))
                        .foregroundStyle(.secondary)
                } icon: {
                    Image(systemName: native ? "heart.text.square" : "wand.and.stars")
                        .foregroundStyle(descriptor.tint)
                }
                .accessibilityIdentifier("metric.derived.source")
            }
        }
    }

    private func derivedSwitch(_ id: String) -> some View {
        let _ = derivedSwitchRevision
        let masterOn = DerivedSettings.isEnabled()
        return Toggle(isOn: Binding(
            get: { DerivedSettings.isEnabled(id) },
            set: { on in
                DerivedSettings.setEnabled(id, on)
                derivedSwitchRevision += 1
                DerivedMetricsService.shared.settingsDidChange()
            }
        )) {
            Label("Calculate this metric", systemImage: "wand.and.stars")
        }
        .disabled(!masterOn)
        .accessibilityIdentifier("metric.derived.calculate")
    }

    @ViewBuilder
    private func derivedSwitchFooter(_ id: String) -> some View {
        let _ = derivedSwitchRevision
        VStack(alignment: .leading, spacing: 6) {
            if !DerivedSettings.isEnabled() {
                Text("Derived metrics are off in Settings › Derived metrics.")
            } else if !DerivedSettings.isEnabled(id) {
                Text("Off: Ayuvo does not calculate this metric and its estimates are deleted. Values from Apple Health still show.")
                let dependents = DerivedCatalog.shared.dependents(of: id)
                if !dependents.isEmpty {
                    Text("Also affects: \(dependents.map(\.displayTitle).joined(separator: ", "))")
                        .accessibilityIdentifier("metric.derived.dependents")
                }
            } else {
                Text("Calculated on this iPhone from your Apple Health data. Never written back to Apple Health.")
            }
        }
    }

    /// Method, source, confidence and the catalog disclaimer.
    private func derivedMethodSection(_ info: DerivedMetricInfo) -> some View {
        let latestEstimate = model.derivedDays.last { !$0.isNative }
        return Section {
            VStack(alignment: .leading, spacing: 10) {
                Text(info.displayMethod)
                    .font(.system(.subheadline, design: .rounded))
                    .accessibilityIdentifier("metric.derived.method")
                LabeledContent("Confidence", value: DerivedMetricFormat.confidenceText(latestEstimate?.quality))
                    .font(.system(.subheadline, design: .rounded))
                    .accessibilityIdentifier("metric.derived.confidence")
                VStack(alignment: .leading, spacing: 2) {
                    Text("Source")
                        .font(.system(.caption, design: .rounded, weight: .semibold))
                        .foregroundStyle(.secondary)
                    Text(info.citation)
                        .font(.system(.caption, design: .rounded))
                        .foregroundStyle(.secondary)
                }
                .accessibilityIdentifier("metric.derived.citation")
            }
            .padding(.vertical, 2)
        } header: {
            Text("How it's calculated")
        } footer: {
            Text(DerivedCatalog.shared.displayDisclaimer)
                .accessibilityIdentifier("metric.derived.disclaimer")
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
