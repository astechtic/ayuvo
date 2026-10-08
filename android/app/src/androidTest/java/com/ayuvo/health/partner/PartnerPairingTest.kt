package com.ayuvo.health.partner

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ayuvo.health.partner.data.PartnerDatabase
import com.ayuvo.health.partner.data.SqlitePartnerStore
import com.ayuvo.health.partner.identity.DeviceIdentity
import com.ayuvo.health.partner.identity.PartnerSecretStore
import com.ayuvo.health.partner.logic.PartnerCatalog
import com.ayuvo.health.partner.logic.PartnerQr
import com.ayuvo.health.partner.net.PairConfirmResult
import com.ayuvo.health.partner.net.PartnerPairing
import com.ayuvo.health.partner.sync.LocalPeer
import com.ayuvo.health.partner.sync.OutboundRenderer
import com.ayuvo.health.partner.sync.PartnerSession
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** QR pairing on the emulator (docs/partner-sync.md §4): Show code ↔ Scan code, same SAS, PAIR_CONFIRM, first session. */
@RunWith(AndroidJUnit4::class)
class PartnerPairingTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private inner class Phone(dbName: String) {
        val db = dbName
        val helper = PartnerDatabase(context, dbName)
        val store = SqlitePartnerStore(helper)
        val identity = DeviceIdentity(object : PartnerSecretStore {
            val m = HashMap<String, String>()
            override fun load(key: String) = m[key]
            override fun save(key: String, value: String) { m[key] = value }
        })
    }

    private lateinit var a: Phone
    private lateinit var b: Phone

    @Before
    fun setUp() {
        PartnerCatalog.install(context)
        listOf(DB_A, DB_B).forEach { PartnerDatabase.deleteDatabaseFiles(context, it) }
        a = Phone(DB_A); b = Phone(DB_B)
    }

    @After
    fun tearDown() {
        a.helper.close(); b.helper.close()
        listOf(DB_A, DB_B).forEach { PartnerDatabase.deleteDatabaseFiles(context, it) }
    }

    @Test
    fun showScanCompareConfirmAndFirstSession() = runBlocking {
        val pa = PartnerPairing(context, a.store, a.identity, this)
        val pb = PartnerPairing(context, b.store, b.identity, this)
        val shown = pa.showCode("Ananya")
        val qr = PartnerQr.parse(shown.qrText, System.currentTimeMillis(), b.identity.deviceId)
        assertTrue("${qr.error}", qr.ok)
        assertTrue(qr.payload!!.hosts.isNotEmpty())

        val scanned = async { pb.scan(shown.qrText).getOrThrow() }
        val atA = withTimeout(30_000) { shown.connections.receive() }
        val atB = scanned.await()
        assertEquals("both screens show the same code", atA.sas, atB.sas)

        val ca = async { pa.confirm(atA, true, listOf("vitals", "sleep"), "Ananya") }
        val cb = async { pb.confirm(atB, true, listOf("medicines"), "Rohan") }
        val ra = ca.await() as PairConfirmResult.Paired
        val rb = cb.await() as PairConfirmResult.Paired
        assertEquals(b.identity.deviceId, ra.partner.ownerId)
        assertEquals("Rohan", ra.partner.displayName)
        assertEquals(b.identity.x25519Public, ra.partner.x25519Pub)
        assertEquals(a.identity.ed25519Public, rb.partner.ed25519Pub)
        assertEquals(listOf("vitals", "sleep"), a.store.grantedCategoriesOut(b.identity.deviceId))
        assertEquals(listOf("medicines"), b.store.grantedCategoriesOut(a.identity.deviceId))

        // The first session runs on the same connection.
        val sa = async { PartnerSession(a.store, OutboundRenderer(a.store, emptyList()), LocalPeer(a.identity.deviceId, appVersion = "t"), b.identity.deviceId).run(ra.channel) }
        val sb = async { PartnerSession(b.store, OutboundRenderer(b.store, emptyList()), LocalPeer(b.identity.deviceId, appVersion = "t"), a.identity.deviceId).run(rb.channel) }
        assertTrue(sa.await().ok)
        assertTrue(sb.await().ok)
        assertEquals("up_to_date", b.store.syncState(a.identity.deviceId)!!.status)
        shown.close()
    }

    @Test
    fun wrongTokenFailsTheHandshake() = runBlocking {
        val pa = PartnerPairing(context, a.store, a.identity, this)
        val pb = PartnerPairing(context, b.store, b.identity, this)
        val shown = pa.showCode("Ananya")
        val qr = PartnerQr.parse(shown.qrText, System.currentTimeMillis(), null).payload!!
        // Same device and keys, but another token: the psk differs.
        val forged = PartnerQr.encode(qr.deviceId, qr.name, qr.x25519, qr.ed25519,
            com.ayuvo.health.partner.logic.PartnerKdf.b64urlEncode(ByteArray(32) { 7 }), qr.expMs, qr.hosts)
        val r = withTimeout(60_000) { pb.scan(forged) }
        assertTrue(r.isFailure)
        // The failed attempt did not burn the single-use token: the real code still pairs.
        val ok = async { pb.scan(shown.qrText).getOrThrow() }
        val atA = withTimeout(30_000) { shown.connections.receive() }
        assertEquals(atA.sas, ok.await().sas)
        shown.close()
    }

    companion object {
        const val DB_A = "partner_pairing_test_a.db"
        const val DB_B = "partner_pairing_test_b.db"
    }
}
