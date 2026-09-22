import Foundation

// Dashboard snapshot for the Today and My Metrics widgets (docs/widgets.md "Dashboard snapshot").
// The app writes it, widgets only read it. This file has a byte-identical copy at
// FudAIWidgets/Shared/WidgetDashboardSnapshot.swift; `WidgetSharedCopiesTests` fails when they differ.
// The older food/water `WidgetSnapshot` is separate and unchanged (the Watch decodes its layout).

nonisolated struct WidgetDashboardSnapshot: Codable, Equatable, Sendable {
    static let currentVersion = 1

    /// One Eat / Move / Drink ring. Text is formatted by the app with the user's units.
    struct Ring: Codable, Equatable, Sendable {
        enum State: String, Codable, Sendable {
            case value, noGoal, noData, connect, off
        }

        var progress: Double
        var valueText: String
        var goalText: String
        var state: State
        /// Shown instead of `valueText` once the snapshot's day is over ("0" for Eat / Drink,
        /// "—" for Move, which the app cannot know without a fresh read).
        var emptyValueText: String
    }

    struct Rings: Codable, Equatable, Sendable {
        var eat: Ring
        var move: Ring
        /// Nil while water tracking is off.
        var drink: Ring?
    }

    struct Fasting: Codable, Equatable, Sendable {
        var enabled: Bool
        var activeStartedAt: Date?
        var goalMinutes: Int?

        var goalDate: Date? {
            guard let activeStartedAt, let goalMinutes else { return nil }
            return activeStartedAt.addingTimeInterval(TimeInterval(goalMinutes * 60))
        }
    }

    struct Dose: Codable, Equatable, Sendable {
        var name: String
        var scheduledAt: Date
        /// `DoseStatus` raw value: scheduled, due, taken, skipped, missed, snoozed.
        var status: String

        var isPending: Bool { status == "scheduled" || status == "due" || status == "snoozed" }
    }

    struct Medications: Codable, Equatable, Sendable {
        var doses: [Dose]
        var taken: Int
        var total: Int
    }

    /// A pre-formatted row (weight, body fat, today's workout).
    struct Row: Codable, Equatable, Sendable {
        var title: String
        var valueText: String
        var at: Date?
    }

    /// One metric for the My Metrics widget, keyed by `WidgetMetricOption` raw value.
    struct Metric: Codable, Equatable, Sendable {
        var key: String
        var title: String
        var systemImage: String
        var tintHex: String
        var valueText: String
        var unitText: String
        /// 0…1 towards the goal, nil when the metric has no goal or no value.
        var progress: Double?
        /// Today's total (sum metrics); ignored after the snapshot's day ends.
        var dayScoped: Bool
        var at: Date?
        /// "Off" when the tracker is disabled in Settings.
        var caption: String?
    }

    var version: Int
    var generatedAt: Date
    var dayStart: Date
    var rings: Rings
    var fasting: Fasting
    /// Nil when there is no medications database (the widget never creates one).
    var medications: Medications?
    var weight: Row?
    var bodyFat: Row?
    var workoutToday: Row?
    var metrics: [Metric]
    var waterTrackingEnabled: Bool
    var fastingTrackingEnabled: Bool

    // MARK: - Reading for display

    func isCurrentDay(_ now: Date, calendar: Calendar = .current) -> Bool {
        calendar.isDate(dayStart, inSameDayAs: now)
    }

    /// The snapshot as it should render at `now`: day-scoped values are cleared once the
    /// snapshot's day is over. Weight, body fat, sleep, heart rate and an active fast stay.
    func display(at now: Date, calendar: Calendar = .current) -> WidgetDashboardSnapshot {
        guard !isCurrentDay(now, calendar: calendar) else { return self }
        var copy = self
        copy.rings.eat = rings.eat.cleared
        copy.rings.move = rings.move.cleared
        copy.rings.drink = rings.drink?.cleared
        copy.medications = nil
        copy.workoutToday = nil
        copy.metrics = metrics.map { metric in
            guard metric.dayScoped else { return metric }
            var cleared = metric
            cleared.valueText = "—"
            cleared.progress = nil
            cleared.at = nil
            return cleared
        }
        return copy
    }

    func metric(_ key: String) -> Metric? {
        metrics.first { $0.key == key }
    }

    /// The first pending dose at or after `now`; otherwise the earliest overdue pending dose.
    func nextDose(after now: Date) -> Dose? {
        guard let doses = medications?.doses.filter(\.isPending).sorted(by: { $0.scheduledAt < $1.scheduledAt }) else { return nil }
        return doses.first { $0.scheduledAt >= now } ?? doses.first
    }

    /// Moments the widgets should re-render: pending dose times, the fasting goal and midnight.
    func refreshDates(after now: Date, calendar: Calendar = .current) -> [Date] {
        var dates: [Date] = medications?.doses.filter { $0.isPending && $0.scheduledAt > now }.map(\.scheduledAt) ?? []
        if let goal = fasting.goalDate, goal > now { dates.append(goal) }
        if let midnight = calendar.date(byAdding: .day, value: 1, to: calendar.startOfDay(for: now)) {
            dates.append(midnight)
        }
        return Array(Set(dates)).sorted()
    }

    /// Equality that ignores `generatedAt`, so a rewrite only happens when content changed.
    func hasSameContent(as other: WidgetDashboardSnapshot) -> Bool {
        var lhs = self
        var rhs = other
        lhs.generatedAt = .distantPast
        rhs.generatedAt = .distantPast
        return lhs == rhs
    }

    // MARK: - Storage (App Group file)

    private static let fileName = "widget_dashboard_v1.json"

    @MainActor
    private static var directoryURL: URL? {
        FileManager.default
            .containerURL(forSecurityApplicationGroupIdentifier: WidgetSnapshot.appGroupID)?
            .appendingPathComponent("Library/Application Support/AyuvoWidgets", isDirectory: true)
    }

    @MainActor
    private static var fileURL: URL? {
        directoryURL?.appendingPathComponent(fileName, isDirectory: false)
    }

    @MainActor
    static func read() -> WidgetDashboardSnapshot? {
        guard let url = fileURL, let data = try? Data(contentsOf: url) else { return nil }
        return decode(data)
    }

    static func decode(_ data: Data) -> WidgetDashboardSnapshot? {
        guard let snapshot = try? JSONDecoder().decode(WidgetDashboardSnapshot.self, from: data),
              snapshot.version <= currentVersion
        else { return nil }
        return snapshot
    }

    @MainActor
    static func write(_ snapshot: WidgetDashboardSnapshot) {
        guard let directoryURL, let fileURL,
              let data = try? JSONEncoder().encode(snapshot) else { return }
        try? FileManager.default.createDirectory(at: directoryURL, withIntermediateDirectories: true)
        try? data.write(to: fileURL, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
    }

    @MainActor
    static func clear() {
        if let fileURL { try? FileManager.default.removeItem(at: fileURL) }
    }
}

nonisolated extension WidgetDashboardSnapshot.Ring {
    var cleared: WidgetDashboardSnapshot.Ring {
        var ring = self
        ring.progress = 0
        ring.valueText = emptyValueText
        if ring.state == .value { ring.state = emptyValueText == "—" ? .noData : .value }
        return ring
    }
}
