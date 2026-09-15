package com.ayuvo.health.data.health

import com.ayuvo.health.models.HealthDataType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

class HealthSleepAnalysisTest {
    private val start = Instant.parse("2026-09-12T22:00:00Z").toEpochMilli()
    private val h = 3_600_000L

    private fun stage(id: String, code: Int, from: Long, to: Long, source: String, day: String = "2026-09-13") = HealthSampleRow(
        id = id, typeId = HealthDataType.SLEEP.id, startMs = from, endMs = to, localDay = day,
        value = (to - from) / 1000.0, unit = "s", categoryValue = code, sourceId = source, updatedMs = to
    )

    @Test
    fun picksTheSourceWithTheLongestAsleepTimeAndUnionsOverlaps() {
        val watch = listOf(
            stage("w", HealthSleepCodes.IN_BED, start, start + 8 * h, "watch"),
            stage("w:0", HealthSleepCodes.LIGHT, start, start + 3 * h, "watch"),
            stage("w:1", HealthSleepCodes.DEEP, start + 3 * h, start + 5 * h, "watch"),
            stage("w:2", HealthSleepCodes.AWAKE, start + 5 * h, start + 5 * h + h / 2, "watch"),
            stage("w:3", HealthSleepCodes.REM, start + 5 * h + h / 2, start + 8 * h, "watch"),
            // Overlapping duplicate stage must not double count.
            stage("w:4", HealthSleepCodes.LIGHT, start + h, start + 2 * h, "watch"),
            stage("w:5", HealthSleepCodes.OUT_OF_BED, start + 8 * h, start + 9 * h, "watch")
        )
        val phone = listOf(
            stage("p", HealthSleepCodes.IN_BED, start + h, start + 7 * h, "phone"),
            stage("p:0", HealthSleepCodes.ASLEEP_UNSPECIFIED, start + h, start + 6 * h, "phone")
        )
        val night = HealthSleepAnalysis.nights(watch + phone).single()
        assertEquals("2026-09-13", night.nightOf)
        assertEquals("watch", night.sourceId)
        assertEquals(8 * 3600.0, night.inBedS, 0.0)
        assertEquals(7.5 * 3600.0, night.asleepS, 0.0)
        assertEquals(3 * 3600.0, night.lightS, 0.0)
        assertEquals(2 * 3600.0, night.deepS, 0.0)
        assertEquals(2.5 * 3600.0, night.remS, 0.0)
        assertEquals(0.5 * 3600.0, night.awakeS, 0.0)
        assertEquals(start, night.startMs)
        assertEquals(start + 8 * h, night.endMs)
    }

    @Test
    fun sessionWithoutStagesCountsAsAsleepAndInBedFallsBackToAllRows() {
        val rows = listOf(
            stage("s", HealthSleepCodes.IN_BED, start, start + 7 * h, "phone"),
            stage("s:0", HealthSleepCodes.ASLEEP_UNSPECIFIED, start, start + 7 * h, "phone")
        )
        val night = HealthSleepAnalysis.nights(rows).single()
        assertEquals(7 * 3600.0, night.asleepS, 0.0)
        assertEquals(7 * 3600.0, night.inBedS, 0.0)
    }

    @Test
    fun nightsAreKeyedByWakeDayAndTombstonesAreIgnored() {
        val rows = listOf(
            stage("a:0", HealthSleepCodes.LIGHT, start, start + 6 * h, "watch", day = "2026-09-13"),
            stage("b:0", HealthSleepCodes.LIGHT, start + 24 * h, start + 30 * h, "watch", day = "2026-09-14"),
            stage("c:0", HealthSleepCodes.LIGHT, start + 48 * h, start + 55 * h, "watch", day = "2026-09-15").copy(deleted = true)
        )
        val nights = HealthSleepAnalysis.nights(rows)
        assertEquals(listOf("2026-09-13", "2026-09-14"), nights.map { it.nightOf })
        assertNull(HealthSleepAnalysis.nightFor("2026-09-15", rows.filter { it.localDay == "2026-09-15" }))
    }

    @Test
    fun unionSecondsMergesTouchingAndNestedIntervals() {
        val rows = listOf(
            stage("1", 1, 0, 10_000, "x"),
            stage("2", 1, 5_000, 15_000, "x"),
            stage("3", 1, 15_000, 20_000, "x"),
            stage("4", 1, 30_000, 31_000, "x"),
            stage("5", 1, 30_200, 30_800, "x")
        )
        assertEquals(21.0, HealthSleepAnalysis.unionSeconds(rows), 1e-9)
        assertEquals(0.0, HealthSleepAnalysis.unionSeconds(emptyList()), 0.0)
    }
}
