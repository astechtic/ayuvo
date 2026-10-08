package com.ayuvo.health.partner

import com.ayuvo.health.partner.net.DialOutcome
import com.ayuvo.health.partner.net.Endpoint
import com.ayuvo.health.partner.net.PartnerLink
import com.ayuvo.health.partner.sync.LocalPeer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket

/** Real TCP on 127.0.0.1 with Noise KK framing (no NSD): docs/partner-sync.md §5. */
class PartnerLoopbackTest {
    private lateinit var a: TestPhone
    private lateinit var b: TestPhone

    private fun link(p: TestPhone) = PartnerLink(p.store, { p.identity.noiseStatic }, p.renderer) { LocalPeer(p.id, appVersion = "test", name = p.name) }

    @Before
    fun setUp() {
        PartnerTestFiles.install()
        a = TestPhone("Ananya")
        b = TestPhone("Rohan")
        TestPhone.pair(a, b)
        a.steps.put(Recs.steps("2026-10-07", 4200))
        a.food.put(Recs.food("f1", "Oats", 320))
        b.meds.put(Recs.medication("m1", "Metformin"))
    }

    @Test
    fun kkSessionOverLoopback() = runBlocking {
        a.refresher.refresh(); b.refresher.refresh()
        ServerSocket(0, 4, InetAddress.getByName("127.0.0.1")).use { server ->
            val accepted = async(Dispatchers.IO) { link(a).accept(server.accept()) }
            val dial = withTimeout(30_000) { link(b).dial(Endpoint("127.0.0.1", server.localPort), a.asPartner()) }
            val inbound = accepted.await()
            assertTrue("dial: $dial", dial is DialOutcome.Synced && dial.result.ok)
            assertEquals(b.id, inbound!!.first)
            assertTrue(inbound.second.ok)
        }
        assertEquals(2, b.received(a).size)
        assertEquals(1, a.received(b).size)
        // The initiator remembers the address that completed a session (direct-dial fallback).
        assertEquals("127.0.0.1", b.store.partner(a.id)!!.lastHost)
    }

    @Test
    fun untrustedInitiatorIsRejectedBeforeAnything() = runBlocking {
        val mallory = TestPhone("Mallory")
        // Mallory knows a's public key (e.g. from an old QR) but a never paired with her.
        mallory.store.upsertPartner(a.asPartner())
        mallory.store.setGrantsOut(a.id, TestPhone.ALL.associateWith { true }, TEST_NOW)
        a.refresher.refresh()
        ServerSocket(0, 4, InetAddress.getByName("127.0.0.1")).use { server ->
            val accepted = async(Dispatchers.IO) { link(a).accept(server.accept()) }
            val dial = withTimeout(30_000) { link(mallory).dial(Endpoint("127.0.0.1", server.localPort), a.asPartner()) }
            assertNull(accepted.await())
            assertEquals(DialOutcome.NotThisPartner, dial)
        }
        assertTrue(mallory.received(a).isEmpty())
        assertTrue(a.received(mallory).isEmpty())
    }

    @Test
    fun unpairedPartnerIsRejected() = runBlocking {
        a.store.unpair(b.id, TEST_NOW)
        a.refresher.refresh(); b.refresher.refresh()
        ServerSocket(0, 4, InetAddress.getByName("127.0.0.1")).use { server ->
            val accepted = async(Dispatchers.IO) { link(a).accept(server.accept()) }
            val dial = withTimeout(30_000) { link(b).dial(Endpoint("127.0.0.1", server.localPort), a.asPartner()) }
            assertNull(accepted.await())
            assertEquals(DialOutcome.NotThisPartner, dial)
        }
        assertTrue(b.received(a).isEmpty())
    }
}
