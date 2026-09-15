package com.ayuvo.health.records.coach

import com.ayuvo.health.records.analytes.AnalyteCatalog
import com.ayuvo.health.records.data.RecordsStore
import com.ayuvo.health.records.model.HealthRecord

/** Store-backed lookups for the §27 entry points and Coach suggestion chips. */
object RecordsCoachSelection {

    /** §27 "previous report" of [recordId], or null. */
    suspend fun previousReport(store: RecordsStore, catalog: () -> AnalyteCatalog, recordId: String): HealthRecord? {
        val id = RecordsCoach.compareCandidate(StoreCoachData(store, catalog), recordId).previousId ?: return null
        return store.record(id)
    }

    data class Chips(val latestLabs: List<String>, val comparePair: List<String>?)

    /** Coach suggestion chips (§27): null when no non-archived lab report exists. */
    suspend fun chips(store: RecordsStore, catalog: () -> AnalyteCatalog): Chips? {
        val selection = RecordsCoach.latestLabSelection(StoreCoachData(store, catalog))
        return if (!selection.showChips) null else Chips(selection.latest, selection.compare)
    }
}
