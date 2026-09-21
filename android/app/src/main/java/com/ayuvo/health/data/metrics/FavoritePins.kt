package com.ayuvo.health.data.metrics

import com.ayuvo.health.data.PreferencesStore
import com.ayuvo.health.models.HealthDataType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * The only reader/writer of `summaryFavourites` (docs/ui-structure.md §7.9). The first read
 * after the update runs `favourite_pins_migrate` over the legacy `healthHomeTiles` value and
 * persists the result; `healthHomeTiles` is left untouched for older builds.
 */
class FavoritePins(private val prefs: PreferencesStore, private val catalog: () -> MetricCatalogData) {

    /** Current favourites (migrated on the fly until [ensureMigrated] has written them). */
    val keys: Flow<List<MetricKey>> = combine(prefs.summaryFavourites, prefs.healthHomeTiles) { new, legacy ->
        resolve(catalog(), new, legacy).favourites.mapNotNull(MetricKey::parse)
    }

    fun isPinned(key: MetricKey): Flow<Boolean> = keys.map { list -> list.any { it.storageId == key.storageId } }

    suspend fun ensureMigrated() {
        if (prefs.summaryFavourites.first() != null) return
        val result = resolve(catalog(), null, prefs.healthHomeTiles.first())
        prefs.setSummaryFavourites(serialize(result.favourites))
    }

    suspend fun current(): List<MetricKey> = keys.first()

    suspend fun set(keys: List<MetricKey>) {
        prefs.setSummaryFavourites(serialize(keys.map { it.storageId }.distinct().take(catalog().favouritesMax)))
    }

    /** Replaces the health pins with [typeIds] (in that order) and keeps every app pin. */
    suspend fun setHealth(typeIds: List<String>) {
        set(withHealth(current(), typeIds, catalog().favouritesMax))
    }

    /** Health registry types currently pinned, in favourites order. */
    val healthTypeIds: Flow<List<String>> = keys.map { list -> list.filterIsInstance<MetricKey.Health>().map { it.typeId } }

    suspend fun toggle(key: MetricKey, pinned: Boolean) {
        set(toggled(current(), key, pinned, catalog().favouritesMax))
    }

    companion object {
        /** Registry ids that can be pinned (reserved types never produce data). */
        val knownHealthIds: Set<String> by lazy { HealthDataType.entries.filterNot { it.reserved }.map { it.id }.toSet() }

        fun resolve(catalog: MetricCatalogData, newRaw: String?, legacyRaw: String?): PinsResult =
            MetricsReference.favouritePinsMigrate(catalog, newRaw, legacyRaw, knownHealthIds, catalog.favouritesMax)

        fun serialize(ids: List<String>): String = ids.joinToString(",")

        /** App pins in their order, then [typeIds]; capped. */
        fun withHealth(current: List<MetricKey>, typeIds: List<String>, max: Int): List<MetricKey> =
            (current.filterIsInstance<MetricKey.App>() + typeIds.distinct().map { MetricKey.Health(it) }).take(max)

        /** Adds (at the end) or removes one key; other pins, app or health, are kept. A full list is left unchanged. */
        fun toggled(current: List<MetricKey>, key: MetricKey, pinned: Boolean, max: Int): List<MetricKey> {
            val already = current.any { it.storageId == key.storageId }
            return when {
                !pinned -> current.filterNot { it.storageId == key.storageId }
                already || current.size >= max -> current
                else -> current + key
            }
        }
    }
}
