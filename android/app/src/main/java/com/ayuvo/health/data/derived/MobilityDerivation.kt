package com.ayuvo.health.data.derived

import com.ayuvo.health.data.derived.DerivedMath.roundTo
import com.ayuvo.health.medications.logic.MedicationJson
import kotlinx.serialization.json.JsonObject
import kotlin.math.pow

/** Readings from the last 7 days; nulls are skipped. */
data class GaitWeekInput(val walkingSpeed: List<Double?>, val doubleSupport: List<Double?>, val asymmetry: List<Double?>)

data class GaitWeekResult(
    val speedMedian: Double?,
    val slowGait: Boolean,
    val doubleSupportMedian: Double?,
    val asymmetryFlagCount: Int,
    val asymmetryFlag: Boolean
) {
    fun toJson(): JsonObject = MedicationJson.obj(
        "speed_median" to speedMedian, "slow_gait" to slowGait, "double_support_median" to doubleSupportMedian,
        "asymmetry_flag_count" to asymmetryFlagCount, "asymmetry_flag" to asymmetryFlag
    )
}

/** One headphone exposure sample. */
data class AudioSample(val startMs: Long, val endMs: Long, val db: Double)

data class AudioDayInput(val samples: List<AudioSample>)

data class AudioDayResult(val dosePct: Double, val loudMinutes: Double) {
    fun toJson(): JsonObject = MedicationJson.obj("dose_pct" to dosePct, "loud_minutes" to loudMinutes)
}

/** Mobility and hearing derivations: ported from `gait_week` and `audio_day`. */
object MobilityDerivation {

    fun gaitWeek(inp: GaitWeekInput, cfg: DerivedConfig): GaitWeekResult {
        val th = cfg.thresholds
        val sp = inp.walkingSpeed.filterNotNull().filter { it > 0 }
        val ds = inp.doubleSupport.filterNotNull()
        val asy = inp.asymmetry.filterNotNull().filter { it < 100 }
        val speed = if (sp.isNotEmpty()) DerivedMath.median(sp) else null
        val count = asy.count { it > th.asymmetryPct }
        return GaitWeekResult(
            speedMedian = roundTo(speed, 2),
            slowGait = speed != null && speed < th.slowGaitMps,
            doubleSupportMedian = if (ds.isNotEmpty()) roundTo(DerivedMath.median(ds), 1) else null,
            asymmetryFlagCount = count,
            asymmetryFlag = count >= th.asymmetryFlagCount
        )
    }

    fun audioDay(inp: AudioDayInput, cfg: DerivedConfig): AudioDayResult {
        val th = cfg.thresholds
        var dose = 0.0
        var loud = 0.0
        for (s in inp.samples) {
            val hours = maxOf(0L, s.endMs - s.startMs) / 3600000.0
            dose += hours * 2.0.pow((s.db - th.soundRefDb) / th.soundExchangeDb)
            if (s.db >= th.soundRefDb) loud += maxOf(0L, s.endMs - s.startMs) / 60000.0
        }
        return AudioDayResult(roundTo(dose * 100.0 / th.soundWeeklyHours, 2), roundTo(loud, 1))
    }
}
