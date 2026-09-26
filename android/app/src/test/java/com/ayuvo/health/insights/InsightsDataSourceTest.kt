package com.ayuvo.health.insights

import com.ayuvo.health.data.health.HealthDailyRollup
import com.ayuvo.health.data.health.HealthDataRepository
import com.ayuvo.health.data.health.HealthPageCommit
import com.ayuvo.health.data.health.HealthSampleRow
import com.ayuvo.health.data.health.HealthSleepCodes
import com.ayuvo.health.data.health.InMemoryHealthDataStore
import com.ayuvo.health.data.metrics.AppMetricFixtures
import com.ayuvo.health.data.metrics.AppMetricSnapshot
import com.ayuvo.health.models.Gender
import com.ayuvo.health.models.OptionalNutrientGoals
import com.ayuvo.health.models.UserProfile
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** The adapter over the Health Connect mirror and the app stores (docs/insights.md §3.1). */
class InsightsDataSourceTest {
    private val zone = AppMetricFixtures.zone
    private val today = LocalDate.of(2026, 9, 20)
    private val cfg = InsightsTestFiles.config
    private val settings = InsightsSettings(
        stepGoal = 9000, waterGoalMl = 2500, waterTracking = true, fastingTracking = false,
        fastingGoalMinutes = 16 * 60, optionalGoals = OptionalNutrientGoals()
    )

    private fun ms(iso: String) = AppMetricFixtures.ms(iso)

    private fun row(id: String, type: String, start: String, end: String, day: String, value: Double? = null, category: Int? = null) = HealthSampleRow(
        id = id, typeId = type, startMs = ms(start), endMs = ms(end), localDay = day, value = value, unit = "",
        categoryValue = category, sourceId = "watch", updatedMs = 1L
    )

    private fun store(): InMemoryHealthDataStore = runBlocking {
        val s = InMemoryHealthDataStore()
        s.commit(
            HealthPageCommit(
                rows = listOf(
                    // Last night 23:00 → 07:00, and the night before.
                    row("s1", "sleep", "2026-09-19T23:00", "2026-09-20T07:00", "2026-09-20", category = HealthSleepCodes.ASLEEP_UNSPECIFIED),
                    row("s2", "sleep", "2026-09-18T23:30", "2026-09-19T06:30", "2026-09-19", category = HealthSleepCodes.ASLEEP_UNSPECIFIED),
                    // HRV: two readings inside last night, one in the afternoon (excluded from the overnight value).
                    row("h1", "hrv_rmssd", "2026-09-20T02:00", "2026-09-20T02:00", "2026-09-20", value = 40.0),
                    row("h2", "hrv_rmssd", "2026-09-20T04:00", "2026-09-20T04:00", "2026-09-20", value = 50.0),
                    row("h3", "hrv_rmssd", "2026-09-20T15:00", "2026-09-20T15:00", "2026-09-20", value = 90.0),
                    row("w1", "workout", "2026-09-19T18:00", "2026-09-19T19:00", "2026-09-19")
                )
            )
        )
        // No HRV sample inside the 19th's night: the daily rollup is used and flagged.
        s.replaceDailyRollups("hrv_rmssd", listOf("2026-09-19"), listOf(HealthDailyRollup("hrv_rmssd", "2026-09-19", zone.id, avg = 61.0, count = 1)))
        s.replaceDailyRollups("steps", listOf("2026-09-19", "2026-09-20"), listOf(
            HealthDailyRollup("steps", "2026-09-19", zone.id, sum = 8432.0, count = 3, fromPlatformAggregate = true),
            HealthDailyRollup("steps", "2026-09-20", zone.id, sum = 1200.0, count = 0, fromPlatformAggregate = true)
        ))
        s
    }

    private val profile = UserProfile(gender = Gender.FEMALE, heightCm = 165.0, customCalories = 2000, customProtein = 120)

    private val snapshot = AppMetricSnapshot(
        food = listOf(
            AppMetricFixtures.food("2026-09-20T08:00", 500, protein = 30.0, fiber = 6.0),
            AppMetricFixtures.food("2026-09-20T13:00", 700, protein = 40.0)
        ),
        water = listOf(AppMetricFixtures.water("2026-09-20T09:00", 750), AppMetricFixtures.water("2026-09-20T12:00", 500)),
        weight = listOf(AppMetricFixtures.weight("2026-09-20T07:30", 62.0)),
        bodyFat = listOf(AppMetricFixtures.bodyFat("2026-09-20T07:30", 0.25)),
        workouts = listOf(AppMetricFixtures.workout("2026-09-20", null))
    )

    private fun build(hubOn: Boolean = true): InsightsBundle = runBlocking {
        val repo = HealthDataRepository(store()) { zone }
        InsightsDataSource { repo }.build(today, zone, snapshot, settings, profile, hubOn, cfg)
    }

    @Test
    fun overnightValuesUseTheNightWindowElseTheFlaggedRollup() {
        val b = build()
        val hrv = b.inputs.series("hrv")
        assertEquals(45.0, hrv[today]!!, 0.0)
        assertEquals(61.0, hrv[today.minusDays(1)]!!, 0.0)
        assertFalse("hrv" in b.fallbackByDay[today].orEmpty())
        assertTrue("hrv" in b.fallbackByDay[today.minusDays(1)].orEmpty())
        assertEquals(setOf("hrv"), b.inputsFor(today.minusDays(1)).overnightFallback)
        assertEquals("rmssd", b.inputs.hrvKind)
    }

    @Test
    fun sleepNightsStepsAndWorkoutsComeFromTheMirror() {
        val b = build()
        assertEquals(480.0, b.inputs.sleep[today]!!.asleepMin!!, 0.0)
        assertEquals(ms("2026-09-19T23:00"), b.inputs.sleep[today]!!.startMs)
        assertEquals(8432.0, b.inputs.series("steps")[today.minusDays(1)]!!, 0.0)
        assertEquals("platform aggregate counts even without rows", 1200.0, b.inputs.series("steps")[today]!!, 0.0)
        // One health workout plus the Ayuvo strength session.
        assertEquals(2, b.inputs.workouts.size)
        assertTrue(b.inputs.tracking.workouts)
    }

    @Test
    fun appLogsBecomeDailyTotalsAndTargets() {
        val b = build()
        val food = b.inputs.nutrition[today]!!
        assertEquals(1200.0, food["calories"]!!, 0.0)
        assertEquals(70.0, food["protein_g"]!!, 0.0)
        assertEquals(6.0, food["fiber_g"]!!, 0.0)
        assertNull("no entry had sugar: missing, not 0", food["sugar_g"])
        assertEquals(1250.0, b.inputs.waterMl[today]!!, 0.0)
        assertEquals(62.0, b.inputs.series("weight")[today]!!, 0.0)
        assertEquals(25.0, b.inputs.series("body_fat")[today]!!, 1e-9)
        assertEquals(2000.0, b.inputs.targets["calories"]!!, 0.0)
        assertEquals(9000.0, b.inputs.targets["steps"]!!, 0.0)
        assertEquals(50.0, b.inputs.targets["sugar_max_g"]!!, 0.0)
        assertTrue(b.inputs.tracking.water)
        assertFalse(b.inputs.tracking.fasting)
        assertEquals("female", b.profile.sex)
    }

    @Test
    fun withoutHealthSyncOnlyAppDataIsRead() {
        val b = build(hubOn = false)
        assertFalse(b.healthEnabled)
        assertTrue(b.inputs.sleep.isEmpty())
        assertTrue(b.inputs.series("hrv").isEmpty())
        assertEquals(1, b.inputs.workouts.size)
        assertEquals(1200.0, b.inputs.nutrition[today]!!["calories"]!!, 0.0)
        val snap = HealthAnalyticsEngine.snapshot(b, cfg)
        assertEquals("collecting", snap.recovery.status)
    }
}
