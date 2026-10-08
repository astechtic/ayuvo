package com.ayuvo.health.partner.pkg

import com.ayuvo.health.partner.data.PartnerStore
import com.ayuvo.health.partner.identity.DeviceIdentity
import com.ayuvo.health.partner.logic.MergeCounts
import com.ayuvo.health.partner.logic.PackageEntry
import com.ayuvo.health.partner.logic.PackageSummary
import com.ayuvo.health.partner.logic.PartnerCatalog
import com.ayuvo.health.partner.logic.PartnerJson
import com.ayuvo.health.partner.logic.PartnerKdf
import com.ayuvo.health.partner.logic.PartnerPackage
import com.ayuvo.health.partner.logic.RecordKey
import com.ayuvo.health.partner.sync.PartnerSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipFile

/** A validated package waiting for the user's confirmation (docs/partner-sync.md §13 "Import" step 1). */
data class PackagePreview(
    val file: File,
    val ownerId: String,
    val senderName: String,
    val exportId: String,
    val fromRev: Long,
    val toRev: Long,
    val categories: List<String>,
    val summary: PackageSummary
)

sealed interface PackageInspection {
    /** Not an Ayuvo partner package: the zip falls through to the other handlers. */
    data object NotPackage : PackageInspection
    /** An Ayuvo package that failed package_validate (or a guard); [code] is the validation code. */
    data class Invalid(val code: String, val senderName: String?) : PackageInspection
    data class Ready(val preview: PackagePreview) : PackageInspection
}

data class PackageImportOutcome(val ok: Boolean, val error: String?, val counts: MergeCounts, val cursor: Long) {
    /** Re-importing an old package: nothing new (docs §13 "Already up to date"). */
    val alreadyUpToDate: Boolean get() = ok && counts.inserted + counts.updated + counts.deleted == 0
}

/**
 * Reads `.ayuvo.zip` packages (docs/partner-sync.md §13). The zip is opened with entry-count and size guards
 * (sizes are measured while streaming, never trusted from headers), every entry is hashed, the signature is verified
 * over the stored manifest bytes with the sender key I stored when we paired, and package_validate runs before any
 * record is read. Import feeds the NDJSON files through package_import's batches; each batch commits in its own
 * transaction and the cursor only with the last one.
 */
class PartnerPackageImporter(
    private val store: PartnerStore,
    private val myDeviceId: () -> String,
    private val now: () -> Long = System::currentTimeMillis
) {
    private class Guard(var total: Long = 0)

    private fun readBounded(input: InputStream, guard: Guard, perEntryMax: Long, onChunk: (ByteArray, Int) -> Unit) {
        val buf = ByteArray(64 * 1024)
        var n = 0L
        while (true) {
            val r = input.read(buf)
            if (r < 0) break
            n += r
            guard.total += r
            if (n > perEntryMax || guard.total > MAX_TOTAL_BYTES) throw IOException("too_large")
            onChunk(buf, r)
        }
    }

    private fun bytesOf(zip: ZipFile, e: ZipEntry, guard: Guard, max: Long): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        zip.getInputStream(e).use { readBounded(it, guard, max) { b, n -> out.write(b, 0, n) } }
        return out.toByteArray()
    }

    private fun openZip(file: File): ZipFile? = try {
        ZipFile(file)
    } catch (e: ZipException) {
        null
    } catch (e: IOException) {
        null
    }

    /** Cheap sniff: does [file] carry a manifest.json whose format is "ayuvo-partner-sync"? */
    fun isPartnerPackage(file: File): Boolean {
        val zip = openZip(file) ?: return isUnsafeZip(file)
        return zip.use { z ->
            val m = z.getEntry(MANIFEST) ?: return@use false
            val bytes = runCatching { bytesOf(z, m, Guard(), MAX_MANIFEST_BYTES) }.getOrNull() ?: return@use false
            val text = PartnerJson.decodeUtf8(bytes) ?: return@use false
            val o = runCatching { PartnerJson.parse(text) }.getOrNull() as? JsonObject ?: return@use false
            PartnerJson.str(o["format"]) == PartnerCatalog.current.packageFormat
        }
    }

    /** Android 14+ refuses zips with traversal names at open time; such a file is reported as an unsafe package. */
    private fun isUnsafeZip(file: File): Boolean = runCatching {
        java.util.zip.ZipInputStream(file.inputStream()).use { zin ->
            generateSequence { runCatching { zin.nextEntry }.getOrNull() }.take(MAX_ENTRIES).any { it.name == MANIFEST }
        }
    }.getOrDefault(false)

    suspend fun inspect(file: File): PackageInspection = withContext(Dispatchers.IO) {
        val zip = openZip(file) ?: return@withContext if (isUnsafeZip(file)) PackageInspection.Invalid("unsafe_entry", null) else PackageInspection.NotPackage
        zip.use { z ->
            val guard = Guard()
            val all = z.entries().toList()
            if (all.size > MAX_ENTRIES) return@withContext PackageInspection.Invalid("unexpected_entry", null)
            val manifestEntry = z.getEntry(MANIFEST) ?: return@withContext PackageInspection.NotPackage
            val manifestBytes = runCatching { bytesOf(z, manifestEntry, guard, MAX_MANIFEST_BYTES) }.getOrNull()
                ?: return@withContext PackageInspection.Invalid("malformed", null)
            val manifest = PartnerJson.decodeUtf8(manifestBytes)?.let { runCatching { PartnerJson.parse(it) }.getOrNull() }
            if (manifest !is JsonObject || PartnerJson.str(manifest["format"]) != PartnerCatalog.current.packageFormat) {
                return@withContext PackageInspection.NotPackage
            }
            val entries = ArrayList<PackageEntry>()
            for (e in all) {
                if (e.isDirectory) { entries += PackageEntry(e.name, null); continue }
                val md = MessageDigest.getInstance("SHA-256")
                try {
                    z.getInputStream(e).use { readBounded(it, guard, MAX_ENTRY_BYTES) { b, n -> md.update(b, 0, n) } }
                } catch (x: IOException) {
                    return@withContext PackageInspection.Invalid("malformed", null)
                }
                entries += PackageEntry(e.name, PartnerKdf.hex(md.digest()))
            }
            val sender = PartnerJson.str(manifest["device_id"])
            val partner = sender?.let { store.partner(it) }?.takeIf { it.trusted }
            val signatureOk = partner != null && z.getEntry(SIGNATURE)?.let { se ->
                val sig = runCatching { bytesOf(z, se, Guard(), MAX_MANIFEST_BYTES) }.getOrNull()
                    ?.let { PartnerJson.decodeUtf8(it) }?.let { runCatching { PartnerJson.parse(it) }.getOrNull() } as? JsonObject
                val raw = PartnerKdf.b64urlDecode(PartnerJson.str(sig?.get("sig")))
                sig != null && PartnerJson.str(sig["alg"]) == "Ed25519" && PartnerJson.str(sig["key"]) == partner.ed25519Pub &&
                    raw != null && DeviceIdentity.verify(partner.ed25519Pub, manifestBytes, raw)
            } == true
            val check = PartnerPackage.validate(manifest, entries, signatureOk, store.trustedOwnerIds(), myDeviceId(), now())
            if (!check.ok) return@withContext PackageInspection.Invalid(check.error!!, partner?.displayName)
            val categories = (manifest["categories"] as JsonArray).map { PartnerJson.str(it)!! }
            val files = (manifest["files"] as JsonArray).map { it as JsonObject }
            PackageInspection.Ready(
                PackagePreview(
                    file = file, ownerId = partner!!.ownerId, senderName = partner.displayName,
                    exportId = PartnerJson.str(manifest["export_id"]) ?: manifest["export_id"].toString(),
                    fromRev = PartnerJson.long(manifest["from_rev"])!!, toRev = PartnerJson.long(manifest["to_rev"])!!,
                    categories = categories, summary = PartnerPackage.summary(files, categories)
                )
            )
        }
    }

    /** Streams the NDJSON envelopes in category order (§1 catalog order), file order inside. */
    private fun records(z: ZipFile, categories: List<String>): Sequence<JsonElement> = sequence {
        for (cat in PartnerCatalog.current.categories) {
            if (cat !in categories) continue
            val e = z.getEntry("health/$cat.ndjson") ?: continue
            z.getInputStream(e).bufferedReader(Charsets.UTF_8).use { r ->
                while (true) {
                    val line = r.readLine() ?: break
                    if (line.isBlank()) continue
                    if (line.length > MAX_LINE_CHARS) { yield(JsonNull); continue }
                    yield(runCatching { PartnerJson.parse(line) }.getOrDefault(JsonNull))
                }
            }
        }
    }

    /** package_import after the user confirmed [preview] (re-validated first: the file may have changed). */
    suspend fun import(preview: PackagePreview): PackageImportOutcome = withContext(Dispatchers.IO) {
        val again = inspect(preview.file)
        if (again !is PackageInspection.Ready) return@withContext PackageImportOutcome(false, (again as? PackageInspection.Invalid)?.code ?: "not_package", MergeCounts(), 0)
        val p = again.preview
        val owner = p.ownerId
        val nowMs = now()
        store.updateGrantsReceived(owner, p.categories, nowMs)
        val cursor = store.syncState(owner)?.lastRev ?: 0L
        val counts = MergeCounts()
        var refused: String? = null
        var finalCursor = cursor
        val cache = HashMap<RecordKey, Long>()
        val seen = HashSet<RecordKey>()
        fun keyOf(e: JsonElement): RecordKey? {
            val o = e as? JsonObject ?: return null
            return RecordKey(PartnerJson.str(o["type"]) ?: return null, PartnerJson.str(o["id"]) ?: return null)
        }
        ZipFile(p.file).use { z ->
            val batch = PartnerCatalog.current.batchMax
            val seq = records(z, p.categories).chunked(batch).flatMap { chunk ->
                // Prefetch the stored revs of this batch's keys (once per key); later batches see earlier ones via onBatch.
                val missing = chunk.mapNotNull(::keyOf).filter { seen.add(it) }
                cache.putAll(store.storedRevs(owner, missing))
                chunk.asSequence()
            }
            PartnerPackage.importBatches({ cache[it] }, cursor, p.categories, p.fromRev, p.toRev, seq, nowMs, batch) { (res, last) ->
                if (!res.accepted) { refused = res.error; return@importBatches }
                store.applyMerge(owner, res, commitCursor = last)
                for (d in res.deletes) cache.remove(d)
                for (u in res.upserts) cache[u.key] = u.rev
                counts.add(res.counts)
                if (last) finalCursor = res.cursor
            }
        }
        if (refused != null) return@withContext PackageImportOutcome(false, refused, counts, cursor)
        store.retentionPrune(nowMs)
        store.syncState(owner)?.let {
            store.updateSyncState(it.copy(status = "up_to_date", lastError = null, lastSyncMs = nowMs, lastAttemptMs = nowMs,
                lastTransport = PartnerSession.TRANSPORT_PACKAGE, lastExportId = p.exportId))
        }
        PackageImportOutcome(true, null, counts, finalCursor)
    }

    companion object {
        const val MANIFEST = "manifest.json"
        const val SIGNATURE = "signature.json"
        const val MAX_ENTRIES = 64
        const val MAX_MANIFEST_BYTES = 1L shl 20
        const val MAX_ENTRY_BYTES = 256L shl 20
        const val MAX_TOTAL_BYTES = 512L shl 20
        private const val MAX_LINE_CHARS = 4 shl 20
    }
}
