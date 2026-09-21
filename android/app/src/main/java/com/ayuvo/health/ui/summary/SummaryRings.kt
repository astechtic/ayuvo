package com.ayuvo.health.ui.summary

import com.ayuvo.health.data.metrics.MetricsReference
import com.ayuvo.health.data.metrics.RingState

/** What a Summary ring shows (docs/ui-structure.md §8.2). CONNECT = Move without a step source. */
enum class SummaryRingState { VALUE, NO_GOAL, NO_DATA, CONNECT }

enum class SummaryRingId { EAT, MOVE, DRINK }

/**
 * One ring: [progress] is the clamped fill (0–1, 0 unless [state] is VALUE); [percent] may exceed
 * 100. [value] and [goal] are in canonical units (kcal, steps, mL) and null when unknown, never 0.
 */
data class SummaryRing(
    val id: SummaryRingId,
    val state: SummaryRingState,
    val progress: Float,
    val percent: Int?,
    val value: Double?,
    val goal: Double?
)

/** Inputs for the three rings: today's totals and goals, as read from the stores and prefs. */
data class SummaryRingInputs(
    val caloriesToday: Double?,
    val calorieGoal: Double?,
    val stepsToday: Double?,
    val stepGoal: Double?,
    /** Health sync on and steps readable; otherwise Move shows the connect state. */
    val stepSource: Boolean,
    val waterTodayMl: Double?,
    val waterGoalMl: Double?,
    val waterTracking: Boolean
)

/** Pure ring assembly on top of the shared `ring_progress` rule (docs/ui-structure.md §7.8). */
object SummaryRings {

    fun build(inputs: SummaryRingInputs): List<SummaryRing> = buildList {
        add(ring(SummaryRingId.EAT, inputs.caloriesToday, inputs.calorieGoal))
        add(
            if (!inputs.stepSource) SummaryRing(SummaryRingId.MOVE, SummaryRingState.CONNECT, 0f, null, null, inputs.stepGoal?.takeIf { it > 0 })
            else ring(SummaryRingId.MOVE, inputs.stepsToday, inputs.stepGoal)
        )
        if (inputs.waterTracking) add(ring(SummaryRingId.DRINK, inputs.waterTodayMl, inputs.waterGoalMl))
    }

    fun ring(id: SummaryRingId, value: Double?, goal: Double?): SummaryRing {
        val p = MetricsReference.ringProgress(value, goal)
        val state = when (p.state) {
            RingState.VALUE -> SummaryRingState.VALUE
            RingState.NO_GOAL -> SummaryRingState.NO_GOAL
            RingState.NO_DATA -> SummaryRingState.NO_DATA
        }
        return SummaryRing(
            id = id,
            state = state,
            progress = (p.progress ?: 0.0).toFloat(),
            percent = p.percent,
            value = value,
            goal = goal?.takeIf { it > 0 }
        )
    }
}
