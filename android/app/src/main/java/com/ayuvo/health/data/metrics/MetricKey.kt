package com.ayuvo.health.data.metrics

/** App-owned metrics from `metric_catalog.json` (`app:<slug>`). */
enum class AppMetricId(val slug: String) {
    CALORIES("calories"),
    PROTEIN("protein"),
    CARBS("carbs"),
    FAT("fat"),
    FIBER("fiber"),
    WATER("water"),
    FASTING("fasting"),
    WEIGHT("weight"),
    BODY_FAT("body_fat"),
    WORKOUTS("workouts"),
    WORKOUT_MINUTES("workout_minutes"),
    WORKOUT_BURN("workout_burn");

    val key: String get() = "app:$slug"

    companion object {
        fun bySlug(slug: String): AppMetricId? = entries.firstOrNull { it.slug == slug }
    }
}

/**
 * A chart/favourite key (docs/ui-structure.md §4 key grammar): `app:<slug>` for app metrics,
 * `nutrient:<key>` for nutrient metrics (a nutrient_reference.json or sports key), any other
 * string is a health registry id (kept as-is, unregistered imported ids included).
 */
sealed interface MetricKey {
    val storageId: String

    data class App(val id: AppMetricId) : MetricKey {
        override val storageId: String get() = id.key
    }

    data class Nutrient(val key: String) : MetricKey {
        override val storageId: String get() = NUTRIENT_PREFIX + key
    }

    data class Health(val typeId: String) : MetricKey {
        override val storageId: String get() = typeId
    }

    companion object {
        const val NUTRIENT_PREFIX = "nutrient:"
        private val NUTRIENT_KEY = Regex("^[a-z][a-z0-9_]*$")

        /** `app:` keys not in [AppMetricId], malformed `nutrient:` keys and blank strings → null. */
        fun parse(raw: String): MetricKey? {
            val k = raw.trim()
            if (k.isEmpty()) return null
            if (k.startsWith("app:")) return AppMetricId.bySlug(k.removePrefix("app:"))?.let(::App)
            if (k.startsWith(NUTRIENT_PREFIX)) return k.removePrefix(NUTRIENT_PREFIX).takeIf { NUTRIENT_KEY.matches(it) }?.let(::Nutrient)
            return Health(k)
        }
    }
}
