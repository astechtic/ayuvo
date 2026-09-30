package com.ayuvo.health.data.intake

import com.ayuvo.health.data.intake.IntakeMath.roundTo
import com.ayuvo.health.medications.logic.MedicationJson
import kotlinx.serialization.json.JsonObject
import kotlin.math.sqrt

/** Days are "YYYY-MM-DD" keys; intake holds logged days only. */
data class EnergyBalanceInput(
    val day: String,
    val intake: Map<String, Double>,
    val tdee: Map<String, Double>,
    val weights: Map<String, Double>
)

data class EnergyBalanceResult(val balanceKcal: Double?, val adaptiveTdee: Double?, val trendChangeKg: Double?, val status: String) {
    fun toMap(): Map<String, Any?> = linkedMapOf(
        "balance_kcal" to balanceKcal, "adaptive_tdee" to adaptiveTdee, "trend_change_kg" to trendChangeKg, "status" to status
    )

    fun toJson(): JsonObject = MedicationJson.element(toMap()) as JsonObject
}

data class PairedDifferenceInput(val exposure: Map<String, Boolean>, val outcome: Map<String, Double?>, val lagDays: Int)

data class PairedDifferenceResult(val nExposed: Int, val nUnexposed: Int, val difference: Double?, val cohensD: Double?, val status: String) {
    fun toJson(): JsonObject = MedicationJson.obj(
        "n_exposed" to nExposed, "n_unexposed" to nUnexposed, "difference" to difference, "cohens_d" to cohensD, "status" to status
    )
}

/** Ports of `energy_balance` and `paired_difference`. */
object EnergyBalance {

    fun energyBalance(inp: EnergyBalanceInput, cfg: IntakeConfig): EnergyBalanceResult {
        val th = cfg.thresholds
        val day = inp.day
        val intake = inp.intake
        val tdee = inp.tdee
        val w = inp.weights
        val bal = if (day in intake && day in tdee) roundTo(intake.getValue(day) - tdee.getValue(day), 0) else null
        val n = th.adaptiveWindowDays
        val start = IntakeMath.addDays(day, -(n - 1))
        val logged = ArrayList<Double>()
        for (i in 0 until n) intake[IntakeMath.addDays(start, i)]?.let { logged += it }
        val weighins = w.keys.filter { start <= it && it <= day }
        if (logged.size < th.adaptiveMinIntakeDays || weighins.size < th.adaptiveMinWeighins) {
            return EnergyBalanceResult(bal, null, null, "insufficient")
        }
        val first = w.keys.min()
        val trend = HashMap<String, Double?>()
        var cur: Double? = null
        var d = first
        while (d <= day) {
            w[d]?.let { v -> cur = if (cur == null) v else cur!! + th.ewmaAlpha * (v - cur!!) }
            trend[d] = cur
            d = IntakeMath.addDays(d, 1)
        }
        val before = trend[IntakeMath.addDays(start, -1)] ?: trend.getValue(weighins.min())!!
        val change = trend.getValue(day)!! - before
        return EnergyBalanceResult(
            balanceKcal = bal,
            adaptiveTdee = roundTo(IntakeMath.mean(logged) - change * th.energyPerKg / n, 0),
            trendChangeKg = roundTo(change, 2),
            status = "ok"
        )
    }

    fun pairedDifference(inp: PairedDifferenceInput, cfg: IntakeConfig): PairedDifferenceResult {
        val th = cfg.thresholds
        val ex = ArrayList<Double>()
        val un = ArrayList<Double>()
        for (d in IntakeMath.sortedKeys(inp.exposure.keys)) {
            val o = inp.outcome[IntakeMath.addDays(d, inp.lagDays)] ?: continue
            if (inp.exposure.getValue(d)) ex += o else un += o
        }
        if (ex.size < th.pairMinGroup || un.size < th.pairMinGroup) {
            return PairedDifferenceResult(ex.size, un.size, null, null, "insufficient")
        }
        val me = IntakeMath.mean(ex)
        val mu = IntakeMath.mean(un)
        fun variance(v: List<Double>, m: Double): Double {
            var t = 0.0
            for (x in v) t += (x - m) * (x - m)
            return t / (v.size - 1)
        }
        val pooled = sqrt(((ex.size - 1) * variance(ex, me) + (un.size - 1) * variance(un, mu)) / (ex.size + un.size - 2))
        return PairedDifferenceResult(
            nExposed = ex.size, nUnexposed = un.size, difference = roundTo(me - mu, 2),
            cohensD = if (pooled > 0) roundTo((me - mu) / pooled, 2) else null, status = "ok"
        )
    }
}
