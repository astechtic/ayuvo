package com.ayuvo.health.partner.sync

import android.util.Log
import com.ayuvo.health.partner.data.PartnerStore
import com.ayuvo.health.partner.logic.LedgerCurrent
import com.ayuvo.health.partner.logic.LedgerScope
import com.ayuvo.health.partner.logic.PartnerCatalog
import com.ayuvo.health.partner.sources.PartnerSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId

/** Outcome of one ledger refresh. [typeMs] is the wall time per type (read + hash + write), for profiling logs. */
data class LedgerRefreshSummary(
    val rev: Long,
    val changed: Int,
    val tombstoned: Int,
    val pruned: Int,
    val failedTypes: List<String>,
    val elapsedMs: Long = 0,
    val rows: Int = 0,
    val typeMs: Map<String, Long> = emptyMap(),
    /** The ledger-write part of [typeMs]. */
    val writeMs: Map<String, Long> = emptyMap()
)

/**
 * `ledger_refresh` + `ledger_prune` (docs/partner-sync.md §10), run at the start of every sync window and before
 * every export, on Dispatchers.IO. Scopes: full-scope types always see everything; window types (`metric_day`,
 * `derived_day`, `analytics_day`, `sleep_night`, `water_day`) see everything on the first full run (recorded as
 * partner_meta `ledger_full_done`) and `day_from = today − 30` after it; intraday types use `today − 7`.
 *
 * One type at a time: only that type's small [LedgerCurrent] rows (ids + hashes) are held, never the source rows. A
 * type whose store cannot be read is skipped (its ledger rows are left alone instead of being tombstoned) and the
 * full run is not marked done until every type succeeded once. Each type is written in one transaction, so a
 * `ledger_delta` read while a refresh runs sees a consistent ledger (some types refreshed, others not yet).
 *
 * Sync windows start the refresh with [refreshInBackground] so discovery is not delayed by it; sessions wait for it
 * (bounded) through [awaitInFlight].
 */
class LedgerRefresher(
    private val store: PartnerStore,
    private val sources: List<PartnerSource>,
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
    private val today: () -> LocalDate = { LocalDate.now(zone()) },
    private val nowMs: () -> Long = System::currentTimeMillis
) {
    private val mutex = Mutex()
    private val lock = Any()
    @Volatile private var inFlight: Deferred<LedgerRefreshSummary?>? = null

    /** When the last refresh finished (any caller), or null in this process so far. */
    @Volatile var lastCompletedMs: Long? = null
        private set

    /** Serializes refreshes and anything that must not interleave with one (exports read the ledger after it). */
    suspend fun <T> locked(block: suspend () -> T): T = mutex.withLock { block() }

    suspend fun refresh(): LedgerRefreshSummary = locked { refreshUnlocked() }

    /**
     * Starts a refresh on [scope] without waiting for it. Returns the running one when a refresh started this way is
     * still in flight, and null (nothing started) when a refresh finished less than [freshMs] ago.
     */
    fun refreshInBackground(scope: CoroutineScope, freshMs: Long = FRESH_MS): Deferred<LedgerRefreshSummary?>? = synchronized(lock) {
        inFlight?.takeIf { it.isActive }?.let { return it }
        val last = lastCompletedMs
        if (last != null && nowMs() - last in 0 until freshMs) return null
        val job = scope.async(Dispatchers.IO) {
            try {
                refresh()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.w(TAG, "ledger refresh failed: ${e.javaClass.simpleName}")
                null
            }
        }
        inFlight = job
        job
    }

    /** True while a refresh started by [refreshInBackground] is running. */
    val isRefreshing: Boolean get() = inFlight?.isActive == true

    /** Waits for a refresh started by [refreshInBackground], if one is running. Callers bound the wait. */
    suspend fun awaitInFlight() {
        inFlight?.takeIf { it.isActive }?.join()
    }

    suspend fun refreshUnlocked(): LedgerRefreshSummary = withContext(Dispatchers.IO) {
        val started = System.nanoTime()
        val catalog = PartnerCatalog.current
        val fullDone = store.meta(META_FULL_DONE) != null
        val day = today()
        val windowFrom = day.minusDays(catalog.refreshWindowDays.toLong()).toString()
        val intradayFrom = day.minusDays(catalog.intradayDays.toLong()).toString()
        var changed = 0
        var tombstoned = 0
        var rows = 0
        var rev = store.outboundRev()
        val failed = mutableListOf<String>()
        val typeMs = LinkedHashMap<String, Long>()
        val writeMs = LinkedHashMap<String, Long>()
        for (source in sources.sortedBy { it.type }) {
            val spec = catalog.types[source.type] ?: continue
            val typeStart = System.nanoTime()
            val dayFrom = when (spec.scope) {
                "intraday" -> intradayFrom
                "window" -> if (fullDone) windowFrom else null
                else -> null
            }
            val current = ArrayList<LedgerCurrent>()
            try {
                source.forEach(dayFrom) { current += it.ledgerCurrent() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.w(TAG, "source ${source.type} unavailable: ${e.javaClass.simpleName}")
                failed += source.type
                continue
            }
            val writeStart = System.nanoTime()
            val out = store.refreshLedger(current, listOf(LedgerScope(source.type, dayFrom)))
            writeMs[source.type] = (System.nanoTime() - writeStart) / 1_000_000
            changed += out.changed
            tombstoned += out.tombstoned
            rows += current.size
            rev = out.rev
            typeMs[source.type] = (System.nanoTime() - typeStart) / 1_000_000
        }
        if (!fullDone && failed.isEmpty()) store.setMeta(META_FULL_DONE, System.currentTimeMillis().toString())
        val pruned = store.pruneLedger(intradayFrom)
        val elapsed = (System.nanoTime() - started) / 1_000_000
        lastCompletedMs = nowMs()
        val summary = LedgerRefreshSummary(rev, changed, tombstoned, pruned, failed, elapsed, rows, typeMs, writeMs)
        Log.i(TAG, "ledger refresh ${if (fullDone) "window" else "full"}: $rows rows, $changed changed, $tombstoned tombstoned in $elapsed ms; per type $typeMs, of it writing $writeMs")
        summary
    }

    companion object {
        const val META_FULL_DONE = "ledger_full_done"
        /** A window skips its refresh when one finished less than this long ago. */
        const val FRESH_MS = 2 * 60_000L
        private const val TAG = "PartnerLedger"
    }
}
