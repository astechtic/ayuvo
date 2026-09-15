package com.ayuvo.health.ui.health

import com.ayuvo.health.models.HealthDataType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HealthHomeTilesTest {
    @Test
    fun blankOrInvalidFallsBackToDefaults() {
        assertEquals(HealthHomeTiles.DEFAULT, HealthHomeTiles.parse(null))
        assertEquals(HealthHomeTiles.DEFAULT, HealthHomeTiles.parse(""))
        assertEquals(HealthHomeTiles.DEFAULT, HealthHomeTiles.parse("nope,also_nope"))
    }

    @Test
    fun parsesDedupesDropsUnknownAndCaps() {
        val raw = "sleep, steps,steps,mystery,heart_rate,weight,blood_pressure,vo2_max,hydration,body_fat,distance,active_energy"
        val parsed = HealthHomeTiles.parse(raw)
        assertEquals(HealthHomeTiles.MAX_TILES, parsed.size)
        assertEquals(listOf(HealthDataType.SLEEP, HealthDataType.STEPS, HealthDataType.HEART_RATE), parsed.take(3))
        assertEquals("sleep,steps,heart_rate", HealthHomeTiles.serialize(listOf(HealthDataType.SLEEP, HealthDataType.STEPS, HealthDataType.HEART_RATE, HealthDataType.STEPS)))
        assertEquals(parsed, HealthHomeTiles.parse(HealthHomeTiles.serialize(parsed)))
    }

    @Test
    fun candidatesIncludeSdkTypesAndMirroredImports() {
        val candidates = HealthHomeTiles.candidates(setOf("hrv_sdnn"))
        assertTrue(HealthDataType.STEPS in candidates)
        assertTrue(HealthDataType.HRV_SDNN in candidates)
        assertTrue(HealthDataType.WALKING_SPEED !in candidates)
    }
}
