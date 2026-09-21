import SwiftUI

/// Health Profile › Goals & Targets: plan, automation, daily targets and the goal calculation.
extension SettingsPaneView {
    @ViewBuilder
    var goalsPane: some View {
        Section {
            Picker(selection: profileBinding.goal) {
                ForEach(WeightGoal.allCases, id: \.self) { goal in
                    Text(goal.displayName).tag(goal)
                }
            } label: {
                Label {
                    Text("Weight Goal")
                } icon: {
                    Image(systemName: profile.goal.icon)
                        .foregroundStyle(AppColors.calorie)
                }
            }
            .pickerStyle(.menu)
            .tint(.secondary)
            .onChange(of: profile.goal) { _, newValue in
                if newValue == .maintain {
                    profile.weeklyChangeKg = nil
                    profile.goalWeightKg = nil
                } else {
                    if profile.weeklyChangeKg == nil {
                        profile.weeklyChangeKg = 0.5
                    }
                    // Clear goal weight if it no longer matches the new direction
                    // (e.g., switching from Lose to Gain with an old target below current weight).
                    if let gw = profile.goalWeightKg {
                        let losingPastTarget = newValue == WeightGoal.lose && gw >= profile.weightKg
                        let gainingPastTarget = newValue == WeightGoal.gain && gw <= profile.weightKg
                        if losingPastTarget || gainingPastTarget {
                            profile.goalWeightKg = nil
                        }
                    }
                }
                saveProfile()
            }

            VStack(alignment: .leading, spacing: 4) {
                Picker(selection: profileBinding.activityLevel) {
                    ForEach(ActivityLevel.allCases, id: \.self) { level in
                        Text(level.displayName).tag(level)
                    }
                } label: {
                    Label {
                        Text("Activity Level")
                    } icon: {
                        Image(systemName: profile.activityLevel.icon)
                            .foregroundStyle(AppColors.calorie)
                    }
                }
                .pickerStyle(.menu)
                .tint(.secondary)

                Text(profile.activityLevel.subtitle)
                    .font(.system(.caption, design: .rounded))
                    .foregroundStyle(.secondary)
                    .padding(.leading, 34)
            }
            .onChange(of: profile.activityLevel) { _, _ in saveProfile() }

            if profile.goal != .maintain {
                Picker(selection: Binding(
                    get: { profile.weeklyChangeKg ?? 0.5 },
                    set: { profile.weeklyChangeKg = $0; saveProfile() }
                )) {
                    Text("Slow (\(WeightDisplayFormatter.weeklyChange(kilograms: 0.25, useMetric: weightMetric, period: "wk")))").tag(0.25)
                    Text("Moderate (\(WeightDisplayFormatter.weeklyChange(kilograms: 0.5, useMetric: weightMetric, period: "wk")))").tag(0.5)
                    Text("Fast (\(WeightDisplayFormatter.weeklyChange(kilograms: 1.0, useMetric: weightMetric, period: "wk")))").tag(1.0)
                } label: {
                    Label {
                        Text("Weekly Change")
                    } icon: {
                        Image(systemName: "gauge.with.dots.needle.33percent")
                            .foregroundStyle(AppColors.calorie)
                    }
                }
                .pickerStyle(.menu)
                .tint(.secondary)

                ProfileInfoRow(
                    icon: "flag.checkered",
                    label: "Goal Weight",
                    value: goalWeightDisplay
                ) {
                    activeSheet = .editGoalWeight
                }
            }

            HStack {
                Label {
                    Text("Adaptive Goals")
                } icon: {
                    Image(systemName: "chart.line.uptrend.xyaxis")
                        .foregroundStyle(AppColors.calorie)
                }
                Spacer()
                if isApplyingAdaptiveGoals {
                    ProgressView()
                }
                Button {
                    showAdaptiveGoalsInfo = true
                } label: {
                    Image(systemName: "info.circle")
                        .foregroundStyle(.secondary)
                }
                .buttonStyle(.borderless)
                .accessibilityLabel("About Adaptive Goals")

                Toggle("", isOn: $adaptiveGoalsEnabled)
                    .labelsHidden()
                    .tint(AppColors.calorie)
                    .disabled(isApplyingAdaptiveGoals)
                    .onChange(of: adaptiveGoalsEnabled) { oldValue, enabled in
                        handleAdaptiveGoalsToggle(enabled, wasEnabled: oldValue)
                    }
            }

            HStack {
                Label {
                    VStack(alignment: .leading, spacing: 2) {
                        Text("Energy Burn")
                        if !healthKitEnabled {
                            Text("Needs Apple Health")
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }
                    }
                } icon: {
                    Image(systemName: "flame")
                        .foregroundStyle(AppColors.calorie)
                }
                Spacer()
                Button {
                    showEnergyBurnInfo = true
                } label: {
                    Image(systemName: "info.circle")
                        .foregroundStyle(.secondary)
                }
                .buttonStyle(.borderless)
                .accessibilityLabel("About Energy Burn")

                Toggle("", isOn: $energyBurnEnabled)
                    .labelsHidden()
                    .tint(AppColors.calorie)
                    .disabled(isRecalculatingGoals)
                    .onChange(of: energyBurnEnabled) { _, enabled in
                        handleEnergyBurnToggle(enabled)
                    }
            }

        }
        .listRowBackground(AppColors.appCard)

        Section("Daily Targets") {
            lockableGoalRow(
                icon: "flame",
                label: "Calories",
                valueText: "\(profile.effectiveCalories.formatted()) kcal",
                macro: nil,
                sheet: .editCalories
            )

            lockableGoalRow(icon: "p.circle", label: "Protein", valueText: "\(profile.effectiveProtein)g", macro: .protein, sheet: .editProtein)
            lockableGoalRow(icon: "c.circle", label: "Carbs", valueText: "\(profile.effectiveCarbs)g", macro: .carbs, sheet: .editCarbs)
            lockableGoalRow(icon: "f.circle", label: "Fat", valueText: "\(profile.effectiveFat)g", macro: .fat, sheet: .editFat)

            NavigationLink {
                OptionalNutrientGoalsSettingsView(profile: profile)
            } label: {
                Label {
                    HStack {
                        Text("Other Nutrients")
                        Spacer()
                        Text("Sugar, Fiber, Sodium")
                            .foregroundStyle(.secondary)
                    }
                } icon: {
                    Image(systemName: "list.bullet.clipboard")
                        .foregroundStyle(AppColors.calorie)
                }
            }

            Button {
                recalculateGoalsNow()
            } label: {
                Label {
                    HStack {
                        Text("Recalculate Goals")
                        Spacer()
                        if isRecalculatingGoals {
                            ProgressView()
                        } else if goalsNeedRecalc {
                            // Soft nudge: a goal input changed since the last recalc. A CTA
                            // on the row's right edge, not a wrapped line below it.
                            Text("Tap to update")
                                .font(.caption)
                                .foregroundStyle(AppColors.calorie)
                        }
                    }
                } icon: {
                    Image(systemName: "arrow.clockwise")
                        .foregroundStyle(AppColors.calorie)
                }
            }
            .tint(.primary)
            .disabled(isRecalculatingGoals)

            Button {
                showCalculationMethods = true
            } label: {
                Label {
                    Text("Calculation Methods")
                } icon: {
                    Image(systemName: "book")
                        .foregroundStyle(AppColors.calorie)
                }
            }
            .tint(.primary)
        }
        .listRowBackground(AppColors.appCard)
    }

    static let lastRecalcGoalSignatureKey = "lastRecalcGoalSignature"

    /// True when a goal-relevant input (weight, activity, goal, pace, …) has changed since the
    /// last Recalculate. Recalculate stays tappable at all times — this only drives a soft
    /// "your profile changed, recalculate to refresh" nudge, never disables the button.
    var goalsNeedRecalc: Bool {
        guard let stored = UserDefaults.standard.string(forKey: Self.lastRecalcGoalSignatureKey) else { return false }
        return stored != profile.goalInputSignature
    }

    /// Capture the current goal inputs as the "last recalculated" baseline so the nudge clears.
    func markGoalsRecalculated() {
        UserDefaults.standard.set(profile.goalInputSignature, forKey: Self.lastRecalcGoalSignatureKey)
    }

    /// A goal row (calories or a macro). Tap the row to edit the value; tap the lock icon to lock it.
    /// Locking a macro keeps it fixed during a rebalance; locking calories holds the calorie total
    /// when a macro is edited. Lock controls are disabled while Adaptive Goals is on (it auto-
    /// recalculates and would overwrite). `macro == nil` means the calories row.
    @ViewBuilder
    func lockableGoalRow(icon: String, label: String, valueText: String, macro: AutoBalanceMacro?, sheet: ActiveSheet) -> some View {
        let locked = macro.map { profile.isMacroLocked($0) } ?? profile.isCaloriesLocked
        // The lock glyph is a read-only indicator. Saving a value locks it; the picker's "Reset to
        // Auto-balance" releases it. Tapping the row opens the picker (or explains, when Adaptive is
        // on and editing would be overwritten weekly).
        Button {
            if adaptiveGoalsEnabled {
                showAdaptiveGoalsLockHint()
            } else {
                activeSheet = sheet
            }
        } label: {
            HStack(spacing: 12) {
                Image(systemName: icon)
                    .foregroundStyle(AppColors.calorie)
                    .frame(width: 22)
                Text(LocalizedDisplayText.text(label))
                    .foregroundStyle(.primary)
                Spacer()
                Text(valueText)
                    .foregroundStyle(.secondary)
                Image(systemName: locked ? "lock.fill" : "lock.open")
                    .font(.footnote)
                    .foregroundStyle(locked ? AppColors.calorie : .secondary)
                    .opacity(adaptiveGoalsEnabled ? 0.3 : 1)
                    .accessibilityLabel(locked ? "Locked" : "Unlocked")
            }
        }
        .buttonStyle(.plain)
    }

    /// Explain why the goals section is read-only while Adaptive Goals owns the targets.
    func showAdaptiveGoalsLockHint() {
        showAdaptiveGoalAlert(
            title: "Adaptive Goals Is On",
            message: "Turn off Adaptive Goals to lock or set your own calories and macros. While it's on, Ayuvo recalculates them for you each week."
        )
    }

    /// Apply a calorie edit: locked macros stay, unlocked macros rescale to the new total. Saving a
    /// value the user chose locks it (the lock icon then releases it).
    func setCalories(to value: Int) {
        profile.applyCaloriesEdit(value)
        profile.caloriesLocked = true
        saveProfile()
    }

    /// Apply a macro edit through the rebalance engine, then lock the macro the user just set
    /// (honoring the max-2 cap — silently left unlocked if two macros are already locked). When
    /// calories is locked and neither other macro can absorb the change, the edit is rejected.
    func setMacro(_ macro: AutoBalanceMacro, to value: Int) {
        guard profile.applyMacroEdit(macro, grams: value) else {
            showAutoMacroEditAlert = true
            return
        }
        if !profile.isMacroLocked(macro) {
            _ = profile.toggleMacroLock(macro)
        }
        saveProfile()
    }

    /// "Reset to Auto-balance" from the picker: release the macro's lock and re-derive it as the
    /// balancing remainder.
    func resetMacroLock(_ macro: AutoBalanceMacro) {
        profile.resetMacroToBalance(macro)
        saveProfile()
    }

    /// "Reset to Auto-balance" from the calories picker: release the calories lock and snap the
    /// total to the sum of the macros.
    func resetCaloriesLock() {
        profile.resetCaloriesToBalance()
        saveProfile()
    }

    func recalculateGoalsNow() {
        Task { await recalculateGoalsWithAI() }
    }

    /// AI-driven goal recalculation. Sends the profile + the app's formulas to the user's
    /// selected provider and applies the returned calorie
    /// and protein/fat targets; carbs auto-balances so totals stay consistent. AI-only — when
    /// the provider is unavailable (no key / offline / bad response) the
    /// existing goals are left unchanged and the user is told to fix their provider/key, with
    /// NO silent formula fallback. Then recomputes the optional "Other Nutrients"
    /// (fiber/sugar/sodium/…) via AI, leaving them untouched if that call fails (no clobbering
    /// of user customizations). The whole recalc is aborted if the user edits a goal input
    /// mid-call. Food calorie estimation is untouched.
    func recalculateGoalsWithAI() async {
        guard !isRecalculatingGoals else { return }
        isRecalculatingGoals = true
        defer { isRecalculatingGoals = false }

        // Snapshot the inputs the AI computes against. The profile is shared (ProfileStore)
        // and can be reloaded/edited on the main actor during the await, so we apply results
        // only if the calc-relevant inputs are still unchanged — otherwise the concurrent
        // edit (which already reset goals) wins, avoiding a stale/lost-update.
        let snapshot = profile
        let energyBurnSnapshot = energyBurnEnabled
        let healthKitSnapshot = healthKitEnabled
        let healthEnergy = await measuredEnergyHistory()
        // Energy Burn toggle: when on, anchor maintenance to the user's measured Apple Health burn.
        let measuredTdee = measuredEnergyTdee(for: snapshot, history: healthEnergy)
        let evidence = makeGoalEvidence(profile: snapshot, healthEnergy: healthEnergy)
        guard energyBurnEnabled == energyBurnSnapshot,
              healthKitEnabled == healthKitSnapshot
        else { return }
        do {
            let result = try await GeminiService.calculateGoals(
                profile: snapshot,
                measuredTdee: measuredTdee,
                measurement: bodyMeasurementStore.latestEntry,
                evidence: evidence,
                heightMetric: heightMetric,
                weightMetric: weightMetric
            )
            guard goalInputsUnchanged(snapshot, profile),
                  energyBurnEnabled == energyBurnSnapshot,
                  healthKitEnabled == healthKitSnapshot
            else { return }
            // Apply the AI's calorie + protein targets. Protein is the AI's choice within a range
            // near the activity multiplier (it can flex with the goal + history), not a rigid lock.
            // Carbs and fat stay auto-balanced (unlocked) and absorb the remaining calories.
            profile.customCalories = result.calories
            profile.customProtein = result.protein
            profile.customCarbs = result.carbs
            profile.customFat = result.fat
            profile.autoBalanceMacro = nil
            profile.clearLocks()
            saveProfile()
            markGoalsRecalculated()
            if adaptiveGoalsEnabled {
                // A successful manual run satisfies this week's Adaptive check; otherwise one tap
                // can be followed by a second identical provider request on the next foreground.
                AdaptiveGoalSettings.markCheckedToday()
            }
        } catch {
            guard goalInputsUnchanged(snapshot, profile),
                  energyBurnEnabled == energyBurnSnapshot,
                  healthKitEnabled == healthKitSnapshot
            else { return }
            // Goals are AI-only now — no formula fallback. Leave the existing goals
            // untouched and tell the user so they can fix their provider/key and retry.
            showAdaptiveGoalAlert(
                title: "Couldn't Recalculate",
                message: "Ayuvo couldn't reach your AI provider, so your goals are unchanged. Check your AI provider and API key in Settings, then try Recalculate again."
            )
            return
        }

        // Also recompute the optional "Other Nutrients" (fiber, sugar, sodium, …) via AI,
        // falling back to the standard defaults when AI is unavailable. These live in a
        // separate store from the calorie/macro goals.
        do {
            let suggested = try await GeminiService.suggestOptionalNutrientGoals(
                profile: profile,
                currentGoals: OptionalNutrientGoals.current,
                heightMetric: heightMetric,
                weightMetric: weightMetric
            )
            OptionalNutrientGoals.save(suggested)
        } catch {
            // AI unavailable — leave the existing Other Nutrients goals untouched rather than
            // clobbering any user customizations with defaults.
        }
        // Note: we do NOT chain Adaptive here. Adaptive Goals now *is* this same calculation on a
        // weekly timer, so chaining would fire a second identical AI call.
    }

    /// Discard an in-flight AI result after *any* profile or target edit. Comparing only formula
    /// inputs would still overwrite a user's concurrent calorie/macro/lock changes.
    func goalInputsUnchanged(_ a: UserProfile, _ b: UserProfile) -> Bool {
        a == b
    }

    func handleAdaptiveGoalsToggle(_ enabled: Bool, wasEnabled: Bool) {
        if enabled {
            // Adaptive owns the targets while on and auto-recalculates — clear any user locks now so
            // the (disabled) lock controls read as unlocked, even before the weekly run lands.
            if profile.isCaloriesLocked || profile.lockedMacroCount > 0 {
                profile.clearLocks()
                saveProfile()
            }
            Task { await applyAdaptiveGoalsIfDue(force: !wasEnabled, showAlert: true) }
        } else {
            if AdaptiveGoalSettings.restorePreviousTargets(to: &profile) {
                saveProfile()
            }
            AdaptiveGoalSettings.clearPreviousTargets()
        }
    }

    /// Energy Burn is an input switch for the goal calc. Enabling requires Apple Health with enough
    /// data (mirrors Android) — otherwise we revert the toggle and tell the user instead of running
    /// an anchorless recalc. On a genuine enable/disable we re-run the calc so the new (or removed)
    /// measured anchor takes effect immediately, exactly like tapping Recalculate.
    func handleEnergyBurnToggle(_ enabled: Bool) {
        // A programmatic revert (failed enable, below) re-fires this onChange — skip that pass.
        if energyBurnToggleReverting { energyBurnToggleReverting = false; return }
        if enabled {
            guard healthKitEnabled else {
                energyBurnToggleReverting = true
                energyBurnEnabled = false
                showAdaptiveGoalAlert(title: "Apple Health Needed", message: "Energy Burn uses your measured calories burned from Apple Health. Connect Apple Health first, then turn Energy Burn on.")
                return
            }
            Task {
                if await healthKitManager.fetchRecentEnergySummary(days: 14) == nil {
                    energyBurnToggleReverting = true
                    energyBurnEnabled = false
                    showAdaptiveGoalAlert(title: "Not Enough Health Data", message: "Ayuvo needs at least 3 recent days of Apple Health energy data before it can use your measured burn.")
                    return
                }
                await recalculateGoalsWithAI()
            }
        } else {
            Task { await recalculateGoalsWithAI() }
        }
    }

    /// Energy Burn toggle resolved to a number from the recent Apple Health window: measured total
    /// when sufficiently available, otherwise measured active energy plus formula BMR. Returns nil
    /// when Energy Burn is off, Health is disconnected, or there isn't enough data.
    func measuredEnergyTdee(
        for profile: UserProfile,
        history: [HealthEnergyDay]
    ) -> Int? {
        guard energyBurnEnabled, healthKitEnabled else { return nil }
        guard let summary = HealthKitManager.energySummary(from: history, requestedDays: 14) else {
            return nil
        }
        return summary.totalAverageCalories ?? (Int(profile.bmr.rounded()) + summary.activeAverageCalories)
    }

    /// Daily measured energy is only included while Energy Burn and Apple Health are enabled.
    /// HealthKitManager removes Ayuvo's own estimated workout samples before aggregation.
    func measuredEnergyHistory() async -> [HealthEnergyDay] {
        guard energyBurnEnabled, healthKitEnabled else { return [] }
        let history = await healthKitManager.fetchRecentEnergyHistory(days: 14)
        // A user can opt out while HealthKit is suspended. Never pass the completed fetch to an
        // AI provider after either switch has been turned off.
        guard energyBurnEnabled, healthKitEnabled else { return [] }
        return history
    }

    func makeGoalEvidence(profile: UserProfile, healthEnergy: [HealthEnergyDay]) -> GoalEvidence {
        GoalEvidence.build(
            foods: foodStore.entries,
            weights: weightStore.entries,
            bodyFatEntries: bodyFatStore.entries,
            workoutSessions: strengthWorkoutStore.completedSessions,
            bodyMeasurements: bodyMeasurementStore.entries,
            healthEnergy: healthEnergy,
            profile: profile
        )
    }

    /// Adaptive Goals: automatically re-runs the FULL AI goal calculation (the same one the
    /// Recalculate button uses) about once a week, from the latest logged food + weight trend
    /// (hit-and-trial) and — when Energy Burn is on — the measured Health maintenance anchor.
    /// Silent and non-destructive on AI failure (keeps existing goals; marks checked so it doesn't
    /// retry every app open).
    func applyAdaptiveGoalsIfDue(force: Bool, showAlert: Bool) async {
        guard adaptiveGoalsEnabled, !isApplyingAdaptiveGoals else { return }
        guard force || AdaptiveGoalSettings.shouldCheckThisWeek() else { return }

        isApplyingAdaptiveGoals = true
        defer { isApplyingAdaptiveGoals = false }

        let snapshot = profile
        let energyBurnSnapshot = energyBurnEnabled
        let healthKitSnapshot = healthKitEnabled
        let healthEnergy = await measuredEnergyHistory()
        let measuredTdee = measuredEnergyTdee(for: snapshot, history: healthEnergy)
        let evidence = makeGoalEvidence(profile: snapshot, healthEnergy: healthEnergy)
        guard adaptiveGoalsEnabled,
              energyBurnEnabled == energyBurnSnapshot,
              healthKitEnabled == healthKitSnapshot
        else { return }
        do {
            let result = try await GeminiService.calculateGoals(
                profile: snapshot,
                measuredTdee: measuredTdee,
                measurement: bodyMeasurementStore.latestEntry,
                evidence: evidence,
                heightMetric: heightMetric,
                weightMetric: weightMetric
            )
            guard adaptiveGoalsEnabled,
                  goalInputsUnchanged(snapshot, profile),
                  energyBurnEnabled == energyBurnSnapshot,
                  healthKitEnabled == healthKitSnapshot
            else { return }
            AdaptiveGoalSettings.savePreviousTargetsIfNeeded(from: profile)
            profile.customCalories = result.calories
            profile.customProtein = result.protein
            profile.customCarbs = result.carbs
            profile.customFat = result.fat
            profile.autoBalanceMacro = nil
            profile.clearLocks()
            saveProfile()
            markGoalsRecalculated()
            AdaptiveGoalSettings.markCheckedToday()
            if showAlert {
                showAdaptiveGoalAlert(title: "Adaptive Goals", message: "Updated to \(result.calories) kcal from your latest data." + (result.reason.map { " \($0)" } ?? ""))
            }
        } catch {
            guard adaptiveGoalsEnabled,
                  energyBurnEnabled == energyBurnSnapshot,
                  healthKitEnabled == healthKitSnapshot
            else { return }
            // AI unavailable — keep existing goals. Mark checked so the auto-run doesn't hammer a
            // misconfigured provider on every app open; the user can still Recalculate manually.
            AdaptiveGoalSettings.markCheckedToday()
            if showAlert {
                showAdaptiveGoalAlert(title: "Adaptive Goals", message: "Couldn't reach your AI provider — your goals are unchanged. Check your AI provider and API key in Settings.")
            }
        }
    }

    func showAdaptiveGoalAlert(title: String, message: String) {
        adaptiveGoalAlertTitle = title
        adaptiveGoalAlertMessage = message
        showAdaptiveGoalAlert = true
    }
}
