package com.ayuvo.health.data.health

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

/** docs/health-data.md §2.2: origin-2 rows, value layout, ranges, delete = tombstone + rollup rebuild + platform delete. */
class ManualVitalsRepositoryTest {
    private val zone = ZoneId.of("Europe/Berlin")
    private val now = Instant.parse("2026-09-14T12:00:00Z").toEpochMilli()
    private val uuid = UUID.fromString("11111111-2222-3333-4444-555555555555")
    private val store = InMemoryHealthDataStore()
    private val platform = FakePlatform()
    private val repo = ManualVitalsRepository(
        store = { store }, packageName = "com.ayuvo.health", platform = platform,
        zone = { zone }, now = { now }, newId = { uuid }
    )

    private class FakePlatform : ManualVitalsPlatform {
        val upserts = mutableListOf<Pair<ManualVitalType, HealthSampleRow>>()
        val deletes = mutableListOf<Pair<ManualVitalType, UUID>>()
        override suspend fun upsert(type: ManualVitalType, row: HealthSampleRow): Boolean { upserts += type to row; return true }
        override suspend fun delete(type: ManualVitalType, uuid: UUID): Boolean { deletes += type to uuid; return true }
    }

    @Test
    fun glucoseInMgDlIsStoredAsOrigin2MmolRow() = runBlocking {
        val at = now - 3_600_000L
        val row = repo.logGlucose(99.0, mgdl = true, atMs = at, specimen = ManualVitals.SPECIMEN_PLASMA, relationToMeal = ManualVitals.RELATION_FASTING)
        val stored = store.samples.getValue("local:$uuid")
        assertEquals(row, stored)
        assertEquals(HealthSampleRow.ORIGIN_LOCAL_APP, stored.origin)
        assertEquals("blood_glucose", stored.typeId)
        assertEquals("com.ayuvo.health", stored.sourceId)
        assertEquals(3, stored.recordingMethod)
        assertEquals(at, stored.startMs)
        assertEquals(at, stored.endMs)
        assertEquals(7200, stored.startOffsetS)
        assertEquals(now, stored.updatedMs)
        assertEquals("2026-09-14", stored.localDay)
        assertEquals(99.0 / 18.0182, stored.value!!, 1e-9)
        assertEquals("mmol/L", stored.unit)
        assertEquals(3, stored.categoryValue)
        val extra = HealthRollupMath.parseExtra(stored.extraJson)!!
        assertEquals(2, extra.getValue("relation_to_meal").jsonPrimitive.int)
        assertEquals(0, extra.getValue("meal_type").jsonPrimitive.int)
        assertNull(stored.clientRecordId)
        // One commit for the row; the day's rollup rebuilt; the platform copy requested.
        assertEquals(1, store.commits.size)
        val rollup = store.dailyRollups("blood_glucose", "2026-09-14", "2026-09-14").single()
        assertEquals(99.0 / 18.0182, rollup.avg!!, 1e-9)
        assertEquals(ManualVitalType.BLOOD_GLUCOSE, platform.upserts.single().first)
    }

    @Test
    fun glucoseDefaultsToCapillaryBloodAndGeneral() = runBlocking {
        val row = repo.logGlucose(5.4, mgdl = false, atMs = now)
        assertEquals(5.4, row.value!!, 0.0)
        assertEquals(2, row.categoryValue)
        assertEquals(1, HealthRollupMath.parseExtra(row.extraJson)!!.getValue("relation_to_meal").jsonPrimitive.int)
    }

    @Test
    fun temperatureInFahrenheitIsStoredInCelsius() = runBlocking {
        val row = repo.logTemperature(98.6, fahrenheit = true, atMs = now, location = 4)
        assertEquals(37.0, row.value!!, 1e-9)
        assertEquals("degC", row.unit)
        assertEquals(0, row.categoryValue)
        assertEquals(4, HealthRollupMath.parseExtra(row.extraJson)!!.getValue("measurement_location").jsonPrimitive.int)
        assertEquals(37.0, ManualVitals.fahrenheitToCelsius(98.6), 1e-9)
        assertEquals(34.0, ManualVitals.fahrenheitToCelsius(93.2), 1e-9)
        assertEquals(42.0, ManualVitals.fahrenheitToCelsius(107.6), 1e-9)
    }

    @Test
    fun rangesAreCheckedInTheEnteredUnit() {
        assertTrue(ManualVitals.glucoseInRange(18.0, mgdl = true))
        assertTrue(ManualVitals.glucoseInRange(600.0, mgdl = true))
        assertFalse(ManualVitals.glucoseInRange(17.9, mgdl = true))
        assertFalse(ManualVitals.glucoseInRange(601.0, mgdl = true))
        assertTrue(ManualVitals.glucoseInRange(1.0, mgdl = false))
        assertTrue(ManualVitals.glucoseInRange(33.3, mgdl = false))
        assertFalse(ManualVitals.glucoseInRange(0.9, mgdl = false))
        assertFalse(ManualVitals.glucoseInRange(33.4, mgdl = false))
        assertTrue(ManualVitals.temperatureInRange(34.0, fahrenheit = false))
        assertTrue(ManualVitals.temperatureInRange(42.0, fahrenheit = false))
        assertFalse(ManualVitals.temperatureInRange(33.9, fahrenheit = false))
        assertFalse(ManualVitals.temperatureInRange(42.1, fahrenheit = false))
        assertTrue(ManualVitals.temperatureInRange(93.2, fahrenheit = true))
        assertTrue(ManualVitals.temperatureInRange(107.6, fahrenheit = true))
        assertFalse(ManualVitals.temperatureInRange(93.1, fahrenheit = true))
        assertFalse(ManualVitals.temperatureInRange(Double.NaN, fahrenheit = true))
    }

    @Test
    fun outOfRangeAndFutureEntriesAreRejected() = runBlocking {
        assertTrue(runCatching { repo.logGlucose(700.0, mgdl = true, atMs = now) }.isFailure)
        assertTrue(runCatching { repo.logTemperature(45.0, fahrenheit = false, atMs = now) }.isFailure)
        assertTrue(runCatching { repo.logTemperature(37.0, fahrenheit = false, atMs = now + 3_600_000L) }.isFailure)
        assertTrue(store.samples.isEmpty())
        assertTrue(platform.upserts.isEmpty())
    }

    @Test
    fun deleteTombstonesRebuildsDayAndDeletesPlatformCopy() = runBlocking {
        val row = repo.logTemperature(37.2, fahrenheit = false, atMs = now)
        assertNotNull(store.dailyRollups("body_temperature", row.localDay, row.localDay).singleOrNull())
        assertTrue(repo.delete(row.id))
        val stored = store.samples.getValue(row.id)
        assertTrue(stored.deleted)
        assertTrue(stored.updatedMs >= now)
        assertTrue(store.dailyRollups("body_temperature", row.localDay, row.localDay).isEmpty())
        assertEquals(ManualVitalType.BODY_TEMPERATURE to uuid, platform.deletes.single())
        assertNull(repo.liveRow(uuid))
        // A second delete is a no-op.
        assertFalse(repo.delete(row.id))
    }

    @Test
    fun rowsFromOtherOriginsAreNotDeletable() = runBlocking {
        val platformRow = HealthSampleRow(
            id = "hc-1", typeId = "blood_glucose", startMs = now, endMs = now, localDay = "2026-09-14",
            value = 5.0, unit = "mmol/L", sourceId = "com.other", updatedMs = now
        )
        store.commit(HealthPageCommit(rows = listOf(platformRow)))
        assertFalse(ManualVitals.isDeletable(platformRow))
        assertFalse(repo.delete("hc-1"))
        assertFalse(store.samples.getValue("hc-1").deleted)
        val weight = platformRow.copy(id = "local:$uuid", typeId = "weight", origin = HealthSampleRow.ORIGIN_LOCAL_APP)
        assertFalse(ManualVitals.isDeletable(weight))
    }

    @Test
    fun idsAndTags() {
        assertEquals("local:$uuid", ManualVitals.rowId(uuid))
        assertEquals("ayuvo_m_$uuid", ManualVitals.clientRecordId(uuid))
        assertEquals(uuid, ManualVitals.uuidOf("local:$uuid"))
        assertNull(ManualVitals.uuidOf("local:height"))
        assertEquals(ManualVitalType.BLOOD_GLUCOSE, ManualVitalType.byTypeId("blood_glucose"))
        assertEquals(ManualVitalType.BODY_TEMPERATURE, ManualVitalType.byRetryKind("bodyTemperature"))
    }
}
