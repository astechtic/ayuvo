package com.ayuvo.health.partner.logic

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.util.TreeMap

/** A zip entry as the reader found it (sha256 computed by the reader; null for directories). */
data class PackageEntry(val name: String, val sha256: String?)

data class PackageCheck(val ok: Boolean, val error: String?) {
    fun toJson(): JsonObject = PartnerJson.obj("ok" to ok, "error" to error)
}

data class PackageSummary(val total: Long, val perCategory: List<Pair<String, Long>>) {
    fun toJson(): JsonObject = PartnerJson.obj(
        "total" to total,
        "per_category" to perCategory.map { (c, n) -> PartnerJson.obj("category" to c, "count" to n) }
    )
}

/** One merge_apply batch of an import plus whether it is the last one (which alone commits the cursor). */
data class PackageImportBatch(val result: MergeResult, val last: Boolean)

/** docs/partner-sync.md §13: the signed `.ayuvo.zip` package. */
object PartnerPackage {
    private val RE_SHA = Regex("[0-9a-f]{64}")
    private const val MANIFEST = "manifest.json"
    private const val SIGNATURE = "signature.json"

    fun entryNames(categories: Collection<String>): List<String> {
        val c = PartnerCatalog.current
        return c.packageEntriesFixed + c.categories.filter { it in categories }.map { "health/$it.ndjson" }
    }

    /** `<Name>_Health_YYYY-MM-DD.ayuvo.zip` with the name reduced to ASCII letters/digits joined by '_'. */
    fun filename(name: String?, day: String): String {
        val parts = mutableListOf<String>()
        val cur = StringBuilder()
        for (cp in PartnerJson.codePoints(PartnerQr.cleanName(name))) {
            if (cp in 'a'.code..'z'.code || cp in 'A'.code..'Z'.code || cp in '0'.code..'9'.code) {
                cur.appendCodePoint(cp)
            } else if (cur.isNotEmpty()) {
                parts += cur.toString(); cur.setLength(0)
            }
        }
        if (cur.isNotEmpty()) parts += cur.toString()
        val base = parts.joinToString("_").take(32).trim('_').ifEmpty { "Partner" }
        return "${base}_Health_$day${PartnerCatalog.current.packageExtension}"
    }

    /**
     * Checks before any record is read. Codes in check order: not_package, unsupported_version, malformed,
     * unsafe_entry, unknown_sender, wrong_recipient, bad_signature, missing_entry, unexpected_entry, hash_mismatch.
     * [signatureOk]: Ed25519 verification of the stored manifest.json bytes with the claimed sender's stored key
     * (false when the sender is unknown); [partners]: trusted owner ids (unpaired excluded); [me]: my device_id.
     */
    @Suppress("UNUSED_PARAMETER")
    fun validate(manifest: JsonElement?, entries: List<PackageEntry>, signatureOk: Boolean, partners: Collection<String>, me: String, nowMs: Long): PackageCheck {
        val c = PartnerCatalog.current
        fun fail(code: String) = PackageCheck(false, code)
        if (manifest !is JsonObject || PartnerJson.str(manifest["format"]) != c.packageFormat) return fail("not_package")
        if (!PartnerJson.isIntEqual(manifest["version"], c.packageVersion)) return fail("unsupported_version")
        for (f in listOf("export_id", "device_id", "recipient_device_id", "created_ms", "from_rev", "to_rev", "categories", "files")) {
            if (!manifest.containsKey(f)) return fail("malformed")
        }
        val catsEl = manifest["categories"]
        if (catsEl !is JsonArray) return fail("malformed")
        val cats = catsEl.map { PartnerJson.str(it) }
        if (cats.any { it == null || it !in c.categories } || cats.toSet().size != cats.size) return fail("malformed")
        val from = PartnerJson.long(manifest["from_rev"])
        val to = PartnerJson.long(manifest["to_rev"])
        if (from == null || to == null || to < from) return fail("malformed")
        val files = manifest["files"]
        if (files !is JsonArray || files.any { f ->
                f !is JsonObject || !PartnerJson.isString(f["name"]) || PartnerJson.str(f["sha256"])?.let { PartnerJson.pyFullMatch(RE_SHA, it) } != true
            }
        ) return fail("malformed")
        for (e in entries) {
            val n = e.name
            if (n.isEmpty() || n.startsWith("/") || n.contains('\\') || n.contains('\u0000') || ".." in n.split('/')) return fail("unsafe_entry")
        }
        if (PartnerJson.str(manifest["device_id"]).let { it == null || it !in partners }) return fail("unknown_sender")
        if (PartnerJson.str(manifest["recipient_device_id"]) != me) return fail("wrong_recipient")
        if (!signatureOk) return fail("bad_signature")
        val allowed = entryNames(cats.map { it!! }).toSet()
        val present = LinkedHashMap<String, String?>()
        for (e in entries) if (!e.name.endsWith("/")) present[e.name] = e.sha256
        val listed = LinkedHashMap<String, String>()
        for (f in files) { f as JsonObject; listed[PartnerJson.str(f["name"])!!] = PartnerJson.str(f["sha256"])!! }
        for (required in listOf(MANIFEST, SIGNATURE)) if (required !in present) return fail("missing_entry")
        for (name in present.keys) if (name !in allowed) return fail("unexpected_entry")
        for (name in listed.keys) {
            if (name !in allowed || name == MANIFEST || name == SIGNATURE) return fail("unexpected_entry")
            if (name !in present) return fail("missing_entry")
        }
        for ((name, sha) in present) {
            if (name == MANIFEST || name == SIGNATURE) continue
            if (name !in listed) return fail("unexpected_entry")
            if (sha != listed[name]) return fail("hash_mismatch")
        }
        return PackageCheck(true, null)
    }

    /** Counts for the import confirmation sheet: total records and per category (manifest `files[].count`). */
    fun summary(manifestFiles: List<JsonObject>, categories: Collection<String>): PackageSummary {
        val per = HashMap<String, Long>()
        for (f in manifestFiles) {
            val name = PartnerJson.str(f["name"]) ?: continue
            if (name.startsWith("health/") && name.endsWith(".ndjson") && name.length >= "health/.ndjson".length) {
                val cat = name.substring("health/".length, name.length - ".ndjson".length)
                if (cat in categories) {
                    val count = if (PartnerJson.truthy(f["count"])) PartnerJson.double(f["count"])?.toLong() ?: 0L else 0L
                    per[cat] = (per[cat] ?: 0L) + count
                }
            }
        }
        val ordered = PartnerCatalog.current.categories.filter { it in per }.map { it to per.getValue(it) }
        return PackageSummary(ordered.sumOf { it.second }, ordered)
    }

    /**
     * `package_import`, streaming form: feeds [records] (category files in catalog order, file order inside)
     * in [batchSize] batches through merge_apply. Category files interleave revs, so every batch uses the
     * manifest's from_rev/to_rev and the PRE-IMPORT [cursor]. [storedRev] must reflect the batches already
     * persisted; each batch's rows are committed by the caller in their own transaction, and the cursor only with
     * the batch flagged `last`. Stops at the first refused batch.
     */
    fun importBatches(
        storedRev: (RecordKey) -> Long?,
        cursor: Long,
        categories: Collection<String>,
        fromRev: Long,
        toRev: Long,
        records: Sequence<JsonElement>,
        nowMs: Long,
        batchSize: Int = PartnerCatalog.current.batchMax,
        onBatch: (PackageImportBatch) -> Unit
    ): Boolean {
        val it = records.chunked(batchSize).iterator()
        var chunk: List<JsonElement> = if (it.hasNext()) it.next() else emptyList()
        while (true) {
            val last = !it.hasNext()
            val batch = PartnerJson.obj("from_rev" to fromRev, "to_rev" to toRev, "records" to JsonArray(chunk))
            val res = PartnerMerge.apply(storedRev, batch, cursor, categories, nowMs)
            onBatch(PackageImportBatch(res, last))
            if (!res.accepted) return false
            if (last) return true
            chunk = it.next()
        }
    }

    /**
     * `package_import` (reference form): merges a validated package against [existing] and returns the final
     * state changes across all batches, sorted by (type, record_id).
     */
    fun import(existing: Map<RecordKey, Long>, cursor: Long, manifest: JsonObject, files: JsonObject, nowMs: Long, batchSize: Int): MergeResult {
        val c = PartnerCatalog.current
        val counts = MergeCounts()
        val granted = (manifest["categories"] as JsonArray).mapNotNull { PartnerJson.str(it) }
        val fromRev = PartnerJson.long(manifest["from_rev"])!!
        val toRev = PartnerJson.long(manifest["to_rev"])!!
        val stored = HashMap(existing)
        val finalUp = TreeMap<RecordKey, PartnerRecordRow>(RecordKey.ORDER)
        val finalDel = TreeMap<RecordKey, RecordKey>(RecordKey.ORDER)
        val rejected = mutableListOf<MergeRejection>()
        val records = c.categories.filter { files.containsKey(it) }.flatMap { files[it] as JsonArray }
        var refused: MergeResult? = null
        importBatches({ stored[it] }, cursor, granted, fromRev, toRev, records.asSequence(), nowMs, batchSize) { (res, _) ->
            if (!res.accepted) { refused = res; return@importBatches }
            counts.add(res.counts)
            rejected += res.rejected
            for (d in res.deletes) { stored.remove(d); finalUp.remove(d); finalDel[d] = d }
            for (u in res.upserts) { stored[u.key] = u.rev; finalDel.remove(u.key); finalUp[u.key] = u }
        }
        refused?.let { return MergeResult(false, it.error, cursor, emptyList(), emptyList(), counts, emptyList()) }
        return MergeResult(true, null, maxOf(cursor, toRev), finalUp.values.toList(), finalDel.values.toList(), counts, rejected)
    }
}
