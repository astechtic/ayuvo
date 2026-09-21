import Foundation
import Testing
@testable import calorietracker

@MainActor
struct HealthDataStoreTests {
    private typealias F = HealthTestFixtures

    private func makeStore() throws -> (HealthDataStore, HealthDataRuntime, UserDefaults, URL) {
        let directory = try F.temporaryDirectory()
        let suite = "HealthDataStoreTests-\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suite)!
        defaults.removePersistentDomain(forName: suite)
        let runtime = HealthDataRuntime(databaseURL: directory.appendingPathComponent("Health/health.sqlite"))
        let store = HealthDataStore(runtime: runtime, defaults: defaults, calendar: F.calendar)
        return (store, runtime, defaults, directory)
    }

    @Test func pinsDefaultAndPersistUnderSummaryFavourites() throws {
        let (store, _, defaults, directory) = try makeStore()
        defer { try? FileManager.default.removeItem(at: directory) }
        #expect(store.pinnedTypeIDs == MetricPins.defaultIDs)
        #expect(store.pinnedTypeIDs.first == "app:calories")
        #expect(store.pinnedHealthTypeIDs == ["steps", "sleep", "heart_rate", "active_energy"])
        #expect(defaults.stringArray(forKey: MetricPins.key) == MetricPins.defaultIDs, "migration result is persisted")
        store.togglePin("weight")
        #expect(defaults.stringArray(forKey: MetricPins.key)?.contains("weight") == true)
        #expect(defaults.object(forKey: HealthDataStore.homeTilesKey) == nil, "the legacy key is never written")
        store.setHomeTilesVisible(false)
        #expect(defaults.stringArray(forKey: MetricPins.key) == [])
        #expect(!store.showsHomeTile)
    }

    @Test func legacyHomeTilesMigrateOnceIntoSummaryFavourites() throws {
        let directory = try F.temporaryDirectory()
        defer { try? FileManager.default.removeItem(at: directory) }
        let suite = "HealthDataStoreTests-\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suite)!
        defaults.removePersistentDomain(forName: suite)
        defaults.set(["sleep", "steps"], forKey: HealthDataStore.homeTilesKey)
        let runtime = HealthDataRuntime(databaseURL: directory.appendingPathComponent("Health/health.sqlite"))
        let store = HealthDataStore(runtime: runtime, defaults: defaults, calendar: F.calendar)
        #expect(Array(store.pinnedTypeIDs.prefix(2)) == ["sleep", "steps"])
        #expect(store.pinnedTypeIDs.contains("app:calories"))
        // A legacy "tiles off" list stays off.
        let offSuite = "HealthDataStoreTests-\(UUID().uuidString)"
        let offDefaults = UserDefaults(suiteName: offSuite)!
        offDefaults.removePersistentDomain(forName: offSuite)
        offDefaults.set([String](), forKey: HealthDataStore.homeTilesKey)
        let off = HealthDataStore(runtime: runtime, defaults: offDefaults, calendar: F.calendar)
        #expect(off.pinnedTypeIDs.isEmpty)
    }

    @Test func homeSnapshotSkipsAppMetricPins() async throws {
        let (store, runtime, _, directory) = try makeStore()
        defer { try? FileManager.default.removeItem(at: directory) }
        #expect(await runtime.openIfNeeded())
        store.setPinnedTypeIDs(["app:calories", "steps"])
        await store.refreshSnapshots()
        #expect(store.homeSnapshot?.tiles.map(\.typeID) == ["steps"])
    }

    @Test func coachAccessRequiresAnAffirmativeAct() throws {
        let (store, _, defaults, directory) = try makeStore()
        defer { try? FileManager.default.removeItem(at: directory) }
        #expect(!store.coachHealthDataEnabled)
        #expect(defaults.object(forKey: HealthDataStore.coachEnabledKey) == nil, "no silent default")
        store.setCoachAccess(true)
        #expect(store.coachHealthDataEnabled)
        #expect(defaults.bool(forKey: HealthDataStore.coachEnabledKey))
        #expect(defaults.string(forKey: HealthDataStore.coachConsentedAtKey) != nil)
    }

    @Test func syncIsSkippedWhileHealthSyncIsOff() async throws {
        let (store, _, _, directory) = try makeStore()
        defer { try? FileManager.default.removeItem(at: directory) }
        // The store reads `healthKitEnabled` from its own (empty) suite → off.
        let outcome = await store.sync(.manual)
        #expect(outcome == .skipped(reason: "health sync is off"))
        #expect(await store.coachContext() == nil)
    }

    @Test func clearSyncedDataRemovesTheMirrorButKeepsPreferences() async throws {
        let (store, runtime, defaults, directory) = try makeStore()
        defer { try? FileManager.default.removeItem(at: directory) }
        store.setCoachAccess(true)
        #expect(await runtime.openIfNeeded())
        let writer = try #require(runtime.writer)
        try await writer.upsertSamples([F.row(type: F.steps, start: F.date(2026, 9, 1), value: 10)])
        await store.refreshSnapshots()
        #expect(store.typeSummaries.count == 1)
        #expect(store.hasAnyData)
        await store.clearSyncedData()
        #expect(!FileManager.default.fileExists(atPath: runtime.databaseURL.path))
        #expect(store.typeSummaries.isEmpty)
        #expect(defaults.bool(forKey: HealthDataStore.coachEnabledKey), "clear keeps consent")
        await store.deleteAllData()
        #expect(defaults.object(forKey: HealthDataStore.coachEnabledKey) == nil)
        #expect(!store.coachHealthDataEnabled)
    }

    @Test func snapshotsNeverWriteHealthValuesToUserDefaults() async throws {
        let (store, runtime, defaults, directory) = try makeStore()
        defer { try? FileManager.default.removeItem(at: directory) }
        #expect(await runtime.openIfNeeded())
        let writer = try #require(runtime.writer)
        try await writer.upsertSamples([
            F.row(type: F.steps, start: F.date(2026, 9, 1), value: 4242),
            F.row(type: F.weight, start: F.date(2026, 9, 1), value: 81.5),
        ])
        store.togglePin("weight")
        await store.refreshSnapshots()
        let allowed: Set<String> = [
            HealthDataStore.homeTilesKey, MetricPins.key, HealthDataStore.coachEnabledKey, HealthDataStore.coachConsentedAtKey,
            HealthDataStore.lastSyncAtKey, HealthDataStore.rateLimitedUntilKey, HealthDataStore.promptedVersionKey, HealthGlucoseUnit.storageKey,
        ]
        for (key, value) in defaults.dictionaryRepresentation() where key.hasPrefix("health") || key.hasPrefix("coachHealth") {
            #expect(allowed.contains(key), "unexpected health key \(key)")
            let text = String(describing: value)
            #expect(!text.contains("4242") && !text.contains("81.5"), "health value leaked into UserDefaults under \(key)")
        }
        #expect(store.homeSnapshot?.tiles.contains { $0.typeID == "weight" && $0.valueText != "—" } == true)
    }

    @Test func reloadAfterRestoreDropsThrottlesAndKeepsData() async throws {
        let (store, runtime, defaults, directory) = try makeStore()
        defer { try? FileManager.default.removeItem(at: directory) }
        #expect(await runtime.openIfNeeded())
        try await runtime.writer!.upsertSamples([F.row(type: F.steps, start: F.date(2026, 9, 1), value: 10)])
        defaults.set(Date().timeIntervalSince1970, forKey: HealthDataStore.lastSyncAtKey)
        store.reloadAfterRestore()
        #expect(store.lastSyncAt == nil)
        #expect(defaults.object(forKey: HealthDataStore.lastSyncAtKey) == nil)
        #expect(try await runtime.writer!.sampleCount() == 1, "restore never wipes the mirror")
    }

    @Test func metricTypeResolutionFallsBackForUnknownSlugs() throws {
        let (store, _, _, directory) = try makeStore()
        defer { try? FileManager.default.removeItem(at: directory) }
        let type = store.metricType(for: "brand_new_metric")
        #expect(type.category == .other)
        #expect(type.id == "brand_new_metric")
        #expect(store.metricType(for: "steps").id == "steps")
    }
}
