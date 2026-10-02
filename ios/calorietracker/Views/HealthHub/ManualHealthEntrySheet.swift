import SwiftUI

/// Glucose / temperature log sheet (docs/ui-structure.md §8, docs/health-data.md §2.2): value with a
/// unit toggle, date and time (never in the future), glucose relation to meal + sample, temperature
/// location. Save stays disabled until the value is in range.
struct ManualHealthEntrySheet: View {
    let kind: ManualHealthKind
    let onSave: (ManualHealthEntry) -> Void

    @Environment(\.dismiss) private var dismiss
    @State private var valueText = ""
    @State private var glucoseUnit: HealthGlucoseUnit
    @State private var celsius: Bool
    @State private var date = Date()
    @State private var relationToMeal = 1
    @State private var specimen = ManualHealthEntryService.defaultSpecimen
    @State private var location = 0
    @FocusState private var valueFocused: Bool

    init(kind: ManualHealthKind, onSave: @escaping (ManualHealthEntry) -> Void) {
        self.kind = kind
        self.onSave = onSave
        _glucoseUnit = State(initialValue: HealthGlucoseUnit.current())
        _celsius = State(initialValue: HealthUnitFormatting.usesMetricLength)
    }

    private var title: LocalizedStringKey {
        kind == .bloodGlucose ? "Log Blood Glucose" : "Log Body Temperature"
    }

    private var unitLabel: String {
        switch kind {
        case .bloodGlucose: return glucoseUnit.rawValue
        case .bodyTemperature: return celsius ? "°C" : "°F"
        }
    }

    private var idPrefix: String { kind == .bloodGlucose ? "log.glucose" : "log.temperature" }

    /// Canonical value (mmol/L or °C) of what is typed, if it parses.
    private var canonicalValue: Double? {
        guard let typed = ManualHealthEntryService.parseDecimal(valueText) else { return nil }
        switch kind {
        case .bloodGlucose: return ManualHealthEntryService.canonicalGlucose(typed, unit: glucoseUnit)
        case .bodyTemperature: return ManualHealthEntryService.canonicalTemperature(typed, celsius: celsius)
        }
    }

    private var isValid: Bool {
        guard let canonicalValue else { return false }
        return ManualHealthEntryService.isInRange(canonicalValue, kind: kind) && date <= Date().addingTimeInterval(60)
    }

    private var rangeHint: String {
        let range: ClosedRange<Double>
        let digits: Int
        switch kind {
        case .bloodGlucose:
            range = glucoseUnit == .mmolPerLiter ? ManualHealthEntryService.glucoseRangeMmol : ManualHealthEntryService.glucoseRangeMgPerDl
            digits = glucoseUnit == .mmolPerLiter ? 1 : 0
        case .bodyTemperature:
            range = celsius ? ManualHealthEntryService.temperatureRangeC : ManualHealthEntryService.temperatureRangeF
            digits = 1
        }
        let low = HealthUnitFormatting.number(range.lowerBound, fractionDigits: digits)
        let high = HealthUnitFormatting.number(range.upperBound, fractionDigits: digits)
        return String(localized: "Allowed range: \(low)–\(high) \(unitLabel)", comment: "Manual health entry: valid value range")
    }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    HStack {
                        TextField(kind == .bloodGlucose ? LocalizedStringKey("Blood Glucose") : LocalizedStringKey("Body Temperature"), text: $valueText)
                            .keyboardType(.decimalPad)
                            .focused($valueFocused)
                            .font(.system(.title2, design: .rounded, weight: .semibold))
                            .accessibilityIdentifier("\(idPrefix).value")
                        Text(unitLabel)
                            .foregroundStyle(.secondary)
                    }
                    unitPicker
                } footer: {
                    Text(rangeHint)
                }

                Section {
                    DatePicker("Date and time", selection: $date, in: ...Date(), displayedComponents: [.date, .hourAndMinute])
                        .accessibilityIdentifier("log.time")
                }

                if kind == .bloodGlucose {
                    Section {
                        Picker("Relation to meal", selection: $relationToMeal) {
                            Text("General").tag(1)
                            Text("Fasting").tag(2)
                            Text("Before meal").tag(3)
                            Text("After meal").tag(4)
                        }
                        .accessibilityIdentifier("log.glucose.relation")
                        Picker("Sample", selection: $specimen) {
                            Text("Capillary blood").tag(2)
                            Text("Interstitial fluid").tag(1)
                            Text("Plasma").tag(3)
                            Text("Whole blood").tag(6)
                        }
                        .accessibilityIdentifier("log.glucose.specimen")
                    }
                } else {
                    Section {
                        Picker("Measured at", selection: $location) {
                            Text("Not specified").tag(0)
                            Text("Mouth").tag(4)
                            Text("Ear").tag(8)
                            Text("Forehead").tag(3)
                            Text("Temporal artery").tag(6)
                            Text("Armpit").tag(1)
                            Text("Rectum").tag(5)
                            Text("Wrist").tag(9)
                            Text("Finger").tag(2)
                            Text("Toe").tag(7)
                            Text("Vagina").tag(10)
                        }
                        .accessibilityIdentifier("log.temperature.location")
                    }
                }

                Section {
                    Button(action: save) {
                        Text("Save")
                            .font(.system(.headline, design: .rounded, weight: .semibold))
                            .frame(maxWidth: .infinity)
                            .padding(.vertical, 6)
                    }
                    .disabled(!isValid)
                    .accessibilityIdentifier("log.save")
                }
            }
            .navigationTitle(title)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
            }
            .onAppear { valueFocused = true }
        }
        .presentationDetents([.large])
    }

    @ViewBuilder
    private var unitPicker: some View {
        switch kind {
        case .bloodGlucose:
            Picker("Unit", selection: Binding(
                get: { glucoseUnit },
                set: { newUnit in
                    convertTyped { newUnit == .mmolPerLiter ? ManualHealthEntryService.mmol(fromMgPerDl: $0) : ManualHealthEntryService.mgPerDl(fromMmol: $0) }
                    glucoseUnit = newUnit
                    // The sheet's toggle is the glucose unit setting (cloud-backed `healthGlucoseUnit`).
                    UserDefaults.standard.set(newUnit.rawValue, forKey: HealthGlucoseUnit.storageKey)
                }
            )) {
                ForEach(HealthGlucoseUnit.allCases) { unit in
                    Text(unit.rawValue).tag(unit)
                }
            }
            .pickerStyle(.segmented)
            .accessibilityIdentifier("log.glucose.unit")
        case .bodyTemperature:
            Picker("Unit", selection: Binding(
                get: { celsius },
                set: { newValue in
                    convertTyped { newValue ? ManualHealthEntryService.celsius(fromFahrenheit: $0) : ManualHealthEntryService.fahrenheit(fromCelsius: $0) }
                    celsius = newValue
                }
            )) {
                Text("°C").tag(true)
                Text("°F").tag(false)
            }
            .pickerStyle(.segmented)
            .accessibilityIdentifier("log.temperature.unit")
        }
    }

    /// Keeps a typed value when the unit flips.
    private func convertTyped(_ transform: (Double) -> Double) {
        guard let typed = ManualHealthEntryService.parseDecimal(valueText) else { return }
        let converted = transform(typed)
        let digits = kind == .bloodGlucose && glucoseUnit == .mmolPerLiter ? 0 : 1
        valueText = HealthUnitFormatting.number(converted, fractionDigits: digits)
    }

    private func save() {
        guard isValid, let value = canonicalValue else { return }
        var entry = ManualHealthEntry(kind: kind, value: value, date: min(date, Date()))
        switch kind {
        case .bloodGlucose:
            entry.relationToMeal = relationToMeal
            entry.specimen = specimen
        case .bodyTemperature:
            entry.measurementLocation = location
        }
        onSave(entry)
        dismiss()
    }
}
