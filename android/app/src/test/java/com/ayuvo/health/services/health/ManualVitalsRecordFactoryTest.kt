package com.ayuvo.health.services.health

import androidx.health.connect.client.records.BloodGlucoseRecord
import androidx.health.connect.client.records.BodyTemperatureRecord
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.units.BloodGlucose
import androidx.health.connect.client.units.Temperature
import com.ayuvo.health.data.health.HealthSampleRow
import com.ayuvo.health.data.health.ManualVitalType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset

/** docs/health-data.md §2.2: manualEntry metadata tagged `ayuvo_m_<uuid>`, HC codes carried over, readers skip it. */
class ManualVitalsRecordFactoryTest {
    private val uuid = "11111111-2222-3333-4444-555555555555"
    private val at = Instant.parse("2026-09-14T10:00:00Z")

    private fun row(type: String, value: Double, category: Int, extra: String) = HealthSampleRow(
        id = "local:$uuid", typeId = type, startMs = at.toEpochMilli(), endMs = at.toEpochMilli(),
        startOffsetS = 7200, endOffsetS = 7200, localDay = "2026-09-14", value = value,
        unit = "x", categoryValue = category, extraJson = extra, sourceId = "com.ayuvo.health",
        recordingMethod = 3, origin = HealthSampleRow.ORIGIN_LOCAL_APP, updatedMs = 1_234L
    )

    @Test
    fun glucoseRecordCarriesSpecimenRelationAndManualTag() {
        val record = ManualVitalsRecordFactory.build(
            row("blood_glucose", 5.5, BloodGlucoseRecord.SPECIMEN_SOURCE_PLASMA, """{"relation_to_meal":3,"meal_type":2}""")
        ) as BloodGlucoseRecord
        assertEquals(5.5, record.level.inMillimolesPerLiter, 1e-9)
        assertEquals(BloodGlucoseRecord.SPECIMEN_SOURCE_PLASMA, record.specimenSource)
        assertEquals(BloodGlucoseRecord.RELATION_TO_MEAL_BEFORE_MEAL, record.relationToMeal)
        assertEquals(2, record.mealType)
        assertEquals(at, record.time)
        assertEquals(ZoneOffset.ofHours(2), record.zoneOffset)
        assertEquals("ayuvo_m_$uuid", record.metadata.clientRecordId)
        assertEquals(1_234L, record.metadata.clientRecordVersion)
        assertEquals(Metadata.RECORDING_METHOD_MANUAL_ENTRY, record.metadata.recordingMethod)
    }

    @Test
    fun temperatureRecordCarriesLocation() {
        val record = ManualVitalsRecordFactory.build(row("body_temperature", 37.1, 0, """{"measurement_location":8}""")) as BodyTemperatureRecord
        assertEquals(37.1, record.temperature.inCelsius, 1e-9)
        assertEquals(8, record.measurementLocation)
        assertEquals("ayuvo_m_$uuid", record.metadata.clientRecordId)
        assertEquals(Metadata.RECORDING_METHOD_MANUAL_ENTRY, record.metadata.recordingMethod)
    }

    @Test
    fun recordClassesMatchTypes() {
        assertEquals(BloodGlucoseRecord::class, ManualVitalsRecordFactory.recordClass(ManualVitalType.BLOOD_GLUCOSE))
        assertEquals(BodyTemperatureRecord::class, ManualVitalsRecordFactory.recordClass(ManualVitalType.BODY_TEMPERATURE))
        assertTrue(runCatching { ManualVitalsRecordFactory.build(row("weight", 70.0, 0, "{}")) }.isFailure)
    }

    @Test
    fun readerSkipsManualEntriesButKeepsOtherOwnRecords() {
        val mapper = HealthRecordMapper(zone = { ZoneOffset.UTC })
        val own = BloodGlucoseRecord(
            time = at, zoneOffset = ZoneOffset.UTC,
            metadata = Metadata.manualEntry(clientRecordId = "ayuvo_m_$uuid", clientRecordVersion = 1),
            level = BloodGlucose.millimolesPerLiter(5.0)
        )
        assertNull(mapper.map(own, at.toEpochMilli()))
        val external = BodyTemperatureRecord(
            time = at, zoneOffset = ZoneOffset.UTC,
            metadata = Metadata.manualEntry(clientRecordId = "other_1", clientRecordVersion = 1),
            temperature = Temperature.celsius(36.8)
        )
        assertEquals(36.8, mapper.map(external, at.toEpochMilli())!!.row.value!!, 1e-9)
        assertTrue(HealthRecordMapper.isManualEntry("ayuvo_m_x"))
        assertFalse(HealthRecordMapper.isManualEntry("ayuvo_$uuid"))
        assertFalse(HealthRecordMapper.isManualEntry("ayuvo_gh_x"))
        assertFalse(HealthRecordMapper.isGoogleHealthMirror("ayuvo_m_x"))
    }
}
