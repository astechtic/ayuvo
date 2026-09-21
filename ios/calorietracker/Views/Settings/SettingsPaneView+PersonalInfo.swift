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
                    Image(systemName: profile.gender.icon)
                        .foregroundStyle(AppColors.calorie)
                }
            }
            .pickerStyle(.menu)
            .tint(.secondary)
            .onChange(of: profile.gender) { _, _ in saveProfile() }

            ProfileInfoRow(icon: "birthday.cake", label: "Birthday", value: birthdayDisplay) {
                activeSheet = .editBirthday
            }

            ProfileInfoRow(icon: "ruler", label: "Height", value: heightDisplay) {
                activeSheet = .editHeight
            }

            ProfileInfoRow(icon: "scalemass", label: "Weight", value: weightDisplay) {
                activeSheet = .editWeight
            }

            ProfileInfoRow(
                icon: "percent",
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
                    Image(systemName: "ruler")
                        .foregroundStyle(AppColors.calorie)
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
                    Image(systemName: "exclamationmark.triangle")
                        .foregroundStyle(AppColors.calorie)
                }
            }
        }
        .listRowBackground(AppColors.appCard)
    }
}
