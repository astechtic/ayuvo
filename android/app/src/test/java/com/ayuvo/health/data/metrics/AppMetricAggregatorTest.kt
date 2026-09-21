package com.ayuvo.health.data.metrics

import com.ayuvo.health.data.metrics.AppMetricFixtures.bodyFat
import com.ayuvo.health.data.metrics.AppMetricFixtures.fast
import com.ayuvo.health.data.metrics.AppMetricFixtures.food
import com.ayuvo.health.data.metrics.AppMetricFixtures.ms
import com.ayuvo.health.data.metrics.AppMetricFixtures.water
import com.ayuvo.health.data.metrics.AppMetricFixtures.weight
import com.ayuvo.health.data.metrics.AppMetricFixtures.workout
import com.ayuvo.health.data.metrics.AppMetricFixtures.zone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppMetricAggregatorTest {
    private val now = ms("2026-09-17T20:00")
    private val week = WeekStart.MONDAY

    private fun series(id: AppMetricId, snap: AppMetricSnapshot, range: MetricRange, anchor: String) =
        AppMetricAggregator.series(id, snap, range, ms(anchor), now, zone, week)

    @Test
    fun caloriesSumPerCalendarDayAndEmptyDaysStayNull() {
        val snap = AppMetricSnapshot(food = listOf(food("2026-09-14T08:00", 500), food("2026-09-14T19:00", 700), food("2026-09-16T12:00", 900)))
        val w = series(AppMetricId.CALORIES, snap, MetricRange.W, "2026-09-17T00:00")
        assertEquals(7, w.size)
        assertEquals(ms("2026-09-14T00:00"), w[0].startMs)
        assertEquals(1200.0, w[0].value!!, 0.0)
        assertNull(w[1].value)
        assertEquals(900.0, w[2].value!!, 0.0)
        val h = AppMetricAggregator.headline(AppMetricId.CALORIES, snap, MetricRange.W, ms("2026-09-17T00:00"), now, zone, week)
        assertEquals(HeadlineKind.AVERAGE, h.kind)
        assertEquals(1050.0, h.value!!, 0.0)
        assertEquals(2, h.daysWithData)
    }

    @Test
    fun dayRangeHasHourlyBucketsIncludingDstDays() {
        val snap = AppMetricSnapshot(food = listOf(food("2026-03-29T08:30", 300)))
        val d = series(AppMetricId.CALORIES, snap, MetricRange.D, "2026-03-29T00:00")
        assertEquals(23, d.size)
        assertEquals(300.0, d.single { it.value != null }.value!!, 0.0)
    }

    @Test
    fun sixMonthBarsShowTheDailyAverage() {
        val snap = AppMetricSnapshot(water = listOf(water("2026-09-14T08:00", 1000), water("2026-09-14T09:00", 500), water("2026-09-15T08:00", 500)))
        val bucket = series(AppMetricId.WATER, snap, MetricRange.SIX_MONTHS, "2026-09-17T00:00").single { it.value != null }
        assertEquals(1000.0, bucket.value!!, 0.0)
        assertEquals(3, bucket.count)
    }

    @Test
    fun missingFiberIsSkippedNotZero() {
        val snap = AppMetricSnapshot(food = listOf(food("2026-09-14T08:00", 100, fiber = null), food("2026-09-15T08:00", 100, fiber = 4.0)))
        val w = series(AppMetricId.FIBER, snap, MetricRange.W, "2026-09-17T00:00")
        assertNull(w[0].value)
        assertEquals(4.0, w[1].value!!, 0.0)
    }

    @Test
    fun weightIsLatestPerBucketAndBodyFatIsPercent() {
        val snap = AppMetricSnapshot(
            weight = listOf(weight("2026-09-14T07:00", 80.0), weight("2026-09-14T21:00", 79.5)),
            bodyFat = listOf(bodyFat("2026-09-15T07:00", 0.215))
        )
        val w = series(AppMetricId.WEIGHT, snap, MetricRange.W, "2026-09-17T00:00")
        assertEquals(79.5, w[0].value!!, 0.0)
        assertEquals(79.5, w[0].min!!, 0.0)
        assertEquals(80.0, w[0].max!!, 0.0)
        assertEquals(21.5, series(AppMetricId.BODY_FAT, snap, MetricRange.W, "2026-09-17T00:00")[1].value!!, 1e-9)
    }

    @Test
    fun fastsAreSplitAcrossMidnightAndActiveFastRunsToNow() {
        val snap = AppMetricSnapshot(fasting = listOf(fast("2026-09-14T22:00", "2026-09-15T14:00"), fast("2026-09-17T18:00", null)))
        val w = series(AppMetricId.FASTING, snap, MetricRange.W, "2026-09-17T00:00")
        assertEquals(7200.0, w[0].value!!, 0.0)
        assertEquals(50400.0, w[1].value!!, 0.0)
        assertEquals(7200.0, w[3].value!!, 0.0)
    }

    @Test
    fun workoutBurnUsesOneSnapshotPerDayAndCountsSessions() {
        val snap = AppMetricSnapshot(
            workouts = listOf(
                workout("2026-09-14", 300, version = 1),
                workout("2026-09-14", 450, version = 2),
                workout("2026-09-15", null, seconds = 600)
            )
        )
        val burn = series(AppMetricId.WORKOUT_BURN, snap, MetricRange.W, "2026-09-17T00:00")
        assertEquals(450.0, burn[0].value!!, 0.0)
        assertNull(burn[1].value)
        val count = series(AppMetricId.WORKOUTS, snap, MetricRange.W, "2026-09-17T00:00")
        assertEquals(2.0, count[0].value!!, 0.0)
        assertEquals(1.0, count[1].value!!, 0.0)
        val minutes = series(AppMetricId.WORKOUT_MINUTES, snap, MetricRange.W, "2026-09-17T00:00")
        assertEquals(600.0, minutes[1].value!!, 0.0)
    }

    @Test
    fun sparklineHasSevenDaysEndingToday() {
        val snap = AppMetricSnapshot(food = listOf(food("2026-09-17T08:00", 400), food("2026-09-11T08:00", 200), food("2026-09-10T08:00", 999)))
        val s = AppMetricAggregator.sparkline(AppMetricId.CALORIES, snap, now, zone)
        assertEquals(7, s.values.size)
        assertEquals(200.0, s.values.first()!!, 0.0)
        assertEquals(400.0, s.values.last()!!, 0.0)
        assertNull(s.values[3])
    }
}
