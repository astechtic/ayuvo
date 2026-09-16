import Foundation
import Testing
@testable import calorietracker

/// `ayuvo-medications` export → wipe → import round trip and the merge rules (docs §14).
@MainActor
struct MedicationArchiveTests {
    typealias T = MedicationStoreTests

    @Test func exportThenImportRestoresEverything() async throws {
        let h = try T.makeHarness(now: T.ms("2026-09-16", "09:00"))
        defer { h.tearDown() }
        let store = h.store
        let metformin = try await store.save(draft: T.draft(name: "Metformin"))
        let prn = try await store.save(draft: T.draft(name: "Paracetamol", prn: true))
        let morning = try #require(store.today.slots.first { $0.slot == "08:00" }?.items.first).occurrence
        #expect((await store.act(.taken, on: morning)).ok)
        #expect((await store.logPRN(medicationID: prn.id)).ok)

        let archive = try await store.exportArchive()
        #expect(archive.medicationCount == 2)
        #expect(archive.scheduleCount == 1)
        #expect(archive.doseLogCount == 2)
        #expect(archive.json["format"].string == "ayuvo-medications")
        #expect(archive.json["app"]["platform"].string == "ios")
        let data = archive.data
        #expect(data.last == 0x0A)
        let reparsed = try MedicationArchive(data: data)
        #expect(reparsed.medicationCount == 2)

        await store.deleteAllData()
        #expect(store.totalCount == 0)
        h.clock.ms = T.ms("2026-09-16", "10:00")
        let result = try await store.importArchive(data)
        #expect(result.insertedMedications == 2)
        #expect(result.insertedSchedules == 1)
        #expect(result.insertedLogs == 2)
        #expect(result.skipped == 0)
        #expect(store.medications.map(\.name).sorted() == ["Metformin", "Paracetamol"])
        let detail = try #require(await store.detail(metformin.id))
        #expect(detail.schedule?.times == ["08:00", "20:00"])
        #expect(detail.recentLogs.map(\.status) == [.taken])
        #expect(store.today.prn.map(\.medicationID) == [prn.id])

        // Importing the same file again changes nothing.
        let again = try await store.importArchive(data)
        #expect(again.totalChanges == 0)
        #expect(again.skipped == 5)
    }

    @Test func newerLocalRowsWinAndNothingIsDeleted() async throws {
        let h = try T.makeHarness(now: T.ms("2026-09-16", "09:00"))
        defer { h.tearDown() }
        let store = h.store
        let saved = try await store.save(draft: T.draft(name: "Metformin"))
        let data = try await store.exportArchive().data

        h.clock.ms = T.ms("2026-09-16", "11:00")
        var renamed = T.draft(name: "Metformin XR")
        renamed.times = ["08:00", "20:00"]
        _ = try await store.save(draft: renamed, editing: saved.id)
        _ = try await store.save(draft: T.draft(name: "Extra"))

        let result = try await store.importArchive(data)
        #expect(result.updatedMedications == 0)
        #expect(result.skipped == 2, "older medication and schedule rows are skipped")
        #expect(store.medications.map(\.name) == ["Extra", "Metformin XR"], "the local edit stays and the extra medicine survives")
    }

    @Test func badFilesAreRejected() async throws {
        let h = try T.makeHarness(now: T.ms("2026-09-16", "09:00"))
        defer { h.tearDown() }
        #expect(throws: MedicationStoreError.archive("bad_format")) {
            try MedicationArchive(data: Data("not json".utf8))
        }
        #expect(throws: MedicationStoreError.archive("bad_format")) {
            try MedicationArchive(data: Data(#"{"format":"ayuvo-records","version":1}"#.utf8))
        }
        #expect(throws: MedicationStoreError.archive("unsupported_version")) {
            try MedicationArchive(data: Data(#"{"format":"ayuvo-medications","version":2,"medications":[]}"#.utf8))
        }
        await #expect(throws: MedicationStoreError.archive("unsupported_version")) {
            try await h.store.importArchive(Data(#"{"format":"ayuvo-medications","version":2}"#.utf8))
        }
        let empty = try await h.store.importArchive(Data(#"{"format":"ayuvo-medications","version":1}"#.utf8))
        #expect(empty.totalChanges == 0)
    }
}
