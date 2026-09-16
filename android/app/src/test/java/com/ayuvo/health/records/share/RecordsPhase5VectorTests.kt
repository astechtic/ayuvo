package com.ayuvo.health.records.share

import com.ayuvo.health.records.processing.RecordsVectors
import org.junit.Test

/**
 * Phase 5 shared vectors (docs/health-records.md §34, §35): the same JSON both platforms run.
 * A failure prints the case name, the JSON path and both sides.
 */
class RecordsPhase5VectorTests {

    @Test
    fun shareSummaryVectors() = RecordsVectors.assertAll("share_summary.json")

    @Test
    fun redactionVectors() = RecordsVectors.assertAll("redaction.json")

    @Test
    fun archiveVectors() = RecordsVectors.assertAll("archive.json")
}
