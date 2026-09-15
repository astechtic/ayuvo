package com.ayuvo.health.records.data

import com.ayuvo.health.records.data.RecordRenderCache.CacheEntry
import org.junit.Assert.assertEquals
import org.junit.Test

class RecordRenderCacheTest {
    @Test
    fun nothingEvictedWithinCap() {
        val entries = listOf(CacheEntry("a", 40, 1), CacheEntry("b", 60, 2))
        assertEquals(emptyList<String>(), RecordRenderCache.evictions(entries, 100))
    }

    @Test
    fun evictsLeastRecentlyUsedUntilUnderCap() {
        val entries = listOf(
            CacheEntry("new", 50, 30),
            CacheEntry("old", 50, 10),
            CacheEntry("mid", 50, 20)
        )
        assertEquals(listOf("old"), RecordRenderCache.evictions(entries, 100))
        assertEquals(listOf("old", "mid"), RecordRenderCache.evictions(entries, 60))
        assertEquals(listOf("old", "mid", "new"), RecordRenderCache.evictions(entries, 0))
    }

    @Test
    fun defaultCapIs150Megabytes() {
        assertEquals(150L * 1024 * 1024, RecordRenderCache.DEFAULT_MAX_BYTES)
    }
}
