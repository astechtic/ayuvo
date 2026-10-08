package com.ayuvo.health.partner.data

import com.ayuvo.health.partner.logic.LedgerCurrent
import com.ayuvo.health.partner.logic.LedgerDelta
import com.ayuvo.health.partner.logic.LedgerRow
import com.ayuvo.health.partner.logic.LedgerScope
import com.ayuvo.health.partner.logic.MergeResult
import com.ayuvo.health.partner.logic.ReceivedGrant
import com.ayuvo.health.partner.logic.RecordKey
import com.ayuvo.health.partner.logic.StoredRecord

/** A row of `partners`. Trusted while [unpairedMs] is null. */
data class Partner(
    val ownerId: String,
    val displayName: String,
    val fingerprint: String,
    val x25519Pub: String,
    val ed25519Pub: String,
    val platform: String?,
    val pairedMs: Long,
    val unpairedMs: Long? = null,
    val lastHost: String? = null,
    val lastPort: Int? = null,
    val updatedMs: Long
) {
    val trusted: Boolean get() = unpairedMs == null
}

/** A row of `partner_grants_out`: what I share with a partner. */
data class GrantOut(val category: String, val granted: Boolean, val updatedMs: Long)

/** A row of `partner_sync_state`. */
data class PartnerSyncState(
    val ownerId: String,
    /** Their revision I have committed (my cursor into their ledger). */
    val lastRev: Long = 0,
    /** My revision they have acknowledged. */
    val ackedRev: Long = 0,
    val lastSyncMs: Long? = null,
    val lastAttemptMs: Long? = null,
    val lastTransport: String? = null,
    val status: String = "pairing_required",
    val lastError: String? = null,
    val lastExportId: String? = null
)

/** Ledger refresh outcome as persisted (the full ledger is never loaded). */
data class LedgerRefreshOutcome(val rev: Long, val changed: Int, val tombstoned: Int)

/**
 * Transactional access to `ayuvo_partner.db` (docs/partner-sync.md §6, §8–§10). Received data is read-only
 * partner data kept apart from the user's own stores. Every multi-row write is one transaction, upserts are
 * UPDATE-then-INSERT (SQLite 3.18), and nothing loads a whole table: merges look up only the batch's keys and
 * delta pages are `rev > ? AND category IN (...) ORDER BY rev LIMIT ?` queries.
 */
interface PartnerStore {
    // -- meta ----------------------------------------------------------------------------------------
    fun meta(key: String): String?
    fun setMeta(key: String, value: String)

    /** My outbound revision counter (`partner_meta.rev`). */
    fun outboundRev(): Long

    // -- partners --------------------------------------------------------------------------------------
    /**
     * Stores (re-)paired trust. Re-pairing with changed keys drops that partner's received data and resets the
     * cursor to 0 in the same transaction; unchanged keys keep both (docs §4). Ensures a sync-state row.
     */
    fun upsertPartner(partner: Partner)
    fun partner(ownerId: String): Partner?
    fun partners(includeUnpaired: Boolean = true): List<Partner>
    /** Owner ids of trusted (not unpaired) partners: the KK lookup set and package_validate's `partners`. */
    fun trustedOwnerIds(): List<String>
    /** The trusted partner owning this X25519 static key, or null (KK responder lookup). */
    fun trustedPartnerByX25519(x25519Pub: String): Partner?
    /** Unpair: trust removed, data kept. */
    fun unpair(ownerId: String, nowMs: Long)
    fun setLastAddress(ownerId: String, host: String, port: Int, nowMs: Long)
    /** Remove partner: trust, grants, received data and sync state (cascade). */
    fun removePartner(ownerId: String)

    // -- grants ----------------------------------------------------------------------------------------
    fun grantsOut(ownerId: String): List<GrantOut>
    fun grantedCategoriesOut(ownerId: String): List<String>
    /**
     * Sets what I share with a partner. A category switched from off to on runs ledger_regrant in the same
     * transaction (docs §9). Returns the categories that were re-granted.
     */
    fun setGrantsOut(ownerId: String, granted: Map<String, Boolean>, nowMs: Long): List<String>
    fun grantsReceived(ownerId: String): List<ReceivedGrant>
    /** grants_received_update from a HELLO / package manifest, persisted. */
    fun updateGrantsReceived(ownerId: String, grantedNow: Collection<String>, nowMs: Long): List<ReceivedGrant>

    // -- received records ------------------------------------------------------------------------------
    /** Revs of the stored rows among [keys] (the only lookup merge_apply needs). */
    fun storedRevs(ownerId: String, keys: Collection<RecordKey>): Map<RecordKey, Long>
    /**
     * Writes one accepted merge result in ONE transaction: deletes, upserts and, when [commitCursor],
     * `partner_sync_state.last_rev = result.cursor`. A failure rolls the whole batch back.
     */
    fun applyMerge(ownerId: String, result: MergeResult, commitCursor: Boolean = true)
    fun records(ownerId: String, types: Collection<String>, days: Collection<String>? = null, limit: Int = 1000): List<StoredRecord>
    fun recordCount(ownerId: String): Long
    /** retention_prune for every partner: intraday rows older than their retention window. */
    fun retentionPrune(nowMs: Long): Int
    /** Delete partner data: the rows go and the cursor returns to 0 so a later sync re-sends what is shared. */
    fun deletePartnerData(ownerId: String)

    // -- sync state ------------------------------------------------------------------------------------
    fun syncState(ownerId: String): PartnerSyncState?
    fun updateSyncState(state: PartnerSyncState)

    // -- outbound ledger -------------------------------------------------------------------------------
    fun ledgerRow(type: String, recordId: String): LedgerRow?
    /** ledger_refresh for [scopes]: loads only in-scope rows, persists changed rows and the counter atomically. */
    fun refreshLedger(current: List<LedgerCurrent>, scopes: List<LedgerScope>): LedgerRefreshOutcome
    /** ledger_prune: drops intraday ledger rows before [intradayDayFrom], without a rev. */
    fun pruneLedger(intradayDayFrom: String): Int
    /** ledger_regrant: every row of [category] gets a fresh rev. Returns the new counter. */
    fun regrantLedger(category: String): Long
    /** ledger_delta: one page (≤ [limit]) of keys after [cursor] in [grants]. */
    fun ledgerDelta(cursor: Long, grants: Collection<String>, limit: Int): LedgerDelta
}
