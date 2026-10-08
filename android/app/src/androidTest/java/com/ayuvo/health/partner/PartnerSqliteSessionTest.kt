package com.ayuvo.health.partner

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ayuvo.health.data.KeyStore
import com.ayuvo.health.partner.data.Partner
import com.ayuvo.health.partner.data.PartnerDatabase
import com.ayuvo.health.partner.data.SqlitePartnerStore
import com.ayuvo.health.partner.identity.DeviceIdentity
import com.ayuvo.health.partner.identity.KeyStorePartnerSecrets
import com.ayuvo.health.partner.logic.PartnerCatalog
import com.ayuvo.health.partner.logic.PartnerJson
import com.ayuvo.health.partner.logic.RecordKey
import com.ayuvo.health.partner.sources.PartnerSource
import com.ayuvo.health.partner.sources.SourceRecord
import com.ayuvo.health.partner.sync.InMemoryFramedChannel
import com.ayuvo.health.partner.sync.LedgerRefresher
import com.ayuvo.health.partner.sync.LocalPeer
import com.ayuvo.health.partner.sync.OutboundRenderer
import com.ayuvo.health.partner.sync.PartnerSession
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

/** Sessions between two real `ayuvo_partner.db` stores: per-page transactions, ACK cursors and resume. */
@RunWith(AndroidJUnit4::class)
class PartnerSqliteSessionTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val now = System.currentTimeMillis()

    private class MapSource(override val type: String) : PartnerSource {
        val records = linkedMapOf<String, SourceRecord>()
        override suspend fun forEach(dayFrom: String?, onRecord: (SourceRecord) -> Unit) =
            records.values.filter { dayFrom == null || it.day == null || it.day >= dayFrom }.forEach(onRecord)
        override suspend fun render(ids: Collection<String>) = ids.mapNotNull { id -> records[id]?.let { id to it } }.toMap()
    }

    private inner class Phone(val dbName: String, secrets: Map<String, String> = emptyMap()) {
        val helper = PartnerDatabase(context, dbName)
        val store = SqlitePartnerStore(helper)
        val identity = DeviceIdentity(object : com.ayuvo.health.partner.identity.PartnerSecretStore {
            val m = HashMap(secrets)
            override fun load(key: String) = m[key]
            override fun save(key: String, value: String) { m[key] = value }
        })
        val steps = MapSource("metric_day")
        val refresher = LedgerRefresher(store, listOf(steps))
        val renderer = OutboundRenderer(store, listOf(steps))
        fun partner() = Partner(identity.deviceId, dbName, identity.fingerprint, identity.x25519Public, identity.ed25519Public, "android", now, updatedMs = now)
        fun session(peer: Phone) = PartnerSession(store, renderer, LocalPeer(identity.deviceId, appVersion = "t"), peer.identity.deviceId)
    }

    private lateinit var a: Phone
    private lateinit var b: Phone

    @Before
    fun setUp() {
        PartnerCatalog.install(context)
        listOf(DB_A, DB_B).forEach { PartnerDatabase.deleteDatabaseFiles(context, it) }
        a = Phone(DB_A)
        b = Phone(DB_B)
        a.store.upsertPartner(b.partner()); b.store.upsertPartner(a.partner())
        val all = PartnerCatalog.current.categories.associateWith { true }
        a.store.setGrantsOut(b.identity.deviceId, all, now); b.store.setGrantsOut(a.identity.deviceId, all, now)
    }

    @After
    fun tearDown() {
        a.helper.close(); b.helper.close()
        listOf(DB_A, DB_B).forEach { PartnerDatabase.deleteDatabaseFiles(context, it) }
    }

    private suspend fun sync(dropAfter: Int = -1) = coroutineScope {
        a.refresher.refresh(); b.refresher.refresh()
        val (ca, cb) = InMemoryFramedChannel.pair()
        ca.failAfterSends = dropAfter
        val x = async { a.session(b).run(ca) }
        val y = async { b.session(a).run(cb) }
        x.await() to y.await()
    }

    @Test
    fun initialSyncInterruptionAndResume() = runBlocking {
        val today = LocalDate.now()
        for (i in 0 until 1300) {
            val day = today.minusDays(i.toLong()).toString()
            a.steps.records["steps:$day"] = SourceRecord("metric_day", "steps:$day", "vitals", day,
                PartnerJson.obj("type_id" to "steps", "day" to day, "unit" to "count", "sum" to i))
        }
        val owner = a.identity.deviceId
        val (ra, rb) = sync(dropAfter = 4)
        assertFalse(ra.ok || rb.ok)
        val first = b.store.recordCount(owner)
        assertEquals(first > 0, b.store.syncState(owner)!!.lastRev > 0)

        val (ra2, rb2) = sync()
        assertTrue("$ra2 / $rb2", ra2.ok && rb2.ok)
        assertEquals(1300L, b.store.recordCount(owner))
        assertEquals(0, rb2.received.duplicate)
        assertEquals(a.store.outboundRev(), b.store.syncState(owner)!!.lastRev)
        assertEquals(a.store.outboundRev(), a.store.syncState(b.identity.deviceId)!!.ackedRev)
        assertEquals("up_to_date", b.store.syncState(owner)!!.status)

        // Tombstone travels; then the second delta is empty.
        val gone = "steps:${today}"
        a.steps.records.remove(gone)
        val (_, rb3) = sync()
        assertEquals(1, rb3.received.deleted)
        assertEquals(null, b.store.storedRevs(owner, listOf(RecordKey("metric_day", gone)))[RecordKey("metric_day", gone)])
        val (ra4, rb4) = sync()
        assertEquals(0, ra4.sentRecords)
        assertTrue(rb4.ok)
    }

    /** Tink 1.23 bump: EncryptedSharedPreferences / Android Keystore still round-trip, and the partner identity persists. */
    @Test
    fun keyStoreStillReadsAndIdentityPersists() {
        val ks = KeyStore(context)
        val key = "partner_test_probe"
        ks.save(key, "value-${now}")
        assertEquals("value-${now}", KeyStore(context).load(key))
        ks.save(key, "")
        val first = DeviceIdentity(KeyStorePartnerSecrets(KeyStore(context)))
        val id = first.deviceId
        val second = DeviceIdentity(KeyStorePartnerSecrets(KeyStore(context)))
        assertEquals(id, second.deviceId)
        assertEquals(first.ed25519Public, second.ed25519Public)
        val sig = second.sign("hello".toByteArray())
        assertTrue(DeviceIdentity.verify(first.ed25519Public, "hello".toByteArray(), sig))
        assertNotNull(first.x25519Public)
    }

    companion object {
        const val DB_A = "partner_session_test_a.db"
        const val DB_B = "partner_session_test_b.db"
    }
}
