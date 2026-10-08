package com.ayuvo.health.partner

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ayuvo.health.partner.data.Partner
import com.ayuvo.health.partner.data.PartnerDatabase
import com.ayuvo.health.partner.data.SqlitePartnerStore
import com.ayuvo.health.partner.identity.DeviceIdentity
import com.ayuvo.health.partner.identity.PartnerSecretStore
import com.ayuvo.health.partner.logic.PartnerCatalog
import com.ayuvo.health.partner.logic.PartnerJson
import com.ayuvo.health.partner.net.PartnerLink
import com.ayuvo.health.partner.sources.PartnerSource
import com.ayuvo.health.partner.sources.SourceRecord
import com.ayuvo.health.partner.sync.LedgerRefresher
import com.ayuvo.health.partner.sync.LocalPeer
import com.ayuvo.health.partner.sync.OutboundRenderer
import com.ayuvo.health.partner.sync.PartnerSyncCoordinator
import com.ayuvo.health.partner.sync.SyncTrigger
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

/**
 * Two "phones" in one process on the emulator, each with its own partner database and identity: both open a sync
 * window at once (listener + DNS-SD advertise/browse), find each other, run a Noise KK session and tear down.
 */
@RunWith(AndroidJUnit4::class)
class PartnerWindowTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val now = System.currentTimeMillis()

    private class OneSource(override val type: String, val records: Map<String, SourceRecord>) : PartnerSource {
        /** A slow store: the refresh takes this long (it runs alongside discovery). */
        @Volatile var readDelayMs = 0L
        override suspend fun forEach(dayFrom: String?, onRecord: (SourceRecord) -> Unit) {
            if (readDelayMs > 0) kotlinx.coroutines.delay(readDelayMs)
            records.values.forEach(onRecord)
        }
        override suspend fun render(ids: Collection<String>) = ids.mapNotNull { id -> records[id]?.let { id to it } }.toMap()
    }

    private inner class Phone(val dbName: String, sum: Int) {
        val helper = PartnerDatabase(context, dbName)
        val store = SqlitePartnerStore(helper)
        val identity = DeviceIdentity(object : PartnerSecretStore {
            val m = HashMap<String, String>()
            override fun load(key: String) = m[key]
            override fun save(key: String, value: String) { m[key] = value }
        })
        val day = LocalDate.now().toString()
        val source = OneSource("metric_day", mapOf("steps:$day" to SourceRecord("metric_day", "steps:$day", "vitals", day,
            PartnerJson.obj("type_id" to "steps", "day" to day, "unit" to "count", "sum" to sum))))
        val renderer = OutboundRenderer(store, listOf(source))
        val refresher = LedgerRefresher(store, listOf(source))
        val link = PartnerLink(store, { identity.noiseStatic }, renderer, awaitLedger = { refresher.awaitInFlight() }) {
            LocalPeer(identity.deviceId, appVersion = "t")
        }
        lateinit var coordinator: PartnerSyncCoordinator
        fun partner() = Partner(identity.deviceId, dbName, identity.fingerprint, identity.x25519Public, identity.ed25519Public, "android", now, updatedMs = now)
    }

    private lateinit var a: Phone
    private lateinit var b: Phone

    @Before
    fun setUp() {
        PartnerCatalog.install(context)
        listOf(DB_A, DB_B).forEach { PartnerDatabase.deleteDatabaseFiles(context, it) }
        a = Phone(DB_A, 1111)
        b = Phone(DB_B, 2222)
        a.store.upsertPartner(b.partner()); b.store.upsertPartner(a.partner())
        val all = PartnerCatalog.current.categories.associateWith { true }
        a.store.setGrantsOut(b.identity.deviceId, all, now); b.store.setGrantsOut(a.identity.deviceId, all, now)
    }

    @After
    fun tearDown() {
        a.helper.close(); b.helper.close()
        listOf(DB_A, DB_B).forEach { PartnerDatabase.deleteDatabaseFiles(context, it) }
    }

    @Test
    fun bothWindowsFindEachOtherAndSync() = runBlocking {
        for (p in listOf(a, b)) {
            p.coordinator = PartnerSyncCoordinator(context, p.store, { p.identity.deviceId }, p.refresher, p.link, this, windowMs = 40_000)
        }
        val ra = async { a.coordinator.runWindow(SyncTrigger.SYNC_NOW) }
        val rb = async { b.coordinator.runWindow(SyncTrigger.SYNC_NOW) }
        val (oa, ob) = ra.await() to rb.await()
        assertTrue("a: $oa", b.identity.deviceId in oa.synced)
        assertTrue("b: $ob", a.identity.deviceId in ob.synced)
        assertEquals(1L, a.store.recordCount(b.identity.deviceId))
        assertEquals(1L, b.store.recordCount(a.identity.deviceId))
        assertEquals("up_to_date", a.store.syncState(b.identity.deviceId)!!.status)
        assertEquals("up_to_date", b.store.syncState(a.identity.deviceId)!!.status)
        // The dialling side (smaller device_id) remembers the address for the direct-dial fallback.
        val dialer = if (a.identity.deviceId < b.identity.deviceId) a else b
        val other = if (dialer === a) b else a
        assertTrue(dialer.store.partner(other.identity.deviceId)!!.lastPort != null)
    }

    /**
     * docs §10–§11: a slow ledger refresh runs alongside discovery. The listener is up at once, and the session waits
     * (bounded) for the refresh before serving, so the record still arrives in this window.
     */
    @Test
    fun slowRefreshRunsAlongsideDiscovery() = runBlocking {
        a.source.readDelayMs = 6_000
        b.source.readDelayMs = 6_000
        for (p in listOf(a, b)) {
            p.coordinator = PartnerSyncCoordinator(context, p.store, { p.identity.deviceId }, p.refresher, p.link, this, windowMs = 40_000)
        }
        val started = System.currentTimeMillis()
        val ra = async { a.coordinator.runWindow(SyncTrigger.SYNC_NOW) }
        val rb = async { b.coordinator.runWindow(SyncTrigger.SYNC_NOW) }
        val (oa, ob) = ra.await() to rb.await()
        assertTrue("a: $oa", b.identity.deviceId in oa.synced)
        assertTrue("b: $ob", a.identity.deviceId in ob.synced)
        assertEquals("served after the refresh finished", 1L, a.store.recordCount(b.identity.deviceId))
        assertEquals(1L, b.store.recordCount(a.identity.deviceId))
        assertTrue("both refreshes ran", a.refresher.lastCompletedMs != null && b.refresher.lastCompletedMs != null)
        android.util.Log.i("PartnerWindowTest", "slow-refresh window done in ${System.currentTimeMillis() - started} ms")

        // A window right after skips the refresh (< 2 min) and still syncs.
        val before = a.refresher.lastCompletedMs
        a.source.readDelayMs = 0; b.source.readDelayMs = 0
        val ra2 = async { a.coordinator.runWindow(SyncTrigger.SYNC_NOW) }
        val rb2 = async { b.coordinator.runWindow(SyncTrigger.SYNC_NOW) }
        assertTrue(b.identity.deviceId in ra2.await().synced)
        assertTrue(a.identity.deviceId in rb2.await().synced)
        assertEquals("refresh skipped", before, a.refresher.lastCompletedMs)
    }

    companion object {
        const val DB_A = "partner_window_test_a.db"
        const val DB_B = "partner_window_test_b.db"
    }
}
