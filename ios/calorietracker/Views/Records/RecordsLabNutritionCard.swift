import SwiftUI

/// Builds the labs ↔ nutrition messages (`lab_nutrient_links`, docs/intake-metrics.md §3). Copy is associational:
/// it says a value was below the report's printed range, shows the average intake against the reference and
/// suggests talking to a doctor. It never names a condition.
enum LabNutritionLinks {
    static let windowDays = 30

    struct Message: Identifiable, Hashable {
        var id: String
        var nutrientKey: String
        var text: String
    }

    /// Latest printed value per configured analyte (value and reference range as on the report).
    static func latestLabs(_ items: [String: [RecordObservationItem]]) -> [LabLinks.Lab] {
        var labs: [LabLinks.Lab] = []
        for id in items.keys.sorted() {
            guard let o = items[id]?.first(where: { $0.observation.valueNum != nil && !$0.observation.excludedFromTrends })?.observation,
                  let value = o.valueNum else { continue }
            labs.append(LabLinks.Lab(analyte: id, value: value, refLow: o.refLow, refHigh: o.refHigh))
        }
        return labs
    }

    /// Average intake per day over the window, keyed by intake key (`iron_mg`): food per logged food day plus
    /// supplements spread over every day of the window (`supplement_daily`).
    static func intakeAverages(foods: [FoodEntry], supplements: [NutrientsReference.SupplementEntry], keys: [String],
                               now: Date, calendar: Calendar) -> [String: Double] {
        let end = calendar.startOfDay(for: now).addingTimeInterval(86_400)
        let start = calendar.date(byAdding: .day, value: -windowDays, to: end) ?? end.addingTimeInterval(-Double(windowDays) * 86_400)
        let windowFoods = foods.filter { $0.timestamp >= start && $0.timestamp < end }
        let loggedDays = Set(windowFoods.map { calendar.startOfDay(for: $0.timestamp) }).count
        let lo = NutrientTotals.ms(start), hi = NutrientTotals.ms(end)
        var out: [String: Double] = [:]
        for key in keys {
            let app = IntakeNutrientKeys.appKey(key)
            var food = 0.0
            for e in windowFoods { food += NutrientTotals.foodValue(app, e) ?? 0 }
            var supplement = 0.0, doses = 0
            for s in supplements where s.nutrientKey == app && s.tMs >= lo && s.tMs < hi {
                supplement += s.value
                doses += 1
            }
            let perDay = NutrientGoals.supplementDaily(amountPerDose: doses > 0 ? supplement / Double(doses) : 0, doses: doses,
                                                       windowDays: Double(windowDays), upper: nil).dailyAverage ?? 0
            guard loggedDays > 0 || doses > 0 else { continue }
            out[key] = (loggedDays > 0 ? food / Double(loggedDays) : 0) + perDay
        }
        return out
    }

    static func messages(labs: [LabLinks.Lab], intakeAvg: [String: Double], goals: [String: Double],
                         supplementNutrients: [String], config: IntakeConfig = .shared) -> [Message] {
        let result = LabLinks.labNutrientLinks(labs: labs, intakeAvg: intakeAvg, goals: goals,
                                               supplementNutrients: supplementNutrients, config: config)
        return result.links.map { link in
            let analytes = link.analytesLow.map { AnalyteCatalog.shared.analyte(id: $0)?.displayName ?? $0 }
            let analyteText = ListFormatter.localizedString(byJoining: analytes)
            let nutrient = NutrientCatalog.labelTitle(IntakeNutrientKeys.appKey(link.nutrient)).lowercased()
            var text = String(localized: "Your \(analyteText) was below the report's range.")
            if let pct = link.intakePct {
                text += " " + (link.supplementProvides
                    ? String(localized: "Your average \(nutrient) intake is \(Int(pct))% of the reference.")
                    : String(localized: "Your average \(nutrient) intake is \(Int(pct))% of the reference, and none of your supplements contain it."))
            } else if !link.supplementProvides {
                text += " " + String(localized: "None of your supplements contain \(nutrient).")
            }
            text += " " + String(localized: "Consider discussing this with your doctor.")
            return Message(id: link.id, nutrientKey: IntakeNutrientKeys.appKey(link.nutrient), text: text)
        }
    }
}

/// Health Records overview card: lab values below the report's range linked to nutrition.
struct RecordsLabNutritionCard: View {
    @Environment(RecordsStore.self) private var recordsStore
    @Environment(FoodStore.self) private var foodStore
    @Environment(MedicationStore.self) private var medicationStore
    @Environment(ProfileStore.self) private var profileStore
    @State private var messages: [LabNutritionLinks.Message] = []

    var body: some View {
        Group {
            if !messages.isEmpty {
                VStack(alignment: .leading, spacing: 8) {
                    AyuvoSectionHeader("Labs and nutrition")
                    RecordsCard {
                        ForEach(messages) { message in
                            NavigationLink(value: MetricRoute.detail(.nutrient(message.nutrientKey))) {
                                HStack(alignment: .top, spacing: 10) {
                                    Image(systemName: NutrientCatalog.iconName(message.nutrientKey))
                                        .foregroundStyle(AyuvoPalette.nutrition)
                                        .frame(width: 22)
                                    Text(message.text)
                                        .font(.system(.subheadline, design: .rounded))
                                        .foregroundStyle(.primary)
                                        .multilineTextAlignment(.leading)
                                        .fixedSize(horizontal: false, vertical: true)
                                    Spacer(minLength: 0)
                                    Image(systemName: "chevron.right")
                                        .font(.caption2)
                                        .foregroundStyle(.tertiary)
                                }
                                .contentShape(Rectangle())
                            }
                            .buttonStyle(.plain)
                            .accessibilityIdentifier("records.labNutrition.\(message.id)")
                        }
                        Text(String(localized: "Reference: \(IntakeNutrientKeys.sourceLine) Intake is your average over the last \(LabNutritionLinks.windowDays) days, including supplements. \(IntakeConfig.shared.disclaimer)"))
                            .font(.system(.caption2, design: .rounded))
                            .foregroundStyle(.secondary)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                }
                .accessibilityElement(children: .contain)
                .accessibilityIdentifier("records.labNutrition")
            }
        }
        .task(id: "\(recordsStore.totalCount)-\(foodStore.entries.count)-\(medicationStore.nutritionRevision)") {
            await reload()
        }
    }

    private func reload() async {
        let config = IntakeConfig.shared
        var items: [String: [RecordObservationItem]] = [:]
        for analyte in Set(config.labLinks.flatMap(\.analytes)) {
            if let trend = await recordsStore.trend(analyteID: analyte) { items[analyte] = trend.items }
        }
        let labs = LabNutritionLinks.latestLabs(items)
        guard !labs.isEmpty else {
            messages = []
            return
        }
        let profile = NutrientCatalog.profile(profileStore.profile)
        let dri = NutrientGoals.driGoals(sex: IntakeNutrientKeys.driSex(profile.sex), age: profile.age.map(floor), config: config)
        let keys = config.labLinks.map(\.nutrient)
        let intake = LabNutritionLinks.intakeAverages(foods: foodStore.entries, supplements: medicationStore.supplementEntries,
                                                      keys: keys, now: Date(), calendar: .current)
        let supplementKeys = medicationStore.activeNutrientKeys.compactMap { IntakeNutrientKeys.intakeKey($0) }.sorted()
        messages = LabNutritionLinks.messages(labs: labs, intakeAvg: intake, goals: dri.goals, supplementNutrients: supplementKeys,
                                              config: config)
    }
}
