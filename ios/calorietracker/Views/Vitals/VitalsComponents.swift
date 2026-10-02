import Charts
import SwiftUI

/// What the results and scan detail screens show (PPG.md §19 layout), from a fresh engine result or a saved scan.
struct VitalScanReport {
    let mode: VitalsMode
    let rejectReason: String?
    let quality: RJ
    let metrics: RJ
    let indicators: RJ
    let fs: Double
    let processed: [Double]
    let mask: [Bool]
    /// Beat times (ms, frame clock) of the processed signal.
    let beatsMs: [Double]

    /// The metric rows in §19 order.
    static let metricOrder = ["heart_rate", "hrv_rmssd", "hrv_sdnn", "ibi_mean", "respiratory_rate", "recovery_indicator",
                              "stress_indicator", "spo2", "blood_pressure"]

    init(result: VitalScanResult, mode: VitalsMode, indicators: RJ) {
        self.mode = mode
        rejectReason = result.rejectReason
        quality = result.quality
        metrics = result.metrics
        self.indicators = indicators
        let s = result.signals
        fs = s["fs"].double ?? VitalsConfig.shared.signal.fs
        processed = (s["processed"].array ?? []).compactMap(\.double)
        mask = (s["mask"].array ?? []).map { $0.bool == true }
        beatsMs = (s["peaks_ms"].array ?? []).compactMap(\.double)
    }

    init(record: VitalScanRecord, signals: [VitalSignal]) {
        mode = VitalsMode(rawValue: record.mode) ?? .finger
        rejectReason = record.rejectReason
        quality = (try? VitalsJSON.parse(record.qualityJSON)) ?? .obj([:])
        let results = (try? VitalsJSON.parse(record.resultsJSON)) ?? .obj([:])
        metrics = results["metrics"]
        indicators = results["indicators"]
        func column(_ kind: String) -> (rate: Double?, values: [Double]) {
            guard let s = signals.first(where: { $0.kind == kind }), let table = try? VitalSignalCodec.decode(s) else { return (nil, []) }
            return (s.sampleRate, table.rows.compactMap { $0.first.map(Double.init) })
        }
        let p = column("processed")
        fs = p.rate ?? VitalsConfig.shared.signal.fs
        processed = p.values
        mask = column("mask").values.map { $0 > 0.5 }
        beatsMs = column("beats").values
    }

    func envelope(_ id: String) -> RJ {
        if id == "recovery_indicator" || id == "stress_indicator" { return indicators[id] }
        return metrics[id]
    }

    var score: Double? { quality["score"].double }
    var grade: String? { quality["grade"].string }
}

/// "Good · 86" style quality label.
func vitalsQualityText(score: Double?, grade: String?) -> String {
    guard let grade else { return "—" }
    guard let score else { return VitalsText.grade(grade) }
    return "\(VitalsText.grade(grade)) · \(Int(score.rounded()))"
}

func vitalsGradeColor(_ grade: String?) -> Color {
    switch grade {
    case "excellent", "good": AyuvoPalette.nutrition
    case "fair": AyuvoPalette.activity
    default: AyuvoPalette.heart
    }
}

/// Value text of one metric envelope; nil when unavailable.
func vitalsValueText(_ id: String, _ env: RJ) -> String? {
    guard env["status"].string == "valid", let v = env["value"].double else { return nil }
    switch id {
    case "blood_pressure":
        let dia = env["diastolic"].double.map { "\(Int($0.rounded()))" } ?? "—"
        return "\(Int(v.rounded()))/\(dia)"
    case "recovery_indicator", "stress_indicator", "spo2":
        return "\(Int(v.rounded()))"
    default:
        return v.formatted(.number.precision(.fractionLength(0...1)))
    }
}

func vitalsUnitText(_ unit: String?) -> String {
    switch unit {
    case "score", "ratio", nil: ""
    case "/min": String(localized: "/min", comment: "Breaths per minute unit")
    default: unit ?? ""
    }
}

func vitalsConfidenceText(_ label: String?) -> String? {
    switch label {
    case "high": String(localized: "High confidence")
    case "medium": String(localized: "Medium confidence")
    case "low": String(localized: "Low confidence")
    default: nil
    }
}

struct VitalsClassificationBadge: View {
    let classification: String

    var body: some View {
        Text(VitalsText.classification(classification))
            .font(.system(.caption2, design: .rounded, weight: .semibold))
            .foregroundStyle(tint)
            .padding(.horizontal, 7)
            .padding(.vertical, 2)
            .background(tint.opacity(0.14), in: Capsule())
            .accessibilityLabel(Text(VitalsText.classificationAbout(classification)))
    }

    private var tint: Color {
        switch classification {
        case "measured": AyuvoPalette.hydration
        case "calculated": AyuvoPalette.records
        case "estimated": AyuvoPalette.fasting
        case "experimental": AyuvoPalette.activity
        default: AyuvoPalette.heart
        }
    }
}

/// One metric row: value + unit (or "Unavailable" with its reason), classification badge and confidence.
struct VitalsMetricRow: View {
    let id: String
    let envelope: RJ

    var body: some View {
        let value = vitalsValueText(id, envelope)
        VStack(alignment: .leading, spacing: 4) {
            HStack(alignment: .firstTextBaseline) {
                Text(VitalsText.metricTitle(id))
                    .font(.system(.body, design: .rounded))
                Spacer(minLength: 8)
                if let value {
                    Text(value)
                        .font(.ayuvoNumber(.title3))
                    Text(vitalsUnitText(envelope["unit"].string))
                        .font(.system(.footnote, design: .rounded))
                        .foregroundStyle(.secondary)
                } else {
                    Text("Unavailable")
                        .font(.system(.subheadline, design: .rounded, weight: .medium))
                        .foregroundStyle(.secondary)
                }
            }
            HStack(spacing: 6) {
                if let c = envelope["classification"].string { VitalsClassificationBadge(classification: c) }
                if value != nil, let conf = vitalsConfidenceText(envelope["confidence_label"].string) {
                    Text(conf)
                        .font(.system(.caption, design: .rounded))
                        .foregroundStyle(.secondary)
                }
                if value != nil, let band = envelope["band"].string {
                    Text(VitalsText.band(band))
                        .font(.system(.caption, design: .rounded))
                        .foregroundStyle(.secondary)
                }
            }
            if value == nil, let reason = envelope["reason"].string {
                Text(VitalsText.reason(reason))
                    .font(.system(.caption, design: .rounded))
                    .foregroundStyle(.secondary)
            }
        }
        .padding(.vertical, 2)
        .accessibilityElement(children: .combine)
        .accessibilityIdentifier("vitals.metric.\(id)")
    }
}

/// The §19 sections: signal quality (with expandable components), the metrics and the processed waveform.
struct VitalsReportSections: View {
    let report: VitalScanReport
    @State private var showComponents = false

    var body: some View {
        Section {
            HStack {
                Label {
                    Text("Signal quality")
                } icon: {
                    Image(systemName: "waveform.path.ecg")
                        .foregroundStyle(vitalsGradeColor(report.grade))
                }
                Spacer()
                Text(vitalsQualityText(score: report.score, grade: report.grade))
                    .font(.system(.headline, design: .rounded))
                    .foregroundStyle(vitalsGradeColor(report.grade))
                    .accessibilityIdentifier("vitals.quality")
            }
            if let reject = report.rejectReason {
                Label(VitalsText.reason(reject), systemImage: "exclamationmark.triangle.fill")
                    .font(.system(.subheadline, design: .rounded))
                    .foregroundStyle(AyuvoPalette.activity)
                    .accessibilityIdentifier("vitals.rejectReason")
            }
            DisclosureGroup(isExpanded: $showComponents) {
                ForEach(componentKeys, id: \.self) { key in
                    LabeledContent(componentTitle(key)) {
                        Text((report.quality["components"][key].double ?? 0).formatted(.percent.precision(.fractionLength(0))))
                            .monospacedDigit()
                    }
                    .font(.system(.subheadline, design: .rounded))
                }
            } label: {
                Text("Quality components")
                    .font(.system(.subheadline, design: .rounded))
            }
            .accessibilityIdentifier("vitals.qualityComponents")
        } header: {
            Text("Quality")
        }

        Section {
            ForEach(VitalScanReport.metricOrder, id: \.self) { id in
                VitalsMetricRow(id: id, envelope: report.envelope(id))
            }
        } header: {
            Text("Results")
        } footer: {
            Text(VitalsText.disclaimer)
        }

        if !report.processed.isEmpty {
            Section {
                VitalsWaveformChart(processed: report.processed, mask: report.mask, beatsMs: report.beatsMs, fs: report.fs)
            } header: {
                Text("Pulse waveform")
            } footer: {
                Text("The processed pulse signal with detected beats. Swipe to see the whole recording.")
            }
        }
    }

    private var componentKeys: [String] {
        let order = ["snr", "template", "ibi", "agreement", "motion", "signal"]
        let have = Set(report.quality["components"].object?.keys.map { $0 } ?? [])
        return order.filter(have.contains)
    }

    private func componentTitle(_ key: String) -> String {
        switch key {
        case "snr": String(localized: "Pulse strength (SNR)")
        case "template": String(localized: "Beat shape consistency")
        case "ibi": String(localized: "Clean beat intervals")
        case "agreement": String(localized: "Rate agreement")
        case "motion": String(localized: "Stillness")
        case "signal": String(localized: "Finger or face detected")
        default: key
        }
    }
}

/// Processed waveform with beat markers, scrollable over the whole recording (10 s visible).
struct VitalsWaveformChart: View {
    struct Point: Identifiable {
        let id: Int
        let t: Double
        let x: Double
    }

    let processed: [Double]
    let mask: [Bool]
    let beatsMs: [Double]
    let fs: Double

    private var points: [Point] {
        processed.enumerated().map { Point(id: $0.offset, t: Double($0.offset) / fs, x: $0.element) }
    }

    private var beats: [Point] {
        beatsMs.enumerated().compactMap { i, ms in
            let idx = Int((ms / 1000 * fs).rounded())
            guard processed.indices.contains(idx) else { return nil }
            return Point(id: i, t: ms / 1000, x: processed[idx])
        }
    }

    var body: some View {
        let duration = Double(processed.count) / fs
        Chart {
            ForEach(points) { p in
                LineMark(x: .value("Time", p.t), y: .value("Signal", p.x))
                    .foregroundStyle(AyuvoPalette.heart)
                    .lineStyle(StrokeStyle(lineWidth: 1.5))
            }
            ForEach(beats) { b in
                PointMark(x: .value("Time", b.t), y: .value("Signal", b.x))
                    .symbolSize(22)
                    .foregroundStyle(Color.primary.opacity(0.75))
            }
        }
        .chartYAxis(.hidden)
        .chartXAxis {
            AxisMarks(values: .stride(by: 5)) { value in
                AxisGridLine().foregroundStyle(Color.primary.opacity(ChartAxisStyle.gridOpacity))
                AxisValueLabel {
                    if let s = value.as(Double.self) { Text("\(Int(s)) s") }
                }
            }
        }
        .chartXScale(domain: 0...max(duration, 1))
        .chartScrollableAxes(.horizontal)
        .chartXVisibleDomain(length: min(10, max(duration, 1)))
        .chartScrollPosition(initialX: max(0, duration - 10))
        .frame(height: 160)
        .accessibilityIdentifier("vitals.waveform")
        .accessibilityLabel(Text("Pulse waveform"))
    }
}

/// Live waveform (last few seconds), no axes.
struct VitalsLiveWaveform: View {
    let values: [Double]
    var tint: Color = AyuvoPalette.heart

    var body: some View {
        Chart {
            ForEach(Array(values.enumerated()), id: \.offset) { i, v in
                LineMark(x: .value("i", i), y: .value("v", v))
                    .interpolationMethod(.catmullRom)
                    .foregroundStyle(tint)
                    .lineStyle(StrokeStyle(lineWidth: 2.5, lineCap: .round))
            }
        }
        .chartXAxis(.hidden)
        .chartYAxis(.hidden)
        .chartXScale(domain: 0...max(values.count - 1, 1))
        .accessibilityIdentifier("vitals.liveWaveform")
        .accessibilityLabel(Text("Live pulse waveform"))
    }
}
