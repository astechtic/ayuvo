import Foundation
import Testing
@testable import calorietracker

/// Partner screens' view-model logic: freshness text, metric formatting, section visibility and the dashboard model
/// built only from stored rows (nothing fabricated).
@MainActor
struct PartnerDisplayTests {
    typealias S = PartnerTestSupport
    static let en = Locale(identifier: "en_US")
    static var utc: Calendar {
        var c = Calendar(identifier: .gregorian)
        c.timeZone = TimeZone(identifier: "UTC")!
        return c
    }

    // MARK: Freshness

    @Test func freshnessReadsLikeASentence() {
        let now = S.now   // 2026-10-07 12:00 UTC
        func fresh(_ secondsAgo: Double?, _ status: String = "up_to_date", trusted: Bool = true) -> String {
            PartnerDisplay.freshness(lastSyncMs: secondsAgo.map { Int64((now.timeIntervalSince1970 - $0) * 1000) }, status: status, trusted: trusted,
                                     now: now, locale: Self.en, calendar: Self.utc)
        }
        #expect(fresh(20) == "Updated just now")
        #expect(fresh(3 * 60) == "Updated 3 min. ago")
        #expect(fresh(2 * 3600 + 120) == "Updated 2 hr. ago")
        #expect(fresh(24 * 3600, "partner_unavailable") == "Updated yesterday · Partner offline")
        #expect(fresh(3 * 86_400) == "Updated 3 days ago")
        #expect(fresh(10 * 86_400) == "Updated Sep 27")
        #expect(fresh(nil) == "Not synced yet")
        #expect(fresh(60 * 5, "up_to_date", trusted: false) == "Updated 5 min. ago · No longer paired")
        #expect(fresh(60 * 5, "local_network_denied") == "Updated 5 min. ago · Local Network off")
        #expect(fresh(60 * 5, "sync_failed") == "Updated 5 min. ago · Sync didn't finish")
        // A partner clock slightly ahead never shows a future time.
        #expect(fresh(-30) == "Updated just now")
    }

    @Test func statusAndShortFingerprint() {
        #expect(PartnerDisplay.shortStatus("up_to_date", trusted: true) == nil)
        #expect(PartnerDisplay.shortStatus("not_trusted", trusted: true) == "Pair again")
        #expect(PartnerDisplay.shortFingerprint("5cbe8b6c1a846a49e8e550ecf82c3931") == "5CBE 8B6C")
        #expect(PartnerDisplay.initial("ananya") == "A")
        #expect(PartnerDisplay.initial("") == "?")
    }

    // MARK: Metric formatting

    @Test func summaryMetricsAreFormattedPerKey() {
        func m(_ key: String, _ value: Int, _ unit: String? = nil) -> PartnerSummaryMetric {
            PartnerSummaryMetric(key: key, category: "vitals", value: value, unit: unit, day: "2026-10-07", shared: true)
        }
        #expect(PartnerDisplay.metricValue(m("recovery", 82)) == "82")
        #expect(PartnerDisplay.metricValue(m("sleep", 444, "min")) == "7 h 24 min")
        #expect(PartnerDisplay.metricValue(m("sleep", 45, "min")) == "45 min")
        #expect(PartnerDisplay.metricValue(m("sleep", 480, "min")) == "8 h")
        #expect(PartnerDisplay.metricValue(m("resting_hr", 58, "bpm")) == "58 bpm")
        #expect(PartnerDisplay.metricValue(m("activity", 32, "min")) == "32 min")
        #expect(PartnerDisplay.metricValue(m("steps", 8421, "count")).hasSuffix("steps"))
        #expect(PartnerDisplay.metricValue(m("medicines", 1, "of:2")) == "1 of 2 taken")
        #expect(m("medicines", 1, "of:2").ofTotal == 2)
        #expect(m("recovery", 82).ofTotal == nil)
    }

    @Test func vitalValuesUseTheRightUnits() {
        #expect(PartnerDisplay.value(key: "spo2", value: 0.97, unit: "%") == "97%")
        #expect(PartnerDisplay.value(key: "spo2", value: 96, unit: "%") == "96%")
        #expect(PartnerDisplay.value(key: "blood_pressure", value: 121.4, value2: 79.6, unit: "mmHg") == "121/80 mmHg")
        #expect(PartnerDisplay.value(key: "blood_pressure", value: 121, unit: "mmHg") == "121 mmHg")
        #expect(PartnerDisplay.value(key: "hrv", value: 41.6, unit: "ms") == "42 ms")
        #expect(PartnerDisplay.value(key: "weight", value: 70, unit: "kg", pounds: false) == "70.0 kg")
        #expect(PartnerDisplay.value(key: "weight", value: 70, unit: "kg", pounds: true) == "154.3 lbs")
    }

    @Test func summaryMetricsComeOnlyFromStoredRows() {
        let rows = [
            Self.row("analytics_day", "recovery_indicator:2026-10-07", "vitals", "2026-10-07",
                     #"{"metric_id":"recovery_indicator","value":81.6,"status":"ok","classification":"good","day":"2026-10-07"}"#),
            Self.row("metric_day", "resting_heart_rate:2026-10-07", "vitals", "2026-10-07",
                     #"{"type_id":"resting_heart_rate","avg":58,"unit":"bpm","day":"2026-10-07"}"#),
        ]
        let grants = [PartnerGrantReceived(category: "vitals", granted: false, revokedMs: 5, updatedMs: 5)]
        let metrics = PartnerSummaryMetric.compute(rows: rows, grants: grants, today: "2026-10-08", yesterday: "2026-10-07")
        #expect(metrics.map(\.key) == ["recovery", "resting_hr"])
        #expect(metrics.map(\.value) == [82, 58])
        #expect(metrics.allSatisfy { !$0.shared })   // revoked: kept, shown dimmed
        #expect(PartnerSummaryMetric.compute(rows: [], grants: grants, today: "2026-10-08", yesterday: "2026-10-07").isEmpty)
    }

    // MARK: Section visibility

    @Test func sectionVisibilityFollowsGrants() {
        let grants = [
            PartnerGrantReceived(category: "vitals", granted: true, revokedMs: nil, updatedMs: 1),
            PartnerGrantReceived(category: "sleep", granted: false, revokedMs: 9, updatedMs: 9),
            PartnerGrantReceived(category: "nutrition", granted: false, revokedMs: nil, updatedMs: 1),
        ]
        #expect(PartnerSectionState.of("vitals", grants: grants, categoriesWithData: []) == .shared)
        #expect(PartnerSectionState.of("sleep", grants: grants, categoriesWithData: []) == .revoked)
        #expect(PartnerSectionState.of("nutrition", grants: grants, categoriesWithData: []) == .hidden)
        #expect(PartnerSectionState.of("workouts", grants: grants, categoriesWithData: []) == .hidden)
        #expect(PartnerSectionState.of("workouts", grants: grants, categoriesWithData: ["workouts"]) == .revoked)
    }

    @Test func restDayOnlyWhenWorkoutsAreSharedAndNoneLogged() {
        func data(_ granted: Bool, revoked: Bool = false, workouts: [PartnerRecordRow] = []) -> PartnerDashboardData {
            let g = [PartnerGrantReceived(category: "workouts", granted: granted, revokedMs: revoked ? 1 : nil, updatedMs: 1)]
            return PartnerDashboardData.build(day: "2026-10-07", rows: workouts, latest: [], reports: [], grants: g, categoriesWithData: [])
        }
        #expect(data(true).showsRestDay)
        #expect(!data(false).showsRestDay)
        #expect(!data(false, revoked: true).showsRestDay)
        let w = Self.row("workout", "w1", "workouts", "2026-10-07", #"{"activity":"running","start_ms":1,"end_ms":2,"duration_s":1800}"#)
        #expect(!data(true, workouts: [w]).showsRestDay)
        #expect(data(true, workouts: [w]).workouts.first?.durationS == 1800)
    }

    // MARK: Dashboard model from the fixture rows

    @Test func dashboardBuildsFromTheSharedFixtureRows() throws {
        let rows = try Self.fixtureRows()
        let grants = PartnerCatalog.shared.categories.map { PartnerGrantReceived(category: $0, granted: true, revokedMs: nil, updatedMs: 1) }
        #expect(PartnerDashboardData.defaultDay(rows: rows, today: "2026-10-08", earliest: "2026-09-09") == "2026-10-07")
        #expect(PartnerDashboardData.defaultDay(rows: rows, today: "2026-10-08", earliest: "2026-10-08") == "2026-10-08")
        let latest = rows.filter { $0.type == "metric_day" }
        let d = PartnerDashboardData.build(day: "2026-10-07", rows: rows, latest: latest, reports: rows, grants: grants,
                                           categoriesWithData: Set(rows.map(\.category)))
        #expect(d.recovery?.value == 81.6)
        #expect(d.sleep?.asleepMin == 444)
        #expect(d.sleep?.hasStages == true)
        #expect(d.food.map(\.name) == ["Oats with milk"])
        #expect(d.calories == 320)
        #expect(d.macroTotal(\.proteinG) == 12.5)
        #expect(d.waterMl == 1750)
        #expect(d.doses.map(\.medication) == ["Vitamin D3"])
        #expect(d.dosesTaken == 1)
        #expect(d.workouts.isEmpty && d.showsRestDay)
        #expect(d.vitals.map(\.key) == ["resting_hr"])   // nothing else was shared: no HRV, SpO2, BP or weight invented
        #expect(Set(d.trends.map(\.key)) == ["resting_hr", "sleep", "steps"])
        #expect(d.trends.first { $0.key == "steps" }?.points == [.init(day: "2026-10-07", value: 8421)])
        #expect(d.reports.map(\.title) == ["Complete Blood Count"])
        #expect(d.reports.first?.abnormalCount == 1)

        // Another day: the day sections are empty, latest vitals and reports stay.
        let other = PartnerDashboardData.build(day: "2026-10-08", rows: rows, latest: latest, reports: rows, grants: grants, categoriesWithData: [])
        #expect(other.recovery == nil && other.sleep == nil && other.food.isEmpty && other.waterMl == nil && other.doses.isEmpty)
        #expect(other.vitals.count == 1 && other.reports.count == 1)
    }

    @Test func dayTitlesAndReferenceRanges() {
        #expect(PartnerDisplay.dayTitle("2026-10-08", today: "2026-10-08") == String(localized: "Today"))
        #expect(PartnerDisplay.dayTitle("2026-10-07", today: "2026-10-08") == String(localized: "Yesterday"))
        let r: RJ = .obj(["name": .str("Hemoglobin"), "value": .str("11.2"), "unit": .str("g/dL"), "ref_low": .int(12), "ref_high": .int(15), "flag": .str("low")])
        #expect(PartnerDisplay.referenceRange(r) == "12–15 g/dL")
        #expect(PartnerDisplay.resultValue(r) == "11.2 g/dL")
        #expect(PartnerDisplay.referenceRange(.obj(["ref_text": .str("negative")])) == "negative")
        #expect(PartnerDisplay.referenceRange(.obj([:])) == nil)
        #expect(PartnerDisplay.flagText("low") == "Low")
        #expect(PartnerDisplay.flagText("unknown").isEmpty)
    }

    // MARK: Helpers

    static func row(_ type: String, _ id: String, _ category: String, _ day: String?, _ json: String) -> PartnerRecordRow {
        PartnerRecordRow(type: type, recordID: id, category: category, rev: 1, day: day, tsMs: nil, updatedMs: 1, dataJSON: json)
    }

    /// The envelopes of the shared cross-platform fixture package as stored rows.
    static func fixtureRows() throws -> [PartnerRecordRow] {
        let reader = try ZipArchiveReader(url: PartnerPackageTests.fixtureZip)
        var rows: [PartnerRecordRow] = []
        for entry in reader.entries where entry.name.hasPrefix("health/") {
            let text = String(decoding: try reader.data(for: entry), as: UTF8.self)
            for line in text.split(separator: "\n") {
                let env = try #require(PartnerJSON.parse(String(line)))
                rows.append(PartnerRecordRow(type: env["type"].string ?? "", recordID: env["id"].string ?? "", category: env["category"].string ?? "",
                                             rev: Int64(env["rev"].pyInt ?? 0), day: env["day"].string, tsMs: nil, updatedMs: 1,
                                             dataJSON: PartnerJSON.canonical(env["data"])))
            }
        }
        return rows
    }
}
