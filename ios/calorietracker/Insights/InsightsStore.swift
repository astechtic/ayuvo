import Foundation
import SwiftUI

/// Observable Insights state for the Summary cards and the Insights screens. Scores are recomputed from the
/// stores on demand and cached in memory only, keyed on the stores' revision counters (the
/// `MetricSeriesCache` pattern); nothing is persisted (docs/insights.md §3).
@Observable
@MainActor
final class InsightsStore {
    static let shared = InsightsStore()

    private(set) var report: InsightsReport?
    private(set) var profile: InsightsProfile?
    private(set) var isLoading = false
    /// Health sync is on and Insights is enabled (the Summary section hides otherwise).
    private(set) var isAvailable = false

    @ObservationIgnored private var source: InsightsDataSource?
    @ObservationIgnored private var cachedKey: String?
    @ObservationIgnored private var running: Task<Void, Never>?
    @ObservationIgnored private var healthRevision: () -> Int = { 0 }
    @ObservationIgnored let config: InsightsConfig

    init(config: InsightsConfig = .shared) {
        self.config = config
    }

    /// Wires the app's live stores (ContentView, once). Safe to call again. Screens may have asked for a
    /// refresh before the source existed, so the first attach computes right away.
    func attach(_ source: InsightsDataSource, healthRevision: @escaping () -> Int) {
        let first = self.source == nil
        self.source = source
        self.healthRevision = healthRevision
        if first { Task { await refresh() } }
    }

    var recovery: RecoveryResult? { report?.recovery }
    var healthAge: HealthAgeResult? { report?.healthAge }
    var pace: HealthAgePaceResult? { report?.pace }
    var patterns: [PatternResult] { report?.patterns ?? [] }
    var baselines: [InsightsMetricBaseline] { report?.baselines ?? [] }

    func review(for day: String) -> DailyReviewResult? { report?.reviews[day] }

    /// Review days, newest first (today … 6 days ago).
    var reviewDays: [String] {
        guard let today = report?.today else { return [] }
        return (0..<HealthAnalyticsEngine.reviewDays).map { InsightsDay.add(today, -$0) }
    }

    /// Recomputes when an input changed (or `force`). Runs the engines off the main actor.
    func refresh(force: Bool = false) async {
        guard let source else { return }
        isAvailable = source.healthSyncEnabled && source.insightsEnabled
        guard isAvailable else {
            report = nil
            cachedKey = nil
            return
        }
        let key = source.revisionKey(healthRevision: healthRevision())
        if !force, key == cachedKey, report != nil { return }
        if let running {
            await running.value
            if !force, key == cachedKey, report != nil { return }
        }
        let task = Task { @MainActor in
            isLoading = true
            defer { isLoading = false }
            let today = source.today()
            let inputs = await source.inputs(today: today)
            let profile = source.profileFacts()
            let config = config
            let report = await Task.detached(priority: .userInitiated) {
                HealthAnalyticsEngine.report(inputs: inputs, today: today, profile: profile, config: config)
            }.value
            self.report = report
            self.profile = profile
            self.cachedKey = key
        }
        running = task
        await task.value
        running = nil
    }

    /// Drops the cache (Insights switched off, data deleted).
    func invalidate() {
        report = nil
        cachedKey = nil
    }

    /// Nights still needed before Recovery can score ("Learning your baseline (n/14 nights)"), nil when ready.
    var collecting: InsightsCollecting? {
        guard let recovery = report?.recovery, recovery.status == "collecting" else { return nil }
        return recovery.collecting
    }
}

// MARK: - One-shot computation (background refresh, actions)

extension InsightsDataSource {
    /// Full report for `now` from this source, computed off the main actor.
    func report(now: Date = Date(), config: InsightsConfig = .shared) async -> InsightsReport {
        let today = today(now)
        let inputs = await inputs(today: today)
        let profile = profileFacts()
        return await Task.detached(priority: .utility) {
            HealthAnalyticsEngine.report(inputs: inputs, today: today, profile: profile, config: config)
        }.value
    }
}
