package com.ayuvo.health.partner

import com.ayuvo.health.partner.logic.PartnerJson
import com.ayuvo.health.partner.logic.PartnerProtocol
import com.ayuvo.health.partner.logic.RecordKey
import com.ayuvo.health.partner.sync.InMemoryFramedChannel
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** In-process sessions between two phones (docs/partner-sync.md §8–§12) over an in-memory FramedChannel pair. */
class PartnerSessionTest {
    private lateinit var a: TestPhone
    private lateinit var b: TestPhone

    @Before
    fun setUp() {
        PartnerTestFiles.install()
        a = TestPhone("Ananya")
        b = TestPhone("Rohan")
        TestPhone.pair(a, b)
        a.steps.put(Recs.steps("2026-10-06", 8000))
        a.steps.put(Recs.steps("2026-10-07", 4200))
        a.food.put(Recs.food("f1", "Oats", 320))
        a.meds.put(Recs.medication("m1", "Vitamin D3"))
        a.samples.put(Recs.heartRate("hr1", TEST_NOW - 60_000, 72))
        b.food.put(Recs.food("g1", "Dal", 410))
    }

    @Test
    fun initialSyncBothWaysThenEmptyDelta() = runBlocking {
        val (ra, rb) = TestPhone.sync(a, b)
        assertTrue("a: $ra", ra.ok)
        assertTrue("b: $rb", rb.ok)
        assertEquals(5, b.received(a).size)
        assertEquals(1, a.received(b).size)
        assertEquals(5, rb.received.inserted)
        assertEquals(a.store.outboundRev(), b.store.syncState(a.id)!!.lastRev)
        assertEquals(a.store.outboundRev(), a.store.syncState(b.id)!!.ackedRev)
        assertEquals("up_to_date", b.store.syncState(a.id)!!.status)
        assertEquals("network", b.store.syncState(a.id)!!.lastTransport)
        // Partner data never lands in my own sources.
        assertEquals(1, b.food.records.size)

        val (ra2, rb2) = TestPhone.sync(a, b)
        assertTrue(ra2.ok && rb2.ok)
        assertEquals(0, rb2.received.inserted + rb2.received.updated + rb2.received.duplicate)
        assertEquals(0, ra2.sentRecords)
    }

    @Test
    fun editAndTombstonePropagate() = runBlocking {
        TestPhone.sync(a, b)
        a.steps.put(Recs.steps("2026-10-07", 9100))
        val (_, rb) = TestPhone.sync(a, b)
        assertEquals(1, rb.received.updated)
        val row = b.received(a)[RecordKey("metric_day", "steps:2026-10-07")]!!
        assertEquals(9100L, PartnerJson.long(row.data["sum"]))

        a.food.records.remove("f1")
        val (_, rb2) = TestPhone.sync(a, b)
        assertEquals(1, rb2.received.deleted)
        assertNull(b.received(a)[RecordKey("food_entry", "f1")])
        assertEquals(4, b.received(a).size)
    }

    @Test
    fun revokedCategoryStopsAndKeepsData() = runBlocking {
        TestPhone.sync(a, b)
        a.store.setGrantsOut(b.id, mapOf("nutrition" to false), TEST_NOW)
        a.food.put(Recs.food("f2", "Rice", 200))
        val (_, rb) = TestPhone.sync(a, b)
        val grant = b.store.grantsReceived(a.id).first { it.category == "nutrition" }
        assertFalse(grant.granted)
        assertNotNull(grant.revokedMs)
        assertNotNull("received data is kept", b.received(a)[RecordKey("food_entry", "f1")])
        assertNull("new data of a revoked category is not sent", b.received(a)[RecordKey("food_entry", "f2")])
        assertEquals(0, rb.received.inserted)

        // Re-granting runs ledger_regrant: every row of the category gets a fresh rev and is offered again.
        a.store.setGrantsOut(b.id, mapOf("nutrition" to true), TEST_NOW)
        val (_, rb2) = TestPhone.sync(a, b)
        assertNotNull(b.received(a)[RecordKey("food_entry", "f2")])
        assertNull(b.store.grantsReceived(a.id).first { it.category == "nutrition" }.revokedMs)
        assertEquals(1, rb2.received.inserted)
        assertEquals(1, rb2.received.updated)
        assertEquals(2, b.received(a).values.count { it.category == "nutrition" })
    }

    @Test
    fun interruptedPullResumesWithoutDuplicates() = runBlocking {
        for (i in 0 until 1200) a.steps.put(Recs.steps(java.time.LocalDate.parse("2023-01-01").plusDays(i.toLong()).toString(), i))
        val total = 1200 + 5
        // A drops the connection after a few messages (HELLO, SYNC_REQ and roughly one CHANGES page).
        val (ra, rb) = TestPhone.sync(a, b) { ca, _ -> (ca as InMemoryFramedChannel).failAfterSends = 4 }
        assertFalse(ra.ok)
        assertFalse(rb.ok)
        val first = b.received(a).size
        assertTrue("some pages committed before the drop: $first", first in 0 until total)
        assertEquals("committed pages keep the cursor", first > 0, b.store.syncState(a.id)!!.lastRev > 0)

        val (_, rb2) = TestPhone.sync(a, b)
        assertTrue(rb2.ok)
        assertEquals(total, b.received(a).size)
        assertEquals(0, rb2.received.duplicate)
        assertEquals(total - first, rb2.received.inserted)
    }

    @Test
    fun helloFromAnotherDeviceIsNotTrusted() = runBlocking {
        val c = TestPhone("Mallory")
        TestPhone.pair(c, b)
        // b believes it is talking to a (the transport authenticated a), but c says HELLO.
        val (ca, cb) = InMemoryFramedChannel.pair()
        val results = coroutineScope {
            val rc = async { c.session(b).run(ca) }
            val rb = async { b.session(a).run(cb) }
            rc.await() to rb.await()
        }
        assertEquals("not_trusted", results.second.error)
        assertTrue(b.received(a).isEmpty())
    }

    @Test
    fun inboundMessagesFollowTheSessionScript() = runBlocking {
        val log = mutableListOf<kotlinx.serialization.json.JsonObject>()
        val (ca, cb) = InMemoryFramedChannel.pair()
        a.refresher.refresh(); b.refresher.refresh()
        val spy = object : com.ayuvo.health.partner.sync.FramedChannel by cb {
            override suspend fun receive(timeoutMs: Long) = cb.receive(timeoutMs).also { synchronized(log) { log += it } }
        }
        coroutineScope {
            val x = async { a.session(b).run(ca) }
            val y = async { b.session(a).run(spy) }
            x.await(); y.await()
        }
        val check = PartnerProtocol.sessionScript(log)
        assertTrue("$check", check.ok)
    }
}
