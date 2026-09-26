import Foundation
import SwiftUI
import Testing
@testable import calorietracker

/// `insights.*` catalog actions against isolated stores and a temporary Health mirror.
@MainActor
struct InsightsActionTests {
    struct Fixture {
        let suite: String
        let defaults: UserDefaults
        let executor: ActionExecutor
        let directory: URL

        func tearDown() {
            defaults.removePersistentDomain(forName: suite)
            try? FileManager.default.removeItem(at: directory)
        }
    }

    /// 2026-09-16 12:00 UTC, Health sync on, 20 nights in the mirror.
    static func fixture(healthSync: Bool = true, nights: Int = 20) async throws -> Fixture {
        let suite = "InsightsActionTests.\(UUID().uuidString)"
        let defaults = try #require(UserDefaults(suiteName: suite))
        defaults.set(healthSync, forKey: "healthKitEnabled")
        let now = Date(timeIntervalSince1970: 1_789_560_000)
        let executor = ActionExecutor(environment: .testing(defaults: defaults, now: now, calendar: InsightsTestData.utcCalendar()))
        let directory = try HealthTestFixtures.temporaryDirectory()
        let runtime = HealthDataRuntime(databaseURL: directory.appendingPathComponent("Health/health.sqlite"))
        #expect(await runtime.openIfNeeded())
        let writer = try #require(runtime.writer)
        try await InsightsTestData.seed(writer, today: "2026-09-16", nights: nights)
        executor.environment.healthRuntime = runtime
        return Fixture(suite: suite, defaults: defaults, executor: executor, directory: directory)
    }

    @Test func recoveryReturnsTheScoreAndSignals() async throws {
        let f = try await Self.fixture()
        defer { f.tearDown() }
        let result = try await f.executor.run("insights.recovery.get", [:], source: .siri)
        #expect(result.string("status") == "ok")
        let score = try #require(result.value)
        #expect((0...100).contains(Int(score)))
        #expect(result.string("label") != nil)
        #expect(result.fields["positives"] != nil && result.fields["negatives"] != nil)
        #expect(result.dialog.contains("\(Int(score))"))
        #expect(result.route == .target("screen:insights.recovery"))
    }

    @Test func recoveryWhileCollectingNeverInventsAScore() async throws {
        let f = try await Self.fixture(nights: 6)
        defer { f.tearDown() }
        let result = try await f.executor.run("insights.recovery.get", [:], source: .shortcuts)
        #expect(result.string("status") == "collecting")
        #expect(result.fields["score"] == .null)
        #expect(result.value == nil)
        #expect(result.dialog.contains("5/14"))
    }

    @Test func healthAgeAndReviewAnswerWithStatus() async throws {
        let f = try await Self.fixture()
        defer { f.tearDown() }
        let age = try await f.executor.run("insights.healthAge.get", [:], source: .coach)
        #expect(["ok", "collecting", "no_birthday", "unsupported_age"].contains(age.string("status") ?? ""))
        #expect(!age.dialog.isEmpty)
        #expect(age.route == .target("screen:insights.health_age"))

        let today = try await f.executor.run("insights.dailyReview.get", [:], source: .siri)
        #expect(today.string("day") == "2026-09-16")
        #expect(today.double("day_score") != nil, "sleep and recovery were synced, so the day has a score")
        let yesterday = try await f.executor.run("insights.dailyReview.get", ["day": .string("yesterday")], source: .shortcuts)
        #expect(yesterday.string("day") == "2026-09-15")
        #expect(yesterday.route == .target("screen:insights.review"))
        await #expect(throws: ActionError.self) {
            _ = try await f.executor.run("insights.dailyReview.get", ["day": .string("last_week")], source: .siri)
        }
    }

    @Test func insightsNeedHealthSyncAndTheSwitch() async throws {
        let f = try await Self.fixture(healthSync: false)
        defer { f.tearDown() }
        for id in ["insights.recovery.get", "insights.healthAge.get", "insights.dailyReview.get"] {
            do {
                _ = try await f.executor.run(id, [:], source: .siri)
                Issue.record("\(id) must need Health sync")
            } catch let error as ActionError {
                #expect(error.code == "permission_required")
            }
        }
        f.defaults.set(true, forKey: "healthKitEnabled")
        f.defaults.set(false, forKey: InsightsSettings.enabledKey)
        do {
            _ = try await f.executor.run("insights.recovery.get", [:], source: .siri)
            Issue.record("Insights off must refuse")
        } catch let error as ActionError {
            #expect(error.code == "unavailable")
        }
    }

    @Test func linksAndCoachToolsComeFromTheCatalog() async throws {
        let f = try await Self.fixture()
        defer { f.tearDown() }
        #expect(await ActionRouteCoordinator.resolve(link: "ayuvo://open/insights", executor: f.executor) == .route(.target("section:insights")))
        #expect(await ActionRouteCoordinator.resolve(link: "ayuvo://action/insights.recovery.get", executor: f.executor)
                == .route(.target("screen:insights.recovery")))
        #expect(await ActionRouteCoordinator.resolve(link: "ayuvo://action/insights.dailyReview.get?day=yesterday", executor: f.executor)
                == .route(.target("screen:insights.review")))
        for tool in ["get_recovery", "get_health_age", "get_daily_review"] {
            #expect(CoachTools.actionReadToolNames.contains(tool))
        }
        let schema = CoachTools.schema(for: "get_daily_review")
        let day = try #require((schema["properties"] as? [String: Any])?["day"] as? [String: Any])
        #expect(day["enum"] as? [String] == ["today", "yesterday"])
        // Insights read Health data: without Coach's Health access they are not offered.
        let tools = CoachTools(weights: [], bodyFats: [], foods: [])
        #expect(!tools.actionToolNames.contains("get_recovery"))
        #expect(tools.actionToolNames.contains("get_water_intake") || !tools.availableToolNames.contains("get_data_summary"))
        #expect(ActionSectionOption(rawValue: "insights") != nil)
        #expect(ActionReviewDayOption.allCases.map(\.rawValue) == ActionCatalog.shared.enums["review_day"])
        #expect(Set(ActionSectionOption.allCases.map(\.rawValue)) == Set(ActionCatalog.shared.enums["section"] ?? []))
    }

    @Test func openingInsightsPlacesLandsOnTheBrowseStack() {
        let navigator = AppNavigator()
        navigator.openInsights(.healthAge)
        #expect(navigator.selectedTab == .browse)
        #expect(navigator.browsePath.count == 2)
    }
}
