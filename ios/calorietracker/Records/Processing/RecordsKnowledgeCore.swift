import Foundation

// Phase 3 typed adapters (docs/health-records.md §19–§23) over the reference port in
// RecordsReferenceKnowledge.swift: catalog, promotion rows, trends, entities, relations and the query
// analyte conditions. Pure and nonisolated.

// MARK: - Catalog

nonisolated struct AnalyteUnit: Hashable, Sendable {
    var unit: String
    var factor: Double
    var offset: Double
}

nonisolated struct AnalyteDefinition: Identifiable, Hashable, Sendable {
    var id: String
    var displayName: String
    var aliases: [String]
    var panels: [String]
    var category: String?
    var canonicalUnit: String?
    var units: [AnalyteUnit]
    var kind: String
    var decimals: Int?
}

/// `shared/records/analytes.json`, loaded once. `index` maps each alias key (`analyte_words` joined) to
/// the analyte ids listing it, in file order (reference `analyte_catalog`).
nonisolated final class AnalyteCatalog: Sendable {
    let analytes: [AnalyteDefinition]
    let entries: [RJ]
    private let byID: [String: Int]
    private let definitions: [String: AnalyteDefinition]
    let index: [String: [String]]

    init(entries: [RJ]) {
        self.entries = entries
        var byID: [String: Int] = [:]
        var index: [String: [String]] = [:]
        var analytes: [AnalyteDefinition] = []
        for (position, e) in entries.enumerated() {
            guard let id = e["id"].string else { continue }
            byID[id] = position
            for alias in (e["aliases"].array ?? []).compactMap(\.string) {
                let key = RR.analyteWords(alias).joined(separator: " ")
                if !(index[key] ?? []).contains(id) { index[key, default: []].append(id) }
            }
            analytes.append(AnalyteDefinition(
                id: id,
                displayName: e["display_name"].string ?? id,
                aliases: (e["aliases"].array ?? []).compactMap(\.string),
                panels: (e["panels"].array ?? []).compactMap(\.string),
                category: e["category"].string,
                canonicalUnit: e["canonical_unit"].string,
                units: (e["units"].array ?? []).compactMap { u in
                    u["unit"].string.map { AnalyteUnit(unit: $0, factor: u["factor"].double ?? 1, offset: u["offset"].double ?? 0) }
                },
                kind: e["kind"].string ?? "numeric",
                decimals: e["decimals"].double.map { Int($0) }
            ))
        }
        self.byID = byID
        self.index = index
        self.analytes = analytes
        self.definitions = Dictionary(analytes.map { ($0.id, $0) }, uniquingKeysWith: { a, _ in a })
    }

    convenience init?(data: Data) {
        guard let root = RJ.parse(String(decoding: data, as: UTF8.self)), let list = root["analytes"].array else { return nil }
        self.init(entries: list)
    }

    func hasEntry(_ id: String) -> Bool { byID[id] != nil }
    func entry(_ id: String) -> RJ { byID[id].map { entries[$0] } ?? .null }
    func analyte(id: String) -> AnalyteDefinition? { definitions[id] }

    static let shared: AnalyteCatalog = {
        for bundle in [Bundle.main, Bundle(for: BundleMarker.self)] {
            if let url = bundle.url(forResource: "analytes", withExtension: "json"),
               let data = try? Data(contentsOf: url),
               let catalog = AnalyteCatalog(data: data) {
                return catalog
            }
        }
        return AnalyteCatalog(entries: [])
    }()

    private final class BundleMarker {}

    /// Picker search: display names and aliases containing every folded query word as a word prefix.
    func search(_ text: String, limit: Int = 80) -> [AnalyteDefinition] {
        let wanted = RR.normText(text).split(separator: " ").map(String.init)
        let sorted = analytes.sorted { $0.displayName.localizedCaseInsensitiveCompare($1.displayName) == .orderedAscending }
        guard !wanted.isEmpty else { return Array(sorted.prefix(limit)) }
        var exact: [AnalyteDefinition] = []
        var aliasOnly: [AnalyteDefinition] = []
        for analyte in sorted {
            if RecordsDatabase.wordsPrefixMatch(words: RR.normText(analyte.displayName).split(separator: " ").map(String.init), wanted: wanted) {
                exact.append(analyte)
            } else if analyte.aliases.contains(where: { RecordsDatabase.wordsPrefixMatch(words: RR.normText($0).split(separator: " ").map(String.init), wanted: wanted) }) {
                aliasOnly.append(analyte)
            }
        }
        return Array((exact + aliasOnly).prefix(limit))
    }
}

// MARK: - Row conversions

extension RecordObservation {
    /// Reference observation dict (`OBSERVATION_KEYS`).
    var referenceRow: RJ {
        .obj([
            "id": .str(id), "record_id": .str(recordID), "field_id": .string(fieldID), "analyte_id": .string(analyteID),
            "analyte_method": .string(analyteMethod?.rawValue), "raw_name": .str(rawName), "value_num": .number(valueNum),
            "value_text": .str(valueText), "unit": .string(unit), "canonical_value": .number(canonicalValue),
            "canonical_unit": .string(canonicalUnit), "ref_low": .number(refLow), "ref_high": .number(refHigh),
            "ref_text": .string(refText), "flag": .str(flag.rawValue), "observed_date": .string(observedDate),
            "observed_date_method": .string(observedDateMethod?.rawValue), "method": .str(method.rawValue),
            "confidence": .number(confidence), "state": .str(state.rawValue), "source_page": .number(sourcePage.map(Double.init)),
            "source_bbox": .string(sourceBBox), "evidence": .string(evidence), "excluded_from_trends": .int(excludedFromTrends ? 1 : 0),
            "created_ms": .int(Int(createdMs)), "updated_ms": .int(Int(updatedMs)),
        ])
    }

    init(referenceRow o: RJ) {
        self.init(
            id: o["id"].string ?? "", recordID: o["record_id"].string ?? "", fieldID: o["field_id"].string,
            analyteID: o["analyte_id"].string, analyteMethod: o["analyte_method"].string.flatMap(RecordAnalyteMethod.init(rawValue:)),
            rawName: o["raw_name"].string ?? "", valueNum: o["value_num"].double, valueText: o["value_text"].string ?? "",
            unit: o["unit"].string, canonicalValue: o["canonical_value"].double, canonicalUnit: o["canonical_unit"].string,
            refLow: o["ref_low"].double, refHigh: o["ref_high"].double, refText: o["ref_text"].string,
            flag: RecordResultFlag(rawValue: o["flag"].string ?? "") ?? .unknown, observedDate: o["observed_date"].string,
            observedDateMethod: o["observed_date_method"].string.flatMap(RecordObservedDateMethod.init(rawValue:)),
            method: RecordFieldMethod(rawValue: o["method"].string ?? "") ?? .rules, confidence: o["confidence"].double ?? 0,
            state: RecordFieldState(rawValue: o["state"].string ?? "") ?? .suggested, sourcePage: o["source_page"].double.map { Int($0) },
            sourceBBox: o["source_bbox"].string ?? (o["source_bbox"].array.map { RecordsDatabase.bboxJSON($0.compactMap(\.double)) }),
            evidence: o["evidence"].string, excludedFromTrends: o["excluded_from_trends"].truthy,
            createdMs: Int64(o["created_ms"].double ?? 0), updatedMs: Int64(o["updated_ms"].double ?? 0)
        )
    }
}

extension RecordField {
    /// Field row for Phase 3 reference functions (bbox included).
    var knowledgeRow: RJ {
        guard case .obj(var o) = referenceRow() else { return .null }
        o["source_bbox"] = .string(sourceBBox)
        return .obj(o)
    }
}

// MARK: - Trends (§21)

nonisolated enum RecordTrendBuilder {
    static func series(_ items: [RecordObservationItem], analyteID: String, catalog: AnalyteCatalog = .shared) -> [RecordTrendSeries] {
        let trend = RR.trendSeriesRef(items.map(\.observation.referenceRow), analyteID: analyteID, catalog)
        let byID = Dictionary(items.map { ($0.observation.id, $0.observation) }, uniquingKeysWith: { a, _ in a })
        return (trend["series"].array ?? []).map { s in
            let unit = s["unit"].string ?? ""
            let points = (s["points"].array ?? []).map { p -> RecordTrendPoint in
                let ids = (p["observation_ids"].array ?? []).compactMap(\.string)
                let first = ids.first.flatMap { byID[$0] }
                return RecordTrendPoint(
                    observationIDs: ids, recordIDs: (p["record_ids"].array ?? []).compactMap(\.string), date: p["date"].string ?? "",
                    value: p["value"].double ?? 0, unit: unit, flag: RecordResultFlag(rawValue: p["flag"].string ?? "") ?? .unknown,
                    refLow: p["ref_low"].double, refHigh: p["ref_high"].double, refText: first?.refText, sourcePage: first?.sourcePage,
                    sourceBBox: first?.sourceBBox, evidence: first?.evidence, isConfirmed: ids.contains { byID[$0]?.isConfirmed == true }
                )
            }
            return RecordTrendSeries(unit: unit, isCanonical: s["convertible"].truthy, points: points, bandLow: s["band"]["low"].double, bandHigh: s["band"]["high"].double)
        }
    }

    static func format(_ value: Double, decimals: Int?) -> String {
        RR.formatValue(value, decimals ?? 2)
    }

    /// §21 mini trend as of this observation's report (≥ 2 points).
    static func miniTrend(analyteID: String, observationID: String, items: [RecordObservationItem], catalog: AnalyteCatalog = .shared) -> RecordMiniTrend? {
        let trend = RR.trendSeriesRef(items.map(\.observation.referenceRow), analyteID: analyteID, catalog)
        let mini = RR.miniTrendRef(trend, observationID: observationID, catalog)
        guard mini["show"].truthy, let text = mini["text"].string else { return nil }
        var change: String?
        if let delta = mini["change"]["delta"].double, let since = mini["change"]["since_date"].string {
            let decimals = catalog.analyte(id: analyteID)?.decimals ?? 2
            let sign = delta > 0 ? "+" : (delta < 0 ? "−" : "")
            let unit = mini["unit"].string.map { $0.isEmpty ? "" : " \($0)" } ?? ""
            let date = RecordDates.date(fromDay: since)?.formatted(.dateTime.month(.abbreviated).day()) ?? since
            change = String(localized: "\(sign)\(String(format: "%.\(decimals)f", abs(delta)))\(unit) since \(date)")
        }
        return RecordMiniTrend(analyteID: analyteID, text: text, change: change, pointCount: (mini["values"].array ?? []).count)
    }
}

// MARK: - Edits (§24)

nonisolated enum RecordObservationEditing {
    /// Reference `edit_observation` patch from the typed edit (only changed keys present).
    static func patch(_ edit: RecordObservationEdit, nowMs: Int64) -> RJ {
        var p: [String: RJ] = ["now_ms": .int(Int(nowMs))]
        if let value = edit.valueText { p["value"] = .str(value) }
        if let unit = edit.unit { p["unit"] = .string(unit) }
        if let refText = edit.refText { p["ref_text"] = .string(refText) }
        if let analyte = edit.analyteID { p["analyte_id"] = .string(analyte) }
        if let date = edit.observedDate { p["observed_date"] = .str(date) }
        if let excluded = edit.excludedFromTrends { p["excluded_from_trends"] = .bool(excluded) }
        return .obj(p)
    }
}

// MARK: - Relations (§22)

/// What the suggester needs to know about one record (reference `suggest_relations` dict).
nonisolated struct RecordRelationProfile: Hashable, Sendable {
    var id: String
    var recordType: RecordType
    var sortDate: String
    var archived: Bool
    var splitParent: Bool
    var reportName: String?
    var panels: [String]
    var analytes: [String]
    var doctors: [String]
    var facilities: [String]
    var followUpDates: [String]

    var referenceRow: RJ {
        .obj([
            "id": .str(id), "record_type": .str(recordType.rawValue), "sort_date": .str(sortDate), "archived": .bool(archived),
            "split_parent": .bool(splitParent), "panels": .arr(panels.map(RJ.str)), "report_name": .string(reportName),
            "analytes": .arr(analytes.map(RJ.str)), "doctors": .arr(doctors.map(RJ.str)), "facilities": .arr(facilities.map(RJ.str)),
            "follow_up_dates": .arr(followUpDates.map(RJ.str)),
        ])
    }
}

nonisolated enum RecordRelationSuggester {
    static let windowDays = 180
    static let maxSuggestions = 5

    static func suggest(for record: RecordRelationProfile, candidates: [RecordRelationProfile], existingLinks: [RecordLink], today: String) -> [RecordRelationSuggestion] {
        let links: [RJ] = existingLinks.map { .obj(["a_id": .str($0.aID), "b_id": .str($0.bID), "kind": .str($0.kind.rawValue), "origin": .str($0.origin.rawValue), "status": .str($0.status.rawValue)]) }
        let result = RR.suggestRelations(record: record.referenceRow, candidates: candidates.map(\.referenceRow), today: today, existingLinks: links)
        return (result["links"].array ?? []).compactMap { l in
            guard let a = l["a_id"].string, let b = l["b_id"].string, let kind = RecordLinkKind(rawValue: l["kind"].string ?? "") else { return nil }
            return RecordRelationSuggestion(otherID: a == record.id ? b : a, kind: kind, score: l["score"].double ?? 0, reasons: (l["reasons"].array ?? []).compactMap(\.string))
        }
    }
}

// MARK: - Query analyte conditions (§23)

nonisolated enum RecordAnalyteQueryParser {
    /// Condition match on an observation: flags from the stored flag, comparisons on canonical values.
    static func matches(_ condition: RecordAnalyteCondition, observation: RecordObservation, catalog: AnalyteCatalog = .shared) -> Bool {
        guard observation.analyteID == condition.analyteID, observation.state != .rejected else { return false }
        if let flag = condition.flag {
            switch flag {
            case "low": return [.low, .criticalLow].contains(observation.flag)
            case "high": return [.high, .criticalHigh].contains(observation.flag)
            case "critical": return [.criticalLow, .criticalHigh].contains(observation.flag)
            case "normal": return observation.flag == .normal
            default: return observation.flag.isAbnormal
            }
        }
        guard let op = condition.op else { return true }
        let target: Double?
        if let canonical = condition.canonicalValue {
            target = canonical
        } else if let raw = condition.value {
            target = condition.unit == nil ? raw : RR.convertUnit(condition.analyteID, raw, condition.unit, catalog)["canonical_value"].double
        } else {
            target = nil
        }
        guard let target, let value = observation.canonicalValue else { return false }
        switch op {
        case ">": return value > target
        case ">=": return value >= target
        case "<": return value < target
        default: return value <= target
        }
    }
}
