import SwiftUI

// Cards for the health analytics results (docs/health-analytics.md). Every card shows the value, the person's own
// baseline, the trend, a status / confidence chip and where the number comes from. Values are rounded for display;
// no card names a condition.

// MARK: - Formatting

enum AnalyticsFormat {
    static let missing = InsightsText.missing

    /// Display value of an analytics metric.
    static func value(_ v: Double?, metric: String) -> String {
        guard let v else { return missing }
        switch metric {
        case "sleep_duration", "sleep": return InsightsDisplayFormat.duration(minutes: v)
        case "sleep_efficiency": return "\(Int(v.rounded()))%"
        case "wrist_temperature": return String(localized: "\(v.formatted(.number.precision(.fractionLength(2)))) °C")
        case "hrr1": return String(localized: "\(Int(v.rounded())) bpm")
        case "ayuvo_rmssd": return InsightsText.value(v, metric: "hrv")
        default: return InsightsText.value(v, metric: metric)
        }
    }

    /// "+1.2 SD" robust z (SD = scaled MAD).
    static func z(_ z: Double?) -> String? {
        guard let z else { return nil }
        return String(localized: "\(InsightsDisplayFormat.signed(z, 1)) SD vs your usual")
    }

    static func percent(_ confidence: Double?) -> String? {
        guard let confidence else { return nil }
        return String(localized: "Confidence \(Int((confidence * 100).rounded()))%")
    }

    /// Short text of a status that is not a usable value.
    static func status(_ status: String?) -> String? {
        switch status {
        case "INSUFFICIENT_HISTORY": String(localized: "Learning your baseline")
        case "INSUFFICIENT_DATA": String(localized: "Not enough data")
        case "NO_DATA": String(localized: "No data")
        case "INVALID_INPUT": String(localized: "Data could not be used")
        case "LOW_CONFIDENCE": String(localized: "Low confidence")
        case "EXPERIMENTAL": String(localized: "Experimental")
        case "RESEARCH_ONLY": String(localized: "Research only")
        default: nil
        }
    }

    static func trendLabel(_ label: String?) -> (String, Color)? {
        switch label {
        case "IMPROVING": ("↑ " + String(localized: "Improving"), AyuvoPalette.nutrition)
        case "DECLINING": ("↓ " + String(localized: "Declining"), AyuvoPalette.heart)
        case "STABLE": ("→ " + String(localized: "Stable"), AyuvoPalette.other)
        case "CHANGING": ("↕ " + String(localized: "Changing"), AyuvoPalette.activity)
        case "UNUSUAL": ("! " + String(localized: "Unusual today"), AyuvoPalette.activity)
        default: nil
        }
    }

    static func minutes(_ v: Double?) -> String { v.map { InsightsDisplayFormat.duration(minutes: $0) } ?? missing }
    static func kcal(_ v: Double?) -> String { v.map { String(localized: "\(Int($0.rounded()).formatted()) kcal") } ?? missing }
}

// MARK: - Pieces

/// Status / confidence capsule: "Confidence 87%", or why there is no value.
struct AnalyticsStatusChip: View {
    let status: String?
    let confidence: Double?

    var body: some View {
        if let text = AnalyticsFormat.status(status), status != "LOW_CONFIDENCE" {
            InsightsChip(text: text, tint: AyuvoPalette.other)
        } else if let text = AnalyticsFormat.percent(confidence) {
            InsightsChip(text: text, tint: (confidence ?? 0) >= 0.5 ? AyuvoPalette.insights : AyuvoPalette.activity)
        }
    }
}

/// "Calculated · from Apple Health" footnote.
struct AnalyticsSourceLine: View {
    let classification: String?
    var source: String?

    var body: some View {
        let parts = [classification.map(AnalyticsText.classificationLabel), source].compactMap { $0 }
        if !parts.isEmpty {
            Text(parts.joined(separator: " · "))
                .font(.system(.caption2, design: .rounded))
                .foregroundStyle(.secondary)
        }
    }
}

/// One "label … value" line with optional detail.
struct AnalyticsLine: View {
    let title: String
    let value: String
    var detail: String?

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            HStack(alignment: .firstTextBaseline) {
                Text(title).font(.system(.subheadline, design: .rounded))
                Spacer(minLength: 8)
                Text(value).font(.ayuvoNumber(.subheadline))
            }
            if let detail {
                Text(detail)
                    .font(.system(.caption, design: .rounded))
                    .foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
        .accessibilityElement(children: .combine)
    }
}

/// Card shell with a title, the status chip and content.
struct AnalyticsCard<Content: View>: View {
    let title: String
    let systemImage: String
    var status: String?
    var confidence: Double?
    let id: String
    @ViewBuilder let content: () -> Content

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack {
                Label(title, systemImage: systemImage)
                    .font(.system(.headline, design: .rounded))
                Spacer(minLength: 8)
                AnalyticsStatusChip(status: status, confidence: confidence)
            }
            content()
        }
        .ayuvoCard()
        .accessibilityIdentifier("analytics.card.\(id)")
    }
}

// MARK: - Signals

struct AnalyticsSignalsCard: View {
    let anomaly: AJ

    var body: some View {
        AnalyticsCard(title: String(localized: "Signals"), systemImage: "waveform.path.ecg", status: anomaly["status"].string,
                      confidence: anomaly["confidence"].double, id: "signals") {
            if let state = anomaly["state"].string {
                Text(AnalyticsText.anomalyState(state))
                    .font(.system(.subheadline, design: .rounded, weight: .medium))
                    .foregroundStyle(state == "NORMAL" ? Color.primary : AyuvoPalette.activity)
                    .fixedSize(horizontal: false, vertical: true)
                ForEach(Array(anomaly["signals"].array.filter { $0["flagged"].bool == true }.enumerated()), id: \.offset) { _, s in
                    let id = s["id"].string ?? ""
                    AnalyticsLine(title: AnalyticsText.metricLabel(id == "sleep_duration" ? "sleep_duration" : id),
                                  value: AnalyticsFormat.value(s["value"].double, metric: id),
                                  detail: [String(localized: "Usual \(AnalyticsFormat.value(s["median"].double, metric: id))"),
                                           AnalyticsFormat.z(s["z"].double)].compactMap { $0 }.joined(separator: " · "))
                }
                if anomaly["persistent"].bool == true {
                    Label(AnalyticsText.persistentNote, systemImage: "stethoscope")
                        .font(.system(.caption, design: .rounded))
                        .foregroundStyle(.secondary)
                        .fixedSize(horizontal: false, vertical: true)
                }
            } else {
                Text("Signals appear once at least two of your metrics have two weeks of history.")
                    .font(.system(.subheadline, design: .rounded))
                    .foregroundStyle(.secondary)
            }
            AnalyticsSourceLine(classification: anomaly["classification"].string)
        }
    }
}

// MARK: - HRV

struct AnalyticsHRVCard: View {
    let hrv: AJ
    /// sdnn (Apple), rmssd (Health Connect) or ayuvo_rmssd (beat-to-beat).
    let kind: String

    private var title: String {
        switch kind {
        case "ayuvo_rmssd": String(localized: "HRV (Ayuvo RMSSD)")
        case "rmssd": String(localized: "HRV (RMSSD)")
        default: String(localized: "HRV (SDNN)")
        }
    }

    var body: some View {
        let b = hrv["baseline"]
        AnalyticsCard(title: title, systemImage: "waveform.path", status: b["status"].string, confidence: b["confidence"].double,
                      id: "hrv.\(kind)") {
            AnalyticsLine(title: String(localized: "Today"), value: AnalyticsFormat.value(b["value"].double, metric: "hrv"),
                          detail: AnalyticsFormat.z(b["z"].double))
            AnalyticsLine(title: String(localized: "Personal baseline (28 days)"),
                          value: AnalyticsFormat.value(b["median"].double, metric: "hrv"),
                          detail: b["p10"].double.map { _ in
                              String(localized: "Usual range \(AnalyticsFormat.value(b["p10"].double, metric: "hrv"))–\(AnalyticsFormat.value(b["p90"].double, metric: "hrv"))")
                          })
            if let chip = AnalyticsFormat.trendLabel(hrv["trend"]["label"].string) {
                HStack {
                    Text("Trend").font(.system(.subheadline, design: .rounded))
                    Spacer()
                    InsightsChip(text: chip.0, tint: chip.1)
                }
            }
            if let cv = hrv["cv_ln_7d"].double {
                AnalyticsLine(title: String(localized: "7-day stability"), value: "\(cv.formatted(.number.precision(.fractionLength(1))))%",
                              detail: String(localized: "Day-to-day variation of ln HRV; lower is steadier."))
            }
            AnalyticsSourceLine(classification: kind == "ayuvo_rmssd" ? "SCIENTIFIC_DERIVED" : "PROVIDER_DERIVED",
                                source: kind == "ayuvo_rmssd" ? String(localized: "From Apple Watch beat-to-beat data")
                                    : String(localized: "From Apple Health"))
        }
    }
}

// MARK: - Sleep

struct AnalyticsSleepCard: View {
    let sleep: AJ

    var body: some View {
        AnalyticsCard(title: String(localized: "Sleep"), systemImage: "bed.double.fill", status: sleep["status"].string,
                      confidence: sleep["confidence"].double, id: "sleep") {
            AnalyticsLine(title: String(localized: "Last night"), value: AnalyticsFormat.minutes(sleep["asleep_min"].double),
                          detail: sleep["efficiency"].double.map { String(localized: "Efficiency \(Int($0.rounded()))% of time in bed") })
            AnalyticsLine(title: String(localized: "Your sleep need"), value: AnalyticsFormat.minutes(sleep["need_min"].double),
                          detail: sleep["need_source"].string == "personal"
                              ? String(localized: "Your usual sleep over the last 60 nights, kept within 7–9 h")
                              : String(localized: "Default 8 h until 14 nights are recorded"))
            AnalyticsLine(title: String(localized: "Sleep debt (14 nights)"), value: AnalyticsFormat.minutes(sleep["debt_min"].double),
                          detail: String(localized: "Shortfall against your need on \(sleep["debt_nights"].int ?? 0) recorded nights"))
            if let bed = sleep["bedtime_sd"].double, let wake = sleep["wake_sd"].double {
                AnalyticsLine(title: String(localized: "Timing variability"),
                              value: String(localized: "± \(Int(bed.rounded())) / \(Int(wake.rounded())) min"),
                              detail: String(localized: "Bedtime / wake time spread over 14 nights"))
            }
            if let nap = sleep["nap_min"].double, nap > 0 {
                AnalyticsLine(title: String(localized: "Naps"), value: AnalyticsFormat.minutes(nap),
                              detail: String(localized: "Counted separately, never added to the night"))
            }
            AnalyticsSourceLine(classification: "SCIENTIFIC_DERIVED", source: String(localized: "From Apple Health sleep"))
        }
    }
}

// MARK: - Training load

struct AnalyticsLoadCard: View {
    let load: AJ

    var body: some View {
        let pm = load["primary_method"].string
        let m = load["methods"][pm ?? ""]
        AnalyticsCard(title: String(localized: "Training load"), systemImage: "figure.run", status: load["status"].string,
                      confidence: load["confidence"].double, id: "load") {
            if let state = load["state"].string {
                Text(AnalyticsText.loadState(state))
                    .font(.system(.title3, design: .rounded, weight: .bold))
                    .foregroundStyle(state == "LOAD_SPIKE" || state == "LOAD_HIGH" ? AyuvoPalette.activity : AyuvoPalette.insights)
                Text("Compared with your own last 90 days. This describes your training, not a risk of injury.")
                    .font(.system(.caption, design: .rounded))
                    .foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
            if pm != nil {
                AnalyticsLine(title: String(localized: "Method"), value: Self.method(pm))
                AnalyticsLine(title: String(localized: "Acute (7-day)"), value: Self.number(m["acute"].double))
                AnalyticsLine(title: String(localized: "Chronic (28-day)"), value: Self.number(m["chronic"].double))
                AnalyticsLine(title: String(localized: "Acute ÷ chronic"), value: m["ratio"].double.map { $0.formatted(.number.precision(.fractionLength(2))) } ?? AnalyticsFormat.missing)
            } else {
                Text("Training load appears after your first logged or synced workout.")
                    .font(.system(.subheadline, design: .rounded))
                    .foregroundStyle(.secondary)
            }
            AnalyticsSourceLine(classification: "SCIENTIFIC_DERIVED")
        }
    }

    static func number(_ v: Double?) -> String { v.map { InsightsDisplayFormat.number(($0 * 10).rounded() / 10) } ?? AnalyticsFormat.missing }

    static func method(_ m: String?) -> String {
        switch m {
        case "trimp": String(localized: "Heart-rate TRIMP")
        case "rpe_load": String(localized: "Effort × minutes (session RPE)")
        default: String(localized: "Minutes")
        }
    }
}

// MARK: - Heart rate recovery

struct AnalyticsHRRCard: View {
    let row: AnalyticsTrendRow

    var body: some View {
        let b = row.baseline
        AnalyticsCard(title: String(localized: "Heart rate recovery"), systemImage: "heart.circle", status: b["status"].string,
                      confidence: b["confidence"].double, id: "hrr") {
            AnalyticsLine(title: String(localized: "Latest (1 minute)"), value: AnalyticsFormat.value(b["value"].double, metric: "hrr1"),
                          detail: AnalyticsFormat.z(b["z"].double))
            AnalyticsLine(title: String(localized: "Personal baseline (90 days)"), value: AnalyticsFormat.value(b["median"].double, metric: "hrr1"))
            if let chip = AnalyticsFormat.trendLabel(row.trend["label"].string) {
                HStack {
                    Text("Trend").font(.system(.subheadline, design: .rounded))
                    Spacer()
                    InsightsChip(text: chip.0, tint: chip.1)
                }
            }
            AnalyticsSourceLine(classification: "PROVIDER_DERIVED", source: String(localized: "Apple Watch cardio recovery"))
        }
    }
}

// MARK: - Activity, energy, fitness

struct AnalyticsActivityCard: View {
    let met: AJ

    var body: some View {
        let equivalent = met["moderate_equivalent_min"].double ?? 0
        AnalyticsCard(title: String(localized: "Activity intensity (7 days)"), systemImage: "flame", id: "met") {
            ProgressView(value: min(equivalent, 150), total: 150).tint(AyuvoPalette.activity)
            AnalyticsLine(title: String(localized: "Moderate-equivalent minutes"), value: "\(Int(equivalent.rounded())) / 150",
                          detail: String(localized: "Moderate minutes plus twice the vigorous minutes, compared with the WHO weekly guideline"))
            AnalyticsLine(title: String(localized: "Moderate"), value: AnalyticsFormat.minutes(met["moderate_min"].double))
            AnalyticsLine(title: String(localized: "Vigorous"), value: AnalyticsFormat.minutes(met["vigorous_min"].double))
            if let unknown = met["unknown_min"].double, unknown > 0 {
                AnalyticsLine(title: String(localized: "Not classified"), value: AnalyticsFormat.minutes(unknown),
                              detail: String(localized: "Activity types without a Compendium value get no intensity"))
            }
            AnalyticsSourceLine(classification: "SCIENTIFIC_DERIVED", source: String(localized: "2024 Adult Compendium of Physical Activities"))
        }
    }
}

struct AnalyticsEnergyCard: View {
    let energy: AJ

    var body: some View {
        AnalyticsCard(title: String(localized: "Energy expenditure (yesterday)"), systemImage: "bolt.fill", status: energy["status"].string,
                      confidence: energy["confidence"].double, id: "energy") {
            AnalyticsLine(title: String(localized: "Estimated total"), value: AnalyticsFormat.kcal(energy["estimated_daily_expenditure"].double),
                          detail: String(localized: "(Resting + active) plus 10% for digestion. An estimate, not a measurement."))
            AnalyticsLine(title: String(localized: "Resting"), value: AnalyticsFormat.kcal(energy["resting_kcal"].double),
                          detail: energy["resting_source"].string == "provider"
                              ? String(localized: "From Apple Health basal energy")
                              : String(localized: "Mifflin–St Jeor estimate (basal energy missing or partial)"))
            AnalyticsLine(title: String(localized: "Active"), value: AnalyticsFormat.kcal(energy["active_kcal"].double),
                          detail: (energy["ayuvo_extra_kcal"].double ?? 0) > 0
                              ? String(localized: "Includes Ayuvo workouts no watch workout covered") : nil)
            AnalyticsSourceLine(classification: "SCIENTIFIC_DERIVED")
        }
    }
}

struct AnalyticsVO2Card: View {
    let vo2: AJ

    var body: some View {
        AnalyticsCard(title: String(localized: "VO2 max trend"), systemImage: "lungs.fill", id: "vo2") {
            ForEach(["provider", "uth"], id: \.self) { kind in
                let k = vo2["kinds"][kind]
                if (k["n"].int ?? 0) > 0 {
                    AnalyticsLine(title: kind == "provider" ? String(localized: "Apple Watch estimate") : String(localized: "Heart-rate ratio estimate"),
                                  value: InsightsText.value(k["latest"].double, metric: "vo2_max"),
                                  detail: [k["change"].double.map { String(localized: "\(InsightsDisplayFormat.signed($0, 1)) vs 2+ months ago") },
                                           k["slope_per_30d"].double.map { String(localized: "\(InsightsDisplayFormat.signed($0, 1)) per 30 days") },
                                           AnalyticsText.classificationLabel(k["classification"].string ?? "")]
                                      .compactMap { $0 }.joined(separator: " · "))
                }
            }
            Text("Each source is tracked separately and never combined.")
                .font(.system(.caption2, design: .rounded))
                .foregroundStyle(.secondary)
        }
    }
}

// MARK: - Forecast

struct AnalyticsForecastCard: View {
    let target: String
    let forecast: AJ

    var body: some View {
        let metric = target == "hrv" ? "hrv" : "resting_heart_rate"
        AnalyticsCard(title: target == "hrv" ? String(localized: "Tomorrow's HRV (forecast)") : String(localized: "Tomorrow's resting heart rate (forecast)"),
                      systemImage: "sparkles", id: "forecast.\(target)") {
            AnalyticsLine(title: String(localized: "Forecast"), value: InsightsText.value(forecast["prediction"].double, metric: metric),
                          detail: String(localized: "Likely range \(InsightsText.value(forecast["interval_low"].double, metric: metric))–\(InsightsText.value(forecast["interval_high"].double, metric: metric))"))
            AnalyticsLine(title: String(localized: "Error on your recent days"),
                          value: InsightsText.value(forecast["metrics"]["mae"].double, metric: metric),
                          detail: String(localized: "Simple guesses: \(InsightsText.value(forecast["baselines"]["persistence_mae"].double, metric: metric)) (same as today), \(InsightsText.value(forecast["baselines"]["median28_mae"].double, metric: metric)) (28-day median)"))
            Text("A small model trained only on your own data. Forecasts can be wrong; it is shown only because it beat simple guesses on your most recent days.")
                .font(.system(.caption, design: .rounded))
                .foregroundStyle(.secondary)
                .fixedSize(horizontal: false, vertical: true)
            AnalyticsSourceLine(classification: "ML_PREDICTED")
        }
    }
}

// MARK: - Trends

struct AnalyticsTrendCard: View {
    let row: AnalyticsTrendRow
    let today: String

    var body: some View {
        let b = row.baseline, t = row.trend
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                Text(AnalyticsText.metricLabel(row.id)).font(.system(.headline, design: .rounded))
                Spacer()
                if let chip = AnalyticsFormat.trendLabel(t["label"].string) { InsightsChip(text: chip.0, tint: chip.1) }
            }
            if b["median"].double != nil {
                HStack(alignment: .firstTextBaseline) {
                    Text(String(localized: "Baseline \(AnalyticsFormat.value(b["median"].double, metric: row.id)) · usual \(AnalyticsFormat.value(b["p10"].double, metric: row.id))–\(AnalyticsFormat.value(b["p90"].double, metric: row.id))"))
                        .font(.system(.subheadline, design: .rounded))
                        .foregroundStyle(.secondary)
                    Spacer(minLength: 8)
                    Text("\(row.day == today ? String(localized: "Today") : String(localized: "Yesterday")) \(AnalyticsFormat.value(b["value"].double, metric: row.id))")
                        .font(.ayuvoNumber(.subheadline))
                }
                Text([AnalyticsFormat.z(b["z"].double),
                      // A deviation around 0 (sleeping temperature) has no meaningful % change.
                      row.id == "wrist_temperature" ? nil
                          : t["pct_per_week"].double.map { String(localized: "\(InsightsDisplayFormat.signed($0, 1))% per week") },
                      String(localized: "\(t["window_days"].int ?? 28) days · \(t["sample_count"].int ?? 0) readings"),
                      AnalyticsFormat.percent(b["confidence"].double)].compactMap { $0 }.joined(separator: " · "))
                    .font(.system(.caption, design: .rounded))
                    .foregroundStyle(.secondary)
            } else {
                Text("Learning your baseline (\(b["n"].int ?? 0)/\(b["needed"].int ?? 14) days)")
                    .font(.system(.subheadline, design: .rounded))
                    .foregroundStyle(.secondary)
            }
        }
        .ayuvoCard()
        .accessibilityElement(children: .combine)
        .accessibilityIdentifier("trends.analytics.\(row.id)")
    }
}

// MARK: - Patterns v2

struct AnalyticsCorrelationsCard: View {
    let correlation: AJ
    let responses: [AJ]

    private var surfaced: [AJ] { correlation["results"].array.filter { $0["surfaced"].bool == true } }

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            AyuvoSectionHeader("Associations in your data")
            if surfaced.isEmpty {
                Text("No clear associations yet. Each pair needs at least 21 days, and a result must hold after correcting for the number of checks.")
                    .font(.system(.subheadline, design: .rounded))
                    .foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
            ForEach(Array(surfaced.enumerated()), id: \.offset) { _, r in
                VStack(alignment: .leading, spacing: 4) {
                    Text(AnalyticsText.correlation(r) ?? "")
                        .font(.system(.subheadline, design: .rounded))
                        .fixedSize(horizontal: false, vertical: true)
                    Text(String(localized: "ρ \(Self.f2(r["spearman_rho"].double)) (95% CI \(Self.f2(r["ci_low"].double)) to \(Self.f2(r["ci_high"].double))) · \(r["n"].int ?? 0) days · q \(Self.f2(r["q"].double))"))
                        .font(.system(.caption, design: .rounded))
                        .foregroundStyle(.secondary)
                    if let e = effect(r) {
                        Text(e).font(.system(.caption, design: .rounded)).foregroundStyle(.secondary)
                    }
                }
                .accessibilityElement(children: .combine)
            }
            Text("Associations, not causes. Checked over the last 120 days at the same day and 1–3 days later.")
                .font(.system(.caption2, design: .rounded))
                .foregroundStyle(.secondary)
        }
        .ayuvoCard()
        .accessibilityIdentifier("patterns.correlations")
    }

    static func f2(_ v: Double?) -> String { v.map { $0.formatted(.number.precision(.fractionLength(2))) } ?? InsightsText.missing }

    private func effect(_ r: AJ) -> String? {
        guard let resp = responses.first(where: { $0["id"].string == r["id"].string && $0["lag"].int == r["lag"].int }),
              resp["status"].string == "VALID", let e = resp["effect"].double else { return nil }
        return String(localized: "Typical change: \(Self.f2(e)) per unit (95% CI \(Self.f2(resp["ci_low"].double)) to \(Self.f2(resp["ci_high"].double)))")
    }
}
