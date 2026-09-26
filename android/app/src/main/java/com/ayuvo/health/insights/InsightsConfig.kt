package com.ayuvo.health.insights

import com.ayuvo.health.medications.logic.MedicationJson
import com.ayuvo.health.medications.logic.MedicationJson.arr
import com.ayuvo.health.medications.logic.MedicationJson.double
import com.ayuvo.health.medications.logic.MedicationJson.int
import com.ayuvo.health.medications.logic.MedicationJson.objOrNull
import com.ayuvo.health.medications.logic.MedicationJson.str
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull

/** One `[x, y]` point of a piecewise-linear table (reference tables, dose-response curves). */
typealias Points = List<Pair<Double, Double>>

/**
 * `assets/insights/insights_config.json`, a byte copy of `shared/insights/insights_config.json`
 * (docs/insights.md). Every weight, window, band, template and methodology text the engines and the
 * info sheets use comes from here; `scripts/insights_reference.py` is the normative algorithm.
 */
class InsightsConfig(val root: JsonObject) {

    data class Metric(
        val id: String,
        val direction: String,
        val androidType: String?,
        val label: String,
        val unit: String,
        val windowDays: Int,
        val minPoints: Int,
        val sdFloor: Double,
        val recent: String,
        val highConfidenceCoverage: Double,
        val highConfidenceMinN: Int,
        val trendDays: Int,
        val trendMinPoints: Int,
        val trendStablePctPerWeek: Double
    )

    data class RecoveryComponentSpec(
        val id: String,
        val metric: String,
        val mode: String,
        val weight: Double,
        val toleranceZ: Double,
        val durationShare: Double,
        val consistencyShare: Double
    )

    data class Band(val id: String, val label: String, val min: Int, val recommendation: String)

    data class Recovery(
        val bands: List<Band>,
        val components: List<RecoveryComponentSpec>,
        val consistencyMinNights: Int,
        val consistencyWindowDays: Int,
        val consistencyZeroAtMin: Double,
        val contributors: Map<String, String>,
        val heartComponents: List<String>,
        val loadHigh: Int,
        val loadVeryHigh: Int,
        val loadVeryHighRatio: Double,
        val subscoreCenter: Double,
        val subscoreSlope: Double,
        val source: String
    )

    data class TrainingLoad(
        val defaultIntensity: Double,
        val effortDivisor: Double,
        val highAbove: Double,
        val lightBelow: Double,
        val intenseMinIntensity: Double,
        val intenseMinMinutesWithoutEffort: Double,
        val intensityMin: Double,
        val intensityMax: Double,
        val labels: Map<String, String>,
        val lateHour: Int,
        val meanWindowDays: Int,
        val source: String
    )

    data class Marker(
        val id: String,
        val label: String,
        val method: String,
        val weight: Double,
        val minDays: Int,
        val capYears: Double,
        val series: String?,
        val unit: String,
        val source: String,
        /** `age_norm`: sex → table, or HRV kind → sex → table when [tablesByKind]. */
        val tables: Map<String, Points>,
        val tablesByKind: Map<String, Map<String, Points>>,
        val points: Points,
        val durationPoints: Points,
        val regularityPoints: Points,
        val minutesPoints: Points,
        val activeSharePoints: Points,
        val weeks: Int,
        val bmiPoints: Points,
        val bodyFatPoints: Map<String, Points>
    )

    data class HealthAge(
        val ageMin: Double,
        val ageMax: Double,
        val collectingDays: Int,
        val highMinMarkers: Int,
        val highRequires: String,
        val mediumMinMarkers: Int,
        val coreMarkers: List<String>,
        val daysPerYear: Double,
        val markers: List<Marker>,
        val minActualAge: Double,
        val minMarkers: Int,
        val paceDecliningAbove: Double,
        val paceImprovingBelow: Double,
        val paceMinPoints: Int,
        val paceWeeks: Int,
        val totalCapYears: Double,
        val windowDays: Int,
        val source: String
    )

    data class Area(val id: String, val label: String, val weight: Double)

    data class Rule(val id: String, val category: String, val template: String, val raw: JsonObject) {
        fun num(key: String): Double = raw.double(key) ?: error("rule $id has no $key")
    }

    data class ReduceNutrient(val id: String, val goalKey: String, val label: String, val unit: String)

    data class DailyReview(
        val areas: List<Area>,
        val averageMinDays: Int,
        val averageWindowDays: Int,
        val maxItemsPerCategory: Int,
        val notLoggedTemplate: String,
        val calorieTolerancePct: Double,
        val calorieZeroAtPct: Double,
        val macroTolerancePct: Double,
        val macroZeroAtPct: Double,
        val proteinMinShare: Double,
        val reduceNutrients: List<ReduceNutrient>,
        val rules: List<Rule>,
        val sleepTargetMin: Double,
        val trainingRestScore: Double
    )

    data class PatternPair(
        val id: String,
        val exposure: String,
        val outcome: String,
        val lagDays: Int,
        val decimals: Int,
        val unit: String,
        val moreWord: String,
        val lessWord: String,
        val template: String,
        val reviewCategory: String?
    )

    data class Patterns(
        val minAbsD: Double,
        val minAbsT: Double,
        val minGroup: Int,
        val pairs: List<PatternPair>,
        val partialTodayOutcomes: Set<String>,
        val shortSleepMarginMin: Double,
        val windowDays: Int,
        val source: String
    )

    data class Section(val heading: String, val body: String)
    data class Methodology(val title: String, val sections: List<Section>)

    data class Ai(
        val allowedNumbers: List<Double>,
        val blockedTerms: List<String>,
        val bulletMax: Int,
        val bulletsMax: Int,
        val headlineMax: Int,
        val maxOutputTokensCloud: Int,
        val maxOutputTokensLocal: Int,
        val statusCloud: String,
        val statusLocal: String,
        val tasks: Map<String, String>
    )

    val metrics: Map<String, Metric> = obj(root, "metrics").entries.associate { (id, v) -> id to metric(id, v as JsonObject) }
    val recovery: Recovery = recovery(obj(root, "recovery"))
    val trainingLoad: TrainingLoad = trainingLoad(obj(root, "training_load"))
    val healthAge: HealthAge = healthAge(obj(root, "health_age"))
    val dailyReview: DailyReview = dailyReview(obj(root, "daily_review"))
    val patterns: Patterns = patterns(obj(root, "patterns"))
    val minNightMinutes: Double = obj(root, "sleep").double("min_night_minutes")!!
    val disclaimers: Map<String, String> = obj(root, "disclaimers").entries.associate { it.key to (it.value as JsonPrimitive).content }
    val methodology: Map<String, Methodology> = obj(root, "methodology").entries.associate { (id, v) ->
        val o = v as JsonObject
        id to Methodology(o.str("title")!!, o.arr("sections")!!.map { s -> (s as JsonObject).let { Section(it.str("heading")!!, it.str("body")!!) } })
    }
    val ai: Ai = ai(obj(root, "ai"))

    fun metric(id: String): Metric = metrics[id] ?: error("unknown metric $id")

    companion object {
        const val ASSET_PATH = "insights/insights_config.json"
        const val PROMPT_ASSET_PATH = "insights/ai_explain.md"

        @Volatile
        var active: InsightsConfig? = null

        fun parse(text: String): InsightsConfig = InsightsConfig(MedicationJson.json.parseToJsonElement(text) as JsonObject)

        private fun obj(o: JsonObject, key: String): JsonObject = o.objOrNull(key) ?: error("config has no $key")

        private fun points(e: JsonElement?): Points = (e as? JsonArray).orEmpty().map { p ->
            val a = p as JsonArray
            (a[0] as JsonPrimitive).doubleOrNull!! to (a[1] as JsonPrimitive).doubleOrNull!!
        }

        private fun sexPoints(e: JsonElement?): Map<String, Points> =
            (e as? JsonObject)?.entries?.associate { it.key to points(it.value) }.orEmpty()

        private fun strings(e: JsonElement?): List<String> = MedicationJson.strings(e)

        private fun metric(id: String, o: JsonObject) = Metric(
            id = id,
            direction = o.str("direction")!!,
            androidType = o.objOrNull("health_type")?.str("android"),
            label = o.str("label")!!,
            unit = o.str("unit")!!,
            windowDays = o.int("window_days")!!,
            minPoints = o.int("min_points")!!,
            sdFloor = o.double("sd_floor")!!,
            recent = o.str("recent")!!,
            highConfidenceCoverage = o.double("high_confidence_coverage")!!,
            highConfidenceMinN = o.int("high_confidence_min_n")!!,
            trendDays = o.int("trend_days")!!,
            trendMinPoints = o.int("trend_min_points")!!,
            trendStablePctPerWeek = o.double("trend_stable_pct_per_week")!!
        )

        private fun recovery(o: JsonObject): Recovery {
            val lm = obj(o, "load_modifier")
            return Recovery(
                bands = o.arr("bands")!!.map { (it as JsonObject).let { b -> Band(b.str("id")!!, b.str("label")!!, b.int("min")!!, b.str("recommendation")!!) } },
                components = o.arr("components")!!.map {
                    val c = it as JsonObject
                    RecoveryComponentSpec(
                        id = c.str("id")!!, metric = c.str("metric")!!, mode = c.str("mode")!!, weight = c.double("weight")!!,
                        toleranceZ = c.double("tolerance_z") ?: 0.0, durationShare = c.double("duration_share") ?: 1.0,
                        consistencyShare = c.double("consistency_share") ?: 0.0
                    )
                },
                consistencyMinNights = o.int("consistency_min_nights")!!,
                consistencyWindowDays = o.int("consistency_window_days")!!,
                consistencyZeroAtMin = o.double("consistency_zero_at_min")!!,
                contributors = obj(o, "contributors").entries.associate { it.key to (it.value as JsonPrimitive).content },
                heartComponents = strings(o["heart_components"]),
                loadHigh = lm.int("high")!!,
                loadVeryHigh = lm.int("very_high")!!,
                loadVeryHighRatio = lm.double("very_high_ratio")!!,
                subscoreCenter = o.double("subscore_center")!!,
                subscoreSlope = o.double("subscore_slope")!!,
                source = o.str("source").orEmpty()
            )
        }

        private fun trainingLoad(o: JsonObject) = TrainingLoad(
            defaultIntensity = o.double("default_intensity")!!,
            effortDivisor = o.double("effort_divisor")!!,
            highAbove = o.double("high_above")!!,
            lightBelow = o.double("light_below")!!,
            intenseMinIntensity = o.double("intense_min_intensity")!!,
            intenseMinMinutesWithoutEffort = o.double("intense_min_minutes_without_effort")!!,
            intensityMin = o.double("intensity_min")!!,
            intensityMax = o.double("intensity_max")!!,
            labels = obj(o, "labels").entries.associate { it.key to (it.value as JsonPrimitive).content },
            lateHour = o.int("late_hour")!!,
            meanWindowDays = o.int("mean_window_days")!!,
            source = o.str("source").orEmpty()
        )

        private fun marker(o: JsonObject): Marker {
            val byKind = (o["tables_by_kind"] as? JsonPrimitive)?.booleanOrNull == true
            val tables = o.objOrNull("tables")
            return Marker(
                id = o.str("id")!!,
                label = o.str("label")!!,
                method = o.str("method")!!,
                weight = o.double("weight")!!,
                minDays = o.int("min_days")!!,
                capYears = o.double("cap_years")!!,
                series = o.str("series"),
                unit = o.str("unit").orEmpty(),
                source = o.str("source").orEmpty(),
                tables = if (byKind) emptyMap() else sexPoints(tables),
                tablesByKind = if (byKind) tables!!.entries.associate { it.key to sexPoints(it.value) } else emptyMap(),
                points = points(o["points"]),
                durationPoints = points(o["duration_points"]),
                regularityPoints = points(o["regularity_points"]),
                minutesPoints = points(o["minutes_points"]),
                activeSharePoints = points(o["active_share_points"]),
                weeks = o.int("weeks") ?: 0,
                bmiPoints = points(o["bmi_points"]),
                bodyFatPoints = sexPoints(o["body_fat_points"])
            )
        }

        private fun healthAge(o: JsonObject): HealthAge {
            val c = obj(o, "confidence")
            val p = obj(o, "pace")
            return HealthAge(
                ageMin = o.double("age_min")!!,
                ageMax = o.double("age_max")!!,
                collectingDays = o.int("collecting_days")!!,
                highMinMarkers = c.int("high_min_markers")!!,
                highRequires = c.str("high_requires")!!,
                mediumMinMarkers = c.int("medium_min_markers")!!,
                coreMarkers = strings(o["core_markers"]),
                daysPerYear = o.double("days_per_year")!!,
                markers = o.arr("markers")!!.map { marker(it as JsonObject) },
                minActualAge = o.double("min_actual_age")!!,
                minMarkers = o.int("min_markers")!!,
                paceDecliningAbove = p.double("declining_above")!!,
                paceImprovingBelow = p.double("improving_below")!!,
                paceMinPoints = p.int("min_points")!!,
                paceWeeks = p.int("weeks")!!,
                totalCapYears = o.double("total_cap_years")!!,
                windowDays = o.int("window_days")!!,
                source = o.str("source").orEmpty()
            )
        }

        private fun dailyReview(o: JsonObject): DailyReview {
            val n = obj(o, "nutrition")
            return DailyReview(
                areas = o.arr("areas")!!.map { (it as JsonObject).let { a -> Area(a.str("id")!!, a.str("label")!!, a.double("weight")!!) } },
                averageMinDays = o.int("average_min_days")!!,
                averageWindowDays = o.int("average_window_days")!!,
                maxItemsPerCategory = o.int("max_items_per_category")!!,
                notLoggedTemplate = o.str("not_logged_template")!!,
                calorieTolerancePct = n.double("calorie_tolerance_pct")!!,
                calorieZeroAtPct = n.double("calorie_zero_at_pct")!!,
                macroTolerancePct = n.double("macro_tolerance_pct")!!,
                macroZeroAtPct = n.double("macro_zero_at_pct")!!,
                proteinMinShare = n.double("protein_min_share")!!,
                reduceNutrients = o.arr("reduce_nutrients")!!.map {
                    (it as JsonObject).let { r -> ReduceNutrient(r.str("id")!!, r.str("goal_key")!!, r.str("label")!!, r.str("unit")!!) }
                },
                rules = o.arr("rules")!!.map { (it as JsonObject).let { r -> Rule(r.str("id")!!, r.str("category")!!, r.str("template")!!, r) } },
                sleepTargetMin = o.double("sleep_target_min")!!,
                trainingRestScore = o.double("training_rest_score")!!
            )
        }

        private fun patterns(o: JsonObject) = Patterns(
            minAbsD = o.double("min_abs_d")!!,
            minAbsT = o.double("min_abs_t")!!,
            minGroup = o.int("min_group")!!,
            pairs = o.arr("pairs")!!.map {
                val p = it as JsonObject
                PatternPair(
                    id = p.str("id")!!, exposure = p.str("exposure")!!, outcome = p.str("outcome")!!, lagDays = p.int("lag_days")!!,
                    decimals = p.int("decimals")!!, unit = p.str("unit")!!, moreWord = p.str("more_word")!!, lessWord = p.str("less_word")!!,
                    template = p.str("template")!!, reviewCategory = p.str("review_category")
                )
            },
            partialTodayOutcomes = strings(o["partial_today_outcomes"]).toSet(),
            shortSleepMarginMin = o.double("short_sleep_margin_min")!!,
            windowDays = o.int("window_days")!!,
            source = o.str("source").orEmpty()
        )

        private fun ai(o: JsonObject): Ai {
            val tokens = obj(o, "max_output_tokens")
            val labels = obj(o, "status_labels")
            return Ai(
                allowedNumbers = (o["allowed_numbers"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.doubleOrNull },
                blockedTerms = strings(o["blocked_terms"]),
                bulletMax = o.int("bullet_max")!!,
                bulletsMax = o.int("bullets_max")!!,
                headlineMax = o.int("headline_max")!!,
                maxOutputTokensCloud = tokens.int("cloud")!!,
                maxOutputTokensLocal = tokens.int("local")!!,
                statusCloud = labels.str("cloud")!!,
                statusLocal = labels.str("local")!!,
                tasks = obj(o, "tasks").entries.associate { it.key to (it.value as JsonPrimitive).content }
            )
        }
    }
}
