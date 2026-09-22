package com.ayuvo.health.widget

import com.ayuvo.health.data.metrics.AppMetricId
import com.ayuvo.health.data.metrics.MetricKey
import com.ayuvo.health.medications.model.DoseStatus
import com.ayuvo.health.medications.model.DoseUnit
import com.ayuvo.health.medications.model.Medication
import com.ayuvo.health.medications.model.TimelineItem
import com.ayuvo.health.medications.model.TimelineKind
import com.ayuvo.health.medications.model.TimelineSlot
import com.ayuvo.health.medications.model.TodaySummary
import com.ayuvo.health.medications.model.TodayTimeline
import com.ayuvo.health.models.BodyFatEntry
import com.ayuvo.health.models.FastingSession
import com.ayuvo.health.models.WeightEntry
import com.ayuvo.health.ui.health.HealthTileUi
import com.ayuvo.health.ui.metrics.MetricTileUi
import com.ayuvo.health.widget.dashboard.DashboardInputs
import com.ayuvo.health.widget.dashboard.DashboardRender
import com.ayuvo.health.widget.dashboard.WidgetDashboardBuilder
import com.ayuvo.health.widget.dashboard.WidgetDashboardSnapshot
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

class WidgetDashboardBuilderTest {
    private val zone = ZoneId.of("Europe/London")
    private val now = ZonedDateTime.of(2026, 9, 22, 12, 0, 0, 0, zone).toInstant().toEpochMilli()
    private fun at(hour: Int, minute: Int = 0) = ZonedDateTime.of(2026, 9, 22, hour, minute, 0, 0, zone).toInstant().toEpochMilli()

    private fun inputs(
        medications: TodayTimeline? = null,
        activeFast: FastingSession? = null,
        stepSource: Boolean = false,
        waterTracking: Boolean = false,
        tiles: List<MetricTileUi> = emptyList(),
        goals: Map<String, Double> = emptyMap()
    ) = DashboardInputs(
        nowMs = now, zone = zone, themeHex = 0x0A84FF, weightMetric = true, waterUnitRaw = "ml",
        caloriesToday = 1_240.0, calorieGoal = 2_000.0,
        stepsToday = 6_512.0, stepSource = stepSource, stepGoal = 10_000.0,
        waterTracking = waterTracking, waterTodayMl = 1_200.0, waterGoalMl = 2_500.0,
        fastingTracking = activeFast != null, activeFast = activeFast,
        medications = medications, weights = emptyList(), bodyFats = emptyList(), workoutsToday = emptyList(),
        tiles = tiles, tileGoals = goals
    )

    private fun med(id: String, name: String) = Medication(id = id, name = name, startDate = "2026-09-01", createdMs = 0, updatedMs = 0)

    private fun item(id: String, atMs: Long, status: DoseStatus) = TimelineItem(
        medicationId = id, scheduleId = "s", scheduledAtMs = atMs, status = status, isLate = false, logId = null,
        snoozedUntilMs = null, doseQuantity = 1.0, doseUnit = DoseUnit.TABLET, kind = TimelineKind.SCHEDULED
    )

    private fun timeline(vararg items: TimelineItem) = TodayTimeline(
        date = "2026-09-22",
        summary = TodaySummary(total = items.size, taken = items.count { it.status == DoseStatus.TAKEN }),
        groups = listOf(TimelineSlot("all", items.toList())),
        prn = emptyList(),
        medications = mapOf("a" to med("a", "Metformin"), "b" to med("b", "Vitamin D"))
    )

    @Test
    fun missingSourcesStayNull() {
        val s = WidgetDashboardBuilder.build(inputs())
        assertEquals("2026-09-22", s.dayKey)
        assertEquals(1_240.0, s.eat.value!!, 0.0)
        assertNull("no step source, no steps", s.move.steps)
        assertFalse(s.move.connected)
        assertNull("water tracking off", s.drink.ml)
        assertNull(s.medications)
        assertNull(s.weight)
        assertNull(s.workouts)
        assertNull(s.fasting.activeStartedAtMs)
    }

    @Test
    fun latestBodyReadingsAndFast() {
        val fast = FastingSession(startedAt = Instant.ofEpochMilli(at(8)), goalMinutes = 960)
        val s = WidgetDashboardBuilder.build(
            inputs(activeFast = fast, stepSource = true, waterTracking = true).copy(
                weights = listOf(WeightEntry(date = Instant.ofEpochMilli(at(7)), weightKg = 72.4), WeightEntry(date = Instant.ofEpochMilli(at(6)), weightKg = 73.0)),
                bodyFats = listOf(BodyFatEntry(date = Instant.ofEpochMilli(at(7)), bodyFatFraction = 0.2))
            )
        )
        assertEquals(72.4, s.weight!!.value, 0.0)
        assertEquals(20.0, s.bodyFat!!.value, 1e-9)
        assertEquals(6_512.0, s.move.steps!!, 0.0)
        assertEquals(1_200.0, s.drink.ml!!, 0.0)
        assertEquals(240L, DashboardRender.fastingElapsedMinutes(s, now))
        // Boundary: the fasting goal (00:00 next day) ties midnight; nothing earlier.
        assertEquals(at(8) + 960 * 60_000L, DashboardRender.nextBoundaryMs(s, now, zone))
    }

    @Test
    fun nextDoseSkipsTerminalAndFallsBackToOverdue() {
        val s = WidgetDashboardBuilder.build(
            inputs(medications = timeline(item("a", at(8), DoseStatus.TAKEN), item("b", at(9), DoseStatus.DUE), item("a", at(20), DoseStatus.SCHEDULED)))
        )
        assertEquals(3, s.medications!!.total)
        assertEquals(1, s.medications!!.taken)
        val next = DashboardRender.nextDose(s, now, zone)!!
        assertEquals("Metformin", next.name)
        assertEquals(at(20), next.scheduledAtMs)
        assertFalse(next.due)
        assertEquals(at(20), DashboardRender.nextBoundaryMs(s, now, zone))
        // After 20:00 only the overdue 09:00 dose remains pending.
        val late = DashboardRender.nextDose(s, at(21), zone)!!
        assertEquals("Vitamin D", late.name)
        assertTrue(late.due)
    }

    @Test
    fun staleDayHidesDayScopedValues() {
        val tiles = listOf(
            MetricTileUi(MetricKey.App(AppMetricId.CALORIES), HealthTileUi("app:calories", "1,240", "kcal", HealthTileUi.CaptionKind.TODAY, numeric = 1_240.0, hasData = true)),
            MetricTileUi(MetricKey.App(AppMetricId.WEIGHT), HealthTileUi("app:weight", "72.4", "kg", HealthTileUi.CaptionKind.RELATIVE, captionMs = at(7), numeric = 72.4, hasData = true))
        )
        val s = WidgetDashboardBuilder.build(
            inputs(medications = timeline(item("a", at(20), DoseStatus.SCHEDULED)), tiles = tiles, goals = mapOf("app:calories" to 2_000.0))
        )
        assertEquals(0.62, s.tiles.getValue("app:calories").progress!!, 1e-9)
        assertNull("no goal, no bar", s.tiles.getValue("app:weight").progress)
        val tomorrow = at(12) + 24 * 3_600_000L
        assertFalse(DashboardRender.isToday(s, tomorrow, zone))
        assertEquals(DashboardRender.EMPTY, DashboardRender.tile(s, "app:calories", tomorrow, zone))
        assertEquals("72.4", DashboardRender.tile(s, "app:weight", tomorrow, zone).number)
        assertNull("yesterday's doses are not next", DashboardRender.nextDose(s, tomorrow, zone))
        assertEquals(DashboardRender.EMPTY, DashboardRender.tile(s, "steps", now, zone))
    }

    @Test
    fun progressNeedsValueAndGoal() {
        assertNull(DashboardRender.progress(null, 100.0))
        assertNull(DashboardRender.progress(10.0, null))
        assertNull(DashboardRender.progress(10.0, 0.0))
        assertEquals(1f, DashboardRender.progress(250.0, 100.0)!!, 0f)
    }

    @Test
    fun snapshotRoundTripsThroughJson() {
        val json = Json { ignoreUnknownKeys = true }
        val s = WidgetDashboardBuilder.build(inputs(medications = timeline(item("a", at(20), DoseStatus.SCHEDULED))))
        val text = json.encodeToString(WidgetDashboardSnapshot.serializer(), s)
        assertEquals(s, json.decodeFromString(WidgetDashboardSnapshot.serializer(), text))
    }
}
