package com.ayuvo.health.insights

import com.ayuvo.health.vitals.VitalsTestFiles
import com.ayuvo.health.vitals.storage.VitalScanRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** Finger camera scans fill Recovery / Health Age inputs only on days without a platform value (docs/camera-vitals.md §7). */
class InsightsScanFallbackTest {
    private val cfg = VitalsTestFiles.config
    private val d1 = LocalDate.of(2026, 9, 18)
    private val d2 = LocalDate.of(2026, 9, 19)
    private val d3 = LocalDate.of(2026, 9, 20)

    private fun env(value: Double?) =
        if (value == null) """{"status":"unavailable","value":null,"reason":"low_quality"}""" else """{"status":"valid","value":$value}"""

    private fun scan(
        id: String,
        day: LocalDate,
        hr: Double? = 62.0,
        rmssd: Double? = 48.0,
        resp: Double? = 14.0,
        mode: String = VitalScanRecord.MODE_FINGER,
        context: String = VitalScanRecord.CONTEXT_RESTING,
        quality: Double? = 85.0,
        reject: String? = null
    ) = VitalScanRecord(
        id = id, mode = mode, startMs = 0L, endMs = 60_000L, tzOffsetS = 0, localDay = day.toString(), durationMs = 60_000L,
        platform = "android", deviceModel = "Test", cameraJson = "{}", context = context, qualityScore = quality, rejectReason = reject,
        qualityJson = "{}", algoVersion = 1, updatedMs = 1L,
        resultsJson = """{"metrics":{"heart_rate":${env(hr)},"hrv_rmssd":${env(rmssd)},"hrv_sdnn":${env(30.0)},"respiratory_rate":${env(resp)}}}"""
    )

    @Test
    fun aScanFillsAMissingDayAndIsFlagged() {
        val series = linkedMapOf<String, Map<LocalDate, Double>>("resting_heart_rate" to mapOf(d1 to 58.0))
        val flagged = InsightsDataSource.withScanFallback(series, listOf(scan("a", d2, hr = 61.0), scan("b", d2, hr = 65.0)), cfg, d1, d3)
        // Median of the day's finger scans; HRV uses RMSSD on Android (hrv_kind "rmssd"), not SDNN.
        assertEquals(mapOf(d1 to 58.0, d2 to 63.0), series["resting_heart_rate"])
        assertEquals(mapOf(d2 to 48.0), series["hrv"])
        assertEquals(mapOf(d2 to 14.0), series["respiratory_rate"])
        assertEquals(mapOf("hrv" to setOf(d2), "respiratory_rate" to setOf(d2), "resting_heart_rate" to setOf(d2)), flagged)
    }

    @Test
    fun aPlatformDayIsNeverOverwritten() {
        val series = linkedMapOf<String, Map<LocalDate, Double>>(
            "hrv" to mapOf(d2 to 45.0),
            "resting_heart_rate" to mapOf(d2 to 57.0),
            "respiratory_rate" to mapOf(d2 to 15.5)
        )
        val flagged = InsightsDataSource.withScanFallback(series, listOf(scan("a", d2, hr = 80.0, rmssd = 20.0, resp = 20.0)), cfg, d1, d3)
        assertEquals(mapOf(d2 to 45.0), series["hrv"])
        assertEquals(mapOf(d2 to 57.0), series["resting_heart_rate"])
        assertEquals(mapOf(d2 to 15.5), series["respiratory_rate"])
        assertTrue(flagged.isEmpty())
    }

    @Test
    fun faceRejectedActiveAndLowQualityScansAreIgnored() {
        val series = linkedMapOf<String, Map<LocalDate, Double>>()
        val scans = listOf(
            scan("face", d1, mode = VitalScanRecord.MODE_FACE),
            scan("rejected", d2, reject = "motion"),
            scan("active", d2, context = VitalScanRecord.CONTEXT_AFTER_ACTIVITY),
            scan("poor", d3, quality = 60.0),
            scan("deleted", d3).copy(deleted = true)
        )
        val flagged = InsightsDataSource.withScanFallback(series, scans, cfg, d1, d3)
        assertTrue(flagged.isEmpty())
        assertTrue(series.values.all { it.isEmpty() })
    }

    @Test
    fun anUnavailableMetricFillsOnlyTheOthersAndDaysOutsideTheWindowAreDropped() {
        val series = linkedMapOf<String, Map<LocalDate, Double>>()
        val flagged = InsightsDataSource.withScanFallback(
            series, listOf(scan("a", d2, rmssd = null), scan("old", d1.minusDays(1))), cfg, d1, d3
        )
        assertEquals(setOf("respiratory_rate", "resting_heart_rate"), flagged.keys)
        assertEquals(mapOf(d2 to 62.0), series["resting_heart_rate"])
        assertTrue(series["hrv"].isNullOrEmpty())
    }
}
