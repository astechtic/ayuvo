package com.ayuvo.health.partner.logic

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject

/**
 * `(type, record_id)`. Ordered by Unicode scalar values (UTF-8 byte order), exactly like the reference's Python
 * tuple sort; never by UTF-16 units or locale.
 */
data class RecordKey(val type: String, val recordId: String) : Comparable<RecordKey> {
    override fun compareTo(other: RecordKey): Int {
        val t = PartnerJson.compareCodePoints(type, other.type)
        return if (t != 0) t else PartnerJson.compareCodePoints(recordId, other.recordId)
    }

    fun toJson(): JsonObject = PartnerJson.obj("type" to type, "record_id" to recordId)

    companion object {
        val ORDER: Comparator<RecordKey> = Comparator { a, b -> a.compareTo(b) }
    }
}

/** A received row of `partner_records` (without owner_id). */
data class PartnerRecordRow(
    val type: String,
    val recordId: String,
    val category: String,
    val rev: Long,
    val day: String?,
    val tsMs: Long?,
    val updatedMs: Long,
    val data: JsonObject
) {
    val key: RecordKey get() = RecordKey(type, recordId)

    fun toJson(): JsonObject = PartnerJson.obj(
        "type" to type, "record_id" to recordId, "category" to category, "rev" to rev, "day" to day,
        "ts_ms" to tsMs, "updated_ms" to updatedMs, "data" to data
    )
}

data class MergeCounts(
    var inserted: Int = 0,
    var updated: Int = 0,
    var deleted: Int = 0,
    var duplicate: Int = 0,
    var stale: Int = 0,
    var rejected: Int = 0
) {
    fun add(o: MergeCounts) {
        inserted += o.inserted; updated += o.updated; deleted += o.deleted
        duplicate += o.duplicate; stale += o.stale; rejected += o.rejected
    }

    fun toJson(): JsonObject = PartnerJson.obj(
        "inserted" to inserted, "updated" to updated, "deleted" to deleted,
        "duplicate" to duplicate, "stale" to stale, "rejected" to rejected
    )
}

/** A record the merge skipped; `type`/`id` are echoed as received (they may not even be strings). */
data class MergeRejection(val type: JsonElement, val id: JsonElement, val reason: String) {
    fun toJson(): JsonObject = PartnerJson.obj("type" to type, "id" to id, "reason" to reason)
}

/** Output of `merge_apply` / `package_import` (docs/partner-sync.md §8, §13). */
data class MergeResult(
    val accepted: Boolean,
    val error: String?,
    val cursor: Long,
    val upserts: List<PartnerRecordRow>,
    val deletes: List<RecordKey>,
    val counts: MergeCounts,
    val rejected: List<MergeRejection>
) {
    val hasChanges: Boolean get() = upserts.isNotEmpty() || deletes.isNotEmpty()

    fun toJson(): JsonObject = PartnerJson.obj(
        "accepted" to accepted, "error" to error, "cursor" to cursor,
        "upserts" to upserts.map { it.toJson() }, "deletes" to deletes.map { it.toJson() },
        "counts" to counts.toJson(), "rejected" to rejected.map { it.toJson() }
    )
}

/** One row of `partner_grants_received` (without owner_id / updated_ms). */
data class ReceivedGrant(val category: String, val granted: Boolean, val revokedMs: Long?) {
    fun toJson(): JsonObject = PartnerJson.obj("category" to category, "granted" to granted, "revoked_ms" to revokedMs)
}

/** One row of `outbound_ledger`. */
data class LedgerRow(
    val type: String,
    val recordId: String,
    val category: String,
    val day: String?,
    val contentHash: String,
    val rev: Long,
    val deleted: Boolean
) {
    val key: RecordKey get() = RecordKey(type, recordId)

    fun toJson(): JsonObject = PartnerJson.obj(
        "type" to type, "record_id" to recordId, "category" to category, "day" to day,
        "content_hash" to contentHash, "rev" to rev, "deleted" to deleted
    )
}

/** A shareable record rendered from a source during `ledger_refresh`. */
data class LedgerCurrent(val type: String, val recordId: String, val category: String, val day: String?, val contentHash: String) {
    val key: RecordKey get() = RecordKey(type, recordId)
}

/** Records of [type] the refresh looked at; with [dayFrom] only rows whose day >= dayFrom (or no day). */
data class LedgerScope(val type: String, val dayFrom: String? = null)

data class LedgerRefreshResult(val rev: Long, val ledger: List<LedgerRow>, val changed: Int, val tombstoned: Int) {
    fun toJson(): JsonObject = PartnerJson.obj(
        "rev" to rev, "ledger" to ledger.map { it.toJson() }, "changed" to changed, "tombstoned" to tombstoned
    )
}

data class LedgerRegrantResult(val rev: Long, val ledger: List<LedgerRow>) {
    fun toJson(): JsonObject = PartnerJson.obj("rev" to rev, "ledger" to ledger.map { it.toJson() })
}

data class LedgerDeltaKey(val type: String, val recordId: String, val rev: Long, val deleted: Boolean) {
    fun toJson(): JsonObject = PartnerJson.obj("type" to type, "record_id" to recordId, "rev" to rev, "deleted" to deleted)
}

/** One CHANGES page worth of ledger keys. */
data class LedgerDelta(val fromRev: Long, val toRev: Long, val hasMore: Boolean, val keys: List<LedgerDeltaKey>) {
    fun toJson(): JsonObject = PartnerJson.obj(
        "from_rev" to fromRev, "to_rev" to toRev, "has_more" to hasMore, "keys" to keys.map { it.toJson() }
    )
}

/** A mapped source record (`map_*`): envelope minus rev/deleted/updated_ms. */
data class MappedRecord(val type: String, val id: String, val category: String, val day: String?, val data: JsonObject) {
    fun toJson(): JsonObject = PartnerJson.obj("type" to type, "id" to id, "category" to category, "day" to day, "data" to data)

    /** The wire envelope for this record. */
    fun envelope(rev: Long, updatedMs: Long): JsonObject {
        val m = linkedMapOf<String, JsonElement>(
            "type" to PartnerJson.of(type), "id" to PartnerJson.of(id), "category" to PartnerJson.of(category),
            "rev" to PartnerJson.of(rev), "deleted" to PartnerJson.of(false), "updated_ms" to PartnerJson.of(updatedMs)
        )
        if (day != null) m["day"] = PartnerJson.of(day)
        m["data"] = data
        return JsonObject(m)
    }

    companion object {
        fun toJsonOrNull(r: MappedRecord?): JsonElement = r?.toJson() ?: JsonNull

        /** A tombstone envelope (no data). */
        fun tombstone(type: String, id: String, category: String, rev: Long, updatedMs: Long): JsonObject = PartnerJson.obj(
            "type" to type, "id" to id, "category" to category, "rev" to rev, "deleted" to true, "updated_ms" to updatedMs
        )
    }
}

/** A stored partner row as the summary card reads it. */
data class StoredRecord(val type: String, val recordId: String, val day: String?, val tsMs: Long?, val data: JsonObject)

data class SummaryMetric(val key: String, val category: String, val value: Long, val unit: String?, val day: String, val shared: Boolean) {
    fun toJson(): JsonObject = PartnerJson.obj(
        "key" to key, "category" to category, "value" to value, "unit" to unit, "day" to day, "shared" to shared
    )
}
