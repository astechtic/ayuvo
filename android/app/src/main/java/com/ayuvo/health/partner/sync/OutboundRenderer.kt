package com.ayuvo.health.partner.sync

import android.util.Log
import com.ayuvo.health.partner.data.PartnerStore
import com.ayuvo.health.partner.logic.LedgerDelta
import com.ayuvo.health.partner.logic.MappedRecord
import com.ayuvo.health.partner.logic.PartnerCatalog
import com.ayuvo.health.partner.logic.PartnerEnvelopes
import com.ayuvo.health.partner.logic.PartnerJson
import com.ayuvo.health.partner.sources.PartnerSource
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/** One CHANGES message worth of envelopes. */
data class ChangesPage(val fromRev: Long, val toRev: Long, val hasMore: Boolean, val records: List<JsonObject>) {
    fun message(): JsonObject = PartnerJson.obj(
        "t" to "CHANGES", "from_rev" to fromRev, "to_rev" to toRev, "has_more" to hasMore, "records" to JsonArray(records)
    )
}

/**
 * Renders `ledger_delta` keys into wire envelopes (docs/partner-sync.md §10): payloads come from the sources at send
 * time, a deleted ledger row or a vanished source record becomes a tombstone, and every envelope is checked with
 * envelope_validate before it leaves (an invalid one is logged and skipped, the receiver would reject it anyway).
 */
class OutboundRenderer(
    private val store: PartnerStore,
    sources: List<PartnerSource>,
    private val now: () -> Long = System::currentTimeMillis
) {
    private val byType = sources.associateBy { it.type }

    suspend fun envelopes(delta: LedgerDelta): List<JsonObject> {
        val nowMs = now()
        val live = delta.keys.filter { !it.deleted }.groupBy { it.type }
        val rendered = HashMap<String, Map<String, com.ayuvo.health.partner.sources.SourceRecord>>()
        for ((type, keys) in live) {
            rendered[type] = byType[type]?.let { src -> runCatching { src.render(keys.map { it.recordId }) }.getOrNull() } ?: emptyMap()
        }
        val out = ArrayList<JsonObject>(delta.keys.size)
        for (k in delta.keys) {
            val ledger = store.ledgerRow(k.type, k.recordId)
            val category = ledger?.category ?: continue
            val record = if (k.deleted) null else rendered[k.type]?.get(k.recordId)
            val env = if (record == null || record.category != category) {
                MappedRecord.tombstone(k.type, k.recordId, category, k.rev, nowMs)
            } else {
                record.envelope(k.rev, nowMs)
            }
            val check = PartnerEnvelopes.validate(env, nowMs)
            if (!check.ok) {
                Log.w(TAG, "skipping ${k.type} envelope: ${check.error}")
                continue
            }
            out += env
        }
        return out
    }

    /**
     * Splits one delta page into CHANGES messages that each fit a Noise frame (65535 − 16 bytes of JSON), 512 KB and
     * 500 records. Every message but the last ends at its own last rev with has_more = true; the last one carries the
     * page's to_rev / has_more. A single envelope too large for any frame is skipped on the network only (docs §7.6
     * "Frame fit"): its rev is still covered by the page's to_rev, [onOversize] counts it (`oversize_skipped`), and it
     * still travels in packages, which have no frame limit. report_overview is trimmed to fit at the source first.
     */
    fun split(delta: LedgerDelta, envelopes: List<JsonObject>, maxJsonBytes: Int = MAX_JSON_BYTES, onOversize: () -> Unit = {}): List<ChangesPage> {
        val c = PartnerCatalog.current
        val limit = minOf(maxJsonBytes, c.batchBytesMax)
        val pages = ArrayList<ChangesPage>()
        var from = delta.fromRev
        var cur = ArrayList<JsonObject>()
        var curBytes = 0
        val overhead = 120
        for (env in envelopes) {
            val size = env.toString().toByteArray(Charsets.UTF_8).size + 1
            if (size + overhead > limit) {
                Log.w(TAG, "oversize_skipped ${PartnerJson.str(env["type"])} envelope ($size bytes)")
                onOversize()
                continue
            }
            if (cur.isNotEmpty() && (cur.size >= c.batchMax || curBytes + size + overhead > limit)) {
                val to = PartnerJson.long(cur.last()["rev"])!!
                pages += ChangesPage(from, to, true, cur)
                from = to
                cur = ArrayList()
                curBytes = 0
            }
            cur += env
            curBytes += size
        }
        pages += ChangesPage(from, maxOf(delta.toRev, from), delta.hasMore, cur)
        return pages
    }

    companion object {
        /** One transport message: 65535 bytes minus the 16-byte ChaCha20-Poly1305 tag. */
        const val MAX_JSON_BYTES = 65535 - 16
        private const val TAG = "PartnerOutbound"
    }
}
