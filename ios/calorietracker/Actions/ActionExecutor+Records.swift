import Foundation

/// Health Records and Medications actions. Records reuse the Coach record tools' payloads
/// (`records_search`, `records_observation_series`); dose marking goes through the same repository
/// call as the notification Taken / Skip / Snooze buttons and never edits a medication.
extension ActionExecutor {
    // MARK: - Records

    private func recordsDB() async throws -> RecordsDatabase {
        guard let database = await env.recordsDatabase() else {
            throw ActionError.notFound(String(localized: "There are no health records in Ayuvo yet."))
        }
        return database
    }

    private var today: String { env.zone.day(of: env.nowMs).text }

    func recordsPayload(_ tool: String, _ args: [String: Any]) async throws -> RJ {
        let database = try await recordsDB()
        do {
            return try await database.coachToolPayload(name: tool, args: RJ.from(args), selectedIDs: [], today: today,
                                                       dateOrder: RecordDateOrder.device.rawValue)
        } catch {
            throw ActionError.unavailable(String(localized: "Health records can't be read right now."))
        }
    }

    static func recordFields(_ record: RJ) -> [String: ActionField] {
        [
            "id": .optional(record["record_id"].string), "title": .optional(record["title"].string),
            "kind": .optional(record["record_type"].string), "date": .optional(record["date"].string),
        ]
    }

    func recordsSearch(_ v: ActionValidation) async throws -> ActionResult {
        let query = v.string("query") ?? ""
        let payload = try await recordsPayload(RecordsCoachContract.searchTool, ["query": query, "limit": v.int("limit") ?? 20])
        if let error = payload["error"].string { throw ActionError.unavailable(error) }
        let items = (payload["records"].array ?? []).map(Self.recordFields)
        let titles = items.prefix(3).compactMap { $0["title"]?.string }.joined(separator: ", ")
        let dialog = items.isEmpty
            ? String(localized: "No records match “\(query)”.")
            : String(localized: "\(items.count) records: \(titles)\(items.count > 3 ? "…" : "").")
        return ActionResult(actionID: v.actionID, fields: ["count": .int(items.count)], items: items, dialog: dialog)
    }

    func recordsLatest(_ v: ActionValidation) async throws -> ActionResult {
        let payload = try await recordsPayload(RecordsCoachContract.searchTool, ["query": "", "limit": 1])
        guard let record = payload["records"].array?.first else {
            throw ActionError.notFound(String(localized: "There are no health records in Ayuvo yet."))
        }
        let fields = Self.recordFields(record)
        let title = fields["title"]?.string ?? ""
        let date = fields["date"]?.string ?? ""
        return ActionResult(actionID: v.actionID, fields: fields, dialog: String(localized: "Your latest record is “\(title)” from \(date)."),
                            route: fields["id"]?.string.map { .target("record:\($0)") })
    }

    func labValue(_ v: ActionValidation) async throws -> ActionResult {
        let analyte = v.string("analyte") ?? ""
        let payload = try await recordsPayload(RecordsCoachContract.seriesTool, ["analyte": analyte])
        let points = (payload["points"].array ?? []).sorted { ($0["date"].string ?? "") < ($1["date"].string ?? "") }
        guard let latest = points.last else {
            throw ActionError.notFound(String(localized: "No \(analyte) results in your health records."))
        }
        let name = payload["display_name"].string ?? analyte
        let value = latest["canonical_value"].double ?? Double(latest["value"].string ?? "")
        let unit = payload["unit"].string ?? latest["unit"].string
        let limit = v.int("limit") ?? 10
        let history: [ActionField] = points.suffix(limit).map { point in
            .object([
                "date": .optional(point["date"].string), "value": .optional(point["canonical_value"].double ?? Double(point["value"].string ?? "")),
                "unit": .optional(unit), "flag": .optional(point["flag"].string), "record_id": .optional(point["record_id"].string),
            ])
        }
        let flag = latest["flag"].string
        var dialog = String(localized: "Latest \(name): \(value.map { Self.number($0, digits: 2) } ?? latest["value"].string ?? "") \(unit ?? "") on \(latest["date"].string ?? "").")
        if let flag, flag != "normal" { dialog += " " + String(localized: "Marked \(flag) on the report.") }
        return ActionResult(actionID: v.actionID, fields: [
            "value": .optional(value), "unit": .optional(unit), "date": .optional(latest["date"].string), "flag": .optional(flag),
            "history": .list(history), "analyte": .optional(payload["analyte"].string),
        ], dialog: dialog)
    }

    // MARK: - Medications

    private func medications() async throws -> MedicationsRepository {
        guard let runtime = env.medicationsRuntime, runtime.databaseExists || runtime.isOpen,
              await runtime.openIfNeeded(), let repository = runtime.repository else {
            throw ActionError.notFound(String(localized: "You haven't added any medications in Ayuvo."))
        }
        return repository
    }

    static func doseID(_ item: MedicationTodayTimeline.Item) -> String {
        "\(item.medicationID)|\(item.scheduleID ?? "")|\(item.scheduledAtMs)"
    }

    static func occurrence(fromDoseID id: String) -> DoseOccurrence? {
        let parts = id.split(separator: "|", omittingEmptySubsequences: false)
        guard parts.count == 3, !parts[0].isEmpty, let ms = Int64(parts[2]) else { return nil }
        return DoseOccurrence(medicationID: String(parts[0]), scheduleID: parts[1].isEmpty ? nil : String(parts[1]), scheduledAtMs: ms)
    }

    func doseFields(_ item: MedicationTodayTimeline.Item, timeline: MedicationTodayTimeline) -> [String: ActionField] {
        [
            "id": .string(Self.doseID(item)), "medication": .string(timeline.medication(item.medicationID)?.displayName ?? ""),
            "medication_id": .string(item.medicationID), "scheduled_ms": .int(Int(item.scheduledAtMs)), "status": .string(item.status.rawValue),
        ]
    }

    func todayTimeline() async throws -> MedicationTodayTimeline {
        let repository = try await medications()
        do {
            return try await repository.today(nowMs: env.nowMs, zone: env.calendar.timeZone.identifier)
        } catch {
            throw ActionError.unavailable(String(localized: "Medications can't be read right now."))
        }
    }

    func scheduledItems(_ timeline: MedicationTodayTimeline) -> [MedicationTodayTimeline.Item] {
        timeline.slots.flatMap(\.items).filter { $0.kind == .scheduled }.sorted { $0.scheduledAtMs < $1.scheduledAtMs }
    }

    static func timeText(_ ms: Int64) -> String {
        Date(timeIntervalSince1970: Double(ms) / 1000).formatted(date: .omitted, time: .shortened)
    }

    func dosesToday(_ v: ActionValidation) async throws -> ActionResult {
        let timeline = try await todayTimeline()
        let items = scheduledItems(timeline)
        let s = timeline.summary
        let dialog = items.isEmpty
            ? String(localized: "No scheduled doses today.")
            : String(localized: "\(s.total) doses today: \(s.taken) taken, \(s.due + s.upcoming + s.snoozed) still to go.")
        return ActionResult(actionID: v.actionID, fields: ["count": .int(items.count), "taken": .int(s.taken), "missed": .int(s.missed)],
                            items: items.map { doseFields($0, timeline: timeline) }, dialog: dialog)
    }

    func doseNext(_ v: ActionValidation) async throws -> ActionResult {
        let timeline = try await todayTimeline()
        guard let next = scheduledItems(timeline).first(where: { !$0.status.isTerminal }) else {
            throw ActionError.notFound(String(localized: "No more doses due today."))
        }
        let name = timeline.medication(next.medicationID)?.displayName ?? ""
        return ActionResult(actionID: v.actionID, fields: doseFields(next, timeline: timeline),
                            dialog: String(localized: "Next: \(name) at \(Self.timeText(next.scheduledAtMs)) (\(next.status.title.lowercased()))."))
    }

    func doseHistory(_ v: ActionValidation) async throws -> ActionResult {
        let r = try range(v)
        let repository = try await medications()
        let limit = v.int("limit") ?? 100
        let logs = ((try? await repository.history(medicationID: v.string("medication"), before: nil, limit: 500)) ?? [])
            .filter { r.contains(ms: $0.scheduledAtMs) }
            .prefix(limit)
        let names = Dictionary(((try? await repository.medications(status: nil, search: nil)) ?? []).map { ($0.id, $0.displayName) },
                               uniquingKeysWith: { a, _ in a })
        let items: [[String: ActionField]] = logs.map {
            [
                "medication": .string(names[$0.medicationID] ?? ""), "medication_id": .string($0.medicationID),
                "scheduled_ms": .int(Int($0.scheduledAtMs)), "status": .string($0.status.rawValue), "taken_ms": .optional($0.takenAtMs),
            ]
        }
        let missed = logs.filter { $0.status == .missed }.count
        return ActionResult(actionID: v.actionID, fields: ["count": .int(items.count), "missed": .int(missed)], items: items,
                            dialog: String(localized: "\(items.count) doses logged \(Self.rangeText(v.string("range"))), \(missed) missed."))
    }

    /// Taken ÷ settled scheduled doses (taken, skipped, missed) in the requested range — the same rule
    /// on both platforms, computed from dose history rather than the fixed 7-day card window.
    func adherence(_ v: ActionValidation) async throws -> ActionResult {
        let preset = v.string("range") ?? "last_7_days"
        let r = try range(v)
        let repository = try await medications()
        let logs = ((try? await repository.history(medicationID: v.string("medication"), before: nil, limit: 2000)) ?? [])
            .filter { r.contains(ms: $0.scheduledAtMs) && !$0.isPRN && $0.status.isTerminal }
        let taken = logs.filter { $0.status == .taken }.count
        let expected = logs.count
        let percent = expected > 0 ? ActionMath.percent(Double(taken), Double(expected)) : nil
        let dialog = expected == 0
            ? String(localized: "No scheduled doses \(Self.rangeText(preset)).")
            : String(localized: "\(taken) of \(expected) doses taken \(Self.rangeText(preset)) (\(percent ?? 0)%).")
        return ActionResult(actionID: v.actionID, fields: [
            "taken": .int(taken), "expected": .int(expected), "percent": .optional(percent), "value": .optional(percent), "unit": .string("%"),
        ], dialog: dialog)
    }

    func doseMark(_ v: ActionValidation) async throws -> ActionResult {
        guard let occurrence = Self.occurrence(fromDoseID: v.string("dose") ?? "") else {
            throw ActionError.notFound(String(localized: "That dose isn't on today's list."))
        }
        let action: DoseAction
        switch v.string("action") {
        case "taken": action = .taken
        case "skipped": action = .skipped
        default: action = .snoozed
        }
        let repository = try await medications()
        let outcome: DoseActionOutcome
        do {
            outcome = try await repository.act(action, on: occurrence, nowMs: env.nowMs, snoozeMinutes: v.int("snooze_minutes") ?? 10,
                                               takenAtMs: nil, note: nil)
        } catch {
            throw ActionError.unavailable(String(localized: "Medications can't be changed right now."))
        }
        guard outcome.ok else {
            throw ActionError.conflict(Self.doseErrorText(outcome.error))
        }
        if env.sideEffects {
            NotificationCenter.default.post(name: .medicationDoseDidChange, object: nil)
            await MedicationReminderRuntime.shared.replan()
        }
        let timeline = try? await todayTimeline()
        let item = timeline.flatMap { t in scheduledItems(t).first { $0.medicationID == occurrence.medicationID && $0.scheduledAtMs == occurrence.scheduledAtMs } }
        let name = timeline?.medication(occurrence.medicationID)?.displayName ?? ""
        var fields: [String: ActionField] = ["id": .string(v.string("dose") ?? ""), "medication": .string(name),
                                             "scheduled_ms": .int(Int(occurrence.scheduledAtMs)),
                                             "status": .string(item?.status.rawValue ?? action.rawValue)]
        fields["medication_id"] = .string(occurrence.medicationID)
        let dialog: String
        switch action {
        case .taken: dialog = String(localized: "Marked \(name) as taken.")
        case .skipped: dialog = String(localized: "Marked \(name) as skipped.")
        default: dialog = String(localized: "Snoozed \(name) for \(v.int("snooze_minutes") ?? 10) minutes.")
        }
        return ActionResult(actionID: v.actionID, fields: fields, dialog: dialog)
    }

    static func doseErrorText(_ code: String?) -> String {
        switch code {
        case "already_taken": String(localized: "That dose is already marked as taken.")
        case "not_due_yet": String(localized: "That dose isn't due yet.")
        case "dose_missed": String(localized: "That dose is already marked as missed.")
        default: String(localized: "Ayuvo couldn't update that dose.")
        }
    }
}
