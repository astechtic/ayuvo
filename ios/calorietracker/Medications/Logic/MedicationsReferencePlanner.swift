import Foundation

// §10 Reminder planning (port of the reference; see MedicationsReferenceCore.swift).

/// One planned reminder (typed form of a `plan_reminders` entry).
nonisolated struct PlannedReminder: Hashable, Sendable {
    var medicationID: String
    var scheduleID: String?
    var scheduledAtMs: Int64
    var fireAtMs: Int64
    var isSnooze: Bool

    /// Reminder identity string shared by both platforms (docs §10).
    var identity: String { "\(medicationID):\(scheduledAtMs)" }
}

nonisolated struct ReminderPlan: Hashable, Sendable {
    var entries: [PlannedReminder]
    var nextFireMs: Int64?
    var truncated: Bool

    static let empty = ReminderPlan(entries: [], nextFireMs: nil, truncated: false)
}

nonisolated extension MR {
    /// Reminder entries ascending by fire time, trimmed to `budget` (null = unlimited).
    static func planReminders(medications: [RJ], schedules: [RJ], logs: [RJ], now: Int, horizon: RJ, zone: String, budget: RJ) -> RJ {
        let byID = medicationsByID(medications)
        var active: [String: RJ] = [:]
        var activeOrder: [String] = []
        for m in medications {
            guard let id = m["id"].string, byID[id]?["status"].string == "active" else { continue }
            if active[id] == nil { activeOrder.append(id) }
            active[id] = byID[id]
        }
        let activeList = activeOrder.compactMap { active[$0] }
        let openRows = schedules.filter {
            $0["active_until_ms"].isNull && $0["reminder_enabled"].truthy && active[$0["medication_id"].string ?? ""] != nil
        }
        let index = indexLogs(logs)
        var entries: [RJ] = []
        if let horizonMs = int(horizon), horizonMs > 0 {
            let occurrences = expandAll(medications: activeList, schedules: openRows, windowStart: now - lateFireMs,
                                        windowEnd: now + horizonMs, zone: zone, statuses: ["active"])
            for occ in occurrences {
                if index[logKey(occ["schedule_id"], occ["scheduled_at_ms"])] != nil { continue }
                let t = int(occ["scheduled_at_ms"]) ?? 0
                entries.append(.obj(["medication_id": occ["medication_id"], "schedule_id": occ["schedule_id"],
                                     "scheduled_at_ms": occ["scheduled_at_ms"], "fire_at_ms": .int(max(t, now)),
                                     "kind": .str("scheduled")]))
            }
        }
        for log in logs {
            guard log["status"].string == "snoozed", active[log["medication_id"].string ?? ""] != nil else { continue }
            guard let until = int(log["snoozed_until_ms"]), until >= now - lateFireMs else { continue }
            entries.append(.obj(["medication_id": log["medication_id"], "schedule_id": log["schedule_id"],
                                 "scheduled_at_ms": log["scheduled_at_ms"], "fire_at_ms": .int(max(until, now)),
                                 "kind": .str("snooze")]))
        }
        func key(_ e: RJ) -> [RJ] {
            [e["fire_at_ms"], .str(foldName(byID[e["medication_id"].string ?? ""]?["name"] ?? .null)), e["medication_id"],
             e["scheduled_at_ms"], .str(e["schedule_id"].string ?? "")]
        }
        entries = entries.stableSorted { less(key($0), key($1)) }
        var truncated = false
        if let b = int(budget), b >= 0, entries.count > b {
            entries = Array(entries.prefix(b))
            truncated = true
        }
        return .obj(["entries": .arr(entries), "next_fire_ms": entries.first?["fire_at_ms"] ?? .null, "truncated": .bool(truncated)])
    }

    /// Typed planning for the store / scheduler.
    static func planReminders(medications: [Medication], schedules: [MedicationSchedule], logs: [DoseLog],
                              nowMs: Int64, horizonMs: Int64, zone: String, budget: Int?) -> ReminderPlan {
        let result = planReminders(medications: medications.map(\.rj), schedules: schedules.map(\.rj), logs: logs.map(\.rj),
                                   now: Int(nowMs), horizon: .int(Int(horizonMs)), zone: zone, budget: budget.map(RJ.int) ?? .null)
        let entries = (result["entries"].array ?? []).compactMap { e -> PlannedReminder? in
            guard let medicationID = e["medication_id"].string, let scheduledAt = int(e["scheduled_at_ms"]), let fireAt = int(e["fire_at_ms"]) else { return nil }
            return PlannedReminder(medicationID: medicationID, scheduleID: e["schedule_id"].string, scheduledAtMs: Int64(scheduledAt),
                                   fireAtMs: Int64(fireAt), isSnooze: e["kind"].string == "snooze")
        }
        return ReminderPlan(entries: entries, nextFireMs: int(result["next_fire_ms"]).map(Int64.init), truncated: result["truncated"].bool ?? false)
    }
}
