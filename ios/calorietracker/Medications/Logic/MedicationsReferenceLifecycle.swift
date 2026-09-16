import Foundation

// §11 Dose actions and PRN logging, §12 lifecycle and auto-completion, §15 draft validation
// (port of the reference; see MedicationsReferenceCore.swift).

nonisolated extension MR {
    // MARK: §11 Dose actions

    private static func fail(_ error: String) -> RJ {
        .obj(["ok": .bool(false), "error": .str(error), "row": .null, "op": .null])
    }

    /// The user's explicit action on one scheduled occurrence. Returns `{ok, error, row, op}`.
    /// `taken` is the ONLY way (with `logPRNDose`) a `taken` row comes into existence.
    static func applyDoseAction(action: String, occurrence: RJ, existingLog: RJ, medication: RJ, now: Int,
                                snoozeMinutes: RJ, takenAtMs: RJ, note: RJ) -> RJ {
        let ids = Ids()
        guard ["taken", "skipped", "snoozed", "undo"].contains(action) else { return fail("bad_action") }
        if !note.isNull {
            guard let text = note.string, length(text) <= noteMax else { return fail("note_too_long") }
        }
        let stored = existingLog.isNull ? nil : existingLog["status"].string
        let derived = resolveDoseStatus(occurrence: occurrence, log: existingLog, now: now)["status"].string ?? ""

        func baseRow(_ over: [String: RJ]) -> RJ {
            var r: [String: RJ]
            if !existingLog.isNull {
                r = row(doseLogColumns, existingLog, ["updated_ms": .int(now)])
                if !note.isNull { r["note"] = note }
            } else {
                r = ["id": .str(ids.next()), "medication_id": occurrence["medication_id"],
                     "schedule_id": occurrence["schedule_id"], "scheduled_at_ms": occurrence["scheduled_at_ms"],
                     "status": .null, "taken_at_ms": .null, "snoozed_until_ms": .null,
                     "dose_quantity": medication["dose_quantity"], "dose_unit": medication["dose_unit"],
                     "note": note, "created_ms": .int(now), "updated_ms": .int(now)]
            }
            for (k, v) in over { r[k] = v }
            return .obj(r)
        }

        let op = existingLog.isNull ? "insert" : "update"
        switch action {
        case "taken":
            if stored == "taken" { return fail("already_taken") }
            let takenAt = int(takenAtMs) ?? now
            if takenAt > now { return fail("taken_at_in_future") }
            return .obj(["ok": .bool(true), "error": .null, "row": baseRow(["status": .str("taken"), "taken_at_ms": .int(takenAt)]), "op": .str(op)])
        case "skipped":
            if stored == "taken" { return fail("already_taken") }
            if stored == "skipped" { return fail("already_resolved") }
            return .obj(["ok": .bool(true), "error": .null, "row": baseRow(["status": .str("skipped"), "taken_at_ms": .null]), "op": .str(op)])
        case "snoozed":
            guard let minutes = snoozeMinutes.double, MR.snoozeMinutes.contains(where: { Double($0) == minutes }) else { return fail("bad_snooze") }
            if derived == "scheduled" { return fail("not_due_yet") }
            if derived == "missed" { return fail("dose_missed") }
            if derived == "taken" || derived == "skipped" { return fail("already_resolved") }
            let until = now + Int(minutes) * 60_000
            return .obj(["ok": .bool(true), "error": .null, "row": baseRow(["status": .str("snoozed"), "snoozed_until_ms": .int(until)]), "op": .str(op)])
        default: // undo
            if existingLog.isNull { return fail("nothing_to_undo") }
            if stored == "missed" { return fail("cannot_undo_missed") }
            return .obj(["ok": .bool(true), "error": .null, "row": .obj(row(doseLogColumns, existingLog)), "op": .str("delete")])
        }
    }

    /// An as-needed dose: a `taken` row with `schedule_id` NULL and `scheduled_at_ms = taken_at_ms`.
    static func logPRNDose(medication: RJ, now: Int, takenAtMs: RJ, doseQuantity: RJ, note: RJ) -> RJ {
        let ids = Ids()
        func fail(_ error: String) -> RJ { .obj(["ok": .bool(false), "error": .str(error), "row": .null]) }
        if !medication["is_prn"].truthy { return fail("not_prn") }
        if medication["status"].string != "active" { return fail("not_active") }
        let takenAt = int(takenAtMs) ?? now
        if takenAt > now { return fail("taken_at_in_future") }
        if !doseQuantity.isNull {
            guard let q = doseQuantity.double, isNumber(doseQuantity), q > 0, q <= doseQuantityMax else { return fail("dose_quantity_invalid") }
        }
        if !note.isNull {
            guard let text = note.string, length(text) <= noteMax else { return fail("note_too_long") }
        }
        let row: [String: RJ] = [
            "id": .str(ids.next()), "medication_id": medication["id"], "schedule_id": .null, "scheduled_at_ms": .int(takenAt),
            "status": .str("taken"), "taken_at_ms": .int(takenAt), "snoozed_until_ms": .null,
            "dose_quantity": doseQuantity.isNull ? medication["dose_quantity"] : doseQuantity,
            "dose_unit": medication["dose_unit"], "note": note, "created_ms": .int(now), "updated_ms": .int(now),
        ]
        return .obj(["ok": .bool(true), "error": .null, "row": .obj(row)])
    }

    // MARK: §12 Lifecycle

    /// Schedule-row validation shared by `validateDraft` and `lifecycle`. Returns `[{field, code}]`.
    static func validateSchedule(_ s: RJ, prefix: String = "") -> [RJ] {
        func err(_ field: String, _ code: String) -> RJ { .obj(["field": .str(prefix + field), "code": .str(code)]) }
        var errs: [RJ] = []
        let kind = s["frequency_kind"].string ?? ""
        let times = s["times"]
        let days = s["days"]
        guard frequencyKinds.contains(kind) else {
            errs.append(err("frequency_kind", "frequency_required"))
            return errs
        }
        if kind == "daily" || kind == "weekly" {
            if let list = times.array, !list.isEmpty {
                let strings = list.map { $0.string }
                let ok = strings.allSatisfy { parseHHMM($0) != nil }
                let values = strings.compactMap { $0 }
                if !ok || list.count > maxTimes || Set(values).count != list.count || values.sorted() != values {
                    errs.append(err("times", "times_invalid"))
                }
            } else {
                errs.append(err("times", "times_required"))
            }
        }
        if kind == "weekly" {
            if let list = days.array, !list.isEmpty {
                let ints = list.map { int($0) }
                let allValid = ints.allSatisfy { $0 != nil && (1...7).contains($0!) }
                let values = ints.compactMap { $0 }
                if !allValid || Set(values).count != list.count || values.sorted() != values {
                    errs.append(err("days", "days_invalid"))
                }
            } else {
                errs.append(err("days", "days_required"))
            }
        } else if days.truthy {
            errs.append(err("days", "days_not_allowed"))
        }
        if kind == "interval" {
            let validInterval = int(s["interval_hours"]).map { intervalHours.contains($0) } ?? false
            if !validInterval { errs.append(err("interval_hours", "interval_invalid")) }
            let anchor = s["anchor_time"]
            if anchor.isNull || anchor.string == "" {
                errs.append(err("anchor_time", "anchor_required"))
            } else if parseHHMM(anchor) == nil {
                errs.append(err("anchor_time", "anchor_invalid"))
            }
        }
        return errs
    }

    /// Status transitions and schedule versioning. Returns `{ok, error, medication, schedules, ops}`.
    static func lifecycle(action: String, medication: RJ, schedules: [RJ], now: Int, newSchedule: RJ) -> RJ {
        let ids = Ids()
        var rows: [[String: RJ]] = schedules
            .filter { RJ.same($0["medication_id"], medication["id"]) }
            .map { row(scheduleColumns, $0) }
        var med = row(medicationColumns, medication)
        let status = med["status"]?.string
        let openIndexes = rows.indices.filter { rows[$0]["active_until_ms"]?.isNull ?? true }

        func fail(_ error: String) -> RJ {
            .obj(["ok": .bool(false), "error": .str(error), "medication": .obj(med), "schedules": .arr(rows.map { .obj($0) }), "ops": .arr([])])
        }
        if openIndexes.count > 1 { return fail("multiple_open_schedules") }
        var ops: [RJ] = []

        func closeOpen() {
            for i in openIndexes {
                rows[i]["active_until_ms"] = .int(now)
                rows[i]["updated_ms"] = .int(now)
                ops.append(.obj(["op": .str("close_schedule"), "id": rows[i]["id"] ?? .null]))
            }
        }
        func setStatus(_ new: String) {
            med["status"] = .str(new)
            med["updated_ms"] = .int(now)
            ops.append(.obj(["op": .str("set_status"), "status": .str(new)]))
        }

        switch action {
        case "pause":
            if status != "active" { return fail("invalid_transition") }
            closeOpen()
            setStatus("paused")
        case "resume":
            if status != "paused" { return fail("invalid_transition") }
            let closed = rows.filter { isInt($0["active_until_ms"] ?? .null) }
            if !closed.isEmpty {
                let latest = closed.map { int($0["active_until_ms"] ?? .null)! }.max()!
                for r in closed where int(r["active_until_ms"] ?? .null) == latest {
                    let copy = row(scheduleColumns, .obj(r), ["id": .str(ids.next()), "active_from_ms": .int(now), "active_until_ms": .null,
                                                              "created_ms": .int(now), "updated_ms": .int(now)])
                    rows.append(copy)
                    ops.append(.obj(["op": .str("insert_schedule"), "id": copy["id"] ?? .null]))
                }
            }
            setStatus("active")
        case "stop", "complete":
            if status != "active" && status != "paused" { return fail("invalid_transition") }
            closeOpen()
            setStatus(action == "stop" ? "stopped" : "completed")
        case "edit_schedule":
            if status != "active" { return fail("invalid_transition") }
            if (med["is_prn"] ?? .null).truthy { return fail("prn_has_schedule") }
            guard let ns = newSchedule.object else { return fail("schedule_required") }
            let errs = validateSchedule(newSchedule)
            if !errs.isEmpty {
                guard case .obj(var out) = fail("invalid_schedule") else { return fail("invalid_schedule") }
                out["errors"] = .arr(errs)
                return .obj(out)
            }
            closeOpen()
            let kind = ns["frequency_kind"]?.string ?? ""
            let reminderEnabled: Bool = ns["reminder_enabled"].map(\.truthy) ?? true
            let newRow: [String: RJ] = [
                "id": .str(ids.next()), "medication_id": med["id"] ?? .null, "frequency_kind": .str(kind),
                "times": kind != "interval" ? .arr((ns["times"] ?? .null).array ?? []) : .arr([]),
                "days": kind == "weekly" ? .arr((ns["days"] ?? .null).array ?? []) : .arr([]),
                "interval_hours": kind == "interval" ? (ns["interval_hours"] ?? .null) : .null,
                "anchor_time": kind == "interval" ? (ns["anchor_time"] ?? .null) : .null,
                "reminder_enabled": .int(reminderEnabled ? 1 : 0),
                "active_from_ms": .int(now), "active_until_ms": .null, "created_ms": .int(now), "updated_ms": .int(now),
            ]
            rows.append(newRow)
            ops.append(.obj(["op": .str("insert_schedule"), "id": newRow["id"] ?? .null]))
            med["updated_ms"] = .int(now)
        case "set_reminder_enabled":
            guard let ns = newSchedule.object, let enabled = ns["reminder_enabled"] else { return fail("schedule_required") }
            guard let i = openIndexes.first else { return fail("no_open_schedule") }
            rows[i]["reminder_enabled"] = .int(enabled.truthy ? 1 : 0)
            rows[i]["updated_ms"] = .int(now)
            ops.append(.obj(["op": .str("update_schedule"), "id": rows[i]["id"] ?? .null]))
        default:
            return fail("bad_action")
        }
        rows = rows.stableSorted { a, b in
            less([.int(int(a["active_from_ms"] ?? .null) ?? 0), a["id"] ?? .null], [.int(int(b["active_from_ms"] ?? .null) ?? 0), b["id"] ?? .null])
        }
        return .obj(["ok": .bool(true), "error": .null, "medication": .obj(med), "schedules": .arr(rows.map { .obj($0) }), "ops": .arr(ops)])
    }

    /// Ids of active/paused medications whose `end_date` is before today.
    static func autoComplete(medications: [RJ], today: RJ) -> [RJ] {
        let todayDate = parseDate(today)
        var out: [RJ] = []
        for m in medications {
            guard let status = m["status"].string, status == "active" || status == "paused" else { continue }
            let endRaw = m["end_date"]
            let end: YMD? = endRaw.truthy ? parseDate(endRaw) : nil
            if let end, let todayDate, end < todayDate { out.append(m["id"]) }
        }
        return out
    }

    // MARK: §15 Draft validation

    /// Errors of an Add/Edit form draft as `[{field, code}]` in field order; `[]` = valid.
    static func validateDraft(_ draft: RJ) -> [RJ] {
        func err(_ field: String, _ code: String) -> RJ { .obj(["field": .str(field), "code": .str(code)]) }
        var errs: [RJ] = []
        let d = draft
        let validName = d["name"].string.map { !strip($0).isEmpty && length(strip($0)) <= nameMax } ?? false
        if !validName { errs.append(err("name", "name_required")) }
        let strength = d["strength"]
        if !strength.isNull, strength.string == nil || length(strength.string!) > strengthMax {
            errs.append(err("strength", "strength_too_long"))
        }
        if !forms.contains(d["form"].string ?? "") { errs.append(err("form", "form_invalid")) }
        let q = d["dose_quantity"]
        if !isNumber(q) || q.double! <= 0 || q.double! > doseQuantityMax { errs.append(err("dose_quantity", "dose_quantity_invalid")) }
        if !doseUnits.contains(d["dose_unit"].string ?? "") { errs.append(err("dose_unit", "dose_unit_invalid")) }
        if !foodRelations.contains(d["food_relation"].string ?? "") { errs.append(err("food_relation", "food_relation_invalid")) }
        let start = parseDate(d["start_date"])
        if start == nil { errs.append(err("start_date", "start_date_invalid")) }
        if !d["end_date"].isNull {
            if let end = parseDate(d["end_date"]) {
                if let start, end < start { errs.append(err("end_date", "end_date_before_start")) }
            } else {
                errs.append(err("end_date", "end_date_invalid"))
            }
        }
        let isPRN = d["is_prn"].truthy
        let hasSchedule = !d["frequency_kind"].isNull || d["times"].truthy || d["days"].truthy || !d["interval_hours"].isNull
        if isPRN {
            if hasSchedule { errs.append(err("frequency_kind", "prn_has_schedule")) }
        } else {
            errs.append(contentsOf: validateSchedule(d))
        }
        let instructions = d["instructions"]
        if !instructions.isNull, instructions.string == nil || length(instructions.string!) > instructionsMax {
            errs.append(err("instructions", "instructions_too_long"))
        }
        let note = d["note"]
        if !note.isNull, note.string == nil || length(note.string!) > noteMax {
            errs.append(err("note", "note_too_long"))
        }
        return errs
    }
}
