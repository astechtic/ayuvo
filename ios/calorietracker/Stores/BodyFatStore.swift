import Foundation
import SwiftUI

/// Local-only store for body-fat history. Mirrors WeightStore but trimmer:
///   - no HealthKit sync (no body-fat callbacks wired into calorietrackerApp)
///   - no goal-crossing notification yet (goalBodyFatPercentage exists but
///     the celebration UX hasn't been requested — easy to add later)
///   - the latest entry's value is treated as the user's "current" body fat
///     and pushed to UserProfile.bodyFatPercentage on every add, so Katch-McArdle
///     BMR/TDEE re-evaluates every time the user logs a new reading
@Observable
class BodyFatStore {
    private(set) var entries: [BodyFatEntry] = [] {
        didSet { revision &+= 1 }
    }
    /// Bumped on every change to `entries` (metric caches key on it).
    private(set) var revision = 0
    /// Wired in calorietrackerApp.swift → HealthKitManager.writeBodyFat(for:)
    /// when HealthKit sync is enabled. Mirrors WeightStore.onEntryAdded.
    var onEntryAdded: ((BodyFatEntry) -> Void)?
    /// Wired to HealthKitManager.deleteBodyFat(entryID:) so per-entry deletes
    /// also pull the matching HK sample (matched by ayuvo_bodyfat_id metadata).
    var onEntryDeleted: ((UUID) -> Void)?

    private let storageKey = "bodyFatEntries"
    private let defaults: UserDefaults
    static let externalChangeNotification = Notification.Name("app.ayuvo.bodyFatEntriesDidChange")

    init(defaults: UserDefaults = .standard, observesExternalChanges: Bool = true) {
        self.defaults = defaults
        loadEntries()
        if observesExternalChanges { startObservingExternalChanges() }
    }

    deinit {
        if let externalChangeObserver { NotificationCenter.default.removeObserver(externalChangeObserver) }
    }

    /// Seed the first entry from the user's onboarding-set body fat. Called
    /// once from calorietrackerApp once onboarding completes — idempotent
    /// (no-op if any entry already exists, so re-onboarding can't duplicate).
    func seedInitialBodyFatFromProfileIfEmpty(_ fraction: Double) {
        guard entries.isEmpty else { return }
        addEntry(BodyFatEntry(date: .now, bodyFatFraction: fraction))
    }

    var latestEntry: BodyFatEntry? {
        entries.sorted { $0.date > $1.date }.first
    }

    func entries(in range: ClosedRange<Date>) -> [BodyFatEntry] {
        entries
            .filter { range.contains($0.date) }
            .sorted { $0.date < $1.date }
    }

    func addEntry(_ entry: BodyFatEntry) {
        entries.append(entry)
        saveEntries()
        onEntryAdded?(entry)
        syncProfileBodyFatToLatest()
    }

    func deleteEntry(_ entry: BodyFatEntry) {
        let id = entry.id
        entries.removeAll { $0.id == id }
        saveEntries()
        onEntryDeleted?(id)
        syncProfileBodyFatToLatest()
    }

    /// Keep UserProfile.bodyFatPercentage aligned with the latest reading so
    /// Katch-McArdle BMR + Settings → Profile → Body Fat row never drift apart.
    /// If the store is empty after a delete, leave the profile value alone —
    /// silently dropping someone's BMR formula because they cleared one row
    /// would surprise them; they can clear it explicitly via Settings.
    private func syncProfileBodyFatToLatest() {
        guard var profile = UserProfile.load(),
              let newest = entries.sorted(by: { $0.date > $1.date }).first else { return }
        if abs((profile.bodyFatPercentage ?? -1) - newest.bodyFatFraction) > 0.0001 {
            profile.bodyFatPercentage = newest.bodyFatFraction
            profile.save()
        }
    }

    func reloadFromDefaults() {
        loadEntries()
    }

    func replaceAllEntries(_ newEntries: [BodyFatEntry]) {
        entries = newEntries
        saveEntries()
    }

    /// Bulk-import body-fat samples discovered from HealthKit (e.g. years of
    /// smart-scale history). Bypasses onEntryAdded so the imports don't echo
    /// back to HK as fresh writes — these samples already exist there.
    func importExternalEntries(_ external: [BodyFatEntry]) {
        guard !external.isEmpty else { return }
        entries.append(contentsOf: external)
        saveEntries()
        syncProfileBodyFatToLatest()
    }

    private func saveEntries() {
        if let data = try? JSONEncoder().encode(entries) {
            defaults.set(data, forKey: storageKey)
        }
    }

    private func loadEntries() {
        guard let data = defaults.data(forKey: storageKey),
              let decoded = try? JSONDecoder().decode([BodyFatEntry].self, from: data)
        else { return }
        entries = decoded
    }

    // MARK: - External changes (Siri / Shortcuts / deep-link actions run in this process)

    @ObservationIgnored private var externalChangeObserver: NSObjectProtocol?

    private func startObservingExternalChanges() {
        externalChangeObserver = NotificationCenter.default.addObserver(
            forName: Self.externalChangeNotification, object: nil, queue: nil
        ) { [weak self] _ in
            if Thread.isMainThread {
                MainActor.assumeIsolated { self?.reloadFromDefaults() }
            } else {
                DispatchQueue.main.async { self?.reloadFromDefaults() }
            }
        }
    }

    /// Tells the app's live store that an action wrote through a separate instance, so the next
    /// save here cannot overwrite that write with a stale in-memory copy.
    static func postExternalChangeNotification() {
        NotificationCenter.default.post(name: externalChangeNotification, object: nil)
    }
}
