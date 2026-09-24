import Foundation

/// Swift port of the Coach section of `scripts/medications_reference.py` (docs/medications.md §20,
/// docs/coach.md §3). Tool names, descriptions and input schemas live in
/// `shared/medications/coach_tools.json`, bundled here as `medication_coach_tools.json`; a parity
/// test byte-compares the two. Payload shapes are driven by
/// `shared/medications/test-vectors/coach_tools_payloads.json`.
nonisolated extension MR {
    static let coachDoseLimit = 200
    static let reCoachPlaceholder = Rx("\\{([a-z_]+)\\}")

    // MARK: Errors and prompt strings

    /// `{"error": <errors.key with {placeholders} filled in ONE left-to-right pass>}`.
    static func coachError(_ key: String, _ values: [String: String] = [:]) -> RJ {
        let template = MedicationsCoachContract.shared.errors[key] ?? key
        var out = ""
        var last = 0
        for m in reCoachPlaceholder.finditer(template) {
            guard let name = m.g(1), let value = values[name] else { continue }
            out += template.rSub(last, m.start)
            out += value
            last = m.end
        }
        out += template.rSub(last)
        return .obj(["error": .str(out)])
    }

    /// A required `yyyy-MM-dd` argument. Returns nil and appends the error when it is not one.
    static func coachDate(_ value: RJ, _ out: inout [RJ]) -> String? {
        if let text = value.string, parseDate(text) != nil { return text }
        let shown = value.string ?? value.compactJSON
        out.append(coachError("bad_date", ["value": shown]))
        return nil
    }

    /// `from`/`to` validated together: bad_date first (from before to), then date_order.
    static func coachRange(_ args: RJ, _ out: inout [RJ]) -> (String, String)? {
        guard let start = coachDate(args["from"], &out), out.isEmpty else { return nil }
        guard let end = coachDate(args["to"], &out), out.isEmpty else { return nil }
        if start > end {
            out.append(coachError("date_order"))
            return nil
        }
        return (start, end)
    }

    /// Whole numbers clamp to 1...cap; anything else (strings, fractions, booleans) -> cap.
    static func coachLimit(_ value: RJ, _ cap: Int) -> Int {
        guard case .int(let raw) = value else {
            if case .num(let d) = value, d == d.rounded(), abs(d) < 1e15 {
                return max(1, min(cap, Int(d)))
            }
            return cap
        }
        return max(1, min(cap, raw))
    }

    // MARK: get_medications

    /// The open schedule row of a medication (`active_until_ms IS NULL`), else the newest closed one.
    static func activeSchedule(_ schedules: [RJ], _ medicationID: String) -> RJ {
        let rows = schedules.filter { $0["medication_id"].string == medicationID }
        let open = rows.filter { $0["active_until_ms"].isNull }
        if !open.isEmpty {
            return open.stableSorted { a, b in
                less([a["active_from_ms"], a["id"]], [b["active_from_ms"], b["id"]])
            }.last ?? .null
        }
        if rows.isEmpty { return .null }
        return rows.stableSorted { a, b in
            less([a["active_until_ms"], a["id"]], [b["active_until_ms"], b["id"]])
        }.last ?? .null
    }

    /// Structured, never a localized sentence: the model phrases it.
    static func schedulePayload(_ schedule: RJ) -> RJ {
        guard !schedule.isNull else { return .null }
        return .obj([
            "frequency": schedule["frequency_kind"],
            "times": .arr(schedule["times"].array ?? []),
            "days": .arr(schedule["days"].array ?? []),
            "interval_hours": schedule["interval_hours"],
            "anchor_time": schedule["anchor_time"],
            "reminders_on": .bool(schedule["reminder_enabled"].truthy),
        ])
    }

    static func medicationPayload(_ medication: RJ, _ schedule: RJ) -> RJ {
        .obj([
            "medication_id": medication["id"], "name": medication["name"],
            "generic_name": medication["generic_name"], "brand_name": medication["brand_name"],
            "strength": medication["strength"], "form": medication["form"],
            "dose_quantity": RJ.number(medication["dose_quantity"].double),
            "dose_unit": medication["dose_unit"], "food_relation": medication["food_relation"],
            "instructions": medication["instructions"], "status": medication["status"],
            "is_prn": .bool(medication["is_prn"].truthy), "start_date": medication["start_date"],
            "end_date": medication["end_date"],
            "schedule": medication["is_prn"].truthy ? .null : schedulePayload(schedule),
        ])
    }

    /// `get_medications`. Active medicines unless `include_inactive` is exactly true.
    static func coachMedicationsPayload(snapshot: RJ, args: RJ) -> RJ {
        let includeInactive = args["include_inactive"].bool == true
        let meds = (snapshot["medications"].array ?? [])
            .filter { includeInactive || $0["status"].string == "active" }
            .stableSorted { a, b in
                let af = foldName(a["name"])
                let bf = foldName(b["name"])
                if af != bf { return af < bf }
                return (a["id"].string ?? "") < (b["id"].string ?? "")
            }
        let schedules = snapshot["schedules"].array ?? []
        let rows = meds.map { medicationPayload($0, activeSchedule(schedules, $0["id"].string ?? "")) }
        return .obj(["count": .int(rows.count), "medications": .arr(rows)])
    }

    // MARK: get_dose_history

    private static func coachDoseRow(medicationID: RJ, byID: [String: RJ], scheduledAt: Int, log: RJ,
                                     status: String, isLate: Bool, isPRN: Bool, zone: String) -> RJ {
        let med = byID[medicationID.string ?? ""] ?? .null
        let takenAt = MR.int(log["taken_at_ms"])
        return .obj([
            "medication_id": medicationID, "name": med["name"],
            "date": .str(localDateOf(scheduledAt, zone: zone)),
            "scheduled_at": .str(localHHMMOf(scheduledAt, zone: zone)),
            "scheduled_at_ms": .int(scheduledAt),
            "taken_at": takenAt.map { RJ.str(localHHMMOf($0, zone: zone)) } ?? .null,
            "status": .str(status == "taken" && isLate ? "taken_late" : status),
            "is_prn": .bool(isPRN),
            "dose_quantity": log.isNull ? .null : RJ.number(log["dose_quantity"].double),
            "dose_unit": log.isNull ? .null : log["dose_unit"],
            "note": log.isNull ? .null : log["note"],
        ])
    }

    /// Every dose of the window: scheduled occurrences with their resolved status, stored logs of
    /// scheduled doses that match no occurrence (the schedule was edited), and PRN logs.
    static func coachDoseRows(snapshot: RJ, medicationID: String?, start: Int, end: Int,
                              now: Int, zone: String) -> [RJ] {
        let meds = snapshot["medications"].array ?? []
        let byID = medicationsByID(meds)
        let logs = (snapshot["dose_logs"].array ?? []).filter { log in
            let at = MR.int(log["scheduled_at_ms"]) ?? 0
            guard at >= start, at < end else { return false }
            guard let wanted = medicationID else { return true }
            return log["medication_id"].string == wanted
        }
        let index = indexLogs(logs)
        var matched = Set<Int>()
        var rows: [RJ] = []
        for occ in expandAll(medications: meds, schedules: snapshot["schedules"].array ?? [],
                             windowStart: start, windowEnd: end, zone: zone, statuses: nil) {
            if let wanted = medicationID, occ["medication_id"].string != wanted { continue }
            let key = logKey(occ["schedule_id"], occ["scheduled_at_ms"])
            let position = index[key]
            let log = position.map { logs[$0] } ?? RJ.null
            if let position { matched.insert(position) }
            let resolved = resolveDoseStatus(occurrence: occ, log: log, now: now)
            rows.append(coachDoseRow(medicationID: occ["medication_id"], byID: byID,
                                     scheduledAt: MR.int(occ["scheduled_at_ms"]) ?? 0, log: log,
                                     status: resolved["status"].string ?? "", isLate: resolved["is_late"].truthy,
                                     isPRN: false, zone: zone))
        }
        for (position, log) in logs.enumerated() where !matched.contains(position) {
            let isPRN = log["schedule_id"].isNull
            let status = log["status"].string ?? ""
            if !isPRN, !doseStatuses.contains(status) { continue }
            rows.append(coachDoseRow(medicationID: log["medication_id"], byID: byID,
                                     scheduledAt: MR.int(log["scheduled_at_ms"]) ?? 0, log: log,
                                     status: status, isLate: false, isPRN: isPRN, zone: zone))
        }
        return rows.stableSorted { a, b in
            let at = MR.int(a["scheduled_at_ms"]) ?? 0
            let bt = MR.int(b["scheduled_at_ms"]) ?? 0
            if at != bt { return at > bt }
            let af = foldName(a["name"])
            let bf = foldName(b["name"])
            if af != bf { return af < bf }
            return (a["medication_id"].string ?? "") < (b["medication_id"].string ?? "")
        }
    }

    /// `get_dose_history`. Newest first, `limit` omitted -> 200.
    static func coachDoseHistoryPayload(snapshot: RJ, args: RJ, now: Int, zone: String) -> RJ {
        var errors: [RJ] = []
        guard let (start, end) = coachRange(args, &errors) else { return errors[0] }
        let known = Set((snapshot["medications"].array ?? []).compactMap { $0["id"].string })
        var medicationID: String?
        if let requested = args["medication_id"].string {
            guard known.contains(requested) else { return coachError("unknown_medication", ["id": requested]) }
            medicationID = requested
        }
        guard let startMs = dayWindow(start, zone: zone)?.0, let endMs = dayWindow(end, zone: zone)?.1 else {
            return coachError("bad_date", ["value": start])
        }
        let rows = coachDoseRows(snapshot: snapshot, medicationID: medicationID, start: startMs,
                                 end: endMs, now: now, zone: zone)
        let limit = coachLimit(args["limit"], coachDoseLimit)
        let kept = Array(rows.prefix(limit))
        return .obj(["from": .str(start), "to": .str(end), "count": .int(kept.count), "doses": .arr(kept)])
    }

    // MARK: get_medication_adherence

    private static func coachCounts(_ rows: [RJ]) -> [String: Int] {
        var out = ["taken": 0, "taken_late": 0, "skipped": 0, "missed": 0, "open": 0]
        for row in rows {
            switch row["status"].string ?? "" {
            case "taken_late":
                out["taken"]! += 1
                out["taken_late"]! += 1
            case "taken": out["taken"]! += 1
            case "skipped": out["skipped"]! += 1
            case "missed": out["missed"]! += 1
            default: out["open"]! += 1
            }
        }
        return out
    }

    /// The scheduled slot with the most missed or skipped doses; ties go to the earlier time.
    private static func coachMostMissedTime(_ rows: [RJ]) -> RJ {
        var tally: [String: Int] = [:]
        for row in rows {
            let status = row["status"].string ?? ""
            guard status == "missed" || status == "skipped", !row["is_prn"].truthy else { continue }
            tally[row["scheduled_at"].string ?? "", default: 0] += 1
        }
        guard !tally.isEmpty else { return .null }
        let best = tally.sorted { a, b in
            if a.value != b.value { return a.value > b.value }
            return a.key < b.key
        }.first!
        return .str(best.key)
    }

    /// `get_medication_adherence`. `percent` comes from §9 `adherence`, so the tool and the app's own
    /// adherence screen can never disagree.
    static func coachAdherencePayload(snapshot: RJ, args: RJ, now: Int, zone: String) -> RJ {
        var errors: [RJ] = []
        guard let (start, end) = coachRange(args, &errors) else { return errors[0] }
        let meds = snapshot["medications"].array ?? []
        let known = Set(meds.compactMap { $0["id"].string })
        var medicationID: String?
        if let requested = args["medication_id"].string {
            guard known.contains(requested) else { return coachError("unknown_medication", ["id": requested]) }
            medicationID = requested
        }
        guard let startMs = dayWindow(start, zone: zone)?.0, let endMs = dayWindow(end, zone: zone)?.1 else {
            return coachError("bad_date", ["value": start])
        }
        let occurrences = expandAll(medications: meds, schedules: snapshot["schedules"].array ?? [],
                                    windowStart: startMs, windowEnd: endMs, zone: zone, statuses: nil)
        let logs = (snapshot["dose_logs"].array ?? []).filter {
            let at = MR.int($0["scheduled_at_ms"]) ?? 0
            return at >= startMs && at < endMs
        }
        let rows = coachDoseRows(snapshot: snapshot, medicationID: medicationID, start: startMs,
                                 end: endMs, now: now, zone: zone)

        func block(_ mid: String?) -> [String: RJ] {
            let scoped = mid == nil ? rows : rows.filter { $0["medication_id"].string == mid }
            let counts = coachCounts(scoped)
            let summary = adherence(occurrences: occurrences, logs: logs, now: now, medicationID: mid)
            return [
                "scheduled": summary["expected"], "taken": summary["taken"],
                "taken_late": .int(counts["taken_late"] ?? 0), "skipped": .int(counts["skipped"] ?? 0),
                "missed": .int(counts["missed"] ?? 0), "still_open": .int(counts["open"] ?? 0),
                "percent": summary["percent"], "has_data": summary["has_data"],
                "most_missed_time": coachMostMissedTime(scoped),
            ]
        }

        var per: [RJ] = []
        let scopedMeds = meds
            .filter { medicationID == nil || $0["id"].string == medicationID }
            .stableSorted { a, b in
                let af = foldName(a["name"])
                let bf = foldName(b["name"])
                if af != bf { return af < bf }
                return (a["id"].string ?? "") < (b["id"].string ?? "")
            }
        for med in scopedMeds {
            var entry = block(med["id"].string)
            if MR.int(entry["scheduled"] ?? .null) == 0, MR.int(entry["still_open"] ?? .null) == 0 { continue }
            entry["medication_id"] = med["id"]
            entry["name"] = med["name"]
            per.append(.obj(entry))
        }
        return .obj(["from": .str(start), "to": .str(end), "overall": .obj(block(medicationID)),
                     "medications": .arr(per)])
    }

    // MARK: Prompt lines

    /// The `## Data available` lines for medications (docs/coach.md §3).
    static func coachPromptLines(snapshot: RJ, accessEnabled: Bool) -> RJ {
        let meds = snapshot["medications"].array ?? []
        let active = meds.filter { $0["status"].string == "active" }
        let contract = MedicationsCoachContract.shared
        guard accessEnabled, !meds.isEmpty else {
            return .obj(["advertise_tools": .bool(false), "available_line": .null,
                         "not_available_line": RJ.string(contract.prompt["not_available_line"]),
                         "guardrails": .null])
        }
        let template = contract.prompt["available_line"] ?? ""
        var out = ""
        var last = 0
        let values = ["n": String(meds.count), "active": String(active.count)]
        for m in reCoachPlaceholder.finditer(template) {
            guard let name = m.g(1), let value = values[name] else { continue }
            out += template.rSub(last, m.start)
            out += value
            last = m.end
        }
        out += template.rSub(last)
        return .obj(["advertise_tools": .bool(true), "available_line": .str(out),
                     "not_available_line": .null,
                     "guardrails": RJ.string(contract.prompt["guardrails"])])
    }

    // MARK: Vector dispatch

    static func runCoachCase(_ inp: RJ) -> RJ {
        let tool = inp["tool"].string ?? ""
        switch tool {
        case "get_medications":
            return coachMedicationsPayload(snapshot: inp["snapshot"], args: inp["args"])
        case "get_dose_history":
            return coachDoseHistoryPayload(snapshot: inp["snapshot"], args: inp["args"],
                                           now: MR.int(inp["now_ms"]) ?? 0,
                                           zone: inp["time_zone"].string ?? "")
        case "get_medication_adherence":
            return coachAdherencePayload(snapshot: inp["snapshot"], args: inp["args"],
                                         now: MR.int(inp["now_ms"]) ?? 0,
                                         zone: inp["time_zone"].string ?? "")
        case "prompt_lines":
            return coachPromptLines(snapshot: inp["snapshot"], accessEnabled: inp["access_enabled"].truthy)
        default:
            return .obj(["error": .str("unknown tool \(tool)")])
        }
    }
}

/// `shared/medications/coach_tools.json`, bundled as `medication_coach_tools.json` (iOS flattens
/// bundle resources, so it cannot keep the shared name — Records already ships a `coach_tools.json`).
nonisolated struct MedicationsCoachContract {
    struct Tool {
        var name: String
        var description: String
        var schemaText: String
        var schema: RJ
    }

    var tools: [Tool]
    var prompt: [String: String]
    var errors: [String: String]
    /// `prompt.mentions_words` — shared so "did the user ask about medicines?" cannot drift.
    var mentionsWords: [String] = []

    var names: [String] { tools.map(\.name) }
    func tool(_ name: String) -> Tool? { tools.first { $0.name == name } }

    /// The schema as the platforms advertise it: the compact serialization stored in the file.
    func parameterSchema(for name: String) -> [String: Any]? {
        guard let tool = tool(name) else { return nil }
        return tool.schema.anyValue as? [String: Any]
    }

    static let shared: MedicationsCoachContract = {
        for bundle in [Bundle.main, Bundle(for: BundleMarker.self)] {
            if let url = bundle.url(forResource: "medication_coach_tools", withExtension: "json"),
               let text = try? String(contentsOf: url, encoding: .utf8),
               let parsed = MedicationsCoachContract(text: text) {
                return parsed
            }
        }
        return MedicationsCoachContract(tools: [], prompt: [:], errors: [:])
    }()

    init(tools: [Tool], prompt: [String: String], errors: [String: String]) {
        self.tools = tools
        self.prompt = prompt
        self.errors = errors
    }

    init?(text: String) {
        guard let root = RJ.parse(text), root["format"].string == "ayuvo-medications-coach-tools" else { return nil }
        let rawSchemas = RecordsCoachContract.rawSchemaTexts(text)
        var tools: [Tool] = []
        for (index, entry) in (root["tools"].array ?? []).enumerated() {
            guard let name = entry["name"].string, let description = entry["description"].string else { continue }
            let schemaText = index < rawSchemas.count ? rawSchemas[index] : entry["input_schema"].compactJSON
            tools.append(Tool(name: name, description: description, schemaText: schemaText,
                              schema: entry["input_schema"]))
        }
        self.tools = tools
        self.prompt = (root["prompt"].object ?? [:]).compactMapValues(\.string)
        self.errors = (root["errors"].object ?? [:]).compactMapValues(\.string)
        self.mentionsWords = (root["prompt"]["mentions_words"].array ?? []).compactMap(\.string)
    }

    private final class BundleMarker {}
}
