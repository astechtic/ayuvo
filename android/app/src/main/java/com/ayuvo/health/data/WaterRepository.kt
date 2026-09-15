package com.ayuvo.health.data

import com.ayuvo.health.models.WaterEntry
import com.ayuvo.health.export.DiaryImporter
import com.ayuvo.health.export.DiaryImportPreview
import com.ayuvo.health.export.DiaryImportMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

class WaterRepository(private val prefs: PreferencesStore) {
    private val mutationMutex = Mutex()
    val entries: Flow<List<WaterEntry>> = prefs.waterEntries.map { list -> list.sortedBy { it.date } }

    suspend fun add(entry: WaterEntry) {
        if (entry.milliliters <= 0) return
        mutationMutex.withLock {
            prefs.updateWaterEntries { current -> current + entry }
        }
    }

    suspend fun importDiary(preview: DiaryImportPreview, mode: DiaryImportMode) {
        if (!preview.includesWater) return
        mutationMutex.withLock {
            prefs.updateWaterEntries { current -> DiaryImporter.applyingWater(preview, current, mode) }
        }
    }

    suspend fun delete(id: UUID) {
        mutationMutex.withLock {
            prefs.updateWaterEntries { current -> current.filter { it.id != id } }
        }
    }
}
