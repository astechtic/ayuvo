import Foundation
import Observation

/// View-facing façade over `MedicationsRepository` (same shape as `RecordsStore`): opens the
/// database lazily, owns the Today timeline and the filtered list, runs every write through the
/// reference logic and bumps `revision` after each committed change.
@Observable @MainActor final class MedicationStore {
    let defaults: UserDefaults
    let runtime: MedicationsRuntime
    private let calendar: Calendar
    @ObservationIgnored private let clock: @Sendable () -> Date

    /// Incremented after every committed write; views use `.task(id: store.revision)`.
    private(set) var revision = 0
    private(set) var isOpen = false
    private(set) var openError: String?
    private(set) var hasLoadedOnce = false
    private(set) var today: MedicationTodayTimeline = .empty
    /// Medications matching `filter` + `searchText`, name order.
    private(set) var medications: [Medication] = []
    private(set) var activeCount = 0
    private(set) var pausedCount = 0
    private(set) var totalCount = 0
    var filter: MedicationFilter = .active {
        didSet { if filter != oldValue { Task { await reload() } } }
    }
    var searchText = "" {
        didSet { if searchText != oldValue { scheduleSearch() } }
    }
    var banner: RecordsBanner?
    /// Last failed dose action (reference error code), for a gentle inline message.
    private(set) var lastActionError: String?
    /// Incremented when something outside the Health tab wants the Meds segment shown.
    private(set) var tabRequest = 0
    /// Route to push on the Health stack (consumed by the Medications home view).
    var navigationRequest: MedicationRoute?

    /// Installed by the reminder scheduler (wave 3); called after every write, debounced.
    @ObservationIgnored var reminderPlannerHook: (@MainActor () async -> Void)?
    /// Installed by the reminder scheduler; cancels every pending medication notification.
    @ObservationIgnored var reminderCancelHook: (@MainActor () async -> Void)?
    @ObservationIgnored private var searchTask: Task<Void, Never>?
    @ObservationIgnored private var replanTask: Task<Void, Never>?
    @ObservationIgnored private var bannerTask: Task<Void, Never>?
    @ObservationIgnored private var loadGeneration = 0
    @ObservationIgnored private var reloadTask: Task<Void, Never>?

    static let replanDebounceNanoseconds: UInt64 = 300_000_000
    static let searchDebounceNanoseconds: UInt64 = 150_000_000

    /// `runtime` defaults to `MedicationsRuntime.shared` (the notification handler uses the same one).
    /// With the shared runtime the reminder hooks point at `MedicationReminderRuntime.shared`, so every
    /// write re-plans the notifications; tests pass their own runtime and get no hooks.
    init(
        defaults: UserDefaults = .standard,
        runtime: MedicationsRuntime? = nil,
        calendar: Calendar = .current,
        clock: @escaping @Sendable () -> Date = { Date() }
    ) {
        self.defaults = defaults
        self.runtime = runtime ?? MedicationsRuntime.shared
        self.calendar = calendar
        self.clock = clock
        if runtime == nil {
            reminderPlannerHook = { await MedicationReminderRuntime.shared.replan() }
            reminderCancelHook = { await MedicationReminderRuntime.shared.cancelAll() }
        }
    }

    // MARK: - Time helpers

    var zoneIdentifier: String { calendar.timeZone.identifier }
    var nowMs: Int64 { Int64(clock().timeIntervalSince1970 * 1000) }
    var todayLocalDate: String { MR.localDateOf(Int(nowMs), zone: zoneIdentifier) }

    // MARK: - Preferences

    /// Dose reminders on/off (under the app's global `notificationsEnabled`).
    var remindersEnabled: Bool {
        get { MedicationSettings.remindersEnabled(defaults) }
        set {
            defaults.set(newValue, forKey: MedicationSettings.remindersEnabledKey)
            replanReminders()
        }
    }

    var snoozeMinutes: Int {
        get { MedicationSettings.snoozeMinutes(defaults) }
        set {
            defaults.set(MedicationSettings.snoozeOptions.contains(newValue) ? newValue : MedicationSettings.defaultSnoozeMinutes,
                         forKey: MedicationSettings.snoozeMinutesKey)
        }
    }

    /// The app-wide master switch (`NotificationSettingsView`).
    var notificationsEnabled: Bool { defaults.object(forKey: "notificationsEnabled") as? Bool ?? false }

    // MARK: - Opening and loading

    @discardableResult
    func openIfNeeded() async -> MedicationsRepository? {
        if let repository = runtime.repository { return repository }
        let opened = await runtime.openIfNeeded()
        isOpen = opened
        openError = opened ? nil : runtime.openError.map { String(describing: $0) }
        return runtime.repository
    }

    /// Refreshes the Today timeline, the filtered list and the counts. Reloads are serialized so an
    /// awaited call always returns with the state as of that call (a `filter` change and an explicit
    /// reload never race each other).
    func reload() async {
        let previous = reloadTask
        let task = Task { [weak self] in
            _ = await previous?.value
            await self?.performReload()
        }
        reloadTask = task
        await task.value
    }

    private func performReload() async {
        loadGeneration += 1
        let generation = loadGeneration
        guard let repository = await openIfNeeded() else {
            hasLoadedOnce = true
            return
        }
        let now = nowMs
        let zone = zoneIdentifier
        do {
            let timeline = try await repository.today(nowMs: now, zone: zone)
            let rows = try await repository.medications(status: filter.status, search: searchText)
            let counts = try await repository.countsByStatus()
            guard generation == loadGeneration else { return }
            today = timeline
            medications = rows
            activeCount = counts[.active] ?? 0
            pausedCount = counts[.paused] ?? 0
            totalCount = counts.values.reduce(0, +)
            openError = nil
        } catch {
            guard generation == loadGeneration else { return }
            openError = String(describing: error)
        }
        hasLoadedOnce = true
    }

    /// Runs the maintenance rules (docs §7, §12) and bumps `revision` when rows changed.
    func materializeMissedAndCompletions() async {
        guard let repository = await openIfNeeded() else { return }
        let now = nowMs
        let zone = zoneIdentifier
        let missed = (try? await repository.materializeMissed(nowMs: now, zone: zone)) ?? 0
        let completed = (try? await repository.autoComplete(nowMs: now, zone: zone)) ?? []
        if missed > 0 || !completed.isEmpty { revision += 1 }
    }

    private func scheduleSearch() {
        searchTask?.cancel()
        searchTask = Task { [weak self] in
            try? await Task.sleep(nanoseconds: MedicationStore.searchDebounceNanoseconds)
            guard !Task.isCancelled else { return }
            await self?.reload()
        }
    }

    func bumpRevision() { revision += 1 }

    private func didChange() async {
        revision += 1
        await reload()
        replanReminders()
    }

    /// External change (notification action handled in the background): reload and re-plan.
    func handleExternalChange() {
        Task {
            await materializeMissedAndCompletions()
            await didChange()
        }
    }

    // MARK: - Reminders

    /// Debounced re-plan through the installed scheduler hook. No-op while notifications or dose
    /// reminders are off (the scheduler removes its pending requests in that case).
    func replanReminders() {
        replanTask?.cancel()
        replanTask = Task { [weak self] in
            try? await Task.sleep(nanoseconds: MedicationStore.replanDebounceNanoseconds)
            guard !Task.isCancelled else { return }
            await self?.replanRemindersNow()
        }
    }

    func replanRemindersNow() async {
        replanTask?.cancel()
        replanTask = nil
        guard let hook = reminderPlannerHook else { return }
        await hook()
    }

    /// The plan the scheduler installs (docs §10): empty while reminders are off.
    func currentReminderPlan(horizonMs: Int64 = 7 * 86_400_000, budget: Int? = MR.iosBudget) async -> ReminderPlan {
        guard notificationsEnabled, remindersEnabled, let repository = await openIfNeeded() else { return .empty }
        let now = nowMs
        let plan = (try? await repository.reminderPlan(nowMs: now, horizonMs: horizonMs, zone: zoneIdentifier, budget: budget)) ?? .empty
        try? await repository.markPlanned(nowMs: now)
        return plan
    }

    // MARK: - Medications

    /// Validates and creates (or edits) a medication; throws `MedicationStoreError.validation` with
    /// every field error, or `.lifecycle` when a schedule edit is not allowed in the current status.
    @discardableResult
    func save(draft: MedicationDraft, editing existingID: String? = nil) async throws -> Medication {
        let errors = draft.validationErrors
        guard errors.isEmpty else { throw MedicationStoreError.validation(errors) }
        guard let repository = await openIfNeeded() else { throw MedicationStoreError.notOpen }
        let now = nowMs
        var saved: Medication
        if let existingID {
            guard let existing = try await repository.medication(id: existingID) else { throw MedicationStoreError.notFound }
            saved = try await repository.update(draft: draft, existing: existing, nowMs: now)
        } else {
            saved = try await repository.create(draft: draft, nowMs: now, zone: zoneIdentifier)
        }
        if draft.removePhoto, saved.photoPath != nil {
            runtime.photos.delete(medicationID: saved.id)
            saved.photoPath = nil
            saved.updatedMs = now
            try await repository.database.updateMedication(saved)
        } else if let data = draft.newPhotoData, let path = try? runtime.photos.save(data, medicationID: saved.id) {
            saved.photoPath = path
            saved.updatedMs = now
            try await repository.database.updateMedication(saved)
        }
        await didChange()
        return saved
    }

    func pause(_ id: String) async { await lifecycle(.pause, id: id) }
    func resume(_ id: String) async { await lifecycle(.resume, id: id) }
    func stop(_ id: String) async { await lifecycle(.stop, id: id) }
    func complete(_ id: String) async { await lifecycle(.complete, id: id) }

    private func lifecycle(_ action: LifecycleAction, id: String) async {
        guard let repository = await openIfNeeded() else { return }
        do {
            try await repository.lifecycle(action, id: id, nowMs: nowMs)
            await didChange()
        } catch {
            openError = nil
            lastActionError = (error as? MedicationStoreError).map(Self.code(for:)) ?? String(describing: error)
        }
    }

    func setReminderEnabled(_ id: String, enabled: Bool) async {
        guard let repository = await openIfNeeded() else { return }
        _ = try? await repository.setReminderEnabled(id: id, enabled: enabled, nowMs: nowMs)
        await didChange()
    }

    func delete(_ id: String) async {
        guard let repository = await openIfNeeded() else { return }
        try? await repository.delete(id: id)
        await didChange()
    }

    /// Detail model; resolves the linked Health Record through the caller's `RecordsStore`.
    func detail(_ id: String, records: RecordsStore? = nil) async -> MedicationDetail? {
        guard let repository = await openIfNeeded(),
              var detail = try? await repository.detail(id: id, nowMs: nowMs, zone: zoneIdentifier) else { return nil }
        if let recordID = detail.medication.relatedRecordID {
            if let records, let record = await records.detail(id: recordID)?.record {
                detail.relatedRecord = record
            } else {
                detail.relatedRecordMissing = true
            }
        }
        return detail
    }

    func adherence(for id: String?) async -> MedicationAdherence {
        guard let repository = await openIfNeeded() else { return .none }
        return (try? await repository.adherence(medicationID: id, nowMs: nowMs, zone: zoneIdentifier)) ?? .none
    }

    func history(medicationID: String?, before: DoseLog? = nil, limit: Int = MedicationsRepository.historyPageSize) async -> [DoseLog] {
        guard let repository = await openIfNeeded() else { return [] }
        return (try? await repository.history(medicationID: medicationID, before: before, limit: limit)) ?? []
    }

    // MARK: - Doses

    /// Taken / Skip / Snooze / Undo on one occurrence. A failed action (`not_due_yet`, …) leaves
    /// the row untouched and is reported through the outcome and `lastActionError`.
    @discardableResult
    func act(_ action: DoseAction, on occurrence: DoseOccurrence, note: String? = nil, takenAt: Date? = nil,
             snoozeMinutes: Int? = nil) async -> DoseActionOutcome {
        guard let repository = await openIfNeeded() else { return .failure("not_open") }
        do {
            let outcome = try await repository.act(action, on: occurrence, nowMs: nowMs,
                                                   snoozeMinutes: snoozeMinutes ?? self.snoozeMinutes,
                                                   takenAtMs: takenAt.map { Int64($0.timeIntervalSince1970 * 1000) }, note: note)
            lastActionError = outcome.ok ? nil : outcome.error
            if outcome.ok { await didChange() }
            return outcome
        } catch {
            lastActionError = (error as? MedicationStoreError).map(Self.code(for:)) ?? String(describing: error)
            return .failure(lastActionError ?? "unknown")
        }
    }

    @discardableResult
    func logPRN(medicationID: String, at takenAt: Date? = nil, quantity: Double? = nil, note: String? = nil) async -> DoseActionOutcome {
        guard let repository = await openIfNeeded() else { return .failure("not_open") }
        do {
            let outcome = try await repository.logPRN(medicationID: medicationID, nowMs: nowMs,
                                                      takenAtMs: takenAt.map { Int64($0.timeIntervalSince1970 * 1000) },
                                                      quantity: quantity, note: note)
            lastActionError = outcome.ok ? nil : outcome.error
            if outcome.ok { await didChange() }
            return outcome
        } catch {
            lastActionError = (error as? MedicationStoreError).map(Self.code(for:)) ?? String(describing: error)
            return .failure(lastActionError ?? "unknown")
        }
    }

    /// Removes a PRN dose the user logged by mistake.
    func undoPRN(logID: String) async {
        guard let repository = await openIfNeeded() else { return }
        try? await repository.deleteLog(id: logID)
        await didChange()
    }

    func clearActionError() { lastActionError = nil }

    // MARK: - Navigation and banners

    /// Notification tap / deep link: show the Meds segment and optionally push a detail.
    func openFromNotification(_ medicationID: String?) {
        navigationRequest = medicationID.map { .detail($0) }
        tabRequest += 1
    }

    func requestTab() { tabRequest += 1 }

    func showBanner(_ message: String, systemImage: String = "checkmark.circle.fill") {
        banner = RecordsBanner(message: message, systemImage: systemImage)
        bannerTask?.cancel()
        bannerTask = Task { [weak self] in
            try? await Task.sleep(nanoseconds: 3_000_000_000)
            guard !Task.isCancelled else { return }
            self?.banner = nil
        }
    }

    // MARK: - Deletion

    /// Delete All Data: database, photos, pending reminders and the `medication*` preferences.
    func deleteAllData() async {
        if let cancel = reminderCancelHook { await cancel() }
        try? await runtime.deleteAllData()
        defaults.removeObject(forKey: MedicationSettings.remindersEnabledKey)
        defaults.removeObject(forKey: MedicationSettings.snoozeMinutesKey)
        defaults.removeObject(forKey: MedicationSettings.pendingRouteKey)
        today = .empty
        medications = []
        activeCount = 0
        pausedCount = 0
        totalCount = 0
        isOpen = false
        openError = nil
        revision += 1
    }

    /// After a cloud restore only preferences change (the database is never in the backup).
    func reloadAfterRestore() {
        Task { await didChange() }
    }

    static func code(for error: MedicationStoreError) -> String {
        switch error {
        case .notOpen: "not_open"
        case .notFound: "not_found"
        case .validation: "invalid"
        case .lifecycle(let code): code
        case .archive(let code): code
        }
    }
}
