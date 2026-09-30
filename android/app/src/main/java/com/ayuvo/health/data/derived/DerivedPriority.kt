package com.ayuvo.health.data.derived

import com.ayuvo.health.medications.logic.MedicationJson
import kotlinx.serialization.json.JsonObject

/** A platform reading for the metric and day that Ayuvo did not write. */
data class NativeReading(val value: Double?, val source: String?)

data class PriorityInput(val enabled: Boolean, val native: NativeReading?, val derived: Double?)

data class PriorityResult(val value: Double?, val sourceKind: String?, val source: String?) {
    fun toJson(): JsonObject = MedicationJson.obj("value" to value, "source_kind" to sourceKind, "source" to source)
}

/** Native platform data wins; a derived value is shown only when enabled and nothing native exists. Ported from `priority`. */
object DerivedPriority {
    fun priority(inp: PriorityInput, @Suppress("UNUSED_PARAMETER") cfg: DerivedConfig? = null): PriorityResult {
        val n = inp.native
        if (n?.value != null) return PriorityResult(n.value, "native", n.source)
        if (inp.enabled && inp.derived != null) return PriorityResult(inp.derived, "derived", null)
        return PriorityResult(null, null, null)
    }
}
