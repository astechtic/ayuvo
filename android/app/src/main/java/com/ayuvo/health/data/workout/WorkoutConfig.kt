package com.ayuvo.health.data.workout

import android.content.Context
import com.ayuvo.health.l10n.ContractStrings
import com.ayuvo.health.medications.logic.MedicationJson
import com.ayuvo.health.medications.logic.MedicationJson.double
import com.ayuvo.health.medications.logic.MedicationJson.int
import com.ayuvo.health.medications.logic.MedicationJson.objOrNull
import com.ayuvo.health.medications.logic.MedicationJson.str
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull

/**
 * `assets/workout/workout_config.json`, a byte copy of `shared/workout/workout_config.json`
 * (docs/workouts-gps.md). `scripts/workout_reference.py` is the normative algorithm.
 */
class WorkoutConfig(val root: JsonObject) {

    data class Sport(
        val id: String,
        val title: String,
        val autoPauseSpeedMps: Double,
        val maxSpeedMps: Double,
        val hcExercise: String?,
        val hkActivity: String?,
        val metItemId: String?
    ) {
        /** Translated [title] for the screen; [title] stays English for saved workout names and Health Connect. */
        fun displayTitle(context: Context?): String = ContractStrings.text(context, "workout.sports.$id.title", title)
    }

    data class Thresholds(
        val detectBpm: Double,
        val detectHrr: Double,
        val elevationHysteresisM: Double,
        val hrValidMax: Double,
        val hrValidMin: Double,
        val hrmaxZones: List<Double>,
        val hrr1AbnormalBelow: Double,
        val hrrZones: List<Double>,
        /** sex → Keytel (2005) coefficients [intercept, hr, weight, age]. */
        val keytel: Map<String, List<Double>>,
        val keytelMinCoverage: Double,
        val maxGrade: Double,
        val maxHAccuracyM: Double,
        val maxHrr: Double,
        val maxSampleGapS: Int,
        val mergeGapMin: Int,
        val minHrr: Double,
        val minSegmentS: Double,
        val minWindowMin: Int,
        val recoveryToleranceS: Int,
        val trimpK: Map<String, Double>,
        /** Banister TRIMP weighting coefficient by sex (0.64 men, 0.86 women). */
        val trimpA: Map<String, Double>,
        val walkRunSplitMps: Double
    )

    val algoVersion: Int = root.int("algo_version") ?: 0
    val configVersion: Int = root.int("config_version") ?: 0
    val disclaimer: String = root.str("disclaimer").orEmpty()
    /** Translated [disclaimer] for the screen. */
    fun displayDisclaimer(context: Context?): String = ContractStrings.text(context, "workout.disclaimer", disclaimer)

    val sources: Map<String, String> =
        root.objOrNull("sources")?.entries?.associate { it.key to (it.value as JsonPrimitive).content }.orEmpty()
    val sports: Map<String, Sport> = (root.objOrNull("sports") ?: error("config has no sports")).entries.associate { (id, v) ->
        val o = v as JsonObject
        id to Sport(
            id = id, title = o.str("title").orEmpty(), autoPauseSpeedMps = o.double("auto_pause_speed_mps")!!,
            maxSpeedMps = o.double("max_speed_mps")!!, hcExercise = o.str("hc_exercise"), hkActivity = o.str("hk_activity"),
            metItemId = o.str("met_item_id")
        )
    }
    val thresholds: Thresholds = thresholds(root.objOrNull("thresholds") ?: error("config has no thresholds"))

    fun sport(id: String): Sport = sports[id] ?: error("unknown sport $id")

    companion object {
        const val ASSET_PATH = "workout/workout_config.json"

        @Volatile
        var active: WorkoutConfig? = null

        fun parse(text: String): WorkoutConfig = WorkoutConfig(MedicationJson.json.parseToJsonElement(text) as JsonObject)

        private fun numbers(e: JsonElement?): List<Double> = (e as? JsonArray).orEmpty().map { (it as JsonPrimitive).doubleOrNull!! }

        private fun thresholds(o: JsonObject): Thresholds {
            fun d(k: String): Double = o.double(k) ?: error("thresholds.$k missing")
            fun i(k: String): Int = o.int(k) ?: error("thresholds.$k missing")
            return Thresholds(
                detectBpm = d("detect_bpm"),
                detectHrr = d("detect_hrr"),
                elevationHysteresisM = d("elevation_hysteresis_m"),
                hrValidMax = d("hr_valid_max"),
                hrValidMin = d("hr_valid_min"),
                hrmaxZones = numbers(o["hrmax_zones"]),
                hrr1AbnormalBelow = d("hrr1_abnormal_below"),
                hrrZones = numbers(o["hrr_zones"]),
                keytel = o.objOrNull("keytel")?.entries?.associate { it.key to numbers(it.value) }.orEmpty(),
                keytelMinCoverage = d("keytel_min_coverage"),
                maxGrade = d("max_grade"),
                maxHAccuracyM = d("max_h_accuracy_m"),
                maxHrr = d("max_hrr"),
                maxSampleGapS = i("max_sample_gap_s"),
                mergeGapMin = i("merge_gap_min"),
                minHrr = d("min_hrr"),
                minSegmentS = d("min_segment_s"),
                minWindowMin = i("min_window_min"),
                recoveryToleranceS = i("recovery_tolerance_s"),
                trimpK = o.objOrNull("trimp_k")?.entries?.associate { it.key to (it.value as JsonPrimitive).doubleOrNull!! }.orEmpty(),
                trimpA = o.objOrNull("trimp_a")?.entries?.associate { it.key to (it.value as JsonPrimitive).doubleOrNull!! }.orEmpty(),
                walkRunSplitMps = d("walk_run_split_mps")
            )
        }
    }
}
