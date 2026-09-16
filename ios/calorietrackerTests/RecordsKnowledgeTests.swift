import Foundation
import Testing
import UIKit
@testable import calorietracker

enum RecordsKnowledgeFixtures {
    static var sharedMigrationURL: URL {
        HealthTestFixtures.repoRootURL.appendingPathComponent("shared/records/migrations/003_knowledge.sql")
    }

    /// The shipped catalog (Records/Resources/analytes.json, byte-identical to shared/records).
    static var catalog: AnalyteCatalog { .shared }

    static func testResult(_ name: String, _ value: String, unit: String?, low: Double?, high: Double?, flag: RecordResultFlag, confidence: Double = 0.9, page: Int = 0) -> ExtractedField {
        let ref: String? = low != nil && high != nil ? "\(RecordsFold.compactNumber(low!)) - \(RecordsFold.compactNumber(high!))" : nil
        let value = RecordTestResultValue(name: name, value: value, valueNum: Double(value), unit: unit, refText: ref, refLow: low, refHigh: high, flag: flag)
        return ExtractedField(key: .testResult, valueText: name, valueJSON: RecordsJSON.encode(value).flatMap(RJ.parse)?.compactJSON, confidence: confidence, sourcePage: page, evidence: "\(name)  \(value.value ?? "")  \(unit ?? "")")
    }

    static func labRecord(_ db: RecordsDatabase, id: String, date: String, hb: String, unit: String = "g/dL", doctor: String? = "Suresh Menon", facility: String? = "City Diagnostics") async throws {
        try await db.insert(RecordsTestFixtures.record(id: id, title: "CBC \(date)", createdMs: RecordsTestFixtures.ms(date), type: .labReport))
        var fields = [
            ExtractedField(key: .collectionDate, valueText: date, valueJSON: "{\"precision\":\"day\"}", confidence: 0.9, sourcePage: 0),
            ExtractedField(key: .reportName, valueText: "Complete Blood Count", confidence: 0.8, sourcePage: 0),
            testResult("Hb", hb, unit: unit, low: unit == "g/L" ? 130 : 13, high: unit == "g/L" ? 170 : 17, flag: .low),
            testResult("Platelets", "250", unit: "10^3/µL", low: 150, high: 410, flag: .normal),
            testResult("WBC", "6200", unit: "cells/cumm", low: 4000, high: 11000, flag: .normal),
        ]
        if let doctor { fields.append(ExtractedField(key: .doctorName, valueText: doctor, confidence: 0.85, sourcePage: 0)) }
        if let facility { fields.append(ExtractedField(key: .facility, valueText: facility, confidence: 0.8, sourcePage: 0)) }
        try await db.applyExtraction(recordID: id, RecordExtraction(fields: fields))
    }
}

// MARK: - Schema v3

struct RecordsKnowledgeMigrationTests {
    private typealias F = RecordsTestFixtures
    private typealias K = RecordsKnowledgeFixtures

    @Test func embeddedMigrationMatchesSharedFileVerbatim() throws {
        let url = K.sharedMigrationURL
        let shared = try #require(RecordsSchema.parseStatementsStrict(try String(contentsOf: url, encoding: .utf8)))
        let embedded = try #require(RecordsSchema.migrations.first { $0.version == 3 })
        #expect(embedded.fileName == url.lastPathComponent)
        #expect(shared.count == embedded.statements.count)
        for (a, b) in zip(shared, embedded.statements) {
            #expect(a == b, "statement differs from 003_knowledge.sql:\n\(a)")
        }
        #expect(RecordsSchema.schemaVersion == RecordsSchema.migrations.map(\.version).max())
    }

    @Test func freshInstallEqualsSharedFilesColumnForColumn() async throws {
        let embedded = try await RecordsDatabase.inMemory()
        let fromFiles = try await RecordsDatabase.inMemory(targetVersion: 0)
        let root = HealthTestFixtures.repoRootURL.appendingPathComponent("shared/records")
        let files = ["schema.sql", "migrations/002_intelligence.sql", "migrations/003_knowledge.sql", "migrations/004_sharing.sql"]
        let sql = try files.map { try String(contentsOf: root.appendingPathComponent($0), encoding: .utf8) }
        try await fromFiles.withConnection { connection in
            for text in sql { try connection.exec(text) }
        }
        #expect(try await embedded.tableNames() == fromFiles.tableNames())
        #expect(try await embedded.indexNames() == fromFiles.indexNames())
        for table in RecordsSchema.tableNames {
            #expect(try await embedded.tableInfo(table) == fromFiles.tableInfo(table), "table \(table) differs")
        }
        #expect(try await embedded.tableNames() == RecordsSchema.tableNames.sorted())
        #expect(try await embedded.indexNames() == RecordsSchema.indexNames.sorted())
        #expect(try await embedded.userVersion() == RecordsSchema.schemaVersion)
        #expect(try await embedded.metaValue("schema_version") == "\(RecordsSchema.schemaVersion)")
    }

    @Test func upgradesAVersionTwoDatabaseOnDiskKeepingDataAndBackfills() async throws {
        let directory = try F.temporaryDirectory()
        defer { try? FileManager.default.removeItem(at: directory) }
        let url = directory.appendingPathComponent("Ayuvo/Records/records.sqlite")
        let v2 = try await RecordsDatabase.open(url: url, targetVersion: 2)
        #expect(try await v2.userVersion() == 2)
        #expect(try await !v2.tableNames().contains("observations"))
        try await v2.withConnection { connection in
            try connection.run("INSERT INTO records (id, title, record_type, category, source, import_method, created_ms, updated_ms, document_date, document_date_method, sort_date, mime_type, file_type, notes) VALUES ('old', 'CBC Jul', 'lab_report', 'lab_reports', 'import', 'file_picker', 5, 5, '2026-07-18', 'user', '2026-07-18', 'application/pdf', 'pdf', 'fasting')")
            try connection.run(
                "INSERT INTO record_fields (id, record_id, field_key, value_text, value_json, method, confidence, state, source_page, created_ms, updated_ms) VALUES ('f1', 'old', 'test_result', 'Hb', ?, 'rules', 0.9, 'confirmed', 0, 1, 1)",
                [.text("{\"name\":\"Hb\",\"value\":\"7.2\",\"value_num\":7.2,\"unit\":\"g/dL\",\"ref_text\":\"13 - 17\",\"ref_low\":13,\"ref_high\":17,\"flag\":\"low\"}")]
            )
            try connection.run("INSERT INTO processing_jobs (record_id, stage, attempts, next_attempt_ms, awaiting_consent, updated_ms) VALUES ('old', 'done', 0, 0, 0, 1)")
        }
        await v2.close()

        let upgraded = try await RecordsDatabase.open(url: url)
        #expect(try await upgraded.userVersion() == RecordsSchema.schemaVersion)
        #expect(try await upgraded.metaValue("schema_version") == "\(RecordsSchema.schemaVersion)")
        #expect(try await upgraded.record(id: "old")?.notes == "fasting")
        #expect(try await upgraded.fields(recordID: "old").count == 1)
        #expect(try await upgraded.observations(recordID: "old").isEmpty, "promotion happens in the pipeline backfill, not the migration")
        #expect(try await upgraded.integrityCheck())
        #expect(try await upgraded.backfillKnowledgeJobsIfNeeded() == 1)
        let job = try #require(try await upgraded.job(recordID: "old"))
        #expect(job.stage == .observations)
        #expect(job.nextAttemptMs == 1, "backfill runs behind fresh imports (next_attempt_ms 0)")
        #expect(try await upgraded.backfillKnowledgeJobsIfNeeded() == 0)
        try await upgraded.syncKnowledge(recordID: "old")
        let observation = try #require(try await upgraded.observations(recordID: "old").first)
        #expect(observation.analyteID == "hemoglobin")
        #expect(observation.state == .confirmed)
        #expect(observation.observedDate == "2026-07-18")
        #expect(observation.observedDateMethod == .documentDate)
        await upgraded.close()
    }
}

// MARK: - Repository

struct RecordsKnowledgeRepositoryTests {
    private typealias F = RecordsTestFixtures
    private typealias K = RecordsKnowledgeFixtures

    @Test func promotionMapsConvertsAndFollowsFieldStates() async throws {
        let db = try await RecordsDatabase.inMemory(timeZone: TimeZone(identifier: "UTC")!)
        try await K.labRecord(db, id: "sep", date: "2026-09-12", hb: "97", unit: "g/L")
        var observations = try await db.observations(recordID: "sep")
        #expect(observations.count == 3)
        let hb = try #require(observations.first { $0.rawName == "Hb" })
        #expect(hb.analyteID == "hemoglobin")
        #expect(hb.analyteMethod == .catalog)
        #expect(hb.unit == "g/L")
        #expect(abs((hb.canonicalValue ?? 0) - 9.7) < 1e-9)
        #expect(hb.canonicalUnit == "g/dL")
        #expect(hb.observedDate == "2026-09-12")
        #expect(hb.observedDateMethod == .collectionDate)
        #expect(hb.state == .suggested)
        #expect(hb.fieldID != nil)
        #expect(hb.flag == .low)

        // A higher-confidence extraction of the same value updates the still-suggested observation.
        try await db.applyExtraction(recordID: "sep", RecordExtraction(fields: [K.testResult("Hb", "97", unit: "g/L", low: 130, high: 170, flag: .low, confidence: 0.95, page: 1)]))
        #expect(try await db.observation(id: hb.id)?.sourcePage == 1)

        // User edit: state user, field untouched, later extraction leaves it alone.
        try await db.editObservation(id: hb.id, edit: RecordObservationEdit(valueText: "9.9", unit: .some("g/dL")))
        var edited = try #require(try await db.observation(id: hb.id))
        #expect(edited.state == .user)
        #expect(edited.valueNum == 9.9 && edited.canonicalValue == 9.9)
        #expect(edited.flag == .low, "flag recomputed from the edited value against the kept printed range")
        #expect(edited.evidence != nil, "evidence kept")
        let field = try #require(try await db.fields(recordID: "sep").first { $0.id == hb.fieldID })
        #expect(field.testResult?.value == "97")
        try await db.applyExtraction(recordID: "sep", RecordExtraction(fields: [K.testResult("Hb", "97", unit: "g/L", low: 130, high: 170, flag: .low, confidence: 0.99, page: 2)]))
        edited = try #require(try await db.observation(id: hb.id))
        #expect(edited.valueText == "9.9" && edited.sourcePage == 1)

        // A field that becomes rejected rejects its (suggested) observation.
        let platelets = try #require(observations.first { $0.rawName == "Platelets" })
        try await db.setFieldState(fieldID: try #require(platelets.fieldID), state: .rejected)
        #expect(try await db.observation(id: platelets.id)?.state == .rejected)
        observations = try await db.observations(recordID: "sep", includeRejected: false)
        #expect(observations.count == 2)

        // Range edit recomputes the flag (§24).
        try await db.editObservation(id: hb.id, edit: RecordObservationEdit(refText: .some("9.0 - 12.0")))
        edited = try #require(try await db.observation(id: hb.id))
        #expect(edited.refLow == 9 && edited.refHigh == 12)
        #expect(edited.flag == .normal)

        // Remove and exclude.
        try await db.editObservation(id: hb.id, edit: RecordObservationEdit(excludedFromTrends: true))
        #expect(try await db.observation(id: hb.id)?.excludedFromTrends == true)
        try await db.removeObservation(id: hb.id)
        #expect(try await db.observation(id: hb.id)?.state == .rejected)
    }

    @Test func userAliasesPanelContextAndFTSNames() async throws {
        let db = try await RecordsDatabase.inMemory()
        try await db.insert(F.record(id: "lft", title: "Liver panel", createdMs: 1, type: .labReport))
        try await db.applyExtraction(recordID: "lft", RecordExtraction(fields: [
            K.testResult("ALT", "40", unit: "U/L", low: 0, high: 45, flag: .normal),
            K.testResult("Serum Albumin", "4.1", unit: "g/dL", low: 3.5, high: 5, flag: .normal),
            K.testResult("Protein (serum)", "7.1", unit: "g/dL", low: 6, high: 8, flag: .normal),
            K.testResult("Liver Stiffness Xq", "30", unit: "U/L", low: 0, high: 55, flag: .normal),
        ]))
        let rows = try await db.observations(recordID: "lft")
        #expect(rows.first { $0.rawName == "Protein (serum)" }?.analyteID == "total_protein", "shared alias resolved by unit / urine rule")
        #expect(rows.first { $0.rawName == "Serum Albumin" }?.analyteID == "albumin")
        let unknown = try #require(rows.first { $0.rawName == "Liver Stiffness Xq" })
        #expect(unknown.analyteID == nil)

        // "This is GGT" with remember + apply to existing.
        try await db.insert(F.record(id: "lft2", title: "Liver panel 2", createdMs: 2, type: .labReport))
        try await db.applyExtraction(recordID: "lft2", RecordExtraction(fields: [K.testResult("LIVER STIFFNESS XQ", "35", unit: "U/L", low: 0, high: 55, flag: .normal)]))
        #expect(try await db.unmappedObservations(sameNameAs: "Liver Stiffness Xq", excluding: unknown.id).count == 1)
        try await db.editObservation(id: unknown.id, edit: RecordObservationEdit(analyteID: .some("ggt"), rememberAlias: true, applyAliasToExisting: true))
        let mapped = try #require(try await db.observation(id: unknown.id))
        #expect(mapped.analyteID == "ggt" && mapped.analyteMethod == .user && mapped.state == .user)
        #expect(mapped.canonicalValue == 30 && mapped.canonicalUnit == "U/L")
        let other = try #require(try await db.observations(recordID: "lft2").first)
        #expect(other.analyteID == "ggt" && other.analyteMethod == .userAlias)
        #expect(try await db.userAliases() == ["liver stiffness xq": "ggt"])

        // Future records use the alias.
        try await db.insert(F.record(id: "lft3", title: "Liver panel 3", createdMs: 3, type: .labReport))
        try await db.applyExtraction(recordID: "lft3", RecordExtraction(fields: [K.testResult("Liver Stiffness XQ", "50", unit: "U/L", low: 0, high: 55, flag: .normal)]))
        #expect(try await db.observations(recordID: "lft3").first?.analyteMethod == .userAlias)

        // FTS clinical carries mapped display names ("Hb" printed, "hemoglobin" searched).
        try await K.labRecord(db, id: "cbc", date: "2026-09-01", hb: "9.7")
        #expect(try await db.search(query: RecordQuery(), terms: ["hemoglobin"], today: "2026-09-15").map(\.record.id) == ["cbc"])
    }

    @Test func entitiesAreRebuiltAndBackFilters() async throws {
        let db = try await RecordsDatabase.inMemory(catalog: K.catalog)
        try await K.labRecord(db, id: "a", date: "2026-08-01", hb: "8.4", doctor: "Dr. Suresh Menon", facility: "City Diagnostics")
        try await K.labRecord(db, id: "b", date: "2026-09-01", hb: "9.7", doctor: "SURESH MENON", facility: "Sunrise Clinic")
        let doctors = try await db.entities(kind: .doctor)
        #expect(doctors.count == 1)
        #expect(doctors.first?.recordCount == 2)
        #expect(doctors.first?.normalizedName == "suresh menon")
        let facilities = try await db.entities(kind: .facility)
        #expect(facilities.map(\.displayName) == ["City Diagnostics", "Sunrise Clinic"])
        var query = RecordQuery()
        query.facilityEntityID = facilities.first { $0.displayName == "Sunrise Clinic" }?.id
        #expect(try await db.page(query: query, after: nil, limit: 10).map(\.id) == ["b"])
        query = RecordQuery()
        query.doctor = "men"
        #expect(Set(try await db.page(query: query, after: nil, limit: 10).map(\.id)) == ["a", "b"])

        // Rejecting the facility field removes the record's facility entity and its orphan.
        let facilityField = try #require(try await db.fields(recordID: "b").first { $0.key == .facility })
        try await db.setFieldState(fieldID: facilityField.id, state: .rejected)
        #expect(try await db.entities(kind: .facility).map(\.displayName) == ["City Diagnostics"])
        try await db.delete(ids: ["a"])
        #expect(try await db.entities(kind: .facility).isEmpty)
        #expect(try await db.entities(kind: .doctor).first?.recordCount == 1)
    }

    @Test func linkSemantics() async throws {
        let db = try await RecordsDatabase.inMemory(catalog: K.catalog)
        for id in ["r1", "r2", "r3"] { try await db.insert(F.record(id: id, title: id, createdMs: 1)) }
        try await db.insertSuggestions(recordID: "r2", [RecordRelationSuggestion(otherID: "r1", kind: .previousReport, score: 0.7, reasons: ["same_panel"])])
        var link = try #require(try await db.link("r1", "r2"))
        #expect(link.aID == "r1" && link.bID == "r2")
        #expect(link.origin == .suggested && link.status == .suggested && link.reasons == ["same_panel"])
        // Unlinking a suggestion keeps it rejected; it never comes back.
        try await db.unlink("r2", "r1")
        #expect(try await db.link("r1", "r2")?.status == .rejected)
        #expect(try await db.insertSuggestions(recordID: "r1", [RecordRelationSuggestion(otherID: "r2", kind: .previousReport, score: 0.9, reasons: [])]) == 0)
        #expect(try await db.relatedRecords(recordID: "r1").isEmpty)
        // A user link replaces the rejected suggestion; unlink deletes the user row.
        try await db.setUserLink("r2", "r1", kind: .followUp)
        link = try #require(try await db.link("r1", "r2"))
        #expect(link.origin == .user && link.status == .accepted && link.kind == .followUp)
        #expect(try await db.relatedRecords(recordID: "r2").map(\.record.id) == ["r1"])
        #expect(try await db.episodeNeighbours(recordIDs: ["r1", "r3"]) == ["r1": ["r2"]])
        try await db.unlink("r1", "r2")
        #expect(try await db.link("r1", "r2") == nil)
        // Accepting a suggestion.
        try await db.insertSuggestions(recordID: "r3", [RecordRelationSuggestion(otherID: "r1", kind: .sameEpisode, score: 0.6, reasons: ["same_doctor", "same_facility"])])
        try await db.acceptSuggestion("r1", "r3")
        #expect(try await db.link("r3", "r1")?.status == .accepted)
        // At most five open suggestions per record.
        for index in 0..<8 { try await db.insert(F.record(id: "x\(index)", title: "x", createdMs: 1)) }
        let many = (0..<8).map { RecordRelationSuggestion(otherID: "x\($0)", kind: .related, score: Double($0) / 10, reasons: []) }
        #expect(try await db.insertSuggestions(recordID: "r2", many) == 5)
        #expect(try await db.relatedRecords(recordID: "r2").first?.record.id == "x7")
        // Links cascade on delete.
        try await db.delete(ids: ["r3"])
        #expect(try await db.link("r1", "r3") == nil)
    }

    @Test func trendsCollapseConvertAndUseTheIndex() async throws {
        let db = try await RecordsDatabase.inMemory(timeZone: TimeZone(identifier: "UTC")!)
        try await K.labRecord(db, id: "jul", date: "2026-07-18", hb: "7.2")
        try await K.labRecord(db, id: "aug", date: "2026-08-10", hb: "84", unit: "g/L")
        try await K.labRecord(db, id: "sep", date: "2026-09-12", hb: "9.7")
        try await K.labRecord(db, id: "sepcopy", date: "2026-09-12", hb: "9.7")
        let trend = try await db.trend(analyteID: "hemoglobin")
        #expect(trend.items.count == 4)
        #expect(trend.series.count == 1)
        let series = try #require(trend.series.first)
        #expect(series.unit == "g/dL" && series.isCanonical)
        #expect(series.points.map(\.value) == [7.2, 8.4, 9.7])
        #expect(Set(series.points.last?.recordIDs ?? []) == ["sep", "sepcopy"], "same-day duplicates list every source record")
        #expect(series.bandLow == 13 && series.bandHigh == 17)
        #expect(abs((series.points[1].refLow ?? 0) - 13) < 1e-9, "each point keeps its own converted range")

        let detail = try #require(try await db.detail(id: "sep"))
        let hb = try #require(detail.observations.first { $0.analyteID == "hemoglobin" })
        let mini = try #require(detail.miniTrends[hb.id])
        #expect(mini.text == "7.2 → 8.4 → 9.7")
        #expect(mini.pointCount == 3)
        #expect(mini.change?.hasPrefix("+1.3 g/dL since") == true)
        #expect(detail.miniTrends[detail.observations.first { $0.rawName == "Platelets" }!.id] != nil)

        // Exclude removes the point; unknown units form their own series.
        let aug = try #require(try await db.observations(recordID: "aug").first { $0.analyteID == "hemoglobin" })
        try await db.editObservation(id: aug.id, edit: RecordObservationEdit(excludedFromTrends: true))
        try await db.addUserObservation(recordID: "jul", draft: RecordObservationDraft(analyteID: "hemoglobin", name: "", valueText: "4.4", unit: "mmol/L", observedDate: "2026-07-01"))
        let after = try await db.trend(analyteID: "hemoglobin")
        #expect(after.series.first?.points.map(\.value) == [7.2, 9.7])
        #expect(after.series.contains { $0.unit == "mmol/L" && !$0.isCanonical })

        let plan = try await db.trendQueryPlan(analyteID: "hemoglobin")
        #expect(plan.contains { $0.contains("idx_observations_trend") }, "plan: \(plan)")
    }

    @Test func valuesSearchAndAnalyteConditions() async throws {
        let db = try await RecordsDatabase.inMemory(timeZone: TimeZone(identifier: "UTC")!)
        try await K.labRecord(db, id: "jul", date: "2026-07-18", hb: "7.2")
        try await K.labRecord(db, id: "ok", date: "2026-08-18", hb: "14.1")
        let okHb = try #require(try await db.observations(recordID: "ok").first { $0.analyteID == "hemoglobin" })
        try await db.editObservation(id: okHb.id, edit: RecordObservationEdit(refText: .some("13 - 17")))

        let parsed = RecordQueryParser.parse("reports where hemoglobin was low", today: "2026-09-15", order: .dmy)
        #expect(parsed.analyteConditions.map(\.analyteID) == ["hemoglobin"] && parsed.analyteConditions.first?.flag == "low")
        #expect(parsed.terms.isEmpty)
        #expect(parsed.flags.isEmpty, "the flag belongs to the analyte condition")
        #expect(parsed.recordTypes.contains(.labReport))
        #expect(parsed.chips.contains { if case .analyte = $0 { true } else { false } })
        let query = parsed.applied(to: RecordQuery())
        #expect(try await db.page(query: query, after: nil, limit: 10).map(\.id) == ["jul"])
        let hits = try await db.valueHits(conditions: parsed.analyteConditions)
        #expect(hits.map(\.record.id) == ["jul"])

        let numeric = RecordQueryParser.parse("hb above 12 g/dL", today: "2026-09-15", order: .dmy)
        #expect(numeric.analyteConditions.first?.op == ">" && numeric.analyteConditions.first?.canonicalValue == 12)
        #expect(try await db.valueHits(conditions: numeric.analyteConditions).map(\.record.id) == ["ok"])
        let converted = RecordQueryParser.parse("hemoglobin < 100 g/L", today: "2026-09-15", order: .dmy)
        #expect(try await db.recordIDs(matching: converted.analyteConditions) == ["jul"])

        let bare = RecordQueryParser.parse("haemoglobin", today: "2026-09-15", order: .dmy)
        #expect(bare.terms == ["haemoglobin"], "a bare alias is still an FTS term")
        #expect(bare.analyteConditions.map(\.analyteID) == ["hemoglobin"] && bare.analyteConditions.first?.isBare == true)
        #expect(Set(try await db.valueHits(conditions: bare.analyteConditions).map(\.record.id)) == ["jul", "ok"])
    }
}

// MARK: - Pure pieces

struct RecordsKnowledgeUnitTests {
    private typealias K = RecordsKnowledgeFixtures

    @Test func normalizationMappingAndConversion() {
        #expect(RR.normalizeTestNameRef("Haemoglobin (Hb) [Photometry]") == "haemoglobin hb")
        #expect(RR.testNameKeys("Sr. Creatinine (Jaffe)") == ["sr creatinine jaffe", "sr creatinine", "creatinine"])
        #expect(RR.testNameKeys("Serum Albumin") == ["serum albumin", "albumin"])
        #expect(RR.analyteWords("S.G.O.T.") == ["sgot"])
        #expect(RR.mapAnalyteRef("HGB")["analyte_id"].string == "hemoglobin")
        #expect(RR.mapAnalyteRef("Plasma Albumin")["analyte_id"].string == "albumin")
        #expect(RR.mapAnalyteRef("Hemoglobinx")["analyte_id"].isNull, "never fuzzy")
        #expect(RR.mapAnalyteRef("Hb", userAliases: ["hb": "wbc_count"])["method"].string == "user_alias")
        #expect(RR.convertUnit("hemoglobin", 97, "gm/L")["canonical_value"].double == 9.7)
        #expect(RR.convertUnit("hemoglobin", 9.7, "mmol/L")["status"].string == "unit_unknown")
        #expect(RR.convertUnit("hemoglobin", 9.7, nil)["status"].string == "unit_missing")
        #expect(RR.detectPanelsRef(texts: ["Complete Blood Count"], testNames: []) == ["cbc"])
        #expect(AnalyteCatalog.shared.search("haem").contains { $0.id == "hemoglobin" })
        #expect(AnalyteCatalog.shared.analytes.count > 100)
    }

    @Test func editRecomputesFlags() {
        #expect(RR.recomputeFlag("7.2", "13 - 17", 13, 17) == "low")
        #expect(RR.recomputeFlag("18", "(13.0-17.0)", 13, 17) == "high")
        #expect(RR.recomputeFlag("180", "< 200", nil, 200) == "normal")
        #expect(RR.recomputeFlag("Negative", "Negative", nil, nil) == "normal")
        #expect(RR.recomputeFlag("9.7", nil, nil, nil) == "unknown")
        #expect(RR.parseRefText("150000 - 410000") == (150_000, 410_000))
    }

    @Test func relationSuggesterScoresSignals() {
        func profile(_ id: String, _ type: RecordType, _ date: String, panels: [String] = [], analytes: [String] = [], doctors: [String] = [], followUps: [String] = []) -> RecordRelationProfile {
            RecordRelationProfile(id: id, recordType: type, sortDate: date, archived: false, splitParent: false, reportName: nil, panels: panels, analytes: analytes, doctors: doctors, facilities: [], followUpDates: followUps)
        }
        let cbc = ["hemoglobin", "wbc_count", "platelet_count"]
        let sep = profile("sep", .labReport, "2026-09-12", panels: ["cbc"], analytes: cbc, doctors: ["suresh menon"])
        let aug = profile("aug", .labReport, "2026-08-10", panels: ["cbc"], analytes: cbc)
        let old = profile("old", .labReport, "2025-01-10", panels: ["cbc"], analytes: cbc)
        let thin = profile("thin", .labReport, "2026-09-01", panels: ["cbc"], analytes: ["hemoglobin"])
        let visit = profile("visit", .consultationNote, "2026-09-05", doctors: ["suresh menon"], followUps: ["2026-09-13"])
        let rx = profile("rx", .prescription, "2026-09-08", doctors: ["suresh menon"])
        let result = RecordRelationSuggester.suggest(for: sep, candidates: [aug, old, thin, visit, rx], existingLinks: [], today: "2026-09-15")
        #expect(result.first { $0.otherID == "aug" }?.kind == .previousReport)
        #expect(result.first { $0.otherID == "aug" }?.score == 0.7)
        #expect(!result.contains { $0.otherID == "old" }, "outside ±180 days")
        #expect(!result.contains { $0.otherID == "thin" }, "0.5 alone stays below 0.6")
        let visitLink = result.first { $0.otherID == "visit" }
        #expect(visitLink?.kind == .followUp)
        #expect(visitLink?.score == 1.0)
        let consult = profile("consult", .consultationNote, "2026-09-05", doctors: ["suresh menon"])
        #expect(RecordRelationSuggester.suggest(for: rx, candidates: [consult], existingLinks: [], today: "2026-09-15").first?.kind == .prescriptionFor)
        let rejected = RecordLink(aID: "aug", bID: "sep", kind: .previousReport, origin: .suggested, status: .rejected, score: 0.7, reasons: [], createdMs: 0, updatedMs: 0)
        #expect(!RecordRelationSuggester.suggest(for: sep, candidates: [aug], existingLinks: [rejected], today: "2026-09-15").contains { $0.otherID == "aug" })
    }

    @Test func sourceLocatorOutlinesTheEvidenceRow() {
        let blocks = """
        [{"t":"Complete Blood Count","b":[0.1,0.05,0.4,0.03]},{"t":"Hemoglobin","b":[0.1,0.3,0.2,0.02]},{"t":"9.7","b":[0.4,0.301,0.05,0.02]},{"t":"g/dL","b":[0.55,0.3,0.06,0.02]},{"t":"13.0 - 17.0","b":[0.7,0.302,0.15,0.02]},{"t":"Platelets","b":[0.1,0.34,0.2,0.02]}]
        """
        let page = RecordPage(recordID: "r", pageIndex: 1, text: nil, textSource: .pdfText, blocksJSON: blocks)
        let box = RecordSourceLocator.box(evidence: "Hemoglobin 9.7 g/dL 13.0 - 17.0", page: 1, pages: [page])
        #expect(box.map { $0.map { RecordsMath.pyRound($0, 3) } } == [0.1, 0.3, 0.75, 0.022])
        #expect(RecordSourceLocator.box(evidence: "Missing row", page: 1, pages: [page]) == nil)
    }
}

// MARK: - Pipeline

@Suite(.serialized)
struct RecordsKnowledgePipelineTests {
    private typealias I = RecordsIntelligenceFixtures
    private typealias K = RecordsKnowledgeFixtures

    @Test func threeCBCReportsBecomeATrendWithSuggestedLinks() async throws {
        let directory = try RecordsTestFixtures.temporaryDirectory()
        defer { try? FileManager.default.removeItem(at: directory) }
        let db = try await RecordsDatabase.inMemory(catalog: K.catalog)
        let repository = RecordsRepository(database: db, files: RecordFileStore(root: directory.appendingPathComponent("files")))
        var ids: [String] = []
        for (value, date) in [("7.2", "18/07/2026"), ("8.4", "10/08/2026"), ("9.7", "12/09/2026")] {
            let rows = [I.Row(name: "Hemoglobin", value: value, unit: "g/dL", range: "13.0 - 17.0"),
                        I.Row(name: "Total WBC Count", value: "6200", unit: "cells/cumm", range: "4000 - 11000"),
                        I.Row(name: "Platelet Count", value: "250", unit: "10^3/µL", range: "150 - 410")]
            let record = try await I.importPDF(I.labPDF(rows: rows, date: date), repository: repository, name: "cbc_\(value).pdf")
            ids.append(record.id)
        }
        let queue = RecordProcessingQueue(repository: repository, dependencies: I.dependencies(mode: .off))
        for id in ids {
            await queue.enqueue(ids: [id])
            await queue.drain()
        }
        let trend = try await db.trend(analyteID: "hemoglobin")
        #expect(trend.series.first?.points.map(\.value) == [7.2, 8.4, 9.7])
        let sep = try #require(try await db.detail(id: ids[2]))
        #expect(sep.job?.stage == .done)
        let hb = try #require(sep.observations.first { $0.analyteID == "hemoglobin" })
        #expect(hb.observedDate == "2026-09-12")
        #expect(hb.evidence?.contains("Hemoglobin") == true)
        #expect(sep.miniTrends[hb.id]?.text == "7.2 → 8.4 → 9.7")
        #expect(sep.related.contains { $0.link.kind == .previousReport && $0.link.status == .suggested }, "related: \(sep.related.map { ($0.record.title, $0.link.kind.rawValue, $0.link.score) })")
        let box = RecordSourceLocator.box(evidence: hb.evidence, page: hb.sourcePage ?? 0, pages: sep.pages)
        #expect(box != nil, "the evidence row is located on the page")
        #expect(try await db.entities(kind: .facility).first?.recordCount == 3)
    }
}
