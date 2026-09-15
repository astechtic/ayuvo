import Foundation
import Testing
@testable import calorietracker

struct HealthSleepAnalysisTests {
    private typealias F = HealthTestFixtures

    @Test func unionMergesOverlappingIntervals() {
        let merged = HealthSleepAnalysis.unionMs([(0, 100), (50, 150), (200, 300), (300, 310), (400, 400)])
        #expect(merged == 150 + 110)
    }

    @Test func watchWinsOverPhoneOnTheSameNightAndStagesAreSplit() throws {
        let bed = F.date(2026, 9, 9, 23, 0)
        let watch = "com.apple.health.watch"
        let phone = "com.apple.health.phone"
        let rows = [
            // Phone: only in-bed, 8h, counts as zero asleep.
            F.row(id: "p", type: F.sleep, start: bed, end: bed.addingTimeInterval(8 * 3600), categoryValue: 0, source: phone),
            // Watch: core 3h, awake 30m, deep 2h, rem 2h.
            F.row(id: "w1", type: F.sleep, start: bed, end: bed.addingTimeInterval(3 * 3600), categoryValue: 3, source: watch),
            F.row(id: "w2", type: F.sleep, start: bed.addingTimeInterval(3 * 3600), end: bed.addingTimeInterval(3 * 3600 + 1800), categoryValue: 2, source: watch),
            F.row(id: "w3", type: F.sleep, start: bed.addingTimeInterval(3 * 3600 + 1800), end: bed.addingTimeInterval(5 * 3600 + 1800), categoryValue: 4, source: watch),
            F.row(id: "w4", type: F.sleep, start: bed.addingTimeInterval(5 * 3600 + 1800), end: bed.addingTimeInterval(7 * 3600 + 1800), categoryValue: 5, source: watch),
        ]
        let nights = HealthSleepAnalysis.nights(rows: rows, calendar: F.calendar)
        let night = try #require(nights.first)
        #expect(nights.count == 1)
        #expect(night.nightOf == "2026-09-10")
        #expect(night.source == watch)
        #expect(night.asleepS == 7 * 3600)
        #expect(night.lightS == 3 * 3600)
        #expect(night.deepS == 2 * 3600)
        #expect(night.remS == 2 * 3600)
        #expect(night.awakeS == 1800)
        #expect(night.inBedS == 7 * 3600 + 1800, "no in-bed rows from the winning source → span of its rows")
        #expect(night.startMs == F.ms(bed))
    }

    @Test func nightsAreGroupedByWakeDayAndSortedOldestFirst() {
        let firstBed = F.date(2026, 9, 8, 23, 0)
        let secondBed = F.date(2026, 9, 9, 22, 0)
        let rows = [
            F.row(id: "b", type: F.sleep, start: secondBed, end: secondBed.addingTimeInterval(6 * 3600), categoryValue: 1),
            F.row(id: "a", type: F.sleep, start: firstBed, end: firstBed.addingTimeInterval(7 * 3600), categoryValue: 1),
        ]
        let nights = HealthSleepAnalysis.nights(rows: rows, calendar: F.calendar)
        #expect(nights.map(\.nightOf) == ["2026-09-09", "2026-09-10"])
        #expect(nights.map(\.asleepS) == [7 * 3600, 6 * 3600])
    }

    @Test func outOfBedIsIgnoredAndDeletedRowsSkipped() {
        let bed = F.date(2026, 9, 9, 23, 0)
        var deleted = F.row(id: "d", type: F.sleep, start: bed, end: bed.addingTimeInterval(3600), categoryValue: 1)
        deleted.deleted = 1
        let rows = [
            deleted,
            F.row(id: "o", type: F.sleep, start: bed, end: bed.addingTimeInterval(3600), categoryValue: 6),
        ]
        #expect(HealthSleepAnalysis.nights(rows: rows, calendar: F.calendar).isEmpty)
    }
}
