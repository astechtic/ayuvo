import Foundation

/// §17 `AiQueryRewriter`: sends only the query text (never record content) and accepts a
/// `RecordQuery`-shaped JSON validated against the enumerations. Shown as removable chips.
nonisolated struct RecordsAIQueryRewriter: Sendable {
    var transport: RecordsAITransport = RecordsAppAITransport()

    static func shouldOffer(parsed: ParsedRecordQuery, hitCount: Int) -> Bool {
        parsed.terms.count >= 2 && hitCount == 0
    }

    static func prompt(query: String, today: String) -> String {
        """
        Turn this health-records search into JSON filters. Today is \(today).
        Return ONLY JSON: {"terms": ["words to full-text search"], "date_from": "yyyy-MM-dd or null", "date_to": "yyyy-MM-dd or null",
        "record_types": [one or more of \(RecordType.allCases.map(\.rawValue).joined(separator: ", "))], "flags": [abnormal, low, high, critical],
        "doctor": "name or null", "facility": "name or null"}
        Search: \(query)
        """
    }

    func rewrite(query: String, today: String, engine: RecordsAIEngine) async throws -> ParsedRecordQuery {
        let response = try await transport.complete(prompt: Self.prompt(query: query, today: today), images: [], engine: engine, maxOutputTokens: 400)
        return Self.validate(responseText: response)
    }

    static func validate(responseText: String) -> ParsedRecordQuery {
        var parsed = ParsedRecordQuery()
        guard let object = RR.lenientJSON(.str(responseText)) else { return parsed }
        parsed.terms = (object["terms"]?.array ?? []).compactMap(\.string).flatMap { RecordsSearchText.terms($0) }.filter { !RR.qStop.contains($0) }
        let from = object["date_from"]?.string.flatMap { YMD.iso($0) != nil ? $0 : nil }
        let to = object["date_to"]?.string.flatMap { YMD.iso($0) != nil ? $0 : nil }
        if from != nil || to != nil, (from ?? "0000") <= (to ?? "9999") {
            parsed.dateFrom = from
            parsed.dateTo = to
            parsed.chips.append(.dateRange(from: from, to: to, label: "\(from ?? "…") – \(to ?? "…")"))
        }
        let types = (object["record_types"]?.array ?? []).compactMap { $0.string.flatMap(RecordType.init(rawValue:)) }.filter { $0 != .other }
        if !types.isEmpty {
            parsed.recordTypes = types
            parsed.chips.append(.recordTypes(types, label: types.map(\.title).joined(separator: ", ")))
        }
        for flag in (object["flags"]?.array ?? []).compactMap({ $0.string.flatMap(RecordQueryFlag.init(rawValue:)) }) where !parsed.flags.contains(flag) {
            parsed.flags.append(flag)
            parsed.chips.append(.flag(flag, label: flag.title))
        }
        if let doctor = object["doctor"]?.string.map(RR.normText), !doctor.isEmpty {
            parsed.doctor = doctor
            parsed.chips.append(.doctor(doctor))
        }
        if let facility = object["facility"]?.string.map(RR.normText), !facility.isEmpty {
            parsed.facility = facility
            parsed.chips.append(.facility(facility))
        }
        return parsed
    }
}
