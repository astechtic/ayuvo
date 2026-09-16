import Foundation
import Testing
import UIKit
@testable import calorietracker

enum MedicationsTestFixtures {
    static var sharedSchemaURL: URL { HealthTestFixtures.repoRootURL.appendingPathComponent("shared/medications/schema.sql") }

    static func temporaryDirectory() throws -> URL {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("medications-tests-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
        return url
    }

    static func medication(
        id: String = UUID().uuidString.lowercased(),
        name: String,
        strength: String? = "500 mg",
        form: MedicationForm = .tablet,
        status: MedicationStatus = .active,
        isPRN: Bool = false,
        startDate: String = "2026-09-01",
        endDate: String? = nil,
        createdMs: Int64 = 1_000
    ) -> Medication {
        Medication(
            id: id,
            name: name,
            genericName: "generic \(name)",
            brandName: "brand \(name)",
            strength: strength,
            form: form,
            doseQuantity: 1.5,
            doseUnit: form.defaultUnit,
            foodRelation: .after,
            instructions: "with a glass of water",
            startDate: startDate,
            endDate: endDate,
            status: status,
            isPRN: isPRN,
            photoPath: nil,
            relatedRecordID: "rec-1",
            createdMs: createdMs,
            updatedMs: createdMs
        )
    }

    static func schedule(
        id: String = UUID().uuidString.lowercased(),
        medicationID: String,
        frequency: ScheduleFrequency = .daily,
        times: [String] = ["08:00", "20:00"],
        days: [Int] = [],
        intervalHours: Int? = nil,
        anchorTime: String? = nil,
        activeFromMs: Int64 = 1_000,
        activeUntilMs: Int64? = nil
    ) -> MedicationSchedule {
        MedicationSchedule(
            id: id,
            medicationID: medicationID,
            frequency: frequency,
            times: times,
            days: days,
            intervalHours: intervalHours,
            anchorTime: anchorTime,
            reminderEnabled: true,
            activeFromMs: activeFromMs,
            activeUntilMs: activeUntilMs,
            createdMs: activeFromMs,
            updatedMs: activeFromMs
        )
    }

    static func log(
        id: String = UUID().uuidString.lowercased(),
        medicationID: String,
        scheduleID: String?,
        scheduledAtMs: Int64,
        status: DoseStatus = .taken,
        takenAtMs: Int64? = nil,
        snoozedUntilMs: Int64? = nil,
        note: String? = nil,
        createdMs: Int64 = 5_000
    ) -> DoseLog {
        DoseLog(
            id: id,
            medicationID: medicationID,
            scheduleID: scheduleID,
            scheduledAtMs: scheduledAtMs,
            status: status,
            takenAtMs: takenAtMs,
            snoozedUntilMs: snoozedUntilMs,
            doseQuantity: 1.5,
            doseUnit: .tablet,
            note: note,
            createdMs: createdMs,
            updatedMs: createdMs
        )
    }

    static func jpeg(width: Int, height: Int) -> Data {
        let renderer = UIGraphicsImageRenderer(size: CGSize(width: width, height: height))
        let image = renderer.image { context in
            UIColor.systemTeal.setFill()
            context.fill(CGRect(x: 0, y: 0, width: width, height: height))
        }
        return image.jpegData(compressionQuality: 0.9)!
    }
}

struct MedicationsSchemaTests {
    private typealias F = MedicationsTestFixtures

    @Test func embeddedStatementsMatchSharedSchemaVerbatim() throws {
        let url = F.sharedSchemaURL
        guard FileManager.default.fileExists(atPath: url.path) else {
            Issue.record("shared/medications/schema.sql is missing")
            return
        }
        let fileStatements = try #require(MedicationsSchema.parseStatementsStrict(try String(contentsOf: url, encoding: .utf8)))
        #expect(fileStatements.count == MedicationsSchema.statements.count)
        for (shared, embedded) in zip(fileStatements, MedicationsSchema.statements) {
            #expect(shared == embedded, "statement differs from shared/medications/schema.sql:\n\(shared)")
        }
    }

    @Test func schemaCreatedFromSharedFileMatchesEmbeddedColumnForColumn() async throws {
        let fileSQL = try String(contentsOf: F.sharedSchemaURL, encoding: .utf8)
        let embedded = try await MedicationsDatabase.inMemory(targetVersion: 1)
        let fromFile = try await MedicationsDatabase.inMemory(targetVersion: 0)
        try await fromFile.withConnection { connection in
            try connection.exec(fileSQL)
        }
        #expect(try await embedded.tableNames() == fromFile.tableNames())
        #expect(try await embedded.indexNames() == fromFile.indexNames())
        for table in MedicationsSchema.tableNames {
            let a = try await embedded.columns(of: table)
            let b = try await fromFile.columns(of: table)
            #expect(a == b, "table \(table) differs from shared/medications/schema.sql")
        }
    }

    @Test func embeddedSchemaStampsVersionAndCreatesEverything() async throws {
        let db = try await MedicationsDatabase.inMemory()
        #expect(try await db.tableNames() == MedicationsSchema.tableNames.sorted())
        #expect(try await db.indexNames() == MedicationsSchema.indexNames.sorted())
        #expect(try await db.userVersion() == MedicationsSchema.schemaVersion)
        #expect(try await db.meta("schema_version") == "\(MedicationsSchema.schemaVersion)")
        #expect(try await db.pragmaInt("foreign_keys") == 1)
        #expect(try await db.pragmaInt("busy_timeout") == 5000)
        #expect(MedicationsSchema.migrations.isEmpty)
    }

    @Test func onDiskDatabaseUsesWALInBackupExcludedDirectoryAndReopens() async throws {
        let directory = try F.temporaryDirectory()
        defer { try? FileManager.default.removeItem(at: directory) }
        let url = directory.appendingPathComponent("Ayuvo/Medications/medications.sqlite")
        let db = try await MedicationsDatabase.open(url: url)
        #expect(try await db.journalMode()?.lowercased() == "wal")
        #expect(try await db.pragmaInt("synchronous") == 1)
        #expect(HealthDatabaseLocation.isExcludedFromBackup(url.deletingLastPathComponent()))
        try await db.insertMedication(F.medication(name: "Kept"))
        await db.close()
        let reopened = try await MedicationsDatabase.open(url: url)
        #expect(try await reopened.medicationCount() == 1)
        #expect(try await reopened.userVersion() == MedicationsSchema.schemaVersion)
        await reopened.close()
    }

    @Test func corruptFileIsQuarantinedAndReplaced() async throws {
        let directory = try F.temporaryDirectory()
        defer { try? FileManager.default.removeItem(at: directory) }
        let url = directory.appendingPathComponent("Medications/medications.sqlite")
        try FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
        try Data("not a database at all, just bytes".utf8).write(to: url)
        let (db, quarantined) = try await MedicationsDatabase.openQuarantiningCorruption(url: url)
        #expect(quarantined != nil)
        #expect(try await db.integrityCheck())
        #expect(try await db.tableNames().contains("medications"))
        await db.close()
    }

    @Test func locationIsApplicationSupportAyuvoMedications() {
        #expect(MedicationsLocation.databaseURL().path.hasSuffix("Application Support/Ayuvo/Medications/medications.sqlite"))
        #expect(MedicationsLocation.photosDirectory().path.hasSuffix("Application Support/Ayuvo/Medications/photos"))
    }

    @Test func metaRoundTrip() async throws {
        let db = try await MedicationsDatabase.inMemory()
        #expect(try await db.meta("last_planned_ms") == nil)
        try await db.setMeta("last_planned_ms", "123")
        #expect(try await db.meta("last_planned_ms") == "123")
        try await db.setMeta("last_planned_ms", "456")
        #expect(try await db.meta("last_planned_ms") == "456")
        try await db.removeMeta("last_planned_ms")
        #expect(try await db.meta("last_planned_ms") == nil)
        #expect(try await db.meta("schema_version") == "1")
    }
}

struct MedicationsDatabaseTests {
    private typealias F = MedicationsTestFixtures

    @Test func medicationRoundTripReadsBackEveryColumn() async throws {
        let db = try await MedicationsDatabase.inMemory()
        var medication = F.medication(name: "Metformin", endDate: "2026-09-30")
        medication.photoPath = "photos/\(medication.id).jpg"
        try await db.insertMedication(medication)
        #expect(try await db.medication(id: medication.id) == medication)

        medication.name = "Metformin XR"
        medication.strength = "1000 mg"
        medication.status = .paused
        medication.isPRN = true
        medication.endDate = nil
        medication.updatedMs = 2_000
        try await db.updateMedication(medication)
        #expect(try await db.medication(id: medication.id) == medication)

        try await db.setMedicationStatus(id: medication.id, status: .stopped, nowMs: 3_000)
        let stored = try #require(try await db.medication(id: medication.id))
        #expect(stored.status == .stopped)
        #expect(stored.updatedMs == 3_000)
        #expect(stored.createdMs == 1_000)
        #expect(try await db.medications(relatedRecordID: "rec-1").map(\.id) == [medication.id])
    }

    @Test func medicationsFilterSearchAndOrder() async throws {
        let db = try await MedicationsDatabase.inMemory()
        try await db.insertMedication(F.medication(id: "b", name: "amlodipine", status: .active))
        try await db.insertMedication(F.medication(id: "a", name: "Atorvastatin", status: .active))
        try await db.insertMedication(F.medication(id: "c", name: "Cetirizine", status: .paused))
        try await db.insertMedication(F.medication(id: "d", name: "100% Honey_Syrup", form: .syrup, status: .stopped))

        #expect(try await db.medications().map(\.id) == ["d", "b", "a", "c"])
        #expect(try await db.medications(status: .active).map(\.id) == ["b", "a"])
        #expect(try await db.medications(status: .paused).map(\.id) == ["c"])
        #expect(try await db.medications(search: "ator").map(\.id) == ["a"])
        #expect(try await db.medications(search: "brand cet").map(\.id) == ["c"])
        #expect(try await db.medications(search: "%").map(\.id) == ["d"])
        #expect(try await db.medications(search: "y_s").map(\.id) == ["d"])
        #expect(try await db.medications(status: .active, search: "zzz").isEmpty)
        let counts = try await db.countsByStatus()
        #expect(counts[.active] == 2)
        #expect(counts[.paused] == 1)
        #expect(counts[.stopped] == 1)
        #expect(counts[.completed] == nil)
        #expect(try await db.allMedications().count == 4)
    }

    @Test func schedulesRoundTripAndCloseKeepsOldLogs() async throws {
        let db = try await MedicationsDatabase.inMemory()
        let medication = F.medication(id: "m1", name: "Metformin")
        try await db.insertMedication(medication)
        let weekly = F.schedule(id: "s1", medicationID: "m1", frequency: .weekly, times: ["08:00"], days: [1, 3, 5], activeFromMs: 1_000)
        let interval = F.schedule(id: "s2", medicationID: "m1", frequency: .interval, times: [], intervalHours: 8, anchorTime: "22:00", activeFromMs: 2_000)
        try await db.insertSchedule(weekly)
        #expect(try await db.schedule(id: "s1") == weekly)
        #expect(try await db.schedules(medicationID: "m1", includeEnded: false).map(\.id) == ["s1"])

        let log = try await db.upsertDoseLog(F.log(id: "l1", medicationID: "m1", scheduleID: "s1", scheduledAtMs: 10_000))
        #expect(log.id == "l1")

        // Versioning: close the open row, insert the next one; the old row and its log survive.
        #expect(try await db.closeSchedules(medicationID: "m1", at: 2_000) == 1)
        try await db.insertSchedule(interval)
        let versions = try await db.schedules(medicationID: "m1", includeEnded: true)
        #expect(versions.map(\.id) == ["s1", "s2"])
        #expect(versions[0].activeUntilMs == 2_000)
        #expect(versions[0].updatedMs == 2_000)
        #expect(versions[0].days == [1, 3, 5])
        #expect(versions[1].isOpen)
        #expect(versions[1].intervalHours == 8)
        #expect(versions[1].anchorTime == "22:00")
        #expect(try await db.schedules(medicationID: "m1", includeEnded: false).map(\.id) == ["s2"])
        #expect(try await db.openSchedules().map(\.id) == ["s2"])
        #expect(try await db.doseLog(id: "l1")?.scheduleID == "s1")
        #expect(try await db.closeSchedules(medicationID: "m1", at: 3_000) == 1)
        #expect(try await db.closeSchedules(medicationID: "m1", at: 4_000) == 0)
        #expect(try await db.schedules(overlapping: 1_500, 2_500).map(\.id) == ["s1", "s2"])
        #expect(try await db.schedules(overlapping: 5_000, 6_000).isEmpty)

        try await db.setReminderEnabled(scheduleID: "s2", enabled: false, nowMs: 5_000)
        let s2 = try #require(try await db.schedule(id: "s2"))
        #expect(s2.reminderEnabled == false)
        #expect(s2.updatedMs == 5_000)
    }

    @Test func doseLogUpsertIsKeyedOnOccurrenceAndAllowsPRNRows() async throws {
        let db = try await MedicationsDatabase.inMemory()
        try await db.insertMedication(F.medication(id: "m1", name: "Metformin"))
        try await db.insertSchedule(F.schedule(id: "s1", medicationID: "m1"))

        let snoozed = F.log(id: "l1", medicationID: "m1", scheduleID: "s1", scheduledAtMs: 10_000, status: .snoozed, snoozedUntilMs: 10_600, createdMs: 10_010)
        #expect(try await db.upsertDoseLog(snoozed).id == "l1")
        // Same occurrence, different id: the stored row is updated in place and keeps its id/created_ms.
        let taken = F.log(id: "l2", medicationID: "m1", scheduleID: "s1", scheduledAtMs: 10_000, status: .taken, takenAtMs: 10_700, note: "late", createdMs: 10_700)
        let stored = try await db.upsertDoseLog(taken)
        #expect(stored.id == "l1")
        #expect(stored.status == .taken)
        #expect(stored.takenAtMs == 10_700)
        #expect(stored.snoozedUntilMs == nil)
        #expect(stored.note == "late")
        #expect(stored.createdMs == 10_010)
        #expect(stored.updatedMs == 10_700)
        #expect(try await db.doseLogCount() == 1)
        #expect(try await db.doseLog(scheduleID: "s1", scheduledAtMs: 10_000)?.id == "l1")

        // A raw duplicate insert is rejected by idx_dose_logs_occurrence.
        await #expect(throws: HealthDBError.self) {
            try await db.withConnection { connection in
                try connection.run(
                    "INSERT INTO dose_logs (\(MedicationsDatabase.doseLogColumns)) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    [.text("dup"), .text("m1"), .text("s1"), .int(10_000), .text("taken"), .null, .null, .real(1), .text("tablet"), .null, .int(1), .int(1)]
                )
            }
        }

        // PRN rows (NULL schedule) never collide, even at the same instant.
        try await db.upsertDoseLog(F.log(id: "p1", medicationID: "m1", scheduleID: nil, scheduledAtMs: 20_000, takenAtMs: 20_000))
        try await db.upsertDoseLog(F.log(id: "p2", medicationID: "m1", scheduleID: nil, scheduledAtMs: 20_000, takenAtMs: 20_000))
        #expect(try await db.doseLogCount(medicationID: "m1") == 3)
        #expect(try await db.latestPRNLogs()["m1"]?.id == "p2")

        #expect(try await db.deleteDoseLog(id: "p1"))
        #expect(try await db.deleteDoseLog(id: "p1") == false)
        #expect(try await db.doseLogCount() == 2)
    }

    @Test func doseLogWindowsSnoozesAndKeysetPaging() async throws {
        let db = try await MedicationsDatabase.inMemory()
        try await db.insertMedication(F.medication(id: "m1", name: "A"))
        try await db.insertMedication(F.medication(id: "m2", name: "B"))
        try await db.insertSchedule(F.schedule(id: "s1", medicationID: "m1"))
        try await db.insertSchedule(F.schedule(id: "s2", medicationID: "m2"))
        for (index, at) in [1_000, 2_000, 3_000, 4_000, 5_000].enumerated() {
            try await db.upsertDoseLog(F.log(id: "a\(index)", medicationID: "m1", scheduleID: "s1", scheduledAtMs: Int64(at)))
            try await db.upsertDoseLog(F.log(id: "b\(index)", medicationID: "m2", scheduleID: "s2", scheduledAtMs: Int64(at), status: .skipped))
        }
        try await db.upsertDoseLog(F.log(id: "z", medicationID: "m2", scheduleID: nil, scheduledAtMs: 3_000, status: .snoozed, snoozedUntilMs: 3_500))

        #expect(try await db.doseLogs(from: 2_000, to: 4_000).map(\.id) == ["a1", "b1", "a2", "b2", "z"])
        #expect(try await db.doseLogs(medicationID: "m1", from: 2_000, to: 4_000).map(\.id) == ["a1", "a2"])
        #expect(try await db.snoozedLogs(since: 3_500).map(\.id) == ["z"])
        #expect(try await db.snoozedLogs(since: 3_501).isEmpty)

        // Newest first, ties by id DESC, complete and without repeats across pages.
        var seen: [String] = []
        var cursor: MedicationsDatabase.DoseLogCursor?
        while true {
            let page = try await db.doseLogPage(before: cursor, limit: 4)
            if page.isEmpty { break }
            seen.append(contentsOf: page.map(\.id))
            cursor = MedicationsDatabase.DoseLogCursor(page.last!)
        }
        #expect(seen == ["b4", "a4", "b3", "a3", "z", "b2", "a2", "b1", "a1", "b0", "a0"])
        #expect(try await db.doseLogPage(medicationID: "m1", limit: 2).map(\.id) == ["a4", "a3"])
    }

    @Test func deleteMedicationCascadesSchedulesAndLogs() async throws {
        let db = try await MedicationsDatabase.inMemory()
        try await db.insertMedication(F.medication(id: "m1", name: "A"))
        try await db.insertMedication(F.medication(id: "m2", name: "B"))
        try await db.insertSchedule(F.schedule(id: "s1", medicationID: "m1"))
        try await db.insertSchedule(F.schedule(id: "s2", medicationID: "m2"))
        try await db.upsertDoseLog(F.log(id: "l1", medicationID: "m1", scheduleID: "s1", scheduledAtMs: 1_000))
        try await db.upsertDoseLog(F.log(id: "l2", medicationID: "m2", scheduleID: "s2", scheduledAtMs: 1_000))

        #expect(try await db.deleteMedication(id: "m1"))
        #expect(try await db.deleteMedication(id: "m1") == false)
        #expect(try await db.medication(id: "m1") == nil)
        #expect(try await db.schedule(id: "s1") == nil)
        #expect(try await db.doseLog(id: "l1") == nil)
        #expect(try await db.schedule(id: "s2") != nil)
        #expect(try await db.doseLog(id: "l2") != nil)
        #expect(try await db.medicationCount() == 1)

        try await db.wipeAllData()
        #expect(try await db.medicationCount() == 0)
        #expect(try await db.doseLogCount() == 0)
        #expect(try await db.openSchedules().isEmpty)
        #expect(try await db.meta("schema_version") == "1")
    }

    @Test func transactionRollsBackOnError() async throws {
        let db = try await MedicationsDatabase.inMemory()
        try await db.insertMedication(F.medication(id: "m1", name: "A"))
        await #expect(throws: HealthDBError.self) {
            // Unknown medication → FK violation → rollback of the whole transaction.
            try await db.insertSchedules([
                F.schedule(id: "s1", medicationID: "m1"),
                F.schedule(id: "s2", medicationID: "missing"),
            ])
        }
        #expect(try await db.schedule(id: "s1") == nil)

        try await db.insertSchedule(F.schedule(id: "s1", medicationID: "m1", activeFromMs: 1_000))
        try await db.replaceOpenSchedules(medicationID: "m1", at: 2_000, with: [F.schedule(id: "s3", medicationID: "m1", activeFromMs: 2_000)])
        let versions = try await db.schedules(medicationID: "m1", includeEnded: true)
        #expect(versions.map(\.id) == ["s1", "s3"])
        #expect(versions[0].activeUntilMs == 2_000)
        #expect(versions[1].isOpen)
    }

    @Test func enumsDegradeLenientlyForUnknownStoredValues() async throws {
        let db = try await MedicationsDatabase.inMemory()
        try await db.withConnection { connection in
            try connection.run(
                "INSERT INTO medications (\(MedicationsDatabase.medicationColumns)) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                [.text("m1"), .text("Mystery"), .null, .null, .null, .text("lozenge"), .real(2), .text("scoop"), .text("whenever"),
                 .null, .text("2026-09-01"), .null, .text("archived"), .int(0), .null, .null, .int(1), .int(1)]
            )
        }
        let stored = try #require(try await db.medication(id: "m1"))
        #expect(stored.form == .other)
        #expect(stored.doseUnit == .other)
        #expect(stored.foodRelation == .anytime)
        #expect(stored.status == .active)
        #expect(MedicationJSON.decodeStrings("not json").isEmpty)
        #expect(MedicationJSON.decodeInts("[1, \"3\", null, 7]") == [1, 3, 7])
        #expect(MedicationJSON.encode(strings: ["08:00", "20:00"]) == "[\"08:00\",\"20:00\"]")
        #expect(MedicationJSON.encode(ints: []) == "[]")
    }
}

struct MedicationPhotoStoreTests {
    private typealias F = MedicationsTestFixtures

    @Test func savesDownsampledPhotoWithThumbnailAndDeletes() throws {
        let directory = try F.temporaryDirectory().appendingPathComponent("photos", isDirectory: true)
        defer { try? FileManager.default.removeItem(at: directory.deletingLastPathComponent()) }
        let store = MedicationPhotoStore(directory: directory)
        let path = try store.save(F.jpeg(width: 2400, height: 1200), medicationID: "m1")
        #expect(path == "photos/m1.jpg")
        #expect(store.hasPhoto(medicationID: "m1"))
        #expect(HealthDatabaseLocation.isExcludedFromBackup(directory))
        let full = try #require(store.loadImage(medicationID: "m1"))
        #expect(max(full.size.width, full.size.height) <= 1024)
        let thumbnail = try #require(store.loadThumbnail(medicationID: "m1"))
        #expect(max(thumbnail.size.width, thumbnail.size.height) <= 320)
        #expect(store.url(for: path) != nil)
        #expect(store.totalSizeBytes() > 0)
        store.delete(medicationID: "m1")
        #expect(store.hasPhoto(medicationID: "m1") == false)
        #expect(store.url(for: path) == nil)
        #expect(store.loadThumbnail(medicationID: "m1") == nil)
    }

    @Test func rejectsUndecodableData() throws {
        let directory = try F.temporaryDirectory().appendingPathComponent("photos", isDirectory: true)
        defer { try? FileManager.default.removeItem(at: directory.deletingLastPathComponent()) }
        let store = MedicationPhotoStore(directory: directory)
        #expect(throws: (any Error).self) {
            try store.save(Data("definitely not an image".utf8), medicationID: "m1")
        }
        #expect(store.hasPhoto(medicationID: "m1") == false)
    }
}
