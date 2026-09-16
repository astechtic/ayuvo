import Foundation
import Testing
@testable import calorietracker

/// `MR.localInstant` follows the reference's `fold = 0` rule (docs §5).
struct MedicationLocalTimeTests {
    private static func utc(_ iso: String) -> Int {
        Int(ISO8601DateFormatter().date(from: iso)!.timeIntervalSince1970 * 1000)
    }

    @Test func springForwardGapLandsGapLengthLater() {
        // New York 2026-03-08 02:30 does not exist; the pre-transition offset applies → 07:30Z (03:30 EDT).
        #expect(MR.localInstant(date: "2026-03-08", hhmm: "02:30", zone: "America/New_York") == Self.utc("2026-03-08T07:30:00Z"))
        #expect(MR.localInstant(date: "2026-03-08", hhmm: "01:30", zone: "America/New_York") == Self.utc("2026-03-08T06:30:00Z"))
        #expect(MR.localInstant(date: "2026-03-08", hhmm: "03:30", zone: "America/New_York") == Self.utc("2026-03-08T07:30:00Z"))
        #expect(MR.localInstant(date: "2026-03-08", hhmm: "12:00", zone: "America/New_York") == Self.utc("2026-03-08T16:00:00Z"))
    }

    @Test func fallBackOverlapUsesTheEarlierInstantOnce() {
        // New York 2026-11-01 01:30 happens twice; the earlier (EDT) instant wins → 05:30Z.
        #expect(MR.localInstant(date: "2026-11-01", hhmm: "01:30", zone: "America/New_York") == Self.utc("2026-11-01T05:30:00Z"))
        #expect(MR.localInstant(date: "2026-11-01", hhmm: "00:30", zone: "America/New_York") == Self.utc("2026-11-01T04:30:00Z"))
        #expect(MR.localInstant(date: "2026-11-01", hhmm: "03:00", zone: "America/New_York") == Self.utc("2026-11-01T08:00:00Z"))
    }

    @Test func halfHourZoneAndUTC() {
        #expect(MR.localInstant(date: "2026-09-16", hhmm: "09:00", zone: "Asia/Kolkata") == Self.utc("2026-09-16T03:30:00Z"))
        #expect(MR.localInstant(date: "2026-09-16", hhmm: "00:00", zone: "UTC") == Self.utc("2026-09-16T00:00:00Z"))
        #expect(MR.localInstant(date: "2026-13-01", hhmm: "09:00", zone: "UTC") == nil)
        #expect(MR.localInstant(date: "2026-09-16", hhmm: "24:00", zone: "UTC") == nil)
        #expect(MR.localInstant(date: "2026-09-16", hhmm: "09:00", zone: "Not/AZone") == nil)
    }

    @Test func matchesFoundationOnOrdinaryDays() throws {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(identifier: "Europe/London")!
        for (date, hhmm) in [("2026-09-16", "08:00"), ("2026-01-05", "23:59"), ("2026-06-30", "00:00")] {
            let parts = date.split(separator: "-").map { Int($0)! }
            let time = hhmm.split(separator: ":").map { Int($0)! }
            let components = DateComponents(year: parts[0], month: parts[1], day: parts[2], hour: time[0], minute: time[1])
            let expected = Int(try #require(calendar.date(from: components)).timeIntervalSince1970 * 1000)
            #expect(MR.localInstant(date: date, hhmm: hhmm, zone: "Europe/London") == expected)
        }
    }

    @Test func projectionsInvertTheInstant() {
        let ms = MR.localInstant(date: "2026-09-16", hhmm: "20:15", zone: "Asia/Kolkata")!
        #expect(MR.localDateOf(ms, zone: "Asia/Kolkata") == "2026-09-16")
        #expect(MR.localHHMMOf(ms, zone: "Asia/Kolkata") == "20:15")
        #expect(MR.localDateOf(ms, zone: "UTC") == "2026-09-16")
        #expect(MR.localHHMMOf(ms, zone: "UTC") == "14:45")
        let window = MR.dayWindow("2026-09-16", zone: "Asia/Kolkata")!
        #expect(window.0 == Self.utc("2026-09-15T18:30:00Z"))
        #expect(window.1 == Self.utc("2026-09-16T18:30:00Z"))
        #expect(MR.dayWindow("2026-03-08", zone: "America/New_York")!.1 - MR.dayWindow("2026-03-08", zone: "America/New_York")!.0 == 23 * 3_600_000)
    }

    @Test func intervalSlotsCrossMidnight() {
        let slots = MR.scheduleSlots(.obj(["frequency_kind": .str("interval"), "interval_hours": .int(8), "anchor_time": .str("22:00")]))
        #expect(slots == ["06:00", "14:00", "22:00"])
        #expect(MR.scheduleSlots(.obj(["frequency_kind": .str("interval"), "interval_hours": .int(5), "anchor_time": .str("08:00")])).isEmpty)
        #expect(MR.scheduleSlots(.obj(["frequency_kind": .str("daily"), "times": .arr([.str("20:00"), .str("08:00"), .str("08:00"), .str("x")])])) == ["08:00", "20:00"])
    }
}
