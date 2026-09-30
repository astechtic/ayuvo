import Foundation

// MARK: - Payload rows (canonical units; shapes locked in docs/health-data.md §1.5)

nonisolated struct HealthCoachDataType: Sendable, Equatable {
    var dataType: String
    var category: String
    var displayName: String
    var unit: String
    var aggregation: String
    var count: Int
    var first: String?
    var last: String?
    var latestAt: String?
    var latestValue: Double?
    var latestValueText: String?
    var historyLimitedBefore: String?
    /// A metric Ayuvo derives on the device (`derived:<id>`, docs/derived-metrics.md); adds `derived`, `title`, `method`.
    var isDerived = false
    var method: String?

    var jsonObject: [String: Any] {
        var latest: [String: Any] = [
            "at": latestAt as Any? ?? NSNull(),
            "value": latestValue as Any? ?? NSNull(),
            "value_text": latestValueText as Any? ?? NSNull(),
        ]
        if latestAt == nil, latestValue == nil, latestValueText == nil {
            latest = [:]
        }
        var object: [String: Any] = [
            "data_type": dataType,
            "category": category,
            "display_name": displayName,
            "unit": unit,
            "aggregation": aggregation,
            "count": count,
            "first": first as Any? ?? NSNull(),
            "last": last as Any? ?? NSNull(),
            "latest": latest.isEmpty ? NSNull() : latest,
        ]
        if let historyLimitedBefore {
            object["history_limited_before"] = historyLimitedBefore
        }
        if isDerived {
            object["derived"] = true
            object["title"] = displayName
            object["method"] = method as Any? ?? NSNull()
        }
        return object
    }
}

nonisolated struct HealthCoachDay: Sendable, Equatable {
    var date: String
    var sum: Double?
    var avg: Double?
    var min: Double?
    var max: Double?
    var count: Int
    var durationS: Double?
    var v2Avg: Double?
    var v2Min: Double?
    var v2Max: Double?
    var ownSum: Double?
    /// Derived metrics: "native" (an Apple Health value won) or "derived" (Ayuvo's estimate), and the native source.
    var sourceKind: String? = nil
    var source: String? = nil
    /// Steps / active energy: `sum` is the de-duplicated HealthKit statistics total, not the mirror's row sum.
    var deduplicated = false

    var jsonObject: [String: Any] {
        var object: [String: Any] = [
            "date": date,
            "sum": sum as Any? ?? NSNull(),
            "avg": avg as Any? ?? NSNull(),
            "min": min as Any? ?? NSNull(),
            "max": max as Any? ?? NSNull(),
            "count": count,
        ]
        if let durationS { object["duration_s"] = durationS }
        if let v2Avg { object["v2_avg"] = v2Avg }
        if let v2Min { object["v2_min"] = v2Min }
        if let v2Max { object["v2_max"] = v2Max }
        if let ownSum { object["own_sum"] = ownSum }
        if let sourceKind { object["source_kind"] = sourceKind }
        if let source { object["source"] = source }
        if deduplicated { object["deduplicated"] = true }
        return object
    }
}

nonisolated struct HealthCoachSummary: Sendable, Equatable {
    var dataType: String
    var unit: String
    var from: String
    var to: String
    var total: Double?
    var average: Double?
    var min: Double?
    var max: Double?
    var latest: Double?
    var days: [HealthCoachDay]

    var jsonObject: [String: Any] {
        [
            "data_type": dataType,
            "unit": unit,
            "from": from,
            "to": to,
            "highlights": [
                "total": total as Any? ?? NSNull(),
                "average": average as Any? ?? NSNull(),
                "min": min as Any? ?? NSNull(),
                "max": max as Any? ?? NSNull(),
                "latest": latest as Any? ?? NSNull(),
            ] as [String: Any],
            "days": days.map(\.jsonObject),
        ]
    }
}

nonisolated struct HealthCoachSample: Sendable, Equatable {
    var start: String
    var end: String
    var value: Double?
    var value2: Double?
    var value3: Double?
    var valueText: String?
    var categoryValue: Int?
    var title: String?
    var extraJSON: String?
    var source: String
    var device: String?

    var jsonObject: [String: Any] {
        var extra: Any = NSNull()
        if let extraJSON, let data = extraJSON.data(using: .utf8),
           let object = try? JSONSerialization.jsonObject(with: data) {
            extra = object
        }
        return [
            "start": start,
            "end": end,
            "value": value as Any? ?? NSNull(),
            "value2": value2 as Any? ?? NSNull(),
            "value3": value3 as Any? ?? NSNull(),
            "value_text": valueText as Any? ?? NSNull(),
            "category_value": categoryValue as Any? ?? NSNull(),
            "title": title as Any? ?? NSNull(),
            "extra": extra,
            "source": source,
            "device": device as Any? ?? NSNull(),
        ]
    }
}

nonisolated struct HealthCoachSampleSet: Sendable, Equatable {
    var dataType: String
    var unit: String
    var records: [HealthCoachSample]

    var jsonObject: [String: Any] {
        ["data_type": dataType, "unit": unit, "count": records.count, "records": records.map(\.jsonObject)]
    }
}

nonisolated struct HealthCoachNight: Sendable, Equatable {
    var nightOf: String
    var start: String
    var end: String
    var inBedS: Double
    var asleepS: Double
    var lightS: Double
    var deepS: Double
    var remS: Double
    var awakeS: Double
    var source: String

    var jsonObject: [String: Any] {
        [
            "night_of": nightOf, "start": start, "end": end,
            "in_bed_s": inBedS.rounded(), "asleep_s": asleepS.rounded(), "light_s": lightS.rounded(),
            "deep_s": deepS.rounded(), "rem_s": remS.rounded(), "awake_s": awakeS.rounded(), "source": source,
        ]
    }
}

// MARK: - Query surface handed to CoachTools

/// Async closures over the read-only database so `CoachTools` (a value type built per
/// message) can answer the four health tools without owning the actor.
nonisolated struct CoachHealthQuery: Sendable {
    var dataTypes: @Sendable () async -> [HealthCoachDataType]
    var summary: @Sendable (_ dataType: String, _ from: String, _ to: String, _ limit: Int) async -> HealthCoachSummary?
    var samples: @Sendable (_ dataType: String, _ from: String, _ to: String, _ limit: Int) async -> HealthCoachSampleSet?
    var sleep: @Sendable (_ from: String, _ to: String, _ limit: Int) async -> [HealthCoachNight]

    static func live(
        reader: HealthDatabase,
        calendar: Calendar,
        typeMeta: [String: HealthTypeMetaRow]
    ) -> CoachHealthQuery {
        CoachHealthQuery(
            dataTypes: {
                let types = await HealthCoachQueryBuilder.dataTypes(reader: reader, calendar: calendar, typeMeta: typeMeta)
                return types + (await HealthCoachQueryBuilder.derivedDataTypes(reader: reader, calendar: calendar))
            },
            summary: { dataType, from, to, limit in
                if dataType.hasPrefix(MetricKey.derivedPrefix) {
                    return await HealthCoachQueryBuilder.derivedSummary(reader: reader, dataType: dataType, from: from, to: to, limit: limit)
                }
                return await HealthCoachQueryBuilder.summary(
                    reader: reader, dataType: dataType, from: from, to: to, limit: limit, calendar: calendar, typeMeta: typeMeta,
                    dedupedTotals: { typeID, fromDay, toDay in
                        await HealthCoachQueryBuilder.healthKitTotals(typeID: typeID, from: fromDay, to: toDay, calendar: calendar)
                    }
                )
            },
            samples: { dataType, from, to, limit in
                await HealthCoachQueryBuilder.samples(reader: reader, dataType: dataType, from: from, to: to, limit: limit, calendar: calendar, typeMeta: typeMeta)
            },
            sleep: { from, to, limit in
                await HealthCoachQueryBuilder.sleep(reader: reader, from: from, to: to, limit: limit, calendar: calendar)
            }
        )
    }

    /// Fixed data for tests.
    static func fixed(
        dataTypes: [HealthCoachDataType] = [],
        summaries: [String: HealthCoachSummary] = [:],
        samples: [String: HealthCoachSampleSet] = [:],
        nights: [HealthCoachNight] = []
    ) -> CoachHealthQuery {
        CoachHealthQuery(
            dataTypes: { dataTypes },
            summary: { dataType, _, _, limit in summaries[dataType].map { var s = $0; s.days = Array(s.days.suffix(limit)); return s } },
            samples: { dataType, _, _, limit in samples[dataType].map { var s = $0; s.records = Array(s.records.suffix(limit)); return s } },
            sleep: { _, _, limit in Array(nights.suffix(limit)) }
        )
    }
}

/// Prompt-level facts about the synced mirror.
nonisolated struct HealthCoachContext: Sendable, Equatable {
    var enabled: Bool
    var typeCount: Int
    var lastSync: Date?
    /// ≤12 lines for on-device / LiteRT modes that cannot call tools.
    var sevenDayLines: [String]
}

/// The single optional parameter threaded through `ChatService`.
nonisolated struct CoachHealthContext: Sendable {
    var query: CoachHealthQuery
    var context: HealthCoachContext
}

// MARK: - Live implementation

nonisolated enum HealthCoachQueryBuilder {
    static func isoDay(_ date: Date, calendar: Calendar) -> String {
        HealthRollupMath.dayKey(ms: HealthSampleMapper.ms(date), offsetS: nil, calendar: calendar)
    }

    static func isoTimestamp(ms: Int64, offsetS: Int?, calendar: Calendar) -> String {
        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = [.withInternetDateTime]
        formatter.timeZone = offsetS.flatMap { TimeZone(secondsFromGMT: $0) } ?? calendar.timeZone
        return formatter.string(from: Date(timeIntervalSince1970: Double(ms) / 1000))
    }

    static func isValidDay(_ day: String) -> Bool {
        let parts = day.split(separator: "-")
        return parts.count == 3 && parts.allSatisfy { !$0.isEmpty && $0.allSatisfy(\.isNumber) }
    }

    static func resolve(_ dataType: String, typeMeta: [String: HealthTypeMetaRow]) -> HealthMetricType {
        HealthMetricRegistry.resolve(typeID: dataType, metaRows: typeMeta)
    }

    static func dataTypes(reader: HealthDatabase, calendar: Calendar, typeMeta: [String: HealthTypeMetaRow]) async -> [HealthCoachDataType] {
        guard let summaries = try? await reader.typeSummaries() else { return [] }
        let states = (try? await reader.allSyncStates()) ?? []
        let limits = Dictionary(uniqueKeysWithValues: states.compactMap { state -> (String, Int64)? in
            state.earliestAuthorizedMs.map { (state.typeID, $0) }
        })
        return summaries.filter { $0.count > 0 }.map { summary in
            let type = resolve(summary.typeID, typeMeta: typeMeta)
            let latest = summary.latest
            return HealthCoachDataType(
                dataType: type.id,
                category: type.category.rawValue,
                displayName: type.englishName,
                unit: type.unit,
                aggregation: type.aggregation.rawValue,
                count: summary.count,
                first: summary.firstStartMs.map { isoTimestamp(ms: $0, offsetS: nil, calendar: calendar) },
                last: summary.lastEndMs.map { isoTimestamp(ms: $0, offsetS: nil, calendar: calendar) },
                latestAt: latest.map { isoTimestamp(ms: $0.endMs, offsetS: $0.endOffsetS, calendar: calendar) },
                latestValue: type.isBloodPressure ? latest?.value : latest?.value,
                latestValueText: type.isBloodPressure
                    ? latest.flatMap { row in row.value.map { "\(Int($0.rounded()))/\(Int((row.value2 ?? 0).rounded()))" } }
                    : latest?.valueText,
                historyLimitedBefore: limits[type.id].map { isoTimestamp(ms: $0, offsetS: nil, calendar: calendar) }
            )
        }
    }

    /// Types whose Coach daily sums come from de-duplicated HealthKit statistics (the mirror holds every source's
    /// rows, so summing it counts iPhone and watch steps twice).
    static let dedupedTypes: Set<String> = ["steps", "active_energy"]

    /// De-duplicated daily totals through `InsightsHealthKitTotals` (nil when Health sync is off or unavailable).
    static func healthKitTotals(typeID: String, from: String, to: String, calendar: Calendar) async -> [String: Double]? {
        guard let start = InsightsDay.date(from, calendar: calendar), let end = InsightsDay.date(to, calendar: calendar) else { return nil }
        return await InsightsHealthKitTotals.daily(typeID: typeID, from: start, to: end, calendar: calendar, defaults: .standard)
    }

    static func summary(
        reader: HealthDatabase, dataType: String, from: String, to: String, limit: Int,
        calendar: Calendar, typeMeta: [String: HealthTypeMetaRow],
        dedupedTotals: (@Sendable (_ typeID: String, _ from: String, _ to: String) async -> [String: Double]?)? = nil
    ) async -> HealthCoachSummary? {
        guard isValidDay(from), isValidDay(to), from <= to else { return nil }
        let type = resolve(dataType, typeMeta: typeMeta)
        guard var rollups = try? await reader.dailyRollups(type: type.id, fromDay: from, toDay: to) else { return nil }
        var deduped: [String: Double]?
        if dedupedTypes.contains(type.id), let dedupedTotals, let totals = await dedupedTotals(type.id, from, to) {
            deduped = totals
            let known = Set(rollups.map(\.day))
            for (day, total) in totals where !known.contains(day) && day >= from && day <= to {
                rollups.append(HealthDailyRollupRow(typeID: type.id, day: day, tz: calendar.timeZone.identifier, sum: total))
            }
            rollups = rollups.map { rollup in
                guard let total = totals[rollup.day] else { return rollup }
                var r = rollup
                r.sum = total
                r.ownSum = nil
                return r
            }
            .sorted { $0.day < $1.day }
        }
        let recent = Array(rollups.suffix(max(1, limit)))
        let days = recent.map { rollup in
            HealthCoachDay(
                date: rollup.day,
                sum: rollup.sum,
                avg: rollup.avg,
                min: rollup.min,
                max: rollup.max,
                count: rollup.count,
                durationS: rollup.durationS,
                v2Avg: rollup.v2Avg,
                v2Min: rollup.v2Min,
                v2Max: rollup.v2Max,
                ownSum: rollup.ownSum,
                deduplicated: deduped?[rollup.day] != nil
            )
        }
        var summary = HealthCoachSummary(dataType: type.id, unit: type.unit, from: from, to: to, total: nil, average: nil, min: nil, max: nil, latest: nil, days: days)
        switch type.kind {
        case .cumulative, .duration, .session:
            let sums = recent.compactMap(\.sum)
            summary.total = sums.isEmpty ? nil : sums.reduce(0, +)
            summary.average = sums.isEmpty ? nil : sums.reduce(0, +) / Double(sums.count)
            summary.min = sums.min()
            summary.max = sums.max()
            summary.latest = recent.last?.sum
        case .discrete, .series:
            var weighted = 0.0, weight = 0
            for rollup in recent {
                if let avg = rollup.avg {
                    weighted += avg * Double(max(1, rollup.count))
                    weight += max(1, rollup.count)
                }
            }
            summary.average = weight > 0 ? weighted / Double(weight) : nil
            summary.min = recent.compactMap(\.min).min()
            summary.max = recent.compactMap(\.max).max()
            summary.latest = recent.last?.lastValue
        case .category:
            let counts = recent.map { Double($0.count) }
            summary.total = counts.reduce(0, +)
            summary.average = counts.isEmpty ? nil : counts.reduce(0, +) / Double(counts.count)
            summary.latest = recent.last?.lastValue
        }
        return summary
    }

    // MARK: Derived metrics (docs/derived-metrics.md)

    /// Switched-on derived metrics with at least one stored value, as `derived:<id>` data types.
    static func derivedDataTypes(reader: HealthDatabase, calendar: Calendar, defaults: UserDefaults = .standard) async -> [HealthCoachDataType] {
        let enabled = DerivedSettings.enabledIDs(defaults: defaults)
        guard !enabled.isEmpty, let withValues = try? await reader.derivedMetricIDsWithValues() else { return [] }
        let ids = Set(withValues)
        var out: [HealthCoachDataType] = []
        for info in DerivedCatalog.shared.metrics where enabled.contains(info.id) && ids.contains(info.id) {
            let latest = (try? await reader.latestDerivedValue(metric: info.id)) ?? nil
            out.append(HealthCoachDataType(
                dataType: MetricKey.derivedPrefix + info.id,
                category: info.category,
                displayName: info.title,
                unit: info.unit,
                aggregation: info.aggregation,
                count: 0,
                first: nil,
                last: latest.map { $0.day },
                latestAt: latest.map { $0.day },
                latestValue: latest?.value,
                latestValueText: nil,
                historyLimitedBefore: nil,
                isDerived: true,
                method: info.method
            ))
        }
        return out
    }

    /// A derived metric's native-wins daily series: Apple Health's value when it has one, else Ayuvo's estimate.
    static func derivedSummary(reader: HealthDatabase, dataType: String, from: String, to: String, limit: Int,
                               defaults: UserDefaults = .standard) async -> HealthCoachSummary? {
        guard isValidDay(from), isValidDay(to), from <= to else { return nil }
        let id = String(dataType.dropFirst(MetricKey.derivedPrefix.count))
        guard let info = DerivedCatalog.shared.byID[id] else { return nil }
        let all = await DerivedMetricSeries.days(reader: reader, info: info, fromDay: from, toDay: to,
                                                 enabled: DerivedSettings.isEnabled(id, defaults: defaults))
        let recent = Array(all.suffix(max(1, limit)))
        let days = recent.map { d in
            HealthCoachDay(date: d.day, sum: info.aggregation == "sum" ? d.value : nil, avg: d.value, min: d.value, max: d.value,
                           count: 1, durationS: nil, v2Avg: nil, v2Min: nil, v2Max: nil, ownSum: nil,
                           sourceKind: d.isNative ? "native" : "derived", source: d.isNative ? d.source : "Estimated by Ayuvo")
        }
        let values = recent.map(\.value)
        var summary = HealthCoachSummary(dataType: dataType, unit: info.unit, from: from, to: to, total: nil,
                                         average: values.isEmpty ? nil : values.reduce(0, +) / Double(values.count),
                                         min: values.min(), max: values.max(), latest: values.last, days: days)
        if info.aggregation == "sum" { summary.total = values.isEmpty ? nil : values.reduce(0, +) }
        return summary
    }

    static func samples(
        reader: HealthDatabase, dataType: String, from: String, to: String, limit: Int,
        calendar: Calendar, typeMeta: [String: HealthTypeMetaRow]
    ) async -> HealthCoachSampleSet? {
        guard isValidDay(from), isValidDay(to), from <= to else { return nil }
        let type = resolve(dataType, typeMeta: typeMeta)
        guard let rows = try? await reader.rowsForDays(type: type.id, fromDay: from, toDay: to) else { return nil }
        let recent = Array(rows.suffix(max(1, limit)))
        let sources = Dictionary(uniqueKeysWithValues: ((try? await reader.allSources()) ?? []).map { ($0.id, $0.name) })
        let records = recent.map { row in
            HealthCoachSample(
                start: isoTimestamp(ms: row.startMs, offsetS: row.startOffsetS, calendar: calendar),
                end: isoTimestamp(ms: row.endMs, offsetS: row.endOffsetS, calendar: calendar),
                value: row.value,
                value2: row.value2,
                value3: row.value3,
                valueText: row.valueText,
                categoryValue: row.categoryValue,
                title: row.title,
                extraJSON: row.extraJSON,
                source: sources[row.sourceID] ?? row.sourceID,
                device: row.device
            )
        }
        return HealthCoachSampleSet(dataType: type.id, unit: type.unit, records: records)
    }

    static func sleep(reader: HealthDatabase, from: String, to: String, limit: Int, calendar: Calendar) async -> [HealthCoachNight] {
        guard isValidDay(from), isValidDay(to), from <= to,
              let rows = try? await reader.rowsForDays(type: "sleep", fromDay: from, toDay: to)
        else { return [] }
        let sources = Dictionary(uniqueKeysWithValues: ((try? await reader.allSources()) ?? []).map { ($0.id, $0.name) })
        let nights = HealthSleepAnalysis.nights(rows: rows, calendar: calendar).suffix(max(1, limit))
        return nights.map { night in
            HealthCoachNight(
                nightOf: night.nightOf,
                start: isoTimestamp(ms: night.startMs, offsetS: nil, calendar: calendar),
                end: isoTimestamp(ms: night.endMs, offsetS: nil, calendar: calendar),
                inBedS: night.inBedS,
                asleepS: night.asleepS,
                lightS: night.lightS,
                deepS: night.deepS,
                remS: night.remS,
                awakeS: night.awakeS,
                source: sources[night.source] ?? night.source
            )
        }
    }
}
