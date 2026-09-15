package com.ayuvo.health.data.health

import com.ayuvo.health.models.HealthDataType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.ZoneOffset
import kotlin.random.Random

class HealthRollupMathTest {
    private val dayMs = 86_400_000L
    private val base = 1_789_000_000_000L // 2026-09-10T12:26:40Z

    private fun row(
        type: HealthDataType, id: String, startMs: Long, value: Double?, endMs: Long = startMs,
        count: Int = 1, value2: Double? = null, value3: Double? = null, categoryValue: Int? = null, deleted: Boolean = false
    ) = HealthSampleRow(
        id = id, typeId = type.id, startMs = startMs, endMs = endMs, startOffsetS = 0, endOffsetS = 0,
        localDay = HealthDayKeys.localDay(type, startMs, endMs, 0, 0, ZoneOffset.UTC),
        value = value, value2 = value2, value3 = value3, unit = type.unit, categoryValue = categoryValue,
        count = count, sourceId = "src", updatedMs = startMs, deleted = deleted
    )

    @Test
    fun cumulativeSumsCountsAndTracksLast() {
        val type = HealthTypeDescriptor.of(HealthDataType.STEPS)
        val rows = listOf(
            row(HealthDataType.STEPS, "a", base, 100.0),
            row(HealthDataType.STEPS, "b", base + 60_000, 250.0),
            row(HealthDataType.STEPS, "c", base + 120_000, 50.0, deleted = true)
        )
        val r = HealthRollupMath.rebuildDaily(type, rows, "UTC").single()
        assertEquals(350.0, r.sum!!, 0.0)
        assertEquals(2, r.count)
        assertEquals(175.0, r.avg!!, 0.0)
        assertEquals(100.0, r.min!!, 0.0)
        assertEquals(250.0, r.max!!, 0.0)
        assertEquals(250.0, r.lastValue!!, 0.0)
        assertEquals(base + 60_000, r.lastAtMs)
    }

    @Test
    fun discreteAveragesAreWeightedAndMinMaxComeFromCondensedColumns() {
        val type = HealthTypeDescriptor.of(HealthDataType.HEART_RATE)
        val rows = listOf(
            row(HealthDataType.HEART_RATE, "a", base, 60.0, count = 4, value2 = 50.0, value3 = 90.0),
            row(HealthDataType.HEART_RATE, "b", base + 60_000, 80.0, count = 2, value2 = 70.0, value3 = 100.0),
            row(HealthDataType.HEART_RATE, "c", base + 120_000, 130.0) // single sample: its own value bounds it
        )
        val r = HealthRollupMath.rebuildDaily(type, rows, "UTC").single()
        assertEquals((60.0 * 4 + 80.0 * 2 + 130.0) / 7, r.avg!!, 1e-9)
        assertEquals(50.0, r.min!!, 0.0)
        assertEquals(130.0, r.max!!, 0.0)
        assertEquals(7, r.count)
        assertNull(r.v2Avg)
    }

    @Test
    fun bloodPressureFillsDiastolicColumns() {
        val type = HealthTypeDescriptor.of(HealthDataType.BLOOD_PRESSURE)
        val rows = listOf(
            row(HealthDataType.BLOOD_PRESSURE, "a", base, 120.0, value2 = 80.0),
            row(HealthDataType.BLOOD_PRESSURE, "b", base + 60_000, 130.0, value2 = 70.0)
        )
        val r = HealthRollupMath.rebuildDaily(type, rows, "UTC").single()
        assertEquals(125.0, r.avg!!, 0.0)
        assertEquals(120.0, r.min!!, 0.0)
        assertEquals(130.0, r.max!!, 0.0)
        assertEquals(75.0, r.v2Avg!!, 0.0)
        assertEquals(70.0, r.v2Min!!, 0.0)
        assertEquals(80.0, r.v2Max!!, 0.0)
    }

    @Test
    fun durationTypesSumSecondsAndCategoriesCount() {
        val workout = HealthTypeDescriptor.of(HealthDataType.WORKOUT)
        val w = HealthRollupMath.rebuildDaily(
            workout,
            listOf(row(HealthDataType.WORKOUT, "a", base, 1800.0, endMs = base + 1_800_000), row(HealthDataType.WORKOUT, "b", base + 3_600_000, 600.0, endMs = base + 4_200_000)),
            "UTC"
        ).single()
        assertEquals(2400.0, w.sum!!, 0.0)
        assertEquals(2400.0, w.durationS!!, 0.0)
        assertEquals(2, w.count)

        val flow = HealthTypeDescriptor.of(HealthDataType.MENSTRUAL_FLOW)
        val f = HealthRollupMath.rebuildDaily(
            flow,
            listOf(row(HealthDataType.MENSTRUAL_FLOW, "a", base, null, categoryValue = 2), row(HealthDataType.MENSTRUAL_FLOW, "b", base + 1000, null, categoryValue = 4)),
            "UTC"
        ).single()
        assertEquals(2, f.count)
        assertEquals(4.0, f.lastValue!!, 0.0)
        assertNull(f.sum)
    }

    @Test
    fun rowsAreGroupedByTheirLocalDay() {
        val type = HealthTypeDescriptor.of(HealthDataType.STEPS)
        val rows = listOf(row(HealthDataType.STEPS, "a", base, 1.0), row(HealthDataType.STEPS, "b", base + dayMs, 2.0), row(HealthDataType.STEPS, "c", base + 2 * dayMs, 3.0))
        val days = HealthRollupMath.rebuildDaily(type, rows, "UTC").map { it.day }
        assertEquals(listOf("2026-09-10", "2026-09-11", "2026-09-12"), days)
    }

    @Test
    fun incrementalMergeEqualsFullRebuild() {
        val random = Random(42)
        for (type in listOf(HealthDataType.STEPS, HealthDataType.HEART_RATE, HealthDataType.BLOOD_PRESSURE, HealthDataType.WORKOUT, HealthDataType.MENSTRUAL_FLOW)) {
            val descriptor = HealthTypeDescriptor.of(type)
            repeat(20) { trial ->
                val all = (0 until 12).map { i ->
                    val start = base + random.nextLong(0, 12 * 3_600_000L)
                    row(
                        type, "r$trial-$i", start,
                        value = random.nextDouble(40.0, 200.0),
                        endMs = start + random.nextLong(60_000L, 3_600_000L),
                        count = if (type.kind == com.ayuvo.health.models.HealthKind.SERIES) random.nextInt(1, 6) else 1,
                        value2 = random.nextDouble(30.0, 90.0),
                        value3 = random.nextDouble(200.0, 250.0),
                        categoryValue = random.nextInt(1, 5)
                    )
                }
                val split = random.nextInt(1, 11)
                val first = all.take(split)
                val rest = all.drop(split)
                val incremental = HealthRollupMath.mergeIncremental(
                    descriptor,
                    HealthRollupMath.rebuildDaily(descriptor, first, "UTC").single(),
                    rest
                )
                val full = HealthRollupMath.rebuildDaily(descriptor, all, "UTC").single()
                assertEquals(full.count, incremental.count)
                assertClose(full.sum, incremental.sum)
                assertClose(full.avg, incremental.avg)
                assertClose(full.min, incremental.min)
                assertClose(full.max, incremental.max)
                assertClose(full.durationS, incremental.durationS)
                assertClose(full.v2Avg, incremental.v2Avg)
                assertClose(full.v2Min, incremental.v2Min)
                assertClose(full.v2Max, incremental.v2Max)
                assertEquals(full.lastAtMs, incremental.lastAtMs)
                assertClose(full.lastValue, incremental.lastValue)
            }
        }
    }

    @Test
    fun hourlyRollupsBucketByLocalHour() {
        val type = HealthTypeDescriptor.of(HealthDataType.STEPS)
        val midnight = 1_788_998_400_000L // 2026-09-10T00:00:00Z
        val rows = listOf(
            row(HealthDataType.STEPS, "a", midnight + 30 * 60_000, 10.0),
            row(HealthDataType.STEPS, "b", midnight + 45 * 60_000, 20.0),
            row(HealthDataType.STEPS, "c", midnight + 13 * 3_600_000L, 5.0)
        )
        val hourly = HealthRollupMath.rebuildHourly(type, rows, "2026-09-10", ZoneOffset.UTC)
        assertEquals(listOf(0, 13), hourly.map { it.hour })
        assertEquals(30.0, hourly[0].sum!!, 0.0)
        assertEquals(5.0, hourly[1].sum!!, 0.0)
    }

    private fun assertClose(expected: Double?, actual: Double?) {
        if (expected == null || actual == null) {
            assertEquals(expected, actual)
        } else {
            assertEquals(expected, actual, 1e-6)
        }
    }
}
