package com.ayuvo.health.partner

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ayuvo.health.partner.data.Partner
import com.ayuvo.health.partner.data.PartnerDatabase
import com.ayuvo.health.partner.data.PartnerSchema
import com.ayuvo.health.partner.data.SqlitePartnerStore
import com.ayuvo.health.partner.logic.LedgerCurrent
import com.ayuvo.health.partner.logic.LedgerScope
import com.ayuvo.health.partner.logic.PartnerCatalog
import com.ayuvo.health.partner.logic.PartnerJson
import com.ayuvo.health.partner.logic.PartnerMerge
import com.ayuvo.health.partner.logic.RecordKey
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** [SqlitePartnerStore] on a throwaway database: transactions, UPDATE-then-INSERT upserts and delta paging. */
@RunWith(AndroidJUnit4::class)
class SqlitePartnerStoreTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var helper: PartnerDatabase
    private lateinit var store: SqlitePartnerStore
    private val owner = "0b8f3c2a-5d1e-4c7a-9f00-1a2b3c4d5e6f"
    private val now = 1_791_374_400_000L
    private val all = listOf("vitals", "sleep", "nutrition", "workouts", "medicines", "report_overviews")

    @Before
    fun setUp() {
        PartnerCatalog.install(context)
        context.deleteDatabase(DB)
        helper = PartnerDatabase(context, DB)
        store = SqlitePartnerStore(helper)
        store.upsertPartner(partner())
    }

    @After
    fun tearDown() {
        helper.close()
        PartnerDatabase.deleteDatabaseFiles(context, DB)
    }

    private fun partner(x: String = "jybW_io9r9goCBzD6jpdYQCDxFY_tPT-UbJTOxzkTrA") = Partner(
        ownerId = owner, displayName = "Ananya", fingerprint = "68a7f0d4954e63b10b2cfc8b817d6c12", x25519Pub = x,
        ed25519Pub = "hAWubePr3iq_-yUx1DehY6MdxCoQnHNptmuTJYx-HWc", platform = "ios", pairedMs = now, updatedMs = now
    )

    private fun steps(day: String, rev: Long, sum: Int, deleted: Boolean = false): JsonObject =
        if (deleted) PartnerJson.obj("type" to "metric_day", "id" to "steps:$day", "category" to "vitals", "rev" to rev, "deleted" to true, "updated_ms" to now)
        else PartnerJson.obj(
            "type" to "metric_day", "id" to "steps:$day", "category" to "vitals", "rev" to rev, "deleted" to false,
            "updated_ms" to now, "day" to day,
            "data" to PartnerJson.obj("type_id" to "steps", "day" to day, "unit" to "count", "sum" to sum)
        )

    private fun batch(from: Long, to: Long, vararg records: JsonObject) =
        PartnerJson.obj("from_rev" to from, "to_rev" to to, "records" to JsonArray(records.toList()))

    private fun merge(b: JsonObject, commit: Boolean = true) = run {
        val cursor = store.syncState(owner)!!.lastRev
        val keys = (b["records"] as JsonArray).map { val o = it as JsonObject; RecordKey(PartnerJson.str(o["type"])!!, PartnerJson.str(o["id"])!!) }
        val stored = store.storedRevs(owner, keys)
        PartnerMerge.apply({ stored[it] }, b, cursor, all, now).also { store.applyMerge(owner, it, commit) }
    }

    @Test
    fun schemaCreatedWithMetaRows() {
        val db = helper.readableDatabase
        for (t in PartnerSchema.TABLES) {
            db.rawQuery("SELECT name FROM sqlite_master WHERE type='table' AND name=?", arrayOf(t)).use { assertTrue(t, it.moveToFirst()) }
        }
        assertEquals("1", store.meta("schema_version"))
        assertEquals(0L, store.outboundRev())
        db.rawQuery("PRAGMA foreign_keys", null).use { it.moveToFirst(); assertEquals(1, it.getInt(0)) }
        db.rawQuery("PRAGMA journal_mode", null).use { it.moveToFirst(); assertEquals("wal", it.getString(0).lowercase()) }
    }

    @Test
    fun upsertIsUpdateThenInsertAndCursorCommits() {
        val r1 = merge(batch(0, 2, steps("2026-10-06", 1, 100), steps("2026-10-07", 2, 200)))
        assertEquals(2, r1.counts.inserted)
        assertEquals(2L, store.syncState(owner)!!.lastRev)
        val r2 = merge(batch(2, 3, steps("2026-10-07", 3, 250)))
        assertEquals(1, r2.counts.updated)
        assertEquals(2L, store.recordCount(owner))
        val row = store.records(owner, listOf("metric_day"), listOf("2026-10-07")).single()
        assertEquals("250", (row.data["sum"] as kotlinx.serialization.json.JsonPrimitive).content)
        val r3 = merge(batch(3, 4, steps("2026-10-06", 4, 0, deleted = true)))
        assertEquals(1, r3.counts.deleted)
        assertEquals(1L, store.recordCount(owner))
        assertEquals(4L, store.syncState(owner)!!.lastRev)
        // Replaying an old batch changes nothing and never resurrects the deleted row.
        val replay = merge(batch(0, 2, steps("2026-10-06", 1, 100), steps("2026-10-07", 2, 200)))
        assertFalse(replay.hasChanges)
        assertEquals(1L, store.recordCount(owner))
    }

    @Test
    fun failureMidBatchRollsBackEverything() {
        merge(batch(0, 1, steps("2026-10-01", 1, 10)))
        store.failAfterWrites = 2
        try {
            merge(batch(1, 10, steps("2026-10-01", 5, 0, deleted = true), steps("2026-10-02", 6, 20), steps("2026-10-03", 7, 30), steps("2026-10-04", 8, 40)))
            fail("injected failure expected")
        } catch (_: IllegalStateException) {
        }
        store.failAfterWrites = -1
        assertEquals("cursor unchanged", 1L, store.syncState(owner)!!.lastRev)
        assertEquals("rows unchanged", 1L, store.recordCount(owner))
        assertNotNull(store.records(owner, listOf("metric_day"), listOf("2026-10-01")).singleOrNull())
        // The resumed batch then applies cleanly.
        val ok = merge(batch(1, 10, steps("2026-10-01", 5, 0, deleted = true), steps("2026-10-02", 6, 20)))
        assertTrue(ok.accepted)
        assertEquals(10L, store.syncState(owner)!!.lastRev)
    }

    @Test
    fun deleteDataResetsCursorUnpairKeepsData() {
        merge(batch(0, 3, steps("2026-10-01", 1, 10), steps("2026-10-02", 3, 20)))
        store.unpair(owner, now)
        assertTrue(store.trustedOwnerIds().isEmpty())
        assertEquals(2L, store.recordCount(owner))
        assertNotNull(store.partner(owner)!!.unpairedMs)
        store.deletePartnerData(owner)
        assertEquals(0L, store.recordCount(owner))
        assertEquals(0L, store.syncState(owner)!!.lastRev)
        // Re-pairing with new keys drops data and cursor; unchanged keys keep them.
        merge(batch(0, 3, steps("2026-10-01", 1, 10)))
        store.upsertPartner(partner())
        assertEquals(1L, store.recordCount(owner))
        store.upsertPartner(partner(x = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"))
        assertEquals(0L, store.recordCount(owner))
        assertEquals(0L, store.syncState(owner)!!.lastRev)
        store.removePartner(owner)
        assertNull(store.syncState(owner))
    }

    @Test
    fun retentionPrunesOldIntradayRows() {
        val old = now - 8 * PartnerCatalog.DAY_MS
        val sample = { id: String, start: Long, rev: Long ->
            PartnerJson.obj(
                "type" to "sample", "id" to id, "category" to "vitals", "rev" to rev, "deleted" to false, "updated_ms" to now,
                "day" to "2026-10-01", "data" to PartnerJson.obj("type_id" to "heart_rate", "start_ms" to start, "end_ms" to start, "unit" to "bpm", "value" to 70)
            )
        }
        merge(batch(0, 2, sample("old", old, 1), sample("new", now, 2)))
        assertEquals(1, store.retentionPrune(now))
        assertEquals(1L, store.recordCount(owner))
    }

    @Test
    fun ledgerRefreshDeltaPagingAndRegrant() {
        val n = 5_000
        val current = (0 until n).map { LedgerCurrent("food_entry", "f-%05d".format(it), "nutrition", "2026-10-01", "h$it") } +
            (0 until 100).map { LedgerCurrent("medication", "m-$it", "medicines", null, "m$it") }
        val out = store.refreshLedger(current, listOf(LedgerScope("food_entry"), LedgerScope("medication")))
        assertEquals((n + 100).toLong(), out.rev)
        assertEquals(n + 100, out.changed)
        // Unchanged refresh assigns nothing.
        assertEquals(0, store.refreshLedger(current, listOf(LedgerScope("food_entry"), LedgerScope("medication"))).changed)
        // A vanished record becomes a tombstone with the next rev.
        val shrunk = current.filterNot { it.recordId == "m-7" }
        val t = store.refreshLedger(shrunk, listOf(LedgerScope("food_entry"), LedgerScope("medication")))
        assertEquals(1, t.tombstoned)
        assertTrue(store.ledgerRow("medication", "m-7")!!.deleted)

        var cursor = 0L
        var last = 0L
        var seen = 0
        do {
            val page = store.ledgerDelta(cursor, listOf("nutrition"), 500)
            assertTrue(page.keys.size <= 500)
            page.keys.forEach { assertTrue(it.rev > last); last = it.rev }
            seen += page.keys.size
            cursor = page.toRev
        } while (page.hasMore)
        assertEquals(n, seen)
        assertEquals(store.outboundRev(), cursor)
        // Cursor ahead of the counter restarts from 0.
        assertEquals(0L, store.ledgerDelta(cursor + 100, listOf("medicines"), 500).fromRev)

        // Granting a category to a partner (off -> on) re-revs that category.
        val before = store.outboundRev()
        assertEquals(listOf("medicines"), store.setGrantsOut(owner, mapOf("medicines" to true), now))
        assertEquals(before + 100, store.outboundRev())
        assertEquals(emptyList<String>(), store.setGrantsOut(owner, mapOf("medicines" to true), now))
        assertEquals(listOf("medicines"), store.grantedCategoriesOut(owner))
    }

    @Test
    fun grantsReceivedRevokeKeepsTimestamp() {
        store.updateGrantsReceived(owner, listOf("vitals", "sleep"), now)
        val g = store.updateGrantsReceived(owner, listOf("vitals"), now + 1)
        assertEquals(now + 1, g.single { it.category == "sleep" }.revokedMs)
        assertEquals(g, store.grantsReceived(owner))
    }

    companion object {
        private const val DB = "partner_store_test.db"
    }
}
