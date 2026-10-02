package com.ayuvo.health.services.health

import androidx.health.connect.client.records.BloodGlucoseRecord
import androidx.health.connect.client.records.BodyTemperatureRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.units.BloodGlucose
import androidx.health.connect.client.units.Temperature
import com.ayuvo.health.data.health.HealthRollupMath
import com.ayuvo.health.data.health.HealthSampleRow
import com.ayuvo.health.data.health.ManualVitalType
import com.ayuvo.health.data.health.ManualVitals
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import java.time.Instant
import java.time.ZoneOffset
import kotlin.reflect.KClass

/**
 * Origin-2 manual row → Health Connect record (docs/health-data.md §2.2): `Metadata.manualEntry`
 * with `clientRecordId = "ayuvo_m_<uuid>"` and `clientRecordVersion = updated_ms`, so a rewrite is
 * an idempotent upsert and HealthRecordMapper skips the record on the way back in.
 * Pure over the connect-client data classes (JVM-tested like GoogleHealthRecordFactory).
 */
object ManualVitalsRecordFactory {

    fun recordClass(type: ManualVitalType): KClass<out Record> = when (type) {
        ManualVitalType.BLOOD_GLUCOSE -> BloodGlucoseRecord::class
        ManualVitalType.BODY_TEMPERATURE -> BodyTemperatureRecord::class
    }

    /** Builds the record; throws IllegalArgumentException for a row that is not a manual entry. */
    fun build(row: HealthSampleRow): Record {
        val type = ManualVitalType.byTypeId(row.typeId) ?: throw IllegalArgumentException("not a manual type")
        val uuid = ManualVitals.uuidOf(row.id) ?: throw IllegalArgumentException("not a manual row")
        val value = row.value?.takeIf { it.isFinite() } ?: throw IllegalArgumentException("missing value")
        val metadata = Metadata.manualEntry(
            clientRecordId = ManualVitals.clientRecordId(uuid),
            clientRecordVersion = row.updatedMs
        )
        val time = Instant.ofEpochMilli(row.startMs)
        val offset = row.startOffsetS?.let(ZoneOffset::ofTotalSeconds)
        val extra = HealthRollupMath.parseExtra(row.extraJson)
        fun code(key: String): Int? = (extra?.get(key) as? JsonPrimitive)?.intOrNull
        return when (type) {
            ManualVitalType.BLOOD_GLUCOSE -> BloodGlucoseRecord(
                time = time, zoneOffset = offset, metadata = metadata,
                level = BloodGlucose.millimolesPerLiter(value),
                specimenSource = row.categoryValue?.takeIf { it in 0..6 } ?: BloodGlucoseRecord.SPECIMEN_SOURCE_UNKNOWN,
                mealType = code("meal_type")?.takeIf { it in 0..4 } ?: 0,
                relationToMeal = code("relation_to_meal")?.takeIf { it in 0..4 } ?: BloodGlucoseRecord.RELATION_TO_MEAL_UNKNOWN
            )
            ManualVitalType.BODY_TEMPERATURE -> BodyTemperatureRecord(
                time = time, zoneOffset = offset, metadata = metadata,
                temperature = Temperature.celsius(value),
                measurementLocation = code("measurement_location")?.takeIf { it in 0..ManualVitals.LOCATION_MAX } ?: 0
            )
        }
    }
}
