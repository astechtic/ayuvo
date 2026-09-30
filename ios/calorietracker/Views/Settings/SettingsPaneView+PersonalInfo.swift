import SwiftUI

/// Health Profile › Personal Info: gender, birthday, height, weight, body fat, measurements, allergens.
extension SettingsPaneView {
    @ViewBuilder
    var personalInfoPane: some View {
        Section {
            Picker(selection: profileBinding.gender) {
                Text("Male").tag(Gender.male)
                Text("Female").tag(Gender.female)
                Text("Other").tag(Gender.other)
            } label: {
                Label {
                    Text("Gender")
                } icon: {
                    SettingsIcon(profile.gender.icon, tint: SettingsTint.body)
                }
            }
            .pickerStyle(.menu)
            .tint(.secondary)
            .onChange(of: profile.gender) { _, _ in saveProfile() }

            ProfileInfoRow(icon: "birthday.cake.fill", tint: SettingsTint.body, label: String(localized: "Birthday", comment: "Personal info settings row"), value: birthdayDisplay) {
                activeSheet = .editBirthday
            }

            ProfileInfoRow(icon: "ruler.fill", tint: SettingsTint.body, label: String(localized: "Height", comment: "Personal info settings row"), value: heightDisplay) {
                activeSheet = .editHeight
            }

            ProfileInfoRow(icon: "scalemass.fill", tint: SettingsTint.body, label: String(localized: "Weight", comment: "Personal info settings row"), value: weightDisplay) {
                activeSheet = .editWeight
            }

            ProfileInfoRow(
                icon: "percent",
                tint: SettingsTint.body,
                label: String(localized: "Body Fat", comment: "Personal info settings row"),
                value: profile.bodyFatPercentage.map { (Double(Int($0 * 100)) / 100).formatted(.percent) } ?? String(localized: "Not set", comment: "Settings value placeholder when nothing is set")
            ) {
                activeSheet = .editBodyFat
            }

            // Only surface the goal row to users who have a current
            // body-fat value — feature was scoped to "skippable, no
            // math impact, only visible if the user opted in to the
            // body-fat track in onboarding (or set one later here)."
            if profile.bodyFatPercentage != nil {
                ProfileInfoRow(
                    icon: "target",
                    tint: SettingsTint.body,
                    label: String(localized: "Goal Body Fat", comment: "Personal info settings row"),
                    value: profile.goalBodyFatPercentage.map { (Double(Int($0 * 100)) / 100).formatted(.percent) } ?? String(localized: "Not set", comment: "Settings value placeholder when nothing is set")
                ) {
                    activeSheet = .editGoalBodyFat
                }
            }

            // Optional tape-measure circumferences. Extra signal for the AI goal calc +
            // Coach (waist-to-hip, waist-to-height, Navy body-fat %, frame). Never edits BMR.
            NavigationLink {
                BodyMeasurementsDetailView(gender: profile.gender, heightCm: profile.heightCm)
            } label: {
                Label {
                    HStack {
                        Text("Body Measurements")
                        Spacer()
                        Text(bodyMeasurementsRowValue)
                            .foregroundStyle(.secondary)
                    }
                } icon: {
                    SettingsIcon("ruler.fill", tint: SettingsTint.body)
                }
            }
            let allergenSummary = profile.configuredAllergenSensitivities.joined(separator: ", ")
            NavigationLink {
                AllergenSensitivitiesDetailView(current: profile.configuredAllergenSensitivities) { values in
                    profile.allergenSensitivities = values
                    saveProfile()
                }
            } label: {
                Label {
                    HStack {
                        Text("Allergen sensitivities")
                        Spacer()
                        Text(allergenSummary.isEmpty ? String(localized: "Not set", comment: "Settings value placeholder when nothing is set") : allergenSummary)
                            .foregroundStyle(.secondary)
                            .lineLimit(1)
                    }
                } icon: {
                    SettingsIcon("exclamationmark.triangle.fill", tint: SettingsTint.warning)
                }
            }
        }
        .listRowBackground(AppColors.appCard)
    }
}
