package com.ayuvo.health.nutrients

import com.ayuvo.health.models.FoodEntry
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * The medications side of nutrient totals (docs/nutrients.md §6): what each dose unit of a
 * supplement contains, its taken doses, and the instants of every taken dose (logged days).
 * Recomputed from the medications database on every change; never stored.
 */
data class SupplementSnapshot(
    val rows: List<MedicationNutrientRow> = emptyList(),
    val doses: List<SupplementDose> = emptyList(),
    val takenDoseMs: List<Long> = emptyList()
) {
    /** `supplement_entries` of the whole history, sorted by time. */
    val entries: List<SupplementEntry> by lazy { Nutrients.supplementEntries(rows, doses) }

    val medicationIdsWithNutrients: Set<String> by lazy { rows.mapTo(HashSet()) { it.medicationId } }

    fun entriesFor(key: String): List<SupplementEntry> = entries.filter { it.nutrientKey == key }

    companion object {
        val EMPTY = SupplementSnapshot()
    }
}

/**
 * The one nutrient-totals path (docs/nutrients.md §6): food entries plus taken supplement doses,
 * per local day, through [Nutrients.dayTotals]. Every consumer (Nutrition Details, home cards, All
 * Nutrients, actions, Coach, Daily Review) reads it. Supplements never add calories and are never
 * written to Health Connect: the macro keys are food only.
 */
class NutrientTotals(
    private val food: List<FoodEntry>,
    private val supplements: SupplementSnapshot = SupplementSnapshot.EMPTY,
    private val zone: ZoneId = ZoneId.systemDefault()
) {
    private val cache = HashMap<LocalDate, Map<String, NutrientAmount>>()

    /** `{food, supplements, total}` of [key] on [day]; every part null when nothing was recorded. */
    fun total(key: String, day: LocalDate): NutrientAmount = day(day)[key] ?: NutrientAmount.NONE

    /** Every nutrient key named on [day] (food fields of that day's entries and supplement keys). */
    @Synchronized
    fun day(day: LocalDate): Map<String, NutrientAmount> = cache.getOrPut(day) {
        val dayText = day.toString()
        val foodRows = food.filter { it.timestamp.atZone(zone).toLocalDate() == day }.map { e ->
            FoodNutrients(e.timestamp.toEpochMilli(), ALL_KEYS.associateWith { k -> NutrientFields.foodValue(e, k) })
        }
        val start = day.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val supp = supplements.entries.filter { it.tMs in start until end }
        Nutrients.dayTotals(foodRows, supp, dayText, zone)
    }

    /** The supplement contributions of [day], per medication and key (for the medication detail card). */
    fun supplementEntries(day: LocalDate): List<SupplementEntry> {
        val start = day.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        return supplements.entries.filter { it.tMs in start until end }
    }

    companion object {
        /** Food fields read per entry: the macros, then every reference and sports key. */
        val ALL_KEYS: List<String> = listOf(NutrientFields.CALORIES, NutrientFields.PROTEIN, NutrientFields.CARBS, NutrientFields.FAT) +
            NutrientFields.REFERENCE_KEYS

        /** Chart entries of one nutrient: food values (null skipped) then its supplement entries. */
        fun seriesEntries(key: String, food: List<FoodEntry>, supplements: SupplementSnapshot): List<NutrientValueEntry> =
            food.mapNotNull { e -> NutrientFields.foodValue(e, key)?.let { NutrientValueEntry(e.timestamp.toEpochMilli(), it) } } +
                supplements.entriesFor(key).map { NutrientValueEntry(it.tMs, it.value) }

        /** Local days with any food entry or any taken dose (`logged_day_average`'s logged days). */
        fun loggedDays(food: List<FoodEntry>, supplements: SupplementSnapshot, zone: ZoneId): Set<String> {
            val out = HashSet<String>()
            food.forEach { out += it.timestamp.atZone(zone).toLocalDate().toString() }
            supplements.takenDoseMs.forEach { out += Instant.ofEpochMilli(it).atZone(zone).toLocalDate().toString() }
            return out
        }
    }
}
