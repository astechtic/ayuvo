package com.ayuvo.health.medications.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MedicationNotificationIdsTest {
    @Test
    fun `ids are stable, positive and above the app's fixed request codes`() {
        val a = MedicationNotificationIds.id("6f1c2d3e-0000-4000-8000-000000000001", 1_789_007_400_000L)
        assertEquals(a, MedicationNotificationIds.id("6f1c2d3e-0000-4000-8000-000000000001", 1_789_007_400_000L))
        assertTrue(a >= MedicationNotificationIds.MIN)
        assertNotEquals(a, MedicationNotificationIds.id("6f1c2d3e-0000-4000-8000-000000000001", 1_789_007_400_001L))
        assertEquals("med:5", MedicationNotificationIds.identity("med", 5))
    }

    @Test
    fun `no collisions across a realistic sample`() {
        val seen = HashSet<Int>()
        var collisions = 0
        for (m in 0 until 40) {
            val id = "med-%04d-4000-8000-%012d".format(m, m * 7919L)
            for (k in 0 until 50) {
                val t = 1_789_000_000_000L + k * 28_800_000L
                if (!seen.add(MedicationNotificationIds.id(id, t))) collisions++
            }
        }
        assertEquals(0, collisions)
    }

    @Test
    fun `action request codes differ per action and from the notification id`() {
        val base = MedicationNotificationIds.id("m", 1L)
        val codes = (0 until 3).map { MedicationNotificationIds.actionRequestCode("m", 1L, it) }
        assertEquals(3, codes.toSet().size)
        assertTrue(codes.all { it >= MedicationNotificationIds.MIN && it != base })
    }
}
