import Foundation

// Vector dispatch (`run_case`) of scripts/nutrients_reference.py: JSON-shaped inputs → the typed port → the
// reference's JSON-shaped outputs, so `NutrientsVectorTests` compares them with the shared vectors.

nonisolated extension NutrientsReference {
    /// Python `_is_number`: an int or finite float, never a bool.
    static func number(_ v: RJ) -> Double? {
        switch v {
        case .int(let i): return Double(i)
        case .num(let d): return d.isFinite ? d : nil
        default: return nil
        }
    }

    static func profile(_ v: RJ) -> Profile {
        Profile(age: number(v["age"]), sex: v["sex"].string, calorieGoal: number(v["calorie_goal"]))
    }

    static func rj(_ value: Double?) -> RJ { value.map(RJ.num) ?? .null }

    static func rj(_ lines: Lines) -> RJ {
        .obj([
            "key": .str(lines.key), "band": .str(lines.band), "sex": RJ.string(lines.sex), "style": RJ.string(lines.style),
            "unit": RJ.string(lines.unit), "recommended": rj(lines.recommended), "upper_limit": rj(lines.upperLimit),
            "limit": rj(lines.limit), "recommended_label": .str(lines.recommendedLabel), "limit_label": .str(lines.limitLabel),
            "upper_limit_scope": RJ.string(lines.upperLimitScope), "reference_recommended": rj(lines.referenceRecommended),
            "reference_limit": rj(lines.referenceLimit), "recommended_kind": RJ.string(lines.recommendedKind),
            "source_ids": .arr(lines.sourceIDs.map(RJ.str)), "error": RJ.string(lines.error),
        ])
    }

    static func rj(_ c: Conversion) -> RJ {
        .obj(["ok": .bool(c.ok), "amount": rj(c.amount), "unit": RJ.string(c.unit), "error": RJ.string(c.error)])
    }

    static func rj(_ e: SupplementEntry) -> RJ {
        .obj(["t_ms": .int(Int(e.tMs)), "nutrient_key": .str(e.nutrientKey), "value": .num(e.value), "medication_id": .str(e.medicationID)])
    }

    static func rj(_ r: LabelResult) -> RJ {
        .obj([
            "ok": .bool(r.ok), "error": RJ.string(r.error), "serving_units": r.servingUnits ?? .null,
            "items": .arr(r.items.map { .obj(["key": .str($0.key), "amount": .num($0.amount), "unit": .str($0.unit), "form": RJ.string($0.form)]) }),
            "rejected": .arr(r.rejected.map { .obj(["index": .int($0.index), "code": .str($0.code)]) }),
        ])
    }

    static func supplementEntries(_ v: RJ) -> [SupplementEntry] {
        (v.array ?? []).compactMap { e in
            guard let t = MR.int(e["t_ms"]), let key = e["nutrient_key"].string else { return nil }
            return SupplementEntry(tMs: Int64(t), nutrientKey: key, value: number(e["value"]) ?? .nan, medicationID: e["medication_id"].string ?? "")
        }
    }

    /// Mirrors `run_case(function, input)` of the reference.
    static func runCase(function: String, input inp: RJ) -> RJ {
        switch function {
        case "reference_lines":
            return rj(referenceLines(key: inp["key"].string ?? "", profile: profile(inp["profile"]), customGoal: number(inp["custom_goal"])))
        case "default_goal":
            let value = defaultGoal(key: inp["key"].string ?? "", profile: profile(inp["profile"]))
            return .obj(["value": rj(value), "value_int": defaultGoalInt(value).map(RJ.int) ?? .null])
        case "convert_amount":
            return rj(convertAmount(number(inp["value"]), unit: inp["unit"].string, key: inp["key"].string ?? "", form: inp["form"].string))
        case "interval_days":
            let s = inp["schedule"]
            let days = s.isNull ? 1 : intervalDays(frequencyKind: s["frequency_kind"].string,
                                                   days: (s["days"].array ?? []).compactMap { MR.int($0) },
                                                   intervalHours: MR.int(s["interval_hours"]))
            return .obj(["days": .int(days)])
        case "spread_supplements":
            var intervals: [String: Int] = [:]
            for (k, v) in inp["intervals"].object ?? [:] { intervals[k] = MR.int(v) ?? 1 }
            return .obj(["entries": .arr(spreadSupplementEntries(supplementEntries(inp["entries"]), intervals: intervals).map(rj))])
        case "supplement_entries":
            let rows = (inp["medication_nutrients"].array ?? []).map {
                MedicationNutrientInput(medicationID: $0["medication_id"].string ?? "", nutrientKey: $0["nutrient_key"].string ?? "",
                                        amountPerUnit: number($0["amount_per_unit"]) ?? 0)
            }
            let logs = (inp["dose_logs"].array ?? []).map {
                DoseInput(medicationID: $0["medication_id"].string ?? "", status: $0["status"].string ?? "",
                          takenAtMs: MR.int($0["taken_at_ms"]).map(Int64.init), doseQuantity: number($0["dose_quantity"]))
            }
            return .obj(["entries": .arr(supplementEntries(nutrients: rows, doseLogs: logs).map(rj))])
        case "day_totals":
            let food = (inp["food_entries"].array ?? []).map { e -> FoodInput in
                var values: [String: Double?] = [:]
                for (k, v) in e["nutrients"].object ?? [:] { values[k] = number(v) }
                return FoodInput(tMs: Int64(MR.int(e["t_ms"]) ?? 0), nutrients: values)
            }
            let totals = dayTotals(food: food, supplements: supplementEntries(inp["supplement_entries"]), day: inp["day"].string ?? "",
                                   zone: zone(inp["time_zone"].string ?? "UTC"))
            return .obj(["totals": .obj(totals.mapValues { .obj(["food": rj($0.food), "supplements": rj($0.supplements), "total": rj($0.total)]) })])
        case "logged_day_average":
            let entries = (inp["entries"].array ?? []).map { MetricsReference.Entry(tMs: Int64(MR.int($0["t_ms"]) ?? 0), value: number($0["value"])) }
            let result = loggedDayAverage(entries: entries, loggedDays: (inp["logged_days"].array ?? []).compactMap(\.string),
                                          startMs: Int64(MR.int(inp["interval"]["start_ms"]) ?? 0),
                                          endMs: Int64(MR.int(inp["interval"]["end_ms"]) ?? 0),
                                          zone: zone(inp["time_zone"].string ?? "UTC"), key: inp["key"].string)
            return .obj(["average": rj(result.average), "logged_days": .int(result.loggedDays)])
        case "parse_label_output":
            return rj(parseLabelOutput(inp["text"].string ?? ""))
        default:
            return .obj(["error": .str("unknown function \(function)")])
        }
    }
}
