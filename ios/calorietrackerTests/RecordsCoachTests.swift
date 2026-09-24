import Foundation
import Testing
@testable import calorietracker

enum RecordsCoachFixtures {
    typealias F = RecordsTestFixtures
    typealias K = RecordsKnowledgeFixtures

    /// Three CBCs (Jul / Aug / Sep) with a patient name, a referrer and page text containing identifiers,
    /// plus one archived prescription.
    static func database() async throws -> RecordsDatabase {
        let db = try await RecordsDatabase.inMemory(timeZone: TimeZone(identifier: "UTC")!)
        for (id, date, hb) in [("cbc-jul", "2026-07-18", "7.2"), ("cbc-aug", "2026-08-10", "8.4"), ("cbc-sep", "2026-09-12", "9.7")] {
            try await K.labRecord(db, id: id, date: date, hb: hb)
            try await db.applyExtraction(recordID: id, RecordExtraction(fields: [
                ExtractedField(key: .patientName, valueText: "Asha Rao", confidence: 0.75, sourcePage: 0),
                ExtractedField(key: .patientSex, valueText: "female", confidence: 0.75, sourcePage: 0),
                ExtractedField(key: .doctorName, valueText: "Kavita Shah", valueJSON: "{\"role\":\"referrer\"}", confidence: 0.85, sourcePage: 0),
            ]))
            let fields = try await db.fields(recordID: id)
            try await db.replaceRuleHighlights(recordID: id, RecordHighlightBuilder.build(fields: fields))
            try await db.storePages(recordID: id, pages: [RecordPage(
                recordID: id, pageIndex: 0,
                text: "City Diagnostics Laboratory\nPatient Name: Asha Rao  Age: 34 Y\nPhone: +91 98765 43210\nHemoglobin  \(hb)  g/dL  13.0 - 17.0\nAsha Rao was advised iron.",
                textSource: .pdfText
            )], pageCount: 1)
            try await db.reindex(recordID: id)
        }
        try await db.insert(F.record(id: "rx-old", title: "Old prescription", documentDate: "2026-01-02", createdMs: F.ms("2026-01-02"), type: .prescription, archived: true))
        return db
    }

    static func context(_ db: RecordsDatabase, selected: [String] = [], enabled: Bool = true, session: CoachRecordsSession = CoachRecordsSession()) async throws -> CoachRecordsContext {
        guard enabled else {
            var disabled = CoachRecordsContext.disabled
            disabled.session = session
            return disabled
        }
        let records = try await db.coachRecords(ids: selected)
        return CoachRecordsContext(
            enabled: true, selected: records.map(ChatRecordRef.init), database: db, today: "2026-09-15", dateOrder: "dmy",
            prompt: try await db.coachPromptLines(accessEnabled: true, selectedIDs: selected),
            packed: selected.isEmpty ? .null : try await db.coachPack(selectedIDs: selected),
            session: session
        )
    }

    static func json(_ text: String) throws -> RJ {
        try #require(RJ.parse(text))
    }

    static func keys(_ value: RJ) -> Set<String> { Set(value.object?.keys.map { $0 } ?? []) }
}

// MARK: - Contract

struct RecordsCoachContractTests {
    private var sharedURL: URL { HealthTestFixtures.repoRootURL.appendingPathComponent("shared/records/coach_tools.json") }

    @Test func bundledCopyIsByteIdenticalToSharedFile() throws {
        let shared = try Data(contentsOf: sharedURL)
        // The app sources sit next to this test target's folder (`<app>Tests` → `<app>`).
        let testsFolder = URL(fileURLWithPath: #filePath).deletingLastPathComponent()
        let appFolder = testsFolder.deletingLastPathComponent().appendingPathComponent(testsFolder.lastPathComponent.replacingOccurrences(of: "Tests", with: ""))
        let copy = try Data(contentsOf: appFolder.appendingPathComponent("Records/Resources/coach_tools.json"))
        #expect(shared == copy, "Records/Resources/coach_tools.json differs from shared/records/coach_tools.json")
        let bundled = try #require(Bundle.main.url(forResource: "coach_tools", withExtension: "json"), "coach_tools.json is in the app bundle")
        #expect(try Data(contentsOf: bundled) == shared)
    }

    @Test func advertisedNamesDescriptionsAndSchemasAreTheFileText() throws {
        let text = String(decoding: try Data(contentsOf: sharedURL), as: UTF8.self)
        let root = try #require(RJ.parse(text))
        let tools = try #require(root["tools"].array)
        let contract = RecordsCoachContract.shared
        #expect(contract.names == tools.compactMap { $0["name"].string })
        #expect(CoachTools.recordsToolNames == ["records_search", "records_get", "records_observation_series"])
        let raw = RecordsCoachContract.rawSchemaTexts(text)
        #expect(raw.count == tools.count)
        for (index, tool) in tools.enumerated() {
            let name = try #require(tool["name"].string)
            #expect(CoachTools.toolDescriptions[name] == tool["description"].string, "description of \(name)")
            #expect(contract.tool(name)?.schemaText == raw[index], "schema text of \(name)")
            #expect(RJ.same(RJ.from(CoachTools.parameterSchema(for: name)), tool["input_schema"]), "provider schema of \(name)")
            #expect(RJ.same(try #require(RJ.parse(raw[index])), tool["input_schema"]))
            #expect(RJ.parse(raw[index])?.compactJSON.count ?? 0 > 0)
        }
    }

    @Test func promptAndErrorStringsComeFromTheFile() throws {
        let root = try #require(RJ.parse(String(decoding: try Data(contentsOf: sharedURL), as: UTF8.self)))
        let contract = RecordsCoachContract.shared
        for key in ["available_line", "selected_header", "selected_line", "not_available_line", "guardrails"] {
            #expect(contract.promptText(key) == root["prompt"][key].string, "prompt.\(key)")
        }
        #expect(RR.coachError("unknown_record", ["id": "x1"])["error"].string == "unknown record_id 'x1'; call records_search")
        #expect(RR.coachError("not_selected", ["id": "x1"])["error"].string == "record 'x1' is not in the records the user selected for this conversation")
        #expect(RR.coachError("unknown_analyte", ["analyte": "zz"])["error"].string == "no values found for 'zz'")
        #expect(RR.coachError("bad_date", ["value": "2026-13-01"])["error"].string == "invalid date '2026-13-01' (expected yyyy-MM-dd)")
        #expect(RR.coachError("date_order")["error"].string == "from must be on or before to")
        #expect(RR.coachError("unavailable")["error"].string == "health records are not available")
        #expect(RR.fillPlaceholders("{title} on {date}", ["title": "A {date}", "date": "2026-09-01"]) == "A {date} on 2026-09-01", "one pass")
    }

    @Test func consentPreferencesAreDeviceLocal() {
        #expect(RecordsStore.coachAccessKey == "healthRecordsCoachAccessEnabled")
        #expect(RecordsStore.coachConsentedAtKey == "healthRecordsCoachConsentedAt")
        #expect(!CloudBackupPolicy.include(RecordsStore.coachAccessKey))
        #expect(!CloudBackupPolicy.include(RecordsStore.coachConsentedAtKey))
        #expect(CloudBackupPolicy.excludedKeys.contains("coachChatHistory"), "record refs live in the chat history, which stays on the device")
    }

    @MainActor
    @Test func consentIsStoredOnlyByTheExplicitSetter() throws {
        let suite = "records-coach-\(UUID().uuidString)"
        let defaults = try #require(UserDefaults(suiteName: suite))
        defer { defaults.removePersistentDomain(forName: suite) }
        let directory = try RecordsTestFixtures.temporaryDirectory()
        defer { try? FileManager.default.removeItem(at: directory) }
        let store = RecordsStore(defaults: defaults, databaseURL: directory.appendingPathComponent("records.sqlite"), inboxRoot: { nil })
        #expect(!store.coachAccessEnabled)
        #expect(defaults.object(forKey: RecordsStore.coachAccessKey) == nil)
        store.setCoachAccess(true)
        #expect(defaults.bool(forKey: RecordsStore.coachAccessKey))
        let stamp = try #require(defaults.string(forKey: RecordsStore.coachConsentedAtKey))
        #expect(ISO8601DateFormatter().date(from: stamp) != nil)
        store.setCoachAccess(false)
        #expect(!defaults.bool(forKey: RecordsStore.coachAccessKey))
        let reloaded = RecordsStore(defaults: defaults, databaseURL: directory.appendingPathComponent("records.sqlite"), inboxRoot: { nil })
        #expect(!reloaded.coachAccessEnabled)
    }
}

// MARK: - Gating & tools on a real store

@MainActor
struct RecordsCoachToolTests {
    private typealias X = RecordsCoachFixtures

    private func tools(_ records: CoachRecordsContext?) -> CoachTools {
        CoachTools(weights: [], bodyFats: [], foods: [], records: records)
    }

    @Test func toolsAreAdvertisedOnlyWithAccessAndRecords() async throws {
        let db = try await X.database()
        #expect(!tools(nil).availableToolNames.contains("records_get"))
        #expect(!tools(try await X.context(db, enabled: false)).availableToolNames.contains("records_search"))
        let empty = try await RecordsDatabase.inMemory()
        #expect(!tools(try await X.context(empty)).availableToolNames.contains("records_search"), "no non-archived record → no tools")
        let names = tools(try await X.context(db)).availableToolNames
        #expect(Array(names.suffix(3)) == CoachTools.recordsToolNames)
        let disabled = try X.json(await tools(try await X.context(db, enabled: false)).executeAsync(name: "records_get", arguments: ["record_id": "cbc-sep"]))
        #expect(disabled["error"].string == "health records are not available")
    }

    @Test func searchReturnsRecordsAndValuesWithoutIdentifiers() async throws {
        let db = try await X.database()
        let t = tools(try await X.context(db))
        let payload = try X.json(await t.executeAsync(name: "records_search", arguments: ["query": "hemoglobin was low", "limit": 2]))
        #expect(payload["query"].string == "hemoglobin was low")
        let records = try #require(payload["records"].array)
        #expect(records.count == 2)
        #expect(payload["count"].double == 2)
        #expect(records.first?["record_id"].string == "cbc-sep", "newest first")
        #expect(X.keys(records[0]) == ["record_id", "title", "date", "record_type", "facility", "doctor", "review_status", "highlights"])
        #expect(records[0]["doctor"].string == "Suresh Menon")
        #expect(records[0]["facility"].string == "City Diagnostics")
        let values = try #require(payload["values"].array)
        #expect(values.count == 3)
        #expect(X.keys(values[0]) == ["record_id", "analyte", "name", "value", "unit", "flag", "date"])
        #expect(values.allSatisfy { $0["analyte"].string == "hemoglobin" && $0["flag"].string == "low" })
        let ranked = try X.json(await t.executeAsync(name: "records_search", arguments: ["query": "diagnostics"]))
        #expect(ranked["count"].double == 3, "FTS terms match through the index")
        let all = try X.json(await t.executeAsync(name: "records_search", arguments: ["query": ""]))
        #expect(all["count"].double == 3, "archived records are not listed")
        #expect(!all.jsonText.contains("Asha"), "patient name never appears")
        let bad = try X.json(await t.executeAsync(name: "records_search", arguments: ["query": "cbc", "from": "2026-9-1"]))
        #expect(bad["error"].string == "invalid date '2026-9-1' (expected yyyy-MM-dd)")
        let order = try X.json(await t.executeAsync(name: "records_search", arguments: ["query": "cbc", "from": "2026-09-01", "to": "2026-08-01"]))
        #expect(order["error"].string == "from must be on or before to")
        let ranged = try X.json(await t.executeAsync(name: "records_search", arguments: ["query": "", "from": "2026-08-01", "to": "2026-08-31"]))
        #expect(ranged["records"].array?.map { $0["record_id"].string } == ["cbc-aug"])
    }

    @Test func getPayloadShapeExcludesPatientIdentifiers() async throws {
        let db = try await X.database()
        let t = tools(try await X.context(db))
        let payload = try X.json(await t.executeAsync(name: "records_get", arguments: ["record_id": "cbc-aug", "include_text": true]))
        #expect(X.keys(payload) == ["record_id", "title", "date", "record_type", "category", "facility", "doctor", "referrer", "patient_sex", "patient_age", "review_status", "ai_mode_used", "page_count", "fields", "test_results", "highlights", "text"])
        #expect(payload["date"].string == "2026-08-10")
        #expect(payload["referrer"].string == "Kavita Shah")
        #expect(payload["doctor"].string == "Suresh Menon")
        #expect(payload["patient_sex"].string == "female")
        let fields = try #require(payload["fields"].array)
        #expect(!fields.contains { ["patient_name", "test_result"].contains($0["key"].string ?? "") })
        let results = try #require(payload["test_results"].array)
        #expect(results.count == 3)
        let hb = try #require(results.first { $0["analyte"].string == "hemoglobin" })
        #expect(hb["value"].string == "8.4")
        #expect(hb["flag"].string == "low")
        let text = try #require(payload["text"].string)
        #expect(text.contains("Hemoglobin"))
        #expect(!text.contains("Asha") && !text.contains("98765"), "excerpt drops identifiers: \(text)")
        #expect(!payload.jsonText.contains("Asha"))
        let noText = try X.json(await t.executeAsync(name: "records_get", arguments: ["record_id": "cbc-aug", "include_text": "true"]))
        #expect(noText["text"].isNull, "include_text must be exactly true")
        let unknown = try X.json(await t.executeAsync(name: "records_get", arguments: ["record_id": "nope"]))
        #expect(unknown["error"].string == "unknown record_id 'nope'; call records_search")
    }

    @Test func seriesIsOldestFirstAndResolvesPrintedNames() async throws {
        let db = try await X.database()
        let t = tools(try await X.context(db))
        let payload = try X.json(await t.executeAsync(name: "records_observation_series", arguments: ["analyte": "Hb"]))
        #expect(payload["analyte"].string == "hemoglobin")
        #expect(payload["unit"].string == "g/dL")
        let points = try #require(payload["points"].array)
        #expect(points.map { $0["value"].string } == ["7.2", "8.4", "9.7"])
        #expect(X.keys(points[0]) == ["date", "value", "unit", "canonical_value", "flag", "ref_low", "ref_high", "record_id", "record_title", "state"])
        let ranged = try X.json(await t.executeAsync(name: "records_observation_series", arguments: ["analyte": "hemoglobin", "from": "2026-08-01"]))
        #expect(ranged["count"].double == 2)
        let unknown = try X.json(await t.executeAsync(name: "records_observation_series", arguments: ["analyte": "zzz"]))
        #expect(unknown["error"].string == "no values found for 'zzz'")
    }

    @Test func selectionRestrictsEveryToolAndRefsAreCollected() async throws {
        let db = try await X.database()
        let session = CoachRecordsSession()
        let t = tools(try await X.context(db, selected: ["cbc-sep", "cbc-aug"], session: session))
        let search = try X.json(await t.executeAsync(name: "records_search", arguments: ["query": "hemoglobin was low"]))
        #expect(Set(search["records"].array?.compactMap { $0["record_id"].string } ?? []) == ["cbc-sep", "cbc-aug"])
        #expect(Set(search["values"].array?.compactMap { $0["record_id"].string } ?? []) == ["cbc-sep", "cbc-aug"])
        let blocked = try X.json(await t.executeAsync(name: "records_get", arguments: ["record_id": "cbc-jul"]))
        #expect(blocked["error"].string == "record 'cbc-jul' is not in the records the user selected for this conversation")
        let hidden = try X.json(await t.executeAsync(name: "records_get", arguments: ["record_id": "does-not-exist"]))
        #expect(hidden["error"].string == "record 'does-not-exist' is not in the records the user selected for this conversation", "never confirms existence")
        let series = try X.json(await t.executeAsync(name: "records_observation_series", arguments: ["analyte": "hemoglobin"]))
        #expect(series["points"].array?.map { $0["record_id"].string } == ["cbc-aug", "cbc-sep"])
        #expect(await session.refs.map(\.recordID) == ["cbc-aug", "cbc-sep"], "series point records; search adds none")
        _ = await t.executeAsync(name: "records_get", arguments: ["record_id": "cbc-sep"])
        #expect(await session.refs.map(\.recordID) == ["cbc-aug", "cbc-sep"], "duplicates are merged")
        #expect(await session.refs.first?.title == "CBC 2026-08-10")
    }

    @Test func onlineApprovalCancelBlocksTheTools() async throws {
        let db = try await X.database()
        let session = CoachRecordsSession(needsOnlineApproval: true, requestApproval: { .cancel })
        let payload = try X.json(await tools(try await X.context(db, session: session)).executeAsync(name: "records_get", arguments: ["record_id": "cbc-sep"]))
        #expect(payload["error"].string == "health records are not available")
        #expect(await session.refs.isEmpty)
        let approved = CoachRecordsSession(needsOnlineApproval: true, requestApproval: { .send })
        let ok = try X.json(await tools(try await X.context(db, session: approved)).executeAsync(name: "records_get", arguments: ["record_id": "cbc-sep"]))
        #expect(ok["record_id"].string == "cbc-sep")
    }

    @Test func compareCandidatePrefersPreviousReportLinks() async throws {
        let db = try await X.database()
        #expect(try await db.coachCompareCandidate(recordID: "cbc-jul") == nil, "nothing older")
        try await db.setUserLink("cbc-sep", "cbc-jul", kind: .previousReport)
        #expect(try await db.coachCompareCandidate(recordID: "cbc-sep")?.id == "cbc-jul", "a previous_report link wins over shared analytes")
        let chips = try await db.coachLatestLabSelection()
        #expect(chips.latest.map(\.id) == ["cbc-sep", "cbc-aug", "cbc-jul"])
        #expect(chips.compare.map(\.id) == ["cbc-sep", "cbc-jul"])
    }

    @Test func promptPiecesAndPackedBlockComeFromTheStore() async throws {
        let db = try await X.database()
        let context = try await X.context(db, selected: ["cbc-jul", "cbc-sep"])
        let lines = ChatService.recordsPromptLines(context, newUserMessage: "hi")
        #expect(lines.first == "- 3 health records are stored in Ayuvo (latest 2026-09-12). Use records_search, records_get and records_observation_series to read them.")
        #expect(lines.contains("Health records guardrails:"))
        #expect(Array(lines.suffix(3)) == [
            "The user selected these health records for this conversation. Only these records are available to the tools:",
            "- cbc-sep: CBC 2026-09-12 — 2026-09-12 (Lab Report)",
            "- cbc-jul: CBC 2026-07-18 — 2026-07-18 (Lab Report)",
        ])
        let block = ChatService.onDeviceRecordsBlock(context)
        #expect(block.hasPrefix("\n\n## Health records (selected by the user)\n### CBC 2026-09-12 — 2026-09-12 (Lab Report)"))
        #expect(!block.contains("Asha"))
        #expect(block.hasSuffix("\n\n" + (RecordsCoachContract.shared.prompt["guardrails"] ?? "")), "§32 guardrails follow the packed block")
        #expect(context.packedRefs.map(\.recordID) == ["cbc-sep", "cbc-jul"])
        #expect(ChatService.recordsPromptLines(.disabled, newUserMessage: "How did I sleep?").isEmpty)
        #expect(ChatService.recordsPromptLines(.disabled, newUserMessage: "show my lab reports") == ["- No health records are available: the user has not allowed Coach to use Health Records."])
    }

    /// §26: an assistant reply persists the records it relied on. Messages live in `coach.sqlite`
    /// now, where this list is the `record_refs_json` column, so the wire shape is what matters.
    @Test func recordRefsKeepTheirPersistedShape() throws {
        let refs = [ChatRecordRef(recordID: "r1", title: "CBC", date: "2026-09-12")]
        let text = try #require(CoachRepository.encodeRecordRefs(refs))
        #expect(text.contains("\"record_id\":\"r1\""))
        #expect(!text.contains("record_type"))
        #expect(CoachRepository.decodeRecordRefs(text)?.first?.recordID == "r1")
        // A reply that used no records stores nothing at all, and such a row reads back as none.
        #expect(CoachRepository.encodeRecordRefs([]) == nil)
        #expect(CoachRepository.decodeRecordRefs(nil) == nil)
        #expect(CoachRepository.decodeRecordRefs("[]") == nil)
    }

    /// The legacy `coachChatHistory` blob must still decode so §12 can move it in.
    @Test func legacyHistoryStillDecodes() throws {
        let old = #"[{"id":"11111111-2222-3333-4444-555555555555","role":"assistant","content":"hi","timestamp":0}]"#
        let legacy = try JSONDecoder().decode([LegacyChatMessage].self, from: Data(old.utf8))
        #expect(legacy.first?.content == "hi")
        #expect(legacy.first?.recordRefs == nil)
        let withRefs = #"[{"role":"assistant","content":"Hb improved","record_refs":[{"record_id":"r1","title":"CBC","date":"2026-09-12"}]}]"#
        let decoded = try JSONDecoder().decode([LegacyChatMessage].self, from: Data(withRefs.utf8))
        #expect(decoded.first?.recordRefs?.first?.recordID == "r1")
    }
}

// MARK: - Shared vectors (coach_tools_payloads.json, coach_context.json, coach_prompt.json)

struct RecordsCoachVectorTests {
    static let files = ["coach_tools_payloads", "coach_context", "coach_prompt"]

    static func load(_ name: String) throws -> RJ {
        let url = RecordsVectorTests.vectorsDirectory.appendingPathComponent("\(name).json")
        return try #require(RJ.parse(try String(contentsOf: url, encoding: .utf8)), "unreadable \(name).json")
    }

    @Test(arguments: files)
    func coachVectorFileMatchesReference(_ name: String) throws {
        let root = try Self.load(name)
        let function = try #require(root["function"].string)
        let cases = root["cases"].array ?? []
        #expect(!cases.isEmpty)
        var passed = 0
        var failures: [String] = []
        for c in cases {
            let actual = RR.runCoachCase(function: function, input: c["input"], fixtures: root["fixtures"])
            if let diff = RecordsVectorTests.firstDifference(actual, c["expected"]) {
                failures.append("\(c["name"].string ?? "?"): \(diff)")
            } else {
                passed += 1
            }
        }
        print("RECORDS-VECTORS \(name).json \(passed)/\(cases.count)")
        #expect(failures.isEmpty, Comment(rawValue: "\(name).json \(passed)/\(cases.count) passed\n" + failures.joined(separator: "\n")))
    }

    /// The app path (SQLite store + FTS4 matchinfo ranking) returns the reference payloads for every
    /// `coach_tools` case, after loading the fixture snapshot into an in-memory database.
    @Test func storePathMatchesPayloadVectors() async throws {
        let root = try Self.load("coach_tools_payloads")
        let snapshots = root["fixtures"]["snapshots"].object ?? [:]
        var databases: [String: RecordsDatabase] = [:]
        for (name, snapshot) in snapshots {
            databases[name] = try await RecordsCoachSnapshotLoader.database(snapshot)
        }
        var passed = 0
        var failures: [String] = []
        let cases = root["cases"].array ?? []
        for c in cases {
            let input = c["input"]
            guard let snapshotName = input["snapshot"].string, let db = databases[snapshotName] else {
                failures.append("\(c["name"].string ?? "?"): inline snapshot not loaded")
                continue
            }
            let actual = try await db.coachToolPayload(
                name: input["tool"].string ?? "", args: input["args"].object == nil ? .obj([:]) : input["args"],
                selectedIDs: (input["selected_ids"].array ?? []).compactMap(\.string),
                today: input["today"].string ?? "2026-09-15", dateOrder: input["date_order"].string ?? "dmy"
            )
            if let diff = RecordsVectorTests.firstDifference(actual, c["expected"]) {
                failures.append("\(c["name"].string ?? "?"): \(diff)")
            } else {
                passed += 1
            }
        }
        print("RECORDS-VECTORS coach_tools_payloads.json (store path) \(passed)/\(cases.count)")
        #expect(failures.isEmpty, Comment(rawValue: "store path \(passed)/\(cases.count)\n" + failures.joined(separator: "\n")))
    }

    @Test func ftsRowOfTheStoreEqualsTheReferenceRow() async throws {
        let root = try Self.load("coach_tools_payloads")
        let snapshot = root["fixtures"]["snapshots"]["clinic"]
        #expect(!snapshot.isNull)
        let db = try await RecordsCoachSnapshotLoader.database(snapshot)
        for record in snapshot["records"].array ?? [] {
            let id = try #require(record["id"].string)
            let expected = RR.ftsRow(snapshot, record)
            let actual = try await db.withConnection { connection -> [String] in
                var row: [String] = []
                try connection.query("SELECT records_fts.title, records_fts.people, records_fts.clinical, records_fts.body, records_fts.notes_tags, records_fts.highlights FROM records_fts JOIN records ON records.seq = records_fts.docid WHERE records.id=?", [.text(id)]) { s in
                    row = (0..<6).map { s.text(Int32($0)) ?? "" }
                }
                return row
            }
            #expect(actual.count == 6, "\(id) has an FTS row")
            for column in 0..<min(6, actual.count) {
                #expect(RR.ftsTokens(actual[column]) == RR.ftsTokens(expected[column]), "\(id) column \(column)")
            }
        }
    }
}

/// Loads a reference store snapshot into an in-memory `RecordsDatabase` (raw rows, then one reindex).
enum RecordsCoachSnapshotLoader {
    static func database(_ snapshot: RJ) async throws -> RecordsDatabase {
        let db = try await RecordsDatabase.inMemory(timeZone: TimeZone(identifier: "UTC")!)
        let records = snapshot["records"].array ?? []
        let fields = snapshot["fields"].array ?? []
        let observations = snapshot["observations"].array ?? []
        let highlights = snapshot["highlights"].array ?? []
        let pages = snapshot["pages"].array ?? []
        let links = snapshot["links"].array ?? []
        let aliases = snapshot["user_aliases"].object ?? [:]
        try await db.withConnection { c in
            func text(_ v: RJ) -> SQLValue { v.string.map { .text($0) } ?? .null }
            func textOr(_ v: RJ, _ fallback: String) -> SQLValue { .text(v.string ?? fallback) }
            func real(_ v: RJ) -> SQLValue { v.double.map { .real($0) } ?? .null }
            func int(_ v: RJ, _ fallback: Int64? = nil) -> SQLValue {
                if let d = v.double { return .int(Int64(d)) }
                if let b = v.bool { return .int(b ? 1 : 0) }
                return fallback.map { .int($0) } ?? .null
            }
            try c.inTransaction {
                for r in records {
                    try c.run(
                        "INSERT INTO records (seq, id, title, record_type, category, source, import_method, created_ms, updated_ms, document_date, sort_date, mime_type, file_type, page_count, review_status, favorite, archived, notes, ai_mode_used) VALUES (?, ?, ?, ?, ?, ?, 'file_picker', ?, ?, ?, ?, 'application/pdf', 'pdf', ?, ?, ?, ?, ?, ?)",
                        [int(r["seq"]), text(r["id"]), text(r["title"]), textOr(r["record_type"], "other"), textOr(r["category"], "other"), textOr(r["source"], "import"),
                         int(r["created_ms"], 0), int(r["created_ms"], 0), text(r["sort_date"]), text(r["sort_date"]), int(r["page_count"], 0),
                         textOr(r["review_status"], "none"), int(r["favorite"], 0), int(r["archived"], 0), text(r["notes"]), textOr(r["ai_mode_used"], "none")]
                    )
                    for tag in (r["tags"].array ?? []).compactMap(\.string) {
                        try c.run("INSERT OR IGNORE INTO tags (id, name) VALUES (?, ?)", [.text("tag-" + tag.lowercased()), .text(tag)])
                        try c.run("INSERT INTO record_tags (record_id, tag_id) VALUES (?, (SELECT id FROM tags WHERE name=? COLLATE NOCASE))", [text(r["id"]), .text(tag)])
                    }
                }
                for f in fields {
                    try c.run(
                        "INSERT INTO record_fields (id, record_id, field_key, value_text, value_json, method, confidence, state, source_page, created_ms, updated_ms) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 0, 0)",
                        [text(f["id"]), text(f["record_id"]), text(f["field_key"]), textOr(f["value_text"], ""), f["value_json"].isNull ? .null : .text(f["value_json"].compactJSON),
                         textOr(f["method"], "rules"), real(f["confidence"]), textOr(f["state"], "suggested"), int(f["source_page"])]
                    )
                }
                for o in observations {
                    try c.run(
                        "INSERT INTO observations (\(RecordsDatabase.observationColumns)) VALUES (\(Array(repeating: "?", count: Int(RecordsDatabase.observationColumnCount)).joined(separator: ", ")))",
                        [text(o["id"]), text(o["record_id"]), text(o["field_id"]), text(o["analyte_id"]), text(o["analyte_method"]), textOr(o["raw_name"], ""),
                         real(o["value_num"]), textOr(o["value_text"], ""), text(o["unit"]), real(o["canonical_value"]), text(o["canonical_unit"]),
                         real(o["ref_low"]), real(o["ref_high"]), text(o["ref_text"]), textOr(o["flag"], "unknown"),
                         text(o["observed_date"]), text(o["observed_date_method"]), textOr(o["method"], "rules"),
                         o["confidence"].double.map { .real($0) } ?? .real(0), textOr(o["state"], "suggested"),
                         int(o["source_page"]), text(o["source_bbox"]), text(o["evidence"]), int(o["excluded_from_trends"], 0),
                         int(o["created_ms"], 0), int(o["updated_ms"], 0)]
                    )
                }
                for h in highlights {
                    try c.run(
                        "INSERT INTO record_highlights (id, record_id, section, text, method, confidence, dismissed, position, created_ms) VALUES (?, ?, ?, ?, 'rules', 0.9, ?, ?, 0)",
                        [text(h["id"]), text(h["record_id"]), text(h["section"]), textOr(h["text"], ""), int(h["dismissed"], 0), int(h["position"], 0)]
                    )
                }
                for p in pages {
                    try c.run("INSERT INTO record_pages (record_id, page_index, text, text_source) VALUES (?, ?, ?, 'pdf_text')", [text(p["record_id"]), int(p["page_index"], 0), text(p["text"])])
                }
                for l in links {
                    try c.run("INSERT INTO record_links (a_id, b_id, kind, origin, status, created_ms, updated_ms) VALUES (?, ?, ?, ?, ?, 0, 0)",
                              [text(l["a_id"]), text(l["b_id"]), text(l["kind"]), text(l["origin"]), text(l["status"])])
                }
                for (name, analyte) in aliases {
                    try c.run("INSERT INTO analyte_user_aliases (normalized_name, analyte_id, created_ms) VALUES (?, ?, 0)", [.text(name), text(analyte)])
                }
            }
        }
        for r in records {
            if let id = r["id"].string { try await db.reindex(recordID: id) }
        }
        return db
    }
}

// MARK: - Provider loop with a stubbed transport

private final class StubChatTransport: ChatHTTPTransport, @unchecked Sendable {
    private let lock = NSLock()
    private var responses: [String]
    private var bodies: [[String: Any]] = []

    init(responses: [String]) { self.responses = responses }

    func data(for request: URLRequest) async throws -> (Data, URLResponse) {
        let body = request.httpBody.flatMap { try? JSONSerialization.jsonObject(with: $0) as? [String: Any] } ?? [:]
        let next: String = lock.withLock {
            bodies.append(body)
            return responses.isEmpty ? "{}" : responses.removeFirst()
        }
        let response = HTTPURLResponse(url: request.url!, statusCode: 200, httpVersion: nil, headerFields: nil)!
        return (Data(next.utf8), response)
    }

    var recordedBodies: [[String: Any]] { lock.withLock { bodies } }
}

@MainActor
struct RecordsCoachChatServiceTests {
    @Test func openAICompatibleLoopRunsRestrictedRecordsGetAndCollectsRefs() async throws {
        let db = try await RecordsCoachFixtures.database()
        let session = CoachRecordsSession()
        let context = try await RecordsCoachFixtures.context(db, selected: ["cbc-sep"], session: session)
        let tools = CoachTools(weights: [], bodyFats: [], foods: [], records: context)
        let first = #"{"choices":[{"finish_reason":"tool_calls","message":{"role":"assistant","content":null,"tool_calls":[{"id":"c1","type":"function","function":{"name":"records_get","arguments":"{\"record_id\":\"cbc-jul\"}"}},{"id":"c2","type":"function","function":{"name":"records_get","arguments":"{\"record_id\":\"cbc-sep\"}"}}]}}]}"#
        let second = #"{"choices":[{"finish_reason":"stop","message":{"role":"assistant","content":"Your hemoglobin is 9.7 g/dL (CBC, 2026-09-12)."}}]}"#
        let transport = StubChatTransport(responses: [first, second])
        let reply = try await ChatHTTP.$transport.withValue(transport) {
            try await ChatService.callOpenAICompatible(
                baseURL: "https://stub.invalid/v1", model: "stub-model", apiKey: "k", systemPrompt: "system",
                history: [], newUserMessage: "Explain this report", images: [], provider: .openai, tools: tools
            )
        }
        #expect(reply == "Your hemoglobin is 9.7 g/dL (CBC, 2026-09-12).")
        let bodies = transport.recordedBodies
        #expect(bodies.count == 2)
        let advertised = (bodies.first?["tools"] as? [[String: Any]] ?? []).compactMap { ($0["function"] as? [String: Any])?["name"] as? String }
        #expect(Array(advertised.suffix(3)) == ["records_search", "records_get", "records_observation_series"])
        let recordsGet = try #require((bodies.first?["tools"] as? [[String: Any]])?.first { ($0["function"] as? [String: Any])?["name"] as? String == "records_get" })
        let function = try #require(recordsGet["function"] as? [String: Any])
        #expect(function["description"] as? String == RecordsCoachContract.shared.tool("records_get")?.description)
        #expect(RJ.same(RJ.from(function["parameters"]), try #require(RecordsCoachContract.shared.tool("records_get")).schema))
        let toolMessages = (bodies.last?["messages"] as? [[String: Any]] ?? []).filter { $0["role"] as? String == "tool" }
        #expect(toolMessages.count == 2)
        let blocked = try #require(RJ.parse(toolMessages.first?["content"] as? String ?? ""))
        #expect(blocked["error"].string == "record 'cbc-jul' is not in the records the user selected for this conversation")
        let allowed = try #require(RJ.parse(toolMessages.last?["content"] as? String ?? ""))
        #expect(allowed["record_id"].string == "cbc-sep")
        let allowedText = toolMessages.last?["content"] as? String ?? ""
        #expect(!allowedText.contains("Asha Rao"), Comment(rawValue: allowedText))
        let refs = await session.refs
        #expect(refs == [ChatRecordRef(recordID: "cbc-sep", title: "CBC 2026-09-12", date: "2026-09-12")])
    }
}
