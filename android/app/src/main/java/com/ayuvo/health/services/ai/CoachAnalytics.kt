package com.ayuvo.health.services.ai

import com.ayuvo.health.insights.InsightsSnapshot
import java.util.Locale
import kotlin.math.roundToInt

/**
 * The already-computed analytics in the Coach prompt (docs/health-analytics.md §8): a Recovery Indicator v2 line and a
 * signals line, plus the rules that keep the model an interpreter. The numbers come from the analytics engine; the
 * model must explain them, never recompute them. English prompt text (like the rest of `ChatService`).
 */
object CoachAnalytics {
    val RULES: List<String> = listOf(
        "- Ayuvo computes HRV, sleep efficiency, the Recovery Indicator, training load, VO2 max and every other derived value on this device. Never calculate or estimate HRV, RMSSD, sleep efficiency, recovery, training load, VO2 max, blood pressure or SpO2 yourself: call get_health_evidence and explain the values it returns.",
        "- When an evidence item's confidence is below 0.75, say that the value is less certain.",
        "- Patterns are associations in the user's own data, never causes: say \"was associated with\", never \"caused\".",
        "- Never diagnose a condition from these signals. When several signals stay outside the user's range for days (persistent: true), suggest discussing persistent changes with a qualified healthcare professional.",
        "- Camera SpO2 is an experimental estimate and camera blood pressure is research-only; never treat either as a measurement."
    )

    private fun n(v: Any?): Double? = (v as? Number)?.toDouble()
    private fun f(v: Double?, d: Int = 0): String = v?.let { String.format(Locale.US, "%.${d}f", it) } ?: "—"

    /** Header + up to two lines; empty when there is no analytics result. */
    @Suppress("UNCHECKED_CAST")
    fun promptLines(snap: InsightsSnapshot?): List<String> {
        val a = snap?.analytics ?: return emptyList()
        val out = ArrayList<String>()
        out += ""
        out += "## Ayuvo analytics (already computed on this device; explain, never recompute)"
        snap.recovery.v2?.let { r ->
            if (r["score"] != null) {
                val drivers = (r["drivers"] as? List<Map<String, Any?>>).orEmpty().take(4).joinToString("; ") { d ->
                    val z = n(d["z"])?.let { ", ${if (it >= 0) "+" else ""}${f(it, 1)} SD" } ?: ""
                    "${d["text"]} (${f(n(d["value"]))} vs ${f(n(d["baseline"]))} ${d["unit"] ?: ""}$z)"
                }
                out += "- Recovery Indicator v2: ${r["score"]}/100 (${r["label"]}), confidence ${((n(r["confidence"]) ?: 0.0) * 100).roundToInt()}%. Drivers: $drivers"
            } else {
                out += "- Recovery Indicator v2: ${r["status"]} (${r["reason"] ?: "collecting baseline"})"
            }
        }
        a.anomaly?.let { an ->
            val state = an["state"] as? String ?: return@let
            val flagged = (an["signals"] as? List<Map<String, Any?>>).orEmpty().filter { it["flagged"] == true }
                .joinToString(", ") { "${it["id"]} ${f(n(it["z"]), 1)} SD" }
            out += "- Signals vs the user's own 28-day range: $state" + (if (flagged.isNotEmpty()) " (flagged: $flagged)" else "") +
                (if (an["persistent"] == true) ", persistent for several days" else "")
        }
        return if (out.size > 2) out else emptyList()
    }
}
