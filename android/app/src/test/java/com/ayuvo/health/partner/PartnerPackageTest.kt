package com.ayuvo.health.partner

import com.ayuvo.health.partner.data.Partner
import com.ayuvo.health.partner.identity.DeviceIdentity
import com.ayuvo.health.partner.logic.PartnerJson
import com.ayuvo.health.partner.logic.PartnerKdf
import com.ayuvo.health.partner.logic.PartnerPackage
import com.ayuvo.health.partner.pkg.PackageInspection
import com.ayuvo.health.partner.pkg.PackageSigner
import com.ayuvo.health.partner.pkg.PartnerPackageExporter
import com.ayuvo.health.partner.pkg.PartnerPackageImporter
import com.ayuvo.health.records.processing.RecordsVectors
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * `.ayuvo.zip` (docs/partner-sync.md §13): the shared signed fixture (scripts/partner_fixture.py) and an
 * export → import round trip.
 */
class PartnerPackageTest {
    @get:Rule val tmp = TemporaryFolder()

    private lateinit var fixture: JsonObject
    private lateinit var zip: File
    private val sender get() = PartnerJson.str(fixture["sender_device_id"])!!
    private val recipient get() = PartnerJson.str(fixture["recipient_device_id"])!!
    private val now get() = PartnerJson.long(fixture["now_ms"])!!

    @Before
    fun setUp() {
        PartnerTestFiles.install()
        fixture = PartnerJson.parse(PartnerTestFiles.shared("fixtures/partner-sample.json")!!.readText()) as JsonObject
        zip = tmp.newFile("partner-sample.ayuvo.zip")
        PartnerTestFiles.shared("fixtures/partner-sample.ayuvo.zip")!!.copyTo(zip, overwrite = true)
    }

    private fun senderPartner(ed: String = PartnerJson.str(fixture["sender_ed25519"])!!) = Partner(
        ownerId = sender, displayName = "Ananya", fingerprint = PartnerJson.str(fixture["sender_fingerprint"])!!,
        x25519Pub = PartnerJson.str(fixture["sender_x25519"])!!, ed25519Pub = ed, platform = "android",
        pairedMs = now, updatedMs = now
    )

    private fun store(withSender: Boolean = true, ed: String? = null) = InMemoryPartnerStore().apply {
        if (withSender) upsertPartner(if (ed != null) senderPartner(ed) else senderPartner())
    }

    private fun importer(store: InMemoryPartnerStore) = PartnerPackageImporter(store, { recipient }) { now }

    private fun expected(key: String) = (fixture["expected"] as JsonObject)[key]!!

    @Test
    fun fixtureSignatureVerifiesWithSenderKey() {
        ZipFile(zip).use { z ->
            val manifest = z.getInputStream(z.getEntry("manifest.json")).readBytes()
            val sig = PartnerJson.parse(z.getInputStream(z.getEntry("signature.json")).readBytes().toString(Charsets.UTF_8)) as JsonObject
            assertEquals(PartnerJson.str(fixture["sender_ed25519"]), PartnerJson.str(sig["key"]))
            assertTrue(DeviceIdentity.verify(PartnerJson.str(fixture["sender_ed25519"])!!, manifest, PartnerKdf.b64urlDecode(PartnerJson.str(sig["sig"]))!!))
            assertEquals("manifest.json", z.entries().toList().first().name)
        }
    }

    @Test
    fun fixtureImportsToRecordedCountsThenAllDuplicates() = runBlocking {
        val s = store()
        val imp = importer(s)
        val inspection = imp.inspect(zip)
        assertTrue("$inspection", inspection is PackageInspection.Ready)
        val preview = (inspection as PackageInspection.Ready).preview
        assertNull(RecordsVectors.diff(expected("summary"), preview.summary.toJson(), "$"))
        assertEquals("Ananya", preview.senderName)

        val first = imp.import(preview)
        assertTrue(first.ok)
        val e1 = expected("first_import") as JsonObject
        assertNull(RecordsVectors.diff(e1["counts"]!!, first.counts.toJson(), "$"))
        assertEquals(PartnerJson.long(e1["cursor"]), s.syncState(sender)!!.lastRev)
        assertEquals("package", s.syncState(sender)!!.lastTransport)
        assertEquals("3c3c3c3c-1111-4222-8333-444455556666", s.syncState(sender)!!.lastExportId)

        val second = imp.import(preview)
        val e2 = expected("second_import") as JsonObject
        assertNull(RecordsVectors.diff(e2["counts"]!!, second.counts.toJson(), "$"))
        assertEquals(PartnerJson.long(e2["cursor"]), s.syncState(sender)!!.lastRev)
        assertTrue(second.alreadyUpToDate)
    }

    /** Rewrites the fixture with [edit] applied to one entry's bytes (the zip itself stays well-formed). */
    private fun rewrite(name: String, edit: (ByteArray) -> ByteArray): File {
        val out = tmp.newFile()
        ZipFile(zip).use { z ->
            ZipOutputStream(out.outputStream()).use { zo ->
                for (e in z.entries()) {
                    var data = z.getInputStream(e).readBytes()
                    if (e.name == name) data = edit(data)
                    zo.putNextEntry(ZipEntry(e.name)); zo.write(data); zo.closeEntry()
                }
            }
        }
        return out
    }

    @Test
    fun flippedByteIsHashMismatch() = runBlocking {
        val tampered = rewrite("health/vitals.ndjson") { b -> b.copyOf().also { it[10] = (it[10].toInt() xor 0x01).toByte() } }
        val r = importer(store()).inspect(tampered)
        assertEquals(PackageInspection.Invalid("hash_mismatch", "Ananya"), r)
    }

    @Test
    fun wrongKeyIsBadSignature() = runBlocking {
        val other = DeviceIdentity(MemorySecrets()).ed25519Public
        val r = importer(store(ed = other)).inspect(zip)
        assertTrue("$r", r is PackageInspection.Invalid && r.code == "bad_signature")
    }

    @Test
    fun unknownSenderIsRejected() = runBlocking {
        val r = importer(store(withSender = false)).inspect(zip)
        assertTrue("$r", r is PackageInspection.Invalid && r.code == "unknown_sender")
    }

    @Test
    fun foreignZipIsNotAPackage() = runBlocking {
        val f = tmp.newFile("report.zip")
        ZipOutputStream(f.outputStream()).use { zo -> zo.putNextEntry(ZipEntry("scan.pdf")); zo.write(byteArrayOf(1, 2, 3)); zo.closeEntry() }
        val imp = importer(store())
        assertTrue(!imp.isPartnerPackage(f))
        assertEquals(PackageInspection.NotPackage, imp.inspect(f))
        assertTrue(imp.isPartnerPackage(zip))
    }

    @Test
    fun exportImportRoundTripWithAllowListedEntries() = runBlocking {
        val a = TestPhone("Ananya Rao")
        val b = TestPhone("Rohan")
        TestPhone.pair(a, b, grants = listOf("vitals", "nutrition", "medicines"))
        a.steps.put(Recs.steps("2026-10-07", 4200))
        a.food.put(Recs.food("f1", "Oats", 320))
        a.food.put(Recs.food("f2", "Dal", 410))
        a.meds.put(Recs.medication("m1", "Vitamin D3"))
        a.samples.put(Recs.heartRate("hr1", TEST_NOW - 60_000, 72))
        val exporter = PartnerPackageExporter(a.store, a.renderer, a.refresher, {
            PackageSigner(a.id, a.identity.x25519Public, a.identity.ed25519Public) { a.identity.sign(it) }
        }, now = { TEST_NOW })
        val result = exporter.export(b.id, sinceAcked = false, myName = "Ananya Rao", outDir = tmp.newFolder("partner-export"))
        assertTrue(result.file.name, result.file.name.startsWith("Ananya_Rao_Health_") && result.file.name.endsWith(".ayuvo.zip"))
        assertEquals(5L, result.records)

        ZipFile(result.file).use { z ->
            val names = z.entries().toList().map { it.name }
            assertEquals("manifest.json", names.first())
            val allowed = PartnerPackage.entryNames(listOf("vitals", "nutrition", "medicines")).toSet()
            assertTrue("$names", names.all { it in allowed })
            assertEquals(listOf("manifest.json", "signature.json", "profile/partner.json", "sync/revision.json",
                "health/vitals.ndjson", "health/nutrition.ndjson", "health/medicines.ndjson"), names)
        }

        val imp = PartnerPackageImporter(b.store, { b.id }) { TEST_NOW }
        val preview = (imp.inspect(result.file) as PackageInspection.Ready).preview
        assertEquals(5L, preview.summary.total)
        val outcome = imp.import(preview)
        assertTrue(outcome.ok)
        assertEquals(5, outcome.counts.inserted)
        assertEquals(5, b.received(a).size)
        assertEquals(a.store.outboundRev(), b.store.syncState(a.id)!!.lastRev)

        // "Changes since last sync" after a's acked_rev moved: only the edit travels.
        a.store.updateSyncState(a.store.syncState(b.id)!!.copy(ackedRev = a.store.outboundRev()))
        a.food.put(Recs.food("f1", "Oats with milk", 350))
        val delta = exporter.export(b.id, sinceAcked = true, myName = "Ananya Rao", outDir = tmp.newFolder("partner-export-2"))
        assertEquals(1L, delta.records)
        val o2 = imp.import((imp.inspect(delta.file) as PackageInspection.Ready).preview)
        assertEquals(1, o2.counts.updated)
    }

    @Test
    fun packageForSomeoneElseIsWrongRecipient() = runBlocking {
        val s = store()
        val r = PartnerPackageImporter(s, { "00000000-0000-4000-8000-000000000000" }) { now }.inspect(zip)
        assertTrue("$r", r is PackageInspection.Invalid && r.code == "wrong_recipient")
    }
}
