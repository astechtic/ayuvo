package com.ayuvo.health.partner

import com.ayuvo.health.partner.logic.RecordKey
import com.ayuvo.health.partner.sources.SourceRecord
import com.ayuvo.health.partner.sync.LedgerRefresher
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** docs/partner-sync.md §10 scopes: full first run, then window / intraday scopes; tombstones only in scope. */
class LedgerRefresherTest {
    private lateinit var p: TestPhone

    @Before
    fun setUp() {
        PartnerTestFiles.install()
        p = TestPhone("Ananya")
    }

    private fun row(r: SourceRecord) = p.store.ledger[RecordKey(r.type, r.recordId)]

    @Test
    fun fullRunThenWindowAndTombstones() = runBlocking {
        val old = Recs.steps("2026-08-01", 5000)      // > 30 days ago
        val recent = Recs.steps("2026-10-01", 6000)   // inside the 30-day window
        val food = Recs.food("f1", "Oats", 320, "2025-01-01")
        val hrOld = Recs.heartRate("hr-old", TEST_NOW - 9 * 86_400_000L, 60, "2026-09-28")
        val hrNew = Recs.heartRate("hr-new", TEST_NOW - 3_600_000L, 70)
        listOf(old, recent).forEach(p.steps::put)
        p.food.put(food)
        listOf(hrOld, hrNew).forEach(p.samples::put)

        val first = p.refresher.refresh()
        assertNotNull(p.store.meta(LedgerRefresher.META_FULL_DONE))
        assertNotNull("full run covers all history", row(old))
        assertNull("intraday types only ever see the last 7 days", row(hrOld))
        assertEquals(4, first.changed)
        assertEquals(4L, p.store.outboundRev())

        // Unchanged → no new revs.
        assertEquals(0, p.refresher.refresh().changed)

        // Window run: an old day that vanished is out of scope (kept); a recent one becomes a tombstone.
        p.steps.records.clear()
        p.food.records.clear()
        val second = p.refresher.refresh()
        assertFalse(row(old)!!.deleted)
        assertTrue(row(recent)!!.deleted)
        assertTrue("full-scope food is always in scope", row(food)!!.deleted)
        assertEquals(2, second.tombstoned)
        assertEquals(6L, p.store.outboundRev())

        // An edit gets the next rev.
        p.samples.put(Recs.heartRate("hr-new", TEST_NOW - 3_600_000L, 75))
        p.refresher.refresh()
        assertEquals(7L, row(hrNew)!!.rev)
    }

    @Test
    fun contentHashMatchesTheCanonicalJsonHash() {
        val nested = SourceRecord(
            "report_overview", "r1", "records", null,
            com.ayuvo.health.partner.logic.PartnerJson.obj(
                "title" to "Blutbild \"CBC\"\n\t\u0001 – 血液 😀", "z" to null, "a" to listOf(1, 2.5, "x", mapOf("b" to true, "a" to false)),
                "ä" to 1, "Z" to -0.0
            )
        )
        for (r in listOf(Recs.steps("2026-10-01", 6000), Recs.food("f1", "Oats", 320), Recs.medication("m1", "D3"), nested)) {
            assertEquals(SourceRecord.referenceHash(r), r.contentHash)
        }
    }

    @Test
    fun unavailableSourceIsSkippedNotTombstoned() = runBlocking {
        p.food.put(Recs.food("f1", "Oats", 320))
        p.steps.put(Recs.steps("2026-10-01", 6000))
        p.food.fail = true
        val r = p.refresher.refresh()
        assertEquals(listOf("food_entry"), r.failedTypes)
        assertNull("the full run is not done until every type succeeded", p.store.meta(LedgerRefresher.META_FULL_DONE))
        p.food.fail = false
        p.refresher.refresh()
        assertNotNull(p.store.meta(LedgerRefresher.META_FULL_DONE))
        p.food.fail = true
        p.refresher.refresh()
        assertFalse("a failing store never tombstones what it holds", row(Recs.food("f1", "Oats", 320))!!.deleted)
    }
}
