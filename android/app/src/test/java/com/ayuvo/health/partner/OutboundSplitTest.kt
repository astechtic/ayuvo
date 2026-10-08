package com.ayuvo.health.partner

import com.ayuvo.health.partner.logic.LedgerDelta
import com.ayuvo.health.partner.logic.PartnerJson
import com.ayuvo.health.partner.sync.OutboundRenderer
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/** docs §7.6 "Frame fit": an envelope too large for one frame is skipped on the network, counted, and its rev covered. */
class OutboundSplitTest {
    @Before
    fun setUp() {
        PartnerTestFiles.install()
    }

    private fun env(rev: Long, name: String): JsonObject = PartnerJson.obj(
        "type" to "food_entry", "id" to "f$rev", "category" to "nutrition", "rev" to rev, "deleted" to false,
        "updated_ms" to 1L, "day" to "2026-10-07", "data" to PartnerJson.obj("name" to name, "logged_ms" to 1L, "calories" to 1)
    )

    @Test
    fun oversizedEnvelopeIsSkippedCountedAndCovered() {
        val renderer = OutboundRenderer(InMemoryPartnerStore(), emptyList())
        val delta = LedgerDelta(0, 3, false, emptyList())
        var skipped = 0
        val pages = renderer.split(delta, listOf(env(1, "a"), env(2, "x".repeat(1500)), env(3, "b")), maxJsonBytes = 1000) { skipped++ }
        assertEquals(1, skipped)
        assertEquals(listOf("f1", "f3"), pages.flatMap { it.records }.map { PartnerJson.str(it["id"]) })
        assertEquals(3L, pages.last().toRev)
        assertEquals(false, pages.last().hasMore)
    }
}
