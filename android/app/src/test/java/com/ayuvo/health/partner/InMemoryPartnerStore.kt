package com.ayuvo.health.partner

import com.ayuvo.health.partner.data.GrantOut
import com.ayuvo.health.partner.data.LedgerRefreshOutcome
import com.ayuvo.health.partner.data.Partner
import com.ayuvo.health.partner.data.PartnerStore
import com.ayuvo.health.partner.data.PartnerSyncState
import com.ayuvo.health.partner.logic.LedgerCurrent
import com.ayuvo.health.partner.logic.LedgerDelta
import com.ayuvo.health.partner.logic.LedgerRow
import com.ayuvo.health.partner.logic.LedgerScope
import com.ayuvo.health.partner.logic.MergeResult
import com.ayuvo.health.partner.logic.PartnerCatalog
import com.ayuvo.health.partner.logic.PartnerLedger
import com.ayuvo.health.partner.logic.PartnerMerge
import com.ayuvo.health.partner.logic.PartnerRecordRow
import com.ayuvo.health.partner.logic.ReceivedGrant
import com.ayuvo.health.partner.logic.RecordKey
import com.ayuvo.health.partner.logic.StoredRecord

/**
 * JVM stand-in for SqlitePartnerStore (no SQLite in unit tests): the same contract on maps, built on the pure
 * PartnerLedger / PartnerMerge logic. Every method is synchronized so concurrent sessions behave like transactions.
 */
class InMemoryPartnerStore : PartnerStore {
    private val meta = HashMap<String, String>().apply { put("rev", "0"); put("schema_version", "1") }
    private val partners = LinkedHashMap<String, Partner>()
    private val grantsOut = HashMap<String, MutableMap<String, GrantOut>>()
    private val grantsReceived = HashMap<String, List<ReceivedGrant>>()
    val received = HashMap<String, MutableMap<RecordKey, PartnerRecordRow>>()
    private val sync = HashMap<String, PartnerSyncState>()
    val ledger = HashMap<RecordKey, LedgerRow>()
    /** Every applyMerge call (to prove one transaction per page). */
    var mergeCalls = 0

    @Synchronized override fun meta(key: String): String? = meta[key]
    @Synchronized override fun setMeta(key: String, value: String) { meta[key] = value }
    @Synchronized override fun outboundRev(): Long = meta["rev"]!!.toLong()

    @Synchronized override fun upsertPartner(partner: Partner) {
        val old = partners[partner.ownerId]
        partners[partner.ownerId] = partner
        sync.putIfAbsent(partner.ownerId, PartnerSyncState(partner.ownerId))
        if (old != null && (old.x25519Pub != partner.x25519Pub || old.ed25519Pub != partner.ed25519Pub)) deletePartnerData(partner.ownerId)
    }

    @Synchronized override fun partner(ownerId: String): Partner? = partners[ownerId]
    @Synchronized override fun partners(includeUnpaired: Boolean): List<Partner> = partners.values.filter { includeUnpaired || it.trusted }
    @Synchronized override fun trustedOwnerIds(): List<String> = partners.values.filter { it.trusted }.map { it.ownerId }.sorted()
    @Synchronized override fun trustedPartnerByX25519(x25519Pub: String): Partner? = partners.values.firstOrNull { it.trusted && it.x25519Pub == x25519Pub }
    @Synchronized override fun unpair(ownerId: String, nowMs: Long) { partners[ownerId]?.let { partners[ownerId] = it.copy(unpairedMs = nowMs, updatedMs = nowMs) } }
    @Synchronized override fun setLastAddress(ownerId: String, host: String, port: Int, nowMs: Long) {
        partners[ownerId]?.let { partners[ownerId] = it.copy(lastHost = host, lastPort = port, updatedMs = nowMs) }
    }

    @Synchronized override fun removePartner(ownerId: String) {
        partners.remove(ownerId); grantsOut.remove(ownerId); grantsReceived.remove(ownerId); received.remove(ownerId); sync.remove(ownerId)
    }

    @Synchronized override fun grantsOut(ownerId: String): List<GrantOut> =
        PartnerCatalog.current.categories.mapNotNull { grantsOut[ownerId]?.get(it) }

    @Synchronized override fun grantedCategoriesOut(ownerId: String): List<String> = grantsOut(ownerId).filter { it.granted }.map { it.category }

    @Synchronized override fun setGrantsOut(ownerId: String, granted: Map<String, Boolean>, nowMs: Long): List<String> {
        val m = grantsOut.getOrPut(ownerId) { HashMap() }
        val before = m.values.filter { it.granted }.map { it.category }.toSet()
        val regranted = mutableListOf<String>()
        for (cat in PartnerCatalog.current.categories) {
            val on = granted[cat] ?: continue
            m[cat] = GrantOut(cat, on, nowMs)
            if (on && cat !in before) { regrantLedger(cat); regranted += cat }
        }
        return regranted
    }

    @Synchronized override fun grantsReceived(ownerId: String): List<ReceivedGrant> = grantsReceived[ownerId].orEmpty()

    @Synchronized override fun updateGrantsReceived(ownerId: String, grantedNow: Collection<String>, nowMs: Long): List<ReceivedGrant> {
        val rows = PartnerMerge.grantsReceivedUpdate(grantsReceived[ownerId].orEmpty(), grantedNow, nowMs)
        grantsReceived[ownerId] = rows
        return rows
    }

    @Synchronized override fun storedRevs(ownerId: String, keys: Collection<RecordKey>): Map<RecordKey, Long> {
        val m = received[ownerId] ?: return emptyMap()
        return keys.mapNotNull { k -> m[k]?.let { k to it.rev } }.toMap()
    }

    @Synchronized override fun applyMerge(ownerId: String, result: MergeResult, commitCursor: Boolean) {
        require(result.accepted)
        mergeCalls++
        val m = received.getOrPut(ownerId) { HashMap() }
        for (d in result.deletes) m.remove(d)
        for (u in result.upserts) m[u.key] = u
        if (commitCursor) sync[ownerId] = (sync[ownerId] ?: PartnerSyncState(ownerId)).copy(lastRev = result.cursor)
    }

    @Synchronized override fun records(ownerId: String, types: Collection<String>, days: Collection<String>?, limit: Int): List<StoredRecord> =
        received[ownerId].orEmpty().values.filter { it.type in types && (days == null || it.day in days) }
            .take(limit).map { StoredRecord(it.type, it.recordId, it.day, it.tsMs, it.data) }

    @Synchronized override fun recordCount(ownerId: String): Long = received[ownerId]?.size?.toLong() ?: 0L

    @Synchronized override fun retentionPrune(nowMs: Long): Int {
        var n = 0
        for (m in received.values) {
            val drop = PartnerMerge.retentionPrune(m.values.map { Triple(it.type, it.recordId, it.tsMs) }, nowMs)
            drop.forEach { m.remove(it) }
            n += drop.size
        }
        return n
    }

    @Synchronized override fun deletePartnerData(ownerId: String) {
        received.remove(ownerId)
        sync[ownerId] = (sync[ownerId] ?: PartnerSyncState(ownerId)).copy(lastRev = 0)
    }

    @Synchronized override fun syncState(ownerId: String): PartnerSyncState? = sync[ownerId]
    @Synchronized override fun updateSyncState(state: PartnerSyncState) { sync[state.ownerId] = state }

    @Synchronized override fun ledgerRow(type: String, recordId: String): LedgerRow? = ledger[RecordKey(type, recordId)]

    @Synchronized override fun refreshLedger(current: List<LedgerCurrent>, scopes: List<LedgerScope>): LedgerRefreshOutcome {
        val res = PartnerLedger.refresh(ledger.values.toList(), outboundRev(), current, scopes)
        ledger.clear()
        res.ledger.forEach { ledger[it.key] = it }
        meta["rev"] = res.rev.toString()
        return LedgerRefreshOutcome(res.rev, res.changed, res.tombstoned)
    }

    @Synchronized override fun pruneLedger(intradayDayFrom: String): Int {
        val kept = PartnerLedger.prune(ledger.values.toList(), intradayDayFrom).map { it.key }.toSet()
        val before = ledger.size
        ledger.keys.retainAll(kept)
        return before - ledger.size
    }

    @Synchronized override fun regrantLedger(category: String): Long {
        val res = PartnerLedger.regrant(ledger.values.toList(), outboundRev(), category)
        res.ledger.forEach { ledger[it.key] = it }
        meta["rev"] = res.rev.toString()
        return res.rev
    }

    @Synchronized override fun ledgerDelta(cursor: Long, grants: Collection<String>, limit: Int): LedgerDelta =
        PartnerLedger.delta(ledger.values.toList(), outboundRev(), cursor, grants, limit)
}
