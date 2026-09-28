import SwiftUI

/// Sections under a nutrient chart, shared by the app's `nutrient:<key>` charts (docs/nutrients.md §5) and the
/// health nutrition types (`dietary_*`, §5a): the data-source section, About (summary, the reference for the
/// user's age and sex, scope notes, sources) and the Learn more link.
struct NutrientDetailSections: View {
    /// Where the chart's values come from, which decides the first section.
    enum Context {
        /// `nutrient:<key>`: food entries plus taken supplement doses → Food vs Supplements for the shown period.
        /// `foodTracked: false` (the food log does not record the nutrient): no split, the chart note says the
        /// chart counts supplements only.
        case app(extras: NutrientSeriesExtras?, periodTitle: String, foodTracked: Bool)
        /// A health nutrition type: Apple Health values (no supplements from Ayuvo Medications), with a link to
        /// the app's own chart (`foodTracked` picks its subtitle).
        case health(appChart: MetricKey?, foodTracked: Bool)
    }

    let nutrientKey: String
    let lines: NutrientsReference.Lines
    /// Guide slug from `resolve_metric` (nil for sports supplements).
    let learnSlug: String?
    let context: Context

    private var reference: NutrientReferenceData.Nutrient? { NutrientsReference.byKey[nutrientKey] }
    private var title: String { NutrientCatalog.title(nutrientKey) }
    private var unit: String { NutrientCatalog.unit(nutrientKey) }

    var body: some View {
        switch context {
        case .app(let extras, let periodTitle, let foodTracked):
            if foodTracked {
                splitSection(extras: extras, periodTitle: periodTitle)
            }
        case .health(let appChart, let foodTracked):
            healthSourceSection(appChart: appChart, foodTracked: foodTracked)
        }
        aboutSection
        if let learnSlug {
            Section {
                Link(destination: AppLinks.nutrientURL(learnSlug)) {
                    Label(String(localized: "Learn more about \(title)"), systemImage: "book.pages")
                }
                .accessibilityIdentifier("metric.nutrient.learnMore")
            } footer: {
                Text("Opens the Ayuvo guide on the web, with sources.")
            }
        }
    }

    // MARK: Apple Health values

    private func healthSourceSection(appChart: MetricKey?, foodTracked: Bool) -> some View {
        Section {
            Label {
                Text("These values come from Apple Health. They don't include supplements you log in Ayuvo Medications.")
                    .font(.system(.subheadline, design: .rounded))
                    .foregroundStyle(.secondary)
            } icon: {
                Image(systemName: "heart.text.square")
                    .foregroundStyle(AyuvoPalette.nutrition)
            }
            .accessibilityIdentifier("metric.nutrient.healthNote")
            if let appChart {
                NavigationLink(value: MetricRoute.detail(appChart)) {
                    Label {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(String(localized: "Open Ayuvo \(title) chart"))
                            Text(foodTracked
                                 ? String(localized: "Logged in Ayuvo: food and supplements")
                                 : String(localized: "Supplements logged in Ayuvo Medications"))
                                .font(.system(.caption, design: .rounded))
                                .foregroundStyle(.secondary)
                        }
                    } icon: {
                        Image(systemName: "chart.bar.xaxis")
                    }
                }
                .accessibilityIdentifier("metric.nutrient.appChart")
            }
        } header: {
            Text("Data")
        } footer: {
            if appChart != nil {
                Text(foodTracked
                     ? String(localized: "The Ayuvo chart counts the food you log plus the supplement doses you mark as taken.")
                     : String(localized: "The food log doesn't record \(title), so the Ayuvo chart counts the supplement doses you mark as taken."))
            }
        }
    }

    // MARK: Food vs Supplements

    private func splitSection(extras: NutrientSeriesExtras?, periodTitle: String) -> some View {
        let food = extras?.food
        let supplements = extras?.supplements
        let total = (food ?? 0) + (supplements ?? 0)
        return Section {
            splitRow(String(localized: "Food"), systemImage: "fork.knife", value: food, total: total)
            splitRow(String(localized: "Supplements"), systemImage: "pills.fill", value: supplements, total: total)
            if food != nil, supplements != nil, total > 0 {
                GeometryReader { proxy in
                    HStack(spacing: 2) {
                        Capsule().fill(AyuvoPalette.nutrition)
                            .frame(width: max(4, proxy.size.width * CGFloat((food ?? 0) / total)))
                        Capsule().fill(AyuvoPalette.domain("medications"))
                    }
                }
                .frame(height: 8)
                .accessibilityHidden(true)
            }
        } header: {
            Text("Food vs Supplements")
        } footer: {
            Text("\(periodTitle). Supplements count only doses marked as taken. They never add calories and aren't written to Apple Health.")
        }
        .accessibilityIdentifier("metric.nutrient.split")
    }

    private func splitRow(_ label: String, systemImage: String, value: Double?, total: Double) -> some View {
        LabeledContent {
            HStack(spacing: 6) {
                Text(NutrientCatalog.text(value, key: nutrientKey))
                    .font(.system(.body, design: .rounded, weight: .semibold))
                if let value, total > 0 {
                    Text("\(Int((value / total * 100).rounded()))%")
                        .font(.system(.caption, design: .rounded))
                        .foregroundStyle(.secondary)
                }
            }
        } label: {
            Label(label, systemImage: systemImage)
        }
    }

    // MARK: About

    private var aboutSection: some View {
        Section("About") {
            VStack(alignment: .leading, spacing: 10) {
                Text(reference?.summary ?? String(localized: "A sports supplement you log with food or take as a supplement."))
                    .font(.system(.subheadline, design: .rounded))
                ForEach(referenceLinesText, id: \.self) { line in
                    Text(line)
                        .font(.system(.subheadline, design: .rounded, weight: .medium))
                }
                if let note = reference?.upperLimit?.note, lines.upperLimit != nil {
                    Text(note)
                        .font(.system(.footnote, design: .rounded))
                        .foregroundStyle(.secondary)
                }
                ForEach(reference?.notes ?? [], id: \.self) { note in
                    Text(note)
                        .font(.system(.footnote, design: .rounded))
                        .foregroundStyle(.secondary)
                }
                if reference == nil {
                    Text("No reference amount is set for this supplement. A line shows only when you set your own goal.")
                        .font(.system(.footnote, design: .rounded))
                        .foregroundStyle(.secondary)
                } else {
                    Text(NutrientReferenceData.shared.populationNote)
                        .font(.system(.footnote, design: .rounded))
                        .foregroundStyle(.secondary)
                    if !sourceTitles.isEmpty {
                        Text(String(localized: "Sources: \(sourceTitles.joined(separator: "; "))"))
                            .font(.system(.caption, design: .rounded))
                            .foregroundStyle(.secondary)
                    }
                }
                Text("Reference amounts describe intakes for healthy adults. They are not personal medical advice.")
                    .font(.system(.caption, design: .rounded))
                    .foregroundStyle(.secondary)
            }
            .padding(.vertical, 2)
            .accessibilityIdentifier("metric.about")
        }
    }

    private var sourceTitles: [String] {
        lines.sourceIDs.compactMap { id in
            NutrientReferenceData.shared.sources[id].map { "\($0.title) (\($0.publisher))" }
        }
    }

    private var bandText: String {
        lines.band == "71+" ? String(localized: "71 and over") : lines.band.replacingOccurrences(of: "-", with: "–")
    }

    private var groupText: String {
        switch lines.sex {
        case "male": String(localized: "men \(bandText)")
        case "female": String(localized: "women \(bandText)")
        default: String(localized: "adults \(bandText)")
        }
    }

    private func amount(_ value: Double) -> String { NutrientCatalog.text(value, key: nutrientKey) }

    private var referenceLinesText: [String] {
        var out: [String] = []
        if let recommended = lines.referenceRecommended {
            let kind = lines.recommendedKind ?? "RDA"
            out.append(String(localized: "Recommended for you: \(amount(recommended)) a day (\(kind), \(groupText))"))
        }
        if lines.recommendedLabel == NutrientsReference.labelGoal, let goal = lines.recommended {
            out.append(String(localized: "Your goal: \(amount(goal)) a day"))
        }
        if let upper = lines.upperLimit {
            out.append(String(localized: "Upper limit: \(amount(upper)) a day"))
        }
        if let limit = lines.referenceLimit {
            if let pct = reference?.limit?.pct, reference?.limit?.kind == "pct_energy" {
                out.append(String(localized: "Limit: \(amount(limit)) a day (\(pct.formatted())% of your calorie goal)"))
            } else {
                out.append(String(localized: "Limit: \(amount(limit)) a day"))
            }
        } else if reference?.limit?.kind == "pct_energy" {
            out.append(String(localized: "Limit: set a calorie goal to see it (\((reference?.limit?.pct ?? 10).formatted())% of calories)"))
        }
        if lines.limitLabel == NutrientsReference.labelGoal, let goal = lines.limit {
            out.append(String(localized: "Your goal: \(amount(goal)) a day"))
        }
        return out
    }
}
