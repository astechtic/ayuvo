import Foundation

// Enumerations stored as the exact lowercase strings of `docs/health-records.md` §3.

nonisolated enum RecordType: String, CaseIterable, Codable, Sendable, Identifiable {
    case labReport = "lab_report"
    case prescription
    case consultationNote = "consultation_note"
    case dischargeSummary = "discharge_summary"
    case imagingReport = "imaging_report"
    case diagnosticReport = "diagnostic_report"
    case medicationList = "medication_list"
    case vaccinationRecord = "vaccination_record"
    case bill
    case insurance
    case personalNote = "personal_note"
    case other

    var id: String { rawValue }

    var defaultCategory: RecordCategory {
        switch self {
        case .labReport, .diagnosticReport: .labReports
        case .prescription: .prescriptions
        case .consultationNote: .doctorVisits
        case .dischargeSummary: .hospitalization
        case .imagingReport: .imaging
        case .medicationList: .medication
        case .vaccinationRecord: .vaccination
        case .bill, .insurance: .insuranceBills
        case .personalNote: .personalNotes
        case .other: .other
        }
    }

    var title: String {
        switch self {
        case .labReport: String(localized: "Lab Report")
        case .prescription: String(localized: "Prescription")
        case .consultationNote: String(localized: "Doctor Note")
        case .dischargeSummary: String(localized: "Discharge Summary")
        case .imagingReport: String(localized: "Imaging Report")
        case .diagnosticReport: String(localized: "Diagnostic Report")
        case .medicationList: String(localized: "Medication List")
        case .vaccinationRecord: String(localized: "Vaccination Record")
        case .bill: String(localized: "Bill")
        case .insurance: String(localized: "Insurance")
        case .personalNote: String(localized: "Note")
        case .other: String(localized: "Record")
        }
    }

    var systemImage: String {
        switch self {
        case .labReport, .diagnosticReport: "testtube.2"
        case .prescription, .medicationList: "pills.fill"
        case .consultationNote: "stethoscope"
        case .dischargeSummary: "bed.double.fill"
        case .imagingReport: "xray"
        case .vaccinationRecord: "syringe.fill"
        case .bill, .insurance: "creditcard.fill"
        case .personalNote: "note.text"
        case .other: "doc.text.fill"
        }
    }
}

nonisolated enum RecordCategory: String, CaseIterable, Codable, Sendable, Identifiable {
    case labReports = "lab_reports"
    case prescriptions
    case doctorVisits = "doctor_visits"
    case imaging
    case hospitalization
    case procedures
    case vaccination
    case medication
    case insuranceBills = "insurance_bills"
    case personalNotes = "personal_notes"
    case other

    var id: String { rawValue }

    var title: String {
        switch self {
        case .labReports: String(localized: "Lab Reports")
        case .prescriptions: String(localized: "Prescriptions")
        case .doctorVisits: String(localized: "Doctor Visits")
        case .imaging: String(localized: "Imaging")
        case .hospitalization: String(localized: "Hospitalization")
        case .procedures: String(localized: "Procedures")
        case .vaccination: String(localized: "Vaccination")
        case .medication: String(localized: "Medication")
        case .insuranceBills: String(localized: "Insurance & Bills")
        case .personalNotes: String(localized: "Personal Notes")
        case .other: String(localized: "Other")
        }
    }
}

nonisolated enum RecordSource: String, CaseIterable, Codable, Sendable {
    case `import`
    case photos
    case camera
    case scan
    case paste
    case note
    case shareIn = "share_in"
    case openIn = "open_in"

    /// Records that arrived from another app (share sheet / "Open in").
    var isReceived: Bool { self == .shareIn || self == .openIn }
}

nonisolated enum RecordImportMethod: String, CaseIterable, Codable, Sendable {
    case filePicker = "file_picker"
    case photoPicker = "photo_picker"
    case camera
    case documentScanner = "document_scanner"
    case pasteText = "paste_text"
    case noteEditor = "note_editor"
    case shareSheet = "share_sheet"
    case openIn = "open_in"
    case archiveRestore = "archive_restore"
}

nonisolated enum RecordFileType: String, CaseIterable, Codable, Sendable {
    case pdf
    case image
    case text
    case other
}

nonisolated enum RecordProcessingStatus: String, CaseIterable, Codable, Sendable {
    case saved
    case queued
    case extractingText = "extracting_text"
    case analyzing
    case aiPendingConsent = "ai_pending_consent"
    case ready
    case failedPartial = "failed_partial"
}

nonisolated enum RecordReviewStatus: String, CaseIterable, Codable, Sendable {
    case none
    case needsReview = "needs_review"
    case reviewed
}

nonisolated enum RecordDatePrecision: String, CaseIterable, Codable, Sendable {
    case day
    case month
    case year
}

nonisolated enum RecordDateMethod: String, CaseIterable, Codable, Sendable {
    case fileMetadata = "file_metadata"
    case pdfText = "pdf_text"
    case ocr
    case rules
    case aiLocal = "ai_local"
    case aiCloud = "ai_cloud"
    case user
    case importTime = "import_time"
}

nonisolated enum RecordPageTextSource: String, CaseIterable, Codable, Sendable {
    case pdfText = "pdf_text"
    case ocr
    case user
    case plain
}

/// `healthRecordsViewMode` values (contract §6).
nonisolated enum RecordsViewMode: String, CaseIterable, Codable, Sendable, Identifiable {
    case timeline
    case list
    case grid

    static let storageKey = "healthRecordsViewMode"
    static let defaultMode: RecordsViewMode = .timeline

    var id: String { rawValue }

    var title: String {
        switch self {
        case .timeline: String(localized: "Timeline")
        case .list: String(localized: "List")
        case .grid: String(localized: "Grid")
        }
    }
}
