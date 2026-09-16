import Foundation

/// One validation error of the Add/Edit form (reference `validate_draft`, docs §15).
nonisolated struct DraftError: Hashable, Sendable, Identifiable {
    var field: String
    var code: String

    var id: String { field + ":" + code }

    /// Human copy for the inline footer of the form.
    var message: String {
        switch code {
        case "name_required": String(localized: "Enter a medicine name (up to 80 characters).")
        case "strength_too_long": String(localized: "Strength is too long (up to 40 characters).")
        case "form_invalid": String(localized: "Choose a form.")
        case "dose_quantity_invalid": String(localized: "Enter a dose greater than 0.")
        case "dose_unit_invalid": String(localized: "Choose a dose unit.")
        case "food_relation_invalid": String(localized: "Choose when to take it.")
        case "start_date_invalid": String(localized: "Choose a start date.")
        case "end_date_invalid": String(localized: "Choose a valid end date.")
        case "end_date_before_start": String(localized: "The end date is before the start date.")
        case "prn_has_schedule": String(localized: "As-needed medicines don't have a schedule.")
        case "frequency_required": String(localized: "Choose how often to take it.")
        case "times_required": String(localized: "Add at least one time.")
        case "times_invalid": String(localized: "Times must be unique (up to 12).")
        case "days_required": String(localized: "Choose at least one day.")
        case "days_invalid", "days_not_allowed": String(localized: "Check the selected days.")
        case "interval_invalid": String(localized: "Choose an interval that divides 24 hours.")
        case "anchor_required", "anchor_invalid": String(localized: "Choose the first dose time.")
        case "instructions_too_long": String(localized: "Instructions are too long (up to 200 characters).")
        case "note_too_long": String(localized: "The note is too long (up to 200 characters).")
        default: String(localized: "Check this field.")
        }
    }
}

/// Editable form model for Add / Edit / Import (docs §13, §15). Validation and the row shapes come
/// from the reference so the form can never save what the contract rejects.
nonisolated struct MedicationDraft: Hashable, Sendable {
    var name = ""
    var genericName = ""
    var brandName = ""
    var strength = ""
    var form: MedicationForm = .tablet
    var doseQuantity: Double? = 1
    var doseUnit: DoseUnit = .tablet
    var foodRelation: FoodRelation = .anytime
    var instructions = ""
    /// `yyyy-MM-dd`.
    var startDate: String
    /// `nil` = no end date.
    var endDate: String?
    var isPRN = false
    var frequency: ScheduleFrequency = .daily
    /// `HH:mm`; normalized (unique, ascending) before validation and saving.
    var times: [String] = ["08:00"]
    /// ISO weekdays 1 = Mon … 7 = Sun (weekly only).
    var days: [Int] = []
    var intervalHours = 8
    var anchorTime = "08:00"
    var reminderEnabled = true
    var relatedRecordID: String?
    /// A newly picked photo to store on save; `nil` keeps the current one.
    var newPhotoData: Data?
    /// Remove the stored photo on save.
    var removePhoto = false
    // Prescription-import metadata (informational).
    var hintConfidence: Double?
    var hintNotes: [String] = []
    var durationDays: Int?

    init(startDate: String) {
        self.startDate = startDate
    }

    /// Prefilled from an existing medication and its open schedule row.
    init(from medication: Medication, schedule: MedicationSchedule?) {
        startDate = medication.startDate
        name = medication.name
        genericName = medication.genericName ?? ""
        brandName = medication.brandName ?? ""
        strength = medication.strength ?? ""
        form = medication.form
        doseQuantity = medication.doseQuantity
        doseUnit = medication.doseUnit
        foodRelation = medication.foodRelation
        instructions = medication.instructions ?? ""
        endDate = medication.endDate
        isPRN = medication.isPRN
        relatedRecordID = medication.relatedRecordID
        if let schedule {
            frequency = schedule.frequency
            times = schedule.times.isEmpty ? ["08:00"] : schedule.times
            days = schedule.days
            intervalHours = schedule.intervalHours ?? 8
            anchorTime = schedule.anchorTime ?? "08:00"
            reminderEnabled = schedule.reminderEnabled
        }
    }

    /// Prefilled from a Records `medication` field through the reference's `frequency_hint` (docs §13).
    init(candidate: RecordMedicationValue, startDate: String, relatedRecordID: String?) {
        self.startDate = startDate
        self.relatedRecordID = relatedRecordID
        let value: RJ = .obj([
            "name": RJ.string(candidate.name), "strength": RJ.string(candidate.strength), "form": RJ.string(candidate.form),
            "dose": RJ.string(candidate.dose), "frequency": RJ.string(candidate.frequency),
            "duration": RJ.string(candidate.duration), "instructions": RJ.string(candidate.instructions),
        ])
        let hint = MR.frequencyHint(value)
        name = hint["name"].string ?? ""
        strength = hint["strength"].string ?? ""
        form = MedicationForm(raw: hint["form"].string)
        doseQuantity = hint["dose_quantity"].double
        doseUnit = DoseUnit(raw: hint["dose_unit"].string)
        foodRelation = FoodRelation(raw: hint["food_relation"].string)
        instructions = hint["instructions"].string ?? ""
        isPRN = hint["is_prn"].bool ?? false
        if !isPRN {
            frequency = ScheduleFrequency(raw: hint["frequency_kind"].string)
            let hintTimes = (hint["times"].array ?? []).compactMap(\.string)
            times = hintTimes.isEmpty ? ["08:00"] : hintTimes
            days = (hint["days"].array ?? []).compactMap { MR.int($0) }
            intervalHours = MR.int(hint["interval_hours"]) ?? 8
            anchorTime = hint["anchor_time"].string ?? "08:00"
        }
        hintConfidence = hint["confidence"].double
        hintNotes = (hint["notes"].array ?? []).compactMap(\.string)
        durationDays = MR.int(hint["duration_days"])
        if let durationDays, durationDays > 0, let start = MR.parseDate(startDate) {
            endDate = start.days(durationDays - 1).isoString
        }
    }

    // MARK: Normalization and validation

    /// Unique, ascending `HH:mm` values (the reference rejects unsorted or duplicate times).
    var normalizedTimes: [String] {
        Array(Set(times.filter { MR.parseHHMM($0) != nil })).sorted()
    }

    var normalizedDays: [Int] {
        Array(Set(days.filter { (1...7).contains($0) })).sorted()
    }

    private static func optional(_ s: String) -> RJ {
        let trimmed = s.trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty ? .null : .str(trimmed)
    }

    /// The draft in the reference's `validate_draft` shape.
    var rj: RJ {
        var obj: [String: RJ] = [
            "name": .str(name.trimmingCharacters(in: .whitespacesAndNewlines)),
            "strength": Self.optional(strength),
            "form": .str(form.rawValue),
            "dose_quantity": RJ.number(doseQuantity),
            "dose_unit": .str(doseUnit.rawValue),
            "food_relation": .str(foodRelation.rawValue),
            "start_date": .str(startDate),
            "end_date": RJ.string(endDate),
            "is_prn": .bool(isPRN),
            "instructions": Self.optional(instructions),
            "note": .null,
        ]
        if isPRN {
            obj["frequency_kind"] = .null
            obj["times"] = .arr([])
            obj["days"] = .arr([])
            obj["interval_hours"] = .null
            obj["anchor_time"] = .null
        } else {
            obj["frequency_kind"] = .str(frequency.rawValue)
            obj["times"] = .arr(frequency == .interval ? [] : normalizedTimes.map(RJ.str))
            obj["days"] = .arr(frequency == .weekly ? normalizedDays.map(RJ.int) : [])
            obj["interval_hours"] = frequency == .interval ? .int(intervalHours) : .null
            obj["anchor_time"] = frequency == .interval ? .str(anchorTime) : .null
            obj["reminder_enabled"] = .int(reminderEnabled ? 1 : 0)
        }
        return .obj(obj)
    }

    var validationErrors: [DraftError] {
        MR.validateDraft(rj).compactMap { e in
            guard let field = e["field"].string, let code = e["code"].string else { return nil }
            return DraftError(field: field, code: code)
        }
    }

    var isValid: Bool { validationErrors.isEmpty }

    // MARK: Rows

    /// A new or updated `medications` row. `existing` keeps id, created_ms, status and photo.
    func medication(existing: Medication?, nowMs: Int64) -> Medication {
        let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
        func opt(_ s: String) -> String? {
            let t = s.trimmingCharacters(in: .whitespacesAndNewlines)
            return t.isEmpty ? nil : t
        }
        return Medication(
            id: existing?.id ?? UUID().uuidString.lowercased(),
            name: trimmed,
            genericName: opt(genericName),
            brandName: opt(brandName),
            strength: opt(strength),
            form: form,
            doseQuantity: doseQuantity ?? 1,
            doseUnit: doseUnit,
            foodRelation: foodRelation,
            instructions: opt(instructions),
            startDate: startDate,
            endDate: endDate,
            status: existing?.status ?? .active,
            isPRN: isPRN,
            photoPath: existing?.photoPath,
            relatedRecordID: relatedRecordID,
            createdMs: existing?.createdMs ?? nowMs,
            updatedMs: nowMs
        )
    }

    /// The open schedule row for a non-PRN draft (`nil` for PRN). A NEW medication's row is active
    /// from `now − GRACE_MS` (docs §4 "creation", same on Android): a dose that became due within the
    /// last two hours still shows today and can be taken, while earlier slots of the day never existed
    /// (no phantom missed rows). Edits and resumes go through the reference's `lifecycle` (`now`).
    func schedule(medicationID: String, nowMs: Int64, zone: String) -> MedicationSchedule? {
        guard !isPRN else { return nil }
        return MedicationSchedule(
            medicationID: medicationID,
            frequency: frequency,
            times: frequency == .interval ? [] : normalizedTimes,
            days: frequency == .weekly ? normalizedDays : [],
            intervalHours: frequency == .interval ? intervalHours : nil,
            anchorTime: frequency == .interval ? anchorTime : nil,
            reminderEnabled: reminderEnabled,
            activeFromMs: nowMs - Int64(MR.graceMs),
            activeUntilMs: nil,
            createdMs: nowMs,
            updatedMs: nowMs
        )
    }

    /// `new_schedule` for the reference's `lifecycle('edit_schedule')`.
    var newScheduleRJ: RJ {
        .obj([
            "frequency_kind": .str(frequency.rawValue),
            "times": .arr(frequency == .interval ? [] : normalizedTimes.map(RJ.str)),
            "days": .arr(frequency == .weekly ? normalizedDays.map(RJ.int) : []),
            "interval_hours": frequency == .interval ? .int(intervalHours) : .null,
            "anchor_time": frequency == .interval ? .str(anchorTime) : .null,
            "reminder_enabled": .int(reminderEnabled ? 1 : 0),
        ])
    }

    /// True when the schedule part differs from `schedule` (frequency, times, days, interval, anchor).
    func scheduleDiffers(from schedule: MedicationSchedule?) -> Bool {
        guard let schedule else { return !isPRN }
        if frequency != schedule.frequency { return true }
        switch frequency {
        case .daily:
            return normalizedTimes != schedule.times
        case .weekly:
            return normalizedTimes != schedule.times || normalizedDays != schedule.days
        case .interval:
            return intervalHours != schedule.intervalHours || anchorTime != schedule.anchorTime
        }
    }
}
