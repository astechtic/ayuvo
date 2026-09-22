package com.ayuvo.health.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Test

class AppThemeColorDefaultTest {
    @Test
    fun defaultIsBlue() {
        assertEquals("blue", AppThemeColor.DEFAULT_KEY)
        assertEquals(AppThemeColor.BLUE, AppThemeColor.fromKey(AppThemeColor.DEFAULT_KEY))
    }

    @Test
    fun missingOrUnknownKeyFallsBackToBlue() {
        assertEquals(AppThemeColor.BLUE, AppThemeColor.fromKey(null))
        assertEquals(AppThemeColor.BLUE, AppThemeColor.fromKey("no-such-colour"))
    }

    @Test
    fun storedChoicesAreKept() {
        AppThemeColor.entries.forEach { assertEquals(it, AppThemeColor.fromKey(it.key)) }
        assertEquals(AppThemeColor.ROSE, AppThemeColor.fromKey("rose"))
        assertEquals(18, AppThemeColor.entries.size)
    }
}
