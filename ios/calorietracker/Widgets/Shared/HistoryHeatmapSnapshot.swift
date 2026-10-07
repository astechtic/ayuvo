import Foundation

// Workout and Food history widgets (docs/widgets.md "History widgets"; contract
// shared/widgets/history_heatmap.json). The app writes the snapshot (`HistoryHeatmapBuilder`); the
// widgets only read it. This file has a byte-identical copy at FudAIWidgets/Shared/HistoryHeatmapSnapshot.swift;
// `WidgetSharedCopiesTests` fails when they differ.

/// Levels and grid layout of the history heatmaps.
nonisolated enum HistoryHeatmap {
    /// Days the snapshot covers (53 weeks), oldest first.
    static let days = 371
    static let levels = 4
    /// Minimum seconds trained for levels 1…4; any workout is at least level 1.
    static let workoutMinSecondsPerLevel: [Double] = [1, 900, 1800, 3600]
    /// Minimum distinct meal types for levels 1…4.
    static let foodMinMealsPerLevel = [1, 2, 3, 4]

    enum WeekStart: String, Codable, Sendable {
        case monday, sunday

        /// `Calendar.firstWeekday` numbering (1 = Sunday).
        var firstWeekday: Int { self == .sunday ? 1 : 2 }
    }

    static func workoutLevel(workouts: Int, seconds: Double) -> Int {
        guard workouts > 0 else { return 0 }
        let reached = workoutMinSecondsPerLevel.filter { seconds >= $0 }.count
        return max(1, reached)
    }

    /// `mealTypes`: the meal type of each of the day's entries (duplicates allowed).
    static func foodLevel<S: Sequence>(mealTypes: S) -> Int where S.Element == String {
        let distinct = Set(mealTypes).count
        return foodMinMealsPerLevel.filter { distinct >= $0 }.count
    }

    struct Grid: Equatable, Sendable {
        /// The day in row 0 of the first column.
        var firstDay: Date
        var columns: Int
        /// Row of today in the last column.
        var todayRow: Int
        /// Cells after today in the last column (not drawn).
        var hiddenAfterToday: Int
    }

    /// 7 rows starting at `weekStart`; the last column is the week that contains `today`.
    static func grid(today: Date, weekStart: WeekStart, columns: Int, calendar: Calendar) -> Grid {
        let day = calendar.startOfDay(for: today)
        let weekday = calendar.component(.weekday, from: day)
        let row = (weekday - weekStart.firstWeekday + 7) % 7
        let count = max(1, columns)
        let first = calendar.date(byAdding: .day, value: -row - 7 * (count - 1), to: day) ?? day
        return Grid(firstDay: first, columns: count, todayRow: row, hiddenAfterToday: 6 - row)
    }

    /// `yyyy-MM-dd` of the local day.
    static func dayKey(_ date: Date, calendar: Calendar) -> String {
        let parts = calendar.dateComponents([.year, .month, .day], from: date)
        return String(format: "%04d-%02d-%02d", parts.year ?? 0, parts.month ?? 0, parts.day ?? 0)
    }

    static func date(dayKey: String, calendar: Calendar) -> Date? {
        let parts = dayKey.split(separator: "-").compactMap { Int($0) }
        guard parts.count == 3 else { return nil }
        return calendar.date(from: DateComponents(year: parts[0], month: parts[1], day: parts[2]))
    }
}

/// The last `HistoryHeatmap.days` days of both heatmaps.
nonisolated struct HistoryHeatmapSnapshot: Codable, Equatable, Sendable {
    static let currentVersion = 1
    static let workoutKind = "WorkoutHistoryWidget"
    static let foodKind = "FoodHistoryWidget"

    struct Series: Codable, Equatable, Sendable {
        /// One digit 0…4 per day, oldest first, ending at `lastDay`.
        var levels: String
        /// Days with level > 0.
        var activeDays: Int
        /// Workout only: seconds trained over the covered days.
        var totalSeconds: Double?
    }

    enum Kind: String, Sendable {
        case workout, food
    }

    var version: Int = currentVersion
    var generatedAt: Date
    /// The last covered local day (`yyyy-MM-dd`), the day the snapshot was built.
    var lastDay: String
    var weekStart: HistoryHeatmap.WeekStart
    var workout: Series
    var food: Series

    func series(_ kind: Kind) -> Series { kind == .workout ? workout : food }

    /// The level of a local day; 0 for days the snapshot does not cover.
    func level(_ kind: Kind, on day: Date, calendar: Calendar) -> Int {
        guard let last = HistoryHeatmap.date(dayKey: lastDay, calendar: calendar) else { return 0 }
        let start = calendar.startOfDay(for: day)
        guard let back = calendar.dateComponents([.day], from: start, to: last).day, back >= 0 else { return 0 }
        let digits = Array(series(kind).levels.utf8)
        let index = digits.count - 1 - back
        guard index >= 0, index < digits.count else { return 0 }
        return max(0, min(HistoryHeatmap.levels, Int(digits[index]) - 48))
    }

    /// Same content, ignoring when it was generated.
    func hasSameContent(as other: HistoryHeatmapSnapshot) -> Bool {
        lastDay == other.lastDay && weekStart == other.weekStart && workout == other.workout && food == other.food
    }

    // MARK: - Storage (App Group file)

    private static let fileName = "history_heatmap_v1.json"

    @MainActor
    private static var fileURL: URL? {
        FileManager.default
            .containerURL(forSecurityApplicationGroupIdentifier: WidgetSnapshot.appGroupID)?
            .appendingPathComponent("Library/Application Support/AyuvoWidgets", isDirectory: true)
            .appendingPathComponent(fileName, isDirectory: false)
    }

    @MainActor
    static func read() -> HistoryHeatmapSnapshot? {
        guard let url = fileURL, let data = try? Data(contentsOf: url) else { return nil }
        return decode(data)
    }

    static func decode(_ data: Data) -> HistoryHeatmapSnapshot? {
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .iso8601
        guard let snapshot = try? decoder.decode(HistoryHeatmapSnapshot.self, from: data),
              snapshot.version <= currentVersion
        else { return nil }
        return snapshot
    }

    static func encode(_ snapshot: HistoryHeatmapSnapshot) -> Data? {
        let encoder = JSONEncoder()
        encoder.dateEncodingStrategy = .iso8601
        return try? encoder.encode(snapshot)
    }

    @MainActor
    static func write(_ snapshot: HistoryHeatmapSnapshot) {
        guard let fileURL, let data = encode(snapshot) else { return }
        try? FileManager.default.createDirectory(at: fileURL.deletingLastPathComponent(), withIntermediateDirectories: true)
        try? data.write(to: fileURL, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
    }

    @MainActor
    static func clear() {
        if let fileURL { try? FileManager.default.removeItem(at: fileURL) }
    }
}
