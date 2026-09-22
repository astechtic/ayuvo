import Foundation
import Testing
@testable import calorietracker

/// §15 display window: Important highlights show only records dated within the last 6 calendar months.
struct RecordsHighlightWindowTests {
    private typealias F = RecordsTestFixtures

    @Test func sinceIsSixCalendarMonthsBackWithMonthEndClamp() {
        #expect(RecordDates.highlightsSince(today: "2026-09-22") == "2026-03-22")
        #expect(RecordDates.highlightsSince(today: "2026-08-31") == "2026-02-28")
        #expect(RecordDates.highlightsSince(today: "2028-08-31") == "2028-02-29")
        #expect(RecordDates.highlightsSince(today: "2026-01-15") == "2025-07-15")
        #expect(RecordDates.highlightsSince(today: "garbage") == nil)
    }

    @Test func sinceUsesTheLocalCalendarDay() throws {
        // 2026-09-21 23:30 UTC is already 2026-09-22 in Kolkata.
        let instant = Date(timeIntervalSince1970: Double(F.ms("2026-09-21", hour: 23)) / 1000 + 1_800)
        let kolkata = try #require(TimeZone(identifier: "Asia/Kolkata"))
        let utc = try #require(TimeZone(identifier: "UTC"))
        #expect(RecordDates.highlightsSince(now: instant, timeZone: kolkata) == "2026-03-22")
        #expect(RecordDates.highlightsSince(now: instant, timeZone: utc) == "2026-03-21")
    }

    @Test func databaseFiltersOldRecordsOnlyWhenSinceIsGiven() async throws {
        let db = try await RecordsDatabase.inMemory(timeZone: TimeZone(identifier: "UTC")!)
        let recent = try await db.insert(F.record(id: "recent", title: "Lipid Test", documentDate: "2026-09-05", createdMs: F.ms("2026-09-06")))
        let edge = try await db.insert(F.record(id: "edge", title: "HbA1c", documentDate: "2026-03-22", createdMs: F.ms("2026-03-23")))
        _ = try await db.insert(F.record(id: "old", title: "Hematology", documentDate: "2024-05-18", createdMs: F.ms("2026-09-10")))
        // No record date: the import day decides (§5 sort_date fallback).
        _ = try await db.insert(F.record(id: "undated", title: "Note", createdMs: F.ms("2026-09-15")))
        try await db.withConnection { connection in
            for (id, record) in [("h1", "recent"), ("h2", "edge"), ("h3", "old"), ("h4", "undated")] {
                try connection.run(
                    "INSERT INTO record_highlights (id, record_id, section, text, method, confidence, dismissed, position, created_ms) VALUES (?, ?, 'important', ?, 'rules', 0.9, 0, 0, 0)",
                    [.text(id), .text(record), .text("text \(id)")]
                )
            }
        }
        let windowed = try await db.importantHighlights(limit: 8, since: "2026-03-22").map(\.0.id)
        #expect(windowed == ["h4", "h1", "h2"])
        let all = try await db.importantHighlights(limit: 8).map(\.0.id)
        #expect(Set(all) == ["h1", "h2", "h3", "h4"])
        #expect(recent.sortDate == "2026-09-05")
        #expect(edge.sortDate == "2026-03-22")
    }

    @Test func referencePortMatchesTheDatabaseRule() {
        let records: [RJ] = [
            .obj(["id": .str("a"), "sort_date": .str("2026-09-05"), "seq": .int(1), "archived": .bool(false)]),
            .obj(["id": .str("b"), "sort_date": .str("2024-05-18"), "seq": .int(2), "archived": .bool(false)]),
        ]
        let highlights: [RJ] = [
            .obj(["id": .str("h1"), "record_id": .str("a"), "section": .str("important"), "text": .str("x"), "position": .int(0), "dismissed": .bool(false)]),
            .obj(["id": .str("h2"), "record_id": .str("b"), "section": .str("important"), "text": .str("y"), "position": .int(0), "dismissed": .bool(false)]),
        ]
        let windowed = RR.importantHighlights(records, highlights, limit: 8, since: "2026-03-22").compactMap { $0["id"].string }
        #expect(windowed == ["h1"])
        let unbounded = RR.importantHighlights(records, highlights, limit: 8, since: nil).compactMap { $0["id"].string }
        #expect(unbounded == ["h1", "h2"])
    }
}
