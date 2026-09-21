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

// MARK: - Nutrition (food diary, pushed from Browse › Nutrition)
struct NutritionView: View {
    @Environment(AppNavigator.self) private var navigator
    private var quickActionRequest: QuickActionRequest? { navigator.quickActionRequest }
    private var foodLogMethodRequest: FoodLogMethodRequest? { navigator.foodLogMethodRequest }
    private func onQuickActionHandled(_ id: UUID) {
        if navigator.quickActionRequest?.id == id { navigator.quickActionRequest = nil }
    }
    private func onFoodLogMethodHandled(_ id: UUID) {
        if navigator.foodLogMethodRequest?.id == id { navigator.foodLogMethodRequest = nil }
    }
    @Environment(FoodStore.self) private var foodStore
    @Environment(WaterStore.self) private var waterStore
    @Environment(FastingStore.self) private var fastingStore
    @Environment(NotificationManager.self) private var notificationManager
    @Environment(HealthKitManager.self) private var healthKitManager
    @Environment(\.scenePhase) private var scenePhase
    @AppStorage("healthKitEnabled") private var healthKitEnabled = false
    @State private var dailySteps: Int?
    @State private var dailyStepsFetchGeneration = 0
    @State private var showCamera = false
    @State private var showBarcodeScanner = false
    @State private var capturedImage: UIImage?
    @State private var cameraMode: CameraMode = .snapFood
    @State private var selectedPhotoItems: [PhotosPickerItem] = []
    @State private var showPhotoPicker = false
    @State private var showError = false
    @State private var errorMessage = ""
    private enum RetryRequest {
        case analysis(images: [UIImage], mode: CameraMode, description: String?, progressiveMeal: Bool)
        case text(String)
        case barcode(String)
    }
    @State private var retryRequest: RetryRequest?
    @State private var selectedDate: Date = .now
    @State private var showVoicePopover = false
    @State private var showTextPopover = false
    @State private var showManualPopover = false
    @State private var showSiriPhrases = false
    @State private var savedMealsMode: SavedMealsMode?
    @State private var showCopyFromDaySheet = false
    @State private var pendingContextImage: UIImage?
    @State private var captureImages: [UIImage] = []
    @State private var isImportingPhotos = false
    @State private var showMultiPhotoCaptureSheet = false
    @State private var contextDescription: String = ""
    @State private var showContextSheet = false

    enum ActiveSheet: String, Identifiable {
        case analyzing, foodResult, analyzingText, lookingUpBarcode, editFood
        var id: String { rawValue }
    }
    @State private var activeSheet: ActiveSheet?
    @State private var editingEntry: FoodEntry?
    @State private var pendingDiaryDeletion: DiaryDeletion?

    private enum DiaryDeletion {
        case food(FoodEntry), water(WaterEntry), fasting(FastingSession)

        var title: String {
            switch self {
            case .food: return "Delete Food Log?"
            case .water: return "Delete Water Log?"
            case .fasting: return "Delete Fasting Log?"
            }
        }

        var message: String {
            switch self {
            case .food: return "This removes the food from your diary. Saved favorites are kept."
            case .water: return "This removes the water entry from your diary."
            case .fasting: return "This removes the completed fast from your diary."
            }
        }
    }
    @State private var selectedFoodIDs: Set<UUID> = []
    private var isDiaryDeletionPresented: Binding<Bool> {
        Binding<Bool>(
            get: { pendingDiaryDeletion != nil },
            set: { presented in
                if !presented { pendingDiaryDeletion = nil }
            }
        )
    }

    private func confirmDiaryDeletion(_ target: DiaryDeletion) {
        switch target {
        case .food(let entry): foodStore.deleteEntry(entry)
        case .water(let entry): waterStore.delete(id: entry.id)
        case .fasting(let session):
            fastingStore.delete(id: session.id)
            refreshFastingGoalNotification()
        }
        pendingDiaryDeletion = nil
    }

    private var isFoodSelectionMode: Bool { !selectedFoodIDs.isEmpty }
    private var foodSelectionSummary: some View {
        HStack(spacing: 8) {
            Button {
                selectedFoodIDs.removeAll()
            } label: {
                Image(systemName: "xmark")
                    .font(.system(.subheadline, design: .rounded, weight: .semibold))
                    .frame(width: 44, height: 44)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .foregroundStyle(.secondary)
            .accessibilityLabel("Cancel selection")

            Text("\(selectedFoodIDs.count) selected")
                .font(.system(.subheadline, design: .rounded, weight: .semibold))
                .fixedSize(horizontal: true, vertical: false)
                .frame(maxWidth: .infinity, alignment: .leading)
        }
    }

    private var combineFoodButton: some View {
        Button {
            let ids = selectedFoodIDs
            if let combined = foodStore.combineIntoMeal(ids: ids) {
                selectedFoodIDs.removeAll()
                editingEntry = combined
                activeSheet = .editFood
            } else {
                selectedFoodIDs.removeAll()
                errorMessage = "Can't combine foods while fasting is active."
                showError = true
            }
        } label: {
            Text("Combine")
                .font(.system(.subheadline, design: .rounded, weight: .semibold))
                .fixedSize()
                .padding(.horizontal, 16)
                .frame(minHeight: 44)
                .foregroundStyle(AppColors.calorie.opacity(selectedFoodIDs.count < 2 ? 0.45 : 1))
                .background(AppColors.calorie.opacity(0.12), in: Capsule())
        }
        .buttonStyle(.plain)
        .disabled(selectedFoodIDs.count < 2)
        .accessibilityLabel("Combine into Meal")
    }

    @State private var currentFoodResult: GeminiService.FoodAnalysis?
    @State private var currentImage: UIImage?
    @State private var currentImages: [UIImage] = []
    @State private var currentEmoji: String?
    @State private var currentFoodSource: FoodSource = .snapFood
    @State private var showNutritionDetail = false
    @State private var showCustomWaterLog = false
    @State private var showFastingStart = false
    @State private var editingFastingSession: FastingSession?
    @State private var showFastingQuickActionDisabled = false
    @State private var showFoodLoggingBlocked = false
    @State private var hasPresentedFoodDestination = false
    @State private var didPrewarmFoodDestinations = false
    // Bumped each time the app is opened (cold launch = 1, then +1 on every
    // return from background). Drives the gauge + macro "fill from zero" reveal.
    // Not bumped on tab switches or data edits, so it only plays on app open.
    @State private var launchFillEpoch = 1
    @State private var wasBackgrounded = false
    @AppStorage("weightUnit") private var weightUnitRaw = "lbs"
    @AppStorage(FoodLogSortOrder.storageKey) private var foodLogSortOrderRaw = FoodLogSortOrder.defaultOrder.rawValue
    @AppStorage(HomeTopNutrient.storageKey) private var homeTopNutrientsRaw = HomeTopNutrient.storageValue(for: HomeTopNutrient.defaultSelection)
    @AppStorage(OptionalNutrientGoals.storageKey) private var optionalNutrientGoalsData = Data()
    @AppStorage(WaterSettings.enabledKey) private var waterTrackingEnabled = false
    @AppStorage(WaterSettings.dailyGoalKey) private var waterDailyGoal = WaterSettings.defaultDailyGoalMl
    @AppStorage(WaterSettings.unitKey) private var waterUnitRaw = WaterUnit.defaultUnit.rawValue
    @AppStorage(FastingSettings.enabledKey) private var fastingTrackingEnabled = false
    @AppStorage(FastingSettings.defaultGoalMinutesKey) private var fastingDefaultGoalMinutes = FastingSettings.defaultGoalMinutes
    @AppStorage(FastingSettings.notificationEnabledKey) private var fastingGoalNotificationEnabled = true
    @AppStorage("notificationsEnabled") private var notificationsEnabled = false
    @Environment(ProfileStore.self) private var profileStore
    @State private var homeBurnLine: String?
    @State private var homeBurnRefreshGeneration = 0

    /// Force a body re-evaluation whenever profileStore.profile changes by reading it
    /// at the top of body. SwiftUI's @Observable tracking sometimes misses the access
    /// when the read is buried in a computed property; explicit access guarantees it.
    private var userProfile: UserProfile { profileStore.profile }
    private var calorieGoal: Int { userProfile.effectiveCalories }
    private var proteinGoal: Int { userProfile.effectiveProtein }
    private var carbsGoal: Int { userProfile.effectiveCarbs }
    private var fatGoal: Int { userProfile.effectiveFat }
    private var selectedCalories: Int { foodStore.calories(for: selectedDate) }
    private var isToday: Bool { Calendar.current.isDateInToday(selectedDate) }
    private var foodLogSortOrder: FoodLogSortOrder { FoodLogSortOrder.order(for: foodLogSortOrderRaw) }
    private var homeTopNutrients: [HomeTopNutrient] { HomeTopNutrient.selection(from: homeTopNutrientsRaw) }
    private var displayedHomeNutrients: [HomeTopNutrient] {
        waterTrackingEnabled ? Array(homeTopNutrients.prefix(3)) : homeTopNutrients
    }
    private var optionalNutrientGoals: OptionalNutrientGoals { OptionalNutrientGoals.decoded(from: optionalNutrientGoalsData) }
    private var waterUnit: WaterUnit { WaterUnit(rawValue: waterUnitRaw) ?? .defaultUnit }
    private var waterPillarUnit: String { waterUnit == .fluidOunces ? " fl oz" : "ml" }
    private var logDateForSelectedDay: Date { logDate(on: selectedDate) }

    private var navigationTitle: String {
        if isToday { return "Today" }
        return selectedDate.formatted(.dateTime.weekday(.wide).month(.abbreviated).day())
    }

    /// Horizontal swipe → previous/next day. Attached only to the top section (calorie hero +
    /// macros), not the food log below "View More", so it never competes with the food rows'
    /// own swipe actions or vertical scrolling there. `.simultaneousGesture` lets the List still
    /// scroll; we act only on a clearly horizontal flick.
    private var daySwipeGesture: some Gesture {
        DragGesture(minimumDistance: 24)
            .onEnded { value in
                let dx = value.translation.width
                let dy = value.translation.height
                guard abs(dx) > 60, abs(dx) > abs(dy) * 1.5 else { return }
                changeDay(by: dx < 0 ? 1 : -1)
            }
    }

    /// Step the selected day by `delta` (−1 previous, +1 next), from the swipe gesture. Won't move
    /// past today, and gives a light haptic on a successful change. Animates with the existing
    /// `.animation(.snappy, value: selectedDate)` on the List.
    private func changeDay(by delta: Int) {
        let calendar = Calendar.current
        guard let newDate = calendar.date(byAdding: .day, value: delta, to: selectedDate) else { return }
        if delta > 0 && calendar.startOfDay(for: newDate) > calendar.startOfDay(for: .now) { return }
        UIImpactFeedbackGenerator(style: .light).impactOccurred()
        selectedDate = newDate
    }

private var dailyStepsTaskKey: String {
        "\(selectedDate.timeIntervalSince1970)-\(healthKitEnabled)"
    }

    private func refreshDailySteps() async {
        guard healthKitEnabled else {
            dailySteps = nil
            return
        }
        let requestedDate = selectedDate
        dailyStepsFetchGeneration += 1
        let generation = dailyStepsFetchGeneration
        guard !Task.isCancelled else { return }
        let steps = await healthKitManager.fetchStepsForDay(requestedDate)
        guard !Task.isCancelled, healthKitEnabled else { return }
        guard generation == dailyStepsFetchGeneration else { return }
        guard Calendar.current.isDate(selectedDate, inSameDayAs: requestedDate) else { return }
        dailySteps = steps
    }

    private var homeBurnRefreshToken: String {
        let profileBmr = Int(userProfile.bmr.rounded())
        return "\(healthKitEnabled)-\(selectedDate.timeIntervalSince1970)-\(selectedCalories)-\(profileBmr)-\(homeBurnRefreshGeneration)"
    }

    private func formattedHomeBurnLine(from balance: DailyCalorieBalance) -> String {
        let burned = balance.burnedCalories.formatted()
        switch balance.direction {
        case .deficit:
            return String(
                format: String(localized: "%@ burned · %@ deficit"),
                burned,
                balance.differenceCalories.formatted()
            )
        case .surplus:
            return String(
                format: String(localized: "%@ burned · %@ surplus"),
                burned,
                balance.differenceCalories.formatted()
            )
        case .balanced:
            return String(format: String(localized: "%@ burned · balanced"), burned)
        }
    }

    private func refreshHomeBurnLine() async {
        let requestDate = selectedDate
        let requestCalories = selectedCalories
        let requestBmr = Int(userProfile.bmr.rounded())
        let requestHealthEnabled = healthKitEnabled

        func inputsStillMatch() -> Bool {
            healthKitEnabled == requestHealthEnabled
                && Calendar.current.isDate(selectedDate, inSameDayAs: requestDate)
                && selectedCalories == requestCalories
                && Int(userProfile.bmr.rounded()) == requestBmr
        }

        guard requestHealthEnabled else {
            homeBurnLine = nil
            return
        }
        guard let energy = await healthKitManager.readEnergyForDay(requestDate) else {
            guard !Task.isCancelled, inputsStillMatch() else { return }
            homeBurnLine = nil
            return
        }
        guard !Task.isCancelled, inputsStillMatch() else { return }
        guard let burned = DailySummaryPolicy.resolveBurnedCalories(
            measuredTotalCalories: energy.totalCalories,
            externalActiveCalories: energy.activeCalories,
            profileBmrCalories: requestBmr
        ) else {
            guard inputsStillMatch() else { return }
            homeBurnLine = nil
            return
        }
        guard inputsStillMatch() else { return }
        let balance = DailySummaryPolicy.balance(
            eatenCalories: requestCalories,
            burnedCalories: burned
        )
        homeBurnLine = formattedHomeBurnLine(from: balance)
    }

    private func logDate(on day: Date, now: Date = .now) -> Date {
        let calendar = Calendar.current
        if calendar.isDateInToday(day) { return now }

        let dayComponents = calendar.dateComponents([.year, .month, .day], from: day)
        let timeComponents = calendar.dateComponents([.hour, .minute, .second, .nanosecond], from: now)
        var components = DateComponents()
        components.year = dayComponents.year
        components.month = dayComponents.month
        components.day = dayComponents.day
        components.hour = timeComponents.hour
        components.minute = timeComponents.minute
        components.second = timeComponents.second
        components.nanosecond = timeComponents.nanosecond
        return calendar.date(from: components) ?? day
    }

    private func logWater(_ milliliters: Int) {
        _ = waterStore.add(milliliters: milliliters, on: logDateForSelectedDay)
        UIImpactFeedbackGenerator(style: .light).impactOccurred()
    }

    private func refreshFastingGoalNotification() {
        notificationManager.scheduleFastingGoal(
            enabled: notificationsEnabled && fastingTrackingEnabled && fastingGoalNotificationEnabled,
            session: fastingStore.activeSession
        )
    }

    private func startFast(goalMinutes: Int) {
        guard fastingStore.start(goalMinutes: goalMinutes) != nil else { return }
        refreshFastingGoalNotification()
        UIImpactFeedbackGenerator(style: .medium).impactOccurred()
    }

    @discardableResult
    private func endFast() -> Bool {
        guard fastingStore.endActive() != nil else { return false }
        notificationManager.cancelFastingGoal()
        UIImpactFeedbackGenerator(style: .medium).impactOccurred()
        return true
    }

    @discardableResult
    private func canBeginFoodLogging() -> Bool {
        guard fastingStore.activeSession == nil else {
            showFoodLoggingBlocked = true
            return false
        }
        return true
    }

    /// Lets the native menu finish its selection before presenting the chosen destination.
    /// The first presentation skips animation so cold setup cannot stretch the handoff;
    /// later selections retain the short transition that already feels responsive.
    private func presentFoodDestination(_ updates: @escaping () -> Void) {
        let shouldAnimate = hasPresentedFoodDestination
        hasPresentedFoodDestination = true

        DispatchQueue.main.async {
            var transaction = Transaction(animation: shouldAnimate ? .easeOut(duration: 0.16) : nil)
            transaction.disablesAnimations = !shouldAnimate
            withTransaction(transaction) {
                updates()
            }
        }
    }

    /// Loads the native menu and media authorization code paths while Home is idle.
    /// Reading authorization status never prompts the user or starts camera/microphone capture.
    private func prewarmFoodDestinations() {
        guard !didPrewarmFoodDestinations else { return }
        didPrewarmFoodDestinations = true

        let placeholderAction = UIAction(title: "") { _ in }
        let placeholderSubmenu = UIMenu(title: "", children: [placeholderAction])
        _ = UIMenu(title: "", children: [placeholderSubmenu])

        Task.detached(priority: .utility) {
            _ = PHPhotoLibrary.authorizationStatus(for: .readWrite)
            _ = AVCaptureDevice.authorizationStatus(for: .video)
            _ = SFSpeechRecognizer.authorizationStatus()
        }
    }

    @ViewBuilder
    private var waterQuickMenuItems: some View {
        Button {
            presentFoodDestination {
                showCustomWaterLog = true
            }
        } label: {
            Label("Custom", systemImage: "slider.horizontal.3")
        }
        Button {
            logWater(750)
        } label: {
            Label("3 Glasses (~\(waterUnit.formatted(milliliters: 750)))", systemImage: "drop.fill")
        }
        Button {
            logWater(500)
        } label: {
            Label("2 Glasses (~\(waterUnit.formatted(milliliters: 500)))", systemImage: "drop.fill")
        }
        Button {
            logWater(250)
        } label: {
            Label("1 Glass (~\(waterUnit.formatted(milliliters: 250)))", systemImage: "drop.fill")
        }
    }

    /// Links to the nutrition metric details (docs/ui-structure.md §2 "Nutrition page").
    private var trendsSection: some View {
        Section("Trends") {
            ForEach([AppMetric.calories, .protein, .carbs, .fat, .fiber], id: \.self) { metric in
                let descriptor = MetricCatalog.descriptor(for: .app(metric))
                NavigationLink(value: MetricRoute.detail(.app(metric))) {
                    MetricRow(systemImage: descriptor.systemImage, tint: descriptor.tint, title: descriptor.title)
                }
                .accessibilityIdentifier("browse.metric.\(metric.key)")
            }
            NavigationLink(value: HealthRoute.category(.nutrition)) {
                MetricRow(systemImage: "heart.text.square", tint: AyuvoPalette.nutrition, title: String(localized: "All Apple Health Nutrition"))
            }
        }
        .listRowBackground(AppColors.appCard)
    }

    var body: some View {
        homeContent
            .alert(pendingDiaryDeletion?.title ?? "Delete Entry?", isPresented: isDiaryDeletionPresented, presenting: pendingDiaryDeletion) { target in
                Button("Cancel", role: .cancel) { pendingDiaryDeletion = nil }
                Button("Delete", role: .destructive) {
                    confirmDiaryDeletion(target)
                }
            } message: { target in
                Text(target.message)
            }
    }

    private var homeContent: some View {
        // Explicit observation tracking — reads profileStore.profile at body root
        // so SwiftUI invalidates this view on every profile mutation.
        let _ = profileStore.profile
        // Pushed on the Browse stack: no NavigationStack of its own.
        return Group {
            List {
                // Week energy strip
                Section {
                    WeekEnergyStrip(
                        selectedDate: $selectedDate,
                        caloriesForDate: { foodStore.calories(for: $0) },
                        calorieGoal: calorieGoal
                    )
                    .listRowBackground(Color.clear)
                    .listRowSeparator(.hidden)
                    .listRowInsets(EdgeInsets(top: 0, leading: 16, bottom: 0, trailing: 16))
                }

                // Today's fast comes before nutrition — Health Data and Workouts moved to
                // their own Health tab segments, so the Food tab stays food-only.
                Section {
                    if isToday, fastingTrackingEnabled {
                        if let activeFast = fastingStore.activeSession {
                            Button {
                                editingFastingSession = activeFast
                            } label: {
                                ActiveFastingRow(session: activeFast)
                            }
                            .buttonStyle(.plain)
                            .listRowBackground(Color.clear)
                            .listRowSeparator(.hidden)
                        } else {
                            Button {
                                presentFoodDestination {
                                    showFastingStart = true
                                }
                            } label: {
                                HStack {
                                    Label("Start Fast", systemImage: "timer")
                                        .font(.system(.subheadline, design: .rounded, weight: .semibold))
                                    Spacer()
                                    Image(systemName: "chevron.right")
                                        .font(.caption2)
                                        .foregroundStyle(.tertiary)
                                }
                                .foregroundStyle(AppColors.calorie)
                                .padding(.vertical, 6)
                            }
                            .buttonStyle(.plain)
                            .listRowBackground(Color.clear)
                            .listRowSeparator(.hidden)
                        }
                    }
                }

                // Nutrition summary. Keeping the dome, macros, water and detail affordance in
                // one section removes an unhelpful List section gap and matches Android's
                // compact top-region hierarchy.
                Section {
                    NutritionCalorieHeader(
                        eaten: selectedCalories,
                        goal: calorieGoal,
                        burnLine: homeBurnLine
                    )
                        .listRowInsets(EdgeInsets(top: 4, leading: 0, bottom: 4, trailing: 0))
                        .contentShape(Rectangle())
                        .simultaneousGesture(daySwipeGesture)
                        .listRowBackground(Color.clear)
                        .listRowSeparator(.hidden)
                        .task(id: homeBurnRefreshToken) {
                            await refreshHomeBurnLine()
                        }

                    HStack(alignment: .top, spacing: 4) {
                        ForEach(displayedHomeNutrients) { nutrient in
                            MacroVerticalBar(
                                label: nutrient.displayName,
                                current: nutrient.value(from: foodStore, on: selectedDate),
                                goal: nutrient.goal(for: userProfile, optionalGoals: optionalNutrientGoals),
                                unit: nutrient.unit,
                                gradient: nutrient.gradientColors,
                                launchFillEpoch: launchFillEpoch
                            )
                        }
                        if waterTrackingEnabled {
                            MacroVerticalBar(
                                label: "Water",
                                current: waterUnit.displayAmount(
                                    forMilliliters: waterStore.total(on: selectedDate)
                                ),
                                goal: waterUnit.displayAmount(forMilliliters: waterDailyGoal),
                                unit: waterPillarUnit,
                                gradient: [AyuvoPalette.hydration, AyuvoPalette.hydration],
                                launchFillEpoch: launchFillEpoch
                            )
                        }
                    }
                    .padding(.vertical, 4)
                    .contentShape(Rectangle())
                    .simultaneousGesture(daySwipeGesture)
                    .listRowBackground(Color.clear)
                    .listRowSeparator(.hidden)

                    Button {
                        showNutritionDetail = true
                    } label: {
                        HStack {
                            Spacer()
                            Text("View More")
                                .font(.system(.subheadline, design: .rounded, weight: .medium))
                            Image(systemName: "chevron.right")
                                .font(.caption2)
                            Spacer()
                        }
                        .foregroundStyle(AppColors.calorie.opacity(0.6))
                    }
                    .listRowBackground(Color.clear)
                    .listRowSeparator(.hidden)
                }

                // Unified diary: water and fasting are grouped by their log/end time,
                // but stay excluded from food calories, macros, sharing and favorites.
                // Tracking preferences control new-entry UI, not persisted history.
                // Keep previously logged water and fasting sessions visible after
                // either tracker is disabled so the diary never appears to lose data.
                // The active fast is pinned above the nutrition summary, so the diary lists
                // completed sessions only.
                let diaryFasts = fastingStore.completed(on: selectedDate)
                let mealGroups = homeDiaryMealGroups(
                    foodEntries: foodStore.entries(for: selectedDate),
                    waterEntries: waterStore.entries(on: selectedDate),
                    fastingSessions: diaryFasts,
                    order: foodLogSortOrder
                )
                if mealGroups.isEmpty {
                    Section(isToday ? "Today's Diary" : "Diary") {
                        Text("No diary entries")
                            .foregroundStyle(.secondary)
                            .listRowBackground(AppColors.appCard)
                    }
                } else {
                    ForEach(mealGroups) { group in
                        Section {
                            ForEach(group.items) { item in
                                Group {
                                    switch item {
                                    case .food(let entry):
                                        HStack(spacing: 12) {
                                            if isFoodSelectionMode {
                                                Image(systemName: selectedFoodIDs.contains(entry.id) ? "checkmark.circle.fill" : "circle")
                                                    .font(.title2)
                                                    .foregroundStyle(selectedFoodIDs.contains(entry.id) ? AppColors.calorie : .secondary)
                                            }
                                            FoodRow(entry: entry)
                                        }
                                        .contentShape(Rectangle())
                                        .onTapGesture {
                                            if isFoodSelectionMode {
                                                if selectedFoodIDs.contains(entry.id) {
                                                    selectedFoodIDs.remove(entry.id)
                                                } else {
                                                    selectedFoodIDs.insert(entry.id)
                                                }
                                            } else {
                                                editingEntry = entry
                                                activeSheet = .editFood
                                            }
                                        }
                                        .onLongPressGesture {
                                            selectedFoodIDs.insert(entry.id)
                                        }
                                        .swipeActions(edge: .trailing, allowsFullSwipe: true) {
                                            if !isFoodSelectionMode {
                                                Button {
                                                    pendingDiaryDeletion = .food(entry)
                                                } label: {
                                                    Label("Delete", systemImage: "trash.fill")
                                                }
                                                .tint(.red)
                                                Button {
                                                    foodStore.toggleFavorite(entry)
                                                } label: {
                                                    Label(foodStore.isFavorite(entry) ? "Unfavorite" : "Favorite", systemImage: foodStore.isFavorite(entry) ? "heart.slash.fill" : "heart.fill")
                                                }
                                                .tint(AppColors.calorie)
                                            }
                                        }
                                    case .water(let entry):
                                        WaterLogRow(entry: entry, unit: waterUnit)
                                            .swipeActions(edge: .trailing, allowsFullSwipe: true) {
                                                Button {
                                                    pendingDiaryDeletion = .water(entry)
                                                } label: {
                                                    Label("Delete", systemImage: "trash.fill")
                                                }
                                                .tint(.red)
                                            }
                                    case .fasting(let session):
                                        Button {
                                            editingFastingSession = session
                                        } label: {
                                            if session.isActive {
                                                ActiveFastingRow(session: session)
                                            } else {
                                                CompletedFastingRow(session: session)
                                            }
                                        }
                                        .buttonStyle(.plain)
                                        .swipeActions(edge: .trailing, allowsFullSwipe: !session.isActive) {
                                            if session.isActive {
                                                Button {
                                                    endFast()
                                                } label: {
                                                    Label("End Fast", systemImage: "stop.fill")
                                                }
                                                .tint(AppColors.calorie)
                                                Button(role: .destructive) {
                                                    fastingStore.cancelActive()
                                                    notificationManager.cancelFastingGoal()
                                                } label: {
                                                    Label("Cancel Fast", systemImage: "trash.fill")
                                                }
                                            } else {
                                                Button {
                                                    pendingDiaryDeletion = .fasting(session)
                                                } label: {
                                                    Label("Delete", systemImage: "trash.fill")
                                                }
                                                .tint(.red)
                                            }
                                        }
                                    }
                                }
                                .listRowBackground(AppColors.appCard)
                            }
                        } header: {
                            HStack(alignment: .center) {
                                Label(group.meal.displayName, systemImage: group.meal.icon)
                                if group.id == mealGroups.first?.id {
                                    Menu {
                                        Picker("Food Log Order", selection: $foodLogSortOrderRaw) {
                                            ForEach(FoodLogSortOrder.allCases) { order in
                                                Text(order.displayName).tag(order.rawValue)
                                            }
                                        }
                                    } label: {
                                        HStack(spacing: 6) {
                                            Image(systemName: "arrow.up.arrow.down")
                                                .font(.system(.caption2, design: .rounded, weight: .semibold))
                                            Text("Sort")
                                                .font(.system(.subheadline, design: .rounded, weight: .semibold))
                                        }
                                    }
                                    .tint(AppColors.calorie)
                                    .textCase(nil)
                                    .padding(.leading, 8)
                                }
                                Spacer()
                                if !group.foodEntries.isEmpty {
                                    // Share and totals include food only; water and fasting have no calories/macros.
                                    Button {
                                        MealShare.presentShareSheet(for: group.foodEntries)
                                    } label: {
                                        Image(systemName: "square.and.arrow.up")
                                            .font(.system(.subheadline, design: .rounded, weight: .semibold))
                                            .foregroundStyle(AppColors.calorie)
                                    }
                                    .buttonStyle(.plain)
                                    .padding(.trailing, 12)
                                    .textCase(nil)
                                    VStack(alignment: .trailing, spacing: 1) {
                                        Text("\(group.totalCalories.formatted()) kcal")
                                            .font(.system(.subheadline, design: .rounded, weight: .semibold))
                                            .foregroundStyle(AppColors.calorie)
                                        Text("\(Int(group.totalProtein.rounded()))P · \(Int(group.totalCarbs.rounded()))C · \(Int(group.totalFat.rounded()))F")
                                            .font(.system(.caption2, design: .rounded, weight: .medium))
                                            .foregroundStyle(.secondary)
                                    }
                                    .textCase(nil)
                                }
                            }
                        }
                    }
                }

                trendsSection
            }
            .scrollContentBackground(.hidden)
            .background(AppColors.appBackground)
            .animation(.snappy, value: selectedDate)
            .task(id: dailyStepsTaskKey) {
                await refreshDailySteps()
            }
            .contentMargins(.bottom, isFoodSelectionMode ? 8 : 96, for: .scrollContent)
            .sensoryFeedback(.selection, trigger: selectedFoodIDs) { _, selection in
                !selection.isEmpty
            }
            .safeAreaInset(edge: .bottom, spacing: 0) {
                if isFoodSelectionMode {
                    ViewThatFits(in: .horizontal) {
                        HStack(spacing: 12) {
                            foodSelectionSummary
                            combineFoodButton
                        }
                        VStack(alignment: .leading, spacing: 8) {
                            foodSelectionSummary
                            combineFoodButton
                        }
                    }
                    .padding(.horizontal, 12)
                    .padding(.vertical, 8)
                    .background(AppColors.appCard, in: RoundedRectangle(cornerRadius: 20))
                    .padding(.horizontal, 16)
                    .padding(.vertical, 8)
                    .background(AppColors.appBackground)
                }
            }
            .navigationTitle(navigationTitle)
            .navigationBarTitleDisplayMode(.inline)
            .overlay(alignment: .bottomTrailing) {
                Menu {
                    if fastingTrackingEnabled {
                        Section {
                            if fastingStore.activeSession != nil {
                                Menu {
                                    Button {
                                        endFast()
                                    } label: {
                                        Label("End Fast", systemImage: "stop.fill")
                                    }
                                    Button(role: .destructive) {
                                        fastingStore.cancelActive()
                                        notificationManager.cancelFastingGoal()
                                    } label: {
                                        Label("Cancel Fast", systemImage: "trash")
                                    }
                                } label: {
                                    Label("Fasting", systemImage: "timer")
                                }
                            } else {
                                Button {
                                    presentFoodDestination {
                                        showFastingStart = true
                                    }
                                } label: {
                                    Label("Start Fast", systemImage: "timer")
                                }
                            }
                        }
                    }
                    if waterTrackingEnabled {
                        Section {
                            Menu {
                                waterQuickMenuItems
                            } label: {
                                Label("Water", systemImage: "drop.fill")
                            }
                        }
                    }
                    if fastingStore.activeSession == nil {
                        configuredFoodAddMenuContent
                    }
                } label: {
                            Image(systemName: "plus")
                                .font(.system(size: 26, weight: .semibold))
                                .foregroundStyle(.white)
                                .frame(width: 60, height: 60)
                                .background(AppColors.calorie, in: Circle())
                        }
                        .accessibilityIdentifier("home.add")
                        .opacity(isFoodSelectionMode ? 0 : 1)
                        .disabled(isFoodSelectionMode)
                        .allowsHitTesting(!isFoodSelectionMode)
                        .popover(isPresented: $showTextPopover) {
                            TextFoodInputView(
                                onCancel: {
                                    showTextPopover = false
                                },
                                onSubmit: { description in
                                    showTextPopover = false
                                    currentImage = nil
                                    currentImages = []
                                    currentEmoji = nil
                                    currentFoodSource = .textInput
                                    startTextAnalysis(description)
                                }
                            )
                            .presentationCompactAdaptation(.popover)
                        }
                        .popover(isPresented: $showVoicePopover) {
                            VoiceInputView(
                                onCancel: {
                                    showVoicePopover = false
                                },
                                onSubmit: { description in
                                    showVoicePopover = false
                                    currentImage = nil
                                    currentImages = []
                                    currentEmoji = nil
                                    currentFoodSource = .textInput
                                    startTextAnalysis(description)
                                }
                            )
                            .presentationCompactAdaptation(.popover)
                        }
                        .popover(isPresented: $showManualPopover) {
                            ManualEntryView(
                                logDate: logDateForSelectedDay,
                                onCancel: { showManualPopover = false },
                                onSave: { entry in
                                    showManualPopover = false
                                    if !foodStore.addEntry(entry) { showFoodLoggingBlocked = true }
                                }
                            )
                            .presentationCompactAdaptation(.popover)
                        }
                        .padding(24)
            }
            .fullScreenCover(isPresented: $showCamera) {
                CameraView(
                    image: $capturedImage,
                    title: captureImages.isEmpty ? nil : "Photo \(captureImages.count + 1)",
                    onCancel: {
                        if !captureImages.isEmpty {
                            DispatchQueue.main.asyncAfter(deadline: .now() + 0.25) {
                                showMultiPhotoCaptureSheet = true
                            }
                        }
                    }
                )
                    .ignoresSafeArea()
            }
            .fullScreenCover(isPresented: $showBarcodeScanner) {
                BarcodeScannerView(
                    onScan: { barcode in
                        showBarcodeScanner = false
                        startBarcodeLookup(barcode)
                    },
                    onCancel: {
                        showBarcodeScanner = false
                    }
                )
                .ignoresSafeArea()
            }
            .onChange(of: capturedImage) { oldValue, newValue in
                guard let image = newValue else { return }
                capturedImage = nil
                currentEmoji = nil

                captureImages.append(image)
                DispatchQueue.main.asyncAfter(deadline: .now() + 0.25) {
                    showMultiPhotoCaptureSheet = true
                }
            }
            .sheet(isPresented: $showMultiPhotoCaptureSheet) {
                MultiPhotoCaptureSheet(
                    images: $captureImages,
                    isImportingPhotos: isImportingPhotos,
                    selectedPhotoItems: $selectedPhotoItems,
                    description: $contextDescription,
                    onAddPhoto: {
                        guard captureImages.count < 10 else { return }
                        showMultiPhotoCaptureSheet = false
                        DispatchQueue.main.asyncAfter(deadline: .now() + 0.35) {
                            showCamera = true
                        }
                    },
                    onRemove: { index in
                        guard captureImages.indices.contains(index) else { return }
                        captureImages.remove(at: index)
                        if captureImages.isEmpty {
                            showMultiPhotoCaptureSheet = false
                        }
                    },
                    onAnalyze: { progressiveMeal in
                        let images = captureImages
                        let description = cameraMode == .snapFoodWithContext ? contextDescription : nil
                        showMultiPhotoCaptureSheet = false
                        captureImages = []
                        currentImages = images
                        currentImage = images.first
                        DispatchQueue.main.asyncAfter(deadline: .now() + 0.4) {
                            startAnalysis(
                                images: images,
                                mode: cameraMode,
                                description: description,
                                progressiveMeal: progressiveMeal
                            )
                        }
                    },
                    onCancel: {
                        showMultiPhotoCaptureSheet = false
                        captureImages = []
                        contextDescription = ""
                    }
                )
                .presentationDetents([.large])
                .presentationDragIndicator(.visible)
            }
            .sheet(isPresented: $showContextSheet) {
                ContextDescriptionSheet(
                    image: pendingContextImage,
                    description: $contextDescription,
                    onAnalyze: {
                        let desc = contextDescription
                        let image = pendingContextImage
                        showContextSheet = false
                        pendingContextImage = nil
                        
                        if let image {
                            // Delay presenting the next sheet until the current one fully dismisses.
                            // This prevents SwiftUI from silently ignoring the new activeSheet presentation.
                            DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) {
                                currentImage = image // Ensure currentImage is set so AnalyzingView/FoodResultView shows the image
                                currentImages = [image]
                                startAnalysis(image: image, mode: .snapFoodWithContext, description: desc)
                            }
                        }
                    },
                    onCancel: {
                        showContextSheet = false
                        pendingContextImage = nil
                        currentImage = nil
                        currentImages = []
                    }
                )
            }
            .sheet(item: $activeSheet) { sheet in
                switch sheet {
                case .analyzing:
                    AnalyzingView(image: currentImage)
                case .analyzingText:
                    AnalyzingView(image: nil, message: "Looking up nutrition...")
                case .lookingUpBarcode:
                    AnalyzingView(image: nil, message: "Looking up barcode...")
                case .foodResult:
                    if let result = currentFoodResult {
                        FoodResultView(
                            images: currentImages,
                            emoji: currentEmoji,
                            source: currentFoodSource,
                            name: result.name,
                            calories: result.calories,
                            protein: result.protein,
                            carbs: result.carbs,
                            fat: result.fat,
                            ingredients: result.ingredients,
                            progressiveMeal: result.progressiveMeal,
                            productMetadata: result.productMetadata,
                            servingSizeGrams: result.servingSizeGrams,
                            sugar: result.sugar,
                            addedSugar: result.addedSugar,
                            fiber: result.fiber,
                            saturatedFat: result.saturatedFat,
                            monounsaturatedFat: result.monounsaturatedFat,
                            polyunsaturatedFat: result.polyunsaturatedFat,
                            cholesterol: result.cholesterol,
                            caffeine: result.caffeine,
                            supplementalNutrients: result.supplementalNutrients,
                            sodium: result.sodium,
                            potassium: result.potassium,
                            transFat: result.transFat,
                            calcium: result.calcium,
                            iron: result.iron,
                            magnesium: result.magnesium,
                            zinc: result.zinc,
                            vitaminA: result.vitaminA,
                            vitaminC: result.vitaminC,
                            vitaminD: result.vitaminD,
                            vitaminB12: result.vitaminB12,
                            vitaminE: result.vitaminE,
                            vitaminK: result.vitaminK,
                            folate: result.folate,
                            omega3: result.omega3,
                            servingUnitOptions: result.servingUnitOptions,
                            selectedServingUnit: result.selectedServingUnit,
                            selectedServingQuantity: result.selectedServingQuantity,
                            servingSizeIsKnown: result.servingSizeIsKnown,
                            logDate: logDateForSelectedDay,
                            profile: userProfile,
                            entriesForDate: { foodStore.entries(for: $0) },
                            weightMetric: weightUnitRaw == "kg",
                            onLog: { entry in
                                if !foodStore.addEntry(entry) { showFoodLoggingBlocked = true }
                            }
                        )
                    }
                case .editFood:
                    if let editingEntry {
                        EditFoodEntryView(entry: editingEntry)
                    }
                }
            }
            .sheet(item: $savedMealsMode, content: { mode in
                RecentsView(mode: mode, logDate: logDateForSelectedDay, onReview: { entry in
                    currentImages = entry.allImageData.compactMap(UIImage.init(data:))
                    currentImage = currentImages.first
                    currentEmoji = entry.emoji
                    currentFoodSource = entry.source
                    currentFoodResult = GeminiService.FoodAnalysis(
                        name: entry.name,
                        calories: entry.calories,
                        protein: entry.protein,
                        carbs: entry.carbs,
                        fat: entry.fat,
                        servingSizeGrams: entry.reviewServingReference,
                        emoji: entry.emoji,
                        sugar: entry.sugar,
                        addedSugar: entry.addedSugar,
                        fiber: entry.fiber,
                        saturatedFat: entry.saturatedFat,
                        monounsaturatedFat: entry.monounsaturatedFat,
                        polyunsaturatedFat: entry.polyunsaturatedFat,
                        cholesterol: entry.cholesterol,
                        caffeine: entry.caffeine,
                        supplementalNutrients: entry.supplementalNutrients,
                        sodium: entry.sodium,
                        potassium: entry.potassium,
                        transFat: entry.transFat,
                        calcium: entry.calcium,
                        iron: entry.iron,
                        magnesium: entry.magnesium,
                        zinc: entry.zinc,
                        vitaminA: entry.vitaminA,
                        vitaminC: entry.vitaminC,
                        vitaminD: entry.vitaminD,
                        vitaminB12: entry.vitaminB12,
                        vitaminE: entry.vitaminE,
                        vitaminK: entry.vitaminK,
                        folate: entry.folate,
                        omega3: entry.omega3,
                        servingUnitOptions: entry.reviewServingUnitOptions,
                        selectedServingUnit: entry.reviewSelectedServingUnit,
                        selectedServingQuantity: entry.reviewSelectedServingQuantity,
                        servingSizeIsKnown: entry.hasKnownServingSize,
                        progressiveMeal: entry.progressiveMeal,
                        ingredients: entry.ingredients,
                        productMetadata: entry.productMetadata
                    )
                    activeSheet = .foodResult
                })
            })
            .sheet(isPresented: $showCopyFromDaySheet) {
                CopyFromDaySheet(targetDate: selectedDate)
            }
            .sheet(isPresented: $showFastingStart) {
                FastingStartSheet(defaultGoalMinutes: fastingDefaultGoalMinutes) { goalMinutes in
                    startFast(goalMinutes: goalMinutes)
                }
            }
            .sheet(item: $editingFastingSession) { session in
                FastingSessionEditorView(
                    session: session,
                    onSave: { updated in
                        let saved = fastingStore.update(updated)
                        if saved { refreshFastingGoalNotification() }
                        return saved
                    },
                    onEndNow: { updated in
                        guard fastingStore.update(updated) else { return false }
                        return endFast()
                    },
                    onDelete: { removed in
                        fastingStore.delete(id: removed.id)
                        refreshFastingGoalNotification()
                    }
                )
            }
            .sheet(isPresented: $showSiriPhrases) {
                NavigationStack {
                    SiriPhrasesSettingsView()
                        .toolbar {
                            ToolbarItem(placement: .confirmationAction) {
                                Button("Done") {
                                    showSiriPhrases = false
                                }
                            }
                        }
                }
            }
            .interactiveDismissDisabled(activeSheet == .analyzing || activeSheet == .analyzingText || activeSheet == .lookingUpBarcode)
            .photosPicker(
                isPresented: $showPhotoPicker,
                selection: $selectedPhotoItems,
                maxSelectionCount: 10,
                selectionBehavior: .ordered,
                matching: .images
            )
            .onChange(of: selectedPhotoItems) { oldValue, newValue in
                guard !newValue.isEmpty else { return }
                selectedPhotoItems = []
                Task {
                    var imported: [UIImage] = []
                    for item in newValue.prefix(10 - captureImages.count) {
                        if let data = try? await item.loadTransferable(type: Data.self),
                           let image = UIImage(data: data) {
                            imported.append(image)
                        }
                    }
                    if !imported.isEmpty {
                        captureImages = Array((captureImages + imported).prefix(10))
                        currentImage = captureImages.first
                        currentImages = captureImages
                        currentEmoji = nil
                        currentFoodSource = .snapFood
                        DispatchQueue.main.asyncAfter(deadline: .now() + 0.25) {
                            showMultiPhotoCaptureSheet = true
                        }
                    }
                }
            }
            .alert("Error", isPresented: $showError) {
                Button("Retry") { retryLastRequest() }
                Button("Cancel", role: .cancel) { retryRequest = nil }
            } message: {
                Text(errorMessage)
            }
            .alert("Food logging paused", isPresented: $showFoodLoggingBlocked) {
                Button("OK", role: .cancel) { }
            } message: {
                Text("End or cancel your active fast before logging food.")
            }
            .alert("Fasting Tracking off", isPresented: $showFastingQuickActionDisabled) {
                Button("OK", role: .cancel) { }
            } message: {
                Text("Enable Fasting Tracking in Settings to use this shortcut.")
            }
            .sheet(isPresented: $showNutritionDetail) {
                NutritionDetailView(date: selectedDate, homeTopNutrientsRaw: $homeTopNutrientsRaw)
            }
            .sheet(isPresented: $showCustomWaterLog) {
                WaterCustomAmountSheet(unit: waterUnit, onAdd: logWater)
            }
            .onOpenURL { url in
                if url.scheme == "ayuvo", url.host == "import-share-image" {
                    checkAndConsumeSharedImage()
                }
            }
            .onAppear {
                checkAndConsumeSharedImage()
                prewarmFoodDestinations()
            }
            .task(id: quickActionRequest?.id) {
                presentQuickActionIfPossible()
            }
            .task(id: foodLogMethodRequest?.id) {
                presentFoodLogMethodIfPossible()
            }
            .onChange(of: activeSheet) { oldValue, newValue in
                if oldValue != nil && newValue == nil {
                    DispatchQueue.main.asyncAfter(deadline: .now() + 0.35) {
                        presentQuickActionIfPossible()
                        presentFoodLogMethodIfPossible()
                    }
                } else {
                    presentQuickActionIfPossible()
                    presentFoodLogMethodIfPossible()
                }
            }
            .onChange(of: scenePhase) { _, newPhase in
                if newPhase == .active {
                    checkAndConsumeSharedImage()
                    Task { await refreshDailySteps() }
                    // Returned to the foreground -> replay the fill-from-zero reveal.
                    // Gated on wasBackgrounded so transient .inactive blips (control
                    // center, app switcher) don't retrigger it.
                    if wasBackgrounded {
                        launchFillEpoch += 1
                        wasBackgrounded = false
                    }
                    homeBurnRefreshGeneration += 1
                } else if newPhase == .background {
                    wasBackgrounded = true
                }
            }
            .onReceive(NotificationCenter.default.publisher(for: .fudBarcodeAlertScanLabel)) { _ in
                retryRequest = nil
                openCameraForNutritionLabel()
            }
            .onReceive(NotificationCenter.default.publisher(for: .fudBarcodeAlertRetry)) { _ in
                retryLastRequest()
            }
            .onReceive(NotificationCenter.default.publisher(for: .fudBarcodeAlertCancel)) { _ in
                retryRequest = nil
            }
        }
    }

    @MainActor
    private func presentQuickActionIfPossible() {
        guard let request = quickActionRequest, activeSheet == nil else { return }

        let hadOpenDestination = showCamera || showBarcodeScanner || showPhotoPicker
            || showVoicePopover || showTextPopover || showManualPopover
            || savedMealsMode != nil || showContextSheet || showMultiPhotoCaptureSheet
            || showFastingStart || editingFastingSession != nil

        showCamera = false
        showBarcodeScanner = false
        showPhotoPicker = false
        showVoicePopover = false
        showTextPopover = false
        showManualPopover = false
        savedMealsMode = nil
        showContextSheet = false
        showMultiPhotoCaptureSheet = false
        showCopyFromDaySheet = false
        showNutritionDetail = false
        showCustomWaterLog = false
        showError = false
        showFastingStart = false
        editingFastingSession = nil
        selectedDate = .now

        if request.action == .fasting {
            onQuickActionHandled(request.id)
            let launchFasting: @MainActor @Sendable () -> Void = {
                if !fastingTrackingEnabled {
                    showFastingQuickActionDisabled = true
                } else if let active = fastingStore.activeSession {
                    editingFastingSession = active
                } else {
                    showFastingStart = true
                }
            }
            if hadOpenDestination {
                DispatchQueue.main.asyncAfter(deadline: .now() + 0.35, execute: launchFasting)
            } else {
                launchFasting()
            }
            return
        }

        guard fastingStore.activeSession == nil else {
            onQuickActionHandled(request.id)
            showFoodLoggingBlocked = true
            return
        }

        onQuickActionHandled(request.id)
        let launch: @MainActor @Sendable () -> Void = {
            presentFoodDestination {
                switch request.action {
                case .camera:
                    cameraMode = .snapFoodWithContext
                    isImportingPhotos = false
                    captureImages = []
                    contextDescription = ""
                    showCamera = true
                case .photos:
                    cameraMode = .snapFoodWithContext
                    isImportingPhotos = true
                    captureImages = []
                    contextDescription = ""
                    selectedPhotoItems = []
                    showPhotoPicker = true
                case .voice:
                    showVoicePopover = true
                case .text:
                    showTextPopover = true
                case .barcode:
                    showBarcodeScanner = true
                case .favorites:
                    savedMealsMode = .favorites
                case .frequent:
                    savedMealsMode = .frequent
                case .recent:
                    savedMealsMode = .recent
                case .manual:
                    showManualPopover = true
                case .fasting:
                    break
                }
            }
        }

        if hadOpenDestination {
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.35, execute: launch)
        } else {
            launch()
        }
    }

    @MainActor
    private func presentFoodLogMethodIfPossible() {
        guard let request = foodLogMethodRequest, activeSheet == nil else { return }

        let hadOpenDestination = showCamera || showBarcodeScanner || showPhotoPicker
            || showVoicePopover || showTextPopover || showManualPopover
            || savedMealsMode != nil || showContextSheet || showMultiPhotoCaptureSheet
            || showCopyFromDaySheet || showFastingStart || editingFastingSession != nil

        showCamera = false
        showBarcodeScanner = false
        showPhotoPicker = false
        showVoicePopover = false
        showTextPopover = false
        showManualPopover = false
        savedMealsMode = nil
        showContextSheet = false
        showMultiPhotoCaptureSheet = false
        showCopyFromDaySheet = false
        showNutritionDetail = false
        showCustomWaterLog = false
        showError = false
        showFastingStart = false
        editingFastingSession = nil
        selectedDate = .now

        guard fastingStore.activeSession == nil else {
            onFoodLogMethodHandled(request.id)
            showFoodLoggingBlocked = true
            return
        }

        onFoodLogMethodHandled(request.id)
        let launch: @MainActor @Sendable () -> Void = {
            presentFoodDestination {
                performFoodLogMethod(request.method)
            }
        }

        if hadOpenDestination {
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.35, execute: launch)
        } else {
            launch()
        }
    }
    
    private func checkAndConsumeSharedImage() {
        guard ShareImportManager.hasSharedImage() else { return }
        guard canBeginFoodLogging() else { return }
        guard let image = ShareImportManager.consumeSharedImage() else { return }
        
        // Force dismiss any currently open sheets to prevent SwiftUI from swallowing the new presentation
        activeSheet = nil
        
        currentImage = image
        currentImages = [image]
        currentEmoji = nil
        currentFoodSource = .snapFood

        // A slight delay ensures the view hierarchy is clear before presenting
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.3) {
            pendingContextImage = image
            contextDescription = ""
            showContextSheet = true
        }
    }

    private func startAnalysis(image: UIImage, mode: CameraMode, description: String? = nil) {
        startAnalysis(images: [image], mode: mode, description: description, progressiveMeal: false)
    }

    private func startAnalysis(
        images: [UIImage],
        mode: CameraMode,
        description: String? = nil,
        progressiveMeal: Bool = false
    ) {
        retryRequest = .analysis(
            images: images,
            mode: mode,
            description: description,
            progressiveMeal: progressiveMeal
        )
        activeSheet = .analyzing

        Task {
            do {
                switch mode {
                case .snapFood:
                    let result = try await GeminiService.analyzeFood(
                        images: images,
                        progressiveMeal: progressiveMeal
                    )
                    currentFoodResult = result
                    currentFoodSource = .snapFood
                    retryRequest = nil
                    activeSheet = .foodResult

                case .snapFoodWithContext:
                    let result = try await GeminiService.analyzeFood(
                        images: images,
                        description: description,
                        progressiveMeal: progressiveMeal
                    )
                    currentFoodResult = result
                    currentFoodSource = .snapFood
                    retryRequest = nil
                    activeSheet = .foodResult

                }
            } catch {
                presentAnalysisError(error)
            }
        }
    }

    private func startBarcodeLookup(_ barcode: String) {
        let trimmedBarcode = barcode.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmedBarcode.isEmpty else { return }
        retryRequest = .barcode(trimmedBarcode)

        currentImage = nil
        currentImages = []
        currentEmoji = nil
        currentFoodSource = .barcode
        activeSheet = .lookingUpBarcode

        Task {
            do {
                let lookup = try await OpenFoodFactsService.lookupWithImage(barcode: trimmedBarcode)
                let result = lookup.analysis
                currentFoodResult = result
                currentEmoji = result.emoji
                if let imageData = lookup.productImageData,
                   let image = UIImage(data: imageData) {
                    currentImage = image
                    currentImages = [image]
                }
                retryRequest = nil
                activeSheet = .foodResult
            } catch {
                presentBarcodeLookupError(error)
            }
        }
    }

    private func startTextAnalysis(_ description: String) {
        retryRequest = .text(description)
        activeSheet = .analyzingText
        Task {
            do {
                let result = try await GeminiService.analyzeTextInput(description: description)
                currentFoodResult = result
                currentEmoji = result.emoji
                retryRequest = nil
                activeSheet = .foodResult
            } catch {
                presentAnalysisError(error)
            }
        }
    }

    /// Dismiss the loading sheet first, then present the alert after the sheet
    /// animation finishes. Presenting both in the same turn makes SwiftUI flash
    /// or drop the alert.
    @MainActor
    private func presentAnalysisError(_ error: Error) {
        activeSheet = nil
        errorMessage = GeminiService.analysisErrorMessage(error)
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.45) {
            showError = true
        }
    }

    @MainActor
    private func presentBarcodeLookupError(_ error: Error) {
        let offersScanLabel: Bool
        if let lookupError = error as? OpenFoodFactsService.LookupError {
            switch lookupError {
            case .missingNutrition, .productNotFound:
                offersScanLabel = true
            default:
                offersScanLabel = false
            }
        } else {
            offersScanLabel = false
        }

        let message = (error as? OpenFoodFactsService.LookupError)?.localizedDescription
            ?? OpenFoodFactsService.LookupError.invalidResponse.localizedDescription
        errorMessage = message
        // End the loading sheet first, then show only the system popup.
        activeSheet = nil
        BarcodeLookupAlertPresenter.present(
            message: message,
            offersScanLabel: offersScanLabel
        )
    }

    @MainActor
    private func openCameraForNutritionLabel() {
        guard canBeginFoodLogging() else { return }
        cameraMode = .snapFoodWithContext
        isImportingPhotos = false
        captureImages = []
        contextDescription = ""
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.35) {
            showCamera = true
        }
    }

    private func retryLastRequest() {
        guard let retryRequest else { return }
        switch retryRequest {
        case let .analysis(images, mode, description, progressiveMeal):
            startAnalysis(
                images: images,
                mode: mode,
                description: description,
                progressiveMeal: progressiveMeal
            )
        case let .text(description):
            startTextAnalysis(description)
        case let .barcode(barcode):
            startBarcodeLookup(barcode)
        }
    }

}

// Configurable + menu helpers (same file as FoodManagementView so private state is accessible).
extension NutritionView {
    @ViewBuilder
    var configuredFoodAddMenuContent: some View {
        let config = AddMenuSettings.load()
        if config.usesFlatLayout {
            Section {
                ForEach(config.flatMethods) { method in
                    addMenuButton(for: method)
                }
            }
        } else {
            ForEach(config.groups.filter { !$0.methods.isEmpty }) { group in
                Section {
                    Menu {
                        ForEach(group.methods) { method in
                            addMenuButton(for: method)
                        }
                    } label: {
                        Label(group.name, systemImage: addMenuGroupIcon(for: group))
                    }
                }
            }
        }
    }

    @ViewBuilder
    func addMenuButton(for method: FoodLogMethod) -> some View {
        Button {
            presentFoodDestination {
                performFoodLogMethod(method)
            }
        } label: {
            Label(method.title, systemImage: method.systemImageName)
        }
    }

    func performFoodLogMethod(_ method: FoodLogMethod) {
        switch method {
        case .camera:
            cameraMode = .snapFoodWithContext
            isImportingPhotos = false
            captureImages = []
            contextDescription = ""
            showCamera = true
        case .photos:
            cameraMode = .snapFoodWithContext
            isImportingPhotos = true
            captureImages = []
            contextDescription = ""
            selectedPhotoItems = []
            showPhotoPicker = true
        case .barcode:
            showBarcodeScanner = true
        case .voice:
            showVoicePopover = true
        case .text:
            showTextPopover = true
        case .manual:
            showManualPopover = true
        case .siriPhrases:
            showSiriPhrases = true
        case .favorites:
            savedMealsMode = .favorites
        case .frequent:
            savedMealsMode = .frequent
        case .recent:
            savedMealsMode = .recent
        case .copyFromDay:
            showCopyFromDaySheet = true
        }
    }

    private func addMenuGroupIcon(for group: AddMenuGroupConfig) -> String {
        group.methods.first?.systemImageName ?? "folder.fill"
    }
}

private extension Notification.Name {
    static let fudBarcodeAlertScanLabel = Notification.Name("fudBarcodeAlertScanLabel")
    static let fudBarcodeAlertRetry = Notification.Name("fudBarcodeAlertRetry")
    static let fudBarcodeAlertCancel = Notification.Name("fudBarcodeAlertCancel")
}

/// UIKit alert so the barcode error popup survives SwiftUI sheet dismissal.
@MainActor
private enum BarcodeLookupAlertPresenter {
    static func present(message: String, offersScanLabel: Bool, attempt: Int = 0) {
        guard let root = keyRootViewController() else { return }

        // Wait until the "Looking up barcode..." sheet has fully dismissed.
        if root.presentedViewController != nil, attempt < 30 {
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.08) {
                present(message: message, offersScanLabel: offersScanLabel, attempt: attempt + 1)
            }
            return
        }

        let alert = UIAlertController(
            title: "Couldn't use this barcode",
            message: message,
            preferredStyle: .alert
        )
        if offersScanLabel {
            alert.addAction(UIAlertAction(title: "Scan Label", style: .default) { _ in
                NotificationCenter.default.post(name: .fudBarcodeAlertScanLabel, object: nil)
            })
        } else {
            alert.addAction(UIAlertAction(title: "Retry", style: .default) { _ in
                NotificationCenter.default.post(name: .fudBarcodeAlertRetry, object: nil)
            })
        }
        alert.addAction(UIAlertAction(title: "Cancel", style: .cancel) { _ in
            NotificationCenter.default.post(name: .fudBarcodeAlertCancel, object: nil)
        })
        root.present(alert, animated: true)
    }

    private static func keyRootViewController() -> UIViewController? {
        UIApplication.shared.connectedScenes
            .compactMap { $0 as? UIWindowScene }
            .flatMap(\.windows)
            .first(where: \.isKeyWindow)?
            .rootViewController
    }
}

/// Flat calorie summary at the top of the diary: eaten, goal progress and the burn line.
struct NutritionCalorieHeader: View {
    let eaten: Int
    let goal: Int
    let burnLine: String?

    private var caption: String {
        guard goal > 0 else { return String(localized: "No calorie goal set") }
        let remaining = goal - eaten
        return remaining >= 0
            ? String(localized: "\(remaining.formatted()) kcal left of \(goal.formatted())")
            : String(localized: "\((-remaining).formatted()) kcal over your \(goal.formatted()) goal")
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HeadlineStat(label: String(localized: "Eaten"), value: eaten.formatted(), unit: String(localized: "kcal"), caption: caption)
            ProgressView(value: Double(min(eaten, max(goal, 1))), total: Double(max(goal, 1)))
                .tint(AyuvoPalette.nutrition)
                .accessibilityHidden(true)
            if let burnLine {
                Text(burnLine)
                    .font(.system(.caption, design: .rounded))
                    .foregroundStyle(.secondary)
            }
        }
        .ayuvoCard()
        .accessibilityElement(children: .combine)
        .accessibilityIdentifier("nutrition.calories")
    }
}
