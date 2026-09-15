package com.ayuvo.health.services.health

import com.ayuvo.health.models.HealthAndroidTier
import com.ayuvo.health.models.HealthCategory
import com.ayuvo.health.models.HealthDataType
import com.ayuvo.health.models.HealthFeatureFlag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HealthHubPermissionsTest {
    private val none: (HealthFeatureFlag) -> Boolean = { false }
    private val all: (HealthFeatureFlag) -> Boolean = { true }
    private fun p(name: String) = "android.permission.health.$name"

    @Test
    fun coreSheetMatchesThePlan() {
        val expected = setOf(
            "READ_STEPS", "READ_DISTANCE", "READ_ACTIVE_CALORIES_BURNED", "READ_TOTAL_CALORIES_BURNED", "READ_FLOORS_CLIMBED",
            "READ_EXERCISE", "READ_WEIGHT", "READ_HEIGHT", "READ_BODY_FAT", "READ_HEART_RATE", "READ_RESTING_HEART_RATE",
            "READ_HEART_RATE_VARIABILITY", "READ_SLEEP"
        ).map(::p).toSet()
        assertEquals(expected, HealthHubPermissions.readPermissions(HealthAndroidTier.CORE, none))
        assertEquals(expected + HealthHubPermissions.HISTORY, HealthHubPermissions.requestPermissions(HealthAndroidTier.CORE, includeHistory = true, all))
        // History is only requested when the platform supports it.
        assertEquals(expected, HealthHubPermissions.requestPermissions(HealthAndroidTier.CORE, includeHistory = true, none))
    }

    @Test
    fun featureGatedTypesAppearOnlyWhenAvailable() {
        val withoutFeatures = HealthHubPermissions.readPermissions(HealthAndroidTier.EXTENDED, none)
        assertFalse(p("READ_SKIN_TEMPERATURE") in withoutFeatures)
        assertFalse(p("READ_PLANNED_EXERCISE") in withoutFeatures)
        assertFalse(p("READ_MINDFULNESS") in withoutFeatures)
        val withFeatures = HealthHubPermissions.readPermissions(HealthAndroidTier.EXTENDED, all)
        assertTrue(p("READ_SKIN_TEMPERATURE") in withFeatures)
        assertTrue(p("READ_PLANNED_EXERCISE") in withFeatures)
        assertTrue(p("READ_MINDFULNESS") in withFeatures)
        assertEquals(
            setOf(p("READ_BLOOD_GLUCOSE"), p("READ_BODY_TEMPERATURE"), p("READ_SKIN_TEMPERATURE")),
            HealthHubPermissions.categoryReadPermissions(HealthCategory.VITALS, all)
        )
        assertEquals(setOf(p("READ_MINDFULNESS")), HealthHubPermissions.categoryReadPermissions(HealthCategory.MENTAL_WELLBEING, all))
        assertTrue(HealthHubPermissions.categoryReadPermissions(HealthCategory.MENTAL_WELLBEING, none).isEmpty())
    }

    @Test
    fun grantedTypesFollowPermissionsIncludingSharedOnes() {
        val granted = HealthHubPermissions.grantedTypes(setOf(p("READ_STEPS"), p("READ_EXERCISE"), p("READ_MENSTRUATION")), none)
        assertEquals(
            setOf(HealthDataType.STEPS, HealthDataType.STEP_CADENCE, HealthDataType.WORKOUT, HealthDataType.CYCLING_CADENCE, HealthDataType.MENSTRUAL_FLOW, HealthDataType.MENSTRUATION_PERIOD),
            granted
        )
        assertTrue(HealthHubPermissions.grantedTypes(emptySet(), all).isEmpty())
    }

    @Test
    fun quotaAndBoundaryErrorsAreRecognisedByClassNameAndMessage() {
        assertTrue(HealthHubPermissions.isQuotaError(IllegalStateException("API call quota exceeded")))
        assertTrue(HealthHubPermissions.isQuotaError(RuntimeException("Rate limited by Health Connect")))
        assertTrue(HealthHubPermissions.isQuotaError(RemoteException()))
        assertTrue(HealthHubPermissions.isQuotaError(RuntimeException("wrapped", RemoteException())))
        assertFalse(HealthHubPermissions.isQuotaError(IllegalArgumentException("bad request")))
        assertTrue(HealthHubPermissions.isBoundaryError(IllegalArgumentException("Cannot read data older than 30 days without history permission")))
        assertFalse(HealthHubPermissions.isBoundaryError(IllegalStateException("binder died")))
    }

    /** Mirrors android.os.RemoteException by simple class name, which is what the detector matches. */
    private class RemoteException : Exception("Transaction failed")
}
