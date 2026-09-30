package com.ayuvo.health.nutrients

import com.ayuvo.health.medications.data.MedicationsStore
import com.ayuvo.health.medications.model.MedicationFilter
import com.ayuvo.health.medications.model.MedicationStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.shareIn

/**
 * Supplement nutrients and taken doses from the medications database, re-read on every store
 * write (the store revision). Never creates `ayuvo_medications.db` just to look: without one the
 * snapshot is empty until the Meds segment opens it ([opened]).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SupplementIntakeSource(
    private val databaseExists: () -> Boolean,
    private val store: () -> MedicationsStore,
    private val opened: StateFlow<Boolean>,
    scope: CoroutineScope
) {
    val snapshots: Flow<SupplementSnapshot> = opened
        .flatMapLatest { isOpen ->
            if (isOpen || databaseExists()) store().revision.mapLatest { load() } else flowOf(SupplementSnapshot.EMPTY)
        }
        .shareIn(scope, SharingStarted.WhileSubscribed(5_000, replayExpirationMillis = 0), replay = 1)

    /** The current snapshot (empty without a medications database). */
    suspend fun current(): SupplementSnapshot = snapshots.first()

    /** Days one dose covers per supplement, from its latest schedule generation (docs/intake-metrics.md §3). */
    private suspend fun intervalDays(s: MedicationsStore, medicationIds: Set<String>): Map<String, Int> {
        val out = HashMap<String, Int>()
        for (id in medicationIds) {
            val latest = s.schedules(id, openOnly = false).maxByOrNull { it.activeFromMs }
            val n = SupplementAveraging.intervalDays(latest)
            if (n > 1) out[id] = n
        }
        return out
    }

    private suspend fun load(): SupplementSnapshot {
        if (!databaseExists() && !opened.value) return SupplementSnapshot.EMPTY
        val s = store()
        return try {
            val rows = s.allNutrients()
            SupplementSnapshot(
                rows, s.supplementDoses(), s.takenDoseTimes(),
                s.list(MedicationFilter(status = MedicationStatus.ACTIVE)).mapTo(HashSet()) { it.id },
                intervalDays = intervalDays(s, rows.mapTo(LinkedHashSet()) { it.medicationId })
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            SupplementSnapshot.EMPTY
        }
    }
}
