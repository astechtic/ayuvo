import SwiftUI

/// Health Profile › Units: display units and the week start. Stored values never change units.
extension SettingsPaneView {
    @ViewBuilder
    var unitsPane: some View {
        Section {
            Picker(selection: $heightUnitRaw) {
                Text("Centimetres (cm)").tag(HeightUnit.cm.rawValue)
                Text("Feet & inches (ft in)").tag(HeightUnit.ftin.rawValue)
            } label: {
                Label {
                    Text("Height & Length")
                } icon: {
                    Image(systemName: "ruler")
                        .foregroundStyle(AppColors.calorie)
                }
            }
            .pickerStyle(.menu)
            .tint(.secondary)
            .accessibilityIdentifier("settings.row.heightUnit")

            Picker(selection: $weightUnitRaw) {
                Text("Kilograms (kg)").tag(WeightUnit.kg.rawValue)
                Text("Pounds (lbs)").tag(WeightUnit.lbs.rawValue)
            } label: {
                Label {
                    Text("Weight")
                } icon: {
                    Image(systemName: "scalemass")
                        .foregroundStyle(AppColors.calorie)
                }
            }
            .pickerStyle(.menu)
            .tint(.secondary)
            .accessibilityIdentifier("settings.row.weightUnit")

            Picker(selection: $waterUnitRaw) {
                ForEach(WaterUnit.allCases) { unit in
                    Text("\(unit.title) (\(unit.symbol))").tag(unit.rawValue)
                }
            } label: {
                Label {
                    Text("Water Unit")
                } icon: {
                    Image(systemName: "ruler")
                        .foregroundStyle(AppColors.calorie)
                }
            }
            .pickerStyle(.menu)
            .tint(.secondary)
            .onChange(of: waterUnitRaw) { _, _ in
                WidgetSnapshotWriter.publish(foods: foodStore.entries, profile: profile)
            }
            .accessibilityIdentifier("settings.row.waterUnit")

            Picker(selection: $glucoseUnitRaw) {
                ForEach(HealthGlucoseUnit.allCases) { unit in
                    Text(unit.rawValue).tag(unit.rawValue)
                }
            } label: {
                Label {
                    Text("Blood Glucose")
                } icon: {
                    Image(systemName: "drop")
                        .foregroundStyle(AppColors.calorie)
                }
            }
            .pickerStyle(.menu)
            .tint(.secondary)
            .accessibilityIdentifier("settings.row.glucoseUnit")
        } footer: {
            Text("Only how values are displayed changes. Temperature follows the height unit (metric → °C).")
        }
        .listRowBackground(AppColors.appCard)
        .onChange(of: heightUnitRaw) { _, _ in Task { await healthDataStore.refreshSnapshots() } }
        .onChange(of: weightUnitRaw) { _, _ in Task { await healthDataStore.refreshSnapshots() } }
        .onChange(of: glucoseUnitRaw) { _, _ in Task { await healthDataStore.refreshSnapshots() } }

        Section {
            Picker(selection: $weekStartsOnMonday) {
                Text("Sunday").tag(false)
                Text("Monday").tag(true)
            } label: {
                Label {
                    Text("Week Starts On")
                } icon: {
                    Image(systemName: "calendar")
                        .foregroundStyle(AppColors.calorie)
                }
            }
            .pickerStyle(.menu)
            .tint(.secondary)
            .accessibilityIdentifier("settings.row.weekStart")
        } footer: {
            Text("Weekly charts and 6-month buckets start on this day.")
        }
        .listRowBackground(AppColors.appCard)
    }
}
