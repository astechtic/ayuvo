package com.ayuvo.health.cycle.engine

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull

/**
 * `assets/cycle/cycle_config.json`, a byte copy of `shared/cycle/cycle_config.json` (docs/cycle-tracking.md).
 * `scripts/cycle_reference.py` is the normative algorithm; every threshold the port reads comes from here.
 * Pure JVM (no Android classes) so the vector tests run as plain unit tests.
 */
class CycleConfig(val root: JsonObject) {

    data class Defaults(
        val cycleLength: Int, val periodLength: Int, val lutealLength: Int,
        val reminderDaysBefore: Int, val reminderTime: String
    )

    data class Limits(
        val cycleMin: Int, val cycleMax: Int, val periodMin: Int, val periodMax: Int,
        val settingCycleMin: Int, val settingCycleMax: Int, val settingPeriodMin: Int, val settingPeriodMax: Int,
        val lutealMin: Int, val lutealMax: Int, val painMax: Int, val noteMaxChars: Int
    )

    data class Prediction(
        val historyWindow: Int, val historyMinCycles: Int, val trimmedRangeMinCycles: Int,
        val defaultRangeDays: Int, val projectCycles: Int, val openPeriodExtraDays: Int
    )

    data class Variability(val minCycles: Int, val highRangeDays: Int, val highSdDays: Double)

    data class Fertile(val daysBeforeOvulation: Int, val daysAfterOvulation: Int)

    data class InsightRules(
        val recentCycles: Int, val typicalCycleLow: Int, val typicalCycleHigh: Int, val outOfRangeMinCount: Int,
        val lateDays: Int, val longPeriodDays: Int, val severePain: Double
    )

    data class FlowLevel(
        val key: String, val title: String, val rank: Int, val period: Boolean,
        val healthkit: String?, val healthConnect: String?
    )

    /** A catalogue entry (symptom, mood, pain location, phase, basis, symptom group). */
    data class Item(val key: String, val title: String, val group: String? = null, val about: String? = null, val healthkit: String? = null)

    data class InsightTemplate(val key: String, val template: String, val professional: Boolean)

    val algoVersion: String = root.s("algo_version")
    val defaults: Defaults = root.obj("defaults").let {
        Defaults(it.i("cycle_length"), it.i("period_length"), it.i("luteal_length"), it.i("reminder_days_before"), it.s("reminder_time"))
    }
    val limits: Limits = root.obj("limits").let {
        Limits(
            it.i("cycle_min"), it.i("cycle_max"), it.i("period_min"), it.i("period_max"),
            it.i("setting_cycle_min"), it.i("setting_cycle_max"), it.i("setting_period_min"), it.i("setting_period_max"),
            it.i("luteal_min"), it.i("luteal_max"), it.i("pain_max"), it.i("note_max_chars")
        )
    }
    val prediction: Prediction = root.obj("prediction").let {
        Prediction(
            it.i("history_window"), it.i("history_min_cycles"), it.i("trimmed_range_min_cycles"),
            it.i("default_range_days"), it.i("project_cycles"), it.i("open_period_extra_days")
        )
    }
    val variability: Variability = root.obj("variability").let {
        Variability(it.i("min_cycles"), it.i("high_range_days"), it.d("high_sd_days"))
    }
    val fertile: Fertile = root.obj("fertile").let { Fertile(it.i("days_before_ovulation"), it.i("days_after_ovulation")) }
    val insightRules: InsightRules = root.obj("insight_rules").let {
        InsightRules(
            it.i("recent_cycles"), it.i("typical_cycle_low"), it.i("typical_cycle_high"), it.i("out_of_range_min_count"),
            it.i("late_days"), it.i("long_period_days"), it.d("severe_pain")
        )
    }
    val flowLevels: List<FlowLevel> = root.arr("flow_levels").map {
        val o = it as JsonObject
        FlowLevel(o.s("key"), o.s("title"), o.i("rank"), (o["period"] as JsonPrimitive).booleanOrNull == true,
            o.optS("healthkit"), o.optS("health_connect"))
    }
    val symptoms: List<Item> = items("symptoms")
    val symptomGroups: List<Item> = items("symptom_groups")
    val moods: List<Item> = items("moods")
    val painLocations: List<Item> = items("pain_locations")
    val phases: List<Item> = items("phases")
    val basis: List<Item> = items("basis")
    val insights: List<InsightTemplate> = root.arr("insights").map {
        val o = it as JsonObject
        InsightTemplate(o.s("key"), o.s("template"), (o["professional"] as JsonPrimitive).booleanOrNull == true)
    }
    val disclaimer: String = root.s("disclaimer")
    val fertilityNote: String = root.s("fertility_note")

    private fun items(key: String): List<Item> = root.arr(key).map {
        val o = it as JsonObject
        Item(o.s("key"), o.s("title"), o.optS("group"), o.optS("about"), o.optS("healthkit"))
    }

    companion object {
        const val ASSET_PATH = "cycle/cycle_config.json"
        const val COACH_ASSET_PATH = "cycle/coach.json"

        private val json = Json { ignoreUnknownKeys = true; isLenient = false }

        fun parse(text: String): CycleConfig = CycleConfig(json.parseToJsonElement(text) as JsonObject)

        internal fun num(e: JsonElement?): Double? =
            (e as? JsonPrimitive)?.takeIf { it !is JsonNull && !it.isString }?.doubleOrNull

        private fun JsonObject.d(k: String): Double = num(this[k]) ?: error("cycle config: $k missing")
        private fun JsonObject.i(k: String): Int = d(k).toInt()
        private fun JsonObject.s(k: String): String = (this[k] as? JsonPrimitive)?.content ?: error("cycle config: $k missing")
        private fun JsonObject.optS(k: String): String? = (this[k] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content
        private fun JsonObject.obj(k: String): JsonObject = this[k] as? JsonObject ?: error("cycle config: $k missing")
        private fun JsonObject.arr(k: String): JsonArray = this[k] as? JsonArray ?: error("cycle config: $k missing")
    }
}
