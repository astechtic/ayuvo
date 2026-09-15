package com.ayuvo.health.services.health

import androidx.health.connect.client.records.BloodPressureRecord
import androidx.health.connect.client.records.BodyFatRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.NutritionRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.records.metadata.DataOrigin
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.units.Energy
import androidx.health.connect.client.units.Mass
import androidx.health.connect.client.units.Percentage
import androidx.health.connect.client.units.Pressure
import com.ayuvo.health.data.health.HealthRollupMath
import com.ayuvo.health.data.health.HealthSampleRow
import com.ayuvo.health.data.health.HealthSleepCodes
import com.ayuvo.health.models.HealthDataType
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset

/**
 * Proves connect-client records are JVM-constructible (isReturnDefaultValues) and that the
 * mapper applies the shared conventions: percent 0–100, seconds, wake-day sleep, series stats.
 */
class HealthRecordMapperTest {
    private val nowMs = Instant.parse("2026-09-14T12:00:00Z").toEpochMilli()
    private val mapper = HealthRecordMapper(zone = { ZoneOffset.UTC })

    private val lastModified = Instant.parse("2026-09-13T12:00:00Z")

    /**
     * connect-client 1.1.0 only exposes factory functions that leave dataOrigin empty and
     * lastModifiedTime at EPOCH (the platform fills them on real reads). The full constructor is
     * Kotlin-`internal` but public in bytecode, so the test builds platform-shaped metadata
     * through it to exercise the source / last-modified paths.
     */
    private fun meta(id: String, clientId: String? = null, pkg: String = SOURCE_PKG): Metadata {
        val ctor = Metadata::class.java.getDeclaredConstructor(
            Int::class.javaPrimitiveType, String::class.java, DataOrigin::class.java, Instant::class.java,
            String::class.java, Long::class.javaPrimitiveType, Device::class.java
        )
        ctor.isAccessible = true
        return ctor.newInstance(
            Metadata.RECORDING_METHOD_AUTOMATICALLY_RECORDED, id, DataOrigin(pkg), lastModified, clientId, 0L,
            Device(manufacturer = "Acme", model = "Band 3", type = Device.TYPE_WATCH)
        )
    }

    @Test
    fun stepsMapToCumulativeCountRowWithSourceAndDevice() {
        val start = Instant.parse("2026-09-13T06:00:00Z")
        val record = StepsRecord(
            startTime = start, startZoneOffset = ZoneOffset.ofHours(2),
            endTime = start.plusSeconds(600), endZoneOffset = ZoneOffset.ofHours(2),
            count = 412, metadata = meta("steps-1")
        )
        val mapped = mapper.map(record, nowMs)!!
        val row = mapped.row
        assertEquals("steps-1", row.id)
        assertEquals("steps", row.typeId)
        assertEquals(412.0, row.value!!, 0.0)
        assertEquals("count", row.unit)
        assertEquals(7200, row.startOffsetS)
        assertEquals("2026-09-13", row.localDay)
        assertEquals(SOURCE_PKG, row.sourceId)
        assertEquals(SOURCE_PKG, mapped.source!!.id)
        assertEquals("Band 3", mapped.source!!.deviceModel)
        assertEquals("Acme Band 3", row.device)
        assertEquals(Device.TYPE_WATCH, row.deviceType)
        assertEquals(Metadata.RECORDING_METHOD_AUTOMATICALLY_RECORDED, row.recordingMethod)
        assertEquals(lastModified.toEpochMilli(), row.updatedMs)
        assertEquals(HealthSampleRow.ORIGIN_PLATFORM, row.origin)
    }

    @Test
    fun factoryMetadataWithoutOriginFallsBackToBlankSourceAndNow() {
        val record = StepsRecord(
            startTime = Instant.parse("2026-09-13T06:00:00Z"), startZoneOffset = null,
            endTime = Instant.parse("2026-09-13T06:10:00Z"), endZoneOffset = null,
            count = 10, metadata = Metadata.autoRecordedWithId("steps-2", Device(type = Device.TYPE_PHONE))
        )
        val mapped = mapper.map(record, nowMs)!!
        assertEquals("", mapped.row.sourceId)
        assertNull(mapped.source)
        assertEquals(nowMs, mapped.row.updatedMs)
    }

    @Test
    fun zoneOffsetMovesTheLocalDayAcrossMidnight() {
        // 23:30 in UTC-5 is already the next day in UTC; the row's own offset wins.
        val time = Instant.parse("2026-09-14T04:30:00Z")
        val record = WeightRecord(time = time, zoneOffset = ZoneOffset.ofHours(-5), weight = Mass.kilograms(80.4), metadata = meta("w-1", clientId = "ayuvo_abc"))
        val row = mapper.map(record, nowMs)!!.row
        assertEquals("2026-09-13", row.localDay)
        assertEquals(80.4, row.value!!, 1e-9)
        assertEquals("kg", row.unit)
        assertEquals("ayuvo_abc", row.clientRecordId)
    }

    @Test
    fun percentIsStoredZeroToHundred() {
        val record = BodyFatRecord(time = Instant.parse("2026-09-13T06:00:00Z"), zoneOffset = null, percentage = Percentage(23.5), metadata = meta("bf-1"))
        val row = mapper.map(record, nowMs)!!.row
        assertEquals(23.5, row.value!!, 1e-9)
        assertEquals("%", row.unit)
        assertNull(row.startOffsetS)
    }

    @Test
    fun heartRateSeriesCarriesAverageMinMaxCountAndPoints() {
        val start = Instant.parse("2026-09-13T06:00:00Z")
        val samples = listOf(60L, 70L, 80L, 90L).mapIndexed { i, bpm -> HeartRateRecord.Sample(start.plusSeconds(i * 60L), bpm) }
        val record = HeartRateRecord(startTime = start, startZoneOffset = null, endTime = start.plusSeconds(240), endZoneOffset = null, samples = samples, metadata = meta("hr-1"))
        val mapped = mapper.map(record, nowMs)!!
        assertEquals(75.0, mapped.row.value!!, 1e-9)
        assertEquals(60.0, mapped.row.value2!!, 0.0)
        assertEquals(90.0, mapped.row.value3!!, 0.0)
        assertEquals(4, mapped.row.count)
        assertEquals(4, mapped.series.size)
        assertEquals("hr-1", mapped.series.first().sampleId)
        assertEquals(70.0, mapped.series[1].value, 0.0)
    }

    @Test
    fun seriesPointsOlderThanRetentionAreNotEmittedButStillCounted() {
        val old = Instant.ofEpochMilli(nowMs).minusSeconds(400L * 86_400)
        val record = HeartRateRecord(
            startTime = old, startZoneOffset = null, endTime = old.plusSeconds(60), endZoneOffset = null,
            samples = listOf(HeartRateRecord.Sample(old, 55L), HeartRateRecord.Sample(old.plusSeconds(30), 65L)),
            metadata = meta("hr-old")
        )
        val mapped = mapper.map(record, nowMs)!!
        assertEquals(2, mapped.row.count)
        assertTrue(mapped.series.isEmpty())
    }

    @Test
    fun bloodPressureKeepsSystolicDiastolicAndBodyPosition() {
        val record = BloodPressureRecord(
            time = Instant.parse("2026-09-13T06:00:00Z"), zoneOffset = null,
            systolic = Pressure.millimetersOfMercury(121.0), diastolic = Pressure.millimetersOfMercury(79.0),
            bodyPosition = BloodPressureRecord.BODY_POSITION_SITTING_DOWN,
            measurementLocation = BloodPressureRecord.MEASUREMENT_LOCATION_LEFT_UPPER_ARM,
            metadata = meta("bp-1")
        )
        val row = mapper.map(record, nowMs)!!.row
        assertEquals("blood_pressure", row.typeId)
        assertEquals(121.0, row.value!!, 0.0)
        assertEquals(79.0, row.value2!!, 0.0)
        assertEquals(BloodPressureRecord.BODY_POSITION_SITTING_DOWN, row.categoryValue)
        assertEquals("mmHg", row.unit)
        val extra = HealthRollupMath.parseExtra(row.extraJson)!!
        assertEquals(BloodPressureRecord.MEASUREMENT_LOCATION_LEFT_UPPER_ARM.toString(), extra["measurement_location"]!!.jsonPrimitive.content)
    }

    @Test
    fun sleepSessionBecomesSessionRowPlusStageRowsOnTheWakeDay() {
        val start = Instant.parse("2026-09-12T22:30:00Z")
        val end = Instant.parse("2026-09-13T06:30:00Z")
        val record = SleepSessionRecord(
            startTime = start, startZoneOffset = ZoneOffset.UTC, endTime = end, endZoneOffset = ZoneOffset.UTC,
            title = "Night",
            stages = listOf(
                SleepSessionRecord.Stage(start, start.plusSeconds(1800), SleepSessionRecord.STAGE_TYPE_AWAKE_IN_BED),
                SleepSessionRecord.Stage(start.plusSeconds(1800), start.plusSeconds(3 * 3600), SleepSessionRecord.STAGE_TYPE_LIGHT),
                SleepSessionRecord.Stage(start.plusSeconds(3 * 3600), start.plusSeconds(5 * 3600), SleepSessionRecord.STAGE_TYPE_DEEP),
                SleepSessionRecord.Stage(start.plusSeconds(5 * 3600), end, SleepSessionRecord.STAGE_TYPE_REM)
            ),
            metadata = meta("sleep-1")
        )
        val mapped = mapper.map(record, nowMs)!!
        assertEquals(HealthSleepCodes.IN_BED, mapped.row.categoryValue)
        assertEquals(8 * 3600.0, mapped.row.value!!, 0.0)
        assertEquals("2026-09-13", mapped.row.localDay)
        assertEquals("Night", mapped.row.title)
        assertEquals(4, mapped.extraRows.size)
        assertEquals(listOf("sleep-1:0", "sleep-1:1", "sleep-1:2", "sleep-1:3"), mapped.extraRows.map { it.id })
        assertEquals(listOf(HealthSleepCodes.AWAKE, HealthSleepCodes.LIGHT, HealthSleepCodes.DEEP, HealthSleepCodes.REM), mapped.extraRows.map { it.categoryValue })
        assertTrue(mapped.extraRows.all { it.localDay == "2026-09-13" })
        assertEquals("sleep-1", HealthRollupMath.parseExtra(mapped.extraRows[0].extraJson)!!["session_id"]!!.jsonPrimitive.content)
    }

    @Test
    fun sleepSessionWithoutStagesCountsEntirelyAsAsleep() {
        val start = Instant.parse("2026-09-12T23:00:00Z")
        val record = SleepSessionRecord(startTime = start, startZoneOffset = null, endTime = start.plusSeconds(7 * 3600), endZoneOffset = null, metadata = meta("sleep-2"))
        val mapped = mapper.map(record, nowMs)!!
        assertEquals(1, mapped.extraRows.size)
        assertEquals(HealthSleepCodes.ASLEEP_UNSPECIFIED, mapped.extraRows.single().categoryValue)
        assertEquals(7 * 3600.0, mapped.extraRows.single().value!!, 0.0)
    }

    @Test
    fun workoutCarriesDurationActivityTypeAndExtraJson() {
        val start = Instant.parse("2026-09-13T17:00:00Z")
        val record = ExerciseSessionRecord(
            startTime = start, startZoneOffset = null, endTime = start.plusSeconds(2700), endZoneOffset = null,
            exerciseType = ExerciseSessionRecord.EXERCISE_TYPE_RUNNING, title = "Evening run", metadata = meta("ex-1")
        )
        val row = mapper.map(record, nowMs)!!.row
        assertEquals("workout", row.typeId)
        assertEquals(2700.0, row.value!!, 0.0)
        assertEquals(ExerciseSessionRecord.EXERCISE_TYPE_RUNNING, row.categoryValue)
        assertEquals("Evening run", row.title)
        val extra = HealthRollupMath.parseExtra(row.extraJson)!!
        assertEquals("false", extra["has_route"]!!.jsonPrimitive.content)
        assertEquals(ExerciseSessionRecord.EXERCISE_TYPE_RUNNING.toString(), extra["activity_type"]!!.jsonPrimitive.content)
    }

    @Test
    fun nutritionRecordIsOneRowWithEveryNutrientInExtraJson() {
        val start = Instant.parse("2026-09-13T12:00:00Z")
        val record = NutritionRecord(
            startTime = start, startZoneOffset = null, endTime = start.plusSeconds(60), endZoneOffset = null,
            energy = Energy.kilocalories(520.0), protein = Mass.grams(30.0), totalCarbohydrate = Mass.grams(55.0), totalFat = Mass.grams(18.0),
            dietaryFiber = Mass.grams(6.0), sodium = Mass.milligrams(700.0), vitaminC = Mass.milligrams(12.0),
            name = "Lunch bowl", mealType = androidx.health.connect.client.records.MealType.MEAL_TYPE_LUNCH, metadata = meta("nut-1")
        )
        val row = mapper.map(record, nowMs)!!.row
        assertEquals("nutrition", row.typeId)
        assertEquals(520.0, row.value!!, 1e-9)
        assertEquals(30.0, row.value2!!, 1e-9)
        assertEquals(55.0, row.value3!!, 1e-9)
        assertEquals("Lunch bowl", row.title)
        val extra = HealthRollupMath.parseExtra(row.extraJson)!!
        assertEquals(18.0, extra["fat_total"]!!.jsonPrimitive.content.toDouble(), 1e-9)
        assertEquals(6.0, extra["fiber"]!!.jsonPrimitive.content.toDouble(), 1e-9)
        assertEquals(700.0, extra["sodium"]!!.jsonPrimitive.content.toDouble(), 1e-9)
        assertEquals(12.0, extra["vitamin_c"]!!.jsonPrimitive.content.toDouble(), 1e-9)
        assertNull(extra["iron"])
        // Virtual dietary roll-ups read the same keys.
        val virtual = HealthRollupMath.virtualDietary(listOf(row), "UTC")
        assertEquals(18.0, virtual.getValue(HealthDataType.DIETARY_FAT_TOTAL).single().sum!!, 1e-9)
        assertEquals(12.0, virtual.getValue(HealthDataType.DIETARY_VITAMIN_C).single().sum!!, 1e-9)
    }

    @Test
    fun unknownLastModifiedFallsBackToNow() {
        val record = StepsRecord(
            startTime = Instant.parse("2026-09-13T06:00:00Z"), startZoneOffset = null,
            endTime = Instant.parse("2026-09-13T06:10:00Z"), endZoneOffset = null,
            count = 10, metadata = Metadata.manualEntryWithId("steps-manual")
        )
        val row = mapper.map(record, nowMs)!!.row
        assertEquals(nowMs, row.updatedMs)
        assertEquals(Metadata.RECORDING_METHOD_MANUAL_ENTRY, row.recordingMethod)
        assertNotNull(row.localDay)
    }

    @Test
    fun everySdkTypeResolvesFromItsRecordClassName() {
        for (type in HealthDataType.sdkTypes) {
            assertEquals(type, HealthRecordMapper.typeOfRecordClass(type.hcRecord!!))
            assertNotNull("no record class for ${type.id}", HealthConnectReadSource.recordClassFor(type))
        }
        assertEquals(HealthSleepCodes.AWAKE, HealthRecordMapper.sleepStageCode(SleepSessionRecord.STAGE_TYPE_AWAKE))
        assertEquals(HealthSleepCodes.OUT_OF_BED, HealthRecordMapper.sleepStageCode(SleepSessionRecord.STAGE_TYPE_OUT_OF_BED))
        assertEquals(HealthSleepCodes.ASLEEP_UNSPECIFIED, HealthRecordMapper.sleepStageCode(SleepSessionRecord.STAGE_TYPE_UNKNOWN))
    }

    private companion object {
        const val SOURCE_PKG = "com.example.band"
    }
}
