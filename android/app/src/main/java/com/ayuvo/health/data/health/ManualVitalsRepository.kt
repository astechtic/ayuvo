package com.ayuvo.health.data.health

import com.ayuvo.health.models.HealthDataType
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/** The two health types the user can log by hand (docs/health-data.md §2.2). */
enum class ManualVitalType(val type: HealthDataType, val retryKind: String) {
    BLOOD_GLUCOSE(HealthDataType.BLOOD_GLUCOSE, "bloodGlucose"),
    BODY_TEMPERATURE(HealthDataType.BODY_TEMPERATURE, "bodyTemperature");

    val typeId: String get() = type.id

    companion object {
        fun byTypeId(typeId: String): ManualVitalType? = entries.firstOrNull { it.typeId == typeId }
        fun byRetryKind(kind: String): ManualVitalType? = entries.firstOrNull { it.retryKind == kind }
    }
}

/** Codes, ranges and conversions of manual glucose / temperature entries (docs/health-data.md §2.2, §5). */
object ManualVitals {
    const val ID_PREFIX = "local:"
    /** clientRecordId prefix of the Health Connect copy; HealthRecordMapper skips it on the way back in. */
    const val CLIENT_PREFIX = "ayuvo_m_"
    const val RECORDING_METHOD_MANUAL = 3

    const val MGDL_PER_MMOL = 18.0182
    const val GLUCOSE_MIN_MMOL = 1.0
    const val GLUCOSE_MAX_MMOL = 33.3
    const val GLUCOSE_MIN_MGDL = 18.0
    const val GLUCOSE_MAX_MGDL = 600.0
    const val TEMP_MIN_C = 34.0
    const val TEMP_MAX_C = 42.0
    const val TEMP_MIN_F = 93.2
    const val TEMP_MAX_F = 107.6
    private const val EPS = 1e-9

    /** Blood glucose specimen source (= Health Connect). */
    const val SPECIMEN_UNKNOWN = 0
    const val SPECIMEN_INTERSTITIAL_FLUID = 1
    const val SPECIMEN_CAPILLARY_BLOOD = 2
    const val SPECIMEN_PLASMA = 3
    const val SPECIMEN_SERUM = 4
    const val SPECIMEN_TEARS = 5
    const val SPECIMEN_WHOLE_BLOOD = 6

    /** Relation to meal (= Health Connect). */
    const val RELATION_UNKNOWN = 0
    const val RELATION_GENERAL = 1
    const val RELATION_FASTING = 2
    const val RELATION_BEFORE_MEAL = 3
    const val RELATION_AFTER_MEAL = 4

    /** Meal type (= Health Connect). */
    const val MEAL_UNKNOWN = 0

    /** BodyTemperatureMeasurementLocation (= Health Connect), 0 unknown … 10 vagina. */
    const val LOCATION_UNKNOWN = 0
    const val LOCATION_MAX = 10

    fun mgdlToMmol(mgdl: Double): Double = mgdl / MGDL_PER_MMOL
    fun mmolToMgdl(mmol: Double): Double = mmol * MGDL_PER_MMOL
    fun fahrenheitToCelsius(f: Double): Double = (f - 32.0) * 5.0 / 9.0
    fun celsiusToFahrenheit(c: Double): Double = c * 9.0 / 5.0 + 32.0

    /** Range check in the unit the user typed, so 18 mg/dL and 93.2 °F are accepted exactly. */
    fun glucoseInRange(value: Double, mgdl: Boolean): Boolean =
        value.isFinite() && if (mgdl) value in (GLUCOSE_MIN_MGDL - EPS)..(GLUCOSE_MAX_MGDL + EPS)
        else value in (GLUCOSE_MIN_MMOL - EPS)..(GLUCOSE_MAX_MMOL + EPS)

    fun temperatureInRange(value: Double, fahrenheit: Boolean): Boolean =
        value.isFinite() && if (fahrenheit) value in (TEMP_MIN_F - EPS)..(TEMP_MAX_F + EPS)
        else value in (TEMP_MIN_C - EPS)..(TEMP_MAX_C + EPS)

    fun rowId(uuid: UUID): String = "$ID_PREFIX$uuid"
    fun clientRecordId(uuid: UUID): String = "$CLIENT_PREFIX$uuid"
    fun uuidOf(rowId: String): UUID? =
        if (rowId.startsWith(ID_PREFIX)) runCatching { UUID.fromString(rowId.removePrefix(ID_PREFIX)) }.getOrNull() else null

    fun isManualClientRecordId(clientRecordId: String?): Boolean = clientRecordId?.startsWith(CLIENT_PREFIX) == true

    /** True for rows the user may delete from Show All Data. */
    fun isDeletable(row: HealthSampleRow): Boolean =
        row.origin == HealthSampleRow.ORIGIN_LOCAL_APP && ManualVitalType.byTypeId(row.typeId) != null && uuidOf(row.id) != null
}

/** Health Connect side of a manual entry; the database row stays canonical whatever this returns. */
interface ManualVitalsPlatform {
    suspend fun upsert(type: ManualVitalType, row: HealthSampleRow): Boolean
    suspend fun delete(type: ManualVitalType, uuid: UUID): Boolean
}

/**
 * Manual blood glucose / body temperature entries (docs/health-data.md §2.2): one origin-2
 * `health_samples` row each (`local:<uuid>`, recording method 3), committed in one transaction,
 * then the day's rollups are rebuilt and the entry is mirrored to Health Connect.
 * Values never touch DataStore / SharedPreferences.
 */
class ManualVitalsRepository(
    private val store: () -> HealthDataStore,
    private val packageName: String,
    private val platform: ManualVitalsPlatform? = null,
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
    private val now: () -> Long = { System.currentTimeMillis() },
    private val newId: () -> UUID = { UUID.randomUUID() }
) {

    /** [value] in mg/dL when [mgdl], else mmol/L. Throws IllegalArgumentException when out of range or in the future. */
    suspend fun logGlucose(
        value: Double,
        mgdl: Boolean,
        atMs: Long,
        specimen: Int = ManualVitals.SPECIMEN_CAPILLARY_BLOOD,
        relationToMeal: Int = ManualVitals.RELATION_GENERAL,
        mealType: Int = ManualVitals.MEAL_UNKNOWN
    ): HealthSampleRow {
        require(ManualVitals.glucoseInRange(value, mgdl)) { "glucose out of range" }
        val mmol = if (mgdl) ManualVitals.mgdlToMmol(value) else value
        val extra = buildJsonObject {
            put("relation_to_meal", relationToMeal.coerceIn(0, 4))
            put("meal_type", mealType.coerceIn(0, 4))
        }.toString()
        val row = buildRow(ManualVitalType.BLOOD_GLUCOSE, mmol, specimen.coerceIn(0, 6), extra, atMs)
        return save(ManualVitalType.BLOOD_GLUCOSE, row)
    }

    /** [value] in °F when [fahrenheit], else °C. Throws IllegalArgumentException when out of range or in the future. */
    suspend fun logTemperature(
        value: Double,
        fahrenheit: Boolean,
        atMs: Long,
        location: Int = ManualVitals.LOCATION_UNKNOWN
    ): HealthSampleRow {
        require(ManualVitals.temperatureInRange(value, fahrenheit)) { "temperature out of range" }
        val celsius = if (fahrenheit) ManualVitals.fahrenheitToCelsius(value) else value
        val extra = buildJsonObject {
            put("measurement_location", location.coerceIn(0, ManualVitals.LOCATION_MAX))
        }.toString()
        val row = buildRow(ManualVitalType.BODY_TEMPERATURE, celsius, 0, extra, atMs)
        return save(ManualVitalType.BODY_TEMPERATURE, row)
    }

    /** Live (non-deleted) manual row of [uuid], or null; used by the Health Connect retry worker. */
    suspend fun liveRow(uuid: UUID): HealthSampleRow? =
        store().samplesByIds(listOf(ManualVitals.rowId(uuid))).firstOrNull { !it.deleted && ManualVitals.isDeletable(it) }

    /** Tombstones an origin-2 manual row, rebuilds its day and deletes the Health Connect copy. */
    suspend fun delete(rowId: String): Boolean {
        val s = store()
        val row = s.samplesByIds(listOf(rowId)).firstOrNull() ?: return false
        if (row.deleted || !ManualVitals.isDeletable(row)) return false
        val type = ManualVitalType.byTypeId(row.typeId) ?: return false
        val result = s.commit(
            HealthPageCommit(deletedIds = listOf(row.id), deleteOrigins = setOf(HealthSampleRow.ORIGIN_LOCAL_APP))
        )
        if (result.tombstoned == 0) return false
        rebuildDay(type, row.localDay)
        ManualVitals.uuidOf(row.id)?.let { uuid -> runCatching { platform?.delete(type, uuid) } }
        return true
    }

    private fun buildRow(type: ManualVitalType, value: Double, category: Int, extra: String, atMs: Long): HealthSampleRow {
        val nowMs = now()
        require(atMs <= nowMs + FUTURE_SLACK_MS) { "time in the future" }
        val z = zone()
        val offset = z.rules.getOffset(Instant.ofEpochMilli(atMs)).totalSeconds
        return HealthSampleRow(
            id = ManualVitals.rowId(newId()),
            typeId = type.typeId,
            startMs = atMs,
            endMs = atMs,
            startOffsetS = offset,
            endOffsetS = offset,
            localDay = HealthDayKeys.localDay(type.type, atMs, atMs, offset, offset, z),
            value = value,
            unit = type.type.unit,
            categoryValue = category,
            extraJson = extra,
            sourceId = packageName,
            recordingMethod = ManualVitals.RECORDING_METHOD_MANUAL,
            origin = HealthSampleRow.ORIGIN_LOCAL_APP,
            updatedMs = nowMs
        )
    }

    private suspend fun save(type: ManualVitalType, row: HealthSampleRow): HealthSampleRow {
        store().commit(
            HealthPageCommit(
                rows = listOf(row),
                sources = listOf(HealthSourceRow(id = packageName, name = LocalHealthSources.LOCAL_SOURCE_NAME, lastSeenMs = row.updatedMs))
            )
        )
        rebuildDay(type, row.localDay)
        runCatching { platform?.upsert(type, row) }
        return row
    }

    private suspend fun rebuildDay(type: ManualVitalType, day: String) {
        val s = store()
        val z = zone()
        val date = runCatching { LocalDate.parse(day) }.getOrNull() ?: return
        val from = date.minusDays(2).atStartOfDay(z).toInstant().toEpochMilli()
        val to = date.plusDays(2).atStartOfDay(z).toInstant().toEpochMilli()
        val rows = s.samplesBetween(type.typeId, from, to).filter { it.localDay == day }
        s.replaceDailyRollups(type.typeId, listOf(day), HealthRollupMath.rebuildDaily(HealthTypeDescriptor.of(type.type), rows, z.id))
    }

    companion object {
        /** Clock skew allowance for "cannot be in the future". */
        const val FUTURE_SLACK_MS = 60_000L
    }
}
