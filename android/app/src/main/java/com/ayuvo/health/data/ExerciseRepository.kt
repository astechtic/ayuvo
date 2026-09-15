package com.ayuvo.health.data

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.InputStreamReader
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Loads the bundled exercise catalogue (shared/exercises/exercises.json) from assets and
 * exposes filtering / sorting, mirroring the iOS `ExerciseLibraryService` +
 * `ExerciseCatalogLoader`.
 */
class ExerciseRepository private constructor(
    val exercises: List<ExerciseItem>
) {

    fun includingActivities(activities: List<com.ayuvo.health.models.PlannedExercise>): ExerciseRepository =
        if (activities.isEmpty()) this else ExerciseRepository(
            (exercises + activities.map { it.asExerciseItem() }).distinctBy { it.id }
        )

    val availableBodyParts: List<String> by lazy { sortedUnique(exercises.map { it.bodyPart }) }
    val availablePrimaryMuscles: List<String> by lazy { sortedUnique(exercises.flatMap { it.primaryMuscles }) }
    val availableSecondaryMuscles: List<String> by lazy { sortedUnique(exercises.flatMap { it.secondaryMuscles }) }
    val availableEquipment: List<String> by lazy { sortedUnique(exercises.map { it.equipment }) }

    fun filtered(
        bodyParts: Set<String> = emptySet(),
        equipment: Set<String> = emptySet(),
        primaryMuscles: Set<String> = emptySet(),
        secondaryMuscles: Set<String> = emptySet(),
        sort: ExerciseSort = ExerciseSort.NAME,
        searchText: String = ""
    ): List<ExerciseItem> {
        val items = exercises.filter { item ->
            (bodyParts.isEmpty() || bodyParts.contains(item.bodyPart)) &&
                (equipment.isEmpty() || equipment.contains(item.equipment)) &&
                (primaryMuscles.isEmpty() || item.primaryMuscles.any { primaryMuscles.contains(it) }) &&
                (secondaryMuscles.isEmpty() || item.secondaryMuscles.any { secondaryMuscles.contains(it) }) &&
                ExerciseSearch.matches(item.searchableText, searchText, item.id)
        }
        return items.sortedWith(comparator(sort))
    }

    fun visualFor(item: ExerciseItem): ExerciseVisual = ExerciseVisual.from(item)

    private fun comparator(sort: ExerciseSort): Comparator<ExerciseItem> {
        val byName = Comparator<ExerciseItem> { a, b -> a.name.compareTo(b.name, ignoreCase = true) }
        fun field(selector: (ExerciseItem) -> String): Comparator<ExerciseItem> =
            Comparator<ExerciseItem> { a, b -> selector(a).compareTo(selector(b), ignoreCase = true) }.then(byName)
        return when (sort) {
            ExerciseSort.NAME -> byName
            ExerciseSort.BODY_PART -> field { it.bodyPart }
            ExerciseSort.TARGET -> field { it.primaryMusclesTitle }
            ExerciseSort.SECONDARY -> field { it.secondaryMusclesTitle }
            ExerciseSort.EQUIPMENT -> field { it.equipment }
        }
    }

    private fun sortedUnique(values: List<String>): List<String> =
        values.filter { it.isNotEmpty() && it != "Unspecified" }.distinct().sortedWith(String.CASE_INSENSITIVE_ORDER)

    companion object {
        private const val TAG = "ExerciseRepository"

        @Volatile
        private var instance: ExerciseRepository? = null
        private val warming = AtomicBoolean(false)
        private val warmExecutor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "exercise-repo-warm").apply { isDaemon = true }
        }

        /** Already-loaded singleton, or null if [get]/[warm] has not finished yet. */
        fun peek(): ExerciseRepository? = instance

        /**
         * Parses the catalogue on a background thread so the first Workouts tab open is
         * not blocked by I/O. Safe to call repeatedly.
         */
        fun warm(context: Context) {
            if (instance != null || !warming.compareAndSet(false, true)) return
            val app = context.applicationContext
            warmExecutor.execute {
                try {
                    get(app)
                } finally {
                    warming.set(false)
                }
            }
        }

        fun get(context: Context): ExerciseRepository =
            instance ?: synchronized(this) {
                instance ?: load(context.applicationContext).also { instance = it }
            }

        /** Builds a repository from already-mapped items (unit tests). */
        internal fun of(items: List<ExerciseItem>): ExerciseRepository = ExerciseRepository(items)

        private fun load(context: Context): ExerciseRepository {
            val startedAt = SystemClock.elapsedRealtime()
            val items = loadExercises(context)
            Log.d(TAG, "load complete exercises=${items.size} ms=${SystemClock.elapsedRealtime() - startedAt}")
            return ExerciseRepository(items)
        }

        private fun loadExercises(context: Context): List<ExerciseItem> = try {
            context.assets.open(CATALOG_ASSET_NAME).use { stream ->
                InputStreamReader(stream, Charsets.UTF_8).use { reader ->
                    val mapped = parseCatalog(reader)
                    Log.d(TAG, "mapped=${mapped.size}")
                    mapped
                }
            }
        } catch (t: Throwable) {
            // Never swallow this silently — an empty Workouts library shipped once
            // because this catch left no trace.
            Log.e(TAG, "failed to load $CATALOG_ASSET_NAME", t)
            emptyList()
        }

        const val CATALOG_ASSET_NAME = "exercises.json"

        /** Parses catalogue JSON into sorted items; shared by runtime loading and unit tests. */
        internal fun parseCatalog(reader: java.io.Reader): List<ExerciseItem> {
            val type = object : TypeToken<List<ExerciseRecord>>() {}.type
            val records: List<ExerciseRecord> = Gson().fromJson(reader, type) ?: emptyList()
            return records.mapNotNull { ExerciseItem.from(it) }
                .sortedWith { a, b -> a.name.compareTo(b.name, ignoreCase = true) }
        }

        /** Asset URI for a bundled asset filename (Coil-loadable). */
        fun imageAssetUri(filename: String): String = "file:///android_asset/$filename"
    }
}
