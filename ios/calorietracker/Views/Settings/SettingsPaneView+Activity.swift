import SwiftUI

/// Tracking › Activity: the daily step goal (Summary Move ring) and workout logging preferences.
extension SettingsPaneView {
    @ViewBuilder
    var activityPane: some View {
        Section {
            Group {
                if dynamicTypeSize.isAccessibilitySize {
                    // Stacked at accessibility sizes so the value never collides with the title.
                    VStack(alignment: .leading, spacing: 8) {
                        Label {
                            Text("Daily Step Goal")
                        } icon: {
                            Image(systemName: "figure.walk")
                                .foregroundStyle(AppColors.calorie)
                        }
                        Stepper(
                            value: $dailyStepGoal,
                            in: ActivitySettings.stepGoalRange,
                            step: ActivitySettings.stepGoalStep
                        ) {
                            Text(dailyStepGoal.formatted())
                                .monospacedDigit()
                                .foregroundStyle(.secondary)
                        }
                    }
                } else {
                    Stepper(
                        value: $dailyStepGoal,
                        in: ActivitySettings.stepGoalRange,
                        step: ActivitySettings.stepGoalStep
                    ) {
                        Label {
                            HStack {
                                Text("Daily Step Goal")
                                Spacer()
                                Text(dailyStepGoal.formatted())
                                    .monospacedDigit()
                                    .foregroundStyle(.secondary)
                            }
                        } icon: {
                            Image(systemName: "figure.walk")
                                .foregroundStyle(AppColors.calorie)
                        }
                    }
                }
            }
            .accessibilityIdentifier("settings.row.dailyStepGoal")
            .accessibilityValue(Text("\(dailyStepGoal.formatted()) steps"))
        } footer: {
            Text("Drives the Move ring on Summary. Steps come from Apple Health.")
        }
        .listRowBackground(AppColors.appCard)

        WorkoutLoggingSettingsSection()
    }
}
