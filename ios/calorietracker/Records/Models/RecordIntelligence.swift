import Foundation

// Phase 2 "Intelligence" enumerations and rows (docs/health-records.md §8, §9, §15, §16).
// Every raw value is the exact lowercase string stored in SQLite.

nonisolated enum RecordFieldKey: String, CaseIterable, Codable, Sendable, Identifiable {
    case doctorName = "doctor_name"
    case doctorSpecialty = "doctor_specialty"
    case facility
    case department
    case patientName = "patient_name"
    case patientAge = "patient_age"
    case patientSex = "patient_sex"
    case reportName = "report_name"
    case testResult = "test_result"
    case diagnosis
    case symptom
    case medication
    case procedure
    case recommendation
    case followUpDate = "follow_up_date"
    case visitDate = "visit_date"
    case collectionDate = "collection_date"
    case reportDate = "report_date"
    case prescriptionDate = "prescription_date"
    case admissionDate = "admission_date"
    case dischargeDate = "discharge_date"
    case documentTime = "document_time"
    case location

    var id: String { rawValue }

    /// Keys that may legitimately hold several values (§8.1); every other key is single-valued.
    static let multiValued: Set<RecordFieldKey> = [.testResult, .diagnosis, .symptom, .medication, .procedure, .recommendation]

    static let dateKeys: Set<RecordFieldKey> = [.followUpDate, .visitDate, .collectionDate, .reportDate, .prescriptionDate, .admissionDate, .dischargeDate]

    /// §8.1 document-date priority.
    static let documentDatePriority: [RecordFieldKey] = [.reportDate, .collectionDate, .prescriptionDate, .dischargeDate, .visitDate]

    /// §15 key fields for review.
    static let reviewKeyFields: Set<RecordFieldKey> = Set<RecordFieldKey>([.reportName, .doctorName, .facility]).union(dateKeys)

    var isMultiValued: Bool { Self.multiValued.contains(self) }
    var isDate: Bool { Self.dateKeys.contains(self) }

    var title: String {
        switch self {
        case .doctorName: String(localized: "Doctor")
        case .doctorSpecialty: String(localized: "Specialty")
        case .facility: String(localized: "Hospital or lab")
        case .department: String(localized: "Department")
        case .patientName: String(localized: "Patient")
        case .patientAge: String(localized: "Age")
        case .patientSex: String(localized: "Sex")
        case .reportName: String(localized: "Report")
        case .testResult: String(localized: "Test result")
        case .diagnosis: String(localized: "Diagnosis")
        case .symptom: String(localized: "Symptom")
        case .medication: String(localized: "Medication")
        case .procedure: String(localized: "Procedure")
        case .recommendation: String(localized: "Recommendation")
        case .followUpDate: String(localized: "Follow-up date")
        case .visitDate: String(localized: "Visit date")
        case .collectionDate: String(localized: "Collection date")
        case .reportDate: String(localized: "Report date")
        case .prescriptionDate: String(localized: "Prescription date")
        case .admissionDate: String(localized: "Admission date")
        case .dischargeDate: String(localized: "Discharge date")
        case .documentTime: String(localized: "Time")
        case .location: String(localized: "Location")
        }
    }

    /// Detail-screen grouping of "Extracted information".
    var group: RecordFieldGroup {
        switch self {
        case .doctorName, .doctorSpecialty, .facility, .department: .care
        case .patientName, .patientAge, .patientSex: .patient
        case .reportName, .diagnosis, .symptom, .procedure, .location: .clinical
        case .testResult: .results
        case .medication: .medications
        case .recommendation: .recommendations
        case .followUpDate, .visitDate, .collectionDate, .reportDate, .prescriptionDate, .admissionDate, .dischargeDate, .documentTime: .dates
        }
    }
}

nonisolated enum RecordFieldGroup: String, CaseIterable, Sendable, Identifiable {
    case dates, care, patient, clinical, results, medications, recommendations

    var id: String { rawValue }

    var title: String {
        switch self {
        case .dates: String(localized: "Dates")
        case .care: String(localized: "Doctor & facility")
        case .patient: String(localized: "Patient")
        case .clinical: String(localized: "Clinical details")
        case .results: String(localized: "Test results")
        case .medications: String(localized: "Medications")
        case .recommendations: String(localized: "Recommendations")
        }
    }
}

/// `method` of fields, highlights and `records.type_method` (§8).
nonisolated enum RecordFieldMethod: String, CaseIterable, Codable, Sendable {
    case fileMetadata = "file_metadata"
    case pdfText = "pdf_text"
    case ocr
    case rules
    case aiLocal = "ai_local"
    case aiCloud = "ai_cloud"
    case user

    var isAI: Bool { self == .aiLocal || self == .aiCloud }

    var badgeTitle: String {
        switch self {
        case .fileMetadata: String(localized: "File details")
        case .pdfText, .ocr, .rules: String(localized: "Rules")
        case .aiLocal: String(localized: "On-device AI")
        case .aiCloud: String(localized: "Online AI")
        case .user: String(localized: "You")
        }
    }

    var dateMethod: RecordDateMethod {
        switch self {
        case .fileMetadata: .fileMetadata
        case .pdfText: .pdfText
        case .ocr: .ocr
        case .rules: .rules
        case .aiLocal: .aiLocal
        case .aiCloud: .aiCloud
        case .user: .user
        }
    }
}

nonisolated enum RecordFieldState: String, CaseIterable, Codable, Sendable {
    case suggested
    case confirmed
    case rejected
    case user

    /// Rows a new extraction never touches (§8.1 step 1).
    var isLocked: Bool { self != .suggested }
}

nonisolated enum RecordResultFlag: String, CaseIterable, Codable, Sendable {
    case low
    case high
    case criticalLow = "critical_low"
    case criticalHigh = "critical_high"
    case normal
    case abnormal
    case unknown

    var isAbnormal: Bool {
        switch self {
        case .low, .high, .criticalLow, .criticalHigh, .abnormal: true
        case .normal, .unknown: false
        }
    }

    var symbol: String? {
        switch self {
        case .low, .criticalLow: "↓"
        case .high, .criticalHigh: "↑"
        case .abnormal: "*"
        case .normal, .unknown: nil
        }
    }
}

nonisolated enum RecordHighlightSection: String, CaseIterable, Codable, Sendable {
    case important
    case medications
    case recommendations
    case summary
}

nonisolated enum RecordAIModeUsed: String, CaseIterable, Codable, Sendable {
    case none
    case local
    case cloud
}

nonisolated enum RecordProcessingStage: String, CaseIterable, Codable, Sendable {
    case text
    case classify
    case boundaries
    case rules
    case ai
    case validate
    case highlights
    case review
    case nearDuplicate = "near_duplicate"
    /// Phase 3: promotion + analyte mapping (§19).
    case observations
    /// Phase 3: relation suggestions (§22).
    case relations
    case index
    case done

    var next: RecordProcessingStage {
        let all = Self.allCases
        guard let index = all.firstIndex(of: self), index + 1 < all.count else { return .done }
        return all[index + 1]
    }

    /// `processing_status` shown while this stage runs (§9).
    var runningStatus: RecordProcessingStatus {
        switch self {
        case .text: .extractingText
        case .done: .ready
        default: .analyzing
        }
    }
}

nonisolated enum RecordDuplicateReason: String, CaseIterable, Codable, Sendable {
    case checksum
    case phash
    case content

    var title: String {
        switch self {
        case .checksum: String(localized: "Identical file")
        case .phash: String(localized: "Looks the same")
        case .content: String(localized: "Same content")
        }
    }
}

nonisolated enum RecordDuplicateResolution: String, CaseIterable, Codable, Sendable {
    case pending
    case keepBoth = "keep_both"
    case replaced
    case merged
    case cancelled
}

nonisolated enum RecordSplitStatus: String, CaseIterable, Codable, Sendable {
    case pending
    case accepted
    case rejected
}

/// `processing_error` values (§9).
nonisolated enum RecordProcessingError: String, CaseIterable, Sendable {
    case textUnavailable = "text_unavailable"
    case ocrFailed = "ocr_failed"
    case aiFailed = "ai_failed"
    case aiUnavailable = "ai_unavailable"
    case protectedPDF = "protected_pdf"
    case unsupported
}

/// `healthRecordsAiMode` (§6b, §16). Unset is represented by `nil`.
nonisolated enum RecordsAIMode: String, CaseIterable, Codable, Sendable, Identifiable {
    case local
    case cloud
    case ask
    case off

    static let storageKey = "healthRecordsAiMode"

    var id: String { rawValue }

    var title: String {
        switch self {
        case .local: String(localized: "Local AI")
        case .cloud: String(localized: "Cloud AI")
        case .ask: String(localized: "Ask me when needed")
        case .off: String(localized: "Skip AI for now")
        }
    }

    var subtitle: String {
        switch self {
        case .local: String(localized: "Uses the AI on this iPhone. Nothing is sent online.")
        case .cloud: String(localized: "Sends page text to your AI provider when details are missing.")
        case .ask: String(localized: "Ayuvo asks before using AI on a record.")
        case .off: String(localized: "Details are found with on-device rules only.")
        }
    }

    var systemImage: String {
        switch self {
        case .local: "iphone"
        case .cloud: "cloud"
        case .ask: "questionmark.bubble"
        case .off: "nosign"
        }
    }

    static func stored(in defaults: UserDefaults = .standard) -> RecordsAIMode? {
        defaults.string(forKey: storageKey).flatMap(RecordsAIMode.init(rawValue:))
    }
}

// MARK: - Rows

/// One `record_fields` row.
nonisolated struct RecordField: Identifiable, Hashable, Sendable {
    var id: String
    var recordID: String
    var key: RecordFieldKey
    var valueText: String
    var valueJSON: String?
    var method: RecordFieldMethod
    var confidence: Double
    var state: RecordFieldState
    var sourcePage: Int?
    var sourceBBox: String?
    var evidence: String?
    var createdMs: Int64
    var updatedMs: Int64

    var testResult: RecordTestResultValue? {
        guard key == .testResult, let valueJSON else { return nil }
        return RecordTestResultValue.decode(valueJSON)
    }

    var medicationValue: RecordMedicationValue? {
        guard key == .medication, let valueJSON else { return nil }
        return RecordMedicationValue.decode(valueJSON)
    }

    /// `[x, y, w, h]` normalized top-left, when stored.
    var bbox: [Double]? {
        guard let sourceBBox, let data = sourceBBox.data(using: .utf8),
              let values = try? JSONSerialization.jsonObject(with: data) as? [NSNumber], values.count == 4
        else { return nil }
        return values.map(\.doubleValue)
    }

    var isRoleReferrer: Bool {
        guard key == .doctorName, let valueJSON, let data = valueJSON.data(using: .utf8),
              let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any]
        else { return false }
        return (object["role"] as? String) == "referrer"
    }

    /// Human text for rows and review.
    var displayValue: String {
        if let result = testResult {
            var parts = [result.value ?? valueText]
            if let unit = result.unit, !unit.isEmpty { parts.append(unit) }
            if let symbol = result.flag?.symbol { parts.append(symbol) }
            return parts.joined(separator: " ")
        }
        if let medication = medicationValue {
            return [medication.name ?? valueText, medication.strength, medication.frequency, medication.duration]
                .compactMap { $0 }
                .filter { !$0.isEmpty }
                .joined(separator: " · ")
        }
        if key.isDate, let date = RecordDates.date(fromDay: valueText) {
            return date.formatted(date: .abbreviated, time: .omitted)
        }
        return valueText
    }
}

nonisolated struct RecordTestResultValue: Codable, Hashable, Sendable {
    var name: String?
    var value: String?
    var valueNum: Double?
    var unit: String?
    var refText: String?
    var refLow: Double?
    var refHigh: Double?
    var flag: RecordResultFlag?

    enum CodingKeys: String, CodingKey {
        case name, value, unit, flag
        case valueNum = "value_num"
        case refText = "ref_text"
        case refLow = "ref_low"
        case refHigh = "ref_high"
    }

    static func decode(_ json: String) -> RecordTestResultValue? {
        guard let data = json.data(using: .utf8) else { return nil }
        return try? JSONDecoder().decode(RecordTestResultValue.self, from: data)
    }
}

nonisolated struct RecordMedicationValue: Codable, Hashable, Sendable {
    var name: String?
    var strength: String?
    var form: String?
    var dose: String?
    var frequency: String?
    var duration: String?
    var instructions: String?

    static func decode(_ json: String) -> RecordMedicationValue? {
        guard let data = json.data(using: .utf8) else { return nil }
        return try? JSONDecoder().decode(RecordMedicationValue.self, from: data)
    }
}

/// One `record_highlights` row.
nonisolated struct RecordHighlight: Identifiable, Hashable, Sendable {
    var id: String
    var recordID: String
    var section: RecordHighlightSection
    var text: String
    var method: RecordFieldMethod
    var provider: String?
    var fieldID: String?
    var sourcePage: Int?
    var confidence: Double
    var dismissed: Bool
    var position: Int
    var createdMs: Int64
}

/// One `processing_jobs` row.
nonisolated struct RecordProcessingJob: Hashable, Sendable {
    var recordID: String
    var stage: RecordProcessingStage
    var attempts: Int
    var nextAttemptMs: Int64
    var lastError: String?
    var requestedMode: String?
    var awaitingConsent: Bool
    var updatedMs: Int64
}

/// One `duplicate_candidates` row.
nonisolated struct RecordDuplicateCandidate: Hashable, Sendable, Identifiable {
    var recordID: String
    var existingID: String
    var reason: RecordDuplicateReason
    var score: Double
    var resolution: RecordDuplicateResolution
    var createdMs: Int64

    var id: String { recordID + "|" + existingID }
}

nonisolated struct RecordSplitSegment: Codable, Hashable, Sendable, Identifiable {
    var pageStart: Int
    var pageEnd: Int
    var recordType: RecordType
    var title: String
    var confidence: Double

    var id: String { "\(pageStart)-\(pageEnd)" }

    enum CodingKeys: String, CodingKey {
        case pageStart = "page_start"
        case pageEnd = "page_end"
        case recordType = "record_type"
        case title
        case confidence
    }

    init(pageStart: Int, pageEnd: Int, recordType: RecordType, title: String, confidence: Double) {
        self.pageStart = pageStart
        self.pageEnd = pageEnd
        self.recordType = recordType
        self.title = title
        self.confidence = confidence
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        pageStart = try container.decode(Int.self, forKey: .pageStart)
        pageEnd = try container.decode(Int.self, forKey: .pageEnd)
        recordType = RecordType(rawValue: (try? container.decode(String.self, forKey: .recordType)) ?? "") ?? .other
        title = (try? container.decode(String.self, forKey: .title)) ?? ""
        confidence = (try? container.decode(Double.self, forKey: .confidence)) ?? 0
    }
}

/// One `split_proposals` row.
nonisolated struct RecordSplitProposal: Hashable, Sendable {
    var recordID: String
    var segments: [RecordSplitSegment]
    var status: RecordSplitStatus
    var createdMs: Int64
    var updatedMs: Int64

    static func encodeSegments(_ segments: [RecordSplitSegment]) -> String {
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.sortedKeys]
        return (try? encoder.encode(segments)).flatMap { String(data: $0, encoding: .utf8) } ?? "[]"
    }

    static func decodeSegments(_ json: String) -> [RecordSplitSegment] {
        guard let data = json.data(using: .utf8) else { return [] }
        return (try? JSONDecoder().decode([RecordSplitSegment].self, from: data)) ?? []
    }
}

// MARK: - Extraction input

/// One value found by rules or AI, before `applyExtraction` (§8.1).
nonisolated struct ExtractedField: Hashable, Sendable {
    var key: RecordFieldKey
    var valueText: String
    /// Compact JSON object text (sorted keys) or nil.
    var valueJSON: String?
    var method: RecordFieldMethod
    var confidence: Double
    var sourcePage: Int?
    var sourceBBox: [Double]?
    var evidence: String?

    init(
        key: RecordFieldKey,
        valueText: String,
        valueJSON: String? = nil,
        method: RecordFieldMethod = .rules,
        confidence: Double,
        sourcePage: Int? = nil,
        sourceBBox: [Double]? = nil,
        evidence: String? = nil
    ) {
        self.key = key
        self.valueText = valueText
        self.valueJSON = valueJSON
        self.method = method
        self.confidence = confidence
        self.sourcePage = sourcePage
        self.sourceBBox = sourceBBox
        self.evidence = evidence
    }
}

nonisolated struct ExtractedHighlight: Hashable, Sendable {
    var section: RecordHighlightSection
    var text: String
    var method: RecordFieldMethod
    var provider: String?
    var sourcePage: Int?
    var confidence: Double
}

nonisolated struct RecordExtraction: Sendable {
    var fields: [ExtractedField] = []
    /// Classifier / AI type suggestion, applied when `type_method` is not `user`.
    var recordType: RecordType?
    var typeConfidence: Double?
    var typeMethod: RecordFieldMethod?
    var summary: ExtractedHighlight?
}

/// Row counts for the processing strip.
nonisolated struct RecordsProcessingSummary: Hashable, Sendable {
    var activeRecords: Int = 0
    var awaitingConsent: Int = 0
    var needsReview: Int = 0
    var currentRecordID: String?
    var currentPage: Int?
    var currentPageCount: Int?
}
