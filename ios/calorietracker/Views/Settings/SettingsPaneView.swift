import SwiftUI
import Photos
import PhotosUI
import PDFKit
import UIKit
import HealthKit
import StoreKit
import WidgetKit
import AVFoundation
import Speech
import UniformTypeIdentifiers

/// One Settings pane (plan §6). The pane bodies live in the `SettingsPaneView+*.swift` extensions;
/// this file keeps the shared state, the sheets/alerts every pane can raise, and the pane switch.
struct SettingsPaneView: View {
    @Environment(AppNavigator.self) var navigator
    @Environment(ProfileStore.self) var profileStore
    @Environment(ChatStore.self) var chatStore
    @Environment(WeightStore.self) var weightStore
    @Environment(BodyFatStore.self) var bodyFatStore
    @Environment(FoodStore.self) var foodStore
    @Environment(WaterStore.self) var waterStore
    @Environment(FastingStore.self) var fastingStore
    @Environment(StrengthWorkoutStore.self) var strengthWorkoutStore
    @Environment(ImportedHealthWorkoutStore.self) var importedHealthWorkoutStore
    @Environment(BodyMeasurementStore.self) var bodyMeasurementStore
    @Environment(NotificationManager.self) var notificationManager
    @Environment(HealthKitManager.self) var healthKitManager
    @Environment(HealthDataStore.self) var healthDataStore
    @Environment(RecordsStore.self) var recordsStore
    @Environment(MedicationStore.self) var medicationStore
    @Environment(\.dynamicTypeSize) var dynamicTypeSize
    var profile: UserProfile {
        get { profileStore.profile }
        nonmutating set { profileStore.profile = newValue }
    }
    var profileBinding: Binding<UserProfile> {
        Binding(get: { profileStore.profile }, set: { profileStore.profile = $0 })
    }
    @AppStorage("appearanceMode") var appearanceMode = "system"
    @AppStorage("heightUnit") var heightUnitRaw = "ftin"
    @AppStorage("weightUnit") var weightUnitRaw = "lbs"
    @AppStorage("hasCompletedOnboarding") var hasCompletedOnboarding = true
    @AppStorage("healthKitEnabled") var healthKitEnabled = false
    @AppStorage(AdaptiveGoalSettings.enabledKey) var adaptiveGoalsEnabled = true
    @AppStorage(EnergyBurnSettings.enabledKey) var energyBurnEnabled = false
    @AppStorage("weekStartsOnMonday") var weekStartsOnMonday = true
    @AppStorage(FoodMeasurementSettings.preferGramsByDefaultKey) var preferGramsByDefault = false
    @AppStorage(MealPhotoSettings.saveToGalleryKey) var saveMealPhotosToGallery = false
    @AppStorage(AppThemeColor.storageKey) var appThemeColorRaw = AppThemeColor.defaultColor.rawValue
    @AppStorage(WaterSettings.enabledKey) var waterTrackingEnabled = false
    @AppStorage(WaterSettings.dailyGoalKey) var waterDailyGoal = WaterSettings.defaultDailyGoalMl
    @AppStorage(WaterSettings.unitKey) var waterUnitRaw = WaterUnit.defaultUnit.rawValue
    @AppStorage(HealthGlucoseUnit.storageKey) var glucoseUnitRaw = HealthGlucoseUnit.current().rawValue
    @AppStorage(ActivitySettings.dailyStepGoalKey) var dailyStepGoal = ActivitySettings.defaultDailyStepGoal
    @AppStorage(FastingSettings.enabledKey) var fastingTrackingEnabled = false
    @AppStorage(FastingSettings.defaultGoalMinutesKey) var fastingDefaultGoalMinutes = FastingSettings.defaultGoalMinutes

    var waterUnit: WaterUnit { WaterUnit(rawValue: waterUnitRaw) ?? .defaultUnit }

    // App-update state is owned by ContentView (it also drives the one-shot update
    // notification). It's forwarded here so the About section — now the last section
    // of Settings — can show the update row and the manual re-check.
    @Binding var updateState: AppUpdateState
    let refreshUpdateState: () async -> Void
    let pane: SettingsPane

    init(
        updateState: Binding<AppUpdateState>,
        refreshUpdateState: @escaping () async -> Void,
        pane: SettingsPane
    ) {
        self._updateState = updateState
        self.refreshUpdateState = refreshUpdateState
        self.pane = pane
    }

    enum ActiveSheet: String, Identifiable {
        case editBirthday, editHeight, editWeight, editBodyFat, editGoalBodyFat, editGoalWeight, editCalories, editProtein, editCarbs, editFat
        var id: String { rawValue }
    }
    @State var activeSheet: ActiveSheet?
    @State var showExportAllData = false
    @State var showImportAllData = false
    @State var showClearHealthDataConfirmation = false
    @State var showDeleteConfirmation = false
    @State var showClearFoodLogConfirmation = false
    @State var showCalculationMethods = false
    @State var showWaterGoalPicker = false
    @State var showFastingGoalPicker = false
    @State var showAutoMacroEditAlert = false
    @State var showMaxPinnedAlert = false
    @State var showInvalidGoalWeightAlert = false
    @State var showDefaultGramsInfo = false
    @State var showAdaptiveGoalsInfo = false
    @State var showEnergyBurnInfo = false
    @State var energyBurnToggleReverting = false
    @State var isRecalculatingGoals = false
    @State var isApplyingAdaptiveGoals = false
    @State var showAdaptiveGoalAlert = false
    @State var adaptiveGoalAlertTitle = ""
    @State var adaptiveGoalAlertMessage = ""
    @State var invalidGoalWeightMessage = ""
    @State var selectedProvider: AIProvider = AIProviderSettings.selectedProvider
    @State var selectedModel: String = AIProviderSettings.selectedModel
    @State var apiKeyText: String = AIProviderSettings.apiKey(for: AIProviderSettings.selectedProvider) ?? ""
    @State var customBaseURL: String = AIProviderSettings.customBaseURL(for: AIProviderSettings.selectedProvider) ?? ""
    @AppStorage("openRouterReasoningEffort") var openRouterReasoningEffort: OpenRouterReasoningEffort = .auto
    @State var maxResponseTokensText: String = String(AIProviderSettings.maxResponseTokens)
    @State var requestTimeoutSecondsText: String = String(AIProviderSettings.requestTimeoutSeconds)
    @State var showAPIKey = false
    @State var separateTextProviderEnabled: Bool = AIProviderSettings.separateTextProviderEnabled
    @State var selectedTextProvider: AIProvider = AIProviderSettings.selectedTextProvider
    @State var selectedTextModel: String = AIProviderSettings.selectedTextModel
    @State var textApiKeyText: String = AIProviderSettings.apiKey(for: AIProviderSettings.selectedTextProvider) ?? ""
    @State var textBaseURL: String = AIProviderSettings.customBaseURL(for: AIProviderSettings.selectedTextProvider) ?? ""
    @State var showTextAPIKey = false
    @State var customAIInstructions: String = AIProviderSettings.userContext
    @State var savedAIInstructions: String = AIProviderSettings.userContext
    @FocusState var customInstructionsFocused: Bool
    @State var fallbackEnabled: Bool = AIProviderSettings.fallbackEnabled
    @State var selectedFallbackProvider: AIProvider = AIProviderSettings.selectedFallbackProvider
    @State var selectedFallbackModel: String = AIProviderSettings.selectedFallbackModel
    @State var fallbackApiKeyText: String = AIProviderSettings.apiKey(for: AIProviderSettings.selectedFallbackProvider) ?? ""
    @State var fallbackBaseURL: String = AIProviderSettings.fallbackCustomBaseURL(for: AIProviderSettings.selectedFallbackProvider) ?? ""
    @State var showFallbackAPIKey = false
    @State var textFallbackEnabled: Bool = AIProviderSettings.textFallbackEnabled
    @State var selectedTextFallbackProvider: AIProvider = AIProviderSettings.selectedTextFallbackProvider
    @State var selectedTextFallbackModel: String = AIProviderSettings.selectedTextFallbackModel
    @State var textFallbackApiKeyText: String = AIProviderSettings.apiKey(for: AIProviderSettings.selectedTextFallbackProvider) ?? ""
    @State var textFallbackBaseURL: String = AIProviderSettings.fallbackCustomBaseURL(for: AIProviderSettings.selectedTextFallbackProvider) ?? ""
    @State var showTextFallbackAPIKey = false
    @State var localModelAvailabilityRevision = 0
    @State var selectedSpeechProvider: SpeechProvider = SpeechSettings.selectedProvider
    @State var selectedSpeechLanguage: SpeechLanguage = SpeechSettings.selectedLanguage(for: SpeechSettings.selectedProvider)
    @State var speechApiKeyText: String = SpeechSettings.apiKey(for: SpeechSettings.selectedProvider) ?? ""
    @State var showSpeechAPIKey = false
    @State var speechFallbackEnabled: Bool = SpeechSettings.fallbackEnabled
    @State var selectedSpeechFallbackProvider: SpeechProvider = SpeechSettings.selectedFallbackProvider
    @State var selectedSpeechFallbackLanguage: SpeechLanguage = SpeechSettings.selectedLanguage(for: SpeechSettings.selectedFallbackProvider)
    @State var speechFallbackApiKeyText: String = SpeechSettings.apiKey(for: SpeechSettings.selectedFallbackProvider) ?? ""
    @State var showSpeechFallbackAPIKey = false

    var heightMetric: Bool { heightUnitRaw == "cm" }
    var weightMetric: Bool { weightUnitRaw == "kg" }

    // Height formatting
    var heightDisplay: String {
        if heightMetric {
            return "\(Int(profile.heightCm)) cm"
        }
        // Round to the nearest inch — truncating shows 5'6" for a 170 cm / 5'7" pick.
        let totalInches = Int((profile.heightCm / 2.54).rounded())
        let feet = totalInches / 12
        let inches = totalInches % 12
        return "\(feet)'\(inches)\""
    }

    // Weight formatting
    var weightDisplay: String {
        if weightMetric {
            return String(format: "%.1f kg", profile.weightKg)
        }
        return String(format: "%.1f lbs", profile.weightKg * 2.20462)
    }

    // Birthday formatting
    var birthdayDisplay: String {
        let formatter = DateFormatter()
        formatter.dateStyle = .medium
        return String(localized: "\(formatter.string(from: profile.birthday)) (age \(profile.age))")
    }

    // Goal weight display
    var goalWeightDisplay: String {
        guard let gw = profile.goalWeightKg else { return "Not set" }
        if weightMetric {
            return String(format: "%.1f kg", gw)
        }
        return String(format: "%.1f lbs", gw * 2.20462)
    }

    /// Glanceable value for the Body Measurements row — the latest waist, or "Not set".
    var bodyMeasurementsRowValue: String {
        guard let latest = bodyMeasurementStore.latestEntry else { return "Not set" }
        if let waist = latest.waistCm {
            return heightMetric ? String(format: "Waist %.0f cm", waist) : String(format: "Waist %.0f in", waist / 2.54)
        }
        return "Logged"
    }

    // Weekly change display
    var weeklyChangeDisplay: String {
        let rate = profile.weeklyChangeKg ?? 0.5
        return WeightDisplayFormatter.weeklyChange(kilograms: rate, useMetric: weightMetric)
    }


    var body: some View {
        List {
            paneContent
        }
            .scrollContentBackground(.hidden)
            .modifier(SettingsKeyboardDismissalModifier())
            .background(AppColors.appBackground)
            .sheet(isPresented: $showExportAllData) {
                ExportAllDataView()
            }
            .sheet(isPresented: $showImportAllData) {
                ImportAllDataView()
            }
            .alert("Clear synced health data from this iPhone?", isPresented: $showClearHealthDataConfirmation) {
                Button("Cancel", role: .cancel) { }
                Button("Clear", role: .destructive) {
                    Task { await healthDataStore.clearSyncedData() }
                }
            } message: {
                Text("Removes the local Apple Health mirror from this iPhone. Your data in the Health app is untouched, and syncing again re-imports it.")
            }
            .sheet(item: $activeSheet) { sheet in
                switch sheet {
                case .editBirthday:
                    NavigationStack {
                        VStack(spacing: 20) {
                            Text("Birthday")
                                .font(.system(.title2, design: .rounded, weight: .bold))

                            DatePicker(
                                "Birthday",
                                selection: profileBinding.birthday,
                                in: ...Date.now,
                                displayedComponents: .date
                            )
                            .datePickerStyle(.wheel)
                            .labelsHidden()

                            Button {
                                saveProfile()
                                activeSheet = nil
                            } label: {
                                Text("Save")
                                    .font(.system(.headline, design: .rounded, weight: .semibold))
                                    .frame(maxWidth: .infinity)
                                    .padding(.vertical, 14)
                                    .background(
                                        LinearGradient(colors: AppColors.calorieGradient, startPoint: .leading, endPoint: .trailing)
                                    )
                                    .foregroundStyle(.white)
                                    .clipShape(RoundedRectangle(cornerRadius: 14))
                            }
                            .padding(.horizontal, 24)

                            Spacer()
                        }
                        .padding(.top, 24)
                        .toolbar {
                            ToolbarItem(placement: .cancellationAction) {
                                Button("Cancel") { activeSheet = nil }
                            }
                        }
                    }
                    .presentationDetents([.medium])

                case .editHeight:
                    HeightPickerSheet(
                        currentHeightCm: profile.heightCm
                    ) { newHeight in
                        profile.heightCm = newHeight
                        saveProfile()
                    }

                case .editWeight:
                    WeightPickerSheet(
                        currentWeightKg: profile.weightKg
                    ) { newWeight in
                        profile.weightKg = newWeight
                        // Invalidate goal weight if the new current weight makes the direction impossible.
                        if let gw = profile.goalWeightKg {
                            let mismatch = (profile.goal == .lose && gw >= newWeight)
                                        || (profile.goal == .gain && gw <= newWeight)
                            if mismatch { profile.goalWeightKg = nil }
                        }
                        saveProfile()
                        weightStore.addEntry(WeightEntry(weightKg: newWeight))
                    }

                case .editBodyFat:
                    BodyFatPickerSheet(
                        currentPercentage: profile.bodyFatPercentage
                    ) { newValue in
                        profile.bodyFatPercentage = newValue
                        // Goal body fat only makes sense alongside a current
                        // value — clear it whenever the current is cleared so
                        // a stale goal doesn't linger on a user who's opted out.
                        if newValue == nil { profile.goalBodyFatPercentage = nil }
                        saveProfile()
                    }

                case .editGoalBodyFat:
                    // Goal body fat is purely cosmetic — does NOT participate
                    // in BMR / TDEE / macro math, so editing it just saves.
                    GoalBodyFatPickerSheet(
                        currentGoal: profile.goalBodyFatPercentage,
                        currentBodyFat: profile.bodyFatPercentage
                    ) { newValue in
                        profile.goalBodyFatPercentage = newValue
                        saveProfile()
                    }

                case .editGoalWeight:
                    WeightPickerSheet(
                        currentWeightKg: profile.goalWeightKg ?? profile.weightKg
                    ) { newGoalWeight in
                        // Validate against current goal direction.
                        let invalid = (profile.goal == .lose && newGoalWeight >= profile.weightKg)
                                   || (profile.goal == .gain && newGoalWeight <= profile.weightKg)
                        if invalid {
                            invalidGoalWeightMessage = profile.goal == .lose
                                ? "A Lose goal needs a target below your current weight."
                                : "A Gain goal needs a target above your current weight."
                            showInvalidGoalWeightAlert = true
                            return
                        }
                        profile.goalWeightKg = newGoalWeight
                        saveProfile()
                    }

                case .editCalories:
                    NutritionPickerSheet(
                        label: "Calories", unit: "kcal",
                        currentValue: profile.effectiveCalories,
                        range: 800...6000, step: 50,
                        onSave: { setCalories(to: $0) },
                        onResetToAuto: profile.isCaloriesLocked ? { resetCaloriesLock() } : nil
                    )

                case .editProtein:
                    NutritionPickerSheet(
                        label: "Protein", unit: "g",
                        currentValue: profile.effectiveProtein,
                        range: 10...500, step: 5,
                        onSave: { setMacro(.protein, to: $0) },
                        onResetToAuto: profile.isMacroLocked(.protein) ? { resetMacroLock(.protein) } : nil
                    )

                case .editCarbs:
                    NutritionPickerSheet(
                        label: "Carbs", unit: "g",
                        currentValue: profile.effectiveCarbs,
                        range: 0...800, step: 5,
                        onSave: { setMacro(.carbs, to: $0) },
                        onResetToAuto: profile.isMacroLocked(.carbs) ? { resetMacroLock(.carbs) } : nil
                    )

                case .editFat:
                    NutritionPickerSheet(
                        label: "Fat", unit: "g",
                        currentValue: profile.effectiveFat,
                        range: 10...300, step: 5,
                        onSave: { setMacro(.fat, to: $0) },
                        onResetToAuto: profile.isMacroLocked(.fat) ? { resetMacroLock(.fat) } : nil
                    )

                }
            }
            .alert("Clear Food Log", isPresented: $showClearFoodLogConfirmation) {
                Button("Cancel", role: .cancel) { }
                Button("Clear All Logs", role: .destructive) {
                    foodStore.replaceAllEntries([])
                }
            } message: {
                Text("This will permanently delete all your logged food entries. Your profile, weight entries, favorites, and workout history will be kept. This action cannot be undone.")
            }
            .alert("Default to Grams", isPresented: $showDefaultGramsInfo) {
                Button("OK", role: .cancel) { }
            } message: {
                Text("When enabled, new food results open with grams selected even if the AI detects cups, portions, or servings. You can still switch units for each food.")
            }
            .alert("Adaptive Goals", isPresented: $showAdaptiveGoalsInfo) {
                Button("OK", role: .cancel) { }
            } message: {
                Text("About once a week when you open the app, Ayuvo automatically re-runs the full goal calculation — the same one the Recalculate button uses — from your profile, recent logged food, and weight trend. If Energy Burn is on, it uses your measured burn as the maintenance anchor. It skips silently if the AI is unavailable. Turning this off restores the targets from before Adaptive Goals first changed them. This is not medical advice.")
            }
            .alert("Energy Burn", isPresented: $showEnergyBurnInfo) {
                Button("OK", role: .cancel) { }
            } message: {
                Text("When on, Ayuvo uses Apple Health’s recent measured-energy window as your maintenance anchor when calculating goals instead of the formula estimate: measured total energy when enough days are available; otherwise, average measured active energy + formula BMR. No AI is used to read your burn. Requires Apple Health. Works with the Recalculate button and with Adaptive Goals.")
            }
            .alert(adaptiveGoalAlertTitle, isPresented: $showAdaptiveGoalAlert) {
                Button("OK", role: .cancel) { }
            } message: {
                Text(adaptiveGoalAlertMessage)
            }
            .sheet(isPresented: $showCalculationMethods) {
                CalculationMethodsView()
            }
            .sheet(isPresented: $showWaterGoalPicker) {
                WaterGoalPickerSheet(currentGoal: waterDailyGoal, unit: waterUnit) {
                    waterDailyGoal = $0
                    WidgetSnapshotWriter.publish(foods: foodStore.entries, profile: profile)
                }
            }
            .sheet(isPresented: $showFastingGoalPicker) {
                FastingGoalPickerSheet(currentGoalMinutes: fastingDefaultGoalMinutes) {
                    fastingDefaultGoalMinutes = $0
                }
            }
            .onAppear {
                // Existing users (and anyone who has never recalculated) start with no baseline.
                // Seed it to the current inputs so the "recalculate suggested" nudge only appears
                // after a genuine change from here on, instead of firing on first launch.
                if UserDefaults.standard.string(forKey: Self.lastRecalcGoalSignatureKey) == nil {
                    markGoalsRecalculated()
                }
            }
            .alert("Can't Rebalance", isPresented: $showAutoMacroEditAlert) {
                Button("OK", role: .cancel) { }
            } message: {
                Text("Calories is locked and both other macros are locked, so there's nothing left to absorb this change. Unlock calories or another macro, then try again.")
            }
            .alert("Max 2 Macros Locked", isPresented: $showMaxPinnedAlert) {
                Button("OK", role: .cancel) { }
            } message: {
                Text("At most 2 macros can be locked at a time, so one stays free to balance. Unlock another macro first (tap its lock icon).")
            }
            .alert("Invalid Goal Weight", isPresented: $showInvalidGoalWeightAlert) {
                Button("OK", role: .cancel) { }
            } message: {
                Text(invalidGoalWeightMessage)
            }
            .alert("Delete All Data", isPresented: $showDeleteConfirmation) {
                Button("Cancel", role: .cancel) { }
                Button("Delete Everything", role: .destructive) {
                    Task {
                        // Apple Health samples remain untouched; users manage those
                        // from the Health app's Sources > Ayuvo screen.
                        foodStore.replaceAllEntries([])
                        weightStore.replaceAllEntries([])
                        waterStore.clear()
                        fastingStore.clear()
                        strengthWorkoutStore.clearAll()
                        importedHealthWorkoutStore.clearAll()
                        FoodImageStore.shared.deleteAll()
                        notificationManager.cancelAllNotifications()
                        // Health mirror: cancel sync → close the SQLite connections → remove the
                        // files (+ -wal/-shm) before the preference domain goes.
                        await healthDataStore.deleteAllData()
                        // Health Records: database (+ sidecars), originals, caches and the share inbox.
                        await recordsStore.deleteAllData()
                        // Medications: database, photos and pending dose reminders.
                        await medicationStore.deleteAllData()
                        let domain = Bundle.main.bundleIdentifier ?? ""
                        UserDefaults.standard.removePersistentDomain(forName: domain)
                        AIProviderSettings.deleteAllData()
                        SpeechSettings.deleteAllData()
                        chatStore.reset()
                        WidgetSnapshot.clear()
                        WidgetDashboardSnapshot.clear()
                        WidgetCenter.shared.reloadAllTimelines()
                        hasCompletedOnboarding = false
                    }
                }
            } message: {
                Text("This will permanently delete all your data including food logs, weight entries, workout history, health records, medications, and profile. This action cannot be undone.")
            }
            .navigationTitle(Text(pane.title))
            .navigationBarTitleDisplayMode(.inline)
    }

    @ViewBuilder
    var paneContent: some View {
        switch pane {
        case .personalInfo: personalInfoPane
        case .goalsNutrition: goalsPane
        case .units: unitsPane
        case .nutritionTracking: nutritionTrackingPane
        case .hydration: hydrationPane
        case .fasting: fastingPane
        case .activity: activityPane
        case .medications: medicationsPane
        case .notifications: EmptyView() // the hub pushes NotificationSettingsView
        case .healthData: healthSyncPane
        case .healthRecords: healthRecordsPane
        case .dataManagement: backupExportPane
        case .deleteData: deleteDataPane
        case .aiProviders: aiProvidersPane
        case .speechToText: speechPane
        case .customInstructions: customInstructionsPane
        case .appearance: appearancePane
        case .appUpdates, .helpSupport, .legal: aboutPane
        }
    }

    func saveProfile() {
        profile.save()
    }
}
