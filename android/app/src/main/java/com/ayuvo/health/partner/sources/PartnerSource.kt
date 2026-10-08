package com.ayuvo.health.partner.sources

import android.database.Cursor
import com.ayuvo.health.partner.logic.LedgerCurrent
import com.ayuvo.health.partner.logic.MappedRecord
import com.ayuvo.health.partner.logic.PartnerJson
import com.ayuvo.health.partner.logic.PartnerKdf
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** A shareable record rendered from one of the user's own stores (docs/partner-sync.md §7, §10). */
data class SourceRecord(val type: String, val recordId: String, val category: String, val day: String?, val data: JsonObject) {
    /**
     * Device-local content hash: SHA-256 of the canonical JSON of `{category, data, day}`. Never compared across
     * devices, but must stay byte-identical across versions (a different hash re-sends the record). Written straight
     * into one buffer (keys already in code-point order) with a per-thread digest: the refresh hashes every record.
     */
    val contentHash: String
        get() {
            val sb = StringBuilder(256)
            sb.append("{\"category\":"); PartnerJson.writeString(category, sb)
            sb.append(",\"data\":"); PartnerJson.writeSorted(data, sb)
            sb.append(",\"day\":"); if (day == null) sb.append("null") else PartnerJson.writeString(day, sb)
            sb.append('}')
            val md = DIGEST.get()!!
            md.reset()
            return PartnerKdf.hex(md.digest(sb.toString().toByteArray(Charsets.UTF_8)))
        }

    fun ledgerCurrent(): LedgerCurrent = LedgerCurrent(type, recordId, category, day, contentHash)

    /** The wire envelope for this record. */
    fun envelope(rev: Long, updatedMs: Long): JsonObject = MappedRecord(type, recordId, category, day, data).envelope(rev, updatedMs)

    companion object {
        fun of(m: MappedRecord): SourceRecord = SourceRecord(m.type, m.id, m.category, m.day, m.data)

        private val DIGEST = ThreadLocal.withInitial { java.security.MessageDigest.getInstance("SHA-256") }

        /** The reference form of [contentHash] (kept for the test that pins the fast path to it). */
        internal fun referenceHash(r: SourceRecord): String = PartnerKdf.hex(
            PartnerKdf.sha256(
                PartnerJson.dumpsCompactSorted(PartnerJson.obj("category" to r.category, "data" to r.data, "day" to r.day)).toByteArray(Charsets.UTF_8)
            )
        )
    }
}

/** A store could not be read; the refresh skips the type rather than tombstoning everything it holds. */
class SourceUnavailableException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * One record type read from the user's existing stores, read-only. Implementations page their reads (never a
 * whole table in memory) and read named columns / model fields only, so files, photos, notes and GPS tracks
 * never reach the partner layer.
 */
interface PartnerSource {
    /** The `record_types.json` type this source renders. */
    val type: String

    /**
     * Streams every current record in scope: all of them when [dayFrom] is null, else only records whose day is on
     * or after [dayFrom] (yyyy-MM-dd). Throws [SourceUnavailableException] when the store cannot be read.
     */
    suspend fun forEach(dayFrom: String?, onRecord: (SourceRecord) -> Unit)

    /** Renders the records with these ids for sending; ids whose source vanished are simply absent. */
    suspend fun render(ids: Collection<String>): Map<String, SourceRecord>
}

/** Reads only the named columns of a cursor row into a JSON object (SQLite types kept). */
internal object CursorJson {
    fun value(c: Cursor, i: Int): JsonElement = when (c.getType(i)) {
        Cursor.FIELD_TYPE_NULL -> JsonNull
        Cursor.FIELD_TYPE_INTEGER -> JsonPrimitive(c.getLong(i))
        Cursor.FIELD_TYPE_FLOAT -> JsonPrimitive(c.getDouble(i))
        Cursor.FIELD_TYPE_STRING -> JsonPrimitive(c.getString(i))
        else -> JsonNull // BLOBs are never part of a shareable row
    }

    fun row(c: Cursor, columns: List<String>): JsonObject {
        val m = LinkedHashMap<String, JsonElement>()
        columns.forEachIndexed { i, name -> m[name] = value(c, i) }
        return JsonObject(m)
    }
}
