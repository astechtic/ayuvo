package com.ayuvo.health.partner

import com.ayuvo.health.partner.data.Partner
import com.ayuvo.health.partner.identity.DeviceIdentity
import com.ayuvo.health.partner.identity.PartnerSecretStore
import com.ayuvo.health.partner.logic.PartnerCatalog
import com.ayuvo.health.partner.logic.PartnerJson
import com.ayuvo.health.partner.sources.PartnerSource
import com.ayuvo.health.partner.sources.SourceRecord
import com.ayuvo.health.partner.sources.SourceUnavailableException
import com.ayuvo.health.partner.sync.FramedChannel
import com.ayuvo.health.partner.sync.InMemoryFramedChannel
import com.ayuvo.health.partner.sync.LedgerRefresher
import com.ayuvo.health.partner.sync.LocalPeer
import com.ayuvo.health.partner.sync.OutboundRenderer
import com.ayuvo.health.partner.sync.PartnerSession
import com.ayuvo.health.partner.sync.SessionResult
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import java.time.LocalDate

const val TEST_NOW = 1_791_374_400_000L // 2026-10-07T12:00Z
val TEST_TODAY: LocalDate = LocalDate.parse("2026-10-07")

/** A source backed by a map the test edits. */
class FakeSource(override val type: String) : PartnerSource {
    val records = linkedMapOf<String, SourceRecord>()
    var fail = false
    /** When set, forEach suspends until it completes (a refresh "in flight"). */
    @Volatile var gate: kotlinx.coroutines.CompletableDeferred<Unit>? = null

    fun put(r: SourceRecord) { records[r.recordId] = r }

    override suspend fun forEach(dayFrom: String?, onRecord: (SourceRecord) -> Unit) {
        gate?.await()
        if (fail) throw SourceUnavailableException("test")
        for (r in records.values.toList()) if (dayFrom == null || r.day == null || r.day >= dayFrom) onRecord(r)
    }

    override suspend fun render(ids: Collection<String>): Map<String, SourceRecord> = ids.mapNotNull { id -> records[id]?.let { id to it } }.toMap()
}

object Recs {
    fun steps(day: String, sum: Int) = SourceRecord(
        "metric_day", "steps:$day", "vitals", day,
        PartnerJson.obj("type_id" to "steps", "day" to day, "unit" to "count", "sum" to sum, "count" to 1)
    )

    fun food(id: String, name: String, kcal: Int, day: String = "2026-10-07") = SourceRecord(
        "food_entry", id, "nutrition", day, PartnerJson.obj("name" to name, "logged_ms" to TEST_NOW - 3_600_000, "calories" to kcal)
    )

    fun medication(id: String, name: String) = SourceRecord(
        "medication", id, "medicines", null,
        PartnerJson.obj("name" to name, "form" to "tablet", "dose_quantity" to 1, "dose_unit" to "tablet", "start_date" to "2026-09-01",
            "status" to "active", "is_prn" to false)
    )

    fun heartRate(id: String, startMs: Long, bpm: Int, day: String = "2026-10-07") = SourceRecord(
        "sample", id, "vitals", day,
        PartnerJson.obj("type_id" to "heart_rate", "start_ms" to startMs, "end_ms" to startMs, "unit" to "bpm", "value" to bpm)
    )
}

class MemorySecrets : PartnerSecretStore {
    private val m = HashMap<String, String>()
    override fun load(key: String): String? = m[key]
    override fun save(key: String, value: String) { m[key] = value }
}

/** One phone in a test: its partner database, identity, sources and the sync pieces built on them. */
class TestPhone(val name: String) {
    val identity = DeviceIdentity(MemorySecrets())
    val id: String get() = identity.deviceId
    val store = InMemoryPartnerStore()
    val steps = FakeSource("metric_day")
    val food = FakeSource("food_entry")
    val meds = FakeSource("medication")
    val samples = FakeSource("sample")
    val sources: List<PartnerSource> = listOf(steps, food, meds, samples)
    /** The refresher's clock (for the "refreshed < 2 min ago" skip). */
    @Volatile var clock = TEST_NOW
    val refresher = LedgerRefresher(store, sources, today = { TEST_TODAY }, nowMs = { clock })
    val renderer = OutboundRenderer(store, sources) { TEST_NOW }

    fun asPartner(): Partner = Partner(
        ownerId = id, displayName = name, fingerprint = identity.fingerprint, x25519Pub = identity.x25519Public,
        ed25519Pub = identity.ed25519Public, platform = "android", pairedMs = TEST_NOW, updatedMs = TEST_NOW
    )

    fun session(peer: TestPhone, awaitLedger: suspend () -> Unit = {}, ledgerWaitMs: Long = PartnerSession.LEDGER_WAIT_MS): PartnerSession =
        PartnerSession(store, renderer, LocalPeer(id, appVersion = "test", name = name), peer.id, now = { TEST_NOW },
            awaitLedger = awaitLedger, ledgerWaitMs = ledgerWaitMs)

    fun received(peer: TestPhone) = store.received[peer.id].orEmpty()

    companion object {
        val ALL: List<String> get() = PartnerCatalog.current.categories

        /** Both phones trust each other and share [grants] both ways. */
        fun pair(a: TestPhone, b: TestPhone, grants: Collection<String> = ALL) {
            a.store.upsertPartner(b.asPartner())
            b.store.upsertPartner(a.asPartner())
            a.store.setGrantsOut(b.id, ALL.associateWith { it in grants }, TEST_NOW)
            b.store.setGrantsOut(a.id, ALL.associateWith { it in grants }, TEST_NOW)
        }

        /** One full session between [a] and [b] over an in-memory channel pair, after both refresh their ledgers. */
        suspend fun sync(a: TestPhone, b: TestPhone, tweak: (FramedChannel, FramedChannel) -> Unit = { _, _ -> }): Pair<SessionResult, SessionResult> {
            a.refresher.refresh()
            b.refresher.refresh()
            val (ca, cb) = InMemoryFramedChannel.pair()
            tweak(ca, cb)
            return coroutineScope {
                val ra = async { a.session(b).run(ca) }
                val rb = async { b.session(a).run(cb) }
                ra.await() to rb.await()
            }
        }
    }
}
