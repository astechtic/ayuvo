package com.ayuvo.health.insights

import com.ayuvo.health.insights.InsightsMath.clamp
import com.ayuvo.health.insights.InsightsMath.fill
import com.ayuvo.health.insights.InsightsMath.fmtDuration
import com.ayuvo.health.insights.InsightsMath.fmtSigned
import com.ayuvo.health.insights.InsightsMath.mean
import com.ayuvo.health.insights.InsightsMath.roundInt
import com.ayuvo.health.insights.InsightsMath.roundTo
import com.ayuvo.health.insights.InsightsMath.sleepMidpointMin
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import kotlin.math.abs

/**
 * Daily Recovery, 0–100 (docs/insights.md, "How Recovery is calculated"): each component scores
 * `clamp(50 + 20·z_dir)` against its personal baseline, the available ones are averaged with
 * renormalised weights and a high previous-day training load takes points off. Ported from
 * `recovery` in the reference; [day] is the wake day of last night's sleep.
 */
object RecoveryEngine {

    private class Item(
        val id: String,
        val weight: Double,
        var available: Boolean,
        val value: Double?,
        val baseline: Double?,
        val delta: Double?,
        val pct: Double?,
        val z: Double?,
        var subscore: Double?,
        var impact: Double?,
        val baselineN: Int,
        val baselineConfidence: String,
        val fallback: Boolean,
        var consistencyDeviationMin: Double?,
        var consistencySubscore: Double?
    )

    private fun subscore(zDir: Double, rc: InsightsConfig.Recovery): Double =
        clamp(rc.subscoreCenter + rc.subscoreSlope * zDir, 0.0, 100.0)

    /** (deviation minutes, sub-score) of last night's midpoint vs the mean midpoint of the prior nights. */
    private fun consistency(nights: Map<LocalDate, SleepInput>, day: LocalDate, zone: ZoneId, rc: InsightsConfig.Recovery): Pair<Double, Double>? {
        val prior = ArrayList<Double>()
        for (k in rc.consistencyWindowDays downTo 1) {
            val d = day.minusDays(k.toLong())
            nights[d]?.let { prior += sleepMidpointMin(it, d, zone).toDouble() }
        }
        if (prior.size < rc.consistencyMinNights) return null
        val dev = abs(sleepMidpointMin(nights.getValue(day), day, zone) - mean(prior))
        return dev to clamp(100.0 * (1.0 - dev / rc.consistencyZeroAtMin), 0.0, 100.0)
    }

    fun recovery(inputs: InsightsInputs, day: LocalDate, cfg: InsightsConfig): RecoveryResult {
        val rc = cfg.recovery
        val zone = inputs.timeZone
        val nights = BaselineEngine.validNights(inputs, cfg)
        val sleepSeries = nights.mapValues { it.value.asleepMin!! }
        val items = ArrayList<Item>()
        val raw = HashMap<String, Pair<Item, BaselineEngine.Raw>>()
        for (c in rc.components) {
            val m = cfg.metric(c.metric)
            val s = if (c.id == "sleep") sleepSeries else inputs.series(c.metric)
            val b = BaselineEngine.raw(s, day, m)
            val item = Item(
                id = c.id, weight = c.weight, available = false, value = b.recent, baseline = b.mean, delta = b.delta,
                pct = b.pct, z = b.z, subscore = null, impact = null, baselineN = b.n, baselineConfidence = b.confidence,
                fallback = c.metric in inputs.overnightFallback, consistencyDeviationMin = null, consistencySubscore = null
            )
            if (b.ok && b.recent != null) {
                val z = b.z!!
                val sub = when (c.mode) {
                    "higher" -> subscore(z, rc)
                    "lower" -> subscore(-z, rc)
                    "band" -> subscore(-maxOf(0.0, abs(z) - c.toleranceZ), rc)
                    "drop_only" -> subscore(-maxOf(0.0, -z - c.toleranceZ), rc)
                    else -> {
                        // Sleep: duration vs the personal baseline, blended with midpoint consistency when known.
                        var s0 = subscore(z, rc)
                        consistency(nights, day, zone, rc)?.let { (dev, cons) ->
                            item.consistencyDeviationMin = dev
                            item.consistencySubscore = cons
                            s0 = c.durationShare * s0 + c.consistencyShare * cons
                        }
                        s0
                    }
                }
                item.available = true
                item.subscore = sub
            }
            items += item
            raw[c.id] = item to b
        }
        val (sleepItem, sleepB) = raw.getValue("sleep")
        val heart = rc.heartComponents.map { raw.getValue(it) }
        val need = cfg.metric("sleep").minPoints
        val have = minOf(sleepB.n, heart.maxOf { it.second.n })
        var status = "ok"
        var collecting: Collecting? = null
        if (!sleepB.ok || heart.all { !it.second.ok }) {
            status = "collecting"
            collecting = Collecting(minOf(have, need), need)
        } else if (!sleepItem.available) {
            status = "no_sleep"
        } else if (heart.none { it.first.available }) {
            status = "no_heart_data"
        }
        if (status != "ok") {
            return result(day, status, null, null, collecting, items, emptyList(), emptyList(), null)
        }
        val avail = items.filter { it.available }
        var wsum = 0.0
        for (i in avail) wsum += i.weight
        var score = 0.0
        for (i in avail) {
            score += i.weight * i.subscore!!
            i.impact = (i.subscore!! - rc.subscoreCenter) * i.weight / wsum
        }
        score /= wsum
        val load = TrainingLoad.raw(TrainingLoad.dailyLoads(inputs.workouts, zone, cfg), day.minusDays(1), cfg)
        var mod = 0
        if (load.category == "high") {
            mod = if (load.ratio != null && load.ratio > rc.loadVeryHighRatio) rc.loadVeryHigh else rc.loadHigh
        }
        val final = clamp(roundInt(score + mod).toDouble(), 0.0, 100.0).toInt()
        val band = rc.bands.first { final >= it.min }
        val allHigh = avail.all { it.baselineConfidence == "high" }
        val confidence = if (avail.size >= 4 && allHigh) "high" else if (avail.size >= 3 || allHigh) "medium" else "low"
        val signals = avail.map { RecoverySignal(it.id, it.impact!!, fill(rc.contributors.getValue(it.id), contributorParams(it))) }
        val pos = signals.filter { it.impact > 0 }.sortedBy { -it.impact }
        var neg = signals.filter { it.impact < 0 }.sortedBy { it.impact }
        val loadLabel = cfg.trainingLoad.labels.getValue(load.category)
        if (mod < 0) {
            neg = (neg + RecoverySignal("training_load", mod.toDouble(), fill(rc.contributors.getValue("training_load"), mapOf("category" to loadLabel))))
                .sortedBy { it.impact }
        }
        val recoveryLoad = RecoveryLoad(
            day = load.day, load = roundTo(load.load, 1), mean28d = roundTo(load.mean28d, 1), ratio = roundTo(load.ratio, 2),
            category = load.category, label = loadLabel, modifier = mod
        )
        return result(
            day, "ok", final, band, null, items,
            pos.map { it.copy(impact = roundTo(it.impact, 1)) }, neg.map { it.copy(impact = roundTo(it.impact, 1)) },
            recoveryLoad, confidence
        )
    }

    private fun contributorParams(i: Item): Map<String, Any?> = mapOf(
        "pct" to fmtSigned(i.pct ?: 0.0, 0),
        "delta0" to fmtSigned(i.delta!!, 0),
        "delta1" to fmtSigned(i.delta, 1),
        "value1" to String.format(Locale.ROOT, "%.1f", roundTo(i.value!!, 1)),
        "duration" to fmtDuration(i.value)
    )

    private fun result(
        day: LocalDate,
        status: String,
        score: Int?,
        band: InsightsConfig.Band?,
        collecting: Collecting?,
        items: List<Item>,
        positives: List<RecoverySignal>,
        negatives: List<RecoverySignal>,
        load: RecoveryLoad?,
        confidence: String? = null
    ) = RecoveryResult(
        day = day, status = status, score = score, label = band?.id, labelText = band?.label,
        recommendation = band?.recommendation, confidence = confidence, collecting = collecting,
        components = items.map {
            RecoveryComponent(
                id = it.id, weight = it.weight, available = it.available, value = roundTo(it.value, 2),
                baseline = roundTo(it.baseline, 2), delta = roundTo(it.delta, 2), pct = roundTo(it.pct, 1), z = roundTo(it.z, 2),
                subscore = roundTo(it.subscore, 1), impact = roundTo(it.impact, 1), baselineN = it.baselineN,
                baselineConfidence = it.baselineConfidence, fallback = it.fallback,
                consistencyDeviationMin = roundTo(it.consistencyDeviationMin, 1), consistencySubscore = roundTo(it.consistencySubscore, 1)
            )
        },
        positives = positives, negatives = negatives, load = load
    )
}
