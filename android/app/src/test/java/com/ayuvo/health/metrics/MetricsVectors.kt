package com.ayuvo.health.metrics

import com.ayuvo.health.data.metrics.FastingSpan
import com.ayuvo.health.data.metrics.MetricAggregation
import com.ayuvo.health.data.metrics.MetricCatalogData
import com.ayuvo.health.data.metrics.MetricEntry
import com.ayuvo.health.data.metrics.MetricRange
import com.ayuvo.health.data.metrics.MetricsReference
import com.ayuvo.health.data.metrics.RegistryFacts
import com.ayuvo.health.data.metrics.WeekStart
import com.ayuvo.health.data.metrics.WorkoutSpan
import com.ayuvo.health.medications.logic.MedicationJson
import com.ayuvo.health.medications.logic.MedicationJson.double
import com.ayuvo.health.medications.logic.MedicationJson.int
import com.ayuvo.health.medications.logic.MedicationJson.long
import com.ayuvo.health.medications.logic.MedicationJson.str
import com.ayuvo.health.models.HealthDataType
import com.ayuvo.health.records.processing.RecordsVectors
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import java.io.File
import java.time.ZoneId

/** Locates shared metrics contract files from the Gradle unit-test working directory (android/app). */
object MetricsTestFiles {
    fun shared(relative: String): File? =
        listOf("../../shared/metrics/$relative", "../shared/metrics/$relative", "shared/metrics/$relative")
            .map(::File).firstOrNull { it.exists() }

    val catalog: MetricCatalogData by lazy {
        MetricCatalogData.parse(shared("metric_catalog.json")!!.readText())
    }

    fun registry(id: String): RegistryFacts? =
        HealthDataType.byId(id)?.let { RegistryFacts(it.category.id, it.aggregation.name, it.unit) }
}

/** Runs `shared/metrics/test-vectors/<file>` through [MetricsReference] (dispatch mirrors `run_case`). */
object MetricsVectors {
    data class Outcome(val file: String, val passed: Int, val total: Int, val failures: List<String>)

    fun run(file: String): Outcome {
        val f = MetricsTestFiles.shared("test-vectors/$file") ?: run { fail("shared/metrics/test-vectors/$file missing"); error("") }
        val root = MedicationJson.json.parseToJsonElement(f.readText()) as JsonObject
        val function = root.str("function") ?: error("$file has no function")
        val cases = root["cases"] as JsonArray
        val failures = mutableListOf<String>()
        var passed = 0
        for (c in cases) {
            val case = c as JsonObject
            val name = case.str("name") ?: "?"
            val expected = case["expected"]!!
            val actual = try {
                runCase(function, case["input"] as JsonObject)
            } catch (e: Throwable) {
                failures += "$name: threw ${e.javaClass.simpleName}: ${e.message}"
                continue
            }
            val diff = RecordsVectors.diff(expected, actual, "$")
            if (diff == null) passed++ else failures += "$name: $diff"
        }
        return Outcome(file, passed, cases.size, failures)
    }

    fun assertAll(file: String) {
        val o = run(file)
        println("VECTORS metrics/${o.file}: ${o.passed}/${o.total}")
        assertTrue("${o.file}: ${o.passed}/${o.total} passed\n" + o.failures.joinToString("\n"), o.failures.isEmpty() && o.total > 0)
    }

    private fun obj(vararg pairs: Pair<String, Any?>) = MedicationJson.obj(*pairs)

    private fun entries(input: JsonObject): List<MetricEntry> = (input["entries"] as JsonArray).map {
        val o = it as JsonObject
        MetricEntry(o.long("t_ms")!!, o.double("value"))
    }

    private fun strings(e: JsonElement?): List<String> = (e as? JsonArray).orEmpty().map { (it as JsonPrimitive).content }
    private fun nullableString(o: JsonObject, key: String): String? = (o[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content

    fun runCase(function: String, input: JsonObject): JsonElement {
        val catalog = MetricsTestFiles.catalog
        fun zone() = ZoneId.of(input.str("time_zone")!!)
        fun range() = MetricRange.fromRaw(input.str("range")!!)
        fun week() = WeekStart.fromRaw(input.str("week_start")!!)
        fun agg() = MetricAggregation.fromRaw(input.str("aggregation")!!)
        return when (function) {
            "bucket_bounds" -> {
                val b = MetricsReference.bucketBounds(range(), input.long("anchor_ms")!!, zone(), week(), input.long("now_ms")!!)
                obj(
                    "interval" to obj("start_ms" to b.startMs, "end_ms" to b.endMs),
                    "buckets" to b.buckets.map { obj("start_ms" to it.startMs, "end_ms" to it.endMs, "label" to it.label) },
                    "can_go_forward" to b.canGoForward
                )
            }
            "step_anchor" -> {
                val a = MetricsReference.stepAnchor(range(), input.long("anchor_ms")!!, input.int("direction")!!, zone(), input.long("now_ms")!!)
                obj("anchor_date" to a.date.toString(), "anchor_ms" to a.anchorMs)
            }
            "bucket_series" -> obj(
                "buckets" to MetricsReference.bucketSeries(entries(input), range(), input.long("anchor_ms")!!, zone(), week(), agg()).map {
                    obj("start_ms" to it.startMs, "end_ms" to it.endMs, "value" to it.value, "count" to it.count, "min" to it.min, "max" to it.max)
                }
            )
            "headline" -> {
                val h = MetricsReference.headline(entries(input), range(), input.long("anchor_ms")!!, zone(), week(), agg())
                obj("kind" to h.kind.name, "value" to h.value, "from_ms" to h.fromMs, "to_ms" to h.toMs, "days_with_data" to h.daysWithData)
            }
            "sparkline_7d" -> {
                val s = MetricsReference.sparkline7d(entries(input), input.long("now_ms")!!, zone(), agg())
                obj("values" to s.values, "min" to s.min, "max" to s.max, "has_data" to s.hasData)
            }
            "fasting_hours_per_day" -> {
                val spans = (input["sessions"] as JsonArray).map {
                    val o = it as JsonObject
                    FastingSpan(o.long("started_at_ms")!!, o.long("ended_at_ms"))
                }
                obj("days" to MetricsReference.fastingSecondsPerDay(spans, input.long("now_ms")!!, zone()).map { (d, s) -> obj("day" to d.toString(), "seconds" to s) })
            }
            "workout_stats_per_bucket" -> {
                val spans = (input["sessions"] as JsonArray).map {
                    val o = it as JsonObject
                    WorkoutSpan(nullableString(o, "diary_date"), o.long("started_at_ms")!!, (o.double("duration_s") ?: 0.0).toLong(), o.double("calories"))
                }
                obj(
                    "buckets" to MetricsReference.workoutStatsPerBucket(spans, range(), input.long("anchor_ms")!!, zone(), week()).map {
                        obj("start_ms" to it.startMs, "end_ms" to it.endMs, "count" to it.count, "duration_s" to it.durationS, "burn_kcal" to it.burnKcal, "burn_count" to it.burnCount)
                    }
                )
            }
            "ring_progress" -> {
                val r = MetricsReference.ringProgress(input.double("value"), input.double("goal"))
                obj("state" to r.state.raw, "progress" to r.progress, "percent" to r.percent, "over" to r.over)
            }
            "favourite_pins_migrate" -> {
                val r = MetricsReference.favouritePinsMigrate(
                    catalog, nullableString(input, "new_raw"), nullableString(input, "legacy_raw"),
                    strings(input["known_health_ids"]).toSet(), input.int("max")!!
                )
                obj("favourites" to r.favourites, "source" to r.source.raw)
            }
            "resolve_metric" -> {
                val r = MetricsReference.resolveMetric(catalog, input.str("key") ?: "", MetricsTestFiles::registry)
                obj(
                    "source" to r.source, "domain" to r.domain, "colour_hex" to r.colourHex, "colour_hex_dark" to r.colourHexDark,
                    "aggregation" to r.aggregation, "chart_kind" to r.chartKind, "unit" to r.unit, "goal_source" to r.goalSource,
                    "default_favourite_order" to r.defaultFavouriteOrder, "browse_hidden" to r.browseHidden,
                    "icon_android" to r.iconAndroid, "icon_ios" to r.iconIos
                )
            }
            else -> error("unknown function $function")
        }
    }
}
