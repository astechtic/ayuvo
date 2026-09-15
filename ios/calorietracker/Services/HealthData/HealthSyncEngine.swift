import Foundation
import HealthKit

nonisolated enum HealthSyncTrigger: String, Sendable {
    case appOpen
    case manual
    case authorizationChanged
    case importCompleted
}

nonisolated enum HealthSyncOutcome: Sendable, Equatable {
    case synced(types: Int, rows: Int)
    case skipped(reason: String)
    case locked
    case cancelled
    case failed(String)
}

nonisolated struct HealthSyncProgress: Sendable, Equatable {
    var typesTotal = 0
    var typesDone = 0
    var rowsCommitted = 0
    var importing = false
    var currentTypeID: String?

    var fraction: Double {
        guard typesTotal > 0 else { return 0 }
        return Double(typesDone) / Double(typesTotal)
    }
}

/// Anchored HealthKit → SQLite sync. Pure with respect to threading: it is entered only
/// from `Task.detached(priority: .utility)` (see `HealthDataStore.sync`), reads through a
/// `HealthKitReading`, and commits every page through the single writer actor.
///
/// Per type: (1) apply the iOS 27 limited-history boundary (a boundary that moved earlier
/// or disappeared resets the cursor); (2) with no cursor, bootstrap the last 7 days with a
/// plain sample query so Home/hub render within seconds — the bootstrap never stores an
/// anchor, an anchor from a date-bounded query would skip older history; (3) loop
/// `HKAnchoredObjectQueryDescriptor(limit: 500)` until added and deleted are both empty,
/// committing rows + tombstones + sources + cursor per page in one transaction; (4) the
/// first completed pass flips `backfill_done` and rebuilds every day's rollup once, later
/// passes rebuild only the days a page touched. Errors are isolated per type; a locked
/// HealthKit database stops the run quietly. `last_sync_ms` is only stamped on commits.
nonisolated final class HealthSyncEngine: Sendable {
    nonisolated struct Configuration: Sendable {
        var pageLimit = 500
        var bootstrapLimit = 5000
        var bootstrapDays = 7
        var maxConcurrentTypes = 3
    }

    let reader: any HealthKitReading
    let database: HealthDatabase
    let types: [HealthMetricType]
    let calendar: Calendar
    let now: @Sendable () -> Date
    let configuration: Configuration
    let ownBundleID: String

    init(
        reader: any HealthKitReading,
        database: HealthDatabase,
        types: [HealthMetricType],
        calendar: Calendar = .current,
        now: @escaping @Sendable () -> Date = { Date() },
        configuration: Configuration = Configuration(),
        ownBundleID: String = Bundle.main.bundleIdentifier ?? "com.ayuvo.health"
    ) {
        self.reader = reader
        self.database = database
        self.types = types
        self.calendar = calendar
        self.now = now
        self.configuration = configuration
        self.ownBundleID = ownBundleID
    }

    // MARK: - Run

    func sync(trigger: HealthSyncTrigger, progress: @escaping @Sendable (HealthSyncProgress) -> Void = { _ in }) async -> HealthSyncOutcome {
        guard !types.isEmpty else { return .skipped(reason: "no types") }
        let limits = await reader.earliestAuthorizedSampleDates(types: types)
        let run = RunState(typesTotal: types.count, progress: progress)
        let ordered = types.sorted { lhs, rhs in
            let lt = HealthMetricRegistry.tier(of: lhs), rt = HealthMetricRegistry.tier(of: rhs)
            if lt != rt { return lt < rt }
            return lhs.id < rhs.id
        }
        let parallel = ordered.filter { HealthMetricRegistry.tier(of: $0) != .t3 }
        let serial = ordered.filter { HealthMetricRegistry.tier(of: $0) == .t3 }

        await withTaskGroup(of: Void.self) { group in
            var nextIndex = 0
            while nextIndex < parallel.count, nextIndex < max(1, configuration.maxConcurrentTypes) {
                let type = parallel[nextIndex]
                nextIndex += 1
                group.addTask { await self.syncType(type, run: run, limits: limits) }
            }
            while await group.next() != nil {
                let locked = await run.isLocked
                if Task.isCancelled || locked { break }
                guard nextIndex < parallel.count else { continue }
                let type = parallel[nextIndex]
                nextIndex += 1
                group.addTask { await self.syncType(type, run: run, limits: limits) }
            }
        }

        for type in serial {
            let locked = await run.isLocked
            if Task.isCancelled || locked { break }
            await syncType(type, run: run, limits: limits)
        }

        if await run.isLocked { return .locked }
        if Task.isCancelled { return .cancelled }
        let (done, rows, failures) = await run.summary()
        if done == 0, !failures.isEmpty {
            return .failed(failures.values.first ?? "sync failed")
        }
        return .synced(types: done, rows: rows)
    }

    // MARK: - One type

    private func syncType(_ type: HealthMetricType, run: RunState, limits: [String: Date]) async {
        guard !Task.isCancelled else { return }
        if await run.isLocked { return }
        await run.report(current: type.id)
        do {
            var state = try await database.syncState(type: type.id) ?? HealthSyncStateRow(typeID: type.id)
            applyLimit(limits[type.id], to: &state)

            if state.cursor == nil, state.backfillDone == 0 {
                try await bootstrap(type, state: &state)
            }

            state.status = state.backfillDone == 0 ? "importing" : "syncing"
            try await database.setSyncState(state)
            await run.report(current: type.id, importing: state.backfillDone == 0)

            var anchor = state.cursor.flatMap { Data(base64Encoded: $0) }
            while true {
                try Task.checkCancellation()
                let page = try await reader.anchoredPage(type: type, anchor: anchor, limit: configuration.pageLimit)
                let nowMs = HealthSampleMapper.ms(now())
                if page.isEmpty {
                    let firstCompletion = state.backfillDone == 0
                    if let newAnchor = page.anchor { state.cursor = newAnchor.base64EncodedString() }
                    state.cursorIssuedMs = nowMs
                    state.lastSyncMs = nowMs
                    state.backfillDone = 1
                    state.lastError = nil
                    state.status = limits[type.id] != nil ? "limited" : "idle"
                    try await database.setSyncState(state)
                    if firstCompletion {
                        try await database.rebuildAllRollups(type: type, tz: calendar.timeZone.identifier, calendar: calendar, ownBundleID: ownBundleID)
                    }
                    break
                }

                var dirtyDays = HealthRollupMath.dirtyDays(page.rows)
                for id in page.deletedIDs {
                    if let existing = try await database.sample(id: id) {
                        dirtyDays.insert(existing.localDay)
                    }
                }
                if let newAnchor = page.anchor { state.cursor = newAnchor.base64EncodedString() }
                anchor = page.anchor ?? anchor
                state.cursorIssuedMs = nowMs
                state.lastSyncMs = nowMs
                state.ipcCallsTotal += 1
                let commit = HealthCommitPage(rows: page.rows, deletedIDs: page.deletedIDs, sources: page.sources, syncState: state)
                try await database.commitPage(commit, nowMs: nowMs)
                await run.add(rows: page.rows.count)
                if state.backfillDone == 1, !dirtyDays.isEmpty {
                    try await database.rebuildRollups(
                        type: type, days: dirtyDays.sorted(), tz: calendar.timeZone.identifier, calendar: calendar, ownBundleID: ownBundleID
                    )
                }
            }
            await run.finishType()
        } catch is CancellationError {
            // Anchors are only advanced with their page, so resuming is safe.
        } catch let error as HKError where error.code == .errorDatabaseInaccessible {
            try? await database.updateStatus(type: type.id, status: "locked", error: nil, nowMs: HealthSampleMapper.ms(now()))
            await run.markLocked()
        } catch let error as HealthDBError where error.isCorruption {
            await run.fail(type.id, message: error.description)
        } catch {
            let nsError = error as NSError
            try? await database.updateStatus(
                type: type.id,
                status: "error:\(nsError.code)",
                error: nsError.localizedDescription,
                nowMs: HealthSampleMapper.ms(now())
            )
            await run.fail(type.id, message: nsError.localizedDescription)
        }
    }

    /// iOS 27 boundary handling. Nil boundary = unlimited history.
    private func applyLimit(_ boundary: Date?, to state: inout HealthSyncStateRow) {
        if let boundary {
            let boundaryMs = HealthSampleMapper.ms(boundary)
            if let previous = state.earliestAuthorizedMs, boundaryMs < previous {
                // The user widened the grant: re-read from the beginning.
                state.cursor = nil
                state.cursorIssuedMs = nil
                state.backfillDone = 0
            }
            state.earliestAuthorizedMs = boundaryMs
        } else if state.earliestAuthorizedMs != nil {
            state.earliestAuthorizedMs = nil
            state.cursor = nil
            state.cursorIssuedMs = nil
            state.backfillDone = 0
        }
    }

    /// Recent rows first so tiles/hub have numbers while the full backfill runs.
    private func bootstrap(_ type: HealthMetricType, state: inout HealthSyncStateRow) async throws {
        state.status = "bootstrapping"
        try await database.setSyncState(state)
        let since = calendar.date(byAdding: .day, value: -configuration.bootstrapDays, to: now()) ?? now()
        let rows = try await reader.recentRows(type: type, since: since, limit: configuration.bootstrapLimit)
        guard !rows.isEmpty else { return }
        let nowMs = HealthSampleMapper.ms(now())
        let commit = HealthCommitPage(rows: rows, deletedIDs: [], sources: [], syncState: nil)
        try await database.commitPage(commit, nowMs: nowMs)
        try await database.rebuildRollups(
            type: type,
            days: HealthRollupMath.dirtyDays(rows).sorted(),
            tz: calendar.timeZone.identifier,
            calendar: calendar,
            ownBundleID: ownBundleID
        )
    }

    // MARK: - Shared run state

    private actor RunState {
        let typesTotal: Int
        let progress: @Sendable (HealthSyncProgress) -> Void
        private var typesDone = 0
        private var typesSucceeded = 0
        private var rowsCommitted = 0
        private var locked = false
        private var failures: [String: String] = [:]
        private var current: String?
        private var importing = false

        init(typesTotal: Int, progress: @escaping @Sendable (HealthSyncProgress) -> Void) {
            self.typesTotal = typesTotal
            self.progress = progress
        }

        var isLocked: Bool { locked }

        func markLocked() {
            locked = true
            emit()
        }

        func add(rows: Int) {
            rowsCommitted += rows
            emit()
        }

        func finishType() {
            typesDone += 1
            typesSucceeded += 1
            emit()
        }

        func fail(_ typeID: String, message: String) {
            failures[typeID] = message
            typesDone += 1
            emit()
        }

        func report(current: String?, importing: Bool? = nil) {
            self.current = current
            if let importing { self.importing = importing }
            emit()
        }

        /// `done` counts types that completed without error.
        func summary() -> (done: Int, rows: Int, failures: [String: String]) {
            (typesSucceeded, rowsCommitted, failures)
        }

        private func emit() {
            progress(HealthSyncProgress(
                typesTotal: typesTotal,
                typesDone: typesDone,
                rowsCommitted: rowsCommitted,
                importing: importing,
                currentTypeID: current
            ))
        }
    }
}
