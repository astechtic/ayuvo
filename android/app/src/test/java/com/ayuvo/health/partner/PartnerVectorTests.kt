package com.ayuvo.health.partner

import com.ayuvo.health.partner.logic.LedgerRow
import com.ayuvo.health.partner.logic.PartnerJson
import com.ayuvo.health.partner.logic.PartnerLedger
import com.ayuvo.health.partner.logic.PartnerMerge
import com.ayuvo.health.partner.logic.PartnerPackage
import com.ayuvo.health.partner.logic.RecordKey
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** One JUnit test per shared partner vector file (docs/partner-sync.md §16). */
class PartnerQrVectorsTest { @Test fun allCases() = PartnerVectors.assertAll("qr.json") }
class PartnerKdfVectorsTest { @Test fun allCases() = PartnerVectors.assertAll("kdf.json") }
class PartnerEnvelopeVectorsTest { @Test fun allCases() = PartnerVectors.assertAll("envelope_validate.json") }
class PartnerMergeVectorsTest { @Test fun allCases() = PartnerVectors.assertAll("merge_apply.json") }
class PartnerGrantsVectorsTest { @Test fun allCases() = PartnerVectors.assertAll("grants_received.json") }
class PartnerRetentionVectorsTest { @Test fun allCases() = PartnerVectors.assertAll("retention_prune.json") }
class PartnerLedgerVectorsTest { @Test fun allCases() = PartnerVectors.assertAll("ledger.json") }
class PartnerMapVectorsTest { @Test fun allCases() = PartnerVectors.assertAll("map.json") }
class PartnerMessageVectorsTest { @Test fun allCases() = PartnerVectors.assertAll("message_validate.json") }
class PartnerSessionVectorsTest { @Test fun allCases() = PartnerVectors.assertAll("session.json") }
class PartnerPackageVectorsTest { @Test fun allCases() = PartnerVectors.assertAll("package.json") }
class PartnerSummaryVectorsTest { @Test fun allCases() = PartnerVectors.assertAll("summary_metrics.json") }

/** Files that have a runner (noise_* are replayed by NoiseVectorTests). */
val PARTNER_VECTOR_FILES_WITH_RUNNERS = listOf(
    "qr.json", "kdf.json", "envelope_validate.json", "merge_apply.json", "grants_received.json", "retention_prune.json",
    "ledger.json", "map.json", "message_validate.json", "session.json", "package.json", "summary_metrics.json",
    "noise_ikpsk2.json", "noise_kk.json"
)

/** Every vector file in shared/partner/test-vectors has a runner (and every runner a file). */
class PartnerVectorCoverageTest {
    @Test
    fun everyVectorFileIsRun() {
        val dir = PartnerTestFiles.shared("test-vectors")
        assertNotNull("shared/partner/test-vectors not found", dir)
        val files = dir!!.listFiles { f: File -> f.name.endsWith(".json") }.orEmpty().map { it.name }.sorted()
        assertEquals(PARTNER_VECTOR_FILES_WITH_RUNNERS.sorted(), files)
    }
}

/** docs §8: re-applying any accepted batch on top of its own result changes nothing (contract check 5). */
class PartnerMergeIdempotencyTest {
    @Test
    fun acceptedBatchesAreIdempotent() {
        PartnerTestFiles.install()
        val (_, cases) = PartnerVectors.cases("merge_apply.json")
        var checked = 0
        for (case in cases) {
            val input = case["input"] as JsonObject
            val granted = (input["granted"] as kotlinx.serialization.json.JsonArray).mapNotNull { PartnerJson.str(it) }
            val now = PartnerJson.long(input["now_ms"])!!
            val first = PartnerMerge.apply(PartnerVectors.existing(input["existing"]), input["batch"] as JsonObject,
                PartnerJson.long(input["cursor"])!!, granted, now)
            if (!first.accepted) continue
            val stored = LinkedHashMap(PartnerVectors.existing(input["existing"]))
            first.deletes.forEach { stored.remove(it) }
            first.upserts.forEach { stored[it.key] = it.rev }
            val again = PartnerMerge.apply(stored, input["batch"] as JsonObject, first.cursor, granted, now)
            val name = PartnerJson.str(case["name"])
            assertTrue("$name: replay changed something", again.accepted && !again.hasChanges)
            assertEquals("$name: cursor moved on replay", first.cursor, again.cursor)
            checked++
        }
        assertTrue(checked > 10)
    }

    /** Re-importing a package on top of its own result (cursor committed) only yields duplicates/stale. */
    @Test
    fun packageImportIsIdempotent() {
        PartnerTestFiles.install()
        val (_, cases) = PartnerVectors.cases("package.json")
        var checked = 0
        for (case in cases) {
            val input = case["input"] as JsonObject
            if (PartnerJson.str(input["op"]) != "import") continue
            val batch = PartnerJson.long(input["batch_size"])?.toInt() ?: 500
            val first = PartnerPackage.import(PartnerVectors.existing(input["existing"]), PartnerJson.long(input["cursor"])!!,
                input["manifest"] as JsonObject, input["files"] as JsonObject, PartnerJson.long(input["now_ms"])!!, batch)
            if (!first.accepted) continue
            val stored = LinkedHashMap(PartnerVectors.existing(input["existing"]))
            first.deletes.forEach { stored.remove(it) }
            first.upserts.forEach { stored[it.key] = it.rev }
            val again = PartnerPackage.import(stored, first.cursor, input["manifest"] as JsonObject, input["files"] as JsonObject,
                PartnerJson.long(input["now_ms"])!!, batch)
            assertTrue(again.accepted && !again.hasChanges)
            checked++
        }
        assertTrue(checked > 0)
    }
}

/** RecordKey ordering is Unicode scalar (UTF-8 byte) order, not UTF-16 code-unit order. */
class PartnerOrderingTest {
    @Test
    fun recordKeysSortByCodePoints() {
        // U+FF5E (BMP, high) vs U+1F600 (astral, surrogate pair 0xD83D...): UTF-16 puts the emoji first.
        val bmp = RecordKey("t", "～")
        val astral = RecordKey("t", "😀")
        assertTrue("😀" < "～")
        assertEquals(listOf(bmp, astral), listOf(astral, bmp).sortedWith(RecordKey.ORDER))
        assertEquals(listOf("a", "ab", "b"), listOf("b", "ab", "a").sortedWith(PartnerJson.CODE_POINT_ORDER))
        // UTF-8 byte comparison agrees.
        val a = "～".toByteArray(Charsets.UTF_8).map { it.toInt() and 0xFF }
        val b = "😀".toByteArray(Charsets.UTF_8).map { it.toInt() and 0xFF }
        assertTrue(a.first() < b.first())
    }
}

/** docs §10: delta pages are bounded and revs strictly increase across 100,000 ledger rows. */
class PartnerLedgerPagingTest {
    @Test
    fun hundredThousandRowsPageInOrder() {
        PartnerTestFiles.install()
        val cats = listOf("vitals", "sleep", "nutrition", "workouts", "medicines", "report_overviews")
        val n = 100_000
        // Revs shuffled through the list so the sort is exercised; every 7th row is in an ungranted category.
        val ledger = (1..n).map { i ->
            val rev = ((i.toLong() * 7919L) % n) + 1
            LedgerRow("sample", "id-$i", cats[(rev % cats.size).toInt()], "2026-10-07", "h$i", rev, i % 13 == 0)
        }
        val grants = listOf("vitals", "sleep", "medicines")
        val counter = n.toLong()
        var cursor = 0L
        var lastRev = 0L
        var seen = 0
        var pages = 0
        while (true) {
            val page = PartnerLedger.delta(ledger, counter, cursor, grants, 500)
            pages++
            assertTrue(page.keys.size <= 500)
            assertEquals(cursor, page.fromRev)
            for (k in page.keys) {
                assertTrue("revs strictly increase", k.rev > lastRev)
                lastRev = k.rev
            }
            seen += page.keys.size
            if (page.hasMore) assertEquals(page.keys.last().rev, page.toRev) else assertEquals(counter, page.toRev)
            assertTrue(page.toRev > cursor || !page.hasMore)
            cursor = page.toRev
            if (!page.hasMore) break
            assertTrue("runaway paging", pages < 1000)
        }
        assertEquals(ledger.count { it.category in grants }, seen)
        assertFalse(PartnerLedger.delta(ledger, counter, cursor, grants, 500).hasMore)
        assertEquals(0, PartnerLedger.delta(ledger, counter, cursor, grants, 500).keys.size)
    }
}
