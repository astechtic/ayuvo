package com.ayuvo.health.records.coach

/**
 * One-shot request from a Records screen to open Coach with [recordIds] selected and [prompt]
 * prefilled (docs/health-records.md §27). [id] lets Coach consume each request exactly once.
 */
data class CoachRecordsRequest(
    val recordIds: List<String>,
    val prompt: String,
    val id: Long = System.nanoTime()
)
