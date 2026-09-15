import CoreGraphics
import Foundation

// App-facing adapters over the reference port (`RR`): typed inputs/outputs for the pipeline, the
// review sheet and search. All logic lives in RecordsReference*.swift (vectors-tested).

/// Device date order for ambiguous numeric dates (§11); vectors pass it explicitly.
nonisolated enum RecordDateOrder: String, Sendable, Codable {
    case dmy
    case mdy

    static var device: RecordDateOrder {
        let format = DateFormatter.dateFormat(fromTemplate: "yMd", options: 0, locale: .current) ?? "d/M/y"
        guard let month = format.firstIndex(of: "M"), let day = format.firstIndex(of: "d") else { return .dmy }
        return month < day ? .mdy : .dmy
    }
}

nonisolated struct RecordClassification: Equatable, Sendable {
    var type: RecordType
    var confidence: Double
}

nonisolated enum RecordsClassifier {
    static func classify(pages: [String]) -> RecordClassification {
        let result = RR.classify(pages)
        return RecordClassification(
            type: RecordType(rawValue: result["record_type"].string ?? "") ?? .other,
            confidence: result["confidence"].double ?? 0
        )
    }
}

extension ExtractedField {
    /// A reference item `{key, value_text, value_json, confidence, source_page, evidence}`.
    init?(referenceItem item: RJ, method: RecordFieldMethod = .rules, pageOffset: Int = 0) {
        guard let key = RecordFieldKey(rawValue: item["key"].string ?? ""), let value = item["value_text"].string else { return nil }
        self.init(
            key: key,
            valueText: value,
            valueJSON: item["value_json"].isNull ? nil : item["value_json"].compactJSON,
            method: method,
            confidence: item["confidence"].double ?? 0,
            sourcePage: item["source_page"].double.map { Int($0) + pageOffset },
            evidence: item["evidence"].string
        )
    }

    var referenceItem: RJ {
        .obj([
            "key": .str(key.rawValue), "value_text": .str(valueText), "value_json": valueJSON.flatMap(RJ.parse) ?? .null,
            "method": .str(method.rawValue), "confidence": .number(confidence), "source_page": .number(sourcePage.map(Double.init)),
            "source_bbox": sourceBBox.map { .arr($0.map { RJ.number($0) }) } ?? .null, "evidence": .string(evidence),
        ])
    }
}

extension RecordField {
    /// `record_fields` row shape used by review / conflicts / highlights.
    func referenceRow(fromImage: Bool = false) -> RJ {
        .obj([
            "id": .str(id), "field_key": .str(key.rawValue), "key": .str(key.rawValue), "value_text": .str(valueText),
            "value_json": valueJSON.flatMap(RJ.parse) ?? .null, "method": .str(method.rawValue), "confidence": .number(confidence),
            "state": .str(state.rawValue), "source_page": .number(sourcePage.map(Double.init)), "evidence": .string(evidence),
            "from_image": .bool(fromImage),
        ])
    }
}

/// Stage "rules": §11 dates, §12 fields and §13 lab rows.
nonisolated enum RecordsRules {
    static func extract(pages: [String], recordType: RecordType, today: String, order: RecordDateOrder, pageOffset: Int = 0) -> [ExtractedField] {
        let type = recordType.rawValue
        let items = RR.extractDates(pages, type, today, order.rawValue)
            + RR.extractFields(pages, type)
            + RR.parseLabRows(pages, type)
        return items.compactMap { ExtractedField(referenceItem: $0, pageOffset: pageOffset) }
    }
}

nonisolated enum RecordsBoundaries {
    static let minimumPages = 3

    static func segments(pages: [String], today: String, order: RecordDateOrder) -> [RecordSplitSegment] {
        let result = RR.detectBoundaries(pages, today, order.rawValue)
        guard result["propose"].truthy else { return [] }
        return (result["segments"].array ?? []).map {
            RecordSplitSegment(
                pageStart: Int($0["page_start"].double ?? 0),
                pageEnd: Int($0["page_end"].double ?? 0),
                recordType: RecordType(rawValue: $0["record_type"].string ?? "") ?? .other,
                title: $0["title"].string ?? "",
                confidence: $0["confidence"].double ?? 0
            )
        }
    }
}

/// §15 highlights.
nonisolated enum RecordHighlightBuilder {
    static func build(fields: [RecordField]) -> [ExtractedHighlight] {
        RR.buildHighlights(fields.map { $0.referenceRow() }).compactMap { h in
            guard let section = RecordHighlightSection(rawValue: h["section"].string ?? ""), section != .summary else { return nil }
            return ExtractedHighlight(
                section: section, text: h["text"].string ?? "", method: .rules, provider: nil,
                sourcePage: h["source_page"].double.map { Int($0) }, confidence: h["confidence"].double ?? 0
            )
        }
    }
}

/// §15 review.
nonisolated enum RecordReviewEvaluator {
    struct Input {
        var record: HealthRecord
        var fields: [RecordField]
        var pendingSplit: Bool
        var pendingDuplicate: Bool
        var pages: [RecordPage] = []
    }

    /// Mean OCR confidence over OCR'd pages (nil when unknown).
    static func ocrConfidence(_ pages: [RecordPage]) -> Double? {
        let values = pages.filter { $0.textSource == .ocr }.compactMap(\.ocrConfidence)
        return values.isEmpty ? nil : values.reduce(0, +) / Double(values.count)
    }

    /// AI rows from a page that had no text were read from an image (§9.3 `from_image`).
    static func fromImage(_ field: RecordField, pages: [RecordPage]) -> Bool {
        guard field.method.isAI, let page = field.sourcePage else { return false }
        guard let stored = pages.first(where: { $0.pageIndex == page }) else { return false }
        return (stored.text ?? "").trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    }

    static func evaluate(_ input: Input) -> (status: RecordReviewStatus, reasons: [String]) {
        let record = input.record
        let recordJSON: RJ = .obj([
            "record_type": .str(record.recordType.rawValue), "type_confidence": .number(record.typeConfidence),
            "type_method": .string(record.typeMethod?.rawValue), "document_date": .string(record.documentDate),
            "processing_error": .string(record.processingError), "ocr_confidence": .number(ocrConfidence(input.pages)),
            "review_status": .str(record.reviewStatus.rawValue),
        ])
        let rows = input.fields.map { $0.referenceRow(fromImage: fromImage($0, pages: input.pages)) }
        let result = RR.reviewStatus(record: recordJSON, rows: rows, pendingSplit: input.pendingSplit, pendingDuplicate: input.pendingDuplicate)
        return (RecordReviewStatus(rawValue: result["status"].string ?? "") ?? .none, (result["reasons"].array ?? []).compactMap(\.string))
    }

    static func status(_ input: Input) -> RecordReviewStatus { evaluate(input).status }
    static func reasons(_ input: Input) -> [String] { evaluate(input).reasons }

    /// Conflict slots (§8.1) with their non-rejected rows.
    static func conflicts(_ fields: [RecordField]) -> [String: [RecordField]] {
        let slots = RR.conflicts(fields.map { $0.referenceRow() })
        var result: [String: [RecordField]] = [:]
        for slot in slots {
            let parts = slot.split(separator: ":")
            let key = String(parts[0])
            let referrer = parts.count > 1
            result[slot] = fields.filter { $0.key.rawValue == key && $0.state != .rejected && $0.isRoleReferrer == referrer }
        }
        return result
    }

    /// Review sheet rows: suggested key fields (§15 reason 4 keys), uncertain rows, and conflicts.
    static func reviewFields(_ fields: [RecordField]) -> [RecordField] {
        let conflicting = Set(conflicts(fields).values.flatMap { $0.map(\.id) })
        return fields.filter { field in
            guard field.state == .suggested else { return false }
            return field.confidence < 0.8 || RecordFieldKey.reviewKeyFields.contains(field.key) || conflicting.contains(field.id)
        }
    }
}

/// §15 near duplicates.
nonisolated enum RecordNearDuplicate {
    static func textSignature(text: String) -> String { RR.minhashSignature(text) }

    /// 9×8 greyscale `(299 R + 587 G + 114 B) / 1000` of the first page render / image, then dHash.
    static func dHash(image: CGImage) -> String? {
        var pixels = [UInt8](repeating: 255, count: 9 * 8 * 4)
        let drawn = pixels.withUnsafeMutableBytes { buffer -> Bool in
            guard let context = CGContext(
                data: buffer.baseAddress, width: 9, height: 8, bitsPerComponent: 8, bytesPerRow: 36,
                space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue
            ) else { return false }
            context.interpolationQuality = .high
            context.draw(image, in: CGRect(x: 0, y: 0, width: 9, height: 8))
            return true
        }
        guard drawn else { return nil }
        let gray: [[Double]] = (0..<8).map { row in
            (0..<9).map { column in
                let i = (row * 9 + column) * 4
                return Double((299 * Int(pixels[i]) + 587 * Int(pixels[i + 1]) + 114 * Int(pixels[i + 2])) / 1000)
            }
        }
        return RR.dhashHex(gray)
    }

    enum Match: Equatable {
        case phash(score: Double)
        case content(score: Double)
    }

    static func match(phashA: String?, signatureA: String?, phashB: String?, signatureB: String?) -> Match? {
        let result = RR.nearDuplicate(phashA: phashA, phashB: phashB, sigA: signatureA ?? "", sigB: signatureB ?? "")
        guard result["candidate"].truthy else { return nil }
        let score = result["score"].double ?? 0
        return result["reason"].string == "phash" ? .phash(score: score) : .content(score: score)
    }
}

/// Parsed universal-search query (§17) with removable chips.
nonisolated struct ParsedRecordQuery: Equatable, Sendable {
    nonisolated enum Chip: Hashable, Sendable, Identifiable {
        case dateRange(from: String?, to: String?, label: String)
        case recordTypes([RecordType], label: String)
        case flag(RecordQueryFlag, label: String)
        case doctor(String)
        case facility(String)
        case favorites
        case received
        case needsReview
        case archived
        /// Phase 3 analyte condition (§23); `label` is the folded query text.
        case analyte(RecordAnalyteCondition, label: String)

        var id: String {
            switch self {
            case .dateRange(let from, let to, _): "date:\(from ?? ""):\(to ?? "")"
            case .recordTypes(let types, _): "types:" + types.map(\.rawValue).joined(separator: ",")
            case .flag(let flag, _): "flag:\(flag.rawValue)"
            case .doctor(let name): "doctor:\(name)"
            case .facility(let name): "facility:\(name)"
            case .favorites: "favorites"
            case .received: "received"
            case .needsReview: "needs_review"
            case .archived: "archived"
            case .analyte(let c, _): "analyte:\(c.analyteID):\(c.flag ?? ""):\(c.op ?? ""):\(c.value.map { RecordsFold.compactNumber($0) } ?? "")"
            }
        }

        var label: String {
            switch self {
            case .dateRange(_, _, let label): label
            case .recordTypes(_, let label): label
            case .flag(_, let label): label
            case .doctor(let name): String(localized: "Dr \(name)")
            case .facility(let name): name
            case .favorites: String(localized: "Favorites")
            case .received: String(localized: "Received")
            case .needsReview: String(localized: "Needs review")
            case .archived: String(localized: "Archived")
            case .analyte(let c, let label):
                RecordAnalyteChipText.label(c, fallback: label)
            }
        }
    }

    var terms: [String] = []
    var dateFrom: String?
    var dateTo: String?
    var recordTypes: [RecordType] = []
    var flags: [RecordQueryFlag] = []
    var doctor: String?
    var facility: String?
    var favorites = false
    var received = false
    var needsReview = false
    var archived = false
    var analyteConditions: [RecordAnalyteCondition] = []
    var chips: [Chip] = []

    func applied(to base: RecordQuery, removing removed: Set<String> = []) -> RecordQuery {
        var query = base
        query.text = terms.joined(separator: " ")
        for chip in chips where !removed.contains(chip.id) {
            switch chip {
            case .dateRange(let from, let to, _):
                if let from { query.dateFrom = max(query.dateFrom ?? from, from) }
                if let to { query.dateTo = min(query.dateTo ?? to, to) }
            case .recordTypes(let types, _):
                query.recordTypes = query.recordTypes.isEmpty ? Set(types) : query.recordTypes.intersection(types)
            case .flag(let flag, _): query.flags.insert(flag)
            case .doctor(let name): query.doctor = name
            case .facility(let name): query.facility = name
            case .favorites: query.favoritesOnly = true
            case .received: query.receivedOnly = true
            case .needsReview: query.needsReviewOnly = true
            case .archived: query.archivedOnly = true
            case .analyte(let condition, _): query.analyteConditions.append(condition)
            }
        }
        return query
    }
}

nonisolated enum RecordQueryParser {
    static var stopWords: Set<String> { RR.qStop }

    static func parse(_ text: String, today: String, order: RecordDateOrder = .device, catalog: AnalyteCatalog = .shared) -> ParsedRecordQuery {
        let q = RR.parseQuery(text, today: today, dateOrder: order.rawValue, catalog: catalog)
        var parsed = ParsedRecordQuery()
        parsed.terms = (q["terms"].array ?? []).compactMap(\.string)
        parsed.dateFrom = q["date_from"].string
        parsed.dateTo = q["date_to"].string
        parsed.recordTypes = (q["record_types"].array ?? []).compactMap { $0.string.flatMap(RecordType.init(rawValue:)) }
        parsed.flags = (q["flags"].array ?? []).compactMap { $0.string.flatMap(RecordQueryFlag.init(rawValue:)) }
        parsed.doctor = q["doctor"].string
        parsed.facility = q["facility"].string
        parsed.favorites = q["favorites"].truthy
        parsed.needsReview = q["needs_review"].truthy
        parsed.archived = q["archived"].truthy
        parsed.received = q["source"].string == "received"
        let chips = q["chips"].array ?? []
        func texts(_ kind: String) -> [String] { chips.filter { $0["kind"].string == kind }.compactMap { $0["text"].string } }
        if parsed.dateFrom != nil || parsed.dateTo != nil {
            parsed.chips.append(.dateRange(from: parsed.dateFrom, to: parsed.dateTo, label: texts("date").joined(separator: ", ")))
        }
        if !parsed.recordTypes.isEmpty {
            parsed.chips.append(.recordTypes(parsed.recordTypes, label: texts("type").joined(separator: ", ")))
        }
        for flag in parsed.flags { parsed.chips.append(.flag(flag, label: flag.title)) }
        if let doctor = parsed.doctor { parsed.chips.append(.doctor(doctor)) }
        if let facility = parsed.facility { parsed.chips.append(.facility(facility)) }
        if parsed.favorites { parsed.chips.append(.favorites) }
        if parsed.needsReview { parsed.chips.append(.needsReview) }
        if parsed.archived { parsed.chips.append(.archived) }
        if parsed.received { parsed.chips.append(.received) }
        // §23: conditions (with chips) and bare aliases (Values group only; they stay FTS terms).
        let conditions = (q["analyte_conditions"].array ?? []).compactMap { c -> RecordAnalyteCondition? in
            guard let id = c["analyte_id"].string else { return nil }
            return RecordAnalyteCondition(analyteID: id, flag: c["flag"].string, op: c["op"].string, value: c["value"].double, unit: c["unit"].string,
                                          canonicalValue: c["canonical_value"].double, canonicalUnit: c["canonical_unit"].string)
        }
        for (condition, text) in zip(conditions, texts("analyte")) {
            parsed.chips.append(.analyte(condition, label: text))
        }
        let bare = (q["analytes"].array ?? []).compactMap(\.string).map { RecordAnalyteCondition(analyteID: $0) }
        parsed.analyteConditions = conditions + bare
        return parsed
    }
}

/// Chip text for an analyte condition: "Hemoglobin · Low", "HbA1c > 7 %".
nonisolated enum RecordAnalyteChipText {
    static func label(_ c: RecordAnalyteCondition, catalog: AnalyteCatalog = .shared, fallback: String) -> String {
        let name = catalog.analyte(id: c.analyteID)?.displayName ?? fallback
        if let flag = c.flag {
            let flagText = RecordQueryFlag(rawValue: flag)?.title ?? (flag == "normal" ? String(localized: "Normal") : flag)
            return "\(name) · \(flagText)"
        }
        if let op = c.op, let value = c.value {
            return "\(name) \(op) \(RecordsFold.compactNumber(value))" + (c.unit.map { " \($0)" } ?? "")
        }
        return name
    }
}
