package com.ayuvo.health.data.intake

import com.ayuvo.health.data.intake.IntakeMath.roundTo
import com.ayuvo.health.medications.logic.MedicationJson
import kotlinx.serialization.json.JsonObject

data class StrengthSet(val reps: Double?, val weightKg: Double?)

data class StrengthExercise(val name: String, val primaryMuscles: List<String>, val sets: List<StrengthSet>)

data class StrengthSession(val day: String, val exercises: List<StrengthExercise>)

data class StrengthWeekInput(val sessions: List<StrengthSession>, val plannedSessions: Int?)

data class StrengthWeekResult(
    val sessions: Int,
    val planned: Int?,
    val setsByMuscle: Map<String, Int>,
    val volumeByMuscle: Map<String, Double>,
    val belowRange: List<String>,
    /** "push" / "pull" / "legs" → share of hard sets (null without any). */
    val patternPct: Map<String, Double?>,
    val e1rm: Map<String, Double>
) {
    fun toJson(): JsonObject = MedicationJson.obj(
        "sessions" to sessions, "planned" to planned, "sets_by_muscle" to setsByMuscle, "volume_by_muscle" to volumeByMuscle,
        "below_range" to belowRange, "pattern_pct" to patternPct, "e1rm" to e1rm
    )
}

/** Port of `strength_week` (Epley 1RM, hard sets per muscle, push/pull/legs balance). */
object StrengthWeek {
    private val PATTERNS = listOf("push", "pull", "legs")

    fun epley(weight: Double, reps: Double): Double = weight * (1.0 + reps / 30.0)

    fun strengthWeek(inp: StrengthWeekInput, cfg: IntakeConfig): StrengthWeekResult {
        val th = cfg.thresholds
        val groups = cfg.muscleGroups
        val setsBy = HashMap<String, Int>()
        val volBy = HashMap<String, Double>()
        val pattern = linkedMapOf("push" to 0.0, "pull" to 0.0, "legs" to 0.0)
        val best = HashMap<String, Double>()
        for (s in inp.sessions) {
            for (e in s.exercises) {
                for (st in e.sets) {
                    val reps = st.reps ?: 0.0
                    val w = st.weightKg ?: 0.0
                    if (reps < 1) continue
                    for (m in e.primaryMuscles) {
                        setsBy[m] = (setsBy[m] ?: 0) + 1
                        volBy[m] = (volBy[m] ?: 0.0) + reps * w
                        for (g in PATTERNS) if (m in groups[g].orEmpty()) pattern[g] = pattern.getValue(g) + 1
                    }
                    if (reps >= 1 && reps <= th.e1rmMaxReps && w > 0) {
                        val v = epley(w, reps)
                        val prev = best[e.name]
                        if (prev == null || v > prev) best[e.name] = v
                    }
                }
            }
        }
        val totalPattern = pattern.getValue("push") + pattern.getValue("pull") + pattern.getValue("legs")
        val below = IntakeMath.sortedKeys(setsBy.filter { it.value < th.weeklySetsMin }.keys)
        val sets = LinkedHashMap<String, Int>()
        for (m in IntakeMath.sortedKeys(setsBy.keys)) sets[m] = setsBy.getValue(m)
        val vol = LinkedHashMap<String, Double>()
        for (m in IntakeMath.sortedKeys(volBy.keys)) vol[m] = roundTo(volBy.getValue(m), 0)
        val pct = LinkedHashMap<String, Double?>()
        for (g in PATTERNS) pct[g] = if (totalPattern != 0.0) roundTo(pattern.getValue(g) * 100.0 / totalPattern, 1) else null
        val e1rm = LinkedHashMap<String, Double>()
        for (n in IntakeMath.sortedKeys(best.keys)) e1rm[n] = roundTo(best.getValue(n), 1)
        return StrengthWeekResult(inp.sessions.size, inp.plannedSessions, sets, vol, below, pct, e1rm)
    }
}
