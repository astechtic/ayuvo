import Foundation
import Observation

/// One day of a derived metric after "native wins" (docs/derived-metrics.md §1): an Apple Health value for the
/// metric's native type that Ayuvo did not write, else Ayuvo's own estimate.
nonisolated struct DerivedDayValue: Sendable, Hashable {
    var day: String
    var value: Double
    var value2: Double?
    var value3: Double?
    /// Engine confidence of an Ayuvo estimate (1 normal, 0.6 medium, 0.3 low); nil for native values.
    var quality: Double?
    var isNative: Bool
    /// Name of the Apple Health source of a native value.
    var source: String?
}

/// Derived metric → chart series, formatting and source text. Pure except `days(reader:…)`, which reads the
/// `derived_daily_values` table and the native type's rows from the Health mirror.
nonisolated enum DerivedMetricSeries {
    static let ranges: [HealthDetailRange] = [.week, .month, .sixMonths, .year]

    /// Browse domain of a derived category.
    static func domainID(for category: String) -> String {
        switch category {
        case "energy": "activity"
        case "heart", "sleep", "activity", "mobility", "hearing", "body", "nutrition": category
        default: "other"
        }
    }

    /// Shared bucketing aggregation for the catalog `aggregation` ("max" buckets as an average, then takes the
    /// bucket maximum in `build`).
    static func aggregation(_ info: DerivedMetricInfo) -> MetricsReference.Aggregation {
        switch info.aggregation {
        case "sum": .sum
        case "latest": .last
        default: .avg
        }
    }

    static func chartKind(_ info: DerivedMetricInfo) -> MetricChartKind {
        info.chartKind == "bar" ? .bar : .line
    }

    // MARK: Native wins

    /// Native daily values from the native type's rows: deleted rows and rows Ayuvo wrote are ignored; the value
    /// is the day's mean and the source is the source with the most rows that day.
    static func nativeDays(rows: [HealthSampleRow], sourceNames: [String: String], ownBundleID: String) -> [String: DerivedPriority.Native] {
        var byDay: [String: [HealthSampleRow]] = [:]
        for row in rows where !row.isDeleted && row.value != nil && row.sourceID != ownBundleID {
            byDay[row.localDay, default: []].append(row)
        }
        var out: [String: DerivedPriority.Native] = [:]
        for (day, list) in byDay {
            var total = 0.0
            for r in list { total += r.value ?? 0 }
            let counts = Dictionary(grouping: list, by: \.sourceID).mapValues(\.count)
            let top = counts.max { $0.value != $1.value ? $0.value < $1.value : $0.key > $1.key }?.key
            out[day] = DerivedPriority.Native(value: total / Double(list.count), source: top.map { sourceNames[$0] ?? $0 })
        }
        return out
    }

    /// Per-day `DerivedPriority.priority` over the union of native and derived days, in day order.
    static func merge(derived: [DerivedDailyValueRow], native: [String: DerivedPriority.Native], enabled: Bool) -> [DerivedDayValue] {
        let derivedByDay = Dictionary(derived.map { ($0.day, $0) }, uniquingKeysWith: { _, last in last })
        let days = Set(derivedByDay.keys).union(native.keys).sorted()
        var out: [DerivedDayValue] = []
        for day in days {
            let row = derivedByDay[day]
            let result = DerivedPriority.priority(enabled: enabled, native: native[day], derived: row?.value)
            guard let value = result.value else { continue }
            if result.sourceKind == "native" {
                out.append(DerivedDayValue(day: day, value: value, isNative: true, source: result.source))
            } else {
                out.append(DerivedDayValue(day: day, value: value, value2: row?.value2, value3: row?.value3,
                                           quality: row?.quality, isNative: false, source: nil))
            }
        }
        return out
    }

    /// Native-wins days of `info` from `fromDay` through `toDay`.
    static func days(
        reader: HealthDatabase, info: DerivedMetricInfo, fromDay: String, toDay: String,
        enabled: Bool, ownBundleID: String = Bundle.main.bundleIdentifier ?? "com.ayuvo.health"
    ) async -> [DerivedDayValue] {
        let derived = enabled ? ((try? await reader.derivedValues(metric: info.id, fromDay: fromDay, toDay: toDay)) ?? []) : []
        var native: [String: DerivedPriority.Native] = [:]
        if let typeID = info.nativeTypeID {
            let rows = (try? await reader.rowsForDays(type: typeID, fromDay: fromDay, toDay: toDay)) ?? []
            if !rows.isEmpty {
                let names = Dictionary(((try? await reader.allSources()) ?? []).map { ($0.id, $0.name) }, uniquingKeysWith: { first, _ in first })
                native = nativeDays(rows: rows, sourceNames: names, ownBundleID: ownBundleID)
            }
        }
        return merge(derived: derived, native: native, enabled: enabled)
    }

    // MARK: Series

    /// Daily values → the shared buckets of `range` (W / M daily, 6M weekly, Y monthly) aggregated by the
    /// metric's `aggregation`.
    static func build(days: [DerivedDayValue], info: DerivedMetricInfo, range: HealthDetailRange, anchor: Date,
                      calendar: Calendar, weekStart: MetricsReference.WeekStart) -> HealthChartSeries {
        let zone = MetricsReference.Zone(calendar: calendar)
        let entries = days.compactMap { d -> MetricsReference.Entry? in
            guard let local = MetricsReference.LocalDay.parse(d.day) else { return nil }
            return MetricsReference.Entry(tMs: zone.midnight(local), value: d.value)
        }
        var series = AppMetricSeriesProvider.build(entries: entries, aggregation: aggregation(info), range: range, anchor: anchor,
                                                   calendar: calendar, weekStart: weekStart)
        if info.aggregation == "max" {
            series.points = series.points.map { point in
                var p = point
                if p.value != nil { p.value = p.max ?? p.value }
                return p
            }
        }
        return series
    }

    /// The days inside a chart bucket.
    static func days(in point: HealthChartPoint, from days: [DerivedDayValue], calendar: Calendar) -> [DerivedDayValue] {
        let zone = MetricsReference.Zone(calendar: calendar)
        let start = Int64((point.start.timeIntervalSince1970 * 1000).rounded())
        let end = Int64((point.end.timeIntervalSince1970 * 1000).rounded())
        return days.filter { d in
            guard let local = MetricsReference.LocalDay.parse(d.day) else { return false }
            let ms = zone.midnight(local)
            return ms >= start && ms < end
        }
    }
}

/// Display text of derived values (follows the weight-unit preference; clock metrics as local times).
enum DerivedMetricFormat {
    /// Most fraction digits a derived value shows.
    static let displayDecimals = 2

    static func usesPounds(_ info: DerivedMetricInfo, defaults: UserDefaults = .standard) -> Bool {
        guard info.unit == "kg" || info.unit == "kg/wk" else { return false }
        return (WeightUnit(rawValue: defaults.string(forKey: WeightUnit.storageKey) ?? "") ?? .lbs) == .lbs
    }

    /// Canonical value → chart axis value (pounds when the user weighs in pounds).
    static func chartValue(_ value: Double?, info: DerivedMetricInfo) -> Double? {
        guard let value else { return nil }
        return usesPounds(info) ? value * 2.20462 : value
    }

    static func unitLabel(_ info: DerivedMetricInfo) -> String {
        if info.isClock { return "" }
        if usesPounds(info) { return info.unit == "kg" ? "lbs" : "lbs/wk" }
        return info.unit
    }

    /// Clock value (minutes after 12:00 of the previous day) → local clock text, e.g. 660 → "23:00".
    static func clockText(_ minutes: Double) -> String {
        var total = Int((720 + minutes).rounded()) % 1440
        if total < 0 { total += 1440 }
        let calendar = Calendar(identifier: .gregorian)
        let date = calendar.date(from: DateComponents(year: 2001, month: 1, day: 1, hour: total / 60, minute: total % 60)) ?? Date()
        return date.formatted(.dateTime.hour().minute())
    }

    static func display(_ value: Double?, info: DerivedMetricInfo) -> (value: String, unit: String) {
        guard let value else { return ("—", unitLabel(info)) }
        if info.isClock { return (clockText(value), "") }
        let shown = chartValue(value, info: info) ?? value
        // Up to two decimals (trailing zeros dropped), so small values such as a 0.39 % sound dose never read as 0.
        return (HealthUnitFormatting.number(shown, fractionDigits: max(info.decimals, displayDecimals)), unitLabel(info))
    }

    static func text(_ value: Double?, info: DerivedMetricInfo) -> String {
        let d = display(value, info: info)
        return d.unit.isEmpty || d.value == "—" ? d.value : "\(d.value) \(d.unit)"
    }

    /// Confidence label of an Ayuvo estimate.
    static func confidenceText(_ quality: Double?) -> String {
        guard let quality else { return String(localized: "Not rated") }
        if quality >= 0.8 { return String(localized: "Normal") }
        if quality >= 0.5 { return String(localized: "Medium") }
        return String(localized: "Low")
    }

    /// "Estimated by Ayuvo", the Apple Health source name(s), or both for a mixed bucket.
    static func sourceText(_ days: [DerivedDayValue]) -> String? {
        guard !days.isEmpty else { return nil }
        let estimated = String(localized: "Estimated by Ayuvo")
        var names: [String] = []
        for d in days where d.isNative {
            let name = d.source ?? String(localized: "Apple Health")
            if !names.contains(name) { names.append(name) }
        }
        let hasDerived = days.contains { !$0.isNative }
        if names.isEmpty { return estimated }
        let native = String(localized: "From \(names.joined(separator: ", "))")
        return hasDerived ? "\(native) · \(estimated)" : native
    }
}

// MARK: - Store

/// Latest value and 7-day sparkline of a derived metric (Summary tiles).
struct DerivedTileSnapshot: Equatable {
    var latest: DerivedDayValue?
    var sparkline: [Double]
}

/// Which derived metrics are available (switched on, with at least one stored value) and the tiles of pinned
/// ones. Reloaded when `DerivedMetricsService.revision` or the pins change.
@MainActor
@Observable
final class DerivedMetricStore {
    static let shared = DerivedMetricStore()

    /// Enabled metric ids with values, in catalog order.
    private(set) var available: [String] = []
    private(set) var tiles: [String: DerivedTileSnapshot] = [:]
    private var loadedKey: String?

    var availableInfos: [DerivedMetricInfo] { available.compactMap { DerivedCatalog.shared.byID[$0] } }

    func infos(inCategories categories: [String]) -> [DerivedMetricInfo] {
        availableInfos.filter { categories.contains($0.category) }
    }

    /// The Health mirror reader, without creating a database for someone who never turned Health sync on.
    static func database(defaults: UserDefaults = .standard) async -> HealthDatabase? {
        let runtime = HealthDataRuntime.shared
        guard runtime.isOpen || defaults.bool(forKey: "healthKitEnabled") else { return nil }
        guard await runtime.openIfNeeded() else { return nil }
        return runtime.reader ?? runtime.writer
    }

    func refresh(pinned: [String], calendar: Calendar = .current, defaults: UserDefaults = .standard) async {
        let derivedPins = pinned.filter { $0.hasPrefix(MetricKey.derivedPrefix) }
        let enabled = DerivedSettings.enabledIDs(defaults: defaults)
        let key = "\(DerivedMetricsService.shared.revision)|\(derivedPins.joined(separator: ","))|\(enabled.sorted().joined(separator: ","))|\(InsightsDay.key(for: Date(), calendar: calendar))"
        guard key != loadedKey else { return }
        guard let db = await Self.database(defaults: defaults) else {
            available = []
            tiles = [:]
            loadedKey = key
            return
        }
        let withValues = Set((try? await db.derivedMetricIDsWithValues()) ?? [])
        available = DerivedCatalog.shared.metrics.map(\.id).filter { enabled.contains($0) && withValues.contains($0) }
        var next: [String: DerivedTileSnapshot] = [:]
        let today = InsightsDay.key(for: Date(), calendar: calendar)
        let from = InsightsDay.add(today, -29)
        for pin in derivedPins {
            let id = String(pin.dropFirst(MetricKey.derivedPrefix.count))
            guard let info = DerivedCatalog.shared.byID[id] else { continue }
            let on = enabled.contains(id)
            var days = await DerivedMetricSeries.days(reader: db, info: info, fromDay: from, toDay: today, enabled: on)
            if days.isEmpty, on, let row = (try? await db.latestDerivedValue(metric: id)) ?? nil, let value = row.value {
                days = [DerivedDayValue(day: row.day, value: value, value2: row.value2, value3: row.value3,
                                        quality: row.quality, isNative: false, source: nil)]
            }
            let weekAgo = InsightsDay.add(today, -6)
            let spark = days.filter { $0.day >= weekAgo }.map { DerivedMetricFormat.chartValue($0.value, info: info) ?? $0.value }
            next[pin] = DerivedTileSnapshot(latest: days.last, sparkline: spark)
        }
        tiles = next
        loadedKey = key
    }
}
