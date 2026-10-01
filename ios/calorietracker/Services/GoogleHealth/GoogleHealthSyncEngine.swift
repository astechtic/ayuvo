import Foundation

nonisolated struct GoogleHealthSyncProgress: Sendable, Equatable {
    var typesTotal = 0
    var typesDone = 0
    var rowsCommitted = 0
    var currentType: String?
    /// Writing to Apple Health after the fetch.
    var mirroring = false
    var mirrored = 0

    var fraction: Double {
        guard typesTotal > 0 else { return 0 }
        return min(1, Double(typesDone) / Double(typesTotal))
    }
}

nonisolated enum GoogleHealthSyncOutcome: Sendable, Equatable {
    case synced(types: Int, rows: Int, failures: Int)
    case skipped(reason: String)
    /// Consent lost / token refused: Settings shows Reconnect.
    case reauthRequired
    case cancelled
    case failed(String)
}

/// Google Health API → `health_samples` (origin 3). Entered from a detached task like
/// `HealthSyncEngine`; commits every page through the single writer actor.
///
/// Per type (≤ `max_concurrent_types` at once): skip with `error:scope` when its scope group was
/// not granted; fetch from `cursor_ms − overlap_days` (first run: `now − initial_backfill_days`,
/// kept in `backfill_floor_ms` so a resumed first fetch reuses the same filter); commit each
/// page's rows, sources, mirror rows and `page_token` in one transaction; the cursor moves only
/// with the last page. Rollups are rebuilt for the days each page touched. A 400/404 on an
/// `optional` type marks it `unsupported`; a refused token stops the whole run (`reauthRequired`).
nonisolated final class GoogleHealthSyncEngine: Sendable {
    nonisolated struct Configuration: Sendable {
        var initialBackfillDays = 90
        var overlapDays = 2
        var maxConcurrentTypes = 3
        /// Safety stop for one type in one run; the saved page token resumes it next time.
        var maxPagesPerType = 400
        var writeBack = true
    }

    let fetcher: any GoogleHealthFetching
    let database: HealthDatabase
    let map: GoogleHealthMap
    let types: [GoogleHealthMap.DataType]
    let grantedScopes: Set<String>
    let calendar: Calendar
    let now: @Sendable () -> Date
    let configuration: Configuration
    let ownBundleID: String
    let runningPlatform: String

    init(
        fetcher: any GoogleHealthFetching,
        database: HealthDatabase,
        map: GoogleHealthMap,
        types: [GoogleHealthMap.DataType]? = nil,
        grantedScopes: Set<String>,
        calendar: Calendar = .current,
        now: @escaping @Sendable () -> Date = { Date() },
        configuration: Configuration? = nil,
        ownBundleID: String = Bundle.main.bundleIdentifier ?? "com.ayuvo.health",
        runningPlatform: String = GoogleHealthMapper.runningPlatform
    ) {
        self.fetcher = fetcher
        self.database = database
        self.map = map
        self.types = types ?? map.types
        self.grantedScopes = grantedScopes
        self.calendar = calendar
        self.now = now
        self.configuration = configuration ?? Configuration(
            initialBackfillDays: map.api.initialBackfillDays,
            overlapDays: map.api.overlapDays,
            maxConcurrentTypes: map.api.maxConcurrentTypes
        )
        self.ownBundleID = ownBundleID
        self.runningPlatform = runningPlatform
    }

    // MARK: - Run

    func sync(progress: @escaping @Sendable (GoogleHealthSyncProgress) -> Void = { _ in }) async -> GoogleHealthSyncOutcome {
        guard !types.isEmpty else { return .skipped(reason: "no types") }
        let run = RunState(typesTotal: types.count, progress: progress)
        await withTaskGroup(of: Void.self) { group in
            var next = 0
            while next < types.count, next < max(1, configuration.maxConcurrentTypes) {
                let type = types[next]
                next += 1
                group.addTask { await self.syncType(type, run: run) }
            }
            while await group.next() != nil {
                let stop = await run.reauthRequired
                if Task.isCancelled || stop { break }
                guard next < types.count else { continue }
                let type = types[next]
                next += 1
                group.addTask { await self.syncType(type, run: run) }
            }
        }
        if await run.reauthRequired { return .reauthRequired }
        if Task.isCancelled { return .cancelled }
        let summary = await run.summary()
        if summary.done == 0, let failure = summary.failures.values.first {
            return .failed(failure)
        }
        return .synced(types: summary.done, rows: summary.rows, failures: summary.failures.count)
    }

    // MARK: - One type

    private func syncType(_ type: GoogleHealthMap.DataType, run: RunState) async {
        guard !Task.isCancelled else { return }
        if await run.reauthRequired { return }
        await run.report(current: type.ghType)
        let nowMs = HealthSampleMapper.ms(now())
        var state: GoogleHealthSyncStateRow
        do {
            state = try await database.googleSyncState(ghType: type.ghType) ?? GoogleHealthSyncStateRow(ghType: type.ghType)
        } catch {
            await run.fail(type.ghType, message: error.localizedDescription)
            return
        }

        guard map.isGranted(type, scopes: grantedScopes) else {
            state.status = "error:scope"
            state.pageToken = nil
            try? await database.setGoogleSyncState(state)
            await run.skip()
            return
        }

        if state.backfillFloorMs == nil {
            state.backfillFloorMs = nowMs - Int64(configuration.initialBackfillDays) * 86_400_000
        }
        let fromMs = state.cursorMs.map { $0 - Int64(configuration.overlapDays) * 86_400_000 } ?? state.backfillFloorMs ?? nowMs
        let filter = map.filter(for: type, fromMs: fromMs, timeZone: calendar.timeZone)
        var maxEndMs = state.cursorMs ?? 0
        var pages = 0

        do {
            state.status = "syncing"
            try await database.setGoogleSyncState(state)
            while true {
                try Task.checkCancellation()
                let page = try await fetcher.listDataPoints(ghType: type.ghType, filter: filter, pageSize: type.pageSize, pageToken: state.pageToken)
                pages += 1
                let pageNowMs = HealthSampleMapper.ms(now())
                var commit = GoogleHealthCommitPage()
                var sources: [String: HealthSourceRow] = [:]
                var echoIDs = Set<String>()
                for point in page.points {
                    let rows = GoogleHealthMapper.rows(point: point, type: type, calendar: calendar, nowMs: pageNowMs)
                    guard let first = rows.first else { continue }
                    commit.rows += rows
                    maxEndMs = max(maxEndMs, rows.map(\.endMs).max() ?? first.endMs)
                    let source = GoogleHealthMapper.source(point, endMs: first.endMs)
                    var merged = sources[source.id] ?? source
                    merged.lastSeenMs = max(merged.lastSeenMs ?? 0, source.lastSeenMs ?? 0)
                    sources[source.id] = merged
                    if GoogleHealthMapper.isEcho(dataSource: point["dataSource"], guard: map.echoGuard, runningPlatform: runningPlatform) {
                        echoIDs.formUnion(rows.map(\.id))
                    }
                }
                commit.sources = sources.values.sorted { $0.id < $1.id }
                commit.mirrorStatus = try await mirrorStatuses(commit.rows, type: type, echoIDs: echoIDs)

                let isLast = page.nextPageToken == nil || pages >= configuration.maxPagesPerType
                state.pageToken = page.nextPageToken
                state.lastSyncMs = pageNowMs
                if isLast {
                    state.status = "idle"
                }
                if page.nextPageToken == nil {
                    // The cursor never runs ahead of the clock (future-dated points).
                    state.cursorMs = min(max(maxEndMs, state.cursorMs ?? 0), pageNowMs).nonZero
                    state.lastError = nil
                }
                commit.syncState = state
                try await database.commitGooglePage(commit)
                await run.add(rows: commit.rows.count)
                try await rebuildRollups(for: commit.rows)
                if isLast { break }
            }
            await run.finishType()
        } catch is CancellationError {
            // The page token is saved with each committed page, so the next run resumes.
            try? await markStatus(type.ghType, status: "idle", error: nil)
        } catch let error as GoogleHealthAPIError {
            if error.isUnsupported(optionalType: type.isOptional) {
                try? await markStatus(type.ghType, status: "unsupported", error: nil, clearPageToken: true)
                await run.skip()
            } else if error == .unauthorized {
                try? await markStatus(type.ghType, status: "error:auth", error: error.localizedDescription)
                await run.markReauthRequired()
            } else if case .forbidden = error {
                try? await markStatus(type.ghType, status: "error:scope", error: error.localizedDescription, clearPageToken: true)
                await run.skip()
            } else {
                let code = error.statusCode.map(String.init) ?? "network"
                try? await markStatus(type.ghType, status: "error:\(code)", error: error.localizedDescription)
                await run.fail(type.ghType, message: error.localizedDescription)
            }
        } catch let error as GoogleHealthAuthError {
            try? await markStatus(type.ghType, status: "error:auth", error: error.localizedDescription)
            if error == .consentLost || error == .notConnected {
                await run.markReauthRequired()
            } else {
                await run.fail(type.ghType, message: error.localizedDescription)
            }
        } catch {
            let nsError = error as NSError
            try? await markStatus(type.ghType, status: "error:\(nsError.code)", error: nsError.localizedDescription)
            await run.fail(type.ghType, message: nsError.localizedDescription)
        }
    }

    /// Initial `google_health_mirror` status per row (docs/google-health.md §4).
    private func mirrorStatuses(_ rows: [HealthSampleRow], type: GoogleHealthMap.DataType, echoIDs: Set<String>) async throws -> [String: GoogleHealthMirrorStatus] {
        guard !rows.isEmpty else { return [:] }
        var statuses: [String: GoogleHealthMirrorStatus] = [:]
        guard type.hk != nil else {
            for row in rows { statuses[row.id] = .unsupported }
            return statuses
        }
        let candidates = rows.filter { !echoIDs.contains($0.id) }
        let duplicates = try await database.platformDuplicateIDs(
            candidates,
            windowMs: map.echoGuard.duplicateWindowMs,
            epsilonRatio: map.echoGuard.duplicateValueEpsilonRatio
        )
        for row in rows {
            if echoIDs.contains(row.id) || duplicates.contains(row.id) {
                statuses[row.id] = .skippedDup
            } else if !GoogleHealthMirrorWriter.canWrite(row, target: type.hk) {
                statuses[row.id] = .unsupported
            } else {
                statuses[row.id] = configuration.writeBack ? .pending : .disabled
            }
        }
        return statuses
    }

    private func rebuildRollups(for rows: [HealthSampleRow]) async throws {
        var daysByType: [String: Set<String>] = [:]
        for row in rows { daysByType[row.typeID, default: []].insert(row.localDay) }
        for (typeID, days) in daysByType {
            guard let registry = HealthMetricRegistry.type(id: typeID) else { continue }
            try await database.rebuildRollups(type: registry, days: days.sorted(), tz: calendar.timeZone.identifier, calendar: calendar, ownBundleID: ownBundleID)
        }
    }

    private func markStatus(_ ghType: String, status: String, error: String?, clearPageToken: Bool = false) async throws {
        var state = try await database.googleSyncState(ghType: ghType) ?? GoogleHealthSyncStateRow(ghType: ghType)
        state.status = status
        if clearPageToken { state.pageToken = nil }
        if let error {
            state.lastError = error
            state.lastErrorMs = HealthSampleMapper.ms(now())
        }
        try await database.setGoogleSyncState(state)
    }

    // MARK: - Shared run state

    private actor RunState {
        let typesTotal: Int
        let progress: @Sendable (GoogleHealthSyncProgress) -> Void
        private var typesDone = 0
        private var typesSucceeded = 0
        private var rowsCommitted = 0
        private var failures: [String: String] = [:]
        private var current: String?
        private(set) var reauthRequired = false

        init(typesTotal: Int, progress: @escaping @Sendable (GoogleHealthSyncProgress) -> Void) {
            self.typesTotal = typesTotal
            self.progress = progress
        }

        func report(current: String?) {
            self.current = current
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

        /// Not granted / unsupported: done, neither a success nor a failure.
        func skip() {
            typesDone += 1
            emit()
        }

        func fail(_ ghType: String, message: String) {
            failures[ghType] = message
            typesDone += 1
            emit()
        }

        func markReauthRequired() {
            reauthRequired = true
            emit()
        }

        func summary() -> (done: Int, rows: Int, failures: [String: String]) {
            (typesSucceeded, rowsCommitted, failures)
        }

        private func emit() {
            progress(GoogleHealthSyncProgress(typesTotal: typesTotal, typesDone: typesDone, rowsCommitted: rowsCommitted, currentType: current))
        }
    }
}

private nonisolated extension Int64 {
    var nonZero: Int64? { self == 0 ? nil : self }
}
