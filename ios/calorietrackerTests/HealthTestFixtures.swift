import Foundation
import HealthKit
@testable import calorietracker

/// Shared helpers for the Health Data hub suites.
enum HealthTestFixtures {
    static let calendar: Calendar = {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(identifier: "Europe/Berlin")!
        return calendar
    }()

    static let bundleID = "com.ayuvo.health"

    /// Repo root derived from this file (`ios/calorietrackerTests/…`).
    static var repoRootURL: URL {
        URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent() // calorietrackerTests
            .deletingLastPathComponent() // ios
            .deletingLastPathComponent() // repo
    }

    static var sharedSchemaURL: URL { repoRootURL.appendingPathComponent("shared/health/schema.sql") }
    static var sharedRegistryURL: URL { repoRootURL.appendingPathComponent("shared/health/metric_registry.json") }
    static var androidFixtureURL: URL {
        URL(fileURLWithPath: #filePath).deletingLastPathComponent().appendingPathComponent("Fixtures/health/android-sample.zip")
    }

    static func temporaryDirectory() throws -> URL {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("health-tests-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
        return url
    }

    static func ms(_ date: Date) -> Int64 { Int64(date.timeIntervalSince1970 * 1000) }

    static func date(_ year: Int, _ month: Int, _ day: Int, _ hour: Int = 12, _ minute: Int = 0) -> Date {
        var components = DateComponents()
        components.year = year
        components.month = month
        components.day = day
        components.hour = hour
        components.minute = minute
        return calendar.date(from: components)!
    }

    static func row(
        id: String = UUID().uuidString.lowercased(),
        type: HealthMetricType,
        start: Date,
        end: Date? = nil,
        value: Double? = nil,
        value2: Double? = nil,
        value3: Double? = nil,
        count: Int = 1,
        categoryValue: Int? = nil,
        source: String = "com.apple.health",
        extraJSON: String? = nil,
        origin: Int = 0,
        updatedMs: Int64? = nil
    ) -> HealthSampleRow {
        let endDate = end ?? start
        let offset = calendar.timeZone.secondsFromGMT(for: start)
        let startMs = ms(start), endMs = ms(endDate)
        return HealthSampleRow(
            id: id,
            typeID: type.id,
            startMs: startMs,
            endMs: endMs,
            startOffsetS: offset,
            endOffsetS: offset,
            localDay: HealthRollupMath.localDay(startMs: startMs, endMs: endMs, startOffsetS: offset, endOffsetS: offset, attribution: type.dayAttribution, calendar: calendar),
            value: value,
            value2: value2,
            value3: value3,
            valueText: nil,
            unit: type.unit,
            categoryValue: categoryValue,
            title: nil,
            extraJSON: extraJSON,
            count: count,
            sourceID: source,
            device: nil,
            deviceType: nil,
            recordingMethod: nil,
            clientRecordID: nil,
            origin: origin,
            deleted: 0,
            updatedMs: updatedMs ?? endMs
        )
    }

    static var steps: HealthMetricType { HealthMetricRegistry.type(id: "steps")! }
    static var heartRate: HealthMetricType { HealthMetricRegistry.type(id: "heart_rate")! }
    static var weight: HealthMetricType { HealthMetricRegistry.type(id: "weight")! }
    static var sleep: HealthMetricType { HealthMetricRegistry.type(id: "sleep")! }
    static var bloodPressure: HealthMetricType { HealthMetricRegistry.type(id: "blood_pressure")! }
    static var activeEnergy: HealthMetricType { HealthMetricRegistry.type(id: "active_energy")! }
}

/// Scripted `HealthKitReading` for engine tests. Pages are consumed in order per type;
/// once exhausted an empty page (with a terminal anchor) is returned.
final class FakeHealthKitReader: HealthKitReading, @unchecked Sendable {
    struct Script {
        var pages: [HealthKitPage] = []
        var recent: [HealthSampleRow] = []
        var error: Error?
        var failAnchoredOnly = false
    }

    private let lock = NSLock()
    private var scripts: [String: Script]
    private(set) var anchorsSeen: [String: [Data?]] = [:]
    private(set) var recentCalls: [String] = []
    private(set) var inFlight = 0
    private(set) var maxInFlight = 0
    private(set) var startOrder: [String] = []
    var limits: [String: Date] = [:]
    var onPage: (@Sendable (String) -> Void)?
    var pageDelayNanoseconds: UInt64 = 0

    init(scripts: [String: Script] = [:], limits: [String: Date] = [:]) {
        self.scripts = scripts
        self.limits = limits
    }

    static let terminalAnchor = Data("terminal".utf8)

    func anchoredPage(type: HealthMetricType, anchor: Data?, limit: Int) async throws -> HealthKitPage {
        lock.lock()
        inFlight += 1
        maxInFlight = max(maxInFlight, inFlight)
        if !startOrder.contains(type.id) { startOrder.append(type.id) }
        anchorsSeen[type.id, default: []].append(anchor)
        var script = scripts[type.id] ?? Script()
        let error = script.error
        let page = script.pages.isEmpty ? nil : script.pages.removeFirst()
        scripts[type.id] = script
        lock.unlock()
        defer {
            lock.lock()
            inFlight -= 1
            lock.unlock()
        }
        if pageDelayNanoseconds > 0 {
            try await Task.sleep(nanoseconds: pageDelayNanoseconds)
        }
        if let error { throw error }
        onPage?(type.id)
        if let page { return page }
        return HealthKitPage(rows: [], deletedIDs: [], sources: [], anchor: Self.terminalAnchor)
    }

    func recentRows(type: HealthMetricType, since: Date, limit: Int) async throws -> [HealthSampleRow] {
        lock.lock()
        recentCalls.append(type.id)
        let script = scripts[type.id] ?? Script()
        lock.unlock()
        if let error = script.error, !script.failAnchoredOnly { throw error }
        return script.recent
    }

    func earliestAuthorizedSampleDates(types: [HealthMetricType]) async -> [String: Date] {
        lock.lock()
        defer { lock.unlock() }
        return limits
    }

    func authorizationRequestNeeded(types: [HealthMetricType]) async -> Bool? { false }

    func preferredUnitStrings(types: [HealthMetricType]) async -> [String: String] { [:] }

    func remainingPages(_ typeID: String) -> Int {
        lock.lock()
        defer { lock.unlock() }
        return scripts[typeID]?.pages.count ?? 0
    }
}
