import Foundation

// Blob-backed sources (docs/partner-sync.md §7.2–§7.4): food diary, water, the app's own weights and workouts. These
// stores are JSON blobs in UserDefaults without update timestamps, which is why the outbound ledger exists. The
// blobs are decoded READ-ONLY on the main actor (their model types are main-actor isolated) into small records; no
// store is touched, and photos, image paths, notes and GPS routes are never read.

/// A source over an in-memory snapshot produced by `load` (decoded once per refresh / session and cached).
nonisolated final class PartnerSnapshotSource: PartnerRecordSource, @unchecked Sendable {
    let type: String
    private let load: @Sendable () async throws -> [PartnerSourceRecord]
    private let lock = NSLock()
    private var cache: [String: PartnerSourceRecord]?
    private var ordered: [PartnerSourceRecord]?

    init(type: String, load: @escaping @Sendable () async throws -> [PartnerSourceRecord]) {
        self.type = type
        self.load = load
    }

    private func snapshot() async throws -> [PartnerSourceRecord] {
        if let ordered = lock.withLock({ self.ordered }) { return ordered }
        let records: [PartnerSourceRecord]
        do {
            records = try await load()
        } catch {
            throw PartnerSourceUnavailable(type: type, reason: String(describing: error))
        }
        let sorted = records.filter { $0.type == type }.sorted { utf8Less($0.recordID, $1.recordID) }
        var map: [String: PartnerSourceRecord] = [:]
        for r in sorted { map[r.recordID] = r }
        lock.withLock {
            self.ordered = sorted
            self.cache = map
        }
        return sorted
    }

    /// Forget the cached snapshot (the next scan re-reads the store).
    func invalidate() {
        lock.withLock {
            ordered = nil
            cache = nil
        }
    }

    func scan(scope: PartnerSourceScope, pageSize: Int, onPage: ([PartnerSourceRecord]) async throws -> Void) async throws {
        let all = try await snapshot()
        var page: [PartnerSourceRecord] = []
        for r in all where scope.contains(day: r.day) {
            page.append(r)
            if page.count >= pageSize {
                try await onPage(page)
                page.removeAll(keepingCapacity: true)
            }
        }
        if !page.isEmpty { try await onPage(page) }
    }

    func record(id: String) async throws -> PartnerSourceRecord? {
        _ = try await snapshot()
        return lock.withLock { cache?[id] }
    }
}

/// Pure mappings from plain values to shareable records (unit-tested with fake data).
nonisolated enum PartnerAppMappers {
    static func ms(_ date: Date) -> Int { Int((date.timeIntervalSince1970 * 1000).rounded()) }

    private static func num(_ v: Double?) -> RJ {
        guard let v, v.isFinite else { return .null }
        return RJ.number((v * 100).rounded() / 100)
    }

    static func trimmed(_ s: String?) -> String? {
        guard let t = s?.trimmingCharacters(in: .whitespacesAndNewlines), !t.isEmpty else { return nil }
        return String(t.prefix(PartnerCatalog.shared.stringMax))
    }

    static func food(id: UUID, name: String, loggedAt: Date, calories: Int, meal: String?, serving: String?, protein: Double?,
                     carbs: Double?, fat: Double?, fiber: Double?, sugar: Double?, sodiumMg: Double?, emoji: String?,
                     timeZone: TimeZone = .current) -> PartnerSourceRecord {
        let data = PartnerRef.dropNone([
            "name": .str(trimmed(name) ?? "Food"), "logged_ms": .int(ms(loggedAt)), "calories": .int(calories),
            "meal": RJ.string(meal), "serving": RJ.string(trimmed(serving)), "protein_g": num(protein), "carbs_g": num(carbs),
            "fat_g": num(fat), "fiber_g": num(fiber), "sugar_g": num(sugar), "sodium_mg": num(sodiumMg), "emoji": RJ.string(trimmed(emoji)),
        ])
        return PartnerSourceRecord(type: "food_entry", recordID: id.uuidString.lowercased(), category: "nutrition",
                                   day: PartnerDay.string(loggedAt, timeZone: timeZone), data: data)
    }

    /// Daily totals from individual water entries (`(date, ml)`), grouped by local day.
    static func waterDays(_ entries: [(date: Date, ml: Int)], goalMl: Int?, timeZone: TimeZone = .current) -> [PartnerSourceRecord] {
        var totals: [String: Int] = [:]
        for e in entries { totals[PartnerDay.string(e.date, timeZone: timeZone), default: 0] += e.ml }
        return totals.keys.sorted().map { day in
            let data = PartnerRef.dropNone(["day": .str(day), "total_ml": .int(totals[day] ?? 0), "goal_ml": goalMl.map { RJ.int($0) }])
            return PartnerSourceRecord(type: "water_day", recordID: day, category: "nutrition", day: day, data: data)
        }
    }

    static func weight(id: UUID, date: Date, kg: Double, timeZone: TimeZone = .current) -> PartnerSourceRecord? {
        guard kg.isFinite, kg > 0 else { return nil }
        let data: RJ = .obj(["measured_ms": .int(ms(date)), "kg": num(kg)])
        return PartnerSourceRecord(type: "weight", recordID: id.uuidString.lowercased(), category: "vitals",
                                   day: PartnerDay.string(date, timeZone: timeZone), data: data)
    }

    /// `snake_case` activity slug from an English title ("Strength Training" → "strength_training").
    static func activitySlug(_ title: String) -> String {
        var out = ""
        var pendingUnderscore = false
        for scalar in title.lowercased().unicodeScalars {
            if CharacterSet.alphanumerics.contains(scalar), scalar.isASCII {
                if pendingUnderscore, !out.isEmpty { out += "_" }
                pendingUnderscore = false
                out.unicodeScalars.append(scalar)
            } else {
                pendingUnderscore = true
            }
        }
        return out.isEmpty ? "workout" : out
    }

    static func outdoorActivity(_ sport: String) -> String {
        switch sport {
        case "walk": "walking"
        case "run": "running"
        case "cycle": "cycling"
        case "hike": "hiking"
        default: activitySlug(sport)
        }
    }

    // swiftlint:disable:next function_parameter_count
    static func workout(id: String, day: String, activity: String, title: String?, start: Date, end: Date, durationS: Int,
                        kcal: Int?, distanceM: Double?, paceSPerKm: Double?, avgHR: Double?, maxHR: Double?, load: Double?,
                        sets: Int?, volumeKg: Double?, source: String?) -> PartnerSourceRecord {
        let data = PartnerRef.dropNone([
            "activity": .str(activity), "title": RJ.string(trimmed(title)), "start_ms": .int(ms(start)), "end_ms": .int(ms(end)),
            "duration_s": .int(max(0, durationS)), "kcal": kcal.map { RJ.int($0) }, "distance_m": num(distanceM),
            "pace_s_per_km": num(paceSPerKm), "avg_hr": num(avgHR), "max_hr": num(maxHR), "load": num(load),
            "sets": sets.map { RJ.int($0) }, "volume_kg": num(volumeKg), "source": RJ.string(trimmed(source)),
        ])
        return PartnerSourceRecord(type: "workout", recordID: id, category: "workouts", day: day, data: data)
    }

    /// Lifted weight × reps, in kg ("lb"/"lbs" converted). Unparseable sets are skipped.
    static func volumeKg(_ sets: [(weight: String, unit: String, reps: String)]) -> Double? {
        var total = 0.0
        var any = false
        for s in sets {
            guard let w = Double(s.weight.replacingOccurrences(of: ",", with: ".")), let r = Double(s.reps), w > 0, r > 0 else { continue }
            let kg = s.unit.lowercased().hasPrefix("lb") ? w * 0.45359237 : w
            total += kg * r
            any = true
        }
        return any ? total : nil
    }
}

/// Decodes the app's blobs on the main actor and maps them (read-only).
@MainActor
enum PartnerAppSnapshots {
    struct DecodeFailed: Error {}

    private static func decode<T: Decodable>(_ type: T.Type, key: String, defaults: UserDefaults) throws -> T? {
        guard let data = defaults.data(forKey: key) else { return nil }
        do { return try JSONDecoder().decode(T.self, from: data) } catch { throw DecodeFailed() }
    }

    static func food(defaults: UserDefaults = .standard) throws -> [PartnerSourceRecord] {
        let entries = try decode([FoodEntry].self, key: FoodStore.storageKey, defaults: defaults) ?? []
        return entries.map { e in
            var serving: String?
            if let q = e.selectedServingQuantity, let unit = e.selectedServingUnit, q > 0 {
                serving = "\(format(q)) \(unit)"
            } else if let g = e.servingSizeGrams, g > 0 {
                serving = "\(format(g)) g"
            }
            return PartnerAppMappers.food(id: e.id, name: e.name, loggedAt: e.timestamp, calories: e.calories, meal: e.mealType.rawValue,
                                          serving: serving, protein: e.protein, carbs: e.carbs, fat: e.fat, fiber: e.fiber,
                                          sugar: e.sugar, sodiumMg: e.sodium, emoji: e.emoji)
        }
    }

    static func water(defaults: UserDefaults = .standard) throws -> [PartnerSourceRecord] {
        let entries = try decode([WaterEntry].self, key: WaterSettings.entriesKey, defaults: defaults) ?? []
        let goal = defaults.object(forKey: WaterSettings.dailyGoalKey) as? Int
        return PartnerAppMappers.waterDays(entries.map { ($0.date, $0.milliliters) }, goalMl: goal.flatMap { $0 > 0 ? $0 : nil })
    }

    static func weight(defaults: UserDefaults = .standard) throws -> [PartnerSourceRecord] {
        let entries = try decode([WeightEntry].self, key: WeightStore.storageKey, defaults: defaults) ?? []
        return entries.compactMap { PartnerAppMappers.weight(id: $0.id, date: $0.date, kg: $0.weightKg) }
    }

    /// Ayuvo's own diary sessions plus Apple Health workouts from other apps. `ImportedHealthWorkout.from` already
    /// skips HealthKit workouts tagged `ayuvo_workout_session_id` (the ones Ayuvo wrote itself), so nothing is counted
    /// twice; `health_samples` workout rows are not used for the same reason.
    static func workouts(defaults: UserDefaults = .standard) throws -> [PartnerSourceRecord] {
        var out: [PartnerSourceRecord] = []
        if let state = try decode(StrengthWorkoutStore.PersistedState.self, key: StrengthWorkoutStore.defaultStorageKey, defaults: defaults) {
            for s in state.completedSessions {
                let interval = s.activeInterval
                let start = interval?.start ?? s.startedAt
                let end = interval?.end ?? s.completedAt
                let performed = s.exercises.flatMap(\.sets).filter(\.isPerformed)
                let activity = s.outdoor.map { PartnerAppMappers.outdoorActivity($0.sport) } ?? "strength_training"
                let names = s.exercises.map(\.name).filter { !$0.isEmpty }
                let title = s.outdoor == nil && !names.isEmpty ? names.prefix(3).joined(separator: ", ") : nil
                let duration = s.durationSeconds > 0 ? s.durationSeconds : Int(max(0, end.timeIntervalSince(start)).rounded())
                out.append(PartnerAppMappers.workout(
                    id: s.id.uuidString.lowercased(), day: s.stableDiaryDateKey, activity: activity, title: title, start: start, end: end,
                    durationS: duration, kcal: s.caloriesBurned, distanceM: s.outdoor?.distanceM, paceSPerKm: s.outdoor?.avgPaceSecondsPerKm,
                    avgHR: s.heartRate?.avgHr, maxHR: s.heartRate?.maxHr, load: s.heartRate?.trimp,
                    sets: performed.isEmpty ? nil : performed.count,
                    volumeKg: PartnerAppMappers.volumeKg(performed.map { ($0.weight, $0.weightUnit, $0.reps) }), source: "ayuvo"))
            }
        }
        if let imported = try decode(ImportedHealthWorkoutStore.PersistedState.self, key: ImportedHealthWorkoutStore.defaultStorageKey, defaults: defaults) {
            for w in imported.workouts {
                out.append(PartnerAppMappers.workout(
                    id: w.id.uuidString.lowercased(), day: w.diaryDateKey, activity: PartnerAppMappers.activitySlug(w.activityTitle),
                    title: w.activityTitle, start: w.startedAt, end: w.endedAt, durationS: w.durationSeconds, kcal: w.totalEnergyBurned,
                    distanceM: nil, paceSPerKm: nil, avgHR: nil, maxHR: nil, load: nil, sets: nil, volumeKg: nil, source: w.sourceName))
            }
        }
        return out
    }

    private static func format(_ v: Double) -> String {
        v.rounded() == v ? String(Int(v)) : String(format: "%.2f", v).replacingOccurrences(of: #"0+$"#, with: "", options: .regularExpression)
    }
}

// MARK: - The production registry

nonisolated enum PartnerSources {
    /// Every source over the real stores. Each call opens fresh read-only connections (closed by `close`).
    static func live(now: @escaping @Sendable () -> Date = { Date() }) -> (registry: PartnerSourceRegistry, readers: [PartnerSQLiteReader]) {
        let health = PartnerSQLiteReader(url: HealthDatabaseLocation.databaseURL())
        let meds = PartnerSQLiteReader(url: MedicationsLocation.databaseURL())
        let records = PartnerSQLiteReader(url: RecordsLocation.databaseURL())
        let list: [any PartnerRecordSource] = [
            PartnerRollupSource(reader: health),
            PartnerHourlySource(reader: health, now: now),
            PartnerSampleSource(reader: health, now: now),
            PartnerDerivedSource(reader: health),
            PartnerAnalyticsSource(reader: health),
            PartnerSleepNightSource(reader: health),
            PartnerSnapshotSource(type: "food_entry") { try await MainActor.run { try PartnerAppSnapshots.food() } },
            PartnerSnapshotSource(type: "water_day") { try await MainActor.run { try PartnerAppSnapshots.water() } },
            PartnerSnapshotSource(type: "weight") { try await MainActor.run { try PartnerAppSnapshots.weight() } },
            PartnerSnapshotSource(type: "workout") { try await MainActor.run { try PartnerAppSnapshots.workouts() } },
            PartnerMedicationSource(reader: meds),
            PartnerScheduleSource(reader: meds),
            PartnerDoseLogSource(reader: meds),
            PartnerReportOverviewSource(reader: records),
        ]
        return (PartnerSourceRegistry(list), [health, meds, records])
    }
}
