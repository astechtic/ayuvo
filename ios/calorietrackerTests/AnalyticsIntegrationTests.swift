import Foundation
import Testing
@testable import calorietracker

/// App side of the analytics engine (docs/health-analytics.md): heartbeat-series conversion, the Insights →
/// engine input builder, Recovery v2 in the app's result shape, incremental storage and the Coach evidence.
struct AnalyticsIntegrationTests {
    private static var config: AnalyticsConfig { AnalyticsVectorTests.config }

    /// A shared recovery vector case as Insights inputs (the shape `InsightsDataSource` builds).
    private static func vectorCase(_ name: String) throws -> (insights: InsightsInputs, day: String, expected: AJ) {
        let url = AnalyticsVectorTests.vectorsDirectory.appendingPathComponent("recovery.json")
        let doc = try #require(AJ.parse(try Data(contentsOf: url)))
        let c = try #require(doc["cases"].array.first { $0["name"].string == name })
        let a = AnalyticsEngine.decodeInputs(c["input"]["inputs"])
        var inputs = InsightsInputs()
        inputs.timeZone = a.timeZone
        for (metric, series) in a.series where metric != "wrist_temperature" { inputs.series[metric] = series }
        if let t = a.series["wrist_temperature"] { inputs.analytics.series["wrist_temperature"] = t }
        inputs.analytics.nights = a.nights
        inputs.analytics.sources = a.sources
        inputs.analytics.workouts = a.workouts.map {
            AnalyticsWorkoutInput(startMs: $0.startMs, endMs: $0.endMs, effort: $0.effort, trimp: $0.trimp, activity: $0.activity,
                                  kcal: nil, provider: true)
        }
        inputs.tracking.workouts = a.trackingWorkouts
        inputs.overnightFallback = a.overnightFallback
        let day = c["input"]["day"].string ?? ""
        for (metric, days) in a.contexts {
            for (d, ctx) in days where ctx == "camera" { inputs.scanFallback[metric, default: []].insert(d) }
        }
        for metric in a.scanFallback { inputs.scanFallback[metric, default: []].insert(day) }
        return (inputs, day, c["expected"])
    }

    // MARK: Heartbeat series

    @Test func heartbeatIntervalsNeverSpanAGap() {
        let beats: [(time: Double, precededByGap: Bool)] = [(0.0, false), (1.0, false), (2.05, false), (5.0, true), (6.0, false), (6.9, false)]
        let ibis = HeartbeatHRV.intervals(beats)
        #expect(ibis.count == 4, "the interval ending on the gap beat is dropped")
        #expect(abs(ibis[0].0 - 1000) < 1e-9 && ibis[0].1 == false)
        #expect(abs(ibis[1].0 - 1050) < 1e-9 && ibis[1].1 == false)
        #expect(abs(ibis[2].0 - 1000) < 1e-9 && ibis[2].1 == true, "no successive difference across the gap")
        #expect(abs(ibis[3].0 - 900) < 1e-9 && ibis[3].1 == false)
        #expect(HeartbeatHRV.intervals([]).isEmpty)
    }

    @Test func heartbeatSeriesBelongToTheNightThatContainsThem() {
        let night = (startMs: Int64(1_773_100_000_000), endMs: Int64(1_773_128_800_000))
        let nights = ["2026-03-10": night]
        #expect(HeartbeatHRV.day(startMs: night.startMs + 3_600_000, nights: nights, timeZone: "UTC") == "2026-03-10")
        // A daytime series uses its own local day.
        #expect(HeartbeatHRV.day(startMs: 1_773_300_000_000, nights: nights, timeZone: "UTC") == "2026-03-12")
        let a = HeartbeatHRV.dayHash(seriesIDs: [("b", 2), ("a", 1)], night: night)
        let b = HeartbeatHRV.dayHash(seriesIDs: [("a", 1), ("b", 2)], night: night)
        let c = HeartbeatHRV.dayHash(seriesIDs: [("a", 1)], night: night)
        #expect(a == b, "order of the series does not matter")
        #expect(a != c, "a new or deleted series changes the hash")
    }

    // MARK: Inputs and Recovery v2

    @Test func builderReproducesTheReferenceRecovery() throws {
        for name in ["valid_full", "camera_and_fallback_warnings", "source_changed_today", "sparse_inputs_low_confidence"] {
            let v = try Self.vectorCase(name)
            let a = AnalyticsInputsBuilder.make(v.insights, today: v.day)
            let got = AnalyticsRecovery.recovery(a, day: v.day, Self.config)
            let diff = AnalyticsVectorTests.firstDifference(got, v.expected, tolerance: Self.config.tolerance)
            #expect(diff == nil, "\(name): \(diff ?? "")")
        }
    }

    @Test func cameraAndDerivedDaysGetTheirContexts() {
        var inputs = InsightsInputs()
        inputs.series["resting_heart_rate"] = ["2026-03-14": 55, "2026-03-15": 52]
        inputs.scanFallback["resting_heart_rate"] = ["2026-03-15"]
        inputs.analytics.derivedDays["resting_heart_rate"] = ["2026-03-14"]
        let a = AnalyticsInputsBuilder.make(inputs, today: "2026-03-15")
        #expect(a.contexts["resting_heart_rate"] == ["2026-03-14": "derived", "2026-03-15": "camera"])
        #expect(a.scanFallback == ["resting_heart_rate"])
        #expect(AnalyticsInputsBuilder.make(inputs, today: "2026-03-16").scanFallback.isEmpty, "only today's scans warn")
    }

    @Test func recoveryV2AdaptsToTheAppShape() throws {
        let ok = try Self.vectorCase("valid_full")
        let r = RecoveryV2Adapter.result(ok.expected)
        #expect(r.status == "ok" && r.score == ok.expected["score"].int && r.v2 != nil)
        #expect(r.components.count == 6)
        #expect(r.positives.count + r.negatives.count == ok.expected["drivers"].array.filter { $0["direction"].string != "neutral" }.count)
        let collecting = try Self.vectorCase("collecting")
        #expect(RecoveryV2Adapter.result(collecting.expected).status == "collecting")
        #expect(RecoveryV2Adapter.result(try Self.vectorCase("no_sleep").expected).status == "no_sleep")
        #expect(RecoveryV2Adapter.result(try Self.vectorCase("no_heart_data").expected).status == "no_heart_data")
        let spike = RecoveryV2Adapter.result(try Self.vectorCase("load_spike_modifier").expected)
        #expect(spike.load?.category == "high" && spike.load?.modifier == -6)
    }

    // MARK: Storage and evidence

    @Test func storageIsIncrementalAndVersioned() async throws {
        let v = try Self.vectorCase("valid_full")
        let (_, json) = RecoveryV2Adapter.recovery(AnalyticsInputsBuilder.make(v.insights, today: v.day), inputs: v.insights, day: v.day)
        let bundle = AnalyticsSuite.compute(v.insights, today: v.day, recoveryByDay: [v.day: json])
        let db = try await HealthDatabase.inMemory()
        let first = await AnalyticsService.persist(bundle, database: db, nowMs: 1)
        #expect(first > 0)
        #expect(await AnalyticsService.persist(bundle, database: db, nowMs: 2) == 0, "unchanged inputs are not rewritten")
        let stored = try #require(try await db.latestAnalyticsResult(metric: "recovery_indicator", algorithmVersion: 2))
        #expect(stored.value == Double(json["score"].int ?? -1))
        #expect(stored.classification == "PERSONALIZED_STATISTICAL" && stored.algorithmID == "ayuvo.recovery")
        let provenance = try #require(AJ.parse(Data(stored.provenanceJSON.utf8)))
        #expect(provenance["algorithm"].string == "ayuvo.recovery@2")
    }

    @Test func evidenceCarriesTheComputedValuesOnly() throws {
        let v = try Self.vectorCase("poor_night_and_heart")
        let (_, json) = RecoveryV2Adapter.recovery(AnalyticsInputsBuilder.make(v.insights, today: v.day), inputs: v.insights, day: v.day)
        let bundle = AnalyticsSuite.compute(v.insights, today: v.day, recoveryByDay: [v.day: json])
        let items = bundle.evidence["items"].array
        let recovery = try #require(items.first { $0["metric"].string == "recovery_indicator" })
        #expect(recovery["score"].int == json["score"].int)
        #expect(recovery["algorithm"].string == "ayuvo.recovery@2")
        #expect(items.contains { $0["metric"].string == "multi_signal_deviation" })
        let field = ActionExecutor.actionField(bundle.evidence)
        guard case .object(let o) = field, case .list(let list)? = o["items"] else {
            Issue.record("evidence is not an object with items")
            return
        }
        #expect(list.count == items.count)
        #expect(bundle.forecasts.isEmpty, "no forecast unless the switch is on")
    }
}
