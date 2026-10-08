package com.ayuvo.health.partner.logic

import java.util.TreeMap

/** docs/partner-sync.md §10: the sender's outbound ledger (pure logic; the store pages it from SQLite). */
object PartnerLedger {

    /**
     * `ledger_refresh`: brings the ledger in line with the current shareable records. New revs are assigned in
     * (type, record_id) order: changes first, then tombstones. Returns the full ledger sorted by key.
     */
    fun refresh(ledger: List<LedgerRow>, rev: Long, current: List<LedgerCurrent>, scopes: List<LedgerScope>): LedgerRefreshResult {
        val byKey = TreeMap<RecordKey, LedgerRow>(RecordKey.ORDER)
        for (r in ledger) byKey[r.key] = r
        val scopeOf = LinkedHashMap<String, String?>()
        for (s in scopes) scopeOf[s.type] = s.dayFrom
        val seen = HashSet<RecordKey>()
        val changed = mutableListOf<LedgerCurrent>()
        for (c in current.sortedWith(compareBy(RecordKey.ORDER) { it.key })) {
            seen += c.key
            val old = byKey[c.key]
            if (old == null || old.deleted || old.contentHash != c.contentHash || old.category != c.category || old.day != c.day) {
                changed += c
            }
        }
        val tomb = mutableListOf<RecordKey>()
        for ((key, r) in byKey) {
            if (r.deleted || key in seen || !scopeOf.containsKey(r.type)) continue
            val dayFrom = scopeOf[r.type]
            if (dayFrom != null && r.day != null && PartnerJson.compareCodePoints(r.day, dayFrom) < 0) continue
            tomb += key
        }
        var next = rev
        for (c in changed) {
            next++
            byKey[c.key] = LedgerRow(c.type, c.recordId, c.category, c.day, c.contentHash, next, false)
        }
        for (key in tomb) {
            next++
            byKey[key] = byKey.getValue(key).copy(rev = next, deleted = true)
        }
        return LedgerRefreshResult(next, byKey.values.toList(), changed.size, tomb.size)
    }

    /** `ledger_prune`: drops intraday-type rows whose day is before the window, without a rev. */
    fun prune(ledger: List<LedgerRow>, intradayDayFrom: String): List<LedgerRow> {
        val types = PartnerCatalog.current.types
        return ledger.filterNot { r ->
            (types[r.type]?.retentionDays ?: 0) != 0 && r.day != null && PartnerJson.compareCodePoints(r.day, intradayDayFrom) < 0
        }
    }

    /** `ledger_regrant`: every row of [category] gets a fresh rev, in (type, record_id) order. */
    fun regrant(ledger: List<LedgerRow>, rev: Long, category: String): LedgerRegrantResult {
        var next = rev
        val out = ledger.sortedWith(compareBy(RecordKey.ORDER) { it.key }).map { r ->
            if (r.category == category) { next++; r.copy(rev = next) } else r
        }
        return LedgerRegrantResult(next, out)
    }

    /**
     * `ledger_delta`: rows with rev > cursor in granted categories, ascending rev, at most [limit]. to_rev is the
     * last row's rev while has_more, else the device counter (so revs of ungranted categories are skipped).
     */
    fun delta(ledger: List<LedgerRow>, rev: Long, cursor: Long, grants: Collection<String>, limit: Int): LedgerDelta {
        val from = if (cursor > rev) 0L else cursor // the partner remembers more than this install issued: start over
        val granted = grants.toSet()
        val rows = ledger.filter { it.rev > from && it.category in granted }.sortedBy { it.rev }
        val page = rows.take(limit)
        val hasMore = rows.size > limit
        return page(from, rev, page, hasMore)
    }

    /** Builds the page from rows already selected `rev > cursor AND category IN grants ORDER BY rev LIMIT limit`. */
    fun page(from: Long, rev: Long, page: List<LedgerRow>, hasMore: Boolean): LedgerDelta {
        val toRev = if (hasMore) page.last().rev else rev
        return LedgerDelta(from, toRev, hasMore, page.map { LedgerDeltaKey(it.type, it.recordId, it.rev, it.deleted) })
    }

    /** The cursor a delta query actually starts from (a cursor ahead of the device counter restarts at 0). */
    fun effectiveCursor(cursor: Long, rev: Long): Long = if (cursor > rev) 0L else cursor
}
