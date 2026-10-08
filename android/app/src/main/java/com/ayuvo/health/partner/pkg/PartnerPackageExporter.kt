package com.ayuvo.health.partner.pkg

import com.ayuvo.health.partner.data.PartnerStore
import com.ayuvo.health.partner.logic.PartnerCatalog
import com.ayuvo.health.partner.logic.PartnerJson
import com.ayuvo.health.partner.logic.PartnerKdf
import com.ayuvo.health.partner.logic.PartnerPackage
import com.ayuvo.health.partner.sync.LedgerRefresher
import com.ayuvo.health.partner.sync.OutboundRenderer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** The sender's identity as a package needs it. */
data class PackageSigner(
    val deviceId: String,
    val x25519Pub: String,
    val ed25519Pub: String,
    val sign: (ByteArray) -> ByteArray
)

data class ExportResult(val file: File, val exportId: String, val fromRev: Long, val toRev: Long, val records: Long)

/**
 * Writes a signed `.ayuvo.zip` for one recipient (docs/partner-sync.md §13): everything I share with them
 * (`from_rev = 0`) or the changes since their `acked_rev`. Envelopes are streamed from `ledger_delta` in 500-row pages
 * into one NDJSON file per granted category (in rev order), then zipped with `manifest.json` first and
 * `signature.json` = Ed25519 over the manifest bytes exactly as stored. Only allow-listed entries are written.
 */
class PartnerPackageExporter(
    private val store: PartnerStore,
    private val renderer: OutboundRenderer,
    private val refresher: LedgerRefresher?,
    private val signer: () -> PackageSigner,
    private val now: () -> Long = System::currentTimeMillis,
    private val zone: () -> ZoneId = { ZoneId.systemDefault() }
) {
    private class Written(val name: String, val file: File, val sha256: String, val bytes: Long, val count: Long)

    private fun canonical(o: JsonObject): ByteArray = (PartnerJson.dumpsCompactSorted(o) + "\n").toByteArray(Charsets.UTF_8)

    suspend fun export(ownerId: String, sinceAcked: Boolean, myName: String, outDir: File): ExportResult {
        val block: suspend () -> ExportResult = {
            refresher?.refreshUnlocked()
            write(ownerId, sinceAcked, myName, outDir)
        }
        return refresher?.locked(block) ?: block()
    }

    private suspend fun write(ownerId: String, sinceAcked: Boolean, myName: String, outDir: File): ExportResult = withContext(Dispatchers.IO) {
        val c = PartnerCatalog.current
        val me = signer()
        store.partner(ownerId)?.takeIf { it.trusted } ?: throw IllegalStateException("not a trusted partner")
        val categories = c.categories.filter { it in store.grantedCategoriesOut(ownerId) }
        val fromRev = if (sinceAcked) store.syncState(ownerId)?.ackedRev ?: 0L else 0L
        val exportId = UUID.randomUUID().toString()
        outDir.mkdirs()
        val work = File(outDir, "tmp-$exportId").apply { mkdirs() }
        try {
            val written = ArrayList<Written>()
            var total = 0L
            for (cat in categories) {
                val f = File(work, "$cat.ndjson")
                val md = MessageDigest.getInstance("SHA-256")
                var bytes = 0L
                var count = 0L
                BufferedOutputStream(FileOutputStream(f)).use { out ->
                    var cursor = fromRev
                    while (true) {
                        val delta = store.ledgerDelta(cursor, listOf(cat), c.batchMax)
                        for (env in renderer.envelopes(delta)) {
                            val line = canonical(env)
                            out.write(line); md.update(line)
                            bytes += line.size; count++
                        }
                        if (!delta.hasMore) break
                        cursor = delta.toRev
                    }
                }
                if (count == 0L) { f.delete(); continue } // a granted-but-empty category has no file
                total += count
                written += Written("health/$cat.ndjson", f, PartnerKdf.hex(md.digest()), bytes, count)
            }
            val toRev = maxOf(fromRev, store.outboundRev())
            val profile = canonical(PartnerJson.obj(
                "device_id" to me.deviceId, "name" to com.ayuvo.health.partner.logic.PartnerQr.cleanName(myName), "platform" to "android",
                "fingerprint" to PartnerKdf.fingerprint(me.x25519Pub, me.ed25519Pub)
            ))
            val revision = canonical(PartnerJson.obj("from_rev" to fromRev, "to_rev" to toRev))
            fun sha(b: ByteArray) = PartnerKdf.hex(PartnerKdf.sha256(b))
            val files = listOf(
                PartnerJson.obj("name" to "profile/partner.json", "sha256" to sha(profile), "bytes" to profile.size, "count" to 1),
                PartnerJson.obj("name" to "sync/revision.json", "sha256" to sha(revision), "bytes" to revision.size, "count" to 1)
            ) + written.map { PartnerJson.obj("name" to it.name, "sha256" to it.sha256, "bytes" to it.bytes, "count" to it.count) }
            val manifest = canonical(PartnerJson.obj(
                "format" to c.packageFormat, "version" to c.packageVersion, "export_id" to exportId, "device_id" to me.deviceId,
                "recipient_device_id" to ownerId, "created_ms" to now(), "from_rev" to fromRev, "to_rev" to toRev,
                "categories" to categories, "files" to files
            ))
            val signature = canonical(PartnerJson.obj("alg" to "Ed25519", "key" to me.ed25519Pub, "sig" to PartnerKdf.b64urlEncode(me.sign(manifest))))
            val name = PartnerPackage.filename(myName, LocalDate.now(zone()).toString())
            val zipFile = File(outDir, name)
            ZipOutputStream(BufferedOutputStream(FileOutputStream(zipFile))).use { zip ->
                fun put(entry: String, data: ByteArray) {
                    zip.putNextEntry(ZipEntry(entry)); zip.write(data); zip.closeEntry()
                }
                put("manifest.json", manifest)
                put("signature.json", signature)
                put("profile/partner.json", profile)
                put("sync/revision.json", revision)
                for (w in written) {
                    zip.putNextEntry(ZipEntry(w.name))
                    w.file.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
            ExportResult(zipFile, exportId, fromRev, toRev, total)
        } finally {
            work.deleteRecursively()
        }
    }
}
