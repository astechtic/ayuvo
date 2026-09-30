package com.ayuvo.health.data.derived

import com.ayuvo.health.data.derived.DerivedMath.roundTo
import com.ayuvo.health.medications.logic.MedicationJson
import kotlinx.serialization.json.JsonObject

data class EnergyDayInput(
    val restingKcal: Double?,
    /** Active energy with Ayuvo's own workout burns excluded. */
    val activeKcal: Double?,
    val weightKg: Double?,
    val heightCm: Double?,
    val age: Double?,
    val sex: String?
)

data class EnergyDayResult(
    val bmrMifflin: Double?,
    val restingKcal: Double?,
    val tdee: Double?,
    val pal: Double?,
    val palBandIndex: Int?,
    val status: String
) {
    fun toJson(): JsonObject = MedicationJson.obj(
        "bmr_mifflin" to bmrMifflin, "resting_kcal" to restingKcal, "tdee" to tdee, "pal" to pal,
        "pal_band_index" to palBandIndex, "status" to status
    )
}

/** Energy derivations: ported from `mifflin` and `energy_day`. */
object EnergyDerivation {

    fun mifflin(weightKg: Double, heightCm: Double, age: Double, sex: String?, th: DerivedConfig.Thresholds): Double =
        10.0 * weightKg + 6.25 * heightCm - 5.0 * age + th.bySex(th.mifflin, sex)

    fun energyDay(inp: EnergyDayInput, cfg: DerivedConfig): EnergyDayResult {
        val th = cfg.thresholds
        var bmr: Double? = null
        if (DerivedMath.truthy(inp.weightKg) && DerivedMath.truthy(inp.heightCm) && inp.age != null) {
            bmr = mifflin(inp.weightKg!!, inp.heightCm!!, inp.age, inp.sex, th)
        }
        val bmrOut = roundTo(bmr, 0)
        val rest = inp.restingKcal?.takeIf { it > 0 } ?: return EnergyDayResult(bmrOut, null, null, null, null, "no_resting")
        if (bmr != null && rest < th.bmrMinShare * bmr) return EnergyDayResult(bmrOut, null, null, null, null, "partial_day")
        val active = inp.activeKcal?.takeIf { it != 0.0 } ?: 0.0
        val tdee = (rest + active) / (1.0 - th.tefShare)
        val pal = tdee / rest
        var band = 0
        for (c in th.palBands) if (pal >= c) band += 1
        return EnergyDayResult(bmrOut, roundTo(rest, 0), roundTo(tdee, 0), roundTo(pal, 2), band, "ok")
    }
}
