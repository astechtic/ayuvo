//
//  calorietrackerApp.swift
//  calorietracker
//
//  Created by Apoorv Darshan on 05/02/26.
//

import SwiftUI
import HealthKit
import WidgetKit

@main
struct calorietrackerApp: App {
    @UIApplicationDelegateAdaptor(AppDelegate.self) private var appDelegate
    @State private var foodStore = FoodStore()
    @State private var weightStore = WeightStore()
    @State private var bodyFatStore = BodyFatStore()
    @State private var bodyMeasurementStore = BodyMeasurementStore()
    @State private var notificationManager = NotificationManager()
    @State private var healthKitManager = HealthKitManager()
    @State private var profileStore = ProfileStore()
    @State private var chatStore = ChatStore()
    @State private var waterStore = WaterStore()
    @State private var fastingStore = FastingStore()
    @State private var strengthWorkoutStore = StrengthWorkoutStore()
    @State private var importedHealthWorkoutStore = ImportedHealthWorkoutStore()
    @State private var cloudBackupService = CloudBackupService()
    @State private var healthDataStore = HealthDataStore()
    @State private var recordsStore = RecordsStore()
    @State private var medicationStore = MedicationStore()
    @AppStorage("hasCompletedOnboarding") private var hasCompletedOnboarding = false
    @AppStorage("appearanceMode") private var appearanceMode = "system"
    @AppStorage("notificationsEnabled") private var notificationsEnabled = false
    @AppStorage(AppThemeColor.storageKey) private var appThemeColorRaw = AppThemeColor.defaultColor.rawValue
    @Environment(\.scenePhase) private var scenePhase
    @State private var isAutoRefreshingAdaptiveGoals = false

    private var colorScheme: ColorScheme? {
        switch appearanceMode {
        case "light": return .light
        case "dark": return .dark
        default: return nil
        }
    }

    init() {
        // Derive the split height/weight unit prefs from the legacy useMetric flag
        // before any view reads them.
        UnitPreferenceMigration.runIfNeeded()
        AIProviderSettings.migrateLegacyGeminiModelsIfNeeded()
        SpeechSettings.migrateMatchingPrimaryProviderIfNeeded()
        AIProviderSettings.migrateFallbackBaseURLsIfNeeded()
        if CommandLine.arguments.contains("--reset-onboarding") {
            UserDefaults.standard.removeObject(forKey: "hasCompletedOnboarding")
            UserDefaults.standard.removeObject(forKey: "userProfile")
        }
        ExerciseCatalogWarmup.startIfNeeded()
    }

    var body: some Scene {
        WindowGroup {
            Group {
                if hasCompletedOnboarding {
                    ContentView()
                        .environment(foodStore)
                        .environment(weightStore)
                        .environment(bodyFatStore)
                        .environment(bodyMeasurementStore)
                        .environment(notificationManager)
                        .environment(healthKitManager)
                        .environment(profileStore)
                        .environment(chatStore)
                        .environment(waterStore)
                        .environment(fastingStore)
                        .environment(strengthWorkoutStore)
                        .environment(importedHealthWorkoutStore)
                        .environment(cloudBackupService)
                        .environment(healthDataStore)
                        .environment(recordsStore)
                        .environment(medicationStore)
                } else {
                    OnboardingView(hasCompletedOnboarding: $hasCompletedOnboarding)
                        .environment(notificationManager)
                        .environment(foodStore)
                        .environment(weightStore)
                        .environment(bodyFatStore)
                        .environment(bodyMeasurementStore)
                        .environment(healthKitManager)
                        .environment(profileStore)
                        .environment(chatStore)
                        .environment(waterStore)
                        .environment(fastingStore)
                        .environment(healthDataStore)
                        .environment(medicationStore)
                }
            }
            .tint(AppThemeColor.color(for: appThemeColorRaw).color)
            .preferredColorScheme(colorScheme)
            .onAppear {
                AppThemeColor.applyAppIconIfNeeded(for: AppThemeColor.color(for: appThemeColorRaw))
            }
            .onChange(of: appThemeColorRaw) { _, newValue in
                AppThemeColor.applyAppIconIfNeeded(for: AppThemeColor.color(for: newValue))
            }
            .onOpenURL { url in
                // "Open in Ayuvo" (Files, Mail…): PDFs and images import as Health Records.
                if url.isFileURL {
                    Task { await recordsStore.importOpenIn(url: url) }
                    return
                }
                // Share extension "Save to Health Records" hand-off.
                if url.scheme == "ayuvo", url.host == "records-inbox" {
                    Task { await recordsStore.drainInbox() }
                    return
                }
                // Share extension "Log as food" hand-off: open the diary, which consumes the image.
                if url.scheme == "ayuvo", url.host == "import-share-image" {
                    NotificationCenter.default.post(name: .shareImageImportRequested, object: nil)
                    return
                }
                // ayuvo://medications[?id=<medication id>] opens the Meds pane (optionally at a detail).
                if url.scheme == "ayuvo", url.host == "medications" {
                    let id = URLComponents(url: url, resolvingAgainstBaseURL: false)?
                        .queryItems?.first(where: { $0.name == "id" })?.value
                    MedicationCoordinator.request(medicationID: id?.isEmpty == false ? id : nil)
                    return
                }
                guard url.scheme == "ayuvo", url.host == "log-food",
                      let raw = URLComponents(url: url, resolvingAgainstBaseURL: false)?
                        .queryItems?.first(where: { $0.name == "method" })?.value,
                      let method = FoodLogMethod(rawValue: raw)
                else { return }
                FoodLogMethodCoordinator.request(method)
            }
            .onReceive(NotificationCenter.default.publisher(for: .medicationDoseDidChange)) { _ in
                // A dose was taken / skipped / snoozed from a notification action in the background.
                medicationStore.handleExternalChange()
            }
            .onReceive(NotificationCenter.default.publisher(for: .userProfileDidChange)) { _ in
                refreshWidgetSnapshot()
            }
            .onReceive(NotificationCenter.default.publisher(for: .cloudBackupDidRestore)) { _ in
                foodStore.reloadFromDefaults()
                weightStore.reloadFromDefaults()
                bodyFatStore.reloadFromDefaults()
                bodyMeasurementStore.reloadFromDefaults()
                waterStore.reloadFromDefaults()
                fastingStore.reloadFromDefaults()
                chatStore.reloadFromDefaults()
                strengthWorkoutStore.reloadFromDefaults()
                importedHealthWorkoutStore.reloadFromDefaults()
                profileStore.reloadFromDisk()
                // The health mirror is never part of the cloud backup; only re-check
                // authorization and drop device-local throttles so "Grant access" shows.
                healthDataStore.reloadAfterRestore()
                // Medications are never in the cloud backup either; only their preferences may change.
                medicationStore.reloadAfterRestore()
                refreshWidgetSnapshot()
            }
            .task {
                // Apply an on-device AI choice from onboarding once the model is usable.
                await Gemma4LocalModelManager.shared.resumePendingSelectionIfNeeded()
            }
            .task {
                await cloudBackupService.runSmokeTestIfRequested()
                await importHealthFixtureIfRequested()
            }
            .task {
                // Items shared while Ayuvo was not running.
                await recordsStore.drainInbox()
                await importRecordsFixtureIfRequested()
                await importRecordsArchiveIfRequested()
                // Health Records processing: resume unfinished jobs (and the one-time Phase 1 backfill).
                if hasCompletedOnboarding { await recordsStore.resumeProcessing() }
            }
            .task {
                // Medications: open lazily (the database file is only created on the first save),
                // then the DEBUG fixture hook for UI tests.
                await medicationStore.openIfNeeded()
                await importMedicationsFixtureIfRequested()
            }
        }
        .onChange(of: scenePhase) { _, newPhase in
            if newPhase == .background {
                Task { await exportRecordsArchiveIfRequested() }
                Task { await cloudBackupService.autoBackupIfNeeded() }
                healthDataStore.sceneDidEnterBackground()
            }
            if newPhase == .active {
                Task {
                    await notificationManager.refreshAuthorizationStatus()
                }
                if notificationsEnabled, let profile = UserProfile.load() {
                    notificationManager.rescheduleDataDependentNotifications(
                        foodStore: foodStore, weightStore: weightStore, bodyFatStore: bodyFatStore, profile: profile
                    )
                }
                Task {
                    await recordsStore.drainInbox()
                    if hasCompletedOnboarding { await recordsStore.resumeProcessing() }
                }
                Task {
                    // Medications: mark doses missed / courses completed, then refresh the Today timeline.
                    await medicationStore.materializeMissedAndCompletions()
                    await medicationStore.reload()
                }
                if hasCompletedOnboarding {
                    wireUpHealthKit()
                    // Re-wire on every scene-active so the widget refresh callback
                    // is connected for users who completed onboarding before this
                    // hook existed (the .onChange(hasCompletedOnboarding) branch
                    // only fires on the false→true transition, never on cold launch).
                    wireUpFoodStoreCallback()
                    refreshAdaptiveGoalsIfNeeded()
                }
                // Refresh on scene-active so widgets roll over at midnight even
                // without an explicit food change.
                refreshWidgetSnapshot()
            }
        }
        .onChange(of: hasCompletedOnboarding) { _, completed in
            if completed {
                // New installs start with Energy Burn on, and Adaptive Goals on —
                // UNLESS the user hand-tuned their plan during onboarding, since
                // adaptive's first weekly run would overwrite those numbers.
                // Existing users are untouched — these keys are only written here
                // and by the Settings toggles. Onboarding just calculated goals, so
                // mark the weekly adaptive check as done; the first run lands next week.
                UserDefaults.standard.set(
                    !UserDefaults.standard.bool(forKey: "onboardingPlanEdited"),
                    forKey: AdaptiveGoalSettings.enabledKey
                )
                UserDefaults.standard.set(true, forKey: EnergyBurnSettings.enabledKey)
                AdaptiveGoalSettings.markCheckedToday()
                wireUpFoodStoreCallback()
                wireUpHealthKit()
                // Seed the user's first WeightEntry from their onboarding-entered profile
                // weight. Used to be seeded in WeightStore.init with .default fallback,
                // which produced a 70 kg phantom entry for every fresh user.
                if let profile = UserProfile.load() {
                    weightStore.seedInitialWeightFromProfileIfEmpty(profile.weightKg)
                    // Same idea for body fat — only when the user actually
                    // entered a value during the onboarding body-fat step.
                    // Skipped when bodyFatPercentage is nil (the "No" branch).
                    if let bf = profile.bodyFatPercentage {
                        bodyFatStore.seedInitialBodyFatFromProfileIfEmpty(bf)
                    }
                }
                refreshWidgetSnapshot()
            }
        }
    }

    private func wireUpHealthKit() {
        guard UserDefaults.standard.bool(forKey: "healthKitEnabled") else { return }

        // Re-request authorization if new HealthKit types were added since last auth.
        // Backfill is idempotent (per-entry HealthKit existence check), so it's safe to call
        // in both branches without duplicating already-synced history.
        if healthKitManager.needsReauthorization {
            Task { [healthKitManager, foodStore] in
                _ = await healthKitManager.requestAuthorization()
                healthKitManager.backfillNutritionIfNeeded(
                    entries: foodStore.entries,
                    currentEntryIDs: { Set(foodStore.entries.map(\.id)) }
                )
                runBodyMeasurementBackfills()
            }
        } else {
            healthKitManager.backfillNutritionIfNeeded(
                entries: foodStore.entries,
                currentEntryIDs: { Set(foodStore.entries.map(\.id)) }
            )
            runBodyMeasurementBackfills()
        }

        healthKitManager.onImportedWorkoutsChanged = { [healthKitManager, importedHealthWorkoutStore] in
            healthKitManager.synchronizeImportedWorkoutsWithHealthKit { workouts, queryStart in
                importedHealthWorkoutStore.synchronize(with: workouts, queryStart: queryStart)
            }
        }

        healthKitManager.onBodyMeasurementsChanged = { [weightStore, bodyFatStore] weightKg, weightDate, weightOwnID, heightCm, bodyFat, bodyFatDate, bodyFatOwnID, dob, sex in
            guard var profile = UserProfile.load() else { return }
            var changed = false

            if let kg = weightKg, let date = weightDate {
                // If the HK sample was written by our app (has ayuvo_weight_id), never re-add
                // from the observer: either the entry still exists in the store (duplicate) or the
                // user just deleted it and the HK delete hasn't propagated yet (would resurrect it).
                // External HK samples (Apple Watch, scale, Health app) have no ayuvo_weight_id;
                // those we dedup by same-day + same-value.
                let shouldAdd: Bool
                if weightOwnID != nil {
                    shouldAdd = false
                } else {
                    let calendar = Calendar.current
                    let alreadyLogged = weightStore.entries.contains {
                        calendar.isDate($0.date, inSameDayAs: date) && abs($0.weightKg - kg) < 0.01
                    }
                    shouldAdd = !alreadyLogged
                }
                if shouldAdd {
                    weightStore.addEntry(WeightEntry(date: date, weightKg: kg))
                }
                // Only sync profile.weightKg from the HK observer when the latest sample came
                // from OUTSIDE our app. For our own samples, WeightStore.addEntry / deleteEntry
                // already syncs profile — updating it here again can revert a just-made edit if
                // HK hasn't indexed the write yet and returns an older sample of ours.
                if weightOwnID == nil, abs(profile.weightKg - kg) > 0.01 {
                    profile.weightKg = kg
                    changed = true
                }
            }
            if let cm = heightCm, abs(profile.heightCm - cm) > 0.1 {
                profile.heightCm = cm
                changed = true
            }
            if let bf = bodyFat, let date = bodyFatDate {
                // Same dedup discipline as weight: skip our own writes
                // (ayuvo_bodyfat_id present), and dedup external samples by
                // same-day + same-fraction so re-firing the observer can't
                // duplicate a smart-scale reading we already imported once.
                let shouldAdd: Bool
                if bodyFatOwnID != nil {
                    shouldAdd = false
                } else {
                    let calendar = Calendar.current
                    let alreadyLogged = bodyFatStore.entries.contains {
                        calendar.isDate($0.date, inSameDayAs: date) && abs($0.bodyFatFraction - bf) < 0.001
                    }
                    shouldAdd = !alreadyLogged
                }
                if shouldAdd {
                    bodyFatStore.addEntry(BodyFatEntry(date: date, bodyFatFraction: bf))
                    // BodyFatStore.addEntry already syncs profile.bodyFatPercentage
                    // for any new entry, so no extra profile.save() needed here.
                } else if bodyFatOwnID == nil,
                          profile.bodyFatPercentage == nil || abs((profile.bodyFatPercentage ?? 0) - bf) > 0.001 {
                    // External sample we already had (dedup hit) — but the
                    // profile cache somehow drifted. Realign without creating
                    // a duplicate entry. Skip when it's our own sample (HK
                    // may briefly return a stale write of ours during indexing).
                    profile.bodyFatPercentage = bf
                    changed = true
                }
            }
            if let d = dob {
                let calendar = Calendar.current
                if !calendar.isDate(profile.birthday, inSameDayAs: d) {
                    profile.birthday = d
                    changed = true
                }
            }
            if let s = sex {
                // Only sync when HealthKit gave us an actual male/female reading.
                // HKBiologicalSex.notSet (sim default + users who never set it
                // in Health) and .other should NOT overwrite the user's
                // onboarding-chosen gender — without this guard, the observer
                // silently flipped gender to "Other" right after onboarding
                // finished on the simulator since HK returns .notSet there.
                let mapped: Gender?
                switch s {
                case .male: mapped = .male
                case .female: mapped = .female
                default: mapped = nil
                }
                if let mapped, profile.gender != mapped {
                    profile.gender = mapped
                    changed = true
                }
            }
            if changed { profile.save() }
        }

        healthKitManager.startBodyMeasurementObserver()

        weightStore.onEntryAdded = { [healthKitManager] entry in
            healthKitManager.writeWeight(for: entry)
        }

        weightStore.onEntryDeleted = { [healthKitManager] entryID in
            healthKitManager.deleteWeight(entryID: entryID)
        }

        bodyFatStore.onEntryAdded = { [healthKitManager] entry in
            healthKitManager.writeBodyFat(for: entry)
        }

        bodyFatStore.onEntryDeleted = { [healthKitManager] entryID in
            healthKitManager.deleteBodyFat(entryID: entryID)
        }

        foodStore.onEntryAdded = { [healthKitManager] entry in
            healthKitManager.writeNutrition(for: entry)
        }

        foodStore.onEntryDeleted = { [healthKitManager] entryID in
            healthKitManager.deleteNutrition(entryID: entryID)
        }

        foodStore.onEntryUpdated = { [healthKitManager] entry in
            healthKitManager.updateNutrition(for: entry)
        }

        // Health Data hub: incremental anchored sync on every app open (5-minute throttle).
        healthDataStore.syncIfNeeded(.appOpen)
    }

    /// Debug / UI-test hook: `-ayuvoHealthFixture <file in Documents>` replaces the health
    /// mirror with a `ayuvo-health-data` archive so the hub can be exercised with bulk data on
    /// a simulator that has no Apple Health samples. Compiled out of release builds.
    private func importHealthFixtureIfRequested() async {
        #if DEBUG
        let arguments = CommandLine.arguments
        guard let index = arguments.firstIndex(of: "-ayuvoHealthFixture"), arguments.indices.contains(index + 1) else { return }
        let argument = arguments[index + 1]
        // Absolute paths are read as-is (a simulator process can read host files the UI test
        // points at); anything else is a file name inside this app's Documents.
        let url: URL
        if argument.hasPrefix("/") {
            url = URL(fileURLWithPath: argument)
        } else {
            guard let documents = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first else { return }
            url = documents.appendingPathComponent(argument)
        }
        guard FileManager.default.fileExists(atPath: url.path) else { return }
        do {
            let preview = try HealthImporter.preview(url: url)
            _ = try await healthDataStore.importHealthData(preview: preview, mode: .replaceAll)
        } catch {
            // Fixture problems only matter to the test run; the app carries on normally.
        }
        #endif
    }

    /// Debug / UI-test hook: `-ayuvoRecordsFixture <absolute path>[,<path>…]` imports those files
    /// through the regular Health Records importer (source `import`) so the Records tab can be
    /// exercised on a simulator without the Files picker. Compiled out of release builds.
    private func importRecordsFixtureIfRequested() async {
        #if DEBUG
        let arguments = CommandLine.arguments
        guard let index = arguments.firstIndex(of: "-ayuvoRecordsFixture"), arguments.indices.contains(index + 1) else { return }
        let urls = arguments[index + 1]
            .split(separator: ",")
            .map { URL(fileURLWithPath: String($0)) }
            .filter { FileManager.default.fileExists(atPath: $0.path) }
        // `-ayuvoRecordsReset` starts the fixture from an empty Health Records store.
        if arguments.contains("-ayuvoRecordsReset") { await recordsStore.deleteAllData() }
        guard !urls.isEmpty, await recordsStore.recordsCountForFixture() == 0 else { return }
        await recordsStore.importItems(urls.map {
            RecordImportItem(payload: .file($0), source: .import, importMethod: .filePicker, originalFilename: $0.lastPathComponent)
        }, announce: false)
        #endif
    }

    /// Debug / UI-test hook: `-ayuvoMedicationsFixture <absolute path>` merges an `ayuvo-medications`
    /// archive through the regular importer so the Meds pane can be exercised on a simulator without
    /// the Files picker. `-ayuvoMedicationsReset` wipes the medications store first. Compiled out of
    /// release builds.
    private func importMedicationsFixtureIfRequested() async {
        #if DEBUG
        let arguments = CommandLine.arguments
        if arguments.contains("-ayuvoMedicationsReset") { await medicationStore.deleteAllData() }
        guard let index = arguments.firstIndex(of: MedicationSettings.fixtureArgument), arguments.indices.contains(index + 1) else { return }
        let url = URL(fileURLWithPath: arguments[index + 1])
        guard let data = try? Data(contentsOf: url) else { return }
        _ = try? await medicationStore.importArchive(data)
        #endif
    }

    /// Debug / UI-test hook: `-ayuvoRecordsArchive <absolute path>` restores an `ayuvo-records`
    /// archive through the regular §35 importer (`-ayuvoRecordsArchiveMode replace` for Replace),
    /// so the export → Delete All Data → restore walk can run without the Files picker.
    /// Compiled out of release builds.
    private func importRecordsArchiveIfRequested() async {
        #if DEBUG
        let arguments = CommandLine.arguments
        guard let index = arguments.firstIndex(of: "-ayuvoRecordsArchive"), arguments.indices.contains(index + 1) else { return }
        let url = URL(fileURLWithPath: arguments[index + 1])
        guard FileManager.default.fileExists(atPath: url.path) else { return }
        var mode = RecordsImportMode.merge
        if let modeIndex = arguments.firstIndex(of: "-ayuvoRecordsArchiveMode"), arguments.indices.contains(modeIndex + 1),
           let parsed = RecordsImportMode(rawValue: arguments[modeIndex + 1]) {
            mode = parsed
        }
        _ = await recordsStore.importArchive(url: url, mode: mode) { _ in }
        #endif
    }

    /// Debug / UI-test hook: `-ayuvoRecordsArchiveOut <absolute path>` writes an `ayuvo-records`
    /// archive of the current store to that path (the §35 exporter), so a UI test can restore it
    /// in a later launch. Compiled out of release builds.
    private func exportRecordsArchiveIfRequested() async {
        #if DEBUG
        let arguments = CommandLine.arguments
        guard let index = arguments.firstIndex(of: "-ayuvoRecordsArchiveOut"), arguments.indices.contains(index + 1) else { return }
        let destination = URL(fileURLWithPath: arguments[index + 1])
        guard let repository = await recordsStore.openIfNeeded() else { return }
        try? FileManager.default.createDirectory(at: destination.deletingLastPathComponent(), withIntermediateDirectories: true)
        let exporter = RecordsArchiveExporter(database: repository.database, files: repository.files)
        _ = try? await exporter.export(to: destination, includeFiles: true, appVersion: RecordsStore.appVersionString)
        #endif
    }

    /// Pulls historical weight + body-fat samples out of HealthKit on first
    /// HK enable so the Progress chart starts populated for users who already
    /// have years of scale data in Apple Health (Withings, Renpho, Apple
    /// Watch, manual entries, etc.). One-shot per typesVersion — see
    /// HealthKitManager.{weight,bodyFat}BackfillVersionKey.
    private func runBodyMeasurementBackfills() {
        healthKitManager.backfillWeightFromHealthKitIfNeeded(
            existing: { [weightStore] in weightStore.entries },
            importBatch: { [weightStore] entries in weightStore.importExternalEntries(entries) }
        )
        healthKitManager.backfillBodyFatFromHealthKitIfNeeded(
            existing: { [bodyFatStore] in bodyFatStore.entries },
            importBatch: { [bodyFatStore] entries in bodyFatStore.importExternalEntries(entries) }
        )
        // Restore the food log from the app's own HK nutrition samples after a
        // reinstall / phone reset wiped the local store. The merge path fires
        // onEntriesChanged (widgets/notifications) but not onEntryAdded, so
        // restored entries are NOT re-written to HealthKit.
        healthKitManager.restoreFoodEntriesFromHealthKitIfNeeded(
            existingIDs: { [foodStore] in Set(foodStore.entries.map(\.id)) },
            importBatch: { [foodStore] entries in foodStore.mergeWithCloudEntries(entries) }
        )
        healthKitManager.synchronizeWorkoutBurnsWithHealthKit(
            existing: { [strengthWorkoutStore] in strengthWorkoutStore.workoutBurnSessions },
            mergeBatch: { [strengthWorkoutStore] sessions in
                strengthWorkoutStore.importWorkoutBurnSessions(sessions)
            }
        )
        healthKitManager.synchronizeImportedWorkoutsWithHealthKit { [importedHealthWorkoutStore] workouts, queryStart in
            importedHealthWorkoutStore.synchronize(with: workouts, queryStart: queryStart)
        }
    }

    private func wireUpFoodStoreCallback() {
        foodStore.onEntriesChanged = { [notificationManager, foodStore, weightStore, bodyFatStore] in
            if UserDefaults.standard.bool(forKey: "notificationsEnabled"),
               let profile = UserProfile.load() {
                notificationManager.rescheduleDataDependentNotifications(
                    foodStore: foodStore, weightStore: weightStore, bodyFatStore: bodyFatStore, profile: profile
                )
            }
            if let profile = UserProfile.load() {
                WidgetSnapshotWriter.publish(foods: foodStore.entries, profile: profile)
            }
        }
        waterStore.onEntriesChanged = { [foodStore] in
            guard let profile = UserProfile.load() else { return }
            WidgetSnapshotWriter.publish(foods: foodStore.entries, profile: profile)
        }
        fastingStore.onSessionsChanged = { [notificationManager, fastingStore] in
            let enabled = UserDefaults.standard.bool(forKey: "notificationsEnabled")
                && UserDefaults.standard.bool(forKey: FastingSettings.enabledKey)
                && UserDefaults.standard.bool(forKey: FastingSettings.notificationEnabledKey)
            notificationManager.scheduleFastingGoal(enabled: enabled, session: fastingStore.activeSession)
        }
        WatchSnapshotSync.shared.configureWaterLogging { [waterStore] request in
            guard UserDefaults.standard.bool(forKey: WaterSettings.enabledKey) else {
                return .disabled
            }
            let duplicate = waterStore.entries.contains(where: { $0.id == request.id })
            guard waterStore.add(
                id: request.id,
                milliliters: request.milliliters,
                on: request.date
            ) != nil else {
                return .invalid
            }
            return duplicate ? .duplicate : .added
        }
        // Install workout callbacks even when Health sync is currently off.
        // Writes honor the toggle, while deletion can still clean up a sample
        // exported before the user disabled Health sync.
        strengthWorkoutStore.onWorkoutBurnUpserted = { [healthKitManager] session in
            healthKitManager.updateWorkoutBurn(for: session)
        }
        strengthWorkoutStore.onWorkoutBurnDeleted = { [healthKitManager] sessionID in
            healthKitManager.deleteWorkoutBurn(sessionID: sessionID)
        }
    }

    private func refreshWidgetSnapshot() {
        guard let profile = UserProfile.load() else {
            // No profile — onboarding not complete OR data was wiped. Clear the
            // shared snapshot so the widget shows an empty day instead of stale
            // numbers from a previous profile.
            WidgetSnapshot.clear()
            WidgetCenter.shared.reloadAllTimelines()
            return
        }
        WidgetSnapshotWriter.publish(foods: foodStore.entries, profile: profile)
    }

    @MainActor
    private func refreshAdaptiveGoalsIfNeeded() {
        guard !isAutoRefreshingAdaptiveGoals else { return }
        guard AdaptiveGoalSettings.isEnabled else { return }
        guard AdaptiveGoalSettings.shouldCheckThisWeek() else { return }
        guard let profile = UserProfile.load() else { return }

        isAutoRefreshingAdaptiveGoals = true
        let healthOn = UserDefaults.standard.bool(forKey: "healthKitEnabled")
        let energyBurnOn = UserDefaults.standard.bool(forKey: EnergyBurnSettings.enabledKey)
        let heightMetric = HeightUnit.current == .cm
        let weightMetric = WeightUnit.current == .kg
        let weights = weightStore.entries
        let foods = foodStore.entries
        let bodyFatEntries = bodyFatStore.entries
        let workoutSessions = strengthWorkoutStore.completedSessions
        let bodyMeasurements = bodyMeasurementStore.entries
        Task {
            defer { Task { @MainActor in isAutoRefreshingAdaptiveGoals = false } }

            // Adaptive Goals = the same AI calculation the Recalculate button runs, on a weekly
            // timer. Energy Burn (when on) anchors maintenance to measured Apple Health burn.
            var healthEnergy = energyBurnOn && healthOn
                ? await healthKitManager.fetchRecentEnergyHistory(days: 14)
                : []
            let adaptiveStillOn = AdaptiveGoalSettings.isEnabled
            let healthStillOn = UserDefaults.standard.bool(forKey: "healthKitEnabled")
            let energyBurnStillOn = UserDefaults.standard.bool(forKey: EnergyBurnSettings.enabledKey)
            guard adaptiveStillOn,
                  healthStillOn == healthOn,
                  energyBurnStillOn == energyBurnOn
            else { return }
            // Defense in depth for the privacy boundary: if either source switch is off, no
            // fetched Health history can enter the evidence pack.
            if !healthStillOn || !energyBurnStillOn { healthEnergy = [] }
            let measuredTdee = HealthKitManager.energySummary(
                from: healthEnergy,
                requestedDays: 14
            ).map {
                $0.totalAverageCalories ?? (Int(profile.bmr.rounded()) + $0.activeAverageCalories)
            }
            let evidence = GoalEvidence.build(
                foods: foods,
                weights: weights,
                bodyFatEntries: bodyFatEntries,
                workoutSessions: workoutSessions,
                bodyMeasurements: bodyMeasurements,
                healthEnergy: healthEnergy,
                profile: profile
            )
            do {
                let result = try await GeminiService.calculateGoals(
                    profile: profile,
                    measuredTdee: measuredTdee,
                    measurement: bodyMeasurements.sorted { $0.date > $1.date }.first,
                    evidence: evidence,
                    heightMetric: heightMetric,
                    weightMetric: weightMetric
                )
                // The provider call can take seconds. Never overwrite profile/target edits the
                // user made while it was in flight; the next foreground can recalculate afresh.
                guard AdaptiveGoalSettings.isEnabled,
                      UserDefaults.standard.bool(forKey: "healthKitEnabled") == healthOn,
                      UserDefaults.standard.bool(forKey: EnergyBurnSettings.enabledKey) == energyBurnOn,
                      let latest = UserProfile.load(), latest == profile
                else { return }
                AdaptiveGoalSettings.savePreviousTargetsIfNeeded(from: latest)
                var next = latest
                next.customCalories = result.calories
                next.customProtein = result.protein
                next.customCarbs = result.carbs
                next.customFat = result.fat
                next.autoBalanceMacro = nil
                next.clearLocks()
                next.save()
                AdaptiveGoalSettings.markCheckedToday()
            } catch {
                // AI unavailable — keep existing goals; mark checked so we don't retry every open.
                if AdaptiveGoalSettings.isEnabled,
                   UserDefaults.standard.bool(forKey: "healthKitEnabled") == healthOn,
                   UserDefaults.standard.bool(forKey: EnergyBurnSettings.enabledKey) == energyBurnOn {
                    AdaptiveGoalSettings.markCheckedToday()
                }
            }
        }
    }
}
