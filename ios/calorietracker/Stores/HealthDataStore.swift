import Foundation
import HealthKit
import UIKit

struct HealthHomeTileModel: Identifiable, Equatable {
    var typeID: String
    var valueText: String
    var unitText: String
    var at: Date?
    var sparkline: [Double]

    var id: String { typeID }
}

struct HealthHomeSnapshot: Equatable {
    var tiles: [HealthHomeTileModel]
    var generatedAt: Date
}

/// Latest day total for a cumulative / duration / session type (hub rows read as "18,465 · Today",
/// never as the last 10-minute chunk).
struct HealthDayTotal: Equatable {
    var day: String
    var value: Double
    var isToday: Bool
}

/// Main-actor façade over the health mirror: sync orchestration, snapshots for Home /
/// hub / detail, pinned tiles, Coach context. Never stores health values in
/// UserDefaults — only the cloud-backed preferences (`summaryFavourites` / legacy `healthHomeTiles`,
/// `coachHealthDataEnabled`, `coachHealthDataConsentedAt`, `healthGlucoseUnit`) and the
/// device-local `healthKitHub*` throttles (excluded from backup by prefix).
@Observable
@MainActor
final class HealthDataStore {
    static let homeTilesKey = "healthHomeTiles"
    static let coachEnabledKey = "coachHealthDataEnabled"
    static let coachConsentedAtKey = "coachHealthDataConsentedAt"
    static let lastSyncAtKey = "healthKitHubLastSyncAt"
    static let promptedVersionKey = "healthKitHubPromptedVersion"
    static let rateLimitedUntilKey = "healthKitHubRateLimitedUntil"
    static let appOpenThrottle: TimeInterval = 5 * 60

    private let runtime: HealthDataRuntime
    private let defaults: UserDefaults
    let calendar: Calendar

    private(set) var isSyncing = false
    private(set) var progress: HealthSyncProgress?
    private(set) var lastSyncAt: Date?
    private(set) var lastOutcome: HealthSyncOutcome?
    private(set) var homeSnapshot: HealthHomeSnapshot?
    private(set) var typeSummaries: [HealthTypeSummary] = []
    /// `typeSummaries` keyed by type for O(1) row lookups while the hub lists ~200 types.
    private(set) var summariesByID: [String: HealthTypeSummary] = [:]
    /// Day totals for cumulative / duration / session types with data (see `HealthDayTotal`).
    private(set) var dayTotals: [String: HealthDayTotal] = [:]
    private(set) var syncStates: [String: HealthSyncStateRow] = [:]
    private(set) var typeMeta: [String: HealthTypeMetaRow] = [:]
    /// nil = unknown, true = HealthKit would show the sheet (nothing granted yet).
    private(set) var needsGrant: Bool?
    private(set) var databaseSizeBytes: Int64 = 0
    private(set) var snapshotRevision = 0
    private(set) var pinnedTypeIDs: [String]
    private(set) var coachHealthDataEnabled: Bool
    private(set) var coachConsentedAt: String?

    private var syncTask: Task<HealthSyncOutcome, Never>?
    private var backgroundTaskID: UIBackgroundTaskIdentifier = .invalid

    init(runtime: HealthDataRuntime = .shared, defaults: UserDefaults = .standard, calendar: Calendar = .current) {
        self.runtime = runtime
        self.defaults = defaults
        self.calendar = calendar
        self.pinnedTypeIDs = MetricPins.load(defaults: defaults)
        self.coachHealthDataEnabled = (defaults.object(forKey: Self.coachEnabledKey) as? Bool) ?? false
        self.coachConsentedAt = defaults.string(forKey: Self.coachConsentedAtKey)
        let lastSync = defaults.double(forKey: Self.lastSyncAtKey)
        self.lastSyncAt = lastSync > 0 ? Date(timeIntervalSince1970: lastSync) : nil
        runtime.onAuthorizationChanged = { [weak self] in
            self?.authorizationDidChange()
        }
    }

    // MARK: - Derived state

    /// Mirrors the Apple Health toggle; the hub is read-only while this is off.
    var isEnabled: Bool { defaults.bool(forKey: "healthKitEnabled") }
    var isHealthDataAvailable: Bool { HKHealthStore.isHealthDataAvailable() }
    var isDegraded: Bool { runtime.isDegraded }
    var openError: Error? { runtime.openError }
    /// Home shows the card whenever Health exists on the device and tiles are not hidden.
    var showsHomeTile: Bool { isHealthDataAvailable && !pinnedTypeIDs.isEmpty }
    /// Summary favourites that are health types (app metric keys filtered out).
    var pinnedHealthTypeIDs: [String] { pinnedTypeIDs.filter { !$0.hasPrefix("app:") } }
    var hasAnyData: Bool { typeSummaries.contains { $0.count > 0 } }
    var typeCountWithData: Int { typeSummaries.filter { $0.count > 0 }.count }
    var isImportingHistory: Bool { syncStates.values.contains { $0.isImporting } }
    var isLocked: Bool { syncStates.values.contains { $0.isLocked } }

    /// Earliest date HealthKit lets us read for `typeID` (iOS 27 limited grant), if any.
    func limitedHistoryBefore(_ typeID: String) -> Date? {
        syncStates[typeID]?.earliestAuthorizedMs.map { Date(timeIntervalSince1970: Double($0) / 1000) }
    }

    var anyLimitedHistoryBefore: Date? {
        syncStates.values.compactMap(\.earliestAuthorizedMs).max().map { Date(timeIntervalSince1970: Double($0) / 1000) }
    }

    func metricType(for typeID: String) -> HealthMetricType {
        HealthMetricRegistry.resolve(typeID: typeID, metaRows: typeMeta, unit: summariesByID[typeID]?.latest?.unit ?? "count")
    }

    func summary(for typeID: String) -> HealthTypeSummary? {
        summariesByID[typeID]
    }

    func dayTotal(for typeID: String) -> HealthDayTotal? {
        dayTotals[typeID]
    }

    /// Registry types plus unknown mirrored types, for the hub's category lists.
    var knownTypes: [HealthMetricType] {
        var types = HealthMetricRegistry.iOSTypes
        let known = Set(types.map(\.id))
        for summary in typeSummaries where !known.contains(summary.typeID) {
            types.append(metricType(for: summary.typeID))
        }
        return types
    }

    func types(in category: HealthCategory) -> [HealthMetricType] {
        let counts = summariesByID
        return knownTypes
            .filter { $0.category == category }
            .sorted { lhs, rhs in
                let l = counts[lhs.id]?.count ?? 0, r = counts[rhs.id]?.count ?? 0
                if (l > 0) != (r > 0) { return l > 0 }
                return lhs.displayName.localizedCaseInsensitiveCompare(rhs.displayName) == .orderedAscending
            }
    }

    // MARK: - Sync

    /// App-open entry point: throttled, never overlaps a running sync.
    func syncIfNeeded(_ trigger: HealthSyncTrigger) {
        guard isEnabled, isHealthDataAvailable else { return }
        if trigger == .appOpen, let lastSyncAt, Date().timeIntervalSince(lastSyncAt) < Self.appOpenThrottle {
            if typeSummaries.isEmpty {
                Task { await refreshSnapshots() }
            }
            return
        }
        guard syncTask == nil else { return }
        Task { _ = await sync(trigger) }
    }

    /// Pull-to-refresh / Settings "Sync now": waits for a running sync, then runs one.
    @discardableResult
    func sync(_ trigger: HealthSyncTrigger) async -> HealthSyncOutcome {
        guard isEnabled else { return .skipped(reason: "health sync is off") }
        guard isHealthDataAvailable else { return .skipped(reason: "health data unavailable") }
        if let running = syncTask {
            _ = await running.value
        }
        guard await runtime.openIfNeeded(),
              let engine = runtime.makeEngine(types: HealthMetricRegistry.syncableTypes())
        else {
            lastOutcome = .failed("database unavailable")
            return .failed("database unavailable")
        }

        isSyncing = true
        progress = HealthSyncProgress(typesTotal: engine.types.count)
        beginBackgroundTask()

        let task = Task<HealthSyncOutcome, Never> { [weak self] in
            let detached = Task.detached(priority: .utility) {
                await engine.sync(trigger: trigger) { update in
                    Task { @MainActor [weak self] in
                        self?.progress = update
                    }
                }
            }
            return await withTaskCancellationHandler {
                await detached.value
            } onCancel: {
                detached.cancel()
            }
        }
        syncTask = task
        let outcome = await task.value
        syncTask = nil
        isSyncing = false
        progress = nil
        lastOutcome = outcome
        if case .synced = outcome {
            let now = Date()
            lastSyncAt = now
            defaults.set(now.timeIntervalSince1970, forKey: Self.lastSyncAtKey)
        }
        await refreshSnapshots()
        endBackgroundTask()
        return outcome
    }

    func cancelSync() {
        syncTask?.cancel()
    }

    /// Called after `requestAuthorization()` widened the grant, or from the connect screens.
    func authorizationDidChange() {
        guard isEnabled else { return }
        needsGrant = nil
        Task {
            await refreshAuthorizationStatus()
            _ = await sync(.authorizationChanged)
        }
    }

    func refreshAuthorizationStatus() async {
        needsGrant = await runtime.hkReader.authorizationRequestNeeded(types: HealthMetricRegistry.syncableTypes())
    }

    /// Apple Health toggle turned off: stop syncing, keep every row (read-only hub).
    func disableSync() {
        cancelSync()
    }

    /// Scene went to background — nothing to do until background delivery (Phase 5).
    func sceneDidEnterBackground() {}

    /// Cloud restore never carries the mirror; only device-local throttles are reset so the
    /// hub re-checks authorization and shows "Grant access" instead of "Nothing shared yet".
    func reloadAfterRestore() {
        defaults.removeObject(forKey: Self.lastSyncAtKey)
        defaults.removeObject(forKey: Self.rateLimitedUntilKey)
        lastSyncAt = nil
        needsGrant = nil
        pinnedTypeIDs = MetricPins.load(defaults: defaults)
        coachHealthDataEnabled = (defaults.object(forKey: Self.coachEnabledKey) as? Bool) ?? false
        coachConsentedAt = defaults.string(forKey: Self.coachConsentedAtKey)
        Task {
            await refreshAuthorizationStatus()
            await refreshSnapshots()
        }
    }

    // MARK: - Snapshots

    private func database() async -> HealthDatabase? {
        guard await runtime.openIfNeeded() else { return nil }
        return runtime.reader ?? runtime.writer
    }

    func refreshSnapshots() async {
        guard let reader = await database() else {
            databaseSizeBytes = 0
            return
        }
        do {
            let summaries = try await reader.typeSummaries()
            let states = try await reader.allSyncStates()
            let meta = try await reader.allTypeMeta()
            typeSummaries = summaries
            summariesByID = Dictionary(summaries.map { ($0.typeID, $0) }, uniquingKeysWith: { first, _ in first })
            syncStates = Dictionary(uniqueKeysWithValues: states.map { ($0.typeID, $0) })
            typeMeta = Dictionary(uniqueKeysWithValues: meta.map { ($0.typeID, $0) })
            dayTotals = await buildDayTotals(reader: reader, summaries: summaries)
            homeSnapshot = await buildHomeSnapshot(reader: reader, summaries: summaries)
            databaseSizeBytes = runtime.databaseSizeBytes()
            snapshotRevision += 1
        } catch {
            // Reads race a wipe only during Delete All Data; the next refresh repairs the view.
        }
    }

    private func dayKey(_ date: Date) -> String {
        HealthRollupMath.dayKey(ms: HealthSampleMapper.ms(date), offsetS: nil, calendar: calendar)
    }

    /// Today's total (or the latest day with one in the past week) per cumulative / duration /
    /// session type — one indexed rollup read per type with data.
    private func buildDayTotals(reader: HealthDatabase, summaries: [HealthTypeSummary]) async -> [String: HealthDayTotal] {
        let now = Date()
        let today = dayKey(now)
        let weekAgo = dayKey(calendar.date(byAdding: .day, value: -6, to: now) ?? now)
        var totals: [String: HealthDayTotal] = [:]
        for summary in summaries where summary.count > 0 {
            let type = metricType(for: summary.typeID)
            guard type.kind == .cumulative || type.kind == .duration || type.kind == .session else { continue }
            let rollups = (try? await reader.dailyRollups(type: summary.typeID, fromDay: weekAgo, toDay: today)) ?? []
            let candidates = rollups.compactMap { rollup -> (String, Double)? in
                guard let value = HealthChartSeriesBuilder.primaryValue(rollup, type: type), value > 0 else { return nil }
                return (rollup.day, value)
            }
            guard let chosen = candidates.first(where: { $0.0 == today }) ?? candidates.last else { continue }
            totals[summary.typeID] = HealthDayTotal(day: chosen.0, value: chosen.1, isToday: chosen.0 == today)
        }
        return totals
    }

    private func buildHomeSnapshot(reader: HealthDatabase, summaries: [HealthTypeSummary]) async -> HealthHomeSnapshot {
        let tiles = await tileModels(typeIDs: pinnedTypeIDs.filter { !$0.hasPrefix("app:") }, reader: reader, summaries: summaries)
        return HealthHomeSnapshot(tiles: tiles, generatedAt: Date())
    }

    /// Tiles for any health ids (home-screen widgets), formatted like the Summary favourites.
    /// Empty while Health sync is off; never opens a mirror that sync has not created.
    func widgetTiles(for typeIDs: [String]) async -> [HealthHomeTileModel] {
        guard isEnabled, !typeIDs.isEmpty, let reader = await database() else { return [] }
        return await tileModels(typeIDs: typeIDs, reader: reader, summaries: typeSummaries)
    }

    private func tileModels(typeIDs: [String], reader: HealthDatabase, summaries: [HealthTypeSummary]) async -> [HealthHomeTileModel] {
        let now = Date()
        let today = dayKey(now)
        let weekAgo = dayKey(calendar.date(byAdding: .day, value: -6, to: now) ?? now)
        var tiles: [HealthHomeTileModel] = []
        for typeID in typeIDs {
            let type = metricType(for: typeID)
            let unit = HealthUnitFormatting.unitLabel(for: type)
            guard let summary = summaries.first(where: { $0.typeID == typeID }), summary.count > 0 else {
                tiles.append(HealthHomeTileModel(typeID: typeID, valueText: "—", unitText: unit, at: nil, sparkline: []))
                continue
            }
            let rollups = (try? await reader.dailyRollups(type: typeID, fromDay: weekAgo, toDay: today)) ?? []
            let sparkline = rollups.compactMap { HealthChartSeriesBuilder.primaryValue($0, type: type) }
            var tile = HealthHomeTileModel(typeID: typeID, valueText: "—", unitText: unit, at: nil, sparkline: sparkline)
            if type.isSleep {
                if let last = (try? await reader.latestRollup(type: typeID)) ?? nil, let seconds = last.durationS ?? last.sum {
                    tile.valueText = HealthUnitFormatting.durationText(seconds: seconds)
                    tile.unitText = ""
                    tile.at = last.lastAtMs.map { Date(timeIntervalSince1970: Double($0) / 1000) }
                }
            } else if type.kind == .cumulative || type.kind == .duration || type.kind == .session {
                let todayRollup = rollups.first { $0.day == today }
                let value = HealthChartSeriesBuilder.primaryValue(todayRollup ?? HealthDailyRollupRow(typeID: typeID, day: today, tz: calendar.timeZone.identifier, sum: 0, durationS: 0), type: type) ?? 0
                let display = HealthUnitFormatting.display(value, type: type)
                tile.valueText = display.value
                tile.unitText = display.unit
                tile.at = todayRollup?.lastAtMs.map { Date(timeIntervalSince1970: Double($0) / 1000) }
            } else if let latest = summary.latest {
                if type.isBloodPressure {
                    tile.valueText = HealthUnitFormatting.bloodPressureText(systolic: latest.value, diastolic: latest.value2)
                    tile.unitText = "mmHg"
                } else if let value = latest.value {
                    let display = HealthUnitFormatting.display(value, type: type)
                    tile.valueText = display.value
                    tile.unitText = display.unit
                } else if let text = latest.valueText {
                    tile.valueText = text
                    tile.unitText = ""
                }
                tile.at = latest.endDate
            }
            tiles.append(tile)
        }
        return tiles
    }

    // MARK: - Detail data

    func series(typeID: String, range: HealthDetailRange, anchor: Date) async -> HealthChartSeries? {
        guard let reader = await database() else { return nil }
        let type = metricType(for: typeID)
        let interval = range.interval(containing: anchor, calendar: calendar)
        let fromDay = dayKey(interval.start)
        let toDay = dayKey(interval.end.addingTimeInterval(-1))
        do {
            let rollups = try await reader.dailyRollups(type: typeID, fromDay: fromDay, toDay: toDay)
            let rows: [HealthSampleRow]
            if type.isSleep {
                rows = try await reader.rowsForDays(type: typeID, fromDay: fromDay, toDay: toDay)
            } else if range == .day {
                rows = try await reader.rows(type: typeID, startMs: HealthSampleMapper.ms(interval.start), endMs: HealthSampleMapper.ms(interval.end))
            } else {
                rows = []
            }
            let calendar = self.calendar
            return await Task.detached(priority: .userInitiated) {
                HealthChartSeriesBuilder.build(range: range, anchor: anchor, type: type, rows: rows, rollups: rollups, calendar: calendar)
            }.value
        } catch {
            return nil
        }
    }

    func samplesPage(typeID: String, before key: (endMs: Int64, id: String)?, limit: Int = 50) async -> [HealthSampleRow] {
        guard let reader = await database() else { return [] }
        return (try? await reader.samplesPage(type: typeID, before: key, limit: limit)) ?? []
    }

    func sources(typeID: String?) async -> [HealthDatabase.SourceUsage] {
        guard let reader = await database() else { return [] }
        return (try? await reader.sourceUsage(type: typeID)) ?? []
    }

    func nights(from: Date, to: Date) async -> [HealthSleepNight] {
        guard let reader = await database() else { return [] }
        let rows = (try? await reader.rowsForDays(type: "sleep", fromDay: dayKey(from), toDay: dayKey(to))) ?? []
        return HealthSleepAnalysis.nights(rows: rows, calendar: calendar)
    }

    // MARK: - Maintenance

    func rebuildRollups() async {
        guard await runtime.openIfNeeded(), let writer = runtime.writer else { return }
        let types = typeSummaries.map { metricType(for: $0.typeID) }
        let tz = calendar.timeZone.identifier
        let calendar = self.calendar
        let bundleID = Bundle.main.bundleIdentifier ?? ""
        await Task.detached(priority: .utility) {
            for type in types {
                _ = try? await writer.rebuildAllRollups(type: type, tz: tz, calendar: calendar, ownBundleID: bundleID)
            }
        }.value
        await refreshSnapshots()
    }

    /// "Clear synced health data": removes the mirror, keeps preferences and consent.
    func clearSyncedData() async {
        cancelSync()
        if let running = syncTask {
            _ = await running.value
        }
        try? await runtime.closeAndDeleteFiles()
        defaults.removeObject(forKey: Self.lastSyncAtKey)
        defaults.removeObject(forKey: Self.rateLimitedUntilKey)
        lastSyncAt = nil
        typeSummaries = []
        summariesByID = [:]
        dayTotals = [:]
        syncStates = [:]
        typeMeta = [:]
        homeSnapshot = nil
        databaseSizeBytes = 0
        lastOutcome = nil
        snapshotRevision += 1
    }

    /// Delete All Data: the mirror plus every hub preference.
    func deleteAllData() async {
        await clearSyncedData()
        for key in [Self.homeTilesKey, MetricPins.key, Self.coachEnabledKey, Self.coachConsentedAtKey, HealthGlucoseUnit.storageKey, Self.promptedVersionKey] {
            defaults.removeObject(forKey: key)
        }
        pinnedTypeIDs = MetricPins.load(defaults: defaults)
        coachHealthDataEnabled = false
        coachConsentedAt = nil
    }

    // MARK: - Preferences

    /// Summary favourites (`summaryFavourites`): app metric keys (`app:…`) and health type ids.
    func setPinnedTypeIDs(_ ids: [String]) {
        pinnedTypeIDs = Array(ids.prefix(MetricPins.max))
        MetricPins.save(pinnedTypeIDs, defaults: defaults)
        Task { await refreshSnapshots() }
    }

    func isPinned(_ typeID: String) -> Bool { pinnedTypeIDs.contains(typeID) }

    func togglePin(_ typeID: String) {
        if isPinned(typeID) {
            setPinnedTypeIDs(pinnedTypeIDs.filter { $0 != typeID })
        } else {
            guard pinnedTypeIDs.count < MetricPins.max else { return }
            setPinnedTypeIDs(pinnedTypeIDs + [typeID])
        }
    }

    /// Home card on/off. Off stores an empty list; on restores the defaults.
    func setHomeTilesVisible(_ visible: Bool) {
        setPinnedTypeIDs(visible ? MetricPins.defaultIDs : [])
    }

    /// The visible consent control (onboarding, connect screen, Settings). Never flipped silently.
    func setCoachAccess(_ enabled: Bool) {
        coachHealthDataEnabled = enabled
        defaults.set(enabled, forKey: Self.coachEnabledKey)
        let stamp = ISO8601DateFormatter().string(from: Date())
        coachConsentedAt = stamp
        defaults.set(stamp, forKey: Self.coachConsentedAtKey)
    }

    // MARK: - Coach

    /// Nil unless Health sync and Coach health access are both on.
    func coachContext() async -> CoachHealthContext? {
        guard isEnabled, coachHealthDataEnabled, let reader = await database() else { return nil }
        if typeSummaries.isEmpty {
            await refreshSnapshots()
        }
        let query = CoachHealthQuery.live(reader: reader, calendar: calendar, typeMeta: typeMeta)
        let lines = await HealthCoachPromptSummary.lines(query: query, calendar: calendar)
        let context = HealthCoachContext(
            enabled: true,
            typeCount: typeCountWithData,
            lastSync: lastSyncAt,
            sevenDayLines: lines
        )
        return CoachHealthContext(query: query, context: context)
    }

    // MARK: - Export / import (ayuvo-health-data)

    /// Writes the archive into a fresh temp directory; the caller deletes it after sharing.
    func exportHealthData(progress: @escaping @Sendable (Int) -> Void = { _ in }) async throws -> HealthExportSummary {
        guard let reader = await database() else { throw HealthDBError(kind: .open, code: 0, message: "database unavailable") }
        let destination = try HealthExporter.makeDestinationURL(calendar: calendar)
        let appVersion = Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "0"
        let calendar = self.calendar
        let typeMeta = self.typeMeta
        return try await Task.detached(priority: .utility) {
            try await HealthExporter.export(
                reader: reader,
                to: destination,
                appVersion: appVersion,
                calendar: calendar,
                typeMeta: typeMeta,
                progress: progress
            )
        }.value
    }

    /// Applies an archive, rebuilds rollups for the touched types, then runs a sync so
    /// platform rows re-attach to their anchors.
    func importHealthData(
        preview: HealthImportPreview,
        mode: HealthImportMode,
        progress: @escaping @Sendable (Int) -> Void = { _ in }
    ) async throws -> HealthImportResult {
        cancelSync()
        if let running = syncTask {
            _ = await running.value
        }
        guard await runtime.openIfNeeded(), let writer = runtime.writer else {
            throw HealthDBError(kind: .open, code: 0, message: "database unavailable")
        }
        let calendar = self.calendar
        let bundleID = Bundle.main.bundleIdentifier ?? ""
        let result = try await Task.detached(priority: .utility) {
            try await HealthImporter.apply(
                preview: preview,
                mode: mode,
                writer: writer,
                calendar: calendar,
                ownBundleID: bundleID,
                progress: progress
            )
        }.value
        await refreshSnapshots()
        if isEnabled {
            Task { _ = await sync(.importCompleted) }
        }
        return result
    }

    // MARK: - Background task

    private func beginBackgroundTask() {
        guard backgroundTaskID == .invalid else { return }
        backgroundTaskID = UIApplication.shared.beginBackgroundTask(withName: "HealthDataSync") { [weak self] in
            self?.cancelSync()
            self?.endBackgroundTask()
        }
    }

    private func endBackgroundTask() {
        guard backgroundTaskID != .invalid else { return }
        UIApplication.shared.endBackgroundTask(backgroundTaskID)
        backgroundTaskID = .invalid
    }
}
