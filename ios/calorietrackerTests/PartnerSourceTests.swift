import Foundation
import Testing
@testable import calorietracker

/// Outbound source renderers over small fake stores: only allow-listed types are rendered, every rendered record is
/// a valid envelope, and forbidden keys (files, photos, notes, evidence, routes…) never appear.
struct PartnerSourceTests {
    typealias S = PartnerTestSupport
    static let tz = TimeZone(identifier: "UTC")!

    static func day(_ back: Int) -> String { PartnerDay.adding(-back, to: PartnerDay.string(S.now, timeZone: tz), timeZone: tz) }

    static func scanAll(_ source: any PartnerRecordSource, scope: PartnerSourceScope = .full, pageSize: Int = 3) async throws -> [PartnerSourceRecord] {
        var out: [PartnerSourceRecord] = []
        try await source.scan(scope: scope, pageSize: pageSize) { page in
            #expect(page.count <= pageSize)
            out += page
        }
        return out
    }

    static func expectClean(_ records: [PartnerSourceRecord], sourceLine: SourceLocation = #_sourceLocation) {
        for r in records {
            #expect(!PartnerRef.hasForbiddenKey(r.data), "forbidden key in \(r.type) \(r.recordID)", sourceLocation: sourceLine)
            let env = r.envelope(rev: 1, nowMs: S.nowMs)
            let v = PartnerRef.envelopeValidate(env, nowMs: Int(S.nowMs))
            #expect(v["ok"].bool == true, "\(r.type) \(r.recordID): \(v)", sourceLocation: sourceLine)
            #expect(PartnerRef.categoryOf(.str(r.type), r.data) == r.category, sourceLocation: sourceLine)
        }
    }

    // MARK: Health mirror

    @Test func healthSourcesRenderOnlyAllowListedTypes() async throws {
        let dir = try HealthTestFixtures.temporaryDirectory()
        defer { try? FileManager.default.removeItem(at: dir) }
        let url = dir.appendingPathComponent("Health/health.sqlite")
        let health = try await HealthDatabase.open(url: url)
        let d1 = Self.day(1), d2 = Self.day(2), d20 = Self.day(20)
        try await health.withConnection { c in
            for (type, day, sum, avg) in [("steps", d1, 8421.0, nil), ("steps", d2, 9000.0, nil), ("resting_heart_rate", d1, nil, 58.0),
                                          ("menstrual_flow", d1, 1.0, nil), ("environmental_audio_exposure", d1, nil, 70.0)] as [(String, String, Double?, Double?)] {
                try c.run("INSERT INTO health_daily_rollups (type_id, day, tz, sum, avg, count) VALUES (?,?,?,?,?,3)",
                          [.text(type), .text(day), .text("UTC"), .optionalReal(sum), .optionalReal(avg)])
            }
            try c.run("INSERT INTO health_hourly_rollups (type_id, day, hour, sum, count) VALUES ('steps', ?, 9, 1200, 4)", [.text(d1)])
            try c.run("INSERT INTO health_hourly_rollups (type_id, day, hour, sum, count) VALUES ('steps', ?, 9, 1200, 4)", [.text(d20)])
            try c.run("INSERT INTO health_hourly_rollups (type_id, day, hour, avg, count) VALUES ('respiratory_rate', ?, 3, 14, 4)", [.text(d1)])
            try c.run("INSERT INTO health_sources (id, name) VALUES ('com.apple.health.watch', 'Apple Watch')")
            try c.run("INSERT INTO derived_daily_values (metric_id, day, value, quality, algo_version, computed_ms) VALUES ('resting_hr_derived', ?, 57, 0.9, 1, 1)", [.text(d1)])
            try c.run("INSERT INTO derived_daily_values (metric_id, day, value, algo_version, computed_ms) VALUES ('not_a_metric', ?, 1, 1, 1)", [.text(d1)])
            for (metric, version, value) in [("recovery_indicator", 1, 70.0), ("recovery_indicator", 2, 81.6), ("anomaly", 1, 1.0)] {
                try c.run("""
                    INSERT INTO analytics_results (metric_id, period_start, period_end, algorithm_id, algorithm_version, config_version, status, classification,
                    value, unit, result_json, provenance_json, input_hash, computed_ms) VALUES (?,?,?,'a',?,1,'ok','good',?,'score','{"x":1}','{"inputs":1}','h',1)
                    """, [.text(metric), .text(d1), .text(d1), .int(Int64(version)), .real(value)])
            }
        }
        let nowMs = S.nowMs
        func sample(_ id: String, _ type: String, startMs: Int64, endMs: Int64? = nil, day: String, value: Double? = nil, category: Int? = nil,
                    unit: String = "count/min") -> HealthSampleRow {
            HealthSampleRow(id: id, typeID: type, startMs: startMs, endMs: endMs ?? startMs, startOffsetS: 0, endOffsetS: 0, localDay: day, value: value,
                            value2: nil, value3: nil, valueText: "secret text", unit: unit, categoryValue: category, title: "title",
                            extraJSON: "{\"route\":[1,2]}", count: 1, sourceID: "com.apple.health.watch", device: "Ritik's Watch", deviceType: nil,
                            recordingMethod: nil, clientRecordID: nil, origin: 0, deleted: 0, updatedMs: startMs)
        }
        // Night ending on d1 (UTC): in bed 22:00 → 06:00, stages inside.
        let wake = (PartnerDay.date(d1, timeZone: Self.tz)!.timeIntervalSince1970 * 1000)
        let w = Int64(wake)
        let h: Int64 = 3_600_000
        try await health.upsertSamples([
            sample("hr1", "heart_rate", startMs: nowMs - 86_400_000, day: d1, value: 61),
            sample("hr-night", "heart_rate", startMs: w - 2 * h, day: d1, value: 52),
            sample("hr-old", "heart_rate", startMs: nowMs - 20 * 86_400_000, day: d20, value: 70),
            sample("flow1", "menstrual_flow", startMs: nowMs - 86_400_000, day: d1, category: 3, unit: "count"),
            sample("bed", "sleep", startMs: w - 2 * h, endMs: w + 6 * h, day: d1, category: HealthSleepStage.inBed.rawValue, unit: "min"),
            sample("core", "sleep", startMs: w - 1 * h, endMs: w + 3 * h, day: d1, category: HealthSleepStage.light.rawValue, unit: "min"),
            sample("deep", "sleep", startMs: w + 3 * h, endMs: w + 4 * h, day: d1, category: HealthSleepStage.deep.rawValue, unit: "min"),
            sample("rem", "sleep", startMs: w + 4 * h, endMs: w + 5 * h + h / 2, day: d1, category: HealthSleepStage.rem.rawValue, unit: "min"),
        ])

        let reader = PartnerSQLiteReader(url: url)
        let now: @Sendable () -> Date = { S.now }
        let rollups = try await Self.scanAll(PartnerRollupSource(reader: reader))
        #expect(Set(rollups.map(\.recordID)) == ["steps:\(d1)", "steps:\(d2)", "resting_heart_rate:\(d1)"])
        #expect(rollups.first { $0.recordID == "steps:\(d1)" }?.data["sum"].pyInt == 8421)
        #expect(rollups.first { $0.recordID == "steps:\(d1)" }?.data["unit"].string == HealthMetricRegistry.byID["steps"]?.unit)
        let window = try await Self.scanAll(PartnerRollupSource(reader: reader), scope: .dayFrom(d1))
        #expect(window.allSatisfy { $0.day == d1 })
        #expect(try await PartnerRollupSource(reader: reader).record(id: "menstrual_flow:\(d1)") == nil)
        #expect(try await PartnerRollupSource(reader: reader).record(id: "steps:\(d2)")?.data["sum"].pyInt == 9000)

        let hourly = try await Self.scanAll(PartnerHourlySource(reader: reader, now: now, timeZone: Self.tz))
        #expect(hourly.map(\.recordID) == ["steps:\(d1):9"])
        #expect(hourly.first?.data["hour_start_ms"].pyInt == Int(PartnerDay.hourStartMs(day: d1, hour: 9, timeZone: Self.tz) ?? 0))

        let samples = try await Self.scanAll(PartnerSampleSource(reader: reader, now: now, timeZone: Self.tz), scope: .dayFrom(Self.day(7)))
        #expect(Set(samples.map(\.recordID)) == ["hr1", "hr-night", "bed", "core", "deep", "rem"])
        #expect(samples.first { $0.recordID == "hr1" }?.data["source_name"].string == "Apple Watch")
        #expect(samples.allSatisfy { $0.data["title"].isNull && $0.data["extra_json"].isNull })

        let derived = try await Self.scanAll(PartnerDerivedSource(reader: reader))
        #expect(derived.map(\.recordID) == ["resting_hr_derived:\(d1)"])
        let analytics = try await Self.scanAll(PartnerAnalyticsSource(reader: reader))
        #expect(analytics.map(\.recordID) == ["recovery_indicator:\(d1)"])
        #expect(analytics.first?.data["value"].pyNumber == 81.6)

        let sleep = try await Self.scanAll(PartnerSleepNightSource(reader: reader, timeZone: Self.tz))
        let night = try #require(sleep.first)
        #expect(sleep.count == 1)
        #expect(night.recordID == d1)
        #expect(night.data["asleep_min"].pyInt == 390)
        #expect(night.data["in_bed_min"].pyInt == 480)
        #expect(night.data["deep_min"].pyInt == 60)
        #expect(night.data["rem_min"].pyInt == 90)
        #expect(night.data["avg_hr"].pyNumber == 52)
        #expect(night.data["score"].isNull)   // iOS has no sleep score: never fabricated
        #expect(try await PartnerSleepNightSource(reader: reader, timeZone: Self.tz).record(id: d1) == night)

        Self.expectClean(rollups + hourly + samples + derived + analytics + sleep)
        await reader.close()
        await health.close()
    }

    @Test func missingDatabaseIsEmptyAndNothingIsCreated() async throws {
        let dir = try HealthTestFixtures.temporaryDirectory()
        defer { try? FileManager.default.removeItem(at: dir) }
        let url = dir.appendingPathComponent("none.sqlite")
        let reader = PartnerSQLiteReader(url: url)
        #expect(try await Self.scanAll(PartnerRollupSource(reader: reader)).isEmpty)
        #expect(try await Self.scanAll(PartnerMedicationSource(reader: reader)).isEmpty)
        #expect(!FileManager.default.fileExists(atPath: url.path))
    }

    // MARK: Medicines

    @Test func medicationSourcesDropPhotoAndRecordLinks() async throws {
        let dir = try HealthTestFixtures.temporaryDirectory()
        defer { try? FileManager.default.removeItem(at: dir) }
        let url = dir.appendingPathComponent("Medications/medications.sqlite")
        let meds = try await MedicationsDatabase.open(url: url)
        let scheduled = S.nowMs - 3_600_000
        try await meds.withConnection { c in
            try c.run("""
                INSERT INTO medications (id, name, form, dose_quantity, dose_unit, start_date, status, is_prn, photo_path, related_record_id, created_ms, updated_ms)
                VALUES ('m1', 'Vitamin D3', 'capsule', 1, 'capsule', '2026-09-01', 'active', 0, '/photos/m1.jpg', 'rec-9', 1, 2)
                """)
            try c.run("""
                INSERT INTO medication_schedules (id, medication_id, frequency_kind, times_json, days_json, active_from_ms, created_ms, updated_ms)
                VALUES ('s1', 'm1', 'daily', '["08:00","20:00"]', '[]', 1, 1, 2)
                """)
            try c.run("""
                INSERT INTO dose_logs (id, medication_id, schedule_id, scheduled_at_ms, status, taken_at_ms, dose_quantity, dose_unit, note, created_ms, updated_ms)
                VALUES ('d1', 'm1', 's1', ?, 'taken', ?, 1, 'capsule', 'with food', 1, 2)
                """, [.int(scheduled), .int(scheduled + 60_000)])
        }
        let reader = PartnerSQLiteReader(url: url)
        let m = try await Self.scanAll(PartnerMedicationSource(reader: reader))
        let s = try await Self.scanAll(PartnerScheduleSource(reader: reader))
        let d = try await Self.scanAll(PartnerDoseLogSource(reader: reader, timeZone: Self.tz))
        #expect(m.count == 1 && s.count == 1 && d.count == 1)
        #expect(m[0].data["photo_path"].isNull && m[0].data["related_record_id"].isNull)
        #expect(m[0].data["is_prn"].bool == false)
        #expect(s[0].data["times"].array?.compactMap(\.string) == ["08:00", "20:00"])
        #expect(d[0].day == PartnerDay.string(ms: scheduled, timeZone: Self.tz))
        #expect(try await PartnerDoseLogSource(reader: reader, timeZone: Self.tz).record(id: "d1") == d[0])
        Self.expectClean(m + s + d)
        await reader.close()
        await meds.close()
    }

    // MARK: Records

    @Test func reportOverviewsNeverCarryFilesTextEvidenceOrNotes() async throws {
        let dir = try HealthTestFixtures.temporaryDirectory()
        defer { try? FileManager.default.removeItem(at: dir) }
        let url = dir.appendingPathComponent("Records/records.sqlite")
        let records = try await RecordsDatabase.open(url: url)
        try await records.withConnection { c in
            for (id, archived) in [("rec-1", 0), ("rec-2", 1)] {
                try c.run("""
                    INSERT INTO records (id, title, record_type, category, source, import_method, original_filename, created_ms, updated_ms, document_date,
                    sort_date, mime_type, file_type, file_path, thumbnail_path, checksum_sha256, archived, notes)
                    VALUES (?, 'Complete Blood Count', 'lab_report', 'blood', 'import', 'filePicker', 'cbc-ritik.pdf', 1, 2, '2026-10-01', '2026-10-01',
                    'application/pdf', 'pdf', 'files/rec/original.pdf', 'thumb.jpg', 'abc', ?, 'private note')
                    """, [.text(id), .int(Int64(archived))])
            }
            try c.run("INSERT INTO record_pages (record_id, page_index, text, text_source, blocks_json) VALUES ('rec-1', 0, 'Patient: Ritik', 'ocr', '[]')")
            try c.run("""
                INSERT INTO record_fields (id, record_id, field_key, value_text, method, confidence, state, source_page, source_bbox, evidence, created_ms, updated_ms)
                VALUES ('f1', 'rec-1', 'doctor_name', 'Dr. Sharma', 'ai', 0.9, 'confirmed', 0, '[1,2,3,4]', 'Dr. Sharma MD', 1, 1)
                """)
            try c.run("""
                INSERT INTO record_fields (id, record_id, field_key, value_text, method, confidence, state, evidence, created_ms, updated_ms)
                VALUES ('f2', 'rec-1', 'patient_name', 'Ritik', 'ai', 0.9, 'confirmed', 'Name: Ritik', 1, 1)
                """)
            try c.run("""
                INSERT INTO record_highlights (id, record_id, section, text, method, confidence, created_ms) VALUES ('h1', 'rec-1', 'summary', 'Mild anaemia.', 'ai', 0.8, 1)
                """)
            try c.run("""
                INSERT INTO observations (id, record_id, raw_name, analyte_id, value_text, value_num, unit, ref_low, ref_high, flag, method, state,
                source_page, source_bbox, evidence, created_ms, updated_ms)
                VALUES ('o1', 'rec-1', 'Hemoglobin', 'hemoglobin', '11.2', 11.2, 'g/dL', 12, 15, 'low', 'ai', 'suggested', 0, '[0,0,1,1]', 'Hb 11.2', 1, 1)
                """)
        }
        let reader = PartnerSQLiteReader(url: url)
        let rendered = try await Self.scanAll(PartnerReportOverviewSource(reader: reader))
        #expect(rendered.map(\.recordID) == ["rec-1"])   // archived rec-2 is not shared
        let data = try #require(rendered.first?.data)
        #expect(data["doctor"].string == "Dr. Sharma")
        #expect(data["summary"].string == "Mild anaemia.")
        #expect(data["abnormal"].array?.count == 1)
        let text = PartnerJSON.canonical(data)
        for secret in ["Ritik", "private note", "original.pdf", "thumb.jpg", "Hb 11.2", "Dr. Sharma MD", "cbc-ritik"] {
            #expect(!text.contains(secret), "\(secret) leaked")
        }
        #expect(try await PartnerReportOverviewSource(reader: reader).record(id: "rec-2") == nil)
        Self.expectClean(rendered)
        await reader.close()
        await records.close()
    }

    /// Frame fit (§7.6): an oversized overview drops `results` from the end until the envelope fits one Noise frame
    /// inside a CHANGES page; `abnormal`, `summary` and `highlights` are never trimmed and a small overview is untouched.
    @Test func oversizedReportOverviewDropsResultsFromTheEndUntilItFitsOneFrame() throws {
        let record: RJ = .obj(["id": .str("rec-big"), "title": .str("Full panel"), "record_type": .str("lab_report"), "category": .str("blood"),
                               "document_date": .str("2026-10-01"), "sort_date": .str("2026-10-01"), "archived": .int(0)])
        let highlights: [RJ] = [
            .obj(["id": .str("h1"), "section": .str("summary"), "text": .str("Several results out of range."), "dismissed": .int(0), "position": .int(0)]),
            .obj(["id": .str("h2"), "section": .str("important"), "text": .str("Vitamin D low."), "dismissed": .int(0), "position": .int(1)]),
        ]
        let padding = String(repeating: "x", count: 220)
        let observations: [RJ] = (0..<400).map { i in
            .obj(["id": .str(String(format: "o%03d", i)), "raw_name": .str(String(format: "Analyte %03d %@", i, padding)), "analyte_id": .null,
                  "value_text": .str("\(i)"), "value_num": .int(i), "unit": .str("mg/dL"), "ref_low": .int(1), "ref_high": .int(5), "ref_text": .null,
                  "flag": .str(i % 10 == 0 ? "high" : "normal"), "observed_date": .str("2026-10-01"), "state": .str("confirmed")])
        }
        let full = try #require(PartnerRef.mapReportOverview(record: record, fields: [], highlights: highlights, observations: observations))
        #expect(PartnerJSON.canonicalData(full).count > PartnerFraming.maxJSONMessage)

        let fitted = try #require(PartnerFrameFit.fitReportOverview(full))
        let fullResults = full["data"]["results"].array ?? []
        let kept = fitted["data"]["results"].array ?? []
        #expect(!kept.isEmpty && kept.count < fullResults.count)
        #expect(kept.enumerated().allSatisfy { pyEq($0.element, fullResults[$0.offset]) }, "results must be a prefix (dropped from the end)")
        #expect((fitted["data"]["abnormal"].array ?? []).count == 40)
        #expect(pyEq(fitted["data"]["abnormal"], full["data"]["abnormal"]))
        #expect(pyEq(fitted["data"]["summary"], full["data"]["summary"]))
        #expect(pyEq(fitted["data"]["highlights"], full["data"]["highlights"]))
        #expect(PartnerJSON.canonicalData(fitted).count <= PartnerFrameFit.maxEnvelopeBytes)
        // One more result would not fit: the largest prefix is kept.
        var bigger = fitted["data"].object ?? [:]
        bigger["results"] = .arr(Array(fullResults.prefix(kept.count + 1)))
        var biggerEnv = fitted.object ?? [:]
        biggerEnv["data"] = .obj(bigger)
        #expect(PartnerJSON.canonicalData(.obj(biggerEnv)).count > PartnerFrameFit.maxEnvelopeBytes)

        // On the wire: the rendered envelope plus the CHANGES page skeleton stays within one frame and validates.
        let source = try #require(PartnerSourceRecord(mapped: fitted))
        let env = source.envelope(rev: 999_999, nowMs: S.nowMs)
        let page: RJ = .obj(["t": .str("CHANGES"), "from_rev": .int(999_998), "to_rev": .int(999_999), "has_more": .bool(false), "records": .arr([env])])
        #expect(PartnerJSON.canonicalData(page).count <= PartnerFraming.maxJSONMessage)
        #expect(PartnerRef.envelopeValidate(env, nowMs: Int(S.nowMs))["ok"].bool == true)

        // A small overview passes through unchanged.
        let small = try #require(PartnerRef.mapReportOverview(record: record, fields: [], highlights: highlights, observations: Array(observations.prefix(3))))
        #expect(pyEq(try #require(PartnerFrameFit.fitReportOverview(small)), small))
        #expect(PartnerFrameFit.fitReportOverview(nil) == nil)
    }

    // MARK: App blobs

    @Test func blobMappersProduceValidEnvelopes() async throws {
        let id = UUID()
        let food = PartnerAppMappers.food(id: id, name: "  Oats  ", loggedAt: S.now, calories: 320, meal: "breakfast", serving: "1 bowl",
                                          protein: 12.456, carbs: 54, fat: nil, fiber: nil, sugar: nil, sodiumMg: 80, emoji: "🥣", timeZone: Self.tz)
        #expect(food.recordID == id.uuidString.lowercased())
        #expect(food.data["name"].string == "Oats")
        #expect(food.data["protein_g"].pyNumber == 12.46)
        #expect(food.data["fat_g"].isNull)
        let water = PartnerAppMappers.waterDays([(S.now, 250), (S.now.addingTimeInterval(60), 500), (S.now.addingTimeInterval(-86_400), 300)],
                                                goalMl: 2000, timeZone: Self.tz)
        #expect(water.map { $0.data["total_ml"].pyInt } == [300, 750])
        let weight = try #require(PartnerAppMappers.weight(id: id, date: S.now, kg: 70.25, timeZone: Self.tz))
        #expect(PartnerAppMappers.weight(id: id, date: S.now, kg: .nan) == nil)
        let workout = PartnerAppMappers.workout(id: "w1", day: Self.day(0), activity: PartnerAppMappers.activitySlug("Strength Training"),
                                                title: "Squat, Bench", start: S.now, end: S.now.addingTimeInterval(3600), durationS: 3600, kcal: 300,
                                                distanceM: nil, paceSPerKm: nil, avgHR: 120, maxHR: 160, load: 55, sets: 12,
                                                volumeKg: PartnerAppMappers.volumeKg([("100", "kg", "5"), ("135", "lb", "5"), ("", "kg", "8")]), source: "ayuvo")
        #expect(workout.data["activity"].string == "strength_training")
        #expect(abs((workout.data["volume_kg"].pyNumber ?? 0) - 806.18) < 0.02)
        #expect(PartnerAppMappers.outdoorActivity("run") == "running")
        Self.expectClean([food, weight, workout] + water)
    }

    @Test @MainActor func blobSnapshotsReadUserDefaultsWithoutTouchingThem() async throws {
        let suite = "partner-tests-\(UUID().uuidString)"
        let defaults = try #require(UserDefaults(suiteName: suite))
        defer { defaults.removePersistentDomain(forName: suite) }
        let entries = [WaterEntry(date: S.now, milliliters: 400), WaterEntry(date: S.now, milliliters: 100)]
        defaults.set(try JSONEncoder().encode(entries), forKey: WaterSettings.entriesKey)
        defaults.set(2500, forKey: WaterSettings.dailyGoalKey)
        defaults.set(try JSONEncoder().encode([WeightEntry(date: S.now, weightKg: 72)]), forKey: WeightStore.storageKey)
        let before = defaults.dictionaryRepresentation().count
        let water = try PartnerAppSnapshots.water(defaults: defaults)
        #expect(water.count == 1)
        #expect(water.first?.data["total_ml"].pyInt == 500)
        #expect(water.first?.data["goal_ml"].pyInt == 2500)
        #expect(try PartnerAppSnapshots.weight(defaults: defaults).first?.data["kg"].pyNumber == 72)
        #expect(try PartnerAppSnapshots.food(defaults: defaults).isEmpty)
        #expect(try PartnerAppSnapshots.workouts(defaults: defaults).isEmpty)
        #expect(defaults.dictionaryRepresentation().count == before)
        defaults.set(Data("not json".utf8), forKey: FoodStore.storageKey)
        #expect(throws: (any Error).self) { _ = try PartnerAppSnapshots.food(defaults: defaults) }
    }
}
