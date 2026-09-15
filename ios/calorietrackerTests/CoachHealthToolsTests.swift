import Foundation
import Testing
@testable import calorietracker

@MainActor
struct CoachHealthToolsTests {
    private static let stepsType = HealthCoachDataType(
        dataType: "steps", category: "activity", displayName: "Steps", unit: "count", aggregation: "SUM", count: 12,
        first: "2026-09-01T00:00:00+02:00", last: "2026-09-13T21:00:00+02:00",
        latestAt: "2026-09-13T21:00:00+02:00", latestValue: 812, latestValueText: nil, historyLimitedBefore: nil
    )

    private static let stepsSummary = HealthCoachSummary(
        dataType: "steps", unit: "count", from: "2026-09-07", to: "2026-09-13", total: 42000, average: 6000, min: 2000, max: 9000, latest: 9000,
        days: (7...13).map { HealthCoachDay(date: "2026-09-\(String(format: "%02d", $0))", sum: 6000, avg: 500, min: 10, max: 900, count: 12, durationS: nil, v2Avg: nil, v2Min: nil, v2Max: nil, ownSum: 120) }
    )

    private static let night = HealthCoachNight(
        nightOf: "2026-09-13", start: "2026-09-12T23:10:00+02:00", end: "2026-09-13T06:40:00+02:00",
        inBedS: 27000, asleepS: 25200, lightS: 12000, deepS: 6000, remS: 7200, awakeS: 1800, source: "Apple Watch"
    )

    private func query() -> CoachHealthQuery {
        .fixed(
            dataTypes: [Self.stepsType],
            summaries: ["steps": Self.stepsSummary],
            samples: ["steps": HealthCoachSampleSet(dataType: "steps", unit: "count", records: (0..<300).map { index in
                HealthCoachSample(start: "2026-09-13T0\(index % 10):00:00+02:00", end: "2026-09-13T0\(index % 10):10:00+02:00", value: Double(index), value2: nil, value3: nil, valueText: nil, categoryValue: nil, title: nil, extraJSON: "{\"k\":1}", source: "iPhone", device: "iPhone")
            })],
            nights: Array(repeating: Self.night, count: 130)
        )
    }

    private func context(enabled: Bool = true) -> CoachHealthContext {
        CoachHealthContext(query: query(), context: HealthCoachContext(enabled: enabled, typeCount: 1, lastSync: Date(timeIntervalSince1970: 1_800_000_000), sevenDayLines: ["- Steps: avg 6000/day over 7 days, total 42000"]))
    }

    private func tools(health: CoachHealthContext?, enabled: Bool) -> CoachTools {
        CoachTools(weights: [], bodyFats: [], foods: [], health: health, healthAccessEnabled: enabled)
    }

    private func json(_ text: String) throws -> [String: Any] {
        try #require(try JSONSerialization.jsonObject(with: Data(text.utf8)) as? [String: Any])
    }

    @Test func healthToolsAreAdvertisedOnlyWithContextAndConsent() {
        #expect(!tools(health: nil, enabled: true).availableToolNames.contains("get_health_summary"))
        #expect(!tools(health: context(), enabled: false).availableToolNames.contains("get_sleep_history"))
        let names = tools(health: context(), enabled: true).availableToolNames
        #expect(names.suffix(4) == CoachTools.healthToolNames[...])
        #expect(CoachTools.healthToolNames == ["get_health_data_types", "get_health_summary", "get_health_samples", "get_sleep_history"])
    }

    @Test func descriptionsMatchTheSharedContract() {
        #expect(CoachTools.toolDescriptions["get_health_data_types"] == "List the health data types synced from the phone's health platform (steps, heart rate, sleep, blood pressure, ...) with record counts, units, and earliest/latest dates. Call this first before any other health tool, and to learn the exact data_type keys.")
        #expect(CoachTools.toolDescriptions["get_health_summary"] == "Daily statistics for one health data type between two dates (inclusive): per-day sum, average, min, max and record count in the type's unit, plus range highlights. Use for questions like \"how did I sleep this week?\", \"average resting heart rate in March\", or step trends. data_type must be a key returned by get_health_data_types.")
        #expect(CoachTools.toolDescriptions["get_health_samples"] == "Individual health records (start/end time, value, source app and device) for one data type between two dates (inclusive). Use only when per-reading detail matters, e.g. a specific blood-pressure reading or a workout. Prefer get_health_summary for trends.")
        #expect(CoachTools.toolDescriptions["get_sleep_history"] == "Per-night sleep sessions between two dates (inclusive): bedtime, wake time, time in bed, time asleep and light/deep/REM/awake durations in seconds, with the source device. Use for questions about sleep duration, quality or consistency.")
    }

    @Test func schemasHaveTheContractShapes() throws {
        let types = CoachTools.parameterSchema(for: "get_health_data_types")
        #expect((types["properties"] as? [String: Any])?.isEmpty == true)
        #expect(types["required"] == nil)
        for name in ["get_health_summary", "get_health_samples"] {
            let schema = CoachTools.parameterSchema(for: name)
            let properties = try #require(schema["properties"] as? [String: Any])
            #expect(Set(properties.keys) == ["data_type", "from", "to", "limit"])
            #expect((properties["limit"] as? [String: Any])?["type"] as? String == "integer")
            #expect(schema["required"] as? [String] == ["data_type", "from", "to"])
        }
        let sleep = CoachTools.parameterSchema(for: "get_sleep_history")
        let sleepProperties = try #require(sleep["properties"] as? [String: Any])
        #expect(Set(sleepProperties.keys) == ["from", "to", "limit"])
        #expect(sleep["required"] as? [String] == ["from", "to"])
        #expect(CoachTools.healthSummaryLimit == 400 && CoachTools.healthSamplesLimit == 200 && CoachTools.sleepHistoryLimit == 120)
    }

    @Test func nonHealthNamesDelegateToTheSynchronousExecute() async {
        let tools = tools(health: context(), enabled: true)
        let sync = tools.execute(name: "get_data_summary", arguments: [:])
        let async = await tools.executeAsync(name: "get_data_summary", arguments: [:])
        #expect(sync == async)
        let unknown = await tools.executeAsync(name: "nope", arguments: [:])
        #expect(unknown.contains("Unknown tool"))
    }

    @Test func disabledAccessReturnsAnErrorPayload() async throws {
        let payload = try json(await tools(health: context(), enabled: false).executeAsync(name: "get_health_summary", arguments: ["data_type": "steps", "from": "2026-09-01", "to": "2026-09-13"]))
        #expect(payload["error"] as? String == "Health data access is disabled.")
    }

    @Test func dataTypesPayloadShape() async throws {
        let payload = try json(await tools(health: context(), enabled: true).executeAsync(name: "get_health_data_types", arguments: [:]))
        #expect(payload["health_data_enabled"] as? Bool == true)
        #expect(payload["count"] as? Int == 1)
        #expect(payload["last_sync"] is String)
        let types = try #require(payload["data_types"] as? [[String: Any]])
        let steps = try #require(types.first)
        #expect(Set(steps.keys) == ["data_type", "category", "display_name", "unit", "aggregation", "count", "first", "last", "latest"])
        let latest = try #require(steps["latest"] as? [String: Any])
        #expect(latest["value"] as? Double == 812)
        #expect(latest["at"] as? String == "2026-09-13T21:00:00+02:00")
    }

    @Test func summaryPayloadShapeAndCap() async throws {
        let tools = tools(health: context(), enabled: true)
        let payload = try json(await tools.executeAsync(name: "get_health_summary", arguments: ["data_type": "steps", "from": "2026-09-07", "to": "2026-09-13", "limit": 3]))
        #expect(payload["data_type"] as? String == "steps")
        #expect(payload["unit"] as? String == "count")
        let highlights = try #require(payload["highlights"] as? [String: Any])
        #expect(Set(highlights.keys) == ["total", "average", "min", "max", "latest"])
        let days = try #require(payload["days"] as? [[String: Any]])
        #expect(days.count == 3)
        #expect(Set(days[0].keys) == ["date", "sum", "avg", "min", "max", "count", "own_sum"])
        let huge = try json(await tools.executeAsync(name: "get_health_summary", arguments: ["data_type": "steps", "from": "2026-09-07", "to": "2026-09-13", "limit": 9999]))
        #expect((huge["days"] as? [[String: Any]])?.count == 7)
    }

    @Test func unknownDataTypeAndBadDatesAreErrors() async throws {
        let tools = tools(health: context(), enabled: true)
        let unknown = try json(await tools.executeAsync(name: "get_health_summary", arguments: ["data_type": "spo2", "from": "2026-09-07", "to": "2026-09-13"]))
        #expect((unknown["error"] as? String)?.contains("Unknown data_type") == true)
        let dates = try json(await tools.executeAsync(name: "get_health_samples", arguments: ["data_type": "steps", "from": "2026-09-13", "to": "2026-09-07"]))
        #expect((dates["error"] as? String)?.contains("from and to") == true)
        let missing = try json(await tools.executeAsync(name: "get_health_samples", arguments: ["from": "2026-09-07", "to": "2026-09-13"]))
        #expect((missing["error"] as? String)?.contains("data_type is required") == true)
    }

    @Test func samplesAreCappedAt200AndCarryExtraObjects() async throws {
        let payload = try json(await tools(health: context(), enabled: true).executeAsync(name: "get_health_samples", arguments: ["data_type": "steps", "from": "2026-09-13", "to": "2026-09-13"]))
        let records = try #require(payload["records"] as? [[String: Any]])
        #expect(records.count == 200)
        #expect(payload["count"] as? Int == 200)
        #expect(Set(records[0].keys) == ["start", "end", "value", "value2", "value3", "value_text", "category_value", "title", "extra", "source", "device"])
        #expect((records[0]["extra"] as? [String: Any])?["k"] as? Int == 1)
    }

    @Test func sleepHistoryPayloadShapeAndCap() async throws {
        let payload = try json(await tools(health: context(), enabled: true).executeAsync(name: "get_sleep_history", arguments: ["from": "2026-09-01", "to": "2026-09-13"]))
        let nights = try #require(payload["nights"] as? [[String: Any]])
        #expect(nights.count == 120)
        #expect(payload["count"] as? Int == 120)
        #expect(Set(nights[0].keys) == ["night_of", "start", "end", "in_bed_s", "asleep_s", "light_s", "deep_s", "rem_s", "awake_s", "source"])
        #expect(nights[0]["asleep_s"] as? Double == 25200)
    }

    @Test func limitArgumentAcceptsProviderNumberShapes() {
        #expect(CoachTools.limitArgument(50, cap: 400) == 50)
        #expect(CoachTools.limitArgument(50.0, cap: 400) == 50)
        #expect(CoachTools.limitArgument("50", cap: 400) == 50)
        #expect(CoachTools.limitArgument(nil, cap: 400) == 400)
        #expect(CoachTools.limitArgument(-4, cap: 400) == 1)
        #expect(CoachTools.limitArgument(9000, cap: 200) == 200)
    }

    @Test func onDeviceBlockAppearsOnlyWithEnabledContext() {
        #expect(ChatService.onDeviceHealthBlock(nil) == "")
        #expect(ChatService.onDeviceHealthBlock(context(enabled: false)) == "")
        let block = ChatService.onDeviceHealthBlock(context())
        #expect(block.contains("## Health (last 7 days"))
        #expect(block.contains("- Steps: avg 6000/day"))
        #expect(block.contains("Never diagnose"))
    }

    @Test func promptSummaryLinesAreBoundedAndReadable() async {
        let lines = await HealthCoachPromptSummary.lines(query: query(), calendar: HealthTestFixtures.calendar, now: HealthTestFixtures.date(2026, 9, 13, 20))
        #expect(lines.count <= HealthCoachPromptSummary.maxLines)
        #expect(lines.first?.hasPrefix("- Sleep: avg 7 h 0 min asleep over 7 nights") == true)
        #expect(lines.contains { $0.hasPrefix("- Steps: avg 6000/day over 7 days, total 42000") })
    }
}
