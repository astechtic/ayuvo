import Foundation

// Swift port of `scripts/partner_reference.py` §11 source mappings (shared-schema rows → envelope parts).
// Every mapper reads named columns only (docs §1.3, §7.6); `nil` = not shareable.

nonisolated extension PartnerRef {
    /// `_drop_none`.
    static func dropNone(_ d: [String: RJ?]) -> RJ {
        var out: [String: RJ] = [:]
        for (k, v) in d { if let v, !v.isNull { out[k] = v } }
        return .obj(out)
    }

    /// `row.get(key)` (nil → JSON null).
    private static func g(_ row: RJ, _ key: String) -> RJ { row.get(key) ?? .null }

    private static func envelope(_ type: String, id: RJ, category: String, day: RJ, data: RJ) -> RJ {
        .obj(["type": .str(type), "id": id, "category": .str(category), "day": day, "data": data])
    }

    static func mapRollup(_ row: RJ) -> RJ? {
        let data = dropNone([
            "type_id": row["type_id"], "day": row["day"], "unit": g(row, "unit"), "sum": g(row, "sum"), "avg": g(row, "avg"),
            "min": g(row, "min"), "max": g(row, "max"), "count": g(row, "count"), "last_value": g(row, "last_value"),
            "last_at_ms": g(row, "last_at_ms"), "v2_avg": g(row, "v2_avg"), "v2_min": g(row, "v2_min"),
            "v2_max": g(row, "v2_max"), "duration_s": g(row, "duration_s"),
        ])
        guard let cat = categoryOf(.str("metric_day"), data), !(data.get("unit") ?? .null).isNull else { return nil }
        return envelope("metric_day", id: .str("\(PyStr.str(row["type_id"])):\(PyStr.str(row["day"]))"), category: cat,
                        day: row["day"], data: data)
    }

    static func mapHourly(_ row: RJ) -> RJ? {
        let data = dropNone([
            "type_id": row["type_id"], "day": row["day"], "hour": row["hour"], "hour_start_ms": row["hour_start_ms"],
            "unit": g(row, "unit"), "sum": g(row, "sum"), "avg": g(row, "avg"), "min": g(row, "min"), "max": g(row, "max"),
            "count": g(row, "count"),
        ])
        guard let cat = categoryOf(.str("metric_hour"), data), !(data.get("unit") ?? .null).isNull else { return nil }
        let hour = PyStr.int(row["hour"]) ?? 0
        return envelope("metric_hour", id: .str("\(PyStr.str(row["type_id"])):\(PyStr.str(row["day"])):\(hour)"),
                        category: cat, day: row["day"], data: data)
    }

    static func mapSample(_ row: RJ, nowMs: Int) -> RJ? {
        if g(row, "deleted").truthy { return nil }
        if let start = row["start_ms"].pyNumber, start < Double(nowMs - catalog.intradayMs) { return nil }
        let data = dropNone([
            "type_id": row["type_id"], "start_ms": row["start_ms"], "end_ms": row["end_ms"], "unit": row["unit"],
            "value": g(row, "value"), "value2": g(row, "value2"), "value3": g(row, "value3"),
            "category_value": g(row, "category_value"), "source_name": g(row, "source_name"),
        ])
        guard let cat = categoryOf(.str("sample"), data) else { return nil }
        return envelope("sample", id: row["id"], category: cat, day: row["local_day"], data: data)
    }

    static func mapAnalytics(_ row: RJ) -> RJ? {
        guard catalog.contains(catalog.analyticsMetrics, row["metric_id"]), pyEq(row["period_start"], row["period_end"]) else { return nil }
        let data = dropNone([
            "metric_id": row["metric_id"], "day": row["period_start"], "status": row["status"],
            "classification": row["classification"], "value": g(row, "value"), "value2": g(row, "value2"),
            "value3": g(row, "value3"), "unit": g(row, "unit"), "confidence": g(row, "confidence"), "coverage": g(row, "coverage"),
        ])
        return envelope("analytics_day", id: .str("\(PyStr.str(row["metric_id"])):\(PyStr.str(row["period_start"]))"),
                        category: "vitals", day: row["period_start"], data: data)
    }

    static func mapMedication(_ row: RJ) -> RJ {
        let data = dropNone([
            "name": row["name"], "generic_name": g(row, "generic_name"), "brand_name": g(row, "brand_name"),
            "strength": g(row, "strength"), "form": row["form"], "dose_quantity": row["dose_quantity"],
            "dose_unit": row["dose_unit"], "food_relation": g(row, "food_relation"), "instructions": g(row, "instructions"),
            "start_date": row["start_date"], "end_date": g(row, "end_date"), "status": row["status"],
            "is_prn": .bool(g(row, "is_prn").truthy),
        ])
        return envelope("medication", id: row["id"], category: "medicines", day: .null, data: data)
    }

    /// `json.loads(text or "[]")`.
    private static func jsonList(_ v: RJ) -> RJ {
        guard v.truthy, case .str(let s) = v else { return .arr([]) }
        return PartnerJSON.parse(s) ?? .arr([])
    }

    static func mapSchedule(_ row: RJ) -> RJ {
        let data = dropNone([
            "medication_id": row["medication_id"], "frequency_kind": row["frequency_kind"],
            "times": jsonList(g(row, "times_json")), "days": jsonList(g(row, "days_json")),
            "interval_hours": g(row, "interval_hours"), "anchor_time": g(row, "anchor_time"),
            "active_from_ms": row["active_from_ms"], "active_until_ms": g(row, "active_until_ms"),
        ])
        return envelope("medication_schedule", id: row["id"], category: "medicines", day: .null, data: data)
    }

    static func mapDoseLog(_ row: RJ, localDay: RJ) -> RJ {
        let data = dropNone([
            "medication_id": row["medication_id"], "schedule_id": g(row, "schedule_id"),
            "scheduled_at_ms": row["scheduled_at_ms"], "status": row["status"], "taken_at_ms": g(row, "taken_at_ms"),
            "dose_quantity": row["dose_quantity"], "dose_unit": row["dose_unit"], "note": g(row, "note"),
        ])
        return envelope("dose_log", id: row["id"], category: "medicines", day: localDay, data: data)
    }

    // MARK: report_overview

    static let abnormalFlags = ["low", "high", "critical_low", "critical_high", "abnormal"]

    private static func fieldOrder(_ state: RJ) -> Int {
        switch state.string {
        case "user"?: 0
        case "confirmed"?: 1
        case "suggested"?: 2
        default: 9
        }
    }

    private static func liveFields(_ fields: [RJ], _ key: String) -> [RJ] {
        fields.filter { pyEq($0["field_key"], .str(key)) && !pyEq(g($0, "state"), .str("rejected")) }
    }

    /// Best non-rejected field: user > confirmed > suggested, then highest confidence, then id.
    static func bestField(_ fields: [RJ], _ key: String) -> RJ? {
        pyStableSorted(liveFields(fields, key)) { f in
            let conf = g(f, "confidence").truthy ? g(f, "confidence").pyNumber ?? 0 : 0
            return [.int(fieldOrder(g(f, "state"))), .num(-conf), f["id"]]
        }.first
    }

    static func fieldValues(_ fields: [RJ], _ key: String) -> [RJ] {
        var out: [RJ] = []
        for f in pyStableSorted(liveFields(fields, key), key: { [$0["id"]] }) where !pyIn(f["value_text"], out) {
            out.append(f["value_text"])
        }
        return out
    }

    private static func orNone(_ list: [RJ]) -> RJ { list.isEmpty ? .null : .arr(list) }

    /// records row + children → report_overview (archived records are not shared).
    static func mapReportOverview(record: RJ, fields: [RJ], highlights: [RJ], observations: [RJ]) -> RJ? {
        if g(record, "archived").truthy { return nil }
        let doctor = bestField(fields, "doctor_name")
        let specialty = bestField(fields, "doctor_specialty")
        let facility = bestField(fields, "facility")
        var reportDate = g(record, "document_date")
        if reportDate.isNull {
            if let rd = bestField(fields, "report_date"), rd.object?.isEmpty == false {
                let text = rd["value_text"].truthy ? rd["value_text"] : .str("")
                if case .str(let s) = text, PyStr.isDay(s) { reportDate = rd["value_text"] } else { reportDate = .null }
            } else {
                reportDate = .null
            }
        }
        let live = pyStableSorted(highlights.filter { !g($0, "dismissed").truthy }) { h in
            [g(h, "position").truthy ? g(h, "position") : .int(0), h["id"]]
        }
        let summary = live.first { pyEq($0["section"], .str("summary")) }.map { $0["text"] } ?? .null
        let important = live.filter { pyEq($0["section"], .str("important")) }.map { $0["text"] }
        let recs = live.filter { pyEq($0["section"], .str("recommendations")) }.map { $0["text"] }
        let liveObs = observations.filter { !pyEq(g($0, "state"), .str("rejected")) }
        let results: [RJ] = pyStableSorted(liveObs) { o in [g(o, "raw_name").truthy ? g(o, "raw_name") : .str(""), o["id"]] }.map { o in
            dropNone([
                "name": o["raw_name"], "analyte_id": g(o, "analyte_id"), "value": o["value_text"], "value_num": g(o, "value_num"),
                "unit": g(o, "unit"), "ref_low": g(o, "ref_low"), "ref_high": g(o, "ref_high"), "ref_text": g(o, "ref_text"),
                "flag": g(o, "flag").truthy ? g(o, "flag") : .str("unknown"), "observed_date": g(o, "observed_date"),
            ])
        }
        let abnormal = results.filter { pyIn($0["flag"], abnormalFlags) }
        var meds: [RJ] = []
        for f in pyStableSorted(liveFields(fields, "medication"), key: { [$0["id"]] }) {
            var vj: RJ = .obj([:])
            if g(f, "value_json").truthy, case .str(let s) = f["value_json"] { vj = PartnerJSON.parse(s) ?? .obj([:]) }
            meds.append(dropNone([
                "name": g(vj, "name").truthy ? g(vj, "name") : f["value_text"], "strength": g(vj, "strength"),
                "dose": g(vj, "dose"), "frequency": g(vj, "frequency"), "duration": g(vj, "duration"),
            ]))
        }
        let data = dropNone([
            "title": record["title"], "record_type": record["record_type"], "category": record["category"],
            "report_date": reportDate, "doctor": doctor.map { $0["value_text"] }, "doctor_specialty": specialty.map { $0["value_text"] },
            "facility": facility.map { $0["value_text"] }, "summary": summary, "highlights": orNone(important),
            "results": orNone(results), "abnormal": orNone(abnormal), "diagnoses": orNone(fieldValues(fields, "diagnosis")),
            "medications": orNone(meds), "recommendations": orNone(recs),
        ])
        let day: RJ = reportDate.truthy ? reportDate : .str(PyStr.slice(PyStr.str(record["sort_date"]), 0, 10))
        return envelope("report_overview", id: record["id"], category: "report_overviews", day: day, data: data)
    }
}
