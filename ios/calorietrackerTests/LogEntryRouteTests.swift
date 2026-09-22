import Foundation
import SwiftUI
import Testing
@testable import calorietracker

/// Widget deep links land where the Summary "+" menu goes (docs/widgets.md "Quick Log actions").
@MainActor
struct LogEntryRouteTests {
    private func defaults(water: Bool, fasting: Bool) -> UserDefaults {
        let suite = "LogEntryRouteTests.\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suite)!
        defaults.set(water, forKey: WaterSettings.enabledKey)
        defaults.set(fasting, forKey: FastingSettings.enabledKey)
        return defaults
    }

    @Test func everyActionURLParsesBack() {
        for action in QuickLogAction.allCases {
            #expect(WidgetDeepLink(url: action.url) == .log(action), "\(action.rawValue)")
        }
        for option in WidgetMetricOption.allCases {
            #expect(WidgetDeepLink(url: option.url) == .metric(option.rawValue), "\(option.rawValue)")
        }
        #expect(WidgetDeepLink(url: WidgetDeepLink.summaryURL) == .summary)
    }

    @Test func foreignAndMalformedLinksAreIgnored() {
        #expect(WidgetDeepLink(url: URL(string: "ayuvo://log/unknown")!) == nil)
        #expect(WidgetDeepLink(url: URL(string: "ayuvo://metric")!) == nil)
        #expect(WidgetDeepLink(url: URL(string: "ayuvo://log-food?method=camera")!) == nil)
        #expect(WidgetDeepLink(url: URL(string: "ayuvo://medications")!) == nil)
        #expect(WidgetDeepLink(url: URL(string: "https://log/water")!) == nil)
    }

    @Test func storageValueRoundTrips() {
        let links: [WidgetDeepLink] = [.summary, .log(.bodyFat), .log(.foodCopyFromDay), .metric("app:calories"), .metric("heart_rate")]
        for link in links {
            #expect(WidgetDeepLink(storageValue: link.storageValue) == link)
        }
        #expect(WidgetDeepLink(storageValue: "log:nope") == nil)
        #expect(WidgetDeepLink(storageValue: "metric:") == nil)
    }

    @Test func foodActionsOpenNutritionLikeTheMenu() {
        #expect(WidgetRouteAction.resolve(.log(.foodMenu)) == .foodMenu)
        #expect(WidgetRouteAction.resolve(.log(.foodCamera)) == .quickAction(.camera))
        #expect(WidgetRouteAction.resolve(.log(.foodBarcode)) == .quickAction(.barcode))
        #expect(WidgetRouteAction.resolve(.log(.foodFavorites)) == .quickAction(.favorites))
        #expect(WidgetRouteAction.resolve(.log(.foodCopyFromDay)) == .logMethod(.copyFromDay))
    }

    @Test func trackedActionsRespectTheirSwitches() {
        let on = defaults(water: true, fasting: true)
        #expect(WidgetRouteAction.resolve(.log(.water), defaults: on) == .summaryLog(.water))
        #expect(WidgetRouteAction.resolve(.log(.fasting), defaults: on) == .quickAction(.fasting))
        let off = defaults(water: false, fasting: false)
        #expect(WidgetRouteAction.resolve(.log(.water), defaults: off) == .settings(.hydration))
        #expect(WidgetRouteAction.resolve(.log(.fasting), defaults: off) == .settings(.fasting))
    }

    @Test func otherActionsMirrorTheSummaryMenu() {
        #expect(WidgetRouteAction.resolve(.log(.weight)) == .summaryLog(.weight))
        #expect(WidgetRouteAction.resolve(.log(.bodyFat)) == .summaryLog(.bodyFat))
        #expect(WidgetRouteAction.resolve(.log(.workout)) == .workouts)
        #expect(WidgetRouteAction.resolve(.log(.medication)) == .medications)
        #expect(WidgetRouteAction.resolve(.log(.record)) == .addRecord)
        #expect(WidgetRouteAction.resolve(.summary) == .summary)
        #expect(WidgetRouteAction.resolve(.metric("app:water")) == .metric(.app(.water)))
        #expect(WidgetRouteAction.resolve(.metric("steps")) == .metric(.health("steps")))
        #expect(WidgetRouteAction.resolve(.metric("app:nope")) == .summary)
    }

    @Test func pendingRouteIsConsumedOnce() {
        let suite = "LogEntryRouteTests.pending.\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suite)!
        defer { defaults.removePersistentDomain(forName: suite) }
        WidgetRouteCoordinator.request(.log(.weight), defaults: defaults)
        #expect(WidgetRouteCoordinator.consumePending(defaults: defaults) == .log(.weight))
        #expect(defaults.string(forKey: "widgetRoute.pending") == nil)
    }

    @Test func navigatorLandsSheetsOnSummaryAndMetricsOnItsStack() {
        let navigator = AppNavigator()
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent("LogEntryRouteTests-\(UUID().uuidString)", isDirectory: true)
        let suite = "LogEntryRouteTests.stores.\(UUID().uuidString)"
        let storeDefaults = UserDefaults(suiteName: suite)!
        defer {
            storeDefaults.removePersistentDomain(forName: suite)
            try? FileManager.default.removeItem(at: directory)
        }
        let medicationStore = MedicationStore(defaults: storeDefaults, runtime: MedicationsRuntime(
            databaseURL: directory.appendingPathComponent("medications.sqlite"),
            photosDirectory: directory.appendingPathComponent("photos", isDirectory: true)
        ))
        let recordsStore = RecordsStore(defaults: storeDefaults, databaseURL: directory.appendingPathComponent("records.sqlite"))

        navigator.selectedTab = .settings
        navigator.apply(.summaryLog(.weight), medicationStore: medicationStore, recordsStore: recordsStore)
        #expect(navigator.selectedTab == .summary)
        #expect(navigator.summaryLogRequest?.kind == .weight)

        navigator.apply(.metric(.health("steps")), medicationStore: medicationStore, recordsStore: recordsStore)
        #expect(navigator.selectedTab == .summary)
        #expect(navigator.summaryPath.count == 1)

        navigator.apply(.summary, medicationStore: medicationStore, recordsStore: recordsStore)
        #expect(navigator.summaryPath.isEmpty)

        navigator.apply(.settings(.hydration), medicationStore: medicationStore, recordsStore: recordsStore)
        #expect(navigator.selectedTab == .settings)
        #expect(navigator.settingsPath.count == 1)

        navigator.apply(.fasting, medicationStore: medicationStore, recordsStore: recordsStore)
        #expect(navigator.selectedTab == .browse)
        #expect(navigator.browsePath.count == 1)

        navigator.apply(.quickAction(.camera), medicationStore: medicationStore, recordsStore: recordsStore)
        #expect(navigator.quickActionRequest?.action == .camera)
    }
}
