package com.ayuvo.health.ui.health

import com.ayuvo.health.models.HealthDataType

/**
 * The `healthHomeTiles` preference (comma-separated registry slugs, cloud-backed, same key on
 * iOS). Pure so the parser/serialiser is unit-tested; unknown slugs are dropped, an empty or
 * invalid value falls back to the defaults.
 */
object HealthHomeTiles {
    const val MAX_TILES = 8

    val DEFAULT: List<HealthDataType> = listOf(
        HealthDataType.STEPS, HealthDataType.ACTIVE_ENERGY, HealthDataType.HEART_RATE, HealthDataType.SLEEP
    )

    fun parse(raw: String?): List<HealthDataType> {
        if (raw.isNullOrBlank()) return DEFAULT
        val parsed = raw.split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .mapNotNull(HealthDataType::byId)
            .distinct()
            .take(MAX_TILES)
        return parsed.ifEmpty { DEFAULT }
    }

    fun serialize(types: List<HealthDataType>): String =
        types.distinct().take(MAX_TILES).joinToString(",") { it.id }

    /** Types eligible for a Home tile: anything readable on Android plus anything already mirrored. */
    fun candidates(withData: Set<String>): List<HealthDataType> =
        HealthDataType.entries.filter { it.sdkAvailable || it.id in withData }
}
