package com.ayuvo.health.partner

import com.ayuvo.health.partner.data.PartnerSyncState
import com.ayuvo.health.partner.logic.RecordKey
import com.ayuvo.health.partner.sync.InMemoryFramedChannel
import com.ayuvo.health.partner.sync.LedgerRefresher
import com.ayuvo.health.partner.sync.PartnerSyncCoordinator
import com.ayuvo.health.partner.sync.SessionResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * docs/partner-sync.md §10–§11: a window's ledger refresh runs alongside discovery; sessions wait for it (bounded)
 * before serving and otherwise serve the ledger as it stands; a refresh finished < 2 min ago is skipped. Also the
 * stale transient status a killed window leaves behind.
 */
class PartnerRefreshConcurrencyTest {
    private lateinit var a: TestPhone
    private lateinit var b: TestPhone

    @Before
    fun setUp() {
        PartnerTestFiles.install()
        a = TestPhone("Ananya")
        b = TestPhone("Rohan")
        TestPhone.pair(a, b)
        a.food.put(Recs.food("f1", "Oats", 320))
        a.steps.put(Recs.steps("2026-10-06", 8000))
    }

    /** One session pair; a's serving waits for a's in-flight refresh (at most [waitMs]). No refresh is run here. */
    private suspend fun sessions(waitMs: Long): Pair<SessionResult, SessionResult> = coroutineScope {
        val (ca, cb) = InMemoryFramedChannel.pair()
        val ra = async { a.session(b, awaitLedger = { a.refresher.awaitInFlight() }, ledgerWaitMs = waitMs).run(ca) }
        val rb = async { b.session(a).run(cb) }
        ra.await() to rb.await()
    }

    @Test
    fun sessionDuringLongRefreshServesThePreRefreshLedger() = runBlocking {
        a.refresher.refresh(); b.refresher.refresh()
        val before = a.store.outboundRev()
        a.food.put(Recs.food("f2", "Rice", 200))
        val gate = CompletableDeferred<Unit>()
        a.food.gate = gate
        a.clock += LedgerRefresher.FRESH_MS // the setup refresh is no longer fresh
        val inFlight = a.refresher.refreshInBackground(this)
        assertNotNull(inFlight)
        assertTrue(a.refresher.isRefreshing)

        val (ra, rb) = sessions(waitMs = 200)
        assertTrue("a: $ra", ra.ok)
        assertTrue("b: $rb", rb.ok)
        assertTrue("the refresh is still running", a.refresher.isRefreshing)
        assertNotNull(b.received(a)[RecordKey("food_entry", "f1")])
        assertNull("not in the ledger yet", b.received(a)[RecordKey("food_entry", "f2")])
        assertEquals("the cursor is the pre-refresh ledger's", before, b.store.syncState(a.id)!!.lastRev)

        gate.complete(Unit)
        inFlight!!.await()
        a.food.gate = null
        // The next session carries the newer change.
        val (ra2, rb2) = sessions(waitMs = 200)
        assertTrue(ra2.ok && rb2.ok)
        assertEquals(1, rb2.received.inserted)
        assertNotNull(b.received(a)[RecordKey("food_entry", "f2")])
    }

    @Test
    fun sessionWaitsForAShortRefresh() = runBlocking {
        a.refresher.refresh(); b.refresher.refresh()
        a.food.put(Recs.food("f2", "Rice", 200))
        val gate = CompletableDeferred<Unit>()
        a.food.gate = gate
        a.clock += LedgerRefresher.FRESH_MS
        assertNotNull(a.refresher.refreshInBackground(this))
        launch { delay(150); gate.complete(Unit) }

        val (ra, rb) = sessions(waitMs = 10_000)
        assertTrue(ra.ok && rb.ok)
        assertFalse(a.refresher.isRefreshing)
        assertNotNull("served after the refresh finished", b.received(a)[RecordKey("food_entry", "f2")])
        assertEquals(a.store.outboundRev(), b.store.syncState(a.id)!!.lastRev)
    }

    @Test
    fun refreshFinishedUnderTwoMinutesAgoIsSkipped() = runBlocking {
        a.refresher.refresh()
        assertEquals(TEST_NOW, a.refresher.lastCompletedMs)
        a.clock = TEST_NOW + LedgerRefresher.FRESH_MS - 1
        assertNull("fresh: no refresh started", a.refresher.refreshInBackground(this))
        a.refresher.awaitInFlight() // nothing in flight: returns at once

        a.clock = TEST_NOW + LedgerRefresher.FRESH_MS
        a.food.put(Recs.food("f2", "Rice", 200))
        val job = a.refresher.refreshInBackground(this)
        assertNotNull("stale: a refresh starts", job)
        assertEquals(1, job!!.await()!!.changed)

        // A second request while one runs joins it instead of starting another.
        a.clock += LedgerRefresher.FRESH_MS
        val gate = CompletableDeferred<Unit>()
        a.food.gate = gate
        val first = a.refresher.refreshInBackground(this)
        assertTrue(first === a.refresher.refreshInBackground(this))
        gate.complete(Unit)
        first!!.await()
        Unit
    }

    @Test
    fun killedWindowStatusBecomesPartnerUnavailable() {
        val c = TestPhone("Meera")
        TestPhone.pair(a, c)
        val keep = a.store.syncState(b.id)!!.copy(status = "syncing", lastSyncMs = 1234L, lastError = "incomplete", lastRev = 9)
        a.store.updateSyncState(keep)
        a.store.updateSyncState(a.store.syncState(c.id)!!.copy(status = "up_to_date", lastSyncMs = 99L))

        assertEquals(1, PartnerSyncCoordinator.settleStaleStatus(a.store))
        val s: PartnerSyncState = a.store.syncState(b.id)!!
        assertEquals("partner_unavailable", s.status)
        assertEquals("last_sync_ms kept", 1234L, s.lastSyncMs)
        assertEquals("last_error kept", "incomplete", s.lastError)
        assertEquals(9L, s.lastRev)
        assertEquals("up_to_date", a.store.syncState(c.id)!!.status)

        a.store.updateSyncState(a.store.syncState(c.id)!!.copy(status = "connecting"))
        assertEquals("a live session is left alone", 0, PartnerSyncCoordinator.settleStaleStatus(a.store) { it == c.id })
        assertEquals("connecting", a.store.syncState(c.id)!!.status)
        assertEquals(1, PartnerSyncCoordinator.settleStaleStatus(a.store))
        assertEquals("partner_unavailable", a.store.syncState(c.id)!!.status)
    }
}
