import Foundation
import Testing
@testable import calorietracker

/// The widget option enums must match `shared/widgets/widget_options.json` (docs/widgets.md).
struct WidgetOptionsTests {
    private struct Options: Decodable {
        struct Action: Decodable {
            let id: String
            let group: String
            let food_method: String?
        }
        struct Metric: Decodable {
            let key: String
            let tap: String
        }
        struct QuickLog: Decodable {
            let slots: Int
            let actions: [Action]
            let defaults: [String]
        }
        struct MyMetrics: Decodable {
            let slots: Int
            let metrics: [Metric]
            let defaults: [String]
        }
        struct DeepLinks: Decodable {
            let ios: [String: String]
        }
        let format: String
        let version: Int
        let quick_log: QuickLog
        let my_metrics: MyMetrics
        let deep_links: DeepLinks
    }

    private func load() throws -> Options {
        let url = URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent()
            .deletingLastPathComponent()
            .deletingLastPathComponent()
            .appendingPathComponent("shared/widgets/widget_options.json")
        return try JSONDecoder().decode(Options.self, from: Data(contentsOf: url))
    }

    @Test func envelope() throws {
        let options = try load()
        #expect(options.format == "ayuvo-widget-options")
        #expect(options.version == 1)
        #expect(options.quick_log.slots == 4)
        #expect(options.my_metrics.slots == 4)
    }

    @Test func quickLogActionsMatchTheContract() throws {
        let options = try load()
        #expect(QuickLogAction.allCases.map(\.rawValue) == options.quick_log.actions.map(\.id))
        #expect(QuickLogAction.defaults.map(\.rawValue) == options.quick_log.defaults)
        for action in options.quick_log.actions {
            let local = try #require(QuickLogAction(rawValue: action.id))
            #expect(local.group == action.group, "\(action.id) group")
            #expect(local.foodMethodRaw == action.food_method, "\(action.id) food method")
            if let method = action.food_method {
                #expect(FoodLogMethod(rawValue: method) != nil, "\(method) is not a FoodLogMethod")
            }
        }
    }

    @Test func myMetricsMatchTheContract() throws {
        let options = try load()
        #expect(WidgetMetricOption.allCases.map(\.rawValue) == options.my_metrics.metrics.map(\.key))
        #expect(WidgetMetricOption.defaults.map(\.rawValue) == options.my_metrics.defaults)
        let healthIDs = Set(HealthMetricRegistry.iOSTypes.map(\.id))
        for metric in options.my_metrics.metrics where metric.key != WidgetMetricOption.nextDose.rawValue {
            let key = try #require(MetricKey(pinID: metric.key), "\(metric.key) is not a metric key")
            if case .health(let id) = key {
                #expect(healthIDs.contains(id), "\(id) missing from the iOS health registry")
            }
        }
        // Tap targets: `fasting` opens Fasting, `medications` opens Medications, else metric detail.
        for metric in options.my_metrics.metrics {
            let action = WidgetRouteAction.resolve(.metric(metric.key))
            switch metric.tap {
            case "fasting": #expect(action == .fasting)
            case "medications": #expect(action == .medications)
            default: #expect(action == .metric(MetricKey(pinID: metric.key)!))
            }
        }
    }

    @Test func unknownSlotsFallBackToThatSlotsDefault() {
        #expect(QuickLogAction.resolve("nope", slot: 2) == .weight)
        #expect(QuickLogAction.resolve(nil, slot: 0) == .foodCamera)
        #expect(QuickLogAction.resolve("record", slot: 0) == .record)
        #expect(WidgetMetricOption.resolve(nil, slot: 1) == .steps)
        #expect(WidgetMetricOption.resolve("sleep", slot: 1) == .sleep)
    }

    @Test func deepLinkTemplatesMatch() throws {
        let links = try load().deep_links.ios
        #expect(QuickLogAction.water.url.absoluteString == links["log"]?.replacingOccurrences(of: "{action_id}", with: "water"))
        #expect(WidgetMetricOption.steps.url.absoluteString == links["metric"]?.replacingOccurrences(of: "{metric_key}", with: "steps"))
        #expect(WidgetDeepLink.summaryURL.absoluteString == links["summary"])
    }
}
