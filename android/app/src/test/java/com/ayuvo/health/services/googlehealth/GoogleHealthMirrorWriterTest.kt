package com.ayuvo.health.services.googlehealth

import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.metadata.Metadata
import com.ayuvo.health.data.health.GoogleHealthMirrorEntry
import com.ayuvo.health.data.health.HealthPageCommit
import com.ayuvo.health.data.health.HealthSampleRow
import com.ayuvo.health.data.health.InMemoryHealthDataStore
import com.ayuvo.health.services.health.HealthRecordMapper
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** Write-back: tagging, permission gating, retries, and the platform reader skipping our own records. */
class GoogleHealthMirrorWriterTest {
    private val stepsWrite = HealthPermission.getWritePermission(StepsRecord::class)

    private fun row(id: String, typeId: String = "steps", value: Double? = 100.0, start: Long = 1_000_000L, end: Long = 1_900_000L) = HealthSampleRow(
        id = "gh:$id", typeId = typeId, startMs = start, endMs = end, startOffsetS = 19800, endOffsetS = 19800,
        localDay = "2026-03-04", value = value, unit = "count", sourceId = "google_health:x", recordingMethod = 2,
        clientRecordId = "ayuvo_gh_$id", origin = HealthSampleRow.ORIGIN_GOOGLE_HEALTH, updatedMs = 1_234L
    )

    private suspend fun seed(store: InMemoryHealthDataStore, vararg rows: HealthSampleRow) {
        store.commit(HealthPageCommit(rows = rows.toList(), googleMirror = rows.filter { it.clientRecordId != null }.map { GoogleHealthMirrorEntry(it.id) }))
    }

    @Test
    fun pendingRowsAreWrittenWithTheirClientIdAndVersion() = runBlocking {
        val store = InMemoryHealthDataStore()
        seed(store, row("a"), row("b"))
        val inserted = mutableListOf<Record>()
        val writer = GoogleHealthMirrorWriter(store, insert = { records -> inserted += records; records.indices.map { "hc-$it" } }, grantedPermissions = { setOf(stepsWrite) })
        val result = writer.flush(writeBack = true)
        assertEquals(2, result.written)
        val steps = inserted.first() as StepsRecord
        assertEquals("ayuvo_gh_a", steps.metadata.clientRecordId)
        assertEquals(1_234L, steps.metadata.clientRecordVersion)
        assertEquals(Metadata.RECORDING_METHOD_AUTOMATICALLY_RECORDED, steps.metadata.recordingMethod)
        assertEquals(100L, steps.count)
        assertEquals(GoogleHealthMirrorEntry.STATUS_MIRRORED, store.googleMirror.getValue("gh:a").mirrorStatus)
        assertEquals("hc-1", store.googleMirror.getValue("gh:b").platformId)
    }

    @Test
    fun missingPermissionParksAndAGrantRequeues() = runBlocking {
        val store = InMemoryHealthDataStore()
        seed(store, row("a"))
        var granted = emptySet<String>()
        val writer = GoogleHealthMirrorWriter(store, insert = { r -> r.map { "id" } }, grantedPermissions = { granted })
        writer.flush(writeBack = true)
        assertEquals(GoogleHealthMirrorEntry.STATUS_DISABLED, store.googleMirror.getValue("gh:a").mirrorStatus)
        granted = setOf(stepsWrite)
        writer.flush(writeBack = true)
        assertEquals(GoogleHealthMirrorEntry.STATUS_MIRRORED, store.googleMirror.getValue("gh:a").mirrorStatus)
    }

    @Test
    fun failedInsertStaysPendingAndSchedulesARetry() = runBlocking {
        val store = InMemoryHealthDataStore()
        seed(store, row("a"))
        var retries = 0
        val writer = GoogleHealthMirrorWriter(
            store,
            insert = { throw IllegalStateException("binder") },
            grantedPermissions = { setOf(stepsWrite) },
            scheduleRetry = { retries++ }
        )
        val result = writer.flush(writeBack = true)
        assertTrue(result.deferred)
        assertEquals(1, retries)
        val entry = store.googleMirror.getValue("gh:a")
        assertEquals(GoogleHealthMirrorEntry.STATUS_PENDING, entry.mirrorStatus)
        assertEquals(1, entry.attempts)
    }

    @Test
    fun outOfRangeValuesAreMarkedErrorAndNotRetried() = runBlocking {
        val store = InMemoryHealthDataStore()
        seed(store, row("a", value = null))
        val writer = GoogleHealthMirrorWriter(store, insert = { r -> r.map { "id" } }, grantedPermissions = { setOf(stepsWrite) })
        writer.flush(writeBack = true)
        assertEquals(GoogleHealthMirrorEntry.STATUS_ERROR, store.googleMirror.getValue("gh:a").mirrorStatus)
    }

    @Test
    fun writeBackOffParksPendingRows() = runBlocking {
        val store = InMemoryHealthDataStore()
        seed(store, row("a"))
        val writer = GoogleHealthMirrorWriter(store, insert = { error("must not write") }, grantedPermissions = { setOf(stepsWrite) })
        writer.flush(writeBack = false)
        assertEquals(GoogleHealthMirrorEntry.STATUS_DISABLED, store.googleMirror.getValue("gh:a").mirrorStatus)
    }

    @Test
    fun sleepSessionCarriesItsStages() {
        val session = row("s1", typeId = "sleep", value = 7200.0, start = 0L, end = 7_200_000L).copy(categoryValue = 0)
        val stages = listOf(
            session.copy(id = "gh:s1:0", startMs = 0L, endMs = 3_600_000L, categoryValue = 3, clientRecordId = null),
            session.copy(id = "gh:s1:1", startMs = 3_600_000L, endMs = 7_200_000L, categoryValue = 5, clientRecordId = null)
        )
        val record = GoogleHealthRecordFactory.build(session, stages) as SleepSessionRecord
        assertEquals(listOf(SleepSessionRecord.STAGE_TYPE_LIGHT, SleepSessionRecord.STAGE_TYPE_REM), record.stages.map { it.stage })
        assertEquals(Instant.ofEpochMilli(7_200_000L), record.endTime)
    }

    @Test
    fun platformReaderSkipsGoogleMirrorRecords() {
        val mapper = HealthRecordMapper()
        val ours = StepsRecord(
            startTime = Instant.ofEpochMilli(0), startZoneOffset = null,
            endTime = Instant.ofEpochMilli(60_000), endZoneOffset = null, count = 10,
            metadata = Metadata.autoRecorded(androidx.health.connect.client.records.metadata.Device(type = 0), "ayuvo_gh_a", 1)
        )
        assertNull(mapper.map(ours, 1L))
        assertTrue(HealthRecordMapper.isGoogleHealthMirror("ayuvo_gh_x"))
        assertTrue(!HealthRecordMapper.isGoogleHealthMirror("ayuvo_123"))
    }
}
