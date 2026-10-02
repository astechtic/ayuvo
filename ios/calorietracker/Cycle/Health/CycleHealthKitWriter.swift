import Foundation
import HealthKit

/// Writes Ayuvo's cycle logs to Apple Health (docs/cycle-tracking.md §4) when the user turned on
/// "Sync with Apple Health":
/// - one `menstrualFlow` sample per period day (the day's flow, `unspecified` without one; cycle start on day 1),
/// - `intermenstrualBleeding` for Spotting, one category sample per mapped symptom per day (severity unspecified).
/// Every sample carries the `ayuvo_cycle` metadata key, so `CyclePlatformPeriods` never reads it back as a Health
/// period. Writes are idempotent: Ayuvo's own samples of the type for the affected days are deleted first.
nonisolated struct CycleHealthKitWriter: Sendable {
    static let metadataKey = CyclePlatformPeriods.ownMetadataKey

    let store: HKHealthStore
    var config: CycleConfig = .shared
    var calendar: Calendar = .current

    static var isAvailable: Bool { HKHealthStore.isHealthDataAvailable() }

    static var flowType: HKCategoryType { HKCategoryType(.menstrualFlow) }
    static var spottingType: HKCategoryType { HKCategoryType(.intermenstrualBleeding) }

    /// HealthKit symptom types for the catalogue keys that map (`symptoms[].healthkit`).
    static func symptomType(_ name: String) -> HKCategoryType? {
        let map: [String: HKCategoryTypeIdentifier] = [
            "abdominalCramps": .abdominalCramps, "headache": .headache, "lowerBackPain": .lowerBackPain,
            "bloating": .bloating, "breastPain": .breastPain, "acne": .acne, "fatigue": .fatigue, "nausea": .nausea,
            "constipation": .constipation, "diarrhea": .diarrhea, "pelvicPain": .pelvicPain,
            "generalizedBodyAche": .generalizedBodyAche, "dizziness": .dizziness, "appetiteChanges": .appetiteChanges,
            "sleepChanges": .sleepChanges,
        ]
        return map[name].map { HKCategoryType($0) }
    }

    /// Every type Ayuvo may write for cycle tracking (asked only when the user turns sync on).
    static func shareTypes(_ config: CycleConfig = .shared) -> Set<HKSampleType> {
        var types: Set<HKSampleType> = [flowType, spottingType]
        for s in config.symptoms {
            if let name = s.healthkit, let type = symptomType(name) { types.insert(type) }
        }
        return types
    }

    /// HealthKit `menstrualFlow` raw value of a flow key: 1 unspecified, 2 light, 3 medium, 4 heavy
    /// (Very heavy is written as heavy, docs §4).
    static func flowValue(_ key: String?) -> Int {
        switch key {
        case "light": 2
        case "medium": 3
        case "heavy", "very_heavy": 4
        default: 1
        }
    }

    // MARK: Day boundaries

    func dayStart(_ day: Int) -> Date {
        let text = CycleDay.string(day)
        var c = DateComponents()
        c.year = Int(text.prefix(4))
        c.month = Int(text.dropFirst(5).prefix(2))
        c.day = Int(text.dropFirst(8).prefix(2))
        return calendar.date(from: c) ?? Date(timeIntervalSince1970: Double(day) * 86_400)
    }

    func dayEnd(_ day: Int) -> Date { dayStart(day + 1) }

    private func ownSamples(from first: Int, to last: Int) -> NSPredicate {
        NSCompoundPredicate(andPredicateWithSubpredicates: [
            HKQuery.predicateForObjects(from: HKSource.default()),
            HKQuery.predicateForSamples(withStart: dayStart(first), end: dayEnd(last), options: [.strictStartDate]),
        ])
    }

    private func delete(_ type: HKObjectType, from first: Int, to last: Int) async throws {
        guard first <= last else { return }
        do {
            _ = try await store.deleteObjects(of: type, predicate: ownSamples(from: first, to: last))
        } catch let error as HKError where error.code == .errorNoData {
            return
        }
    }

    private func sample(_ type: HKCategoryType, value: Int, day: Int, extra: [String: Any] = [:]) -> HKCategorySample {
        var metadata: [String: Any] = [Self.metadataKey: "1"]
        metadata.merge(extra) { $1 }
        return HKCategorySample(type: type, value: value, start: dayStart(day), end: dayEnd(day), metadata: metadata)
    }

    // MARK: Writes

    /// Replaces the flow samples of a period: removes Ayuvo's own samples on `oldRange` ∪ the new days, then writes one
    /// per day of `[start, end ?? today]` (nothing when the period was deleted).
    func writePeriod(start: Int?, end: Int?, today: Int, oldRange: ClosedRange<Int>?, flows: [Int: String]) async throws {
        var lo = oldRange?.lowerBound, hi = oldRange?.upperBound
        let newRange: ClosedRange<Int>? = start.map { s in s...max(s, min(end ?? today, today)) }
        if let r = newRange {
            lo = min(lo ?? r.lowerBound, r.lowerBound)
            hi = max(hi ?? r.upperBound, r.upperBound)
        }
        if let lo, let hi { try await delete(Self.flowType, from: lo, to: hi) }
        guard let r = newRange else { return }
        var samples: [HKObject] = []
        for day in r {
            samples.append(sample(Self.flowType, value: Self.flowValue(flows[day]), day: day,
                                  extra: [HKMetadataKeyMenstrualCycleStart: day == r.lowerBound]))
        }
        if !samples.isEmpty { try await store.save(samples) }
    }

    /// Replaces one day's spotting and symptom samples (and the day's flow value when the day is inside an app period).
    func writeDay(_ log: CycleDayLogRecord, inPeriod period: ClosedRange<Int>?) async throws {
        let day = CycleDay.o(log.day)
        try await delete(Self.spottingType, from: day, to: day)
        for s in config.symptoms {
            if let name = s.healthkit, let type = Self.symptomType(name) { try await delete(type, from: day, to: day) }
        }
        var samples: [HKObject] = []
        if !log.deleted {
            if log.flow == "spotting" { samples.append(sample(Self.spottingType, value: HKCategoryValue.notApplicable.rawValue, day: day)) }
            for key in log.symptoms {
                guard let name = config.symptoms.first(where: { $0.key == key })?.healthkit, let type = Self.symptomType(name) else { continue }
                samples.append(sample(type, value: HKCategoryValueSeverity.unspecified.rawValue, day: day))
            }
        }
        if let period, period.contains(day) {
            try await delete(Self.flowType, from: day, to: day)
            samples.append(sample(Self.flowType, value: Self.flowValue(log.deleted ? nil : log.flow), day: day,
                                  extra: [HKMetadataKeyMenstrualCycleStart: day == period.lowerBound]))
        }
        if !samples.isEmpty { try await store.save(samples) }
    }

    /// Removes every cycle sample Ayuvo ever wrote ("Delete all cycle data").
    func removeAll() async throws {
        let predicate = HKQuery.predicateForObjects(withMetadataKey: Self.metadataKey)
        for type in Self.shareTypes(config) {
            do {
                _ = try await store.deleteObjects(of: type, predicate: predicate)
            } catch let error as HKError where error.code == .errorNoData {
                continue
            }
        }
    }
}

/// Runs the writer after local saves and on app start (docs §4): only rows marked `pending`/`failed`; an ongoing
/// period waits until it ends. Failures leave the local data alone and mark the row `failed` ("Not synced").
@MainActor
final class CycleHealthSync {
    static let shared = CycleHealthSync()

    private let runtime: CycleRuntime
    private var debounceTask: Task<Void, Never>?
    private var running = false

    init(runtime: CycleRuntime? = nil) {
        self.runtime = runtime ?? .shared
    }

    private var store: HKHealthStore { HealthKitManager.sharedHealthStore }

    /// Asks for write access to the cycle types. True when Health can be written.
    func requestAuthorization() async -> Bool {
        guard CycleHealthKitWriter.isAvailable else { return false }
        do {
            try await store.requestAuthorization(toShare: CycleHealthKitWriter.shareTypes(), read: [])
        } catch {
            return false
        }
        return store.authorizationStatus(for: CycleHealthKitWriter.flowType) == .sharingAuthorized
    }

    func syncSoon() {
        debounceTask?.cancel()
        debounceTask = Task { [weak self] in
            try? await Task.sleep(nanoseconds: 800_000_000)
            guard !Task.isCancelled else { return }
            await self?.syncPending()
        }
    }

    /// Writes pending rows. No-op unless the user turned sync on and HealthKit is available.
    func syncPending(now: Date = Date()) async {
        guard !running, runtime.databaseExists || runtime.isOpen, CycleHealthKitWriter.isAvailable,
              let repository = await runtime.openRepository(),
              let settings = try? await repository.settings(), settings.options.healthSync else { return }
        guard store.authorizationStatus(for: CycleHealthKitWriter.flowType) == .sharingAuthorized else { return }
        running = true
        defer { running = false }
        let writer = CycleHealthKitWriter(store: store)
        let today = CycleDay.today(now)
        guard let pending = try? await repository.pendingSync(),
              let live = try? await repository.periods() else { return }
        let logs = (try? await repository.dayLogs()) ?? []
        var flows: [Int: String] = [:]
        for log in logs where !log.deleted { if let f = log.flow { flows[CycleDay.o(log.day)] = f } }

        // An ongoing period is written once it ends (same as Android); it stays pending until then.
        for period in pending.periods where period.deleted || period.endDay != nil {
            let old = Self.writtenRange(period.platformIDsJSON)
            let start = period.deleted ? nil : CycleDay.ordinal(period.startDay)
            let end = period.endDay.flatMap(CycleDay.ordinal)
            do {
                try await writer.writePeriod(start: start, end: end, today: today, oldRange: old, flows: flows)
                let range = start.map { s in s...max(s, min(end ?? today, today)) }
                try? await repository.markPeriodSynced(id: period.id, platformIDsJSON: Self.rangeJSON(range))
            } catch {
                try? await repository.markPeriodSynced(id: period.id, platformIDsJSON: period.platformIDsJSON, state: CycleSyncState.failed)
            }
        }
        let periodRanges: [ClosedRange<Int>] = live.filter { !$0.deleted && $0.endDay != nil }.compactMap { p in
            guard let s = CycleDay.ordinal(p.startDay), let e = p.endDay.flatMap(CycleDay.ordinal) else { return nil }
            return s...max(s, min(e, today))
        }
        for log in pending.logs {
            let day = CycleDay.o(log.day)
            do {
                try await writer.writeDay(log, inPeriod: periodRanges.first { $0.contains(day) })
                try? await repository.markDayLogSynced(day: log.day, platformIDsJSON: #"{"healthkit":true}"#)
            } catch {
                try? await repository.markDayLogSynced(day: log.day, platformIDsJSON: log.platformIDsJSON, state: CycleSyncState.failed)
            }
        }
    }

    /// Every sample Ayuvo wrote, gone (Delete all cycle data with sync on).
    func removeAllWrittenSamples() async {
        guard CycleHealthKitWriter.isAvailable else { return }
        try? await CycleHealthKitWriter(store: store).removeAll()
    }

    /// `{"healthkit_range": ["yyyy-MM-dd", "yyyy-MM-dd"]}`: the days written last time.
    static func rangeJSON(_ range: ClosedRange<Int>?) -> String {
        guard let range else { return "{}" }
        return #"{"healthkit_range":["\#(CycleDay.string(range.lowerBound))","\#(CycleDay.string(range.upperBound))"]}"#
    }

    static func writtenRange(_ json: String) -> ClosedRange<Int>? {
        guard let data = json.data(using: .utf8),
              let o = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let r = o["healthkit_range"] as? [String], r.count == 2,
              let a = CycleDay.ordinal(r[0]), let b = CycleDay.ordinal(r[1]), a <= b else { return nil }
        return a...b
    }
}
