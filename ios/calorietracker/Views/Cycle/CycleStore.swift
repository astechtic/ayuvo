import Foundation
import Observation

/// Cycle tracking for the UI (docs/cycle-tracking.md). Reads `cycle.sqlite` through `CycleRuntime`, adds the
/// platform periods from the health mirror, and caches one engine model + snapshot + trends, recomputed off the main
/// actor only when data, settings or the day change. Every write re-plans the reminders and queues the Health sync.
@Observable
@MainActor
final class CycleStore {
    static let shared = CycleStore()

    private(set) var hasLoaded = false
    private(set) var settings = CycleSettingsRecord()
    /// Live app periods, oldest first.
    private(set) var periods: [CyclePeriodRecord] = []
    /// Live day logs by day.
    private(set) var logs: [String: CycleDayLogRecord] = [:]
    private(set) var platformPeriods: [CyclePeriodInput] = []
    private(set) var model: CycleModel?
    private(set) var snapshot: CycleSnapshot?
    private(set) var trends: CycleTrends?
    /// The local day the cache was computed for.
    private(set) var today = CycleDates.todayString()
    /// Bumped after every reload.
    private(set) var revision = 0
    /// Last write error for the UI (validation codes are handled by the sheets themselves).
    var lastError: String?

    @ObservationIgnored private let runtime: CycleRuntime
    @ObservationIgnored private let config: CycleConfig
    @ObservationIgnored private var observer: NSObjectProtocol?
    @ObservationIgnored private let loadsPlatformPeriods: Bool
    @ObservationIgnored var clock: () -> Date = { Date() }
    /// Called after each write (reminders + Health sync); tests replace it.
    @ObservationIgnored var afterWrite: (() -> Void)? = {
        CycleReminderRuntime.shared.replanSoon()
        CycleHealthSync.shared.syncSoon()
    }

    init(runtime: CycleRuntime? = nil, config: CycleConfig = .shared, loadsPlatformPeriods: Bool = true) {
        self.runtime = runtime ?? .shared
        self.config = config
        self.loadsPlatformPeriods = loadsPlatformPeriods
        observer = NotificationCenter.default.addObserver(forName: .cycleDataDidChange, object: nil, queue: .main) { [weak self] _ in
            Task { @MainActor in await self?.reload() }
        }
    }

    var isSetUp: Bool { settings.setupDone }
    var options: CycleSettingsOptions { settings.options }
    var showFertility: Bool { settings.options.showFertility }

    /// The ongoing app period (a period with no end), if any.
    var ongoingPeriod: CyclePeriodRecord? { periods.last { $0.endDay == nil } }

    var repository: CycleRepository? { runtime.repository }

    // MARK: Loading

    /// Reloads rows and recomputes. Cheap enough to call on appear; the engine runs off the main actor.
    func reload() async {
        #if DEBUG
        await CycleDebugSeed.applyIfRequested(runtime: runtime)
        #endif
        guard let repository = await runtime.openRepository() else {
            hasLoaded = true
            return
        }
        let settings = (try? await repository.settings()) ?? CycleSettingsRecord()
        let periods = ((try? await repository.periods()) ?? []).filter { !$0.deleted }
        let logList = ((try? await repository.dayLogs()) ?? []).filter { !$0.deleted }
        let platform = loadsPlatformPeriods ? await Self.loadPlatformPeriods() : []
        let todayText = CycleDates.todayString(clock())
        let state = CycleState(today: todayText, settings: settings.engineInput,
                               periods: periods.map(\.engineInput) + platform, logs: logList.map(\.engineInput))
        let config = self.config
        let computed = await Task.detached(priority: .userInitiated) { () -> (CycleModel, CycleSnapshot, CycleTrends) in
            let model = CycleEngine.model(state, config)
            let trends = CycleEngine.trends(model, logs: state.logs, config)
            return (model, CycleEngine.snapshot(model: model, logs: state.logs, config), trends)
        }.value
        self.settings = settings
        self.periods = periods.sorted { $0.startDay < $1.startDay }
        var byDay: [String: CycleDayLogRecord] = [:]
        for log in logList { byDay[log.day] = log }
        self.logs = byDay
        self.platformPeriods = platform
        self.today = todayText
        model = computed.0
        snapshot = computed.1
        trends = computed.2
        hasLoaded = true
        revision += 1
    }

    /// Reloads when the local day changed since the last computation (foreground after midnight).
    func refreshIfDayChanged() async {
        if CycleDates.todayString(clock()) != today || !hasLoaded { await reload() }
    }

    /// Periods from Apple Health data already mirrored into `health_samples` (never Ayuvo's own samples).
    static func loadPlatformPeriods() async -> [CyclePeriodInput] {
        let health = HealthDataRuntime.shared
        guard FileManager.default.fileExists(atPath: health.databaseURL.path) else { return [] }
        guard await health.openIfNeeded(), let db = health.writer else { return [] }
        return (try? await CyclePlatformPeriods.load(from: db)) ?? []
    }

    // MARK: Day status for the calendar

    func status(_ day: String) -> CycleDayStatus? {
        guard let model, let n = CycleDay.ordinal(day) else { return nil }
        return CycleEngine.status(model, day: n)
    }

    func statuses(from: Int, to: Int) -> [Int: CycleDayStatus] {
        guard let model else { return [:] }
        var out: [Int: CycleDayStatus] = [:]
        for s in CycleEngine.dayStatuses(model, from: from, to: to) { out[CycleDay.o(s.day)] = s }
        return out
    }

    /// Normalized period (app or Health) that contains `day`.
    func period(containing day: String) -> CycleNormalizedPeriod? {
        guard let n = CycleDay.ordinal(day), let snapshot else { return nil }
        let todayN = CycleDay.o(today)
        return snapshot.periods.first { $0.start <= n && n <= ($0.end ?? todayN) }
    }

    /// The app period record for a normalized period (its merged members), if Ayuvo owns one.
    func appPeriod(for normalized: CyclePeriodRecord.ID) -> CyclePeriodRecord? {
        periods.first { $0.id == normalized }
    }

    // MARK: Writes

    private func nowMs() -> Int64 { CycleDates.nowMs(clock()) }

    private func didWrite() async {
        await reload()
        afterWrite?()
    }

    /// Finishes setup: the lengths (nil = default), options, and the last period start when known.
    func completeSetup(lastStart: String?, cycleLength: Int?, periodLength: Int?, options: CycleSettingsOptions) async {
        guard let repository = await runtime.openRepository() else { return }
        var record = (try? await repository.settings()) ?? CycleSettingsRecord()
        record.setupDone = true
        record.cycleLength = cycleLength
        record.periodLength = periodLength
        record.options = options
        try? await repository.saveSettings(record, nowMs: nowMs())
        if let lastStart, CycleDay.ordinal(lastStart) != nil {
            let length = periodLength ?? config.defaults.periodLength
            let todayN = CycleDay.o(CycleDates.todayString(clock()))
            let startN = CycleDay.o(lastStart)
            // A start within the usual length is still going; an older one is closed at the usual length.
            let end: String? = startN + length - 1 >= todayN ? nil : CycleDay.string(startN + length - 1)
            _ = try? await repository.savePeriod(id: nil, start: lastStart, end: end, today: CycleDates.todayString(clock()), nowMs: nowMs())
        }
        await didWrite()
    }

    func saveSettings(_ record: CycleSettingsRecord) async {
        guard let repository = await runtime.openRepository() else { return }
        try? await repository.saveSettings(record, nowMs: nowMs())
        await didWrite()
    }

    func updateOptions(_ change: (inout CycleSettingsOptions) -> Void) async {
        var record = settings
        var options = record.options
        change(&options)
        record.options = options
        await saveSettings(record)
    }

    /// One tap: "Period started" (today, or the given day) or "Period ended".
    func periodStarted(on day: String? = nil) async {
        await setPeriodDay(day ?? CycleDates.todayString(clock()), on: true)
    }

    func periodEnded(on day: String? = nil) async {
        guard let ongoing = ongoingPeriod else { return }
        let end = day ?? CycleDates.todayString(clock())
        _ = await savePeriod(id: ongoing.id, start: ongoing.startDay, end: max(end, ongoing.startDay))
    }

    /// "This is a period day". Returns the error code, if any.
    @discardableResult
    func setPeriodDay(_ day: String, on: Bool) async -> String? {
        guard let repository = await runtime.openRepository() else { return "unavailable" }
        do {
            try await repository.setPeriodDay(day, on: on, today: CycleDates.todayString(clock()), nowMs: nowMs())
            await didWrite()
            return nil
        } catch CycleStoreError.validation(let codes) {
            return codes.first
        } catch {
            return "unavailable"
        }
    }

    /// Saves (inserts when `id` is nil) a period. Returns the `validate_period` codes on failure.
    func savePeriod(id: String?, start: String, end: String?) async -> [String] {
        guard let repository = await runtime.openRepository() else { return ["unavailable"] }
        do {
            try await repository.savePeriod(id: id, start: start, end: end, today: CycleDates.todayString(clock()), nowMs: nowMs())
            await didWrite()
            return []
        } catch CycleStoreError.validation(let codes) {
            return codes
        } catch {
            return ["unavailable"]
        }
    }

    /// Validation preview for the period sheet.
    func validate(id: String?, start: String, end: String?) -> CycleValidation {
        CycleEngine.validatePeriod(CyclePeriodCandidate(id: id, start: start, end: end),
                                   periods: periods.map(\.engineInput), today: CycleDates.todayString(clock()), config)
    }

    /// "Merge": the overlapping app periods are removed and this one covers the merged range.
    func mergeSave(id: String?, start: String, end: String?) async -> [String] {
        let check = validate(id: id, start: start, end: end)
        guard check.errors == ["overlap"], let mergedStart = check.mergedStart else { return check.errors }
        guard let repository = await runtime.openRepository() else { return ["unavailable"] }
        let now = nowMs()
        for other in check.overlaps where other != id {
            try? await repository.deletePeriod(id: other, nowMs: now)
        }
        do {
            try await repository.savePeriod(id: id, start: mergedStart, end: check.mergedEnd, today: CycleDates.todayString(clock()), nowMs: now)
        } catch CycleStoreError.validation(let codes) {
            await didWrite()
            return codes
        } catch {
            await didWrite()
            return ["unavailable"]
        }
        await didWrite()
        return []
    }

    func deletePeriod(id: String) async {
        guard let repository = await runtime.openRepository() else { return }
        try? await repository.deletePeriod(id: id, nowMs: nowMs())
        await didWrite()
    }

    func dayLog(_ day: String) -> CycleDayLogRecord? { logs[day] }

    /// Saves the day's log (an empty log clears it). Returns an error code on failure.
    @discardableResult
    func saveDayLog(_ log: CycleDayLogRecord) async -> String? {
        guard let repository = await runtime.openRepository() else { return "unavailable" }
        do {
            try await repository.saveDayLog(log, today: CycleDates.todayString(clock()), nowMs: nowMs(), platformPeriods: platformPeriods)
            await didWrite()
            return nil
        } catch CycleStoreError.validation(let codes) {
            return codes.first
        } catch {
            return "unavailable"
        }
    }

    /// "Delete all cycle data": rows, settings and (with sync on) Ayuvo's samples in Apple Health.
    func deleteAll() async {
        let hadSync = settings.options.healthSync
        guard let repository = await runtime.openRepository() else { return }
        if hadSync { await CycleHealthSync.shared.removeAllWrittenSamples() }
        try? await repository.deleteAll()
        await reload()
        await CycleReminderRuntime.shared.replan()
    }

    // MARK: Read-only Health data for the day sheet

    /// Ovulation test results and basal body temperature from Apple Health on `day` ("Positive", "36.5 °C").
    func healthLines(day: String) async -> [String] {
        let health = HealthDataRuntime.shared
        guard FileManager.default.fileExists(atPath: health.databaseURL.path), await health.openIfNeeded(),
              let db = health.writer, let n = CycleDay.ordinal(day) else { return [] }
        let start = Int64(CycleDates.date(ordinal: n).timeIntervalSince1970 * 1000) - 13 * 3_600_000
        let end = start + 26 * 3_600_000
        var lines: [String] = []
        if let rows = try? await db.rows(type: "ovulation_test", startMs: start, endMs: end) {
            for row in rows where !row.isDeleted && row.localDay == day {
                let result: String
                switch row.categoryValue {
                case 2: result = String(localized: "Positive", comment: "Ovulation test result")
                case 1: result = String(localized: "Negative", comment: "Ovulation test result")
                case 4: result = String(localized: "Estrogen surge", comment: "Ovulation test result")
                default: result = String(localized: "Indeterminate", comment: "Ovulation test result")
                }
                lines.append(String(localized: "Ovulation test: \(result)", comment: "Cycle day sheet: read-only Apple Health value"))
            }
        }
        if let rows = try? await db.rows(type: "basal_body_temperature", startMs: start, endMs: end) {
            for row in rows where !row.isDeleted && row.localDay == day {
                guard let value = row.value else { continue }
                let measurement = Measurement(value: value, unit: UnitTemperature.celsius)
                lines.append(String(localized: "Basal body temperature: \(measurement.formatted(.measurement(width: .abbreviated, numberFormatStyle: .number.precision(.fractionLength(2)))))",
                                    comment: "Cycle day sheet: read-only Apple Health value"))
            }
        }
        return lines
    }
}
