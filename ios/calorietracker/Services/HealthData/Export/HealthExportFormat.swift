import Foundation

/// `ayuvo-health-data` v1 (`docs/health-data-export.md`). Entry order lets a single-pass
/// reader preview before touching samples; `checksums.json` is last.
nonisolated enum HealthExportFormat {
    static let format = "ayuvo-health-data"
    static let formatVersion = 1
    static let percentConvention = "0-100"

    static let manifestEntry = "manifest.json"
    static let samplesEntry = "samples.ndjson"
    static let seriesEntry = "series.ndjson"
    static let sourcesEntry = "sources.json"
    static let rollupsEntry = "rollups.ndjson"
    static let checksumsEntry = "checksums.json"

    static let maxArchiveBytes = 512 * 1024 * 1024
    static let maxLineBytes = 64 * 1024
    /// Intake is covered by the food-diary export; hydration is still exported.
    static let excludedTypePrefixes = ["dietary_"]
    static let excludedTypes: Set<String> = ["nutrition"]

    static func isExportable(typeID: String) -> Bool {
        !excludedTypes.contains(typeID) && !excludedTypePrefixes.contains { typeID.hasPrefix($0) }
    }

    struct DateRange: Codable, Equatable, Sendable {
        var start: String?
        var end: String?
    }

    struct TypeEntry: Codable, Equatable, Sendable {
        var type: String
        var category: String
        var kind: String
        var aggregation: String
        var unit: String
        var display_name: String?
        var native_id: String?
        var record_count: Int
        var series_count: Int
    }

    struct Manifest: Codable, Equatable, Sendable {
        var format: String
        var format_version: Int
        var platform: String
        var app_version: String
        var exported_at: String
        var zone_id: String
        var date_range: DateRange
        var registry_version: Int
        var percent_convention: String
        var types: [TypeEntry]
    }

    struct SourceEntry: Codable, Equatable, Sendable {
        var id: String
        var name: String
        var device_model: String?
        var device_type: Int?
        var last_seen: String?
    }

    /// One `samples.ndjson` line from a stored row (`local_day` is not exported; importers recompute it).
    static func sampleLine(_ row: HealthSampleRow, sourceName: String?, deviceZoneOffsetS: Int) -> [String: Any] {
        let startOffset = row.startOffsetS ?? deviceZoneOffsetS
        let endOffset = row.endOffsetS ?? startOffset
        var extra: Any = NSNull()
        if let extraJSON = row.extraJSON, let data = extraJSON.data(using: .utf8),
           let object = try? JSONSerialization.jsonObject(with: data) {
            extra = object
        }
        return [
            "id": row.id,
            "type_id": row.typeID,
            "start": ISO8601Fast.format(ms: row.startMs, offsetS: startOffset),
            "end": ISO8601Fast.format(ms: row.endMs, offsetS: endOffset),
            "updated": ISO8601Fast.format(ms: row.updatedMs, offsetS: 0),
            "value": row.value as Any? ?? NSNull(),
            "value2": row.value2 as Any? ?? NSNull(),
            "value3": row.value3 as Any? ?? NSNull(),
            "value_text": row.valueText as Any? ?? NSNull(),
            "unit": row.unit,
            "category_value": row.categoryValue as Any? ?? NSNull(),
            "title": row.title as Any? ?? NSNull(),
            "extra": extra,
            "count": row.count,
            "source_id": row.sourceID,
            "source_name": sourceName as Any? ?? NSNull(),
            "device": row.device as Any? ?? NSNull(),
            "device_type": row.deviceType as Any? ?? NSNull(),
            "recording_method": row.recordingMethod as Any? ?? NSNull(),
            "client_record_id": row.clientRecordID as Any? ?? NSNull(),
            "origin": row.origin,
        ]
    }

    /// Parses one line back into a row. `type` supplies day attribution and the canonical
    /// unit check; returns nil for structurally invalid lines.
    static func row(fromLine object: [String: Any], type: HealthMetricType, calendar: Calendar) -> HealthSampleRow? {
        guard let id = object["id"] as? String, !id.isEmpty,
              let typeID = object["type_id"] as? String, !typeID.isEmpty,
              let startText = object["start"] as? String, let start = ISO8601Fast.parse(startText),
              let endText = object["end"] as? String, let end = ISO8601Fast.parse(endText),
              let unit = object["unit"] as? String
        else { return nil }
        let updated = (object["updated"] as? String).flatMap(ISO8601Fast.parse)?.ms ?? end.ms
        var extraJSON: String?
        if let extra = object["extra"] as? [String: Any], !extra.isEmpty {
            extraJSON = HealthSampleMapper.json(extra)
        }
        return HealthSampleRow(
            id: id,
            typeID: typeID,
            startMs: start.ms,
            endMs: max(end.ms, start.ms),
            startOffsetS: start.offsetS,
            endOffsetS: end.offsetS,
            localDay: HealthRollupMath.localDay(
                startMs: start.ms, endMs: max(end.ms, start.ms), startOffsetS: start.offsetS, endOffsetS: end.offsetS,
                attribution: type.dayAttribution, calendar: calendar
            ),
            value: number(object["value"]),
            value2: number(object["value2"]),
            value3: number(object["value3"]),
            valueText: object["value_text"] as? String,
            unit: unit,
            categoryValue: number(object["category_value"]).map { Int($0) },
            title: object["title"] as? String,
            extraJSON: extraJSON,
            count: max(1, number(object["count"]).map { Int($0) } ?? 1),
            sourceID: (object["source_id"] as? String) ?? "unknown",
            device: object["device"] as? String,
            deviceType: number(object["device_type"]).map { Int($0) },
            recordingMethod: number(object["recording_method"]).map { Int($0) },
            clientRecordID: object["client_record_id"] as? String,
            origin: HealthRowOrigin.fileImport.rawValue,
            deleted: 0,
            updatedMs: updated
        )
    }

    static func number(_ value: Any?) -> Double? {
        switch value {
        case let n as NSNumber: return n.doubleValue
        case let d as Double: return d
        case let i as Int: return Double(i)
        case let s as String: return Double(s)
        default: return nil
        }
    }
}
