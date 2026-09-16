import Foundation
import Testing
@testable import calorietracker

/// Store behaviour over a temporary runtime, a per-test `UserDefaults` suite and a fixed clock.
@MainActor
struct MedicationStoreTests {
    static let zone = "Asia/Kolkata"

    final class Clock: @unchecked Sendable {
        private let lock = NSLock()
        private var value: Int64
        init(_ ms: Int64) { value = ms }
        var ms: Int64 {
            get { lock.withLock { value } }
            set { lock.withLock { value = newValue } }
        }
    }

    struct Harness {
        let store: MedicationStore
        let runtime: MedicationsRuntime
        let defaults: UserDefaults
        let directory: URL
        let clock: Clock
        let suite: String

        func tearDown() {
            defaults.removePersistentDomain(forName: suite)
            try? FileManager.default.removeItem(at: directory)
        }
    }

    static func ms(_ date: String, _ hhmm: String) -> Int64 {
        Int64(MR.localInstant(date: date, hhmm: hhmm, zone: zone)!)
    }

    static func makeHarness(now: Int64) throws -> Harness {
        let directory = try MedicationsTestFixtures.temporaryDirectory()
        let suite = "MedicationStoreTests.\(UUID().uuidString)"
        let defaults = try #require(UserDefaults(suiteName: suite))
        defaults.removePersistentDomain(forName: suite)
        defaults.set(true, forKey: "notificationsEnabled")
        let runtime = MedicationsRuntime(
            databaseURL: directory.appendingPathComponent("Medications/medications.sqlite"),
            photosDirectory: directory.appendingPathComponent("Medications/photos", isDirectory: true)
        )
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(identifier: zone)!
        let clock = Clock(now)
        let store = MedicationStore(defaults: defaults, runtime: runtime, calendar: calendar,
                                    clock: { Date(timeIntervalSince1970: Double(clock.ms) / 1000) })
        return Harness(store: store, runtime: runtime, defaults: defaults, directory: directory, clock: clock, suite: suite)
    }

    static func draft(name: String = "Metformin", times: [String] = ["08:00", "20:00"], start: String = "2026-09-01", end: String? = nil, prn: Bool = false) -> MedicationDraft {
        var draft = MedicationDraft(startDate: start)
        draft.name = name
        draft.strength = "500 mg"
        draft.form = .tablet
        draft.doseQuantity = 1
        draft.doseUnit = .tablet
        draft.foodRelation = .with
        draft.times = times
        draft.endDate = end
        draft.isPRN = prn
        return draft
    }

    private func item(_ store: MedicationStore, slot: String) -> MedicationTodayTimeline.Item? {
        store.today.slots.first { $0.slot == slot }?.items.first
    }

    @Test func saveBuildsTodayAndActionsFollowTheReference() async throws {
        let h = try Self.makeHarness(now: Self.ms("2026-09-16", "09:00"))
        defer { h.tearDown() }
        let store = h.store
        let saved = try await store.save(draft: Self.draft())
        #expect(saved.status == .active)
        #expect(store.medications.map(\.id) == [saved.id])
        #expect(store.activeCount == 1)
        #expect(store.today.slots.map(\.slot) == ["08:00", "20:00"])
        #expect(item(store, slot: "08:00")?.status == .due)
        #expect(item(store, slot: "20:00")?.status == .scheduled)
        #expect(store.today.summary.total == 2)
        let revisionAfterSave = store.revision
        #expect(revisionAfterSave >= 1)

        let morning = try #require(item(store, slot: "08:00")).occurrence
        let evening = try #require(item(store, slot: "20:00")).occurrence

        let taken = await store.act(.taken, on: morning)
        #expect(taken.ok)
        #expect(taken.log?.status == .taken)
        #expect(taken.log?.scheduleID != nil)
        #expect(item(store, slot: "08:00")?.status == .taken)
        #expect(store.today.summary.taken == 1)
        #expect(store.revision > revisionAfterSave)

        let tooEarly = await store.act(.snoozed, on: evening)
        #expect(!tooEarly.ok)
        #expect(tooEarly.error == "not_due_yet")
        #expect(store.lastActionError == "not_due_yet")

        h.clock.ms = Self.ms("2026-09-16", "20:05")
        await store.reload()
        #expect(item(store, slot: "20:00")?.status == .due)
        let snoozed = await store.act(.snoozed, on: evening, snoozeMinutes: 30)
        #expect(snoozed.ok)
        #expect(snoozed.log?.snoozedUntilMs == Self.ms("2026-09-16", "20:35"))
        #expect(item(store, slot: "20:00")?.status == .snoozed)
        #expect(store.today.summary.snoozed == 1)

        let undone = await store.act(.undo, on: evening)
        #expect(undone.ok)
        #expect(item(store, slot: "20:00")?.status == .due)

        let skipped = await store.act(.skipped, on: evening)
        #expect(skipped.ok)
        #expect(item(store, slot: "20:00")?.status == .skipped)
        let history = await store.history(medicationID: saved.id)
        #expect(history.map(\.status) == [.skipped, .taken])
    }

    @Test func missedDosesAreMaterializedAndCanBeTakenLate() async throws {
        let h = try Self.makeHarness(now: Self.ms("2026-09-16", "09:00"))
        defer { h.tearDown() }
        let store = h.store
        let saved = try await store.save(draft: Self.draft())

        h.clock.ms = Self.ms("2026-09-16", "23:00")
        await store.materializeMissedAndCompletions()
        await store.reload()
        let history = await store.history(medicationID: saved.id)
        #expect(history.map(\.status) == [.missed, .missed])
        #expect(store.today.summary.missed == 2)
        let repository = try #require(store.runtime.repository)
        #expect(try await repository.database.meta(MedicationsDatabase.missedCursorKey) == "\(Self.ms("2026-09-16", "21:00"))")

        let morning = try #require(item(store, slot: "08:00"))
        #expect(morning.logID != nil)
        let late = await store.act(.taken, on: morning.occurrence, note: "took it at bedtime")
        #expect(late.ok)
        #expect(late.log?.id == morning.logID, "a late take updates the missed row in place")
        #expect(late.log?.note == "took it at bedtime")
        #expect(item(store, slot: "08:00")?.status == .taken)
        #expect(item(store, slot: "08:00")?.isLate == true)

        let evening = try #require(item(store, slot: "20:00"))
        let cannotUndo = await store.act(.undo, on: evening.occurrence)
        #expect(cannotUndo.error == "cannot_undo_missed")
        let cannotSnooze = await store.act(.snoozed, on: evening.occurrence)
        #expect(cannotSnooze.error == "dose_missed")

        // Nothing else materializes on a second run.
        await store.materializeMissedAndCompletions()
        #expect((await store.history(medicationID: saved.id)).count == 2)
    }

    @Test func pauseResumeStopVersionSchedulesAndKeepLogs() async throws {
        let h = try Self.makeHarness(now: Self.ms("2026-09-16", "09:00"))
        defer { h.tearDown() }
        let store = h.store
        let saved = try await store.save(draft: Self.draft())
        let morning = try #require(item(store, slot: "08:00")).occurrence
        #expect((await store.act(.taken, on: morning)).ok)

        await store.pause(saved.id)
        var detail = try #require(await store.detail(saved.id))
        #expect(detail.medication.status == .paused)
        #expect(detail.schedule == nil)
        #expect(detail.scheduleHistory.count == 1)
        #expect(detail.scheduleHistory[0].activeUntilMs == Self.ms("2026-09-16", "09:00"))
        #expect(detail.recentLogs.count == 1, "history survives the pause")
        #expect(store.today.slots.map(\.slot) == ["08:00"], "the taken log still shows; the evening dose no longer exists")
        #expect(store.pausedCount == 1)

        var paused = Self.draft(times: ["07:00", "19:00"])
        paused.name = "Metformin XR"
        await #expect(throws: MedicationStoreError.lifecycle("invalid_transition")) {
            try await store.save(draft: paused, editing: saved.id)
        }

        h.clock.ms = Self.ms("2026-09-16", "12:00")
        await store.resume(saved.id)
        detail = try #require(await store.detail(saved.id))
        #expect(detail.medication.status == .active)
        #expect(detail.scheduleHistory.count == 2)
        #expect(detail.schedule?.activeFromMs == Self.ms("2026-09-16", "12:00"))
        #expect(detail.schedule?.times == ["08:00", "20:00"])
        #expect(store.today.slots.map(\.slot) == ["08:00", "20:00"])

        // Editing the schedule closes the open row and inserts a new one; the morning log stays.
        var edited = Self.draft(times: ["07:00", "19:00"])
        edited.name = "Metformin XR"
        let updated = try await store.save(draft: edited, editing: saved.id)
        #expect(updated.name == "Metformin XR")
        detail = try #require(await store.detail(saved.id))
        #expect(detail.scheduleHistory.count == 3)
        #expect(detail.schedule?.times == ["07:00", "19:00"])
        #expect(detail.recentLogs.count == 1)
        #expect(store.today.slots.map(\.slot) == ["08:00", "19:00"])

        // Toggling reminders edits the open row in place.
        var quiet = edited
        quiet.reminderEnabled = false
        _ = try await store.save(draft: quiet, editing: saved.id)
        detail = try #require(await store.detail(saved.id))
        #expect(detail.scheduleHistory.count == 3)
        #expect(detail.schedule?.reminderEnabled == false)

        await store.stop(saved.id)
        detail = try #require(await store.detail(saved.id))
        #expect(detail.medication.status == .stopped)
        #expect(detail.schedule == nil)
        #expect(detail.recentLogs.count == 1)
        await store.resume(saved.id)
        #expect((await store.detail(saved.id))?.medication.status == .stopped, "stopped is final")
        #expect(store.lastActionError == "invalid_transition")
    }

    @Test func endDateCompletesTheCourseAutomatically() async throws {
        let h = try Self.makeHarness(now: Self.ms("2026-09-15", "09:00"))
        defer { h.tearDown() }
        let store = h.store
        let saved = try await store.save(draft: Self.draft(name: "Amoxicillin", times: ["08:00", "14:00", "20:00"], start: "2026-09-11", end: "2026-09-15"))
        #expect(store.today.summary.total == 3, "the end date's own doses still run")
        h.clock.ms = Self.ms("2026-09-16", "08:30")
        await store.materializeMissedAndCompletions()
        await store.reload()
        #expect((await store.detail(saved.id))?.medication.status == .completed)
        #expect(store.today.isEmpty)
        #expect(store.activeCount == 0)
    }

    @Test func prnMedicinesLogDosesWithoutASchedule() async throws {
        let h = try Self.makeHarness(now: Self.ms("2026-09-16", "09:00"))
        defer { h.tearDown() }
        let store = h.store
        let prn = try await store.save(draft: Self.draft(name: "Paracetamol", prn: true))
        let scheduled = try await store.save(draft: Self.draft(name: "Metformin"))
        #expect((await store.detail(prn.id))?.schedule == nil)
        #expect(store.today.prn.map(\.medicationID) == [prn.id])
        #expect(store.today.prn[0].todayCount == 0)

        let logged = await store.logPRN(medicationID: prn.id, quantity: 2, note: "headache")
        #expect(logged.ok)
        #expect(logged.log?.scheduleID == nil)
        #expect(logged.log?.doseQuantity == 2)
        #expect(store.today.prn[0].todayCount == 1)
        #expect(store.today.prn[0].lastTakenMs == Self.ms("2026-09-16", "09:00"))
        #expect(store.today.slots.first { $0.slot == "09:00" }?.items.first?.kind == .prn)
        #expect(store.today.summary.total == 2, "PRN doses never count as scheduled")

        let notPRN = await store.logPRN(medicationID: scheduled.id)
        #expect(notPRN.error == "not_prn")
        let future = await store.logPRN(medicationID: prn.id, at: Date(timeIntervalSince1970: Double(Self.ms("2026-09-16", "10:00")) / 1000))
        #expect(future.error == "taken_at_in_future")

        let adherence = await store.adherence(for: nil)
        #expect(!adherence.hasData, "PRN logs are excluded from adherence")

        await store.undoPRN(logID: try #require(logged.log?.id))
        #expect(store.today.prn[0].todayCount == 0)
    }

    @Test func adherenceCountsResolvedDosesOverSevenDays() async throws {
        let h = try Self.makeHarness(now: Self.ms("2026-09-09", "09:00"))
        defer { h.tearDown() }
        let store = h.store
        let saved = try await store.save(draft: Self.draft(times: ["08:00", "14:00", "20:00"], start: "2026-09-01"))
        let detail = try #require(await store.detail(saved.id))
        let schedule = try #require(detail.schedule)
        #expect(schedule.activeFromMs == Self.ms("2026-09-09", "07:00"), "a new row is active from now − GRACE_MS (docs §4)")
        h.clock.ms = Self.ms("2026-09-16", "23:00")
        // 21 occurrences inside the window (09-10 … 09-16); take 18, leave the 16th unlogged (missed).
        for day in 10...15 {
            for slot in ["08:00", "14:00", "20:00"] {
                let at = Self.ms("2026-09-\(day)", slot)
                let occurrence = DoseOccurrence(medicationID: saved.id, scheduleID: schedule.id, scheduledAtMs: at)
                let outcome = await store.act(.taken, on: occurrence, takenAt: Date(timeIntervalSince1970: Double(at) / 1000))
                #expect(outcome.ok)
            }
        }
        let adherence = await store.adherence(for: saved.id)
        #expect(adherence.taken == 18)
        #expect(adherence.expected == 21)
        #expect(adherence.percent == 86)
        #expect(adherence.hasData)
        #expect(await store.adherence(for: nil) == adherence)

        await store.materializeMissedAndCompletions()
        #expect(await store.adherence(for: saved.id) == adherence, "materialization does not change the numbers")
        let history = await store.history(medicationID: saved.id, limit: 5)
        #expect(history.count == 5)
        #expect(history.first?.scheduledAtMs == Self.ms("2026-09-16", "20:00"))
        let next = await store.history(medicationID: saved.id, before: history.last, limit: 5)
        #expect(next.count == 5)
        #expect(next.first!.scheduledAtMs < history.last!.scheduledAtMs)
    }

    @Test func searchFilterAndDeleteAllData() async throws {
        let h = try Self.makeHarness(now: Self.ms("2026-09-16", "09:00"))
        defer { h.tearDown() }
        let store = h.store
        _ = try await store.save(draft: Self.draft(name: "Metformin"))
        let amox = try await store.save(draft: Self.draft(name: "Amoxicillin"))
        #expect(store.medications.map(\.name) == ["Amoxicillin", "Metformin"])
        await store.pause(amox.id)
        #expect(store.medications.map(\.name) == ["Metformin"])
        store.filter = .paused
        await store.reload()
        #expect(store.medications.map(\.name) == ["Amoxicillin"])
        store.filter = .all
        store.searchText = "met"
        await store.reload()
        #expect(store.medications.map(\.name) == ["Metformin"])

        store.remindersEnabled = false
        store.snoozeMinutes = 45
        #expect(store.snoozeMinutes == 10, "unknown snooze values fall back to the default")
        store.snoozeMinutes = 60
        #expect(h.defaults.integer(forKey: MedicationSettings.snoozeMinutesKey) == 60)

        var cancelled = false
        store.reminderCancelHook = { cancelled = true }
        await store.deleteAllData()
        #expect(cancelled)
        #expect(!FileManager.default.fileExists(atPath: h.runtime.databaseURL.path))
        #expect(h.defaults.object(forKey: MedicationSettings.snoozeMinutesKey) == nil)
        #expect(store.medications.isEmpty)
        #expect(store.today.isEmpty)
        await store.reload()
        #expect(store.totalCount == 0, "a fresh database opens after Delete All Data")
    }

    @Test func validationErrorsBlockSaving() async throws {
        let h = try Self.makeHarness(now: Self.ms("2026-09-16", "09:00"))
        defer { h.tearDown() }
        var draft = Self.draft(name: "  ")
        draft.times = []
        draft.endDate = "2026-08-01"
        let errors = draft.validationErrors.map(\.code)
        #expect(errors == ["name_required", "end_date_before_start", "times_required"])
        await #expect(throws: MedicationStoreError.validation(draft.validationErrors)) {
            try await h.store.save(draft: draft)
        }
        #expect(h.store.medications.isEmpty)

        var prn = Self.draft(name: "Paracetamol", prn: true)
        prn.times = ["08:00", "08:00", "07:00"]
        #expect(prn.isValid, "schedule fields are ignored for PRN drafts")
        var unsorted = Self.draft(times: ["20:00", "08:00", "08:00"])
        #expect(unsorted.isValid, "the typed draft normalizes times before validating")
        #expect(unsorted.schedule(medicationID: "x", nowMs: 1, zone: Self.zone)?.times == ["08:00", "20:00"])
        unsorted.frequency = .interval
        unsorted.intervalHours = 5
        #expect(unsorted.validationErrors.map(\.code) == ["interval_invalid"])
    }

    @Test func draftsFromPrescriptionFieldsAreSuggestionsOnly() async throws {
        let h = try Self.makeHarness(now: Self.ms("2026-09-16", "09:00"))
        defer { h.tearDown() }
        let store = h.store
        func field(_ key: RecordFieldKey, _ text: String, json: String?, state: RecordFieldState = .suggested) -> RecordField {
            RecordField(id: UUID().uuidString, recordID: "rec-1", key: key, valueText: text, valueJSON: json, method: .rules,
                        confidence: 0.8, state: state, sourcePage: 0, sourceBBox: nil, evidence: nil, createdMs: 1, updatedMs: 1)
        }
        let fields = [
            field(.medication, "Metformin", json: #"{"name":"Metformin","strength":"500 mg","form":"Tab.","dose":"1 tab","frequency":"1-0-1","duration":"x 5 days","instructions":"after food"}"#),
            field(.medication, "Rejected", json: #"{"name":"Rejected"}"#, state: .rejected),
            field(.doctorName, "Dr. Rao", json: nil),
            field(.medication, "Paracetamol", json: #"{"name":"Paracetamol","form":"tablet","frequency":"sos"}"#),
        ]
        let drafts = store.candidates(fromRecordID: "rec-1", fields: fields)
        #expect(drafts.map(\.name) == ["Metformin", "Paracetamol"])
        #expect(drafts[0].times == ["08:00", "20:00"])
        #expect(drafts[0].foodRelation == .after)
        #expect(drafts[0].endDate == "2026-09-20")
        #expect(drafts[0].relatedRecordID == "rec-1")
        #expect(drafts[0].hintConfidence == 0.9)
        #expect(drafts[1].isPRN)
        #expect(store.medications.isEmpty, "nothing is created until the user confirms")

        let created = try await store.createFromCandidates(drafts)
        #expect(created.count == 2)
        #expect(created.allSatisfy { $0.relatedRecordID == "rec-1" })
        #expect(store.activeCount == 2)
        let detail = try #require(await store.detail(created[0].id))
        #expect(detail.relatedRecordMissing, "the record store was not given, so the link is reported missing")
    }
}
