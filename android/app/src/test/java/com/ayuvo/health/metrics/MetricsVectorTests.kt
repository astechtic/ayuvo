package com.ayuvo.health.metrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.io.File

/** One JUnit test per shared metrics vector file (docs/ui-structure.md §7). */
class BucketBoundsVectorsTest { @Test fun allCases() = MetricsVectors.assertAll("bucket_bounds.json") }
class AnchorStepVectorsTest { @Test fun allCases() = MetricsVectors.assertAll("anchor_step.json") }
class BucketSeriesVectorsTest { @Test fun allCases() = MetricsVectors.assertAll("bucket_series.json") }
class HeadlineVectorsTest { @Test fun allCases() = MetricsVectors.assertAll("headline.json") }
class SparklineVectorsTest { @Test fun allCases() = MetricsVectors.assertAll("sparkline.json") }
class FastingDaysVectorsTest { @Test fun allCases() = MetricsVectors.assertAll("fasting_days.json") }
class WorkoutsVectorsTest { @Test fun allCases() = MetricsVectors.assertAll("workouts.json") }
class RingsVectorsTest { @Test fun allCases() = MetricsVectors.assertAll("rings.json") }
class PinsVectorsTest { @Test fun allCases() = MetricsVectors.assertAll("pins.json") }
class CatalogResolveVectorsTest { @Test fun allCases() = MetricsVectors.assertAll("catalog_resolve.json") }
class AxisTicksVectorsTest { @Test fun allCases() = MetricsVectors.assertAll("axis_ticks.json") }
class XTicksVectorsTest { @Test fun allCases() = MetricsVectors.assertAll("x_ticks.json") }
class DrillDownVectorsTest { @Test fun allCases() = MetricsVectors.assertAll("drill_down.json") }
class SleepWindowVectorsTest { @Test fun allCases() = MetricsVectors.assertAll("sleep_window.json") }
class SleepOffsetVectorsTest { @Test fun allCases() = MetricsVectors.assertAll("sleep_offset.json") }
class SleepRangeVectorsTest { @Test fun allCases() = MetricsVectors.assertAll("sleep_range.json") }

val METRIC_VECTOR_FILES_WITH_RUNNERS = listOf(
    "bucket_bounds.json", "anchor_step.json", "bucket_series.json", "headline.json", "sparkline.json",
    "fasting_days.json", "workouts.json", "rings.json", "pins.json", "catalog_resolve.json",
    "axis_ticks.json", "x_ticks.json", "drill_down.json", "sleep_window.json", "sleep_offset.json", "sleep_range.json"
)

/** Every vector file in shared/metrics/test-vectors has a test class above (and every runner a file). */
class MetricsVectorCoverageTest {
    @Test
    fun everyVectorFileIsRun() {
        val dir = MetricsTestFiles.shared("test-vectors")
        assertNotNull("shared/metrics/test-vectors not found", dir)
        val files = dir!!.listFiles { f: File -> f.name.endsWith(".json") }.orEmpty().map { it.name }.sorted()
        assertEquals(METRIC_VECTOR_FILES_WITH_RUNNERS.sorted(), files)
    }
}
