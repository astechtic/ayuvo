import Foundation

// Swift port of the Phase 5 §35 section of `scripts/records_reference.py`: the `ayuvo-records`
// archive payload (rows, entry order, manifest), the reader's validation and the Merge / Replace
// planning. Vectors: `archive.json`. The zip mechanics live in `RecordsArchive.swift`.
extension RR {
    // MARK: - Constants

    static let archiveFormat = "ayuvo-records"
    static let archiveFormatVersion = 1
    static let archiveSchemaVersion = 4
    static let archiveApp = "Ayuvo"
    static let archiveLineCap = 256 * 1024

    static let archiveRecordColumns = [
        "id", "parent_id", "page_start", "page_end", "title", "record_type", "category", "source",
        "import_method", "source_app", "original_filename", "created_ms", "updated_ms", "document_date",
        "document_date_precision", "document_date_method", "sort_date", "mime_type", "file_type",
        "file_size", "page_count", "file_path", "thumbnail_path", "checksum_sha256", "processing_status",
        "processing_error", "review_status", "favorite", "archived", "notes", "phash", "text_signature",
        "ai_mode_used", "ai_provider", "type_confidence", "type_method", "shared_count", "last_shared_ms",
    ]
    static let archivePageColumns = ["record_id", "page_index", "text", "text_source", "ocr_confidence", "width", "height", "blocks_json"]
    static let archiveFieldColumns = ["id", "record_id", "field_key", "value_text", "value_json", "method", "confidence",
                                      "state", "source_page", "source_bbox", "evidence", "created_ms", "updated_ms"]
    static var archiveObservationColumns: [String] { observationKeys }
    static let archiveHighlightColumns = ["id", "record_id", "section", "text", "method", "provider", "field_id",
                                          "source_page", "confidence", "dismissed", "position", "created_ms"]
    static let archiveLinkColumns = ["a_id", "b_id", "kind", "origin", "status", "score", "reasons_json", "created_ms", "updated_ms"]
    static let archiveEntityColumns = ["id", "kind", "display_name", "normalized_name", "specialty", "created_ms", "updated_ms"]
    static let archiveRecordEntityColumns = ["record_id", "entity_id", "role"]
    static let archiveTagColumns = ["id", "name", "record_ids"]
    static let archiveAliasColumns = ["normalized_name", "analyte_id", "created_ms"]

    static let archiveDataEntries = ["records.ndjson", "pages.ndjson", "fields.ndjson", "observations.ndjson",
                                     "highlights.ndjson", "links.ndjson", "entities.ndjson", "record_entities.ndjson",
                                     "tags.json", "analyte_user_aliases.json"]

    static let archiveEntryTable: [String: String] = [
        "records.ndjson": "records", "pages.ndjson": "pages", "fields.ndjson": "fields",
        "observations.ndjson": "observations", "highlights.ndjson": "highlights", "links.ndjson": "links",
        "entities.ndjson": "entities", "record_entities.ndjson": "record_entities",
        "tags.json": "tags", "analyte_user_aliases.json": "analyte_user_aliases",
    ]

    static var archiveEntryColumns: [String: [String]] {
        [
            "records.ndjson": archiveRecordColumns, "pages.ndjson": archivePageColumns,
            "fields.ndjson": archiveFieldColumns, "observations.ndjson": archiveObservationColumns,
            "highlights.ndjson": archiveHighlightColumns, "links.ndjson": archiveLinkColumns,
            "entities.ndjson": archiveEntityColumns, "record_entities.ndjson": archiveRecordEntityColumns,
            "tags.json": archiveTagColumns, "analyte_user_aliases.json": archiveAliasColumns,
        ]
    }

    static let archiveRequiredColumns: [String: [String]] = [
        "records.ndjson": ["id"], "pages.ndjson": ["record_id", "page_index"],
        "fields.ndjson": ["id", "record_id", "field_key"], "observations.ndjson": ["id", "record_id"],
        "highlights.ndjson": ["id", "record_id", "section", "text"], "links.ndjson": ["a_id", "b_id", "kind"],
        "entities.ndjson": ["id", "kind"], "record_entities.ndjson": ["record_id", "entity_id", "role"],
        "tags.json": ["id", "name"], "analyte_user_aliases.json": ["normalized_name", "analyte_id"],
    ]

    static let archiveReadErrors: [String: String] = [
        "manifest_missing": "This file is not an Ayuvo records archive",
        "bad_format": "This file is not an Ayuvo records archive",
        "unsupported_version": "This backup was made by a newer version of Ayuvo",
    ]

    static let archiveReadWarnings: [String: String] = [
        "checksums_missing": "checksums.json is missing; nothing could be verified",
        "checksum_mismatch": "{entry} does not match its checksum and may be damaged",
        "checksum_unlisted": "{entry} has no checksum",
        "file_unlisted": "{entry} has no checksum and was skipped",
        "entry_missing": "{entry} is missing",
        "entry_order": "The entries are not in the export order",
        "unknown_entry": "{entry} is not part of this format and was ignored",
        "bad_row": "{entry} has {count} unreadable row(s)",
        "unknown_columns": "{entry} has unknown columns that were dropped: {columns}",
        "record_count_mismatch": "The manifest counts {expected} record(s), the archive holds {actual}",
    ]

    static let archiveChildTables: [(table: String, key: String)] = [
        ("pages", "record_id"), ("fields", "record_id"), ("observations", "record_id"),
        ("highlights", "record_id"), ("record_entities", "record_id"),
    ]

    // MARK: - Ordered rows

    /// `_row(source, columns)`: the present, non-null columns in schema order.
    static func archiveRow(_ source: RJ, _ columns: [String]) -> [(String, RJ)] {
        columns.compactMap { column in
            let value = source[column]
            return value.isNull ? nil : (column, value)
        }
    }

    static func archiveRowObject(_ pairs: [(String, RJ)]) -> RJ {
        var object: [String: RJ] = [:]
        for pair in pairs { object[pair.0] = pair.1 }
        return .obj(object)
    }

    /// Columns stored as SQLite text that travel as JSON values in the archive (§35).
    /// `blocks_json` and `reasons_json` stay strings, as the contract spells out.
    static let archiveParsedJSONColumns: Set<String> = ["value_json", "source_bbox"]

    /// `compact_json` of one value: Python's `json.dumps(separators=(",",":"), ensure_ascii=False)`.
    /// Reals keep their `.0` (a REAL column is never printed as an integer) and object keys are
    /// sorted, which is how the shared fixture and the vectors encode them.
    static func archiveJSONText(_ value: RJ) -> String {
        switch value {
        case .null: return "null"
        case .bool(let b): return b ? "true" : "false"
        case .int(let i): return "\(i)"
        case .num(let d): return d.isFinite ? "\(d)" : "null"
        case .str: return value.compactJSON
        case .arr(let items): return "[" + items.map(archiveJSONText).joined(separator: ",") + "]"
        case .obj(let object):
            let body = object.keys.sorted(by: scalarLess)
                .map { "\(RJ.str($0).compactJSON):\(archiveJSONText(object[$0] ?? .null))" }
                .joined(separator: ",")
            return "{" + body + "}"
        }
    }

    /// `compact_json` of an ordered row: compact separators, non-ASCII unescaped.
    static func archiveCompactJSON(_ pairs: [(String, RJ)]) -> String {
        "{" + pairs.map { "\(RJ.str($0.0).compactJSON):\(archiveJSONText($0.1))" }.joined(separator: ",") + "}"
    }

    static func archiveLineBytes(_ pairs: [(String, RJ)]) -> Int {
        archiveCompactJSON(pairs).utf8.count
    }

    private static func archiveSet(_ pairs: inout [(String, RJ)], _ key: String, _ value: RJ) {
        if let index = pairs.firstIndex(where: { $0.0 == key }) {
            pairs[index].1 = value
        } else {
            pairs.append((key, value))
        }
    }

    private static func archiveRemove(_ pairs: inout [(String, RJ)], _ key: String) {
        pairs.removeAll { $0.0 == key }
    }

    /// `_truncate_page_row`: a page line over the cap keeps the longest text prefix that fits.
    static func truncatePageRow(_ row: [(String, RJ)]) -> (row: [(String, RJ)], changed: Bool) {
        if archiveLineBytes(row) <= archiveLineCap { return (row, false) }
        let text = row.first { $0.0 == "text" }?.1.string ?? ""
        let scalars = Array(text.unicodeScalars)
        var probe = row
        archiveSet(&probe, "text", .str(""))
        if archiveLineBytes(probe) > archiveLineCap, probe.contains(where: { $0.0 == "blocks_json" }) {
            archiveRemove(&probe, "blocks_json")
            probe.append(("blocks_truncated", .bool(true)))
            archiveSet(&probe, "text", .str(text))
            if archiveLineBytes(probe) <= archiveLineCap { return (probe, true) }
            archiveSet(&probe, "text", .str(""))
        }
        probe.append(("text_truncated", .bool(true)))
        var lo = 0
        var hi = scalars.count
        while lo < hi {
            let mid = (lo + hi + 1) / 2
            archiveSet(&probe, "text", .str(String(String.UnicodeScalarView(scalars[0..<mid]))))
            if archiveLineBytes(probe) <= archiveLineCap { lo = mid } else { hi = mid - 1 }
        }
        let kept = String(String.UnicodeScalarView(scalars[0..<lo]))
        archiveSet(&probe, "text", .str(kept))
        if kept.isEmpty { archiveRemove(&probe, "text") }
        return (probe, true)
    }

    // MARK: - Export payload

    /// `_archive_record_order`: records by `(sort_date, created_ms, id)`.
    static func archiveRecordOrder(_ snapshot: RJ) -> [RJ] {
        snapList(snapshot, "records").stableSorted { a, b in
            let sa = a["sort_date"].string ?? "", sb = b["sort_date"].string ?? ""
            if sa != sb { return scalarLess(sa, sb) }
            let ca = a["created_ms"].double ?? 0, cb = b["created_ms"].double ?? 0
            if ca != cb { return ca < cb }
            return scalarLess(a["id"].string ?? "", b["id"].string ?? "")
        }
    }

    private static func lessBy(_ a: RJ, _ b: RJ, _ keys: [String]) -> Bool {
        for key in keys {
            let x = a[key], y = b[key]
            if let sx = x.string ?? (x.isNull ? "" : nil), let sy = y.string ?? (y.isNull ? "" : nil) {
                if sx != sy { return scalarLess(sx, sy) }
                continue
            }
            let nx = x.double ?? 0, ny = y.double ?? 0
            if nx != ny { return nx < ny }
        }
        return false
    }

    /// `archive_rows(snapshot)`: the ten data entries in order, each as its ordered rows.
    static func archiveRows(_ snapshot: RJ) -> [(name: String, rows: [[(String, RJ)]])] {
        let ordered = archiveRecordOrder(snapshot)
        var order: [String: Int] = [:]
        for (index, record) in ordered.enumerated() { order[record["id"].string ?? ""] = index }

        var out: [(name: String, rows: [[(String, RJ)]])] = []
        out.append(("records.ndjson", ordered.map { archiveRow($0, archiveRecordColumns) }))

        let pages = snapList(snapshot, "pages").stableSorted { lessBy($0, $1, ["record_id", "page_index"]) }
        out.append(("pages.ndjson", pages.map { truncatePageRow(archiveRow($0, archivePageColumns)).row }))

        let fields = snapList(snapshot, "fields").stableSorted { lessBy($0, $1, ["record_id", "created_ms", "id"]) }
        out.append(("fields.ndjson", fields.map { archiveRow($0, archiveFieldColumns) }))

        let observations = snapList(snapshot, "observations").stableSorted { lessBy($0, $1, ["record_id", "created_ms", "id"]) }
        out.append(("observations.ndjson", observations.map { archiveRow($0, archiveObservationColumns) }))

        let highlights = snapList(snapshot, "highlights").stableSorted { lessBy($0, $1, ["record_id", "section", "position", "id"]) }
        out.append(("highlights.ndjson", highlights.map { archiveRow($0, archiveHighlightColumns) }))

        let links = snapList(snapshot, "links").stableSorted { lessBy($0, $1, ["a_id", "b_id"]) }
        out.append(("links.ndjson", links.map { archiveRow($0, archiveLinkColumns) }))

        let entities = snapList(snapshot, "entities").stableSorted { lessBy($0, $1, ["kind", "normalized_name", "id"]) }
        out.append(("entities.ndjson", entities.map { archiveRow($0, archiveEntityColumns) }))

        let recordEntities = snapList(snapshot, "record_entities").stableSorted { lessBy($0, $1, ["record_id", "entity_id", "role"]) }
        out.append(("record_entities.ndjson", recordEntities.map { archiveRow($0, archiveRecordEntityColumns) }))

        var byTag: [String: [String]] = [:]
        for row in snapList(snapshot, "record_tags") {
            guard let tagID = row["tag_id"].string, let recordID = row["record_id"].string else { continue }
            byTag[tagID, default: []].append(recordID)
        }
        let tags = snapList(snapshot, "tags").stableSorted { a, b in
            let fa = fold(a["name"].string ?? ""), fb = fold(b["name"].string ?? "")
            if fa != fb { return scalarLess(fa, fb) }
            return scalarLess(a["id"].string ?? "", b["id"].string ?? "")
        }
        out.append(("tags.json", tags.map { tag -> [(String, RJ)] in
            let id = tag["id"].string ?? ""
            var seen = Set<String>()
            let ids = (byTag[id] ?? []).filter { seen.insert($0).inserted }.stableSorted { a, b in
                let oa = order[a] ?? order.count, ob = order[b] ?? order.count
                if oa != ob { return oa < ob }
                return scalarLess(a, b)
            }
            return [("id", .str(id)), ("name", tag["name"]), ("record_ids", .arr(ids.map(RJ.str)))]
        }))

        var aliases = snapList(snapshot, "analyte_user_aliases")
        if aliases.isEmpty, let map = snapshot["user_aliases"].object {
            aliases = map.keys.sorted(by: scalarLess).map { key in
                .obj(["normalized_name": .str(key), "analyte_id": map[key] ?? .null])
            }
        }
        let sortedAliases = aliases.stableSorted { scalarLess($0["normalized_name"].string ?? "", $1["normalized_name"].string ?? "") }
        out.append(("analyte_user_aliases.json", sortedAliases.map { archiveRow($0, archiveAliasColumns) }))
        return out
    }

    /// `archive_file_entries`: per record, its original then its thumbnail.
    static func archiveFileEntries(_ snapshot: RJ, includeFiles: Bool) -> [RJ] {
        guard includeFiles else { return [] }
        var out: [RJ] = []
        for record in archiveRecordOrder(snapshot) {
            let id = record["id"].string ?? ""
            for (kind, pathKey, sizeKey) in [("original", "file_path", "file_size"), ("thumb", "thumbnail_path", "thumbnail_size")] {
                guard let path = record[pathKey].string, !path.isEmpty else { continue }
                let name = path.replacingOccurrences(of: "\\", with: "/").components(separatedBy: "/").last ?? path
                out.append(.obj([
                    "name": .str("files/\(id)/\(name)"), "record_id": .str(id), "kind": .str(kind),
                    "bytes": .number(record[sizeKey].double ?? 0),
                ]))
            }
        }
        return out
    }

    static func archiveEntryNames(_ snapshot: RJ, includeFiles: Bool) -> [String] {
        ["manifest.json"] + archiveDataEntries
            + archiveFileEntries(snapshot, includeFiles: includeFiles).compactMap { $0["name"].string }
            + ["checksums.json"]
    }

    static func archiveManifest(_ snapshot: RJ, platform: String, appVersion: String, createdMs: Double,
                                timeZone: String, includeFiles: Bool) -> RJ {
        let files = archiveFileEntries(snapshot, includeFiles: includeFiles)
        let totalBytes = files.reduce(0.0) { $0 + ($1["bytes"].double ?? 0) }
        return .obj([
            "format": .str(archiveFormat), "format_version": .int(archiveFormatVersion),
            "schema_version": .int(archiveSchemaVersion), "app": .str(archiveApp),
            "app_version": .str(appVersion), "platform": .str(platform),
            "created_ms": .number(createdMs), "time_zone": .str(timeZone),
            "record_count": .int(snapList(snapshot, "records").count),
            "file_count": .int(files.count), "total_file_bytes": .number(totalBytes),
        ])
    }

    /// `archive_entry_text`: the exact bytes of a data entry (always one trailing LF).
    static func archiveEntryText(name: String, rows: [[(String, RJ)]]) -> String {
        if name.hasSuffix(".ndjson") {
            return rows.map { archiveCompactJSON($0) + "\n" }.joined()
        }
        return "[" + rows.map { archiveCompactJSON($0) }.joined(separator: ",") + "]\n"
    }

    // MARK: - Reading

    static func archiveWarning(_ code: String, values: [String: String] = [:]) -> RJ {
        .obj([
            "code": .str(code), "entry": .string(values["entry"]),
            "text": .str(fillPlaceholders(archiveReadWarnings[code] ?? "", values)),
        ])
    }

    /// `read_archive_rows(entries)`.
    static func readArchiveRows(_ entries: [RJ]) -> RJ {
        var names: [String] = []
        var byName: [String: RJ] = [:]
        for entry in entries {
            guard let name = entry["name"].string else { continue }
            names.append(name)
            byName[name] = entry
        }
        var warnings: [RJ] = []
        var rowsOut: [String: RJ] = [:]
        var counts: [String: RJ] = [:]
        var files: [String: [String: RJ]] = [:]

        func failure(_ code: String, manifest: RJ = .null) -> RJ {
            .obj([
                "ok": .bool(false), "error": .str(code), "error_text": .str(archiveReadErrors[code] ?? ""),
                "manifest": manifest, "rows": .obj([:]), "files": .obj([:]), "warnings": .arr([]), "counts": .obj([:]),
            ])
        }

        let manifest = (byName["manifest.json"] ?? .null)["json"]
        guard manifest.object != nil else { return failure("manifest_missing") }
        guard manifest["format"].string == archiveFormat else { return failure("bad_format") }
        var version: Int?
        if case .int(let value) = manifest["format_version"] { version = value }
        guard let version, version <= archiveFormatVersion else { return failure("unsupported_version", manifest: manifest) }

        var checksums: [String: RJ]?
        if let object = (byName["checksums.json"] ?? .null)["json"].object {
            checksums = object
        } else {
            warnings.append(archiveWarning("checksums_missing"))
        }

        var expectedOrder = (["manifest.json"] + archiveDataEntries).filter { byName[$0] != nil }
        expectedOrder += names.filter { $0.hasPrefix("files/") }
        if byName["checksums.json"] != nil { expectedOrder.append("checksums.json") }
        let presentKnown = names.filter {
            archiveEntryTable[$0] != nil || $0.hasPrefix("files/") || $0 == "manifest.json" || $0 == "checksums.json"
        }
        if presentKnown != expectedOrder { warnings.append(archiveWarning("entry_order")) }
        for name in names where archiveEntryTable[name] == nil && !name.hasPrefix("files/")
            && name != "manifest.json" && name != "checksums.json" {
            warnings.append(archiveWarning("unknown_entry", values: ["entry": name]))
        }
        if let checksums {
            for name in names where name != "checksums.json" {
                let want = checksums[name]
                let got = (byName[name] ?? .null)["sha256"]
                if want == nil || want!.isNull {
                    warnings.append(archiveWarning(name.hasPrefix("files/") ? "file_unlisted" : "checksum_unlisted", values: ["entry": name]))
                } else if !got.isNull, got.string != want!.string {
                    warnings.append(archiveWarning("checksum_mismatch", values: ["entry": name]))
                }
            }
            for name in checksums.keys.sorted(by: scalarLess) where byName[name] == nil {
                warnings.append(archiveWarning("entry_missing", values: ["entry": name]))
            }
        }

        for name in archiveDataEntries {
            let table = archiveEntryTable[name] ?? name
            guard let rows = (byName[name] ?? .null)["rows"].array else {
                warnings.append(archiveWarning(byName[name] == nil ? "entry_missing" : "bad_row",
                                               values: ["entry": name, "count": "0"]))
                rowsOut[table] = .arr([])
                counts[name] = .int(0)
                continue
            }
            let columns = archiveEntryColumns[name] ?? []
            let columnSet = Set(columns)
            let required = archiveRequiredColumns[name] ?? []
            var kept: [RJ] = []
            var bad = 0
            var unknown = Set<String>()
            for row in rows {
                guard let object = row.object, required.allSatisfy({ !(object[$0] ?? .null).isNull }) else {
                    bad += 1
                    continue
                }
                unknown.formUnion(Set(object.keys).subtracting(columnSet).subtracting(["text_truncated", "blocks_truncated"]))
                var cleaned: [String: RJ] = [:]
                for column in columns where !(object[column] ?? .null).isNull {
                    cleaned[column] = object[column]
                }
                kept.append(.obj(cleaned))
            }
            if bad > 0 { warnings.append(archiveWarning("bad_row", values: ["entry": name, "count": "\(bad)"])) }
            if !unknown.isEmpty {
                warnings.append(archiveWarning("unknown_columns", values: [
                    "entry": name, "columns": unknown.sorted(by: scalarLess).joined(separator: ", "),
                ]))
            }
            rowsOut[table] = .arr(kept)
            counts[name] = .int(kept.count)
        }

        for name in names where name.hasPrefix("files/") {
            let parts = name.components(separatedBy: "/")
            guard parts.count == 3, !parts[1].isEmpty, !parts[2].isEmpty else { continue }
            if let checksums, (checksums[name] ?? .null).isNull { continue }
            let kind = parts[2].hasPrefix("thumb.") ? "thumb" : "original"
            files[parts[1], default: [:]][kind] = .str(name)
        }

        let actual = (rowsOut["records"]?.array ?? []).count
        if case .int(let expected) = manifest["record_count"], expected != actual {
            warnings.append(archiveWarning("record_count_mismatch", values: ["expected": "\(expected)", "actual": "\(actual)"]))
        }
        return .obj([
            "ok": .bool(true), "error": .null, "error_text": .null, "manifest": manifest,
            "rows": .obj(rowsOut), "files": .obj(files.mapValues { RJ.obj($0) }),
            "warnings": .arr(warnings), "counts": .obj(counts),
        ])
    }

    // MARK: - Merge / Replace

    /// `merge_plan(existing_snapshot, incoming_rows, mode)`.
    static func mergePlan(_ existingSnapshot: RJ, incoming: RJ, mode: String) -> RJ {
        guard mode == "merge" || mode == "replace" else {
            return .obj(["error": .str("bad_mode"), "mode": .str(mode)])
        }
        let rows = incoming["rows"].object != nil ? incoming["rows"] : incoming
        let files = incoming["files"]
        let existing = snapList(existingSnapshot, "records")
        var deleted: [RJ] = []
        var existingIDs = Set<String>()
        var existingChecksums: [String: String] = [:]
        if mode == "replace" {
            deleted = existing.stableSorted { timelineLess($1, $0) }.map { $0["id"] }
        } else {
            for record in existing {
                if let id = record["id"].string { existingIDs.insert(id) }
                if let checksum = record["checksum_sha256"].string, !checksum.isEmpty,
                   existingChecksums[checksum] == nil {
                    existingChecksums[checksum] = record["id"].string
                }
            }
        }
        var imported: [String] = []
        var skipped: [RJ] = []
        var records: [RJ] = []
        var seen = Set<String>()
        for row in rows["records"].array ?? [] {
            guard let id = row["id"].string else { continue }
            if !seen.insert(id).inserted {
                skipped.append(.obj(["id": .str(id), "reason": .str("duplicate_in_archive"), "existing_id": .null]))
                continue
            }
            if existingIDs.contains(id) {
                skipped.append(.obj(["id": .str(id), "reason": .str("existing_id"), "existing_id": .str(id)]))
                continue
            }
            if let checksum = row["checksum_sha256"].string, !checksum.isEmpty, let match = existingChecksums[checksum] {
                skipped.append(.obj(["id": .str(id), "reason": .str("existing_checksum"), "existing_id": .str(match)]))
                continue
            }
            imported.append(id)
            let entry = files[id]
            let hasOriginal = entry["original"].truthy
            let hasThumb = entry["thumb"].truthy
            records.append(.obj([
                "id": .str(id), "processing_status": .str("ready"),
                "file_path": hasOriginal ? row["file_path"] : .null,
                "thumbnail_path": hasThumb ? row["thumbnail_path"] : .null,
                "processing_error": (hasOriginal || !row["file_path"].truthy) ? .null : .str("file_missing"),
            ]))
        }
        let kept = Set(imported)
        let live = kept.union(existingIDs)
        var tables: [String: RJ] = [:]
        var outRows: [String: RJ] = ["records": .arr(records)]
        for (table, key) in archiveChildTables {
            let all = rows[table].array ?? []
            let good = all.filter { kept.contains($0[key].string ?? "") }
            tables[table] = .obj(["imported": .int(good.count), "skipped": .int(all.count - good.count)])
            outRows[table] = .arr(good)
        }
        let allLinks = rows["links"].array ?? []
        let links = allLinks.filter {
            let a = $0["a_id"].string ?? "", b = $0["b_id"].string ?? ""
            return live.contains(a) && live.contains(b) && (kept.contains(a) || kept.contains(b))
        }
        tables["links"] = .obj(["imported": .int(links.count), "skipped": .int(allLinks.count - links.count)])
        outRows["links"] = .arr(links)

        let allEntities = rows["entities"].array ?? []
        let used = Set((outRows["record_entities"]?.array ?? []).compactMap { $0["entity_id"].string })
        let entities = allEntities.filter { used.contains($0["id"].string ?? "") }
        tables["entities"] = .obj(["imported": .int(entities.count), "skipped": .int(allEntities.count - entities.count)])
        outRows["entities"] = .arr(entities)

        let allTags = rows["tags"].array ?? []
        var tags: [RJ] = []
        for tag in allTags {
            let ids = (tag["record_ids"].array ?? []).compactMap(\.string).filter { kept.contains($0) }
            guard !ids.isEmpty else { continue }
            tags.append(.obj(["id": tag["id"], "name": tag["name"], "record_ids": .arr(ids.map(RJ.str))]))
        }
        tables["tags"] = .obj(["imported": .int(tags.count), "skipped": .int(allTags.count - tags.count)])
        outRows["tags"] = .arr(tags)

        var have = Set<String>()
        if mode == "merge" {
            if let map = existingSnapshot["user_aliases"].object { have.formUnion(map.keys) }
            for alias in snapList(existingSnapshot, "analyte_user_aliases") {
                if let key = alias["normalized_name"].string { have.insert(key) }
            }
        }
        let allAliases = rows["analyte_user_aliases"].array ?? []
        let aliases = allAliases.filter { !have.contains($0["normalized_name"].string ?? "") }
        tables["analyte_user_aliases"] = .obj(["imported": .int(aliases.count), "skipped": .int(allAliases.count - aliases.count)])
        outRows["analyte_user_aliases"] = .arr(aliases)

        return .obj([
            "mode": .str(mode), "error": .null, "deleted": .arr(deleted),
            "imported": .arr(imported.map(RJ.str)), "skipped": .arr(skipped), "records": .arr(records),
            "tables": .obj(tables),
            "file_missing": .arr(records.filter { $0["processing_error"].string == "file_missing" }.map { $0["id"] }),
            "rebuild_fts": .bool(true),
        ])
    }

    // MARK: - Vector dispatch

    static func runArchiveCase(input: RJ, snapshot: RJ) -> RJ {
        switch input["op"].string ?? "" {
        case "manifest":
            return archiveManifest(snapshot, platform: input["platform"].string ?? "",
                                   appVersion: input["app_version"].string ?? "",
                                   createdMs: input["created_ms"].double ?? 0,
                                   timeZone: input["time_zone"].string ?? "",
                                   includeFiles: input["include_files"].isNull ? true : input["include_files"].truthy)
        case "rows":
            let rows = archiveRows(snapshot)
            let byName = Dictionary(rows.map { ($0.name, $0.rows) }, uniquingKeysWith: { a, _ in a })
            return .obj(["entries": .arr(archiveDataEntries.map { name in
                .obj(["name": .str(name), "rows": .arr((byName[name] ?? []).map { archiveRowObject($0) })])
            })])
        case "entries":
            let includeFiles = input["include_files"].isNull ? true : input["include_files"].truthy
            return .obj(["entries": .arr(archiveEntryNames(snapshot, includeFiles: includeFiles).map(RJ.str))])
        case "truncate":
            let unit = input["text_unit"].string ?? ""
            let times = Int(input["text_times"].double ?? 0)
            let text = times > 0 ? String(repeating: unit, count: times) : ""
            var page = input["page"]
            if !text.isEmpty, var object = page.object {
                object["text"] = .str(text)
                page = .obj(object)
            }
            let (row, changed) = truncatePageRow(archiveRow(page, archivePageColumns))
            let kept = row.first { $0.0 == "text" }?.1.string ?? ""
            let kscalars = Array(kept.unicodeScalars)
            let head = String(String.UnicodeScalarView(kscalars.prefix(40)))
            let tail = String(String.UnicodeScalarView(kscalars.suffix(40)))
            return .obj([
                "input_length": .int(text.unicodeScalars.count), "text_length": .int(kscalars.count),
                "text_bytes": .int(kept.utf8.count),
                "text_truncated": .bool(row.contains { $0.0 == "text_truncated" && $0.1.truthy }),
                "blocks_truncated": .bool(row.contains { $0.0 == "blocks_truncated" && $0.1.truthy }),
                "changed": .bool(changed),
                "line_bytes": .int(archiveLineBytes(row)),
                "head": .str(head), "tail": .str(tail),
            ])
        case "read":
            return readArchiveRows(input["entries"].array ?? [])
        case "merge":
            return mergePlan(snapshot, incoming: input["incoming"], mode: input["mode"].string ?? "")
        default:
            return .null
        }
    }
}
