import Foundation
import HealthKit
import Testing
@testable import calorietracker

struct HealthSampleMapperTests {
    private typealias F = HealthTestFixtures
    private let nowMs: Int64 = 1_800_000_000_000

    @Test func quantitySampleMapsValueUnitAndDay() throws {
        let start = F.date(2026, 9, 10, 7, 30)
        let sample = HKQuantitySample(
            type: HKQuantityType(.stepCount),
            quantity: HKQuantity(unit: .count(), doubleValue: 1234),
            start: start,
            end: start.addingTimeInterval(600),
            metadata: [HKMetadataKeyTimeZone: "Europe/Berlin", "ayuvo_note": "x"]
        )
        let row = try #require(HealthSampleMapper.row(from: sample, type: F.steps, calendar: F.calendar, nowMs: nowMs))
        #expect(row.id == sample.uuid.uuidString.lowercased())
        #expect(row.typeID == "steps")
        #expect(row.value == 1234)
        #expect(row.unit == "count")
        #expect(row.localDay == "2026-09-10")
        #expect(row.startOffsetS == 7200)
        #expect(row.updatedMs == nowMs)
        #expect(row.extraJSON?.contains("ayuvo_note") == true)
        #expect(row.count == 1)
    }

    @Test func percentValuesAreScaledToZeroToOneHundred() throws {
        let start = F.date(2026, 9, 10)
        let sample = HKQuantitySample(type: HKQuantityType(.oxygenSaturation), quantity: HKQuantity(unit: .percent(), doubleValue: 0.97), start: start, end: start)
        let row = try #require(HealthSampleMapper.row(from: sample, type: HealthMetricRegistry.type(id: "blood_oxygen")!, calendar: F.calendar, nowMs: nowMs))
        #expect(abs((row.value ?? 0) - 97) < 0.0001)
    }

    @Test func sleepStagesMapToCanonicalCodesOnWakeDay() throws {
        let start = F.date(2026, 9, 9, 23, 30)
        let end = F.date(2026, 9, 10, 6, 45)
        let cases: [(Int, Int)] = [
            (HKCategoryValueSleepAnalysis.inBed.rawValue, 0),
            (1, 1), // asleepUnspecified / legacy asleep
            (HKCategoryValueSleepAnalysis.awake.rawValue, 2),
            (3, 3), (4, 4), (5, 5),
        ]
        for (hkValue, canonical) in cases {
            let sample = HKCategorySample(type: HKCategoryType(.sleepAnalysis), value: hkValue, start: start, end: end)
            let row = try #require(HealthSampleMapper.row(from: sample, type: F.sleep, calendar: F.calendar, nowMs: nowMs))
            #expect(row.categoryValue == canonical)
            #expect(row.localDay == "2026-09-10", "sleep attributes to the wake day")
            #expect(row.value == end.timeIntervalSince(start))
        }
    }

    @Test func workoutMapsDurationActivityAndStatistics() throws {
        let start = F.date(2026, 9, 8, 18)
        let workout = HKWorkout(activityType: .running, start: start, end: start.addingTimeInterval(1800))
        let type = HealthMetricRegistry.type(id: "workout")!
        let row = try #require(HealthSampleMapper.row(from: workout, type: type, calendar: F.calendar, nowMs: nowMs))
        #expect(row.value == 1800)
        #expect(row.categoryValue == Int(HKWorkoutActivityType.running.rawValue))
        #expect(row.title == "Running")
        let extra = row.extra
        #expect(extra["activity_type"] as? Int == Int(HKWorkoutActivityType.running.rawValue))
        #expect(extra["has_route"] as? Bool == false)
    }

    @Test func bloodPressureCorrelationFillsSystolicAndDiastolic() throws {
        let start = F.date(2026, 9, 8, 9)
        let systolic = HKQuantitySample(type: HKQuantityType(.bloodPressureSystolic), quantity: HKQuantity(unit: .millimeterOfMercury(), doubleValue: 121), start: start, end: start)
        let diastolic = HKQuantitySample(type: HKQuantityType(.bloodPressureDiastolic), quantity: HKQuantity(unit: .millimeterOfMercury(), doubleValue: 79), start: start, end: start)
        let correlation = HKCorrelation(type: HKCorrelationType(.bloodPressure), start: start, end: start, objects: [systolic, diastolic])
        let row = try #require(HealthSampleMapper.row(from: correlation, type: F.bloodPressure, calendar: F.calendar, nowMs: nowMs))
        #expect(row.value == 121)
        #expect(row.value2 == 79)
        #expect(row.unit == "mmHg")
    }

    @Test func incompatibleUnitFallsBackToValueText() throws {
        let start = F.date(2026, 9, 8)
        // Distance sample mapped as if it were a count type: incompatible unit → text only.
        let sample = HKQuantitySample(type: HKQuantityType(.distanceWalkingRunning), quantity: HKQuantity(unit: .meter(), doubleValue: 500), start: start, end: start)
        let row = try #require(HealthSampleMapper.row(from: sample, type: F.steps, calendar: F.calendar, nowMs: nowMs))
        #expect(row.value == nil)
        #expect(row.valueText != nil)
    }

    @Test func metadataIsSanitizedAndCapped() {
        let big = String(repeating: "x", count: 5000)
        let json = HealthSampleMapper.sanitizedMetadataJSON(["huge": big, "ayuvo_workout_session_id": "abc", "date": Date(timeIntervalSince1970: 0)])
        #expect(json?.contains("ayuvo_workout_session_id") == true)
        #expect(json?.contains("huge") == false)
        #expect(HealthSampleMapper.sanitizedMetadataJSON(nil) == nil)
        #expect(HealthSampleMapper.sanitizedMetadataJSON([:]) == nil)
    }

    @Test func sourcesAreDeduplicatedPerBundle() {
        let start = F.date(2026, 9, 8)
        let a = HKQuantitySample(type: HKQuantityType(.stepCount), quantity: HKQuantity(unit: .count(), doubleValue: 1), start: start, end: start)
        let b = HKQuantitySample(type: HKQuantityType(.stepCount), quantity: HKQuantity(unit: .count(), doubleValue: 2), start: start, end: start.addingTimeInterval(60))
        let sources = HealthSampleMapper.sources(from: [a, b], nowMs: nowMs)
        #expect(sources.count == 1)
        #expect(sources.first?.lastSeenMs == F.ms(start.addingTimeInterval(60)))
    }
}
