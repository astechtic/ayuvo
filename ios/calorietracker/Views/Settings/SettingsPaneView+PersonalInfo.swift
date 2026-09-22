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

            ProfileInfoRow(icon: "birthday.cake.fill", tint: SettingsTint.body, label: "Birthday", value: birthdayDisplay) {
                activeSheet = .editBirthday
            }

            ProfileInfoRow(icon: "ruler.fill", tint: SettingsTint.body, label: "Height", value: heightDisplay) {
                activeSheet = .editHeight
            }

            ProfileInfoRow(icon: "scalemass.fill", tint: SettingsTint.body, label: "Weight", value: weightDisplay) {
                activeSheet = .editWeight
            }

            ProfileInfoRow(
                icon: "percent",
                tint: SettingsTint.body,
                label: "Body Fat",
                value: profile.bodyFatPercentage != nil ? "\(Int(profile.bodyFatPercentage! * 100))%" : "Not set"
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
                    label: "Goal Body Fat",
                    value: profile.goalBodyFatPercentage != nil ? "\(Int(profile.goalBodyFatPercentage! * 100))%" : "Not set"
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
                        Text(allergenSummary.isEmpty ? "Not set" : allergenSummary)
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
