package com.ayuvo.health.insights

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Edge cases the platform adapters hit that the shared vectors state only in general terms. */
class InsightsEngineEdgeTest {
    private val cfg = InsightsTestFiles.config
    private val today = InsightsFixtures.today

    @Test
    fun missingHrvRenormalisesTheRemainingWeights() {
        val r = RecoveryEngine.recovery(InsightsFixtures.inputs(withHrv = false), today, cfg)
        assertEquals("ok", r.status)
        val hrv = r.components.first { it.id == "hrv" }
        assertFalse(hrv.available)
        assertNull("a missing reading is missing, never 0", hrv.value)
        assertNotNull(r.score)
        assertTrue(r.components.filter { it.available }.none { it.id == "hrv" })
    }

    @Test
    fun shortNightUnderTwoHoursIsTreatedAsNoSleep() {
        val r = RecoveryEngine.recovery(InsightsFixtures.inputs(lastNightMin = 90.0), today, cfg)
        assertEquals("no_sleep", r.status)
        assertNull(r.score)
        // A real short night (over two hours) still scores, and lowers the sleep component.
        val short = RecoveryEngine.recovery(InsightsFixtures.inputs(lastNightMin = 250.0), today, cfg)
        assertEquals("ok", short.status)
        assertTrue(short.components.first { it.id == "sleep" }.subscore!! < 50.0)
    }

    @Test
    fun noWorkoutsMeansRestAndNoLoadModifier() {
        val r = RecoveryEngine.recovery(InsightsFixtures.inputs(), today, cfg)
        assertEquals("none", r.load!!.category)
        assertEquals(0, r.load!!.modifier)
        assertTrue(r.negatives.none { it.id == "training_load" })
        val load = TrainingLoad.trainingLoad(emptyList(), today, InsightsFixtures.zone, cfg)
        assertEquals("Rest", load.label)
        assertNull(load.ratio)
    }

    @Test
    fun insufficientHistoryIsCollectingWithCounts() {
        val r = RecoveryEngine.recovery(InsightsFixtures.inputs(days = 10), today, cfg)
        assertEquals("collecting", r.status)
        assertEquals(Collecting(9, 14), r.collecting)
        val h = HealthAgeEngine.healthAge(InsightsFixtures.inputs(days = 10), today, InsightsProfile(java.time.LocalDate.of(1986, 3, 1), "male", 180.0), cfg)
        assertEquals("collecting", h.status)
        assertNull(h.healthAge)
        val b = BaselineEngine.baseline(InsightsFixtures.series(5, 50.0, 2.0), today, cfg.metric("hrv"))
        assertEquals("insufficient", b.status)
        assertNull(b.mean)
    }

    @Test
    fun noFoodLogsLeaveNutritionOutOfTheDayScore() {
        val inputs = InsightsFixtures.inputs(tracking = TrackingFlags(nutrition = true, water = true))
        val rec = RecoveryEngine.recovery(inputs, today, cfg)
        val review = DailyReviewEngine.review(inputs.copy(recovery = rec), today, cfg)
        val nutrition = review.areas.first { it.id == "nutrition" }
        assertFalse(nutrition.included)
        assertNull("not logged is never shown as 0", nutrition.score)
        assertTrue(review.notLogged.any { it.params["area"] == "nutrition" })
        assertTrue(review.notLogged.any { it.params["area"] == "hydration" })
        assertNotNull(review.dayScore)
        assertTrue(review.wentWell.none { it.ruleId.startsWith("calories") })
    }

    @Test
    fun nothingTrackedGivesNoDayScore() {
        val review = DailyReviewEngine.review(InsightsInputs(), today, cfg)
        assertNull(review.dayScore)
        assertTrue(review.areas.none { it.included })
    }

    @Test
    fun healthAgeNeedsABirthdayAndAnAdult() {
        val inputs = InsightsFixtures.inputs(days = 100)
        assertEquals("no_birthday", HealthAgeEngine.healthAge(inputs, today, InsightsProfile(null, "male", 180.0), cfg).status)
        assertEquals("unsupported_age", HealthAgeEngine.healthAge(inputs, today, InsightsProfile(today.minusYears(15), "female", 160.0), cfg).status)
        val ok = HealthAgeEngine.healthAge(inputs, today, InsightsProfile(java.time.LocalDate.of(1986, 3, 1), "male", 180.0), cfg)
        assertEquals("ok", ok.status)
        assertNotNull(ok.healthAge)
    }

    @Test
    fun snapshotSuppliesRecoveryScoresToPatternsAndTheReview() {
        val snap = HealthAnalyticsEngine.snapshot(InsightsFixtures.bundle(InsightsFixtures.inputs(days = 190)), cfg)
        assertEquals(InsightsSnapshot.RECOVERY_HISTORY_DAYS, snap.recoveryHistory.size)
        assertEquals(today, snap.recoveryHistory.last().day)
        assertEquals(InsightsSnapshot.REVIEW_DAYS, snap.reviews.size)
        assertEquals(snap.recovery.score, snap.reviews.last().areas.first { it.id == "recovery" }.score)
        assertEquals(cfg.patterns.pairs.size, snap.patterns.size)
        // Recovery scores from the façade feed the recovery patterns (both groups counted when measurable).
        assertTrue(snap.baselines.any { it.metric.id == "sleep" && it.baseline.ok })
    }
}
