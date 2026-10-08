import Foundation

/// `ledger_refresh` + `ledger_prune` before every sync or export (docs/partner-sync.md §10). Off the main thread,
/// in pages: each source streams pages of at most `pageSize` records into a TEMP staging table, and the rev
/// assignment runs in SQL pages too, so neither the user's health rows nor the ledger are ever held in memory whole.
///
/// Scopes: the first run (until `partner_meta.ledger_full_done = 1`) renders every type in full (intraday types
/// always use today − 7). Afterwards `full` types (food, weights, workouts, medicines, report overviews) stay full,
/// `window` types use today − 30 and `intraday` types today − 7. A source that cannot be read is skipped as a whole
/// (no scope, so no tombstones) and the full-run marker is not set while a window type was skipped.
actor PartnerLedgerRefresher {
    nonisolated struct Result: Sendable, Equatable {
        var changed = 0
        var tombstoned = 0
        var rev: Int64 = 0
        var pruned = 0
        var fullRun = false
        var skipped: [String] = []
        /// Largest page any source handed over (memory bound check).
        var maxPage = 0
        /// Wall time of the whole refresh and per type (scan + hash + staging), for the profiling log.
        var elapsedMs = 0
        var typeMs: [String: Int] = [:]
        var rows = 0
    }

    static let fullDoneKey = "ledger_full_done"

    let store: PartnerDatabase
    let registry: PartnerSourceRegistry
    let pageSize: Int
    let now: @Sendable () -> Date
    let timeZone: TimeZone
    private var running: Task<Result, Error>?

    init(store: PartnerDatabase, registry: PartnerSourceRegistry, pageSize: Int = 500,
         now: @escaping @Sendable () -> Date = { Date() }, timeZone: TimeZone = .current) {
        self.store = store
        self.registry = registry
        self.pageSize = pageSize
        self.now = now
        self.timeZone = timeZone
    }

    /// Single-flight: concurrent callers share one refresh, also across refresher instances on the same database
    /// (the TEMP staging tables are per connection, so two interleaved refreshes would mix their staged rows; a sync
    /// window now refreshes in the background while a pairing session or an export may ask for one).
    func refresh() async throws -> Result {
        if let running { return try await running.value }
        let task = Task { try await PartnerRefreshGate.shared.run(store: self.store) { try await self.run() } }
        running = task
        defer { running = nil }
        return try await task.value
    }

    private func run() async throws -> Result {
        let catalog = PartnerCatalog.shared
        let today = PartnerDay.string(now(), timeZone: timeZone)
        let windowFrom = PartnerDay.adding(-(catalog.recordTypes["refresh_window_days"].pyInt ?? 30), to: today, timeZone: timeZone)
        let intradayFrom = PartnerDay.adding(-catalog.intradayDays, to: today, timeZone: timeZone)
        let fullRun = try await store.meta(Self.fullDoneKey) != "1"
        var result = Result(fullRun: fullRun)
        var windowSkipped = false

        let started = DispatchTime.now().uptimeNanoseconds
        func ms(since t: UInt64) -> Int { Int((DispatchTime.now().uptimeNanoseconds - t) / 1_000_000) }
        try await store.refreshBegin()
        do {
            for type in registry.types {
                guard let source = registry.source(for: type), let spec = catalog.types[type] else { continue }
                let typeStarted = DispatchTime.now().uptimeNanoseconds
                let scope: PartnerSourceScope
                switch spec.scope {
                case "intraday": scope = .dayFrom(intradayFrom)
                case "window": scope = fullRun ? .full : .dayFrom(windowFrom)
                default: scope = .full
                }
                do {
                    var maxPage = 0
                    try await source.scan(scope: scope, pageSize: pageSize) { page in
                        maxPage = max(maxPage, page.count)
                        let rows = page.filter { $0.type == type }
                        result.rows += rows.count
                        try await self.store.refreshStage(rows)
                    }
                    result.maxPage = max(result.maxPage, maxPage)
                    try await store.refreshAddScope(type: type, dayFrom: scope.dayFrom)
                    result.typeMs[type] = ms(since: typeStarted)
                } catch is PartnerSourceUnavailable {
                    result.skipped.append(type)
                    if spec.scope == "window" { windowSkipped = true }
                }
            }
            let committed = try await store.refreshCommit()
            result.changed = committed.changed
            result.tombstoned = committed.tombstoned
            result.rev = committed.rev
        } catch {
            try? await store.refreshDiscard()
            throw error
        }
        result.pruned = try await store.ledgerPrune(intradayDayFrom: intradayFrom)
        if fullRun, !windowSkipped { try await store.setMeta(Self.fullDoneKey, "1") }
        result.elapsedMs = ms(since: started)
        #if DEBUG
        NSLog("AyuvoPartnerDebug ledger refresh %@: %d rows, %d changed, %d tombstoned in %d ms %@", fullRun ? "full" : "window",
              result.rows, result.changed, result.tombstoned, result.elapsedMs, result.typeMs.sorted { $0.key < $1.key }.description)
        #endif
        return result
    }
}

/// One ledger refresh at a time per partner database: a second caller joins the running one.
actor PartnerRefreshGate {
    static let shared = PartnerRefreshGate()
    private var running: [ObjectIdentifier: Task<PartnerLedgerRefresher.Result, Error>] = [:]

    func run(store: PartnerDatabase, _ body: @escaping @Sendable () async throws -> PartnerLedgerRefresher.Result) async throws -> PartnerLedgerRefresher.Result {
        let key = ObjectIdentifier(store)
        if let task = running[key] { return try await task.value }
        let task = Task { try await body() }
        running[key] = task
        defer { running[key] = nil }
        return try await task.value
    }
}
