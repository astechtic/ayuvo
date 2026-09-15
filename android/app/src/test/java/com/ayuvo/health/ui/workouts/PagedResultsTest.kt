package com.ayuvo.health.ui.workouts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PagedResultsTest {
    private val items = (1..74).toList()

    @Test
    fun firstPageShowsOnePageSize() {
        val paged = PagedResults.firstPage(items)
        assertEquals(30, paged.visible.size)
        assertEquals(74, paged.all.size)
        assertTrue(paged.hasMore)
    }

    @Test
    fun shortResultsFitOnOnePage() {
        val paged = PagedResults.firstPage((1..12).toList())
        assertEquals(12, paged.visible.size)
        assertFalse(paged.hasMore)
        assertSame(paged, paged.loadingNextPageIfNeeded(11))
    }

    @Test
    fun nextPageLoadsOnlyWithinPrefetchThresholdOfTheEnd() {
        val paged = PagedResults.firstPage(items)
        assertSame(paged, paged.loadingNextPageIfNeeded(24))
        val second = paged.loadingNextPageIfNeeded(25)
        assertEquals(60, second.visible.size)
        val third = second.loadingNextPageIfNeeded(59)
        assertEquals(74, third.visible.size)
        assertFalse(third.hasMore)
        assertSame(third, third.loadingNextPageIfNeeded(73))
    }

    @Test
    fun emptyResultsHaveNothingToLoad() {
        val paged = PagedResults.firstPage(emptyList<Int>())
        assertTrue(paged.visible.isEmpty())
        assertFalse(paged.hasMore)
        assertSame(paged, paged.loadingNextPageIfNeeded(-1))
    }
}
