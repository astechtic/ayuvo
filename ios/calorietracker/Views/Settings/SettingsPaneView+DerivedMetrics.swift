import SwiftUI

/// Tracking › Derived Metrics: the master switch, one switch per derived metric and the BMI cut-offs
/// (docs/derived-metrics.md §1). Native Health data always wins; these switches only control Ayuvo's estimates.
extension SettingsPaneView {
    @ViewBuilder
    var derivedMetricsPane: some View {
        DerivedMetricsSettingsSection()
    }
}

struct DerivedMetricsSettingsSection: View {
    @AppStorage(DerivedSettings.enabledKey) private var derivedEnabled = true
    @AppStorage(DerivedSettings.bmiSchemeKey) private var bmiScheme = "who"
    @AppStorage("healthKitEnabled") private var healthKitEnabled = false
    @State private var disabled: Set<String> = DerivedSettings.disabledIDs()

    private let catalog = DerivedCatalog.shared

    var body: some View {
        Section {
            Toggle(isOn: $derivedEnabled) {
                Label {
                    Text("Derived Metrics")
                } icon: {
                    SettingsIcon("function", tint: SettingsTint.insights)
                }
            }
            .tint(AppColors.calorie)
            .accessibilityIdentifier("settings.derived.enabled")
            .onChange(of: derivedEnabled) { _, _ in DerivedMetricsService.shared.settingsDidChange() }
        } footer: {
            Text(healthKitEnabled
                 ? "Ayuvo estimates vitals your devices don't record, such as resting heart rate from minute-by-minute heart rate. When Apple Health already has a value for the same day, that value is shown instead. Estimates are never written to Apple Health."
                 : "Derived metrics use Apple Health data. Turn on Health Sync to see them.")
        }
        .listRowBackground(AppColors.appCard)

        if derivedEnabled {
            Section {
                Picker(selection: $bmiScheme) {
                    Text("WHO").tag("who")
                    Text("Asian").tag("asian")
                } label: {
                    Label {
                        Text("BMI Categories")
                    } icon: {
                        SettingsIcon("scalemass.fill", tint: SettingsTint.insights)
                    }
                }
                .accessibilityIdentifier("settings.derived.bmiScheme")
                .onChange(of: bmiScheme) { _, _ in DerivedMetricsService.shared.settingsDidChange() }
            } footer: {
                Text("Asian cut-offs (overweight from 23, obese from 27.5) follow the WHO expert consultation for Asian populations.")
            }
            .listRowBackground(AppColors.appCard)

            ForEach(catalog.categories, id: \.self) { category in
                Section {
                    ForEach(catalog.metrics(in: category)) { metric in
                        metricRow(metric)
                    }
                } header: {
                    Text(DerivedCatalog.categoryTitle(category))
                }
                .listRowBackground(AppColors.appCard)
            }
        }
    }

    private func metricRow(_ metric: DerivedMetricInfo) -> some View {
        let binding = Binding<Bool>(
            get: { !disabled.contains(metric.id) },
            set: { isOn in
                DerivedSettings.setEnabled(metric.id, isOn)
                disabled = DerivedSettings.disabledIDs()
                DerivedMetricsService.shared.settingsDidChange()
            }
        )
        let dependents = catalog.dependents(of: metric.id).map(\.displayTitle)
        return Toggle(isOn: binding) {
            VStack(alignment: .leading, spacing: 2) {
                Text(metric.displayTitle)
                if metric.nativeTypeID != nil {
                    Text("Apple Health value is used when available")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
                if !dependents.isEmpty, disabled.contains(metric.id) {
                    Text("Also affects: \(dependents.joined(separator: ", "))")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            }
        }
        .tint(AppColors.calorie)
        .accessibilityIdentifier("settings.derived.\(metric.id)")
    }
}
