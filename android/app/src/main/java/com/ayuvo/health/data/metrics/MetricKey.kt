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
 * any other string is a health registry id (kept as-is, unregistered imported ids included).
 */
sealed interface MetricKey {
    val storageId: String

    data class App(val id: AppMetricId) : MetricKey {
        override val storageId: String get() = id.key
    }

    data class Health(val typeId: String) : MetricKey {
        override val storageId: String get() = typeId
    }

    companion object {
        /** `app:` keys not in [AppMetricId] and blank strings → null. */
        fun parse(raw: String): MetricKey? {
            val k = raw.trim()
            if (k.isEmpty()) return null
            if (k.startsWith("app:")) return AppMetricId.bySlug(k.removePrefix("app:"))?.let(::App)
            return Health(k)
        }
    }
}
