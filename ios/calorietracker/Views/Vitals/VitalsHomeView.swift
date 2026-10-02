import SwiftUI

/// Browse › Camera measurements (docs/camera-vitals.md §7.1 Vitals home): start buttons, the latest scan per mode,
/// an HR trend per mode, HR / RMSSD baselines, history by day and the disclaimer. Finger and face results are never
/// merged.
struct VitalsHomeView: View {
    @AppStorage(VitalsSettings.experimentalKey) private var experimentalEnabled = false
    @AppStorage(VitalsSettings.researchKey) private var researchEnabled = false
    @State private var scanMode: VitalsMode?
    @State private var showCompare = false
    @State private var trendMode: VitalsMode = .finger
    @State private var trendRange: HealthDetailRange = .month
    @State private var selected: HealthChartPoint?

    private var store: VitalsStore { VitalsStore.shared }

    var body: some View {
        List {
            Section {
                startButton(.finger, title: "Finger scan", subtitle: "Fingertip on the rear camera and flash")
                startButton(.face, title: "Face scan", subtitle: "Front camera, about a minute of stillness")
                Button {
                    showCompare = true
                } label: {
                    HStack(spacing: 12) {
                        CategoryIconView(systemImage: "rectangle.on.rectangle", tint: AyuvoPalette.heart)
                        VStack(alignment: .leading, spacing: 2) {
                            Text("Compare finger & face").font(.system(.body, design: .rounded)).foregroundStyle(.primary)
                            Text("A finger scan, then a face scan, side by side").font(.system(.caption, design: .rounded)).foregroundStyle(.secondary)
                        }
                        Spacer()
                        Image(systemName: "chevron.right").font(.caption.weight(.semibold)).foregroundStyle(.tertiary)
                    }
                }
                .buttonStyle(.plain)
                .accessibilityIdentifier("vitals.start.compare")
            } header: {
                Text("Measure")
            }

            Section {
                NavigationLink {
                    VitalsValidationView()
                } label: {
                    Label("Validation", systemImage: "checkmark.seal")
                }
                .accessibilityIdentifier("vitals.validation")
                if experimentalEnabled || researchEnabled {
                    NavigationLink {
                        VitalsCalibrationView()
                    } label: {
                        Label("Calibration", systemImage: "slider.horizontal.3")
                    }
                    .accessibilityIdentifier("vitals.calibration")
                }
            } footer: {
                Text("Validation compares your scans with reference readings you add to them.")
            }

            if !store.scans.isEmpty {
                Section("Latest") {
                    ForEach(VitalsMode.allCases) { mode in
                        if let latest = store.latest(mode) {
                            NavigationLink {
                                ScanDetailView(scanID: latest.id)
                            } label: {
                                VitalsScanRow(record: latest, showsDate: true)
                            }
                            .accessibilityIdentifier("vitals.latest.\(mode.shortName)")
                        }
                    }
                }

                Section {
                    Picker("Mode", selection: $trendMode) {
                        Text("Finger").tag(VitalsMode.finger)
                        Text("Face").tag(VitalsMode.face)
                    }
                    .pickerStyle(.segmented)
                    RangePicker(selection: $trendRange, options: [.week, .month, .sixMonths])
                    trendChart
                } header: {
                    Text("Heart rate trend")
                }

                baselinesSection

                ForEach(days, id: \.day) { group in
                    Section {
                        ForEach(group.scans, id: \.id) { record in
                            NavigationLink {
                                ScanDetailView(scanID: record.id)
                            } label: {
                                VitalsScanRow(record: record, showsDate: false)
                            }
                        }
                    } header: {
                        Text(group.title)
                    }
                }
            }

            Section {
            } footer: {
                Text(VitalsText.disclaimer)
                    .accessibilityIdentifier("vitals.disclaimer")
            }
        }
        .listStyle(.insetGrouped)
        .navigationTitle("Camera measurements")
        .navigationBarTitleDisplayMode(.inline)
        .accessibilityIdentifier("vitals.home")
        .task(id: store.revision) {
            await store.reload()
            // Open the trend on the mode that has scans.
            if store.scans(mode: trendMode).isEmpty, let latest = store.scans.first, let mode = VitalsMode(rawValue: latest.mode) {
                trendMode = mode
            }
        }
        .fullScreenCover(item: $scanMode) { mode in
            ScanFlowView(mode: mode)
        }
        .fullScreenCover(isPresented: $showCompare) {
            CompareFlowView()
        }
        .onChange(of: trendRange) { _, _ in selected = nil }
        .onChange(of: trendMode) { _, _ in selected = nil }
    }

    private func startButton(_ mode: VitalsMode, title: LocalizedStringKey, subtitle: LocalizedStringKey) -> some View {
        Button {
            scanMode = mode
        } label: {
            HStack(spacing: 12) {
                CategoryIconView(systemImage: mode.systemImage, tint: AyuvoPalette.heart)
                VStack(alignment: .leading, spacing: 2) {
                    Text(title).font(.system(.body, design: .rounded)).foregroundStyle(.primary)
                    Text(subtitle).font(.system(.caption, design: .rounded)).foregroundStyle(.secondary)
                }
                Spacer()
                Image(systemName: "chevron.right").font(.caption.weight(.semibold)).foregroundStyle(.tertiary)
            }
        }
        .buttonStyle(.plain)
        .accessibilityIdentifier("vitals.start.\(mode.shortName)")
    }

    // MARK: Trend

    @ViewBuilder
    private var trendChart: some View {
        let series = VitalsTrend.series(store.scans(mode: trendMode), range: trendRange, now: Date(), calendar: .current)
        if series.isEmpty {
            Text("No valid \(trendMode == .finger ? String(localized: "finger") : String(localized: "face")) scans in this range yet.")
                .font(.system(.footnote, design: .rounded))
                .foregroundStyle(.secondary)
                .frame(maxWidth: .infinity, minHeight: 80)
        } else {
            MetricChart(format: VitalsTrend.format, chartKind: .line, tint: AyuvoPalette.heart, series: series,
                        selected: $selected, referenceLines: [], calendar: .current)
                .accessibilityIdentifier("vitals.trend")
        }
    }

    // MARK: Baselines

    private var baselinesSection: some View {
        Section {
            ForEach(VitalsMode.allCases) { mode in
                let scans = store.scans(mode: mode)
                if !scans.isEmpty {
                    VStack(alignment: .leading, spacing: 6) {
                        Label(mode == .finger ? "Finger" : "Face", systemImage: mode.systemImage)
                            .font(.system(.subheadline, design: .rounded, weight: .semibold))
                        VitalsBaselineGrid(baselines: VitalsTrend.baselines(scans, now: Date()))
                    }
                    .padding(.vertical, 2)
                }
            }
        } header: {
            Text("Your usual values")
        } footer: {
            Text("Medians of valid scans of each type. A window needs at least \(Int(VitalsConfig.shared.baselineMinN)) scans.")
        }
    }

    // MARK: History

    private var days: [(day: String, title: String, scans: [VitalScanRecord])] {
        var order: [String] = []
        var groups: [String: [VitalScanRecord]] = [:]
        for s in store.scans {
            if groups[s.localDay] == nil { order.append(s.localDay) }
            groups[s.localDay, default: []].append(s)
        }
        return order.map { day in
            let date = groups[day]?.first.map { Date(timeIntervalSince1970: Double($0.startMs) / 1000) } ?? Date()
            return (day, date.formatted(.dateTime.weekday(.wide).day().month(.wide).year()), groups[day] ?? [])
        }
    }
}

/// One history row: mode icon, time, HR and quality grade.
struct VitalsScanRow: View {
    let record: VitalScanRecord
    let showsDate: Bool

    var body: some View {
        let mode = VitalsMode(rawValue: record.mode) ?? .finger
        let hr = VitalsTrend.validValue(record, "heart_rate")
        let quality = (try? VitalsJSON.parse(record.qualityJSON)) ?? .null
        let date = Date(timeIntervalSince1970: Double(record.startMs) / 1000)
        HStack(spacing: 12) {
            CategoryIconView(systemImage: mode.systemImage, tint: AyuvoPalette.heart)
            VStack(alignment: .leading, spacing: 2) {
                Text(mode == .finger ? "Finger scan" : "Face scan")
                    .font(.system(.body, design: .rounded))
                Text(showsDate ? date.formatted(date: .abbreviated, time: .shortened) : date.formatted(date: .omitted, time: .shortened))
                    .font(.system(.caption, design: .rounded))
                    .foregroundStyle(.secondary)
            }
            Spacer(minLength: 8)
            VStack(alignment: .trailing, spacing: 2) {
                Text(hr.map { "\(Int($0.rounded())) " + String(localized: "bpm") } ?? String(localized: "No HR"))
                    .font(.ayuvoNumber(.subheadline))
                Text(record.rejectReason == nil ? vitalsQualityText(score: record.qualityScore, grade: quality["grade"].string)
                                                : String(localized: "Not usable"))
                    .font(.system(.caption, design: .rounded))
                    .foregroundStyle(vitalsGradeColor(quality["grade"].string))
            }
        }
        .accessibilityElement(children: .combine)
    }
}

struct VitalsBaselineGrid: View {
    let baselines: [(metric: String, windows: [(label: String, value: Double?)])]

    var body: some View {
        Grid(alignment: .leading, horizontalSpacing: 12, verticalSpacing: 4) {
            GridRow {
                Text("")
                ForEach(Array((baselines.first?.windows ?? []).enumerated()), id: \.offset) { _, w in
                    Text(w.label).font(.system(.caption2, design: .rounded)).foregroundStyle(.secondary)
                }
            }
            ForEach(baselines, id: \.metric) { row in
                GridRow {
                    Text(VitalsText.metricTitle(row.metric))
                        .font(.system(.caption, design: .rounded))
                        .lineLimit(1)
                        .minimumScaleFactor(0.8)
                    ForEach(Array(row.windows.enumerated()), id: \.offset) { _, w in
                        Text(w.value.map { $0.formatted(.number.precision(.fractionLength(0...1))) } ?? "—")
                            .font(.ayuvoNumber(.caption))
                            .monospacedDigit()
                    }
                }
            }
        }
    }
}

/// Trend series and baselines from saved scans (valid values only; finger and face kept apart).
enum VitalsTrend {
    static func validValue(_ record: VitalScanRecord, _ id: String) -> Double? {
        guard record.rejectReason == nil, let json = try? VitalsJSON.parse(record.resultsJSON) else { return nil }
        let m = json["metrics"][id]
        return m["status"].string == "valid" ? m["value"].double : nil
    }

    static var format: MetricChartFormat {
        MetricChartFormat(title: VitalsText.metricTitle("heart_rate"), unit: String(localized: "bpm"),
                          text: { v in v.map { "\(Int($0.rounded())) " + String(localized: "bpm") } ?? "—" },
                          chartValue: { $0 })
    }

    /// Daily (W, M) or weekly (6M) mean HR of valid scans in the range ending now.
    static func series(_ scans: [VitalScanRecord], range: HealthDetailRange, now: Date, calendar: Calendar) -> HealthChartSeries {
        let zone = MetricsReference.Zone(calendar: calendar)
        let nowMs = Int64(now.timeIntervalSince1970 * 1000)
        let bounds = MetricsReference.bucketBounds(range: range, anchorMs: nowMs, zone: zone,
                                                   weekStart: ActivitySettings.weekStart(), nowMs: nowMs)
        var points: [HealthChartPoint] = []
        var all: [Double] = []
        var latest: (Double, Date)?
        for b in bounds.buckets {
            let vals = scans.filter { $0.startMs >= b.startMs && $0.startMs < b.endMs }.compactMap { validValue($0, "heart_rate") }
            let start = Date(timeIntervalSince1970: Double(b.startMs) / 1000), end = Date(timeIntervalSince1970: Double(b.endMs) / 1000)
            var p = HealthChartPoint(start: start, end: end, value: nil, min: nil, max: nil, value2: nil, count: vals.count, stage: nil)
            if !vals.isEmpty {
                p.value = vals.reduce(0, +) / Double(vals.count)
                p.min = vals.min()
                p.max = vals.max()
                all += vals
                latest = (p.value!, end)
            }
            points.append(p)
        }
        var highlights = HealthHighlights()
        if !all.isEmpty {
            highlights.average = all.reduce(0, +) / Double(all.count)
            highlights.min = all.min()
            highlights.max = all.max()
            highlights.latest = latest?.0
            highlights.latestAt = latest?.1
            highlights.count = all.count
        }
        let interval = DateInterval(start: Date(timeIntervalSince1970: Double(bounds.startMs) / 1000),
                                    end: Date(timeIntervalSince1970: Double(bounds.endMs) / 1000))
        return HealthChartSeries(range: range, interval: interval, points: points, stagePoints: [], highlights: highlights,
                                 headline: nil, sleepWindow: nil, sleepRange: nil)
    }

    /// HR and RMSSD medians over 7, 14, 30 days and all time (`baselines`, valid scans of one mode).
    static func baselines(_ scans: [VitalScanRecord], now: Date, _ cfg: VitalsConfig = .shared) -> [(metric: String, windows: [(label: String, value: Double?)])] {
        let nowMs = Double(now.timeIntervalSince1970 * 1000)
        return ["heart_rate", "hrv_rmssd"].map { metric in
            let values = scans.compactMap { s in validValue(s, metric).map { VitalsEngine.TimedValue(tMs: Double(s.startMs), value: $0) } }
            let out = VitalsEngine.baselines(values: values, nowMs: nowMs, windowsDays: cfg.baselineWindowsDays, cfg)
            let windows = cfg.baselineWindowsDays.map { w -> (label: String, value: Double?) in
                let key = w.map { "\(Int($0))d" } ?? "all"
                let label = w.map { String(localized: "\(Int($0)) d", comment: "Baseline window in days") } ?? String(localized: "All")
                return (label, out[key]["median"].double)
            }
            return (metric, windows)
        }
    }
}

/// A saved scan: the same §19 layout plus context and device, and delete.
struct ScanDetailView: View {
    let scanID: String
    @Environment(\.dismiss) private var dismiss
    @State private var record: VitalScanRecord?
    @State private var report: VitalScanReport?
    @State private var confirmDelete = false
    @State private var showReference = false
    @State private var hasComparePair = false

    private var store: VitalsStore { VitalsStore.shared }

    var body: some View {
        List {
            if let record, let report {
                Section {
                    LabeledContent("Type", value: record.mode == VitalsMode.finger.rawValue ? String(localized: "Finger scan") : String(localized: "Face scan"))
                    LabeledContent("Time", value: Date(timeIntervalSince1970: Double(record.startMs) / 1000).formatted(date: .abbreviated, time: .shortened))
                    LabeledContent("Context", value: contextTitle(record.context))
                    LabeledContent("Duration", value: Duration.seconds(Double(record.durationMs) / 1000).formatted(.units(allowed: [.minutes, .seconds])))
                }
                if hasComparePair, let sessionID = record.sessionID {
                    Section {
                        NavigationLink {
                            VitalsCompareView(sessionID: sessionID)
                        } label: {
                            Label("Compare", systemImage: "rectangle.on.rectangle")
                        }
                        .accessibilityIdentifier("vitals.detail.compare")
                    } footer: {
                        Text("This scan is part of a finger and face comparison.")
                    }
                }
                VitalsReportSections(report: report)
                VitalsReferenceSection(record: record) { showReference = true }
                Section {
                    LabeledContent("Device", value: record.deviceModel)
                    LabeledContent("Algorithm version", value: "\(record.algoVersion)")
                    Button("Delete scan", role: .destructive) { confirmDelete = true }
                        .accessibilityIdentifier("vitals.detail.delete")
                } footer: {
                    Text("Camera scans stay in Ayuvo. They are never written to Apple Health.")
                }
            } else {
                ProgressView().frame(maxWidth: .infinity)
            }
        }
        .listStyle(.insetGrouped)
        .navigationTitle("Scan")
        .navigationBarTitleDisplayMode(.inline)
        .task {
            guard let r = await store.scan(id: scanID) else { return }
            record = r
            report = VitalScanReport(record: r, signals: await store.signals(scanID: scanID))
            if let sessionID = r.sessionID {
                hasComparePair = VitalsCompare.pair(await store.scans(sessionID: sessionID)) != nil
            }
        }
        .sheet(isPresented: $showReference) {
            if let record {
                VitalsReferenceSheet(record: record) { updated in
                    if let updated { self.record = updated }
                }
            }
        }
        .confirmationDialog("Delete this scan?", isPresented: $confirmDelete, titleVisibility: .visible) {
            Button("Delete", role: .destructive) {
                Task {
                    await store.delete(id: scanID)
                    dismiss()
                }
            }
        } message: {
            Text("Its results and saved signals are removed from this device.")
        }
    }

    private func contextTitle(_ c: String) -> String {
        switch c {
        case "after_activity": String(localized: "After activity")
        case "other": String(localized: "Other")
        default: String(localized: "Resting")
        }
    }
}
