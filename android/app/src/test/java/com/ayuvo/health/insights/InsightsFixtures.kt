package com.ayuvo.health.insights

import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/** Deterministic synthetic histories for engine and action tests (no clock, fixed zone). */
object InsightsFixtures {
    val zone: ZoneId = ZoneId.of("Europe/London")
    val today: LocalDate = LocalDate.of(2026, 9, 20)

    /** A gentle, repeatable wobble so SDs are non-zero. */
    private fun wobble(i: Int, amp: Double): Double = amp * (((i * 37) % 11) - 5) / 5.0

    fun series(days: Int, base: Double, amp: Double, end: LocalDate = today, skip: (Int) -> Boolean = { false }): Map<LocalDate, Double> {
        val out = LinkedHashMap<LocalDate, Double>()
        for (k in days - 1 downTo 0) {
            if (skip(k)) continue
            out[end.minusDays(k.toLong())] = base + wobble(k, amp)
        }
        return out
    }

    /** Nights 23:00 → 07:00-ish local, keyed by wake day. */
    fun nights(days: Int, asleepMin: Double = 440.0, end: LocalDate = today, lastNightMin: Double? = null): Map<LocalDate, SleepInput> {
        val out = LinkedHashMap<LocalDate, SleepInput>()
        for (k in days - 1 downTo 0) {
            val wake = end.minusDays(k.toLong())
            val start = wake.minusDays(1).atTime(LocalTime.of(23, (k * 7) % 30)).atZone(zone).toInstant().toEpochMilli()
            val minutes = if (k == 0 && lastNightMin != null) lastNightMin else asleepMin + wobble(k, 20.0)
            val endMs = start + (minutes * 60_000).toLong() + 20 * 60_000L
            out[wake] = SleepInput(minutes, start, endMs)
        }
        return out
    }

    fun inputs(
        days: Int = 70,
        withHrv: Boolean = true,
        lastNightMin: Double? = null,
        workouts: List<WorkoutInput> = emptyList(),
        tracking: TrackingFlags = TrackingFlags(),
        nutrition: Map<LocalDate, Map<String, Double>> = emptyMap(),
        targets: Map<String, Double> = mapOf("calories" to 2200.0, "protein_g" to 140.0, "steps" to 8000.0, "water_ml" to 2500.0)
    ): InsightsInputs {
        val series = LinkedHashMap<String, Map<LocalDate, Double>>()
        if (withHrv) series["hrv"] = series(days, 45.0, 4.0)
        series["resting_heart_rate"] = series(days, 56.0, 2.0)
        series["respiratory_rate"] = series(days, 14.5, 0.4)
        series["blood_oxygen"] = series(days, 96.5, 0.4)
        series["steps"] = series(days, 8500.0, 1500.0)
        series["vo2_max"] = series(days, 44.0, 0.5, skip = { it % 7 != 0 })
        return InsightsInputs(
            timeZone = zone,
            hrvKind = "rmssd",
            series = series,
            sleep = nights(days, lastNightMin = lastNightMin),
            workouts = workouts,
            tracking = tracking,
            targets = targets,
            nutrition = nutrition
        )
    }

    fun bundle(inputs: InsightsInputs, profile: InsightsProfile = InsightsProfile(LocalDate.of(1986, 3, 1), "male", 180.0), healthEnabled: Boolean = true) =
        InsightsBundle(today, inputs, profile, emptyMap(), healthEnabled)
}
