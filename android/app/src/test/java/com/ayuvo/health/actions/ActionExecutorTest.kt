package com.ayuvo.health.actions

import com.ayuvo.health.medications.model.DoseAction
import com.ayuvo.health.medications.model.DoseStatus
import com.ayuvo.health.medications.model.DoseUnit
import com.ayuvo.health.medications.model.Medication
import com.ayuvo.health.medications.model.TimelineItem
import com.ayuvo.health.medications.model.TimelineKind
import com.ayuvo.health.medications.model.TimelineSlot
import com.ayuvo.health.medications.model.TodaySummary
import com.ayuvo.health.medications.model.TodayTimeline
import com.ayuvo.health.models.FastingSession
import com.ayuvo.health.models.WaterEntry
import com.ayuvo.health.models.WeightEntry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.Instant

class ActionExecutorTest {
    private val env = FakeActionEnvironment()
    private val executor = ActionExecutor(ActionsTestFiles.catalog, env)

    private fun run(id: String, params: Map<String, Any?> = emptyMap(), source: ActionSource = ActionSource.APP): ActionResult =
        runBlocking { executor.perform(ActionRequest(id, params, source)) }

    private fun failure(id: String, params: Map<String, Any?> = emptyMap(), source: ActionSource = ActionSource.APP): ActionException {
        try {
            run(id, params, source)
        } catch (e: ActionException) {
            return e
        }
        fail("expected an ActionException from $id")
        throw IllegalStateException()
    }

    @Test
    fun waterLogConvertsUnitsAndReportsRemaining() {
        env.prefsValue = env.prefsValue.copy(waterGoalMl = 2500)
        env.waterList += WaterEntry(date = Instant.ofEpochMilli(env.now - 3_600_000), milliliters = 1000)
        val r = run("water.log", mapOf("amount" to "16", "unit" to "floz"), ActionSource.SIRI)
        assertEquals(473L, r.fields["added_ml"])
        assertEquals(1473L, r.fields["intake_ml"])
        assertEquals(1027L, r.fields["remaining_ml"])
        assertEquals("metric:app:water", r.screen)
        assertTrue("water.log" in env.writes)
        val get = run("water.get")
        assertEquals(59L, get.fields["percent"])
    }

    @Test
    fun waterGoalScalesWithMultiDayRanges() {
        env.prefsValue = env.prefsValue.copy(waterGoalMl = 2000)
        val week = run("water.get", mapOf("range" to "last_7_days"))
        assertEquals(14000L, week.fields["goal_ml"])
    }

    @Test
    fun nutrientGetComparesSingleDayToTarget() {
        env.foodList += env.food("Eggs", 150, 12.4)
        env.foodList += env.food("Shake", 180, 30.0)
        env.foodList += env.food("Old", 500, 40.0, atMs = env.now - 3 * 86_400_000L)
        val r = run("nutrition.nutrient.get", mapOf("nutrient" to "protein"))
        assertEquals(42.4, r.fields["value"])
        assertEquals(140.0, r.fields["target"])
        assertEquals(97.6, r.fields["remaining"])
        assertEquals(30L, r.fields["percent"])
        val summary = run("nutrition.summary.get")
        assertEquals(330L, summary.fields["calories"])
        assertEquals(1670L, summary.fields["calories_remaining"])
    }

    @Test
    fun weightLogStoresKilogramsAndGetUsesPreferredUnit() {
        env.prefsValue = env.prefsValue.copy(massUnit = "lb")
        run("weight.log", mapOf("value" to 165), ActionSource.SIRI)
        assertEquals(74.84, env.weightList.single().weightKg, 0.01)
        val r = run("weight.get")
        assertEquals("lb", r.fields["unit"])
        assertEquals(165.0, r.fields["value"])
    }

    @Test
    fun bodyCompositionCalculatesBmi() {
        env.weightList += WeightEntry(date = Instant.ofEpochMilli(env.now), weightKg = 70.0)
        assertEquals(22.9, run("body.composition.get").fields["bmi"])
    }

    @Test
    fun fastingStartRejectsASecondFastAndStopEndsIt() {
        val start = run("fasting.start", mapOf("goal_hours" to 18))
        assertEquals(true, start.fields["active"])
        assertEquals(64_800L, start.fields["goal_s"])
        assertEquals("conflict", failure("fasting.start").code.raw)
        env.now += 2 * 3_600_000L
        val stop = run("fasting.stop")
        assertEquals(7200L, stop.fields["duration_s"])
        assertEquals("not_found", failure("fasting.stop").code.raw)
    }

    @Test
    fun foodLogRespectsActiveFastAndUsesEstimateForDescriptions() {
        env.estimate = { d -> env.food("Two eggs", 156, 12.6).copy(name = d) }
        val r = run("nutrition.food.log", mapOf("description" to "two eggs", "meal" to "breakfast"))
        assertEquals("breakfast", r.fields["meal"])
        assertEquals(156L, r.fields["calories"])
        env.fasts += FastingSession(startedAt = Instant.ofEpochMilli(env.now))
        val e = failure("nutrition.food.log", mapOf("name" to "Banana", "calories" to 105))
        assertEquals(ActionErrorCode.CONFLICT, e.code)
        assertEquals("active_fast", e.detail)
    }

    @Test
    fun healthMetricsNeverReadWithoutPermission() {
        env.healthData["steps"] = listOf(ActionMath.Sample(env.now - 1000, 4000.0), ActionMath.Sample(env.now - 500, 2500.0))
        assertEquals(ActionErrorCode.PERMISSION_REQUIRED, failure("health.metric.get", mapOf("metric" to "steps")).code)
        env.healthGranted = setOf("steps")
        val r = run("health.metric.get", mapOf("metric" to "steps"))
        assertEquals(6500.0, r.fields["value"])
        assertEquals("sum", r.fields["aggregation"])
        assertEquals(ActionErrorCode.NOT_FOUND, failure("health.metric.get", mapOf("metric" to "made_up_type")).code)
    }

    @Test
    fun appMetricsNeedNoHealthPermission() {
        env.waterList += WaterEntry(date = Instant.ofEpochMilli(env.now - 1000), milliliters = 300)
        env.waterList += WaterEntry(date = Instant.ofEpochMilli(env.now - 500), milliliters = 200)
        val r = run("health.metric.get", mapOf("metric" to "app:water", "aggregation" to "average"))
        assertEquals(250.0, r.fields["value"])
        assertEquals("ml", r.fields["unit"])
    }

    @Test
    fun workoutSetLogAddsExerciseAndFillsSets() {
        val first = run("workout.set.log", mapOf("exercise" to "bench press", "weight" to 60, "unit" to "kg", "reps" to 10))
        assertEquals("barbell bench press", first.fields["exercise"])
        assertEquals(1L, first.fields["set_number"])
        val second = run("workout.set.log", mapOf("exercise" to "0025", "weight" to 135, "unit" to "lb", "reps" to 8))
        assertEquals(2L, second.fields["set_number"])
        assertEquals(2L, second.fields["sets_today"])
        assertEquals(1089.9, second.fields["volume_kg"])
        assertEquals(ActionErrorCode.NOT_FOUND, failure("workout.set.log", mapOf("exercise" to "underwater yoga", "reps" to 5)).code)
    }

    private fun medsToday() {
        env.medsExist = true
        val morning = Instant.parse("2026-09-16T08:00:00Z").toEpochMilli()
        val evening = Instant.parse("2026-09-16T20:00:00Z").toEpochMilli()
        fun item(med: String, at: Long, status: DoseStatus) =
            TimelineItem(med, "s-$med", at, status, false, null, null, 1.0, DoseUnit.TABLET, TimelineKind.SCHEDULED)
        env.timeline = TodayTimeline(
            date = "2026-09-16",
            summary = TodaySummary(),
            groups = listOf(
                TimelineSlot("08:00", listOf(item("m1", morning, DoseStatus.DUE))),
                TimelineSlot("20:00", listOf(item("m2", evening, DoseStatus.SCHEDULED)))
            ),
            prn = emptyList(),
            medications = mapOf(
                "m1" to Medication(id = "m1", name = "Metformin", startDate = "2026-09-01", createdMs = 0, updatedMs = 0),
                "m2" to Medication(id = "m2", name = "Atorvastatin", startDate = "2026-09-01", createdMs = 0, updatedMs = 0)
            )
        )
    }

    @Test
    fun doseMarkResolvesMorningNextAndIdsOnlyAmongTodaysDoses() {
        medsToday()
        val r = run("medication.dose.mark", mapOf("dose" to "morning medicine", "action" to "taken"), ActionSource.SIRI)
        assertEquals("Metformin", r.fields["medication"])
        assertEquals("taken", r.fields["status"])
        assertEquals(DoseAction.TAKEN, env.marked.single().third)
        run("medication.dose.mark", mapOf("dose" to "atorvastatin", "action" to "snoozed", "snooze_minutes" to 30))
        assertEquals("m2", env.marked.last().first)
        val next = run("medications.next.get")
        assertEquals("m1|s-m1|${Instant.parse("2026-09-16T08:00:00Z").toEpochMilli()}", next.fields["id"])
        assertEquals(ActionErrorCode.NOT_FOUND, failure("medication.dose.mark", mapOf("dose" to "m9|s|1", "action" to "taken")).code)
    }

    @Test
    fun medicationReadsAreEmptyWithoutADatabase() {
        assertTrue(run("medications.today.list").items.isEmpty())
        assertEquals(0L, run("medications.adherence.get").fields["expected"])
    }

    @Test
    fun goalsUpdateChangesOnlyTheNamedGoal() {
        val r = run("goals.update", mapOf("goal" to "protein", "value" to 150))
        assertEquals(140L, r.fields["previous"])
        assertEquals(150, env.profileValue?.customProtein)
        assertEquals(2000, env.profileValue?.customCalories)
        run("goals.update", mapOf("goal" to "water", "value" to 3000))
        assertEquals(3000, env.prefsValue.waterGoalMl)
    }

    @Test
    fun coachSourceCannotMarkDosesOrSeeRecordsInSearch() {
        env.recordsExist = true
        val e = runBlocking { executor.validate(ActionRequest("medication.dose.mark", mapOf("dose" to "next", "action" to "taken"), ActionSource.COACH)) }
        assertTrue(e is ValidationResult.Failed && e.code == ActionErrorCode.NOT_ALLOWED)
        val coach = run("search.universal", mapOf("query" to "lipid"), ActionSource.COACH)
        assertTrue(coach.items.none { it["domain"] == "records" })
        val app = run("search.universal", mapOf("query" to "lipid"))
        assertTrue(app.items.any { it["domain"] == "records" })
    }

    @Test
    fun labValueReturnsLatestWithHistory() {
        env.recordsExist = true
        val r = run("records.labValue.get", mapOf("analyte" to "hba1c"))
        assertEquals(6.1, r.fields["value"])
        assertEquals("high", r.fields["flag"])
        assertEquals(2, (r.fields["history"] as List<*>).size)
    }

    @Test
    fun openActionsResolveTheirScreen() {
        assertEquals("section:records", run("open.section", mapOf("section" to "records")).screen)
        assertEquals("metric:resting_heart_rate", run("open.metric", mapOf("metric" to "resting_heart_rate")).screen)
        assertFalse("open.section" in env.writes)
    }

    @Test
    fun resultJsonCarriesActionAndFields() {
        val json = run("goals.get").toJson()
        assertEquals("goals.get", json["action"].toString().trim('"'))
        assertNull(json["items"])
    }
}
