@file:OptIn(ExperimentalMindfulnessSessionApi::class)

package com.ayuvo.health.services.health

import androidx.health.connect.client.feature.ExperimentalMindfulnessSessionApi
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.BasalBodyTemperatureRecord
import androidx.health.connect.client.records.BasalMetabolicRateRecord
import androidx.health.connect.client.records.BloodGlucoseRecord
import androidx.health.connect.client.records.BloodPressureRecord
import androidx.health.connect.client.records.BodyFatRecord
import androidx.health.connect.client.records.BodyTemperatureRecord
import androidx.health.connect.client.records.BodyWaterMassRecord
import androidx.health.connect.client.records.BoneMassRecord
import androidx.health.connect.client.records.CervicalMucusRecord
import androidx.health.connect.client.records.CyclingPedalingCadenceRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ElevationGainedRecord
import androidx.health.connect.client.records.ExerciseRouteResult
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.FloorsClimbedRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.HeightRecord
import androidx.health.connect.client.records.HydrationRecord
import androidx.health.connect.client.records.IntermenstrualBleedingRecord
import androidx.health.connect.client.records.LeanBodyMassRecord
import androidx.health.connect.client.records.MenstruationFlowRecord
import androidx.health.connect.client.records.MenstruationPeriodRecord
import androidx.health.connect.client.records.MindfulnessSessionRecord
import androidx.health.connect.client.records.NutritionRecord
import androidx.health.connect.client.records.OvulationTestRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.PlannedExerciseSessionRecord
import androidx.health.connect.client.records.PowerRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.RespiratoryRateRecord
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SexualActivityRecord
import androidx.health.connect.client.records.SkinTemperatureRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.SpeedRecord
import androidx.health.connect.client.records.StepsCadenceRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.records.Vo2MaxRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.records.WheelchairPushesRecord
import androidx.health.connect.client.records.metadata.Metadata
import com.ayuvo.health.data.health.HealthDayKeys
import com.ayuvo.health.data.health.HealthSampleRow
import com.ayuvo.health.data.health.HealthSeriesPoint
import com.ayuvo.health.data.health.HealthSleepCodes
import com.ayuvo.health.data.health.HealthSourceRow
import com.ayuvo.health.models.HealthDataType
import com.ayuvo.health.models.HealthDayAttribution
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

/**
 * Health Connect record → mirror rows. Pure Kotlin over the connect-client data classes
 * (no HealthConnectClient, no android.util.Log) so it is unit-tested on the JVM with
 * `isReturnDefaultValues`. Conventions: percent 0–100, durations in seconds, SI units,
 * sleep sessions → one session row (code 0) + one row per stage (`<id>:<n>`), series
 * records carry avg/min/max + count and their points (retained for [seriesRetentionDays]).
 */
class HealthRecordMapper(
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
    private val seriesRetentionDays: Long = 365
) {

    fun map(record: Record, nowMs: Long): HealthMapped? {
        val type = typeOf(record) ?: return null
        val meta = record.metadata
        val base = baseFor(record, type, meta, nowMs)
        val mapped = mapRecord(record, base, meta, nowMs) ?: return null
        // Every row (not only sessions/series) reports its origin app for the sources table.
        return if (mapped.source == null) mapped.copy(source = sourceOf(meta, nowMs)) else mapped
    }

    private fun mapRecord(record: Record, base: HealthSampleRow, meta: Metadata, nowMs: Long): HealthMapped? {
        return when (record) {
            is StepsRecord -> single(base, record.count.toDouble())
            is DistanceRecord -> single(base, record.distance.inMeters)
            is WheelchairPushesRecord -> single(base, record.count.toDouble())
            is FloorsClimbedRecord -> single(base, record.floors)
            is ElevationGainedRecord -> single(base, record.elevation.inMeters)
            is ActiveCaloriesBurnedRecord -> single(base, record.energy.inKilocalories)
            is TotalCaloriesBurnedRecord -> single(base, record.energy.inKilocalories)
            is HydrationRecord -> single(base, record.volume.inMilliliters)
            is WeightRecord -> single(base, record.weight.inKilograms)
            is HeightRecord -> single(base, record.height.inMeters)
            is BodyFatRecord -> single(base, record.percentage.value)
            is LeanBodyMassRecord -> single(base, record.mass.inKilograms)
            is BoneMassRecord -> single(base, record.mass.inKilograms)
            is BodyWaterMassRecord -> single(base, record.mass.inKilograms)
            is BasalMetabolicRateRecord -> single(base, record.basalMetabolicRate.inKilocaloriesPerDay)
            is RestingHeartRateRecord -> single(base, record.beatsPerMinute.toDouble())
            is HeartRateVariabilityRmssdRecord -> single(base, record.heartRateVariabilityMillis)
            is Vo2MaxRecord -> single(
                base.copy(extraJson = json { put("measurement_method", record.measurementMethod) }),
                record.vo2MillilitersPerMinuteKilogram
            )
            is RespiratoryRateRecord -> single(base, record.rate)
            is OxygenSaturationRecord -> single(base, record.percentage.value)
            is BodyTemperatureRecord -> single(
                base.copy(extraJson = json { put("measurement_location", record.measurementLocation) }),
                record.temperature.inCelsius
            )
            is BasalBodyTemperatureRecord -> single(
                base.copy(extraJson = json { put("measurement_location", record.measurementLocation) }),
                record.temperature.inCelsius
            )
            is BloodGlucoseRecord -> single(
                base.copy(
                    categoryValue = record.specimenSource,
                    extraJson = json {
                        put("specimen_source", record.specimenSource)
                        put("meal_type", record.mealType)
                        put("relation_to_meal", record.relationToMeal)
                    }
                ),
                record.level.inMillimolesPerLiter
            )
            is BloodPressureRecord -> HealthMapped(
                row = base.copy(
                    value = record.systolic.inMillimetersOfMercury,
                    value2 = record.diastolic.inMillimetersOfMercury,
                    categoryValue = record.bodyPosition,
                    extraJson = json {
                        put("body_position", record.bodyPosition)
                        put("measurement_location", record.measurementLocation)
                    }
                ),
                source = sourceOf(meta, nowMs)
            )
            is HeartRateRecord -> series(base, nowMs, record.samples.map { it.time.toEpochMilli() to it.beatsPerMinute.toDouble() })
            is SpeedRecord -> series(base, nowMs, record.samples.map { it.time.toEpochMilli() to it.speed.inMetersPerSecond })
            is PowerRecord -> series(base, nowMs, record.samples.map { it.time.toEpochMilli() to it.power.inWatts })
            is CyclingPedalingCadenceRecord -> series(base, nowMs, record.samples.map { it.time.toEpochMilli() to it.revolutionsPerMinute })
            is StepsCadenceRecord -> series(base, nowMs, record.samples.map { it.time.toEpochMilli() to it.rate })
            is SkinTemperatureRecord -> series(
                base.copy(
                    extraJson = json {
                        record.baseline?.let { put("baseline_c", it.inCelsius) }
                        put("measurement_location", record.measurementLocation)
                    }
                ),
                nowMs,
                record.deltas.map { it.time.toEpochMilli() to it.delta.inCelsius }
            )
            is ExerciseSessionRecord -> HealthMapped(
                row = base.copy(
                    value = base.durationS,
                    categoryValue = record.exerciseType,
                    title = record.title,
                    extraJson = json {
                        put("activity_type", record.exerciseType)
                        put("segments", record.segments.size)
                        put("laps", record.laps.size)
                        put("has_route", record.exerciseRouteResult !is ExerciseRouteResult.NoData)
                        record.notes?.let { put("notes", it) }
                        record.plannedExerciseSessionId?.let { put("planned_exercise_session_id", it) }
                    }
                ),
                source = sourceOf(meta, nowMs)
            )
            is PlannedExerciseSessionRecord -> HealthMapped(
                row = base.copy(
                    value = base.durationS,
                    categoryValue = record.exerciseType,
                    title = record.title,
                    extraJson = json {
                        put("activity_type", record.exerciseType)
                        put("blocks", record.blocks.size)
                        record.notes?.let { put("notes", it) }
                        record.completedExerciseSessionId?.let { put("completed_exercise_session_id", it) }
                    }
                ),
                source = sourceOf(meta, nowMs)
            )
            is MindfulnessSessionRecord -> HealthMapped(
                row = base.copy(
                    value = base.durationS,
                    categoryValue = record.mindfulnessSessionType,
                    title = record.title,
                    extraJson = record.notes?.let { notes -> json { put("notes", notes) } }
                ),
                source = sourceOf(meta, nowMs)
            )
            is SleepSessionRecord -> sleep(base, record, meta, nowMs)
            is NutritionRecord -> nutrition(base, record, meta, nowMs)
            is MenstruationFlowRecord -> single(
                base.copy(categoryValue = menstrualFlowCode(record.flow)), null
            )
            is MenstruationPeriodRecord -> HealthMapped(
                row = base.copy(value = HealthDayKeys.daysCovered(base.startMs, base.endMs, base.startOffsetS, base.endOffsetS, zone()).size.toDouble()),
                source = sourceOf(meta, nowMs)
            )
            is IntermenstrualBleedingRecord -> single(base.copy(categoryValue = 0), null) // registry: 0 = occurred
            is OvulationTestRecord -> single(base.copy(categoryValue = ovulationCode(record.result)), null)
            is CervicalMucusRecord -> single(
                base.copy(categoryValue = record.appearance, extraJson = json { put("sensation", record.sensation) }), null
            )
            is SexualActivityRecord -> single(base.copy(categoryValue = record.protectionUsed), null)
            else -> null
        }
    }

    // -- Builders ------------------------------------------------------------

    private fun single(base: HealthSampleRow, value: Double?): HealthMapped =
        HealthMapped(row = base.copy(value = value), source = null)

    private fun series(base: HealthSampleRow, nowMs: Long, samples: List<Pair<Long, Double>>): HealthMapped {
        if (samples.isEmpty()) return HealthMapped(row = base.copy(count = 0))
        val values = samples.map { it.second }
        val cutoff = nowMs - seriesRetentionDays * DAY_MS
        val points = samples
            .filter { it.first >= cutoff }
            .distinctBy { it.first }
            .map { (t, v) -> HealthSeriesPoint(base.id, base.typeId, t, v) }
        return HealthMapped(
            row = base.copy(
                value = values.average(),
                value2 = values.min(),
                value3 = values.max(),
                count = samples.size
            ),
            series = points
        )
    }

    private fun sleep(base: HealthSampleRow, record: SleepSessionRecord, meta: Metadata, nowMs: Long): HealthMapped {
        val wakeDay = base.localDay
        val session = base.copy(
            value = base.durationS,
            categoryValue = HealthSleepCodes.IN_BED,
            title = record.title,
            extraJson = json {
                put("stage_count", record.stages.size)
                record.notes?.let { put("notes", it) }
            }
        )
        val sessionExtra = json { put("session_id", base.id) }
        val stages = if (record.stages.isEmpty()) {
            listOf(
                base.copy(
                    id = "${base.id}:0",
                    value = base.durationS,
                    categoryValue = HealthSleepCodes.ASLEEP_UNSPECIFIED,
                    localDay = wakeDay,
                    extraJson = sessionExtra
                )
            )
        } else {
            record.stages.mapIndexed { index, stage ->
                val start = stage.startTime.toEpochMilli()
                val end = stage.endTime.toEpochMilli()
                base.copy(
                    id = "${base.id}:$index",
                    startMs = start,
                    endMs = end,
                    localDay = wakeDay,
                    value = (end - start).coerceAtLeast(0L) / 1000.0,
                    categoryValue = sleepStageCode(stage.stage),
                    extraJson = sessionExtra
                )
            }
        }
        return HealthMapped(row = session, extraRows = stages, source = sourceOf(meta, nowMs))
    }

    private fun nutrition(base: HealthSampleRow, record: NutritionRecord, meta: Metadata, nowMs: Long): HealthMapped {
        val extra = buildJsonObject {
            record.energy?.let { put("energy", it.inKilocalories) }
            record.energyFromFat?.let { put("energy_from_fat", it.inKilocalories) }
            record.protein?.let { put("protein", it.inGrams) }
            record.totalCarbohydrate?.let { put("carbohydrates", it.inGrams) }
            record.totalFat?.let { put("fat_total", it.inGrams) }
            record.dietaryFiber?.let { put("fiber", it.inGrams) }
            record.sugar?.let { put("sugar", it.inGrams) }
            record.saturatedFat?.let { put("fat_saturated", it.inGrams) }
            record.monounsaturatedFat?.let { put("fat_monounsaturated", it.inGrams) }
            record.polyunsaturatedFat?.let { put("fat_polyunsaturated", it.inGrams) }
            record.transFat?.let { put("fat_trans", it.inGrams) }
            record.unsaturatedFat?.let { put("fat_unsaturated", it.inGrams) }
            record.cholesterol?.let { put("cholesterol", it.inMilligrams) }
            record.sodium?.let { put("sodium", it.inMilligrams) }
            record.potassium?.let { put("potassium", it.inMilligrams) }
            record.calcium?.let { put("calcium", it.inMilligrams) }
            record.iron?.let { put("iron", it.inMilligrams) }
            record.magnesium?.let { put("magnesium", it.inMilligrams) }
            record.zinc?.let { put("zinc", it.inMilligrams) }
            record.phosphorus?.let { put("phosphorus", it.inMilligrams) }
            record.copper?.let { put("copper", it.inMilligrams) }
            record.manganese?.let { put("manganese", it.inMilligrams) }
            record.chloride?.let { put("chloride", it.inMilligrams) }
            record.vitaminA?.let { put("vitamin_a", it.inMicrograms) }
            record.vitaminB6?.let { put("vitamin_b6", it.inMilligrams) }
            record.vitaminB12?.let { put("vitamin_b12", it.inMicrograms) }
            record.vitaminC?.let { put("vitamin_c", it.inMilligrams) }
            record.vitaminD?.let { put("vitamin_d", it.inMicrograms) }
            record.vitaminE?.let { put("vitamin_e", it.inMilligrams) }
            record.vitaminK?.let { put("vitamin_k", it.inMicrograms) }
            record.thiamin?.let { put("thiamin", it.inMilligrams) }
            record.riboflavin?.let { put("riboflavin", it.inMilligrams) }
            record.niacin?.let { put("niacin", it.inMilligrams) }
            record.folate?.let { put("folate", it.inMicrograms) }
            record.folicAcid?.let { put("folic_acid", it.inMicrograms) }
            record.biotin?.let { put("biotin", it.inMicrograms) }
            record.pantothenicAcid?.let { put("pantothenic_acid", it.inMilligrams) }
            record.iodine?.let { put("iodine", it.inMicrograms) }
            record.selenium?.let { put("selenium", it.inMicrograms) }
            record.chromium?.let { put("chromium", it.inMicrograms) }
            record.molybdenum?.let { put("molybdenum", it.inMicrograms) }
            record.caffeine?.let { put("caffeine", it.inMilligrams) }
            put("meal_type", record.mealType)
        }
        return HealthMapped(
            row = base.copy(
                value = record.energy?.inKilocalories,
                value2 = record.protein?.inGrams,
                value3 = record.totalCarbohydrate?.inGrams,
                categoryValue = record.mealType,
                title = record.name,
                extraJson = extra.toString()
            ),
            source = sourceOf(meta, nowMs)
        )
    }

    private fun baseFor(record: Record, type: HealthDataType, meta: Metadata, nowMs: Long): HealthSampleRow {
        val (startMs, endMs, startOff, endOff) = timing(record)
        val z = zone()
        val updated = meta.lastModifiedTime.takeIf { it != Instant.EPOCH }?.toEpochMilli() ?: nowMs
        return HealthSampleRow(
            id = meta.id.ifBlank { "${type.id}:${startMs}:${meta.dataOrigin.packageName}" },
            typeId = type.id,
            startMs = startMs,
            endMs = endMs,
            startOffsetS = startOff,
            endOffsetS = endOff,
            localDay = HealthDayKeys.localDay(type.dayAttribution, startMs, endMs, startOff, endOff, z),
            unit = type.unit,
            count = 1,
            sourceId = meta.dataOrigin.packageName,
            device = meta.device?.let { d -> listOfNotNull(d.manufacturer, d.model).joinToString(" ").ifBlank { null } },
            deviceType = meta.device?.type,
            recordingMethod = meta.recordingMethod,
            clientRecordId = meta.clientRecordId,
            origin = HealthSampleRow.ORIGIN_PLATFORM,
            updatedMs = updated
        )
    }

    private data class Timing(val startMs: Long, val endMs: Long, val startOffsetS: Int?, val endOffsetS: Int?)

    // InstantaneousRecord / IntervalRecord are internal to connect-client, so the two shapes
    // are matched on the concrete classes instead.
    private fun instant(time: Instant, offset: ZoneOffset?): Timing {
        val ms = time.toEpochMilli()
        return Timing(ms, ms, offset?.totalSeconds, offset?.totalSeconds)
    }

    private fun interval(start: Instant, end: Instant, startOffset: ZoneOffset?, endOffset: ZoneOffset?): Timing =
        Timing(start.toEpochMilli(), end.toEpochMilli(), startOffset?.totalSeconds, endOffset?.totalSeconds)

    private fun timing(record: Record): Timing = when (record) {
        is WeightRecord -> instant(record.time, record.zoneOffset)
        is HeightRecord -> instant(record.time, record.zoneOffset)
        is BodyFatRecord -> instant(record.time, record.zoneOffset)
        is LeanBodyMassRecord -> instant(record.time, record.zoneOffset)
        is BoneMassRecord -> instant(record.time, record.zoneOffset)
        is BodyWaterMassRecord -> instant(record.time, record.zoneOffset)
        is BasalMetabolicRateRecord -> instant(record.time, record.zoneOffset)
        is RestingHeartRateRecord -> instant(record.time, record.zoneOffset)
        is HeartRateVariabilityRmssdRecord -> instant(record.time, record.zoneOffset)
        is BloodPressureRecord -> instant(record.time, record.zoneOffset)
        is Vo2MaxRecord -> instant(record.time, record.zoneOffset)
        is BloodGlucoseRecord -> instant(record.time, record.zoneOffset)
        is BodyTemperatureRecord -> instant(record.time, record.zoneOffset)
        is BasalBodyTemperatureRecord -> instant(record.time, record.zoneOffset)
        is RespiratoryRateRecord -> instant(record.time, record.zoneOffset)
        is OxygenSaturationRecord -> instant(record.time, record.zoneOffset)
        is MenstruationFlowRecord -> instant(record.time, record.zoneOffset)
        is IntermenstrualBleedingRecord -> instant(record.time, record.zoneOffset)
        is OvulationTestRecord -> instant(record.time, record.zoneOffset)
        is CervicalMucusRecord -> instant(record.time, record.zoneOffset)
        is SexualActivityRecord -> instant(record.time, record.zoneOffset)
        is StepsRecord -> interval(record.startTime, record.endTime, record.startZoneOffset, record.endZoneOffset)
        is DistanceRecord -> interval(record.startTime, record.endTime, record.startZoneOffset, record.endZoneOffset)
        is WheelchairPushesRecord -> interval(record.startTime, record.endTime, record.startZoneOffset, record.endZoneOffset)
        is FloorsClimbedRecord -> interval(record.startTime, record.endTime, record.startZoneOffset, record.endZoneOffset)
        is ElevationGainedRecord -> interval(record.startTime, record.endTime, record.startZoneOffset, record.endZoneOffset)
        is ActiveCaloriesBurnedRecord -> interval(record.startTime, record.endTime, record.startZoneOffset, record.endZoneOffset)
        is TotalCaloriesBurnedRecord -> interval(record.startTime, record.endTime, record.startZoneOffset, record.endZoneOffset)
        is HydrationRecord -> interval(record.startTime, record.endTime, record.startZoneOffset, record.endZoneOffset)
        is ExerciseSessionRecord -> interval(record.startTime, record.endTime, record.startZoneOffset, record.endZoneOffset)
        is PlannedExerciseSessionRecord -> interval(record.startTime, record.endTime, record.startZoneOffset, record.endZoneOffset)
        is SleepSessionRecord -> interval(record.startTime, record.endTime, record.startZoneOffset, record.endZoneOffset)
        is NutritionRecord -> interval(record.startTime, record.endTime, record.startZoneOffset, record.endZoneOffset)
        is MenstruationPeriodRecord -> interval(record.startTime, record.endTime, record.startZoneOffset, record.endZoneOffset)
        is MindfulnessSessionRecord -> interval(record.startTime, record.endTime, record.startZoneOffset, record.endZoneOffset)
        is HeartRateRecord -> interval(record.startTime, record.endTime, record.startZoneOffset, record.endZoneOffset)
        is SpeedRecord -> interval(record.startTime, record.endTime, record.startZoneOffset, record.endZoneOffset)
        is PowerRecord -> interval(record.startTime, record.endTime, record.startZoneOffset, record.endZoneOffset)
        is CyclingPedalingCadenceRecord -> interval(record.startTime, record.endTime, record.startZoneOffset, record.endZoneOffset)
        is StepsCadenceRecord -> interval(record.startTime, record.endTime, record.startZoneOffset, record.endZoneOffset)
        is SkinTemperatureRecord -> interval(record.startTime, record.endTime, record.startZoneOffset, record.endZoneOffset)
        else -> Timing(0L, 0L, null, null)
    }

    private fun sourceOf(meta: Metadata, nowMs: Long): HealthSourceRow? {
        val pkg = meta.dataOrigin.packageName
        if (pkg.isBlank()) return null
        return HealthSourceRow(
            id = pkg,
            name = pkg,
            deviceModel = meta.device?.model,
            deviceType = meta.device?.type,
            lastSeenMs = nowMs
        )
    }

    private fun json(build: JsonObjectBuilder.() -> Unit): String = buildJsonObject(build).toString()

    companion object {
        private const val DAY_MS = 86_400_000L

        /** Registry type for a record instance (class simple name lookup). */
        fun typeOf(record: Record): HealthDataType? = typeOfRecordClass(record.javaClass.simpleName)

        fun typeOfRecordClass(simpleName: String): HealthDataType? = when (simpleName) {
            // Two registry types ride on StepsRecord/ExerciseSession permissions but map to their own records.
            else -> HealthDataType.sdkTypes.firstOrNull { it.hcRecord == simpleName }
        }

        /** HC stage constants → canonical codes shared with iOS. */
        fun sleepStageCode(hcStage: Int): Int = when (hcStage) {
            SleepSessionRecord.STAGE_TYPE_SLEEPING -> HealthSleepCodes.ASLEEP_UNSPECIFIED
            SleepSessionRecord.STAGE_TYPE_AWAKE, SleepSessionRecord.STAGE_TYPE_AWAKE_IN_BED -> HealthSleepCodes.AWAKE
            SleepSessionRecord.STAGE_TYPE_LIGHT -> HealthSleepCodes.LIGHT
            SleepSessionRecord.STAGE_TYPE_DEEP -> HealthSleepCodes.DEEP
            SleepSessionRecord.STAGE_TYPE_REM -> HealthSleepCodes.REM
            SleepSessionRecord.STAGE_TYPE_OUT_OF_BED -> HealthSleepCodes.OUT_OF_BED
            else -> HealthSleepCodes.ASLEEP_UNSPECIFIED
        }

        /** HC ovulation result (0 inconclusive/1 positive/2 high/3 negative) → registry codes (1 negative/2 positive/3 indeterminate/4 estrogen_surge). */
        fun ovulationCode(hcResult: Int): Int = when (hcResult) {
            OvulationTestRecord.RESULT_POSITIVE -> 2
            OvulationTestRecord.RESULT_HIGH -> 4
            OvulationTestRecord.RESULT_NEGATIVE -> 1
            else -> 3
        }

        /** HC flow (0 unknown/1 light/2 medium/3 heavy) → registry codes (1 unspecified/2 light/3 medium/4 heavy/5 none). */
        fun menstrualFlowCode(hcFlow: Int): Int = when (hcFlow) {
            MenstruationFlowRecord.FLOW_LIGHT -> 2
            MenstruationFlowRecord.FLOW_MEDIUM -> 3
            MenstruationFlowRecord.FLOW_HEAVY -> 4
            else -> 1
        }

        fun offsetOrDefault(offset: ZoneOffset?, instant: Instant, zone: ZoneId): Int =
            offset?.totalSeconds ?: zone.rules.getOffset(instant).totalSeconds

        fun daysAgo(nowMs: Long, days: Long): Long = Instant.ofEpochMilli(nowMs).minus(days, ChronoUnit.DAYS).toEpochMilli()
    }
}
