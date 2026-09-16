import Foundation

// §8 Today timeline and §9 adherence (port of the reference; see MedicationsReferenceCore.swift).

nonisolated extension MR {
    private static func itemFromLog(_ log: RJ, scheduledAt: Int, kind: String, now: Int) -> RJ {
        let r = resolveDoseStatus(occurrence: .obj(["scheduled_at_ms": .int(scheduledAt)]), log: log, now: now)
        return .obj([
            "medication_id": log["medication_id"], "schedule_id": log["schedule_id"],
            "scheduled_at_ms": .int(scheduledAt), "status": r["status"], "is_late": r["is_late"],
            "log_id": log["id"], "snoozed_until_ms": log["snoozed_until_ms"],
            "dose_quantity": log["dose_quantity"], "dose_unit": log["dose_unit"], "kind": .str(kind),
        ])
    }

    /// The Today screen model: the local day of `now`.
    static func todayTimeline(medications: [RJ], schedules: [RJ], logs: [RJ], now: Int, zone: String) -> RJ {
        let byID = medicationsByID(medications)
        let date = localDateOf(now, zone: zone)
        guard let (start, end) = dayWindow(date, zone: zone) else {
            return .obj(["date": .str(date), "summary": .obj([:]), "groups": .arr([]), "prn": .arr([])])
        }
        let occurrences = expandAll(medications: medications, schedules: schedules, windowStart: start, windowEnd: end, zone: zone, statuses: ["active"])
        let index = indexLogs(logs)
        var items: [RJ] = []
        var matched = Set<Int>()
        for occ in occurrences {
            let med = byID[occ["medication_id"].string ?? ""] ?? .null
            let logIndex = index[logKey(occ["schedule_id"], occ["scheduled_at_ms"])]
            let log = logIndex.map { logs[$0] } ?? .null
            let r = resolveDoseStatus(occurrence: occ, log: log, now: now)
            if let logIndex { matched.insert(logIndex) }
            items.append(.obj([
                "medication_id": occ["medication_id"], "schedule_id": occ["schedule_id"],
                "scheduled_at_ms": occ["scheduled_at_ms"], "status": r["status"], "is_late": r["is_late"],
                "log_id": log.isNull ? .null : log["id"],
                "snoozed_until_ms": log.isNull ? .null : log["snoozed_until_ms"],
                "dose_quantity": log.isNull ? med["dose_quantity"] : log["dose_quantity"],
                "dose_unit": log.isNull ? med["dose_unit"] : log["dose_unit"],
                "kind": .str("scheduled"),
            ]))
        }
        for (i, log) in logs.enumerated() {
            if matched.contains(i) { continue }
            guard let t = int(log["scheduled_at_ms"]), t >= start, t < end else { continue }
            guard let medID = log["medication_id"].string, byID[medID] != nil else { continue }
            guard storedDoseStatuses.contains(log["status"].string ?? "") else { continue }
            let kind = log["schedule_id"].isNull ? "prn" : "scheduled"
            items.append(itemFromLog(log, scheduledAt: t, kind: kind, now: now))
        }
        var groups: [String: [RJ]] = [:]
        for it in items {
            let slot = localHHMMOf(int(it["scheduled_at_ms"]) ?? 0, zone: zone)
            groups[slot, default: []].append(it)
        }
        func key(_ it: RJ) -> [RJ] {
            [it["scheduled_at_ms"], .str(foldName(byID[it["medication_id"].string ?? ""]?["name"] ?? .null)),
             it["medication_id"], .str(it["schedule_id"].string ?? ""), .str(it["log_id"].string ?? "")]
        }
        var ordered: [RJ] = []
        for slot in groups.keys.sorted() {
            let list = groups[slot]!.stableSorted { less(key($0), key($1)) }
            ordered.append(.obj(["slot": .str(slot), "items": .arr(list)]))
        }
        let scheduledItems = items.filter { $0["kind"].string == "scheduled" }
        var summary: [String: RJ] = ["total": .int(scheduledItems.count)]
        for (name, status) in [("taken", "taken"), ("upcoming", "scheduled"), ("due", "due"), ("snoozed", "snoozed"),
                               ("missed", "missed"), ("skipped", "skipped")] {
            summary[name] = .int(scheduledItems.filter { $0["status"].string == status }.count)
        }
        var prn: [RJ] = []
        let prnMeds = medications
            .filter { $0["is_prn"].truthy && $0["status"].string == "active" }
            .stableSorted { less([.str(foldName($0["name"])), $0["id"]], [.str(foldName($1["name"])), $1["id"]]) }
        for m in prnMeds {
            var count = 0
            var last: Int?
            for log in logs {
                if log["medication_id"].string != m["id"].string || !log["schedule_id"].isNull { continue }
                if log["status"].string != "taken" { continue }
                if let t = int(log["scheduled_at_ms"]), t >= start, t < end { count += 1 }
                if let takenAt = int(log["taken_at_ms"]), last == nil || takenAt > last! { last = takenAt }
            }
            prn.append(.obj(["medication_id": m["id"], "today_count": .int(count), "last_taken_ms": last.map(RJ.int) ?? .null]))
        }
        return .obj(["date": .str(date), "summary": .obj(summary), "groups": .arr(ordered), "prn": .arr(prn)])
    }

    // MARK: §9 Adherence

    /// taken / expected over the occurrences and logs passed (already restricted to the window).
    static func adherence(occurrences: [RJ], logs: [RJ], now: Int, medicationID: String?) -> RJ {
        let index = indexLogs(logs)
        var expected = 0
        var taken = 0
        var matched = Set<Int>()
        for occ in occurrences {
            if let medicationID, occ["medication_id"].string != medicationID { continue }
            let logIndex = index[logKey(occ["schedule_id"], occ["scheduled_at_ms"])]
            let log = logIndex.map { logs[$0] } ?? .null
            let status = resolveDoseStatus(occurrence: occ, log: log, now: now)["status"].string ?? ""
            if let logIndex { matched.insert(logIndex) }
            if terminalDoseStatuses.contains(status) {
                expected += 1
                if status == "taken" { taken += 1 }
            }
        }
        for (i, log) in logs.enumerated() {
            if matched.contains(i) || log["schedule_id"].isNull { continue }
            if let medicationID, log["medication_id"].string != medicationID { continue }
            let status = log["status"].string ?? ""
            if terminalDoseStatuses.contains(status) {
                expected += 1
                if status == "taken" { taken += 1 }
            }
        }
        let percent = expected > 0 ? Int((Double(taken) * 100.0 / Double(expected) + 0.5).rounded(.down)) : 0
        return .obj(["taken": .int(taken), "expected": .int(expected), "percent": .int(percent), "has_data": .bool(expected > 0)])
    }
}
