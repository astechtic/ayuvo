package com.ayuvo.health.partner.logic

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import java.util.TreeMap

/** docs/partner-sync.md §8–§9: the receiver's merge engine, received grants and retention. */
object PartnerMerge {

    /**
     * Applies one CHANGES batch (`{from_rev, to_rev, records}`) from one owner.
     *
     * [storedRev] returns the rev of the row already stored for a key (null when there is none); callers look up
     * only the keys of the batch, never the whole table. [cursor] is the owner's last committed rev and [granted]
     * the categories the owner currently shares with me.
     */
    fun apply(storedRev: (RecordKey) -> Long?, batch: JsonObject, cursor: Long, granted: Collection<String>, nowMs: Long): MergeResult {
        val counts = MergeCounts()
        val fromRev = PartnerJson.long(batch["from_rev"])
        val toRev = PartnerJson.long(batch["to_rev"])
        val records = batch["records"]
        if (fromRev == null || toRev == null || toRev < fromRev || records !is JsonArray) {
            return MergeResult(false, "malformed", cursor, emptyList(), emptyList(), counts, emptyList())
        }
        if (fromRev > cursor) {
            return MergeResult(false, "cursor_gap", cursor, emptyList(), emptyList(), counts, emptyList())
        }
        val seen = HashMap<RecordKey, Long>()
        val upserts = TreeMap<RecordKey, PartnerRecordRow>(RecordKey.ORDER)
        val deletes = TreeMap<RecordKey, RecordKey>(RecordKey.ORDER)
        val rejected = mutableListOf<MergeRejection>()
        val grantedSet = granted.toSet()
        val maxUpdated = nowMs + PartnerCatalog.FUTURE_SKEW_MS
        for (env in records) {
            val v = PartnerEnvelopes.validate(env, nowMs)
            val rtype: JsonElement = (env as? JsonObject)?.get("type") ?: JsonNull
            val rid: JsonElement = (env as? JsonObject)?.get("id") ?: JsonNull
            if (!v.ok) {
                counts.rejected++
                rejected += MergeRejection(rtype, rid, v.error!!)
                continue
            }
            env as JsonObject
            if (v.category !in grantedSet) {
                counts.rejected++
                rejected += MergeRejection(rtype, rid, "not_granted")
                continue
            }
            val rev = PartnerJson.long(env["rev"])!!
            if (rev > toRev) {
                counts.rejected++
                rejected += MergeRejection(rtype, rid, "rev_out_of_range")
                continue
            }
            val key = RecordKey(PartnerJson.str(rtype)!!, PartnerJson.str(rid)!!)
            val stored = storedRev(key)
            val applied = seen[key]
            if (applied != null) {
                if (rev <= applied) {
                    if (rev < applied) counts.stale++ else counts.duplicate++
                    continue
                }
            } else if (stored != null) {
                if (rev == stored) { counts.duplicate++; continue }
                if (rev < stored) { counts.stale++; continue }
            } else if (rev <= cursor) {
                // Already committed once and gone since (deleted, pruned or purged): never resurrect it.
                counts.stale++
                continue
            }
            seen[key] = rev
            val exists = upserts.containsKey(key) || (stored != null && !deletes.containsKey(key))
            if (PartnerJson.bool(env["deleted"]) == true) {
                upserts.remove(key)
                if (exists) {
                    if (stored != null) deletes[key] = key
                    counts.deleted++
                } else {
                    counts.duplicate++
                }
                continue
            }
            deletes.remove(key)
            upserts[key] = PartnerRecordRow(
                type = key.type, recordId = key.recordId, category = v.category!!, rev = rev,
                day = PartnerJson.str(env["day"]), tsMs = PartnerEnvelopes.ts(env),
                updatedMs = minOf(PartnerJson.long(env["updated_ms"])!!, maxUpdated),
                data = env["data"] as JsonObject
            )
            if (exists) counts.updated++ else counts.inserted++
        }
        return MergeResult(true, null, maxOf(cursor, toRev), upserts.values.toList(), deletes.values.toList(), counts, rejected)
    }

    /** Reference signature: `existing` = rows already stored for this owner (only type, record_id and rev matter). */
    fun apply(existing: Map<RecordKey, Long>, batch: JsonObject, cursor: Long, granted: Collection<String>, nowMs: Long): MergeResult =
        apply({ existing[it] }, batch, cursor, granted, nowMs)

    /**
     * `grants_received_update`: rows for every category in catalog order. A category that was granted and is no
     * longer gets revoked_ms = now (its data is kept); re-granting clears revoked_ms.
     */
    fun grantsReceivedUpdate(previous: List<ReceivedGrant>, grantedNow: Collection<String>, nowMs: Long): List<ReceivedGrant> {
        val prev = previous.associateBy { it.category }
        val now = grantedNow.toSet()
        return PartnerCatalog.current.categories.map { cat ->
            val p = prev[cat]
            when {
                cat in now -> ReceivedGrant(cat, true, null)
                p?.granted == true -> ReceivedGrant(cat, false, nowMs)
                else -> ReceivedGrant(cat, false, p?.revokedMs)
            }
        }
    }

    /** `retention_prune`: keys of intraday rows (types with retention_days) older than the window, by ts_ms. */
    fun retentionPrune(rows: List<Triple<String, String, Long?>>, nowMs: Long): List<RecordKey> {
        val types = PartnerCatalog.current.types
        return rows.mapNotNull { (type, recordId, tsMs) ->
            val days = types[type]?.retentionDays
            if (days != null && days != 0 && tsMs != null && tsMs < nowMs - days * PartnerCatalog.DAY_MS) RecordKey(type, recordId) else null
        }
    }

    /** Types the receiver prunes after every sync, with their retention in days. */
    fun retentionTypes(): Map<String, Int> =
        PartnerCatalog.current.types.values.filter { (it.retentionDays ?: 0) > 0 }.associate { it.type to it.retentionDays!! }
}
