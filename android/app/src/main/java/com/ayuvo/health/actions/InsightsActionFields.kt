package com.ayuvo.health.actions

import com.ayuvo.health.insights.DailyReviewResult
import com.ayuvo.health.insights.HealthAgePace
import com.ayuvo.health.insights.HealthAgeResult
import com.ayuvo.health.insights.RecoveryResult
import java.time.LocalDate

/**
 * Output records of the three read-only Insights actions (catalog `output.fields`). They carry the
 * engine results as they are: derived scores, labels and texts, never raw samples. A missing value
 * stays null and the `status` says why (`collecting`, `no_birthday`, `no_data`, …).
 */
object InsightsActionFields {

    fun recovery(r: RecoveryResult): Map<String, Any?> = linkedMapOf(
        "status" to r.status,
        "score" to r.score?.toLong(),
        "label" to r.labelText,
        "recommendation" to r.recommendation,
        "confidence" to r.confidence,
        "positives" to r.positives.map { it.text },
        "negatives" to r.negatives.map { it.text },
        "training_load" to r.load?.label
    )

    fun healthAge(h: HealthAgeResult, pace: HealthAgePace): Map<String, Any?> = linkedMapOf(
        "status" to h.status,
        "actual_age" to h.actualAge,
        "health_age" to h.healthAge,
        "difference" to h.difference,
        "confidence" to h.confidence,
        "pace" to pace.pace,
        "direction" to pace.direction,
        "markers" to h.markers.filter { it.available }.map {
            linkedMapOf<String, Any?>("id" to it.id, "offset_years" to it.offsetYears, "contribution_years" to it.contributionYears)
        }
    )

    fun review(v: DailyReviewResult?, day: LocalDate): Map<String, Any?> = linkedMapOf(
        "status" to if (v?.dayScore != null) "ok" else "no_data",
        "day" to day.toString(),
        "day_score" to v?.dayScore?.toLong(),
        "went_well" to v?.wentWell.orEmpty().map { it.text },
        "needs_attention" to v?.needsAttention.orEmpty().map { it.text },
        "improve" to v?.improve.orEmpty().map { it.text },
        "reduce" to v?.reduce.orEmpty().map { it.text },
        "not_logged" to v?.notLogged.orEmpty().map { it.text }
    )
}
