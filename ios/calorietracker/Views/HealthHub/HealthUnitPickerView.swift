import SwiftUI

/// Per-type unit override. Weight / height / water reuse the app-wide preferences;
/// glucose has its own cloud-backed `healthGlucoseUnit`; temperature follows the length unit.
struct HealthUnitPickerView: View {
    let typeID: String
    @Environment(HealthDataStore.self) private var store
    @AppStorage(WeightUnit.storageKey) private var weightUnitRaw = WeightUnit.lbs.rawValue
    @AppStorage(HeightUnit.storageKey) private var heightUnitRaw = HeightUnit.ftin.rawValue
    @AppStorage(WaterSettings.unitKey) private var waterUnitRaw = WaterUnit.defaultUnit.rawValue
    @AppStorage(HealthGlucoseUnit.storageKey) private var glucoseUnitRaw = HealthGlucoseUnit.current().rawValue

    private var type: HealthMetricType { store.metricType(for: typeID) }

    var body: some View {
        List {
            Section {
                switch type.unit {
                case "kg":
                    Picker("Weight", selection: $weightUnitRaw) {
                        Text("Kilograms (kg)").tag(WeightUnit.kg.rawValue)
                        Text("Pounds (lb)").tag(WeightUnit.lbs.rawValue)
                    }
                case "m":
                    Picker("Length", selection: $heightUnitRaw) {
                        Text("Metric (cm, km)").tag(HeightUnit.cm.rawValue)
                        Text("Imperial (ft in, mi)").tag(HeightUnit.ftin.rawValue)
                    }
                case "degC":
                    Picker("Temperature", selection: $heightUnitRaw) {
                        Text("Celsius (°C)").tag(HeightUnit.cm.rawValue)
                        Text("Fahrenheit (°F)").tag(HeightUnit.ftin.rawValue)
                    }
                case "mmol/L":
                    Picker("Blood Glucose", selection: $glucoseUnitRaw) {
                        ForEach(HealthGlucoseUnit.allCases) { unit in
                            Text(unit.rawValue).tag(unit.rawValue)
                        }
                    }
                case "mL":
                    Picker("Water", selection: $waterUnitRaw) {
                        ForEach(WaterUnit.allCases) { unit in
                            Text(unit.title).tag(unit.rawValue)
                        }
                    }
                default:
                    LabeledContent("Unit", value: type.unit)
                }
            } footer: {
                Text(type.unit == "degC"
                    ? "Temperature follows the length unit (metric → °C, imperial → °F). Values are stored in °C."
                    : "Only how values are displayed changes. Stored values keep the shared unit (\(type.unit)).")
                    .font(.system(.caption2, design: .rounded))
            }
            .pickerStyle(.inline)
            .listRowBackground(AppColors.appCard)
        }
        .scrollContentBackground(.hidden)
        .background(AppColors.appBackground)
        .navigationTitle("Unit")
        .navigationBarTitleDisplayMode(.inline)
        .onChange(of: weightUnitRaw) { _, _ in Task { await store.refreshSnapshots() } }
        .onChange(of: heightUnitRaw) { _, _ in Task { await store.refreshSnapshots() } }
        .onChange(of: waterUnitRaw) { _, _ in Task { await store.refreshSnapshots() } }
        .onChange(of: glucoseUnitRaw) { _, _ in Task { await store.refreshSnapshots() } }
    }
}
