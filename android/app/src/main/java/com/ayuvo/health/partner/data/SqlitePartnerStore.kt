package com.ayuvo.health.partner.data

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import com.ayuvo.health.partner.logic.LedgerCurrent
import com.ayuvo.health.partner.logic.LedgerDelta
import com.ayuvo.health.partner.logic.LedgerRow
import com.ayuvo.health.partner.logic.LedgerScope
import com.ayuvo.health.partner.logic.MergeResult
import com.ayuvo.health.partner.logic.PartnerCatalog
import com.ayuvo.health.partner.logic.PartnerJson
import com.ayuvo.health.partner.logic.PartnerLedger
import com.ayuvo.health.partner.logic.PartnerMerge
import com.ayuvo.health.partner.logic.ReceivedGrant
import com.ayuvo.health.partner.logic.RecordKey
import com.ayuvo.health.partner.logic.StoredRecord
import kotlinx.serialization.json.JsonObject

/** [PartnerStore] on [PartnerDatabase]. Thread-safe through SQLiteDatabase's own locking. */
class SqlitePartnerStore(private val database: PartnerDatabase) : PartnerStore {

    private val db: SQLiteDatabase get() = database.writableDatabase

    /** Test hook (androidTest): throw after this many row writes inside [applyMerge] to prove the rollback. */
    internal var failAfterWrites: Int = -1

    private inline fun <T> tx(block: (SQLiteDatabase) -> T): T {
        val d = db
        d.beginTransaction()
        try {
            val r = block(d)
            d.setTransactionSuccessful()
            return r
        } finally {
            d.endTransaction()
        }
    }

    private fun Cursor.str(i: Int): String? = if (isNull(i)) null else getString(i)
    private fun Cursor.lng(i: Int): Long? = if (isNull(i)) null else getLong(i)

    // -- meta ------------------------------------------------------------------------------------------

    override fun meta(key: String): String? =
        db.rawQuery("SELECT value FROM partner_meta WHERE key = ?", arrayOf(key)).use { if (it.moveToFirst()) it.str(0) else null }

    override fun setMeta(key: String, value: String) {
        tx { setMetaIn(it, key, value) }
    }

    private fun setMetaIn(d: SQLiteDatabase, key: String, value: String) {
        val cv = ContentValues().apply { put("value", value) }
        if (d.update("partner_meta", cv, "key = ?", arrayOf(key)) == 0) {
            cv.put("key", key)
            d.insertOrThrow("partner_meta", null, cv)
        }
    }

    private fun revIn(d: SQLiteDatabase): Long =
        d.rawQuery("SELECT value FROM partner_meta WHERE key = 'rev'", null).use { if (it.moveToFirst()) it.str(0)?.toLongOrNull() ?: 0L else 0L }

    override fun outboundRev(): Long = revIn(db)

    // -- partners --------------------------------------------------------------------------------------

    private val partnerColumns = "owner_id, display_name, fingerprint, x25519_pub, ed25519_pub, platform, paired_ms, unpaired_ms, last_host, last_port, updated_ms"

    private fun Cursor.partner() = Partner(
        ownerId = getString(0), displayName = getString(1), fingerprint = getString(2), x25519Pub = getString(3),
        ed25519Pub = getString(4), platform = str(5), pairedMs = getLong(6), unpairedMs = lng(7), lastHost = str(8),
        lastPort = lng(9)?.toInt(), updatedMs = getLong(10)
    )

    private fun partnerIn(d: SQLiteDatabase, ownerId: String): Partner? =
        d.rawQuery("SELECT $partnerColumns FROM partners WHERE owner_id = ?", arrayOf(ownerId)).use { if (it.moveToFirst()) it.partner() else null }

    override fun upsertPartner(partner: Partner) {
        tx { d ->
            val old = partnerIn(d, partner.ownerId)
            val cv = ContentValues().apply {
                put("display_name", partner.displayName)
                put("fingerprint", partner.fingerprint)
                put("x25519_pub", partner.x25519Pub)
                put("ed25519_pub", partner.ed25519Pub)
                put("platform", partner.platform)
                put("paired_ms", partner.pairedMs)
                if (partner.unpairedMs == null) putNull("unpaired_ms") else put("unpaired_ms", partner.unpairedMs)
                put("last_host", partner.lastHost)
                if (partner.lastPort == null) putNull("last_port") else put("last_port", partner.lastPort)
                put("updated_ms", partner.updatedMs)
            }
            if (d.update("partners", cv, "owner_id = ?", arrayOf(partner.ownerId)) == 0) {
                cv.put("owner_id", partner.ownerId)
                d.insertOrThrow("partners", null, cv)
            }
            d.execSQL("INSERT OR IGNORE INTO partner_sync_state(owner_id) VALUES (?)", arrayOf(partner.ownerId))
            if (old != null && (old.x25519Pub != partner.x25519Pub || old.ed25519Pub != partner.ed25519Pub)) {
                // New keys = a different installation: its revisions are unrelated to what we hold.
                deletePartnerDataIn(d, partner.ownerId)
            }
        }
    }

    override fun partner(ownerId: String): Partner? = partnerIn(db, ownerId)

    override fun partners(includeUnpaired: Boolean): List<Partner> {
        val where = if (includeUnpaired) "" else " WHERE unpaired_ms IS NULL"
        return db.rawQuery("SELECT $partnerColumns FROM partners$where ORDER BY paired_ms, owner_id", null).use { c ->
            buildList { while (c.moveToNext()) add(c.partner()) }
        }
    }

    override fun trustedOwnerIds(): List<String> =
        db.rawQuery("SELECT owner_id FROM partners WHERE unpaired_ms IS NULL ORDER BY owner_id", null).use { c ->
            buildList { while (c.moveToNext()) add(c.getString(0)) }
        }

    override fun trustedPartnerByX25519(x25519Pub: String): Partner? =
        db.rawQuery("SELECT $partnerColumns FROM partners WHERE x25519_pub = ? AND unpaired_ms IS NULL", arrayOf(x25519Pub))
            .use { if (it.moveToFirst()) it.partner() else null }

    override fun unpair(ownerId: String, nowMs: Long) {
        tx { d ->
            d.execSQL("UPDATE partners SET unpaired_ms = ?, updated_ms = ? WHERE owner_id = ?", arrayOf(nowMs, nowMs, ownerId))
        }
    }

    override fun setLastAddress(ownerId: String, host: String, port: Int, nowMs: Long) {
        tx { d ->
            d.execSQL("UPDATE partners SET last_host = ?, last_port = ?, updated_ms = ? WHERE owner_id = ?", arrayOf(host, port, nowMs, ownerId))
        }
    }

    override fun removePartner(ownerId: String) {
        tx { d -> d.delete("partners", "owner_id = ?", arrayOf(ownerId)) }
    }

    // -- grants ----------------------------------------------------------------------------------------

    override fun grantsOut(ownerId: String): List<GrantOut> =
        db.rawQuery("SELECT category, granted, updated_ms FROM partner_grants_out WHERE owner_id = ?", arrayOf(ownerId)).use { c ->
            val byCat = buildMap { while (c.moveToNext()) put(c.getString(0), GrantOut(c.getString(0), c.getInt(1) != 0, c.getLong(2))) }
            PartnerCatalog.current.categories.mapNotNull { byCat[it] }
        }

    override fun grantedCategoriesOut(ownerId: String): List<String> = grantsOut(ownerId).filter { it.granted }.map { it.category }

    override fun setGrantsOut(ownerId: String, granted: Map<String, Boolean>, nowMs: Long): List<String> = tx { d ->
        val before = d.rawQuery("SELECT category FROM partner_grants_out WHERE owner_id = ? AND granted = 1", arrayOf(ownerId)).use { c ->
            buildSet { while (c.moveToNext()) add(c.getString(0)) }
        }
        val regranted = mutableListOf<String>()
        for (cat in PartnerCatalog.current.categories) {
            val on = granted[cat] ?: continue
            val cv = ContentValues().apply { put("granted", if (on) 1 else 0); put("updated_ms", nowMs) }
            if (d.update("partner_grants_out", cv, "owner_id = ? AND category = ?", arrayOf(ownerId, cat)) == 0) {
                cv.put("owner_id", ownerId); cv.put("category", cat)
                d.insertOrThrow("partner_grants_out", null, cv)
            }
            if (on && cat !in before) {
                regrantIn(d, cat)
                regranted += cat
            }
        }
        regranted
    }

    private fun grantsReceivedIn(d: SQLiteDatabase, ownerId: String): List<ReceivedGrant> =
        d.rawQuery("SELECT category, granted, revoked_ms FROM partner_grants_received WHERE owner_id = ?", arrayOf(ownerId)).use { c ->
            val byCat = buildMap { while (c.moveToNext()) put(c.getString(0), ReceivedGrant(c.getString(0), c.getInt(1) != 0, c.lng(2))) }
            PartnerCatalog.current.categories.mapNotNull { byCat[it] }
        }

    override fun grantsReceived(ownerId: String): List<ReceivedGrant> = grantsReceivedIn(db, ownerId)

    override fun updateGrantsReceived(ownerId: String, grantedNow: Collection<String>, nowMs: Long): List<ReceivedGrant> = tx { d ->
        val rows = PartnerMerge.grantsReceivedUpdate(grantsReceivedIn(d, ownerId), grantedNow, nowMs)
        for (g in rows) {
            val cv = ContentValues().apply {
                put("granted", if (g.granted) 1 else 0)
                if (g.revokedMs == null) putNull("revoked_ms") else put("revoked_ms", g.revokedMs)
                put("updated_ms", nowMs)
            }
            if (d.update("partner_grants_received", cv, "owner_id = ? AND category = ?", arrayOf(ownerId, g.category)) == 0) {
                cv.put("owner_id", ownerId); cv.put("category", g.category)
                d.insertOrThrow("partner_grants_received", null, cv)
            }
        }
        rows
    }

    // -- received records ------------------------------------------------------------------------------

    override fun storedRevs(ownerId: String, keys: Collection<RecordKey>): Map<RecordKey, Long> {
        val out = HashMap<RecordKey, Long>()
        val d = db
        for (chunk in keys.distinct().chunked(200)) {
            val where = chunk.joinToString(" OR ") { "(type = ? AND record_id = ?)" }
            val args = arrayOf(ownerId) + chunk.flatMap { listOf(it.type, it.recordId) }
            d.rawQuery("SELECT type, record_id, rev FROM partner_records WHERE owner_id = ? AND ($where)", args).use { c ->
                while (c.moveToNext()) out[RecordKey(c.getString(0), c.getString(1))] = c.getLong(2)
            }
        }
        return out
    }

    override fun applyMerge(ownerId: String, result: MergeResult, commitCursor: Boolean) {
        require(result.accepted) { "refused batch (${result.error}) must not be written" }
        tx { d ->
            var writes = 0
            fun wrote() {
                writes++
                if (failAfterWrites in 0 until writes) throw IllegalStateException("injected failure after $failAfterWrites writes")
            }
            val del = d.compileStatement("DELETE FROM partner_records WHERE owner_id = ? AND type = ? AND record_id = ?")
            for (k in result.deletes) {
                del.clearBindings()
                del.bindString(1, ownerId); del.bindString(2, k.type); del.bindString(3, k.recordId)
                del.executeUpdateDelete()
                wrote()
            }
            val upd = d.compileStatement(
                "UPDATE partner_records SET category = ?, rev = ?, day = ?, ts_ms = ?, updated_ms = ?, data_json = ? " +
                    "WHERE owner_id = ? AND type = ? AND record_id = ?"
            )
            val ins = d.compileStatement(
                "INSERT INTO partner_records(owner_id, type, record_id, category, rev, day, ts_ms, updated_ms, data_json) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)"
            )
            for (u in result.upserts) {
                val json = u.data.toString()
                upd.clearBindings()
                upd.bindString(1, u.category); upd.bindLong(2, u.rev)
                if (u.day == null) upd.bindNull(3) else upd.bindString(3, u.day)
                if (u.tsMs == null) upd.bindNull(4) else upd.bindLong(4, u.tsMs)
                upd.bindLong(5, u.updatedMs); upd.bindString(6, json)
                upd.bindString(7, ownerId); upd.bindString(8, u.type); upd.bindString(9, u.recordId)
                if (upd.executeUpdateDelete() == 0) {
                    ins.clearBindings()
                    ins.bindString(1, ownerId); ins.bindString(2, u.type); ins.bindString(3, u.recordId)
                    ins.bindString(4, u.category); ins.bindLong(5, u.rev)
                    if (u.day == null) ins.bindNull(6) else ins.bindString(6, u.day)
                    if (u.tsMs == null) ins.bindNull(7) else ins.bindLong(7, u.tsMs)
                    ins.bindLong(8, u.updatedMs); ins.bindString(9, json)
                    ins.executeInsert()
                }
                wrote()
            }
            if (commitCursor) setLastRevIn(d, ownerId, result.cursor)
        }
    }

    private fun setLastRevIn(d: SQLiteDatabase, ownerId: String, rev: Long) {
        val cv = ContentValues().apply { put("last_rev", rev) }
        if (d.update("partner_sync_state", cv, "owner_id = ?", arrayOf(ownerId)) == 0) {
            cv.put("owner_id", ownerId)
            d.insertOrThrow("partner_sync_state", null, cv)
        }
    }

    override fun records(ownerId: String, types: Collection<String>, days: Collection<String>?, limit: Int): List<StoredRecord> {
        if (types.isEmpty() || days?.isEmpty() == true) return emptyList()
        val args = mutableListOf(ownerId)
        args += types
        var sql = "SELECT type, record_id, day, ts_ms, data_json FROM partner_records WHERE owner_id = ? AND type IN (${types.joinToString(",") { "?" }})"
        if (days != null) {
            sql += " AND day IN (${days.joinToString(",") { "?" }})"
            args += days
        }
        sql += " ORDER BY day DESC, type, record_id LIMIT $limit"
        return db.rawQuery(sql, args.toTypedArray()).use { c ->
            buildList {
                while (c.moveToNext()) {
                    val data = runCatching { PartnerJson.parse(c.getString(4)) as JsonObject }.getOrNull() ?: continue
                    add(StoredRecord(c.getString(0), c.getString(1), c.str(2), c.lng(3), data))
                }
            }
        }
    }

    override fun recordCount(ownerId: String): Long =
        db.rawQuery("SELECT COUNT(*) FROM partner_records WHERE owner_id = ?", arrayOf(ownerId)).use { if (it.moveToFirst()) it.getLong(0) else 0L }

    override fun retentionPrune(nowMs: Long): Int = tx { d ->
        var n = 0
        for ((type, days) in PartnerMerge.retentionTypes()) {
            n += d.delete("partner_records", "type = ? AND ts_ms IS NOT NULL AND ts_ms < ?", arrayOf(type, (nowMs - days * PartnerCatalog.DAY_MS).toString()))
        }
        n
    }

    private fun deletePartnerDataIn(d: SQLiteDatabase, ownerId: String) {
        d.delete("partner_records", "owner_id = ?", arrayOf(ownerId))
        setLastRevIn(d, ownerId, 0)
    }

    override fun deletePartnerData(ownerId: String) {
        tx { deletePartnerDataIn(it, ownerId) }
    }

    // -- sync state ------------------------------------------------------------------------------------

    override fun syncState(ownerId: String): PartnerSyncState? = db.rawQuery(
        "SELECT owner_id, last_rev, acked_rev, last_sync_ms, last_attempt_ms, last_transport, status, last_error, last_export_id " +
            "FROM partner_sync_state WHERE owner_id = ?", arrayOf(ownerId)
    ).use { c ->
        if (!c.moveToFirst()) null else PartnerSyncState(
            c.getString(0), c.getLong(1), c.getLong(2), c.lng(3), c.lng(4), c.str(5), c.getString(6), c.str(7), c.str(8)
        )
    }

    override fun updateSyncState(state: PartnerSyncState) {
        tx { d ->
            val cv = ContentValues().apply {
                put("last_rev", state.lastRev)
                put("acked_rev", state.ackedRev)
                if (state.lastSyncMs == null) putNull("last_sync_ms") else put("last_sync_ms", state.lastSyncMs)
                if (state.lastAttemptMs == null) putNull("last_attempt_ms") else put("last_attempt_ms", state.lastAttemptMs)
                put("last_transport", state.lastTransport)
                put("status", state.status)
                put("last_error", state.lastError)
                put("last_export_id", state.lastExportId)
            }
            if (d.update("partner_sync_state", cv, "owner_id = ?", arrayOf(state.ownerId)) == 0) {
                cv.put("owner_id", state.ownerId)
                d.insertOrThrow("partner_sync_state", null, cv)
            }
        }
    }

    // -- outbound ledger -------------------------------------------------------------------------------

    private val ledgerColumns = "type, record_id, category, day, content_hash, rev, deleted"

    private fun Cursor.ledger() = LedgerRow(getString(0), getString(1), getString(2), str(3), getString(4), getLong(5), getInt(6) != 0)

    override fun ledgerRow(type: String, recordId: String): LedgerRow? =
        db.rawQuery("SELECT $ledgerColumns FROM outbound_ledger WHERE type = ? AND record_id = ?", arrayOf(type, recordId))
            .use { if (it.moveToFirst()) it.ledger() else null }

    override fun refreshLedger(current: List<LedgerCurrent>, scopes: List<LedgerScope>): LedgerRefreshOutcome = tx { d ->
        val startRev = revIn(d)
        val loaded = LinkedHashMap<RecordKey, LedgerRow>()
        // Rows that can become tombstones: in-scope types, and for windowed scopes only day >= day_from (or no day).
        for (s in scopes.associateBy { it.type }.values) {
            val (sql, args) = if (s.dayFrom == null) {
                "SELECT $ledgerColumns FROM outbound_ledger WHERE type = ?" to arrayOf(s.type)
            } else {
                "SELECT $ledgerColumns FROM outbound_ledger WHERE type = ? AND (day IS NULL OR day >= ?)" to arrayOf(s.type, s.dayFrom)
            }
            d.rawQuery(sql, args).use { c -> while (c.moveToNext()) c.ledger().let { loaded[it.key] = it } }
        }
        // Current records whose old row lies outside that window (e.g. its day moved) still need their old row.
        // A full-scope type was loaded completely above, so its unseen keys are new records with no row to look up
        // (on a first full run this skips one 200-key OR query per 200 records).
        val fullyLoaded = scopes.associateBy { it.type }.values.filter { it.dayFrom == null }.map { it.type }.toHashSet()
        val missing = current.map { it.key }.filter { it.type !in fullyLoaded && it !in loaded }.distinct()
        for (chunk in missing.chunked(200)) {
            val where = chunk.joinToString(" OR ") { "(type = ? AND record_id = ?)" }
            d.rawQuery("SELECT $ledgerColumns FROM outbound_ledger WHERE $where", chunk.flatMap { listOf(it.type, it.recordId) }.toTypedArray())
                .use { c -> while (c.moveToNext()) c.ledger().let { loaded[it.key] = it } }
        }
        val res = PartnerLedger.refresh(loaded.values.toList(), startRev, current, scopes)
        writeLedgerRows(d, res.ledger.filter { it.rev > startRev }, loaded.keys)
        setMetaIn(d, "rev", res.rev.toString())
        LedgerRefreshOutcome(res.rev, res.changed, res.tombstoned)
    }

    /** [existing]: keys that have a ledger row (everything else is inserted without trying an UPDATE first). */
    private fun writeLedgerRows(d: SQLiteDatabase, rows: List<LedgerRow>, existing: Set<RecordKey>) {
        val upd = d.compileStatement("UPDATE outbound_ledger SET category = ?, day = ?, content_hash = ?, rev = ?, deleted = ? WHERE type = ? AND record_id = ?")
        val ins = d.compileStatement("INSERT INTO outbound_ledger(type, record_id, category, day, content_hash, rev, deleted) VALUES (?, ?, ?, ?, ?, ?, ?)")
        for (r in rows) {
            var updated = false
            if (r.key in existing) {
                upd.clearBindings()
                upd.bindString(1, r.category)
                if (r.day == null) upd.bindNull(2) else upd.bindString(2, r.day)
                upd.bindString(3, r.contentHash); upd.bindLong(4, r.rev); upd.bindLong(5, if (r.deleted) 1 else 0)
                upd.bindString(6, r.type); upd.bindString(7, r.recordId)
                updated = upd.executeUpdateDelete() > 0
            }
            if (!updated) {
                ins.clearBindings()
                ins.bindString(1, r.type); ins.bindString(2, r.recordId); ins.bindString(3, r.category)
                if (r.day == null) ins.bindNull(4) else ins.bindString(4, r.day)
                ins.bindString(5, r.contentHash); ins.bindLong(6, r.rev); ins.bindLong(7, if (r.deleted) 1 else 0)
                ins.executeInsert()
            }
        }
    }

    override fun pruneLedger(intradayDayFrom: String): Int = tx { d ->
        PartnerMerge.retentionTypes().keys.sumOf { type ->
            d.delete("outbound_ledger", "type = ? AND day IS NOT NULL AND day < ?", arrayOf(type, intradayDayFrom))
        }
    }

    override fun regrantLedger(category: String): Long = tx { regrantIn(it, category) }

    private fun regrantIn(d: SQLiteDatabase, category: String): Long {
        // Keys only (never whole rows), ordered by Unicode scalars like the reference, then fresh revs in that order.
        val keys = d.rawQuery("SELECT type, record_id FROM outbound_ledger WHERE category = ?", arrayOf(category)).use { c ->
            buildList { while (c.moveToNext()) add(RecordKey(c.getString(0), c.getString(1))) }
        }.sortedWith(RecordKey.ORDER)
        var rev = revIn(d)
        val upd = d.compileStatement("UPDATE outbound_ledger SET rev = ? WHERE type = ? AND record_id = ?")
        for (k in keys) {
            rev++
            upd.clearBindings()
            upd.bindLong(1, rev); upd.bindString(2, k.type); upd.bindString(3, k.recordId)
            upd.executeUpdateDelete()
        }
        setMetaIn(d, "rev", rev.toString())
        return rev
    }

    override fun ledgerDelta(cursor: Long, grants: Collection<String>, limit: Int): LedgerDelta {
        val d = db
        d.beginTransactionNonExclusive()
        try {
            val rev = revIn(d)
            val from = PartnerLedger.effectiveCursor(cursor, rev)
            val cats = grants.distinct()
            val rows = if (cats.isEmpty()) emptyList() else d.rawQuery(
                "SELECT $ledgerColumns FROM outbound_ledger WHERE rev > ? AND category IN (${cats.joinToString(",") { "?" }}) ORDER BY rev LIMIT ${limit + 1}",
                (listOf(from.toString()) + cats).toTypedArray()
            ).use { c -> buildList { while (c.moveToNext()) add(c.ledger()) } }
            d.setTransactionSuccessful()
            val hasMore = rows.size > limit
            return PartnerLedger.page(from, rev, rows.take(limit), hasMore)
        } finally {
            d.endTransaction()
        }
    }
}
