package com.ayuvo.health.data.derived

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
 * `assets/derived/derived_config.json`, a byte copy of `shared/derived/derived_config.json`
 * (docs/derived-metrics.md). Every threshold the derivations use comes from here;
 * `scripts/derived_reference.py` is the normative algorithm.
 */
class DerivedConfig(val root: JsonObject) {

    data class Thresholds(
        val activeHourFirst: Int,
        val activeHourLast: Int,
        val activeHourSteps: Double,
        val asymmetryFlagCount: Int,
        val asymmetryPct: Double,
        /** scheme ("who" / "asian") → BMI category cut-offs. */
        val bmi: Map<String, List<Double>>,
        val bmrMinShare: Double,
        val briskCadence: Double,
        val daytimeMinMinutes: Int,
        val dipMinWakeMin: Int,
        val episodeGapHours: Double,
        val etaMinRate: Double,
        val ewmaAlpha: Double,
        val freeWakeWeekdays: List<Int>,
        /** scheme → [low, high] healthy BMI. */
        val healthyBmi: Map<String, List<Double>>,
        val heightConflictM: Double,
        val hrMaxLookbackDays: Int,
        val hrValidMax: Double,
        val hrValidMin: Double,
        val hrmaxZones: List<Double>,
        val hrrZones: List<Double>,
        val mifflin: Map<String, Double>,
        val palBands: List<Double>,
        val peakMinutes: Int,
        val phoneMinWearableSteps: Double,
        val phoneRatio: Double,
        val rateMinWeighins: Int,
        val rateWindowDays: Int,
        val regularityMinNights: Int,
        val regularityWindowDays: Int,
        val rhrMinCoverage: Double,
        val rhrMinSleepMin: Double,
        val rhrWindowMin: Int,
        val sedentaryEndHour: Int,
        val sedentaryMinMinutes: Int,
        val sedentaryStartHour: Int,
        val sedentaryStillMin: Int,
        val sleepNeedMin: Double,
        val slowGaitMps: Double,
        val socialMinFree: Int,
        val socialMinWork: Int,
        val soundExchangeDb: Double,
        val soundRefDb: Double,
        val soundWeeklyHours: Double,
        val sriMinPairs: Int,
        val stepBands: List<Double>,
        val strainDeltaBpm: Double,
        val strainMinDays: Int,
        val strainSdFloor: Double,
        val strainWindowDays: Int,
        val strideFactor: Map<String, Double>,
        val strideMinSteps: Double,
        /** Tanaka HRmax = tanaka[0] − tanaka[1] · age. */
        val tanaka: List<Double>,
        val tefShare: Double,
        val trimpK: Map<String, Double>,
        val trimpMinHrr: Double,
        val uthFactor: Double,
        val validWearMin: Int,
        val wakeWindowHours: Double,
        val wakeupMinGapMin: Double,
        val walkingMaxSteps: Double,
        val walkingMinMinutes: Int,
        val walkingMinSteps: Double,
        val walkingRunMin: Int
    ) {
        /** `table.get(key or "other", table["other"])`. */
        fun bySex(table: Map<String, Double>, sex: String?): Double =
            table[sex?.takeIf { it.isNotEmpty() } ?: "other"] ?: table.getValue("other")
    }

    val algoVersion: Int = root.int("algo_version") ?: 0
    val configVersion: Int = root.int("config_version") ?: 0
    val disclaimer: String = root.str("disclaimer").orEmpty()
    val categories: List<String> = MedicationJson.strings(root["categories"])
    val labels: Map<String, List<String>> =
        root.objOrNull("labels")?.entries?.associate { it.key to MedicationJson.strings(it.value) }.orEmpty()
    val thresholds: Thresholds = thresholds(root.objOrNull("thresholds") ?: error("config has no thresholds"))

    companion object {
        const val ASSET_PATH = "derived/derived_config.json"

        @Volatile
        var active: DerivedConfig? = null

        fun parse(text: String): DerivedConfig = DerivedConfig(MedicationJson.json.parseToJsonElement(text) as JsonObject)

        private fun numbers(e: JsonElement?): List<Double> = (e as? JsonArray).orEmpty().map { (it as JsonPrimitive).doubleOrNull!! }

        private fun ints(e: JsonElement?): List<Int> = numbers(e).map { it.toInt() }

        private fun numberMap(e: JsonElement?): Map<String, Double> =
            (e as? JsonObject)?.entries?.associate { it.key to (it.value as JsonPrimitive).doubleOrNull!! }.orEmpty()

        private fun numberLists(e: JsonElement?): Map<String, List<Double>> =
            (e as? JsonObject)?.entries?.associate { it.key to numbers(it.value) }.orEmpty()

        private fun thresholds(o: JsonObject): Thresholds {
            fun d(k: String): Double = o.double(k) ?: error("thresholds.$k missing")
            fun i(k: String): Int = o.int(k) ?: error("thresholds.$k missing")
            return Thresholds(
                activeHourFirst = i("active_hour_first"),
                activeHourLast = i("active_hour_last"),
                activeHourSteps = d("active_hour_steps"),
                asymmetryFlagCount = i("asymmetry_flag_count"),
                asymmetryPct = d("asymmetry_pct"),
                bmi = numberLists(o["bmi"]),
                bmrMinShare = d("bmr_min_share"),
                briskCadence = d("brisk_cadence"),
                daytimeMinMinutes = i("daytime_min_minutes"),
                dipMinWakeMin = i("dip_min_wake_min"),
                episodeGapHours = d("episode_gap_hours"),
                etaMinRate = d("eta_min_rate"),
                ewmaAlpha = d("ewma_alpha"),
                freeWakeWeekdays = ints(o["free_wake_weekdays"]),
                healthyBmi = numberLists(o["healthy_bmi"]),
                heightConflictM = d("height_conflict_m"),
                hrMaxLookbackDays = i("hr_max_lookback_days"),
                hrValidMax = d("hr_valid_max"),
                hrValidMin = d("hr_valid_min"),
                hrmaxZones = numbers(o["hrmax_zones"]),
                hrrZones = numbers(o["hrr_zones"]),
                mifflin = numberMap(o["mifflin"]),
                palBands = numbers(o["pal_bands"]),
                peakMinutes = i("peak_minutes"),
                phoneMinWearableSteps = d("phone_min_wearable_steps"),
                phoneRatio = d("phone_ratio"),
                rateMinWeighins = i("rate_min_weighins"),
                rateWindowDays = i("rate_window_days"),
                regularityMinNights = i("regularity_min_nights"),
                regularityWindowDays = i("regularity_window_days"),
                rhrMinCoverage = d("rhr_min_coverage"),
                rhrMinSleepMin = d("rhr_min_sleep_min"),
                rhrWindowMin = i("rhr_window_min"),
                sedentaryEndHour = i("sedentary_end_hour"),
                sedentaryMinMinutes = i("sedentary_min_minutes"),
                sedentaryStartHour = i("sedentary_start_hour"),
                sedentaryStillMin = i("sedentary_still_min"),
                sleepNeedMin = d("sleep_need_min"),
                slowGaitMps = d("slow_gait_mps"),
                socialMinFree = i("social_min_free"),
                socialMinWork = i("social_min_work"),
                soundExchangeDb = d("sound_exchange_db"),
                soundRefDb = d("sound_ref_db"),
                soundWeeklyHours = d("sound_weekly_hours"),
                sriMinPairs = i("sri_min_pairs"),
                stepBands = numbers(o["step_bands"]),
                strainDeltaBpm = d("strain_delta_bpm"),
                strainMinDays = i("strain_min_days"),
                strainSdFloor = d("strain_sd_floor"),
                strainWindowDays = i("strain_window_days"),
                strideFactor = numberMap(o["stride_factor"]),
                strideMinSteps = d("stride_min_steps"),
                tanaka = numbers(o["tanaka"]),
                tefShare = d("tef_share"),
                trimpK = numberMap(o["trimp_k"]),
                trimpMinHrr = d("trimp_min_hrr"),
                uthFactor = d("uth_factor"),
                validWearMin = i("valid_wear_min"),
                wakeWindowHours = d("wake_window_hours"),
                wakeupMinGapMin = d("wakeup_min_gap_min"),
                walkingMaxSteps = d("walking_max_steps"),
                walkingMinMinutes = i("walking_min_minutes"),
                walkingMinSteps = d("walking_min_steps"),
                walkingRunMin = i("walking_run_min")
            )
        }
    }
}
