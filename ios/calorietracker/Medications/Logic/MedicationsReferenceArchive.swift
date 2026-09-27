import Foundation

// §14 Archive `ayuvo-medications` v1 plus the §21 supplement nutrients (port of the reference; see
// MedicationsReferenceCore.swift).

nonisolated extension MR {
    // MARK: §21 Supplement nutrients (schema v2)

    static let nutrientSkipReasons = ["unknown_nutrient", "invalid_amount", "duplicate_nutrient"]

    /// nil when (key, amount_per_unit) may be stored, else `unknown_nutrient` | `invalid_amount`. The key must be
    /// a nutrient_reference.json key or a sports supplement key; the amount a finite number with
    /// 0 < amount <= amount_per_unit_max[canonical unit].
    static func nutrientProblem(key: RJ, amount: RJ) -> String? {
        guard let key = key.string, let unit = NutrientsReference.nutrientUnit(key) else { return "unknown_nutrient" }
        guard isNumber(amount), let value = amount.double, value.isFinite, value > 0,
              value <= (NutrientsReference.data.amountPerUnitMax[unit] ?? 0) else { return "invalid_amount" }
        return nil
    }

    /// {medication_id: [{key, amount_per_unit}] sorted by key} from snapshot["medication_nutrients"] rows.
    static func snapshotNutrients(_ snapshot: RJ) -> [String: [RJ]] {
        var out: [String: [RJ]] = [:]
        for r in snapshot["medication_nutrients"].array ?? [] {
            out[r["medication_id"].string ?? "", default: []].append(.obj(["key": r["nutrient_key"], "amount_per_unit": r["amount_per_unit"]]))
        }
        for (id, rows) in out {
            out[id] = rows.stableSorted { ($0["key"].string ?? "") < ($1["key"].string ?? "") }
        }
        return out
    }

    /// (nutrients | nil, skip ops) for one archive medication. nil = no "nutrients" list (an older export): the
    /// platform keeps the local rows. Otherwise the valid rows sorted by key and one skip op per bad row.
    static func archiveNutrients(_ m: RJ) -> (nutrients: [RJ]?, skips: [RJ]) {
        guard let raw = m["nutrients"].array else { return (nil, []) }
        var good: [RJ] = []
        var skips: [RJ] = []
        var seen = Set<String>()
        for item in raw {
            let key: RJ = item.object != nil ? item["key"] : .null
            let amount: RJ = item.object != nil ? item["amount_per_unit"] : .null
            var reason = nutrientProblem(key: key, amount: amount)
            if reason == nil, let k = key.string, seen.contains(k) { reason = "duplicate_nutrient" }
            if let reason {
                skips.append(.obj(["table": .str("medication_nutrients"), "op": .str("skip"), "id": m["id"],
                                   "nutrient_key": key.string.map(RJ.str) ?? .null, "reason": .str(reason)]))
                continue
            }
            seen.insert(key.string ?? "")
            good.append(.obj(["key": key, "amount_per_unit": amount]))
        }
        return (good.stableSorted { ($0["key"].string ?? "") < ($1["key"].string ?? "") }, skips)
    }

    /// The portable archive: every row of the three tables, arrays ordered by id, `photo_path` null. Each
    /// medication also carries "nutrients": [{key, amount_per_unit}] sorted by key ([] when it has none).
    static func exportArchive(snapshot: RJ, exportedMs: RJ, timeZone: RJ, platform: RJ, appVersion: RJ) -> RJ {
        func byID(_ a: [String: RJ], _ b: [String: RJ]) -> Bool { (a["id"]?.string ?? "") < (b["id"]?.string ?? "") }
        let byMed = snapshotNutrients(snapshot)
        var meds = (snapshot["medications"].array ?? []).map { row(medicationColumns, $0, ["photo_path": .null]) }.stableSorted(by: byID)
        for i in meds.indices {
            meds[i]["nutrients"] = .arr(byMed[meds[i]["id"]?.string ?? ""] ?? [])
        }
        var scheds = (snapshot["schedules"].array ?? []).map { row(scheduleColumns, $0) }.stableSorted(by: byID)
        let logs = (snapshot["dose_logs"].array ?? []).map { row(doseLogColumns, $0) }.stableSorted(by: byID)
        for i in scheds.indices {
            scheds[i]["times"] = .arr((scheds[i]["times"] ?? .null).truthy ? ((scheds[i]["times"] ?? .null).array ?? []) : [])
            scheds[i]["days"] = .arr((scheds[i]["days"] ?? .null).truthy ? ((scheds[i]["days"] ?? .null).array ?? []) : [])
        }
        return .obj([
            "format": .str(archiveFormat), "version": .int(archiveVersion), "exported_ms": exportedMs,
            "time_zone": timeZone, "app": .obj(["platform": platform, "version": appVersion]),
            "medications": .arr(meds.map { .obj($0) }), "schedules": .arr(scheds.map { .obj($0) }),
            "dose_logs": .arr(logs.map { .obj($0) }),
        ])
    }

    private static func nonEmptyString(_ v: RJ) -> Bool { !(v.string ?? "").isEmpty }

    static func validMedicationRow(_ m: RJ) -> Bool {
        guard m.object != nil, nonEmptyString(m["id"]) else { return false }
        guard let name = m["name"].string, !strip(name).isEmpty else { return false }
        guard forms.contains(m["form"].string ?? ""), foodRelations.contains(m["food_relation"].string ?? ""),
              statuses.contains(m["status"].string ?? "") else { return false }
        guard isNumber(m["dose_quantity"]), doseUnits.contains(m["dose_unit"].string ?? "") else { return false }
        guard parseDate(m["start_date"]) != nil else { return false }
        if !m["end_date"].isNull, parseDate(m["end_date"]) == nil { return false }
        guard isInt(m["created_ms"]), isInt(m["updated_ms"]) else { return false }
        return true
    }

    static func validScheduleRow(_ s: RJ) -> Bool {
        guard s.object != nil, nonEmptyString(s["id"]) else { return false }
        guard s["medication_id"].string != nil, isInt(s["active_from_ms"]) else { return false }
        if !s["active_until_ms"].isNull, !isInt(s["active_until_ms"]) { return false }
        guard isInt(s["created_ms"]), isInt(s["updated_ms"]) else { return false }
        return validateSchedule(s).isEmpty
    }

    static func validLogRow(_ l: RJ) -> Bool {
        guard l.object != nil, nonEmptyString(l["id"]) else { return false }
        guard l["medication_id"].string != nil, isInt(l["scheduled_at_ms"]) else { return false }
        guard storedDoseStatuses.contains(l["status"].string ?? "") else { return false }
        if !l["schedule_id"].isNull, l["schedule_id"].string == nil { return false }
        guard isNumber(l["dose_quantity"]), l["dose_unit"].string != nil else { return false }
        guard isInt(l["created_ms"]), isInt(l["updated_ms"]) else { return false }
        return true
    }

    /// Merge an archive into the local snapshot: per row by id, absent → insert, newer `updated_ms` →
    /// update, else `skip:older`. Nothing is ever deleted.
    static func mergeArchive(snapshot: RJ, archive: RJ, now: Int) -> RJ {
        guard archive.object != nil, archive["format"].string == archiveFormat else {
            return .obj(["ok": .bool(false), "error": .str("bad_format"), "ops": .arr([])])
        }
        guard archive["version"].double == Double(archiveVersion) else {
            return .obj(["ok": .bool(false), "error": .str("unsupported_version"), "ops": .arr([])])
        }
        var ops: [RJ] = []
        func table(_ key: String) -> [String: RJ] {
            var out: [String: RJ] = [:]
            for r in snapshot[key].array ?? [] {
                if let id = r["id"].string { out[id] = r }
            }
            return out
        }
        let localMeds = table("medications")
        let localScheds = table("schedules")
        let localLogs = table("dose_logs")
        var occupied: [LogKey: String] = [:]
        for l in (snapshot["dose_logs"].array ?? []) {
            if let scheduleID = l["schedule_id"].string, let id = l["id"].string {
                occupied[LogKey(scheduleID: scheduleID, scheduledAtMs: int(l["scheduled_at_ms"]) ?? Int.min)] = id
            }
        }
        var knownMeds = Set(localMeds.keys)
        var knownScheds = Set(localScheds.keys)

        func decide(_ tableName: String, _ source: RJ, _ local: RJ?, _ columns: [String]) -> Bool {
            var r = row(columns, source)
            if let updated = int(r["updated_ms"] ?? .null), updated > now { r["updated_ms"] = .int(now) }
            let id = r["id"] ?? .null
            guard let local else {
                ops.append(.obj(["table": .str(tableName), "op": .str("insert"), "id": id, "row": .obj(r)]))
                return true
            }
            if (int(r["updated_ms"] ?? .null) ?? 0) > (int(local["updated_ms"]) ?? 0) {
                ops.append(.obj(["table": .str(tableName), "op": .str("update"), "id": id, "row": .obj(r)]))
                return true
            }
            ops.append(.obj(["table": .str(tableName), "op": .str("skip"), "id": id, "reason": .str("older")]))
            return false
        }

        for m in archive["medications"].array ?? [] {
            guard validMedicationRow(m) else {
                ops.append(.obj(["table": .str("medications"), "op": .str("skip"), "id": m.object != nil ? m["id"] : .null, "reason": .str("invalid")]))
                continue
            }
            let id = m["id"].string ?? ""
            var copy = m.object ?? [:]
            copy["photo_path"] = localMeds[id].map { $0["photo_path"] } ?? .null
            let (nutrients, nutrientSkips) = archiveNutrients(m)
            if decide("medications", .obj(copy), localMeds[id], medicationColumns),
               case .obj(var op) = ops[ops.count - 1], case .obj(var row)? = op["row"] {
                row["nutrients"] = nutrients.map(RJ.arr) ?? .null
                op["row"] = .obj(row)
                ops[ops.count - 1] = .obj(op)
                ops.append(contentsOf: nutrientSkips)
            }
            knownMeds.insert(id)
        }
        for s in archive["schedules"].array ?? [] {
            guard validScheduleRow(s) else {
                ops.append(.obj(["table": .str("medication_schedules"), "op": .str("skip"), "id": s.object != nil ? s["id"] : .null, "reason": .str("invalid")]))
                continue
            }
            let id = s["id"].string ?? ""
            guard knownMeds.contains(s["medication_id"].string ?? "") else {
                ops.append(.obj(["table": .str("medication_schedules"), "op": .str("skip"), "id": s["id"], "reason": .str("orphan")]))
                continue
            }
            _ = decide("medication_schedules", s, localScheds[id], scheduleColumns)
            knownScheds.insert(id)
        }
        for l in archive["dose_logs"].array ?? [] {
            guard validLogRow(l) else {
                ops.append(.obj(["table": .str("dose_logs"), "op": .str("skip"), "id": l.object != nil ? l["id"] : .null, "reason": .str("invalid")]))
                continue
            }
            let id = l["id"].string ?? ""
            let scheduleID = l["schedule_id"].string
            if !knownMeds.contains(l["medication_id"].string ?? "") || (scheduleID != nil && !knownScheds.contains(scheduleID!)) {
                ops.append(.obj(["table": .str("dose_logs"), "op": .str("skip"), "id": l["id"], "reason": .str("orphan")]))
                continue
            }
            if let scheduleID {
                let key = LogKey(scheduleID: scheduleID, scheduledAtMs: int(l["scheduled_at_ms"]) ?? Int.min)
                if let holder = occupied[key], holder != id {
                    ops.append(.obj(["table": .str("dose_logs"), "op": .str("skip"), "id": l["id"], "reason": .str("occurrence_conflict")]))
                    continue
                }
            }
            if decide("dose_logs", l, localLogs[id], doseLogColumns), let scheduleID {
                occupied[LogKey(scheduleID: scheduleID, scheduledAtMs: int(l["scheduled_at_ms"]) ?? Int.min)] = id
            }
        }
        return .obj(["ok": .bool(true), "error": .null, "ops": .arr(ops)])
    }
}
