import Foundation

// Swift port of `scripts/medications_reference.py` (docs/medications.md). The reference wins over
// prose; every function here follows it line by line and operates on the same JSON-shaped values
// (`RJ`) the vectors use, so `MedicationsVectorTests` runs the shared cases unchanged. Typed
// overloads for the store live in `MedicationsReferenceTyped.swift`.
//
// Ids created here are "new-1", "new-2", … in creation order (ports substitute UUIDs in that order);
// "now" and the time zone are always explicit inputs; no clock, locale or I/O.

nonisolated enum MR {
    // MARK: §3 Enumerations and constants

    static let forms = ["tablet", "capsule", "syrup", "injection", "cream", "drops", "inhaler", "other"]
    static let foodRelations = ["before", "with", "after", "anytime"]
    static let statuses = ["active", "paused", "completed", "stopped"]
    static let frequencyKinds = ["daily", "weekly", "interval"]
    static let doseStatuses = ["scheduled", "due", "taken", "skipped", "missed", "snoozed"]
    static let storedDoseStatuses: Set<String> = ["taken", "skipped", "missed", "snoozed"]
    static let terminalDoseStatuses: Set<String> = ["taken", "skipped", "missed"]
    static let doseUnits = ["tablet", "capsule", "ml", "mg", "g", "mcg", "drop", "puff", "unit", "sachet", "application", "other"]
    static let defaultUnitForForm: [String: String] = [
        "tablet": "tablet", "capsule": "capsule", "drops": "drop", "inhaler": "puff", "injection": "unit",
        "cream": "application", "syrup": "ml", "other": "unit",
    ]
    static let intervalHours: [Int] = [1, 2, 3, 4, 6, 8, 12, 24]
    static let snoozeMinutes: [Int] = [10, 30, 60]

    /// 120 min: a dose is missed this long after its (snoozed) time.
    static let graceMs = 7_200_000
    /// 5 min: a dose that came due this recently still fires "now".
    static let lateFireMs = 300_000
    static let adherenceWindowMs = 7 * 86_400_000
    /// 64 pending notifications − 12 reserved for the rest of the app.
    static let iosBudget = 52
    static let maxTimes = 12
    static let nameMax = 80
    static let strengthMax = 40
    static let instructionsMax = 200
    static let noteMax = 200
    static let doseQuantityMax = 1000.0

    /// Meal-anchored default slots (docs §13). `1-0-1` = morning / afternoon / night positions of `slots3`.
    static let slots1 = ["08:00"]
    static let slots2 = ["08:00", "20:00"]
    static let slots3 = ["08:00", "14:00", "20:00"]
    static let slots4 = ["08:00", "13:00", "18:00", "22:00"]
    static let slotNight = ["22:00"]
    static let intervalAnchor = "08:00"
    static let weeklyDefaultDays = [1]

    static let archiveFormat = "ayuvo-medications"
    static let archiveVersion = 1

    static let medicationColumns = ["id", "name", "generic_name", "brand_name", "strength", "form", "dose_quantity", "dose_unit",
                                    "food_relation", "instructions", "start_date", "end_date", "status", "is_prn", "photo_path",
                                    "related_record_id", "created_ms", "updated_ms"]
    /// `times` / `days` are the parsed forms of the `times_json` / `days_json` columns (archives carry arrays).
    static let scheduleColumns = ["id", "medication_id", "frequency_kind", "times", "days", "interval_hours", "anchor_time",
                                  "reminder_enabled", "active_from_ms", "active_until_ms", "created_ms", "updated_ms"]
    static let doseLogColumns = ["id", "medication_id", "schedule_id", "scheduled_at_ms", "status", "taken_at_ms",
                                 "snoozed_until_ms", "dose_quantity", "dose_unit", "note", "created_ms", "updated_ms"]

    // MARK: Small helpers

    /// Deterministic id generator: new-1, new-2, … per top-level call.
    final class Ids {
        private var n = 0
        func next() -> String {
            n += 1
            return "new-\(n)"
        }
    }

    static let reHHMM = Rx("^([01][0-9]|2[0-3]):([0-5][0-9])$")
    static let reDate = Rx("^([0-9]{4})-([0-9]{2})-([0-9]{2})$")

    /// Python `isinstance(v, int) and not bool`.
    static func isInt(_ v: RJ) -> Bool {
        if case .int = v { return true }
        return false
    }

    static func isNumber(_ v: RJ) -> Bool {
        switch v {
        case .int, .num: return true
        default: return false
        }
    }

    static func int(_ v: RJ) -> Int? {
        if case .int(let i) = v { return i }
        return nil
    }

    /// Python `len(str)` = code points.
    static func length(_ s: String) -> Int { s.unicodeScalars.count }

    /// Python `str.strip()`.
    static func strip(_ s: String) -> String { s.trimmingCharacters(in: .whitespacesAndNewlines) }

    /// `'HH:mm'` → (hour, minute) or nil.
    static func parseHHMM(_ s: RJ) -> (Int, Int)? { parseHHMM(s.string) }

    static func parseHHMM(_ s: String?) -> (Int, Int)? {
        guard let s, let m = reHHMM.match(s), let h = m.g(1), let mi = m.g(2), let hour = Int(h), let minute = Int(mi) else { return nil }
        return (hour, minute)
    }

    /// `'yyyy-MM-dd'` → calendar-checked date or nil.
    static func parseDate(_ s: RJ) -> YMD? { parseDate(s.string) }

    static func parseDate(_ s: String?) -> YMD? {
        guard let s, let m = reDate.match(s), let y = m.g(1).flatMap({ Int($0) }), let mo = m.g(2).flatMap({ Int($0) }),
              let d = m.g(3).flatMap({ Int($0) }) else { return nil }
        return YMD.valid(y, mo, d)
    }

    static func fmtHHMM(_ h: Int, _ m: Int) -> String { String(format: "%02d:%02d", h, m) }

    /// JDN of 1970-01-01.
    private static let epochJDN = 2_440_588

    private static func floorDiv(_ a: Int64, _ b: Int64) -> Int64 {
        let q = a / b
        return (a % b != 0 && (a < 0) != (b < 0)) ? q - 1 : q
    }

    /// Epoch ms of the local wall-clock time on `date` in `zone` with `fold = 0` semantics: inside a
    /// spring-forward gap the pre-transition offset is applied (02:30 → 03:30 on the wall clock);
    /// inside a fall-back overlap the EARLIER (daylight) instant is used. Equals java.time
    /// `LocalDateTime.atZone(zone)` and Foundation `Calendar.date(from:)` with the zone set; computed
    /// from `TimeZone.secondsFromGMT(for:)` so the rule holds regardless of Foundation quirks.
    static func localInstant(_ date: YMD, _ hour: Int, _ minute: Int, zone: String) -> Int? {
        guard let tz = TimeZone(identifier: zone) else { return nil }
        let days = Int64(date.jdn - epochJDN)
        let wall = days * 86_400 + Int64(hour * 3_600 + minute * 60)
        func offset(_ t: Int64) -> Int64 { Int64(tz.secondsFromGMT(for: Date(timeIntervalSince1970: TimeInterval(t)))) }
        let offsetBefore = offset(wall - 86_400)
        let offsetAfter = offset(wall + 86_400)
        let candidateBefore = wall - offsetBefore
        let candidateAfter = wall - offsetAfter
        let beforeHolds = offset(candidateBefore) == offsetBefore
        let afterHolds = offset(candidateAfter) == offsetAfter
        let instant: Int64
        if beforeHolds && afterHolds {
            instant = min(candidateBefore, candidateAfter)   // overlap → earlier; normal day → equal
        } else if beforeHolds {
            instant = candidateBefore
        } else if afterHolds {
            instant = candidateAfter
        } else {
            instant = candidateBefore                        // gap → pre-transition offset
        }
        return Int(instant * 1_000)
    }

    static func localInstant(date: String, hhmm: String, zone: String) -> Int? {
        guard let d = parseDate(date), let hm = parseHHMM(hhmm) else { return nil }
        return localInstant(d, hm.0, hm.1, zone: zone)
    }

    /// (date, hour, minute) of an instant in `zone`.
    static func localComponents(_ ms: Int, zone: String) -> (YMD, Int, Int) {
        let tz = TimeZone(identifier: zone) ?? TimeZone(secondsFromGMT: 0)!
        let seconds = floorDiv(Int64(ms), 1_000)
        let local = seconds + Int64(tz.secondsFromGMT(for: Date(timeIntervalSince1970: TimeInterval(seconds))))
        let days = floorDiv(local, 86_400)
        let rem = Int(local - days * 86_400)
        return (YMD(jdn: Int(days) + epochJDN), rem / 3_600, (rem % 3_600) / 60)
    }

    /// `yyyy-MM-dd` of an instant in `zone`.
    static func localDateOf(_ ms: Int, zone: String) -> String { localComponents(ms, zone: zone).0.isoString }

    static func localHHMMOf(_ ms: Int, zone: String) -> String {
        let c = localComponents(ms, zone: zone)
        return fmtHHMM(c.1, c.2)
    }

    /// `[start_ms, end_ms)` of a local calendar day.
    static func dayWindow(_ date: String, zone: String) -> (Int, Int)? {
        guard let d = parseDate(date), let start = localInstant(d, 0, 0, zone: zone),
              let end = localInstant(d.days(1), 0, 0, zone: zone) else { return nil }
        return (start, end)
    }

    /// Sort key for medication names: lowercase, single spaces, trimmed.
    static func foldName(_ s: RJ) -> String { foldName(s.string) }

    static func foldName(_ s: String?) -> String {
        (s ?? "").lowercased().split(whereSeparator: { $0.isWhitespace }).joined(separator: " ")
    }

    static func sortedUniqueTimes(_ times: RJ) -> [String] {
        var out: [String] = []
        for t in times.array ?? [] {
            guard let s = t.string, parseHHMM(s) != nil, !out.contains(s) else { continue }
            out.append(s)
        }
        return out.sorted()
    }

    /// Effective 'HH:mm' slots of a schedule row (docs §6). Interval schedules derive theirs from
    /// anchor_time + k * interval_hours mod 24 h; an interval that does not divide 24 yields nothing.
    static func scheduleSlots(_ schedule: RJ) -> [String] {
        let kind = schedule["frequency_kind"].string
        if kind == "daily" || kind == "weekly" {
            return sortedUniqueTimes(schedule["times"])
        }
        if kind == "interval" {
            guard let n = int(schedule["interval_hours"]), intervalHours.contains(n), let anchor = parseHHMM(schedule["anchor_time"]) else { return [] }
            var slots = Set<String>()
            for k in 0..<(24 / n) {
                var total = (anchor.0 + k * n) * 60 + anchor.1
                total %= 24 * 60
                slots.insert(fmtHHMM(total / 60, total % 60))
            }
            return slots.sorted()
        }
        return []
    }

    struct LogKey: Hashable {
        let scheduleID: String?
        let scheduledAtMs: Int
    }

    static func logKey(_ scheduleID: RJ, _ scheduledAt: RJ) -> LogKey {
        LogKey(scheduleID: scheduleID.string, scheduledAtMs: int(scheduledAt) ?? Int.min)
    }

    /// `{(schedule_id, scheduled_at_ms): index}` for logs of scheduled doses (first wins).
    static func indexLogs(_ logs: [RJ]) -> [LogKey: Int] {
        var out: [LogKey: Int] = [:]
        for (i, log) in logs.enumerated() {
            guard let scheduleID = log["schedule_id"].string else { continue }
            let key = LogKey(scheduleID: scheduleID, scheduledAtMs: int(log["scheduled_at_ms"]) ?? Int.min)
            if out[key] == nil { out[key] = i }
        }
        return out
    }

    /// `_row(columns, src, **overrides)`: every column (missing → null) plus overrides.
    static func row(_ columns: [String], _ src: RJ, _ overrides: [String: RJ] = [:]) -> [String: RJ] {
        var out: [String: RJ] = [:]
        for c in columns { out[c] = src[c] }
        for (k, v) in overrides { out[k] = v }
        return out
    }

    /// Python tuple comparison for (Int, String, …) sort keys.
    static func less(_ a: [RJ], _ b: [RJ]) -> Bool {
        for (x, y) in zip(a, b) {
            if RJ.same(x, y) { continue }
            switch (x, y) {
            case (.str(let s), .str(let t)): return s < t
            default:
                if let dx = x.double, let dy = y.double { return dx < dy }
                return false
            }
        }
        return a.count < b.count
    }

    // MARK: §6 Occurrences

    /// Occurrences of one schedule row inside `[window_start_ms, window_end_ms)`.
    static func expandOccurrences(schedule: RJ, medication: RJ, windowStart: RJ, windowEnd: RJ, zone: String) -> [RJ] {
        if medication["is_prn"].truthy { return [] }
        guard let ws = int(windowStart), let we = int(windowEnd), we > ws else { return [] }
        let slots = scheduleSlots(schedule)
        if slots.isEmpty { return [] }
        guard let startDate = parseDate(medication["start_date"]) else { return [] }
        let endRaw = medication["end_date"]
        let endDate: YMD? = endRaw.truthy ? parseDate(endRaw) : nil
        let days = schedule["days"].array ?? []
        let kind = schedule["frequency_kind"].string
        let activeFrom = schedule["active_from_ms"]
        let activeUntil = schedule["active_until_ms"]
        let first = localComponents(ws, zone: zone).0
        let last = localComponents(we, zone: zone).0
        var out: [RJ] = []
        var seen = Set<Int>()
        var d = first
        while !(last < d) {
            if !(d < startDate), endDate == nil || !(endDate! < d) {
                let iso = Double(d.weekday + 1)
                if kind != "weekly" || days.contains(where: { $0.double == iso }) {
                    let dateStr = d.isoString
                    for slot in slots {
                        guard let hm = parseHHMM(slot), let t = localInstant(d, hm.0, hm.1, zone: zone) else { continue }
                        if let from = int(activeFrom), t < from { continue }
                        if let until = int(activeUntil), t >= until { continue }
                        if t < ws || t >= we { continue }
                        if seen.contains(t) { continue }
                        seen.insert(t)
                        out.append(.obj(["medication_id": medication["id"], "schedule_id": schedule["id"],
                                         "scheduled_at_ms": .int(t), "local_date": .str(dateStr), "slot": .str(slot)]))
                    }
                }
            }
            d = d.days(1)
        }
        return out.stableSorted { less([$0["scheduled_at_ms"], $0["slot"]], [$1["scheduled_at_ms"], $1["slot"]]) }
    }

    /// Occurrences of every schedule row whose medication has one of `statuses` (nil = any),
    /// sorted by (scheduled_at_ms, folded name, medication_id, schedule_id, slot).
    static func expandAll(medications: [RJ], schedules: [RJ], windowStart: Int, windowEnd: Int, zone: String, statuses: Set<String>?) -> [RJ] {
        let byID = medicationsByID(medications)
        var out: [RJ] = []
        for s in schedules {
            guard let medID = s["medication_id"].string, let m = byID[medID] else { continue }
            if let statuses, !statuses.contains(m["status"].string ?? "") { continue }
            out.append(contentsOf: expandOccurrences(schedule: s, medication: m, windowStart: .int(windowStart), windowEnd: .int(windowEnd), zone: zone))
        }
        return out.stableSorted { a, b in
            less([a["scheduled_at_ms"], .str(foldName(byID[a["medication_id"].string ?? ""]?["name"] ?? .null)), a["medication_id"], a["schedule_id"], a["slot"]],
                 [b["scheduled_at_ms"], .str(foldName(byID[b["medication_id"].string ?? ""]?["name"] ?? .null)), b["medication_id"], b["schedule_id"], b["slot"]])
        }
    }

    static func medicationsByID(_ medications: [RJ]) -> [String: RJ] {
        var byID: [String: RJ] = [:]
        for m in medications {
            if let id = m["id"].string { byID[id] = m }
        }
        return byID
    }

    // MARK: §7 Dose status

    /// Derived status of one occurrence given its stored log (or `.null`) at `now`.
    /// Stored terminal statuses always win (a clock rollback cannot un-miss or un-take a dose).
    static func resolveDoseStatus(occurrence: RJ, log: RJ, now: Int) -> RJ {
        let scheduledAt = int(occurrence["scheduled_at_ms"]) ?? 0
        if !log.isNull, let stored = log["status"].string, terminalDoseStatuses.contains(stored) {
            let snoozed = log["snoozed_until_ms"]
            let deadline = max(scheduledAt, int(snoozed) ?? 0) + graceMs
            var isLate = false
            if stored == "taken", let takenAt = int(log["taken_at_ms"]) {
                isLate = takenAt > scheduledAt + graceMs
            }
            return .obj(["status": .str(stored), "deadline_ms": .int(deadline), "is_late": .bool(isLate)])
        }
        if !log.isNull, log["status"].string == "snoozed" {
            let until = int(log["snoozed_until_ms"]) ?? scheduledAt
            let deadline = max(scheduledAt, until) + graceMs
            let status = now < until ? "snoozed" : (now < deadline ? "due" : "missed")
            return .obj(["status": .str(status), "deadline_ms": .int(deadline), "is_late": .bool(false)])
        }
        let deadline = scheduledAt + graceMs
        let status = now < scheduledAt ? "scheduled" : (now < deadline ? "due" : "missed")
        return .obj(["status": .str(status), "deadline_ms": .int(deadline), "is_late": .bool(false)])
    }

    /// Rows to write so that every occurrence that resolves to `missed` has a stored `missed` log.
    /// Never produces a `taken` row.
    static func materializeMissed(occurrences: [RJ], logs: [RJ], medicationByID: [String: RJ], now: Int) -> RJ {
        let ids = Ids()
        let index = indexLogs(logs)
        var ops: [RJ] = []
        for occ in occurrences {
            let log = index[logKey(occ["schedule_id"], occ["scheduled_at_ms"])].map { logs[$0] } ?? .null
            if resolveDoseStatus(occurrence: occ, log: log, now: now)["status"].string != "missed" { continue }
            if log.isNull {
                guard let med = occ["medication_id"].string.flatMap({ medicationByID[$0] }) else { continue }
                ops.append(.obj(["op": .str("insert"), "row": .obj([
                    "id": .str(ids.next()), "medication_id": occ["medication_id"], "schedule_id": occ["schedule_id"],
                    "scheduled_at_ms": occ["scheduled_at_ms"], "status": .str("missed"), "taken_at_ms": .null,
                    "snoozed_until_ms": .null, "dose_quantity": med["dose_quantity"], "dose_unit": med["dose_unit"],
                    "note": .null, "created_ms": .int(now), "updated_ms": .int(now),
                ])]))
            } else if log["status"].string == "snoozed" {
                ops.append(.obj(["op": .str("update"), "row": .obj(row(doseLogColumns, log, ["status": .str("missed"), "updated_ms": .int(now)]))]))
            }
        }
        return .obj(["ops": .arr(ops)])
    }
}
