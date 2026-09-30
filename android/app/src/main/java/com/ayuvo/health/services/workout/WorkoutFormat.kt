package com.ayuvo.health.services.workout

import java.util.Locale
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import com.ayuvo.health.R
import com.ayuvo.health.l10n.AppText

/** Text for workout numbers shared by the live notification and the workout screens (metric, per-km splits). */
object WorkoutFormat {
    fun duration(ms: Long): String {
        val total = (ms / 1000).coerceAtLeast(0)
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s) else String.format(Locale.US, "%d:%02d", m, s)
    }

    fun seconds(s: Double): String = duration((s * 1000).roundToLong())

    fun distanceKm(m: Double): String = String.format(Locale.getDefault(), "%.2f", m / 1000.0).let { AppText.orEnglish("$it km", R.string.core_workout_fmt_km, it) }

    /** "5:37 /km"; null speed or a standstill shows "--:--". */
    fun paceFromSpeed(speedMps: Double?): String {
        if (speedMps == null || !speedMps.isFinite() || speedMps < 0.3) return perKm("--:--")
        return pace(1000.0 / speedMps)
    }

    fun pace(secondsPerKm: Double?): String {
        if (secondsPerKm == null || !secondsPerKm.isFinite() || secondsPerKm <= 0 || secondsPerKm > 3600) return perKm("--:--")
        val s = secondsPerKm.roundToInt()
        return perKm(String.format(Locale.US, "%d:%02d", s / 60, s % 60))
    }

    fun speedKmh(speedMps: Double?): String =
        (if (speedMps == null || !speedMps.isFinite()) "--" else String.format(Locale.getDefault(), "%.1f", speedMps * 3.6))
            .let { AppText.orEnglish("$it km/h", R.string.core_workout_fmt_kmh, it) }

    fun metres(m: Double?): String = (if (m == null || !m.isFinite()) "--" else "${m.roundToInt()}")
        .let { AppText.orEnglish("$it m", R.string.core_workout_fmt_m, it) }

    fun bpm(v: Double?): String = (if (v == null || !v.isFinite()) "--" else "${v.roundToInt()}")
        .let { AppText.orEnglish("$it bpm", R.string.core_workout_fmt_bpm, it) }

    private fun perKm(value: String): String = AppText.orEnglish("$value /km", R.string.core_workout_fmt_pace, value)

    /** Cycling reads naturally as speed; walks, runs and hikes as pace. */
    fun usesSpeed(sport: String): Boolean = sport == "cycle"
}
