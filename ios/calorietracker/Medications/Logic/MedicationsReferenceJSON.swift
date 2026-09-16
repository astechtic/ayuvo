import Foundation

// Vector dispatch (`run_case`) and the JSON ⇄ typed-model conversions the store uses.

nonisolated extension MR {
    /// Mirrors `run_case(function, input)` of the reference.
    static func runCase(function: String, input inp: RJ) -> RJ {
        switch function {
        case "expand_occurrences":
            return .obj(["occurrences": .arr(expandOccurrences(schedule: inp["schedule"], medication: inp["medication"],
                                                                windowStart: inp["window_start_ms"], windowEnd: inp["window_end_ms"],
                                                                zone: inp["time_zone"].string ?? ""))])
        case "resolve_dose_status":
            return resolveDoseStatus(occurrence: inp["occurrence"], log: inp["log"], now: int(inp["now_ms"]) ?? 0)
        case "materialize_missed":
            return materializeMissed(occurrences: inp["occurrences"].array ?? [], logs: inp["logs"].array ?? [],
                                     medicationByID: medicationsByID(inp["medications"].array ?? []), now: int(inp["now_ms"]) ?? 0)
        case "today_timeline":
            return todayTimeline(medications: inp["medications"].array ?? [], schedules: inp["schedules"].array ?? [],
                                 logs: inp["logs"].array ?? [], now: int(inp["now_ms"]) ?? 0, zone: inp["time_zone"].string ?? "")
        case "adherence":
            return adherence(occurrences: inp["occurrences"].array ?? [], logs: inp["logs"].array ?? [],
                             now: int(inp["now_ms"]) ?? 0, medicationID: inp["medication_id"].string)
        case "plan_reminders":
            return planReminders(medications: inp["medications"].array ?? [], schedules: inp["schedules"].array ?? [],
                                 logs: inp["logs"].array ?? [], now: int(inp["now_ms"]) ?? 0, horizon: inp["horizon_ms"],
                                 zone: inp["time_zone"].string ?? "", budget: inp["budget"])
        case "dose_actions":
            switch inp["op"].string {
            case "apply_dose_action":
                return applyDoseAction(action: inp["action"].string ?? "", occurrence: inp["occurrence"], existingLog: inp["existing_log"],
                                       medication: inp["medication"], now: int(inp["now_ms"]) ?? 0, snoozeMinutes: inp["snooze_minutes"],
                                       takenAtMs: inp["taken_at_ms"], note: inp["note"])
            case "log_prn_dose":
                return logPRNDose(medication: inp["medication"], now: int(inp["now_ms"]) ?? 0, takenAtMs: inp["taken_at_ms"],
                                  doseQuantity: inp["dose_quantity"], note: inp["note"])
            default:
                return .obj(["error": .str("bad_op")])
            }
        case "lifecycle":
            return lifecycle(action: inp["action"].string ?? "", medication: inp["medication"], schedules: inp["schedules"].array ?? [],
                             now: int(inp["now_ms"]) ?? 0, newSchedule: inp["new_schedule"])
        case "auto_complete":
            return .obj(["medication_ids": .arr(autoComplete(medications: inp["medications"].array ?? [], today: inp["today"]))])
        case "frequency_hint":
            return frequencyHint(inp["value_json"])
        case "archive":
            switch inp["op"].string {
            case "export":
                return exportArchive(snapshot: inp["snapshot"], exportedMs: inp["exported_ms"], timeZone: inp["time_zone"],
                                     platform: inp["platform"], appVersion: inp["app_version"])
            case "merge":
                return mergeArchive(snapshot: inp["snapshot"], archive: inp["archive"], now: int(inp["now_ms"]) ?? 0)
            default:
                return .obj(["error": .str("bad_op")])
            }
        case "validate_draft":
            return .obj(["errors": .arr(validateDraft(inp["draft"]))])
        default:
            return .obj(["error": .str("unknown function \(function)")])
        }
    }
}

// MARK: - Typed ⇄ JSON conversions (column names, `is_prn`/`reminder_enabled` as 0/1, `times`/`days` as arrays)

nonisolated extension Medication {
    var rj: RJ {
        .obj([
            "id": .str(id), "name": .str(name), "generic_name": RJ.string(genericName), "brand_name": RJ.string(brandName),
            "strength": RJ.string(strength), "form": .str(form.rawValue), "dose_quantity": RJ.number(doseQuantity),
            "dose_unit": .str(doseUnit.rawValue), "food_relation": .str(foodRelation.rawValue), "instructions": RJ.string(instructions),
            "start_date": .str(startDate), "end_date": RJ.string(endDate), "status": .str(status.rawValue),
            "is_prn": .int(isPRN ? 1 : 0), "photo_path": RJ.string(photoPath), "related_record_id": RJ.string(relatedRecordID),
            "created_ms": .int(Int(createdMs)), "updated_ms": .int(Int(updatedMs)),
        ])
    }

    /// Lenient decode of a reference / archive row; `nil` without an id.
    init?(rj: RJ) {
        guard let id = rj["id"].string else { return nil }
        self.init(
            id: id,
            name: rj["name"].string ?? "",
            genericName: rj["generic_name"].string,
            brandName: rj["brand_name"].string,
            strength: rj["strength"].string,
            form: MedicationForm(raw: rj["form"].string),
            doseQuantity: rj["dose_quantity"].double ?? 1,
            doseUnit: DoseUnit(raw: rj["dose_unit"].string),
            foodRelation: FoodRelation(raw: rj["food_relation"].string),
            instructions: rj["instructions"].string,
            startDate: rj["start_date"].string ?? "",
            endDate: rj["end_date"].string,
            status: MedicationStatus(raw: rj["status"].string),
            isPRN: rj["is_prn"].truthy,
            photoPath: rj["photo_path"].string,
            relatedRecordID: rj["related_record_id"].string,
            createdMs: Int64(MR.int(rj["created_ms"]) ?? 0),
            updatedMs: Int64(MR.int(rj["updated_ms"]) ?? 0)
        )
    }
}

nonisolated extension MedicationSchedule {
    var rj: RJ {
        .obj([
            "id": .str(id), "medication_id": .str(medicationID), "frequency_kind": .str(frequency.rawValue),
            "times": .arr(times.map(RJ.str)), "days": .arr(days.map(RJ.int)),
            "interval_hours": intervalHours.map(RJ.int) ?? .null, "anchor_time": RJ.string(anchorTime),
            "reminder_enabled": .int(reminderEnabled ? 1 : 0), "active_from_ms": .int(Int(activeFromMs)),
            "active_until_ms": activeUntilMs.map { RJ.int(Int($0)) } ?? .null,
            "created_ms": .int(Int(createdMs)), "updated_ms": .int(Int(updatedMs)),
        ])
    }

    init?(rj: RJ) {
        guard let id = rj["id"].string, let medicationID = rj["medication_id"].string else { return nil }
        self.init(
            id: id,
            medicationID: medicationID,
            frequency: ScheduleFrequency(raw: rj["frequency_kind"].string),
            times: (rj["times"].array ?? []).compactMap(\.string),
            days: (rj["days"].array ?? []).compactMap { $0.double.map { Int($0) } },
            intervalHours: MR.int(rj["interval_hours"]),
            anchorTime: rj["anchor_time"].string,
            reminderEnabled: rj["reminder_enabled"].truthy,
            activeFromMs: Int64(MR.int(rj["active_from_ms"]) ?? 0),
            activeUntilMs: MR.int(rj["active_until_ms"]).map(Int64.init),
            createdMs: Int64(MR.int(rj["created_ms"]) ?? 0),
            updatedMs: Int64(MR.int(rj["updated_ms"]) ?? 0)
        )
    }
}

nonisolated extension DoseLog {
    var rj: RJ {
        .obj([
            "id": .str(id), "medication_id": .str(medicationID), "schedule_id": RJ.string(scheduleID),
            "scheduled_at_ms": .int(Int(scheduledAtMs)), "status": .str(status.rawValue),
            "taken_at_ms": takenAtMs.map { RJ.int(Int($0)) } ?? .null, "snoozed_until_ms": snoozedUntilMs.map { RJ.int(Int($0)) } ?? .null,
            "dose_quantity": RJ.number(doseQuantity), "dose_unit": .str(doseUnit.rawValue), "note": RJ.string(note),
            "created_ms": .int(Int(createdMs)), "updated_ms": .int(Int(updatedMs)),
        ])
    }

    init?(rj: RJ) {
        guard let id = rj["id"].string, let medicationID = rj["medication_id"].string, let scheduledAt = MR.int(rj["scheduled_at_ms"]) else { return nil }
        self.init(
            id: id,
            medicationID: medicationID,
            scheduleID: rj["schedule_id"].string,
            scheduledAtMs: Int64(scheduledAt),
            status: DoseStatus(raw: rj["status"].string),
            takenAtMs: MR.int(rj["taken_at_ms"]).map(Int64.init),
            snoozedUntilMs: MR.int(rj["snoozed_until_ms"]).map(Int64.init),
            doseQuantity: rj["dose_quantity"].double ?? 1,
            doseUnit: DoseUnit(raw: rj["dose_unit"].string),
            note: rj["note"].string,
            createdMs: Int64(MR.int(rj["created_ms"]) ?? 0),
            updatedMs: Int64(MR.int(rj["updated_ms"]) ?? 0)
        )
    }
}

/// Substitutes the reference's `new-N` ids with UUIDs, consistently within one call.
nonisolated final class MedicationIDSubstitution {
    private var map: [String: String] = [:]

    func resolve(_ id: String?) -> String {
        guard let id else { return UUID().uuidString.lowercased() }
        if id.hasPrefix("new-") {
            if let existing = map[id] { return existing }
            let fresh = UUID().uuidString.lowercased()
            map[id] = fresh
            return fresh
        }
        return id
    }

    /// Returns `rj` with its `id` (and, when present, `schedule_id`) resolved.
    func resolvedRow(_ rj: RJ) -> RJ {
        guard var obj = rj.object else { return rj }
        obj["id"] = .str(resolve(obj["id"]?.string))
        if let scheduleID = obj["schedule_id"]?.string, scheduleID.hasPrefix("new-") {
            obj["schedule_id"] = .str(resolve(scheduleID))
        }
        return .obj(obj)
    }
}
