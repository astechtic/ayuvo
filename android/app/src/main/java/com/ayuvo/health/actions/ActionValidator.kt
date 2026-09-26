package com.ayuvo.health.actions

/**
 * Request validation, ported line by line from `validate()` in scripts/actions_reference.py
 * (shared/actions/test-vectors/validation.json). Check order: unknown action, surface, unknown
 * params (sorted), each param in catalog order (missing / type / enum / length), ranges in catalog
 * order (unit params converted to the canonical unit first), requires_one_of.
 */
class ActionValidator(private val catalog: ActionCatalog) {

    fun validate(id: String, rawParams: Map<String, Any?>?, source: ActionSource, prefs: Map<String, String> = emptyMap()): ValidationResult {
        val spec = catalog.action(id) ?: return fail(ActionErrorCode.UNKNOWN_ACTION)
        if (source != ActionSource.APP && source.raw !in spec.surfaces) return fail(ActionErrorCode.NOT_ALLOWED)
        val names = spec.params.map { it.name }
        val raw = rawParams.orEmpty()
        for (key in raw.keys.sorted()) {
            if (key !in names) return fail(ActionErrorCode.UNKNOWN_PARAM, key)
        }
        val params = LinkedHashMap<String, Any?>()
        for (p in spec.params) {
            var value = raw[p.name]
            if (value is String) {
                value = value.trim()
                if (value.isEmpty()) value = null
            }
            if (value == null) {
                if (p.default != null) {
                    params[p.name] = p.default
                    continue
                }
                val pref = p.defaultPref?.let { prefs[it] }
                if (pref != null && pref in catalog.enums[p.enumName].orEmpty()) {
                    params[p.name] = pref
                    continue
                }
                if (p.required) return fail(ActionErrorCode.MISSING_PARAM, p.name)
                continue
            }
            when (val coerced = coerce(p, value)) {
                is Coerced.Value -> params[p.name] = coerced.value
                is Coerced.Error -> return fail(coerced.code, p.name)
            }
        }
        for (p in spec.params) {
            val value = params[p.name] as? Number ?: continue
            var lo = p.min
            var hi = p.max
            if (p.rangeByParam != null) {
                val range = p.rangeByRanges?.get(params[p.rangeByParam] as? String) ?: continue
                lo = range.first
                hi = range.second
            }
            if (lo == null && hi == null) continue
            var v = value.toDouble()
            if (p.unitParam != null && p.unitFamily != null) {
                val family = catalog.units.getValue(p.unitFamily)
                val unit = params[p.unitParam] as? String ?: family.canonical
                v = ActionMath.toCanonical(catalog, p.unitFamily, unit, v)
            }
            if ((lo != null && v < lo) || (hi != null && v > hi)) return fail(ActionErrorCode.OUT_OF_RANGE, p.name)
        }
        if (spec.requiresOneOf.isNotEmpty() && spec.requiresOneOf.none { group -> group.all { it in params } }) {
            return fail(ActionErrorCode.REQUIRES_ONE_OF)
        }
        val ai = spec.confirmation == Confirmation.WHEN_AI && !spec.aiUnless.all { it in params }
        val confirm = spec.kind == ActionKind.SET && !spec.opensApp && (
            spec.confirmation == Confirmation.ALWAYS || ai || source == ActionSource.DEEPLINK || source == ActionSource.COACH)
        return ValidationResult.Ok(ValidatedAction(spec, params, source, confirm, ai))
    }

    private sealed interface Coerced {
        data class Value(val value: Any) : Coerced
        data class Error(val code: ActionErrorCode) : Coerced
    }

    private fun coerce(p: ParamSpec, raw: Any): Coerced {
        val v: Any = when (p.type) {
            ParamType.NUMBER -> {
                val d = when (raw) {
                    is Boolean -> return Coerced.Error(ActionErrorCode.BAD_TYPE)
                    is Number -> raw.toDouble()
                    is String -> if (NUMBER_RE.matches(raw)) raw.toDouble() else return Coerced.Error(ActionErrorCode.BAD_TYPE)
                    else -> return Coerced.Error(ActionErrorCode.BAD_TYPE)
                }
                if (d.isNaN() || d.isInfinite()) return Coerced.Error(ActionErrorCode.BAD_TYPE)
                d
            }
            ParamType.INTEGER -> when (raw) {
                is Boolean -> return Coerced.Error(ActionErrorCode.BAD_TYPE)
                is Int -> raw.toLong()
                is Long -> raw
                is Double -> if (!raw.isNaN() && !raw.isInfinite() && raw % 1.0 == 0.0) raw.toLong() else return Coerced.Error(ActionErrorCode.BAD_TYPE)
                is Float -> if (raw % 1f == 0f) raw.toLong() else return Coerced.Error(ActionErrorCode.BAD_TYPE)
                is String -> if (INTEGER_RE.matches(raw)) raw.toLongOrNull() ?: return Coerced.Error(ActionErrorCode.BAD_TYPE) else return Coerced.Error(ActionErrorCode.BAD_TYPE)
                else -> return Coerced.Error(ActionErrorCode.BAD_TYPE)
            }
            ParamType.ENUM -> {
                if (raw !is String) return Coerced.Error(ActionErrorCode.BAD_TYPE)
                if (raw !in catalog.enums[p.enumName].orEmpty()) return Coerced.Error(ActionErrorCode.BAD_ENUM)
                raw
            }
            ParamType.METRIC -> {
                if (raw !is String) return Coerced.Error(ActionErrorCode.BAD_TYPE)
                if (!METRIC_RE.matches(raw)) return Coerced.Error(ActionErrorCode.BAD_VALUE)
                raw
            }
            ParamType.STRING, ParamType.ENTITY -> {
                if (raw !is String) return Coerced.Error(ActionErrorCode.BAD_TYPE)
                val limit = if (p.type == ParamType.STRING) p.maxLength ?: ENTITY_MAX_LENGTH else ENTITY_MAX_LENGTH
                if (raw.codePointCount(0, raw.length) > limit) return Coerced.Error(ActionErrorCode.TOO_LONG)
                raw
            }
        }
        val allowed = p.allowed
        if (allowed != null && (v as? Number)?.toDouble() !in allowed) return Coerced.Error(ActionErrorCode.BAD_ENUM)
        return Coerced.Value(v)
    }

    private fun fail(code: ActionErrorCode, param: String? = null) = ValidationResult.Failed(code, param)

    companion object {
        const val ENTITY_MAX_LENGTH = 200
        private val NUMBER_RE = Regex("^[+-]?([0-9]+(\\.[0-9]*)?|\\.[0-9]+)$")
        private val INTEGER_RE = Regex("^[+-]?[0-9]+$")
        private val METRIC_RE = Regex("^(app:)?[a-z][a-z0-9_]*$")
    }
}
