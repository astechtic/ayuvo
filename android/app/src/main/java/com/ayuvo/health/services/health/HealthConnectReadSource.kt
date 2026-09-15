@file:OptIn(ExperimentalMindfulnessSessionApi::class)

package com.ayuvo.health.services.health

import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.aggregate.AggregateMetric
import androidx.health.connect.client.changes.DeletionChange
import androidx.health.connect.client.changes.UpsertionChange
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
import androidx.health.connect.client.records.metadata.DataOrigin
import androidx.health.connect.client.request.AggregateGroupByDurationRequest
import androidx.health.connect.client.request.AggregateGroupByPeriodRequest
import androidx.health.connect.client.request.ChangesTokenRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import com.ayuvo.health.models.HealthDataType
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.Period
import java.time.ZoneId
import kotlin.reflect.KClass

/**
 * The only hub file that talks to [HealthConnectClient]. Every call is wrapped so the
 * engine only ever sees [HealthQuotaExceededException], [HealthReadBoundaryException]
 * or [HealthReadFailedException]; records are mapped to mirror rows on the way out.
 */
class HealthConnectReadSource(
    private val client: () -> HealthConnectClient?,
    private val ownPackage: String,
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
    private val mapper: HealthRecordMapper = HealthRecordMapper(zone),
    private val nowMs: () -> Long = { System.currentTimeMillis() }
) : HealthReadSource {

    override suspend fun changesToken(type: HealthDataType): String? {
        val c = client() ?: return null
        val cls = recordClassFor(type) ?: return null
        return guard { c.getChangesToken(ChangesTokenRequest(recordTypes = setOf(cls))) }.getOrNull()
    }

    override suspend fun changes(token: String): HealthChangesPage {
        val c = client() ?: throw HealthReadFailedException(IllegalStateException("Health Connect unavailable"))
        val response = guard { c.getChanges(token) }.getOrThrow()
        if (response.changesTokenExpired) {
            return HealthChangesPage(emptyList(), emptyList(), null, hasMore = false, expired = true)
        }
        val now = nowMs()
        val upserts = mutableListOf<HealthMapped>()
        val deleted = mutableListOf<String>()
        for (change in response.changes) {
            when (change) {
                is UpsertionChange -> mapper.map(change.record, now)?.let(upserts::add)
                is DeletionChange -> deleted += change.recordId
            }
        }
        return HealthChangesPage(upserts, deleted, response.nextChangesToken, response.hasMore, expired = false)
    }

    override suspend fun readPage(
        type: HealthDataType,
        fromMs: Long,
        toMs: Long,
        pageToken: String?,
        pageSize: Int,
        ascending: Boolean
    ): HealthReadPage {
        val c = client() ?: throw HealthReadFailedException(IllegalStateException("Health Connect unavailable"))
        @Suppress("UNCHECKED_CAST")
        val cls = (recordClassFor(type) ?: return HealthReadPage(emptyList(), null)) as KClass<Record>
        val response = guard {
            c.readRecords(
                ReadRecordsRequest(
                    recordType = cls,
                    timeRangeFilter = TimeRangeFilter.between(Instant.ofEpochMilli(fromMs), Instant.ofEpochMilli(toMs)),
                    ascendingOrder = ascending,
                    pageSize = pageSize,
                    pageToken = pageToken
                )
            )
        }.getOrThrow()
        val now = nowMs()
        val mapped = response.records.mapNotNull { mapper.map(it, now) }
        return HealthReadPage(mapped, response.pageToken?.takeIf { it.isNotEmpty() })
    }

    override suspend fun earliestRecordTime(type: HealthDataType, fromMs: Long, toMs: Long): HealthProbe =
        probe(type, fromMs, toMs, ascending = true)

    override suspend fun latestRecordTime(type: HealthDataType, fromMs: Long, toMs: Long): HealthProbe =
        probe(type, fromMs, toMs, ascending = false)

    private suspend fun probe(type: HealthDataType, fromMs: Long, toMs: Long, ascending: Boolean): HealthProbe {
        val c = client() ?: return HealthProbe.Failed
        @Suppress("UNCHECKED_CAST")
        val cls = (recordClassFor(type) ?: return HealthProbe.NoData) as KClass<Record>
        if (toMs <= fromMs) return HealthProbe.NoData
        val response = guard {
            c.readRecords(
                ReadRecordsRequest(
                    recordType = cls,
                    timeRangeFilter = TimeRangeFilter.between(Instant.ofEpochMilli(fromMs), Instant.ofEpochMilli(toMs)),
                    ascendingOrder = ascending,
                    pageSize = 1
                )
            )
        }.getOrElse { error ->
            if (error is HealthReadBoundaryException) return HealthProbe.NoData
            if (error is HealthQuotaExceededException) throw error
            return HealthProbe.Failed
        }
        val record = response.records.firstOrNull() ?: return HealthProbe.NoData
        val mapped = mapper.map(record, nowMs()) ?: return HealthProbe.NoData
        return HealthProbe.Found(mapped.row.startMs)
    }

    override suspend fun aggregateDaily(type: HealthDataType, fromDay: LocalDate, toDay: LocalDate, ownOriginOnly: Boolean): Map<LocalDate, Double>? {
        val c = client() ?: return null
        val metric = aggregateMetricFor(type) ?: return null
        val result = guard {
            c.aggregateGroupByPeriod(
                AggregateGroupByPeriodRequest(
                    metrics = setOf(metric.metric),
                    timeRangeFilter = TimeRangeFilter.between(fromDay.atStartOfDay(), toDay.plusDays(1).atStartOfDay()),
                    timeRangeSlicer = Period.ofDays(1),
                    dataOriginFilter = if (ownOriginOnly) setOf(DataOrigin(ownPackage)) else emptySet()
                )
            )
        }.getOrElse { error ->
            if (error is HealthQuotaExceededException) throw error
            return null
        }
        val out = LinkedHashMap<LocalDate, Double>()
        for (group in result) {
            val value = metric.read(group.result) ?: continue
            out[group.startTime.toLocalDate()] = value
        }
        return out
    }

    override suspend fun aggregateHourly(type: HealthDataType, day: LocalDate): Map<Int, Double>? {
        val c = client() ?: return null
        val metric = aggregateMetricFor(type) ?: return null
        val z = zone()
        val start = day.atStartOfDay(z).toInstant()
        val end = day.plusDays(1).atStartOfDay(z).toInstant()
        val result = guard {
            c.aggregateGroupByDuration(
                AggregateGroupByDurationRequest(
                    metrics = setOf(metric.metric),
                    timeRangeFilter = TimeRangeFilter.between(start, end),
                    timeRangeSlicer = Duration.ofHours(1)
                )
            )
        }.getOrElse { error ->
            if (error is HealthQuotaExceededException) throw error
            return null
        }
        val out = LinkedHashMap<Int, Double>()
        for (group in result) {
            val value = metric.read(group.result) ?: continue
            out[group.startTime.atZone(z).hour] = value
        }
        return out
    }

    /** Runs [block], classifying failures for the engine. */
    private inline fun <T> guard(block: () -> T): Result<T> = try {
        Result.success(block())
    } catch (cancel: kotlinx.coroutines.CancellationException) {
        throw cancel
    } catch (t: Throwable) {
        Result.failure(classify(t))
    }

    private class MetricReader(
        val metric: AggregateMetric<*>,
        val read: (androidx.health.connect.client.aggregate.AggregationResult) -> Double?
    )

    private fun aggregateMetricFor(type: HealthDataType): MetricReader? = when (type) {
        HealthDataType.STEPS -> MetricReader(StepsRecord.COUNT_TOTAL) { it[StepsRecord.COUNT_TOTAL]?.toDouble() }
        HealthDataType.DISTANCE -> MetricReader(DistanceRecord.DISTANCE_TOTAL) { it[DistanceRecord.DISTANCE_TOTAL]?.inMeters }
        HealthDataType.ACTIVE_ENERGY -> MetricReader(ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL) { it[ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL]?.inKilocalories }
        HealthDataType.TOTAL_ENERGY -> MetricReader(TotalCaloriesBurnedRecord.ENERGY_TOTAL) { it[TotalCaloriesBurnedRecord.ENERGY_TOTAL]?.inKilocalories }
        HealthDataType.FLOORS_CLIMBED -> MetricReader(FloorsClimbedRecord.FLOORS_CLIMBED_TOTAL) { it[FloorsClimbedRecord.FLOORS_CLIMBED_TOTAL] }
        HealthDataType.ELEVATION_GAINED -> MetricReader(ElevationGainedRecord.ELEVATION_GAINED_TOTAL) { it[ElevationGainedRecord.ELEVATION_GAINED_TOTAL]?.inMeters }
        HealthDataType.HYDRATION -> MetricReader(HydrationRecord.VOLUME_TOTAL) { it[HydrationRecord.VOLUME_TOTAL]?.inMilliliters }
        HealthDataType.WHEELCHAIR_PUSHES -> MetricReader(WheelchairPushesRecord.COUNT_TOTAL) { it[WheelchairPushesRecord.COUNT_TOTAL]?.toDouble() }
        HealthDataType.WORKOUT -> MetricReader(ExerciseSessionRecord.EXERCISE_DURATION_TOTAL) { it[ExerciseSessionRecord.EXERCISE_DURATION_TOTAL]?.seconds?.toDouble() }
        else -> null
    }

    companion object {
        fun classify(t: Throwable): Throwable = when {
            t is HealthQuotaExceededException || t is HealthReadBoundaryException || t is HealthReadFailedException -> t
            HealthHubPermissions.isQuotaError(t) -> HealthQuotaExceededException(t)
            HealthHubPermissions.isBoundaryError(t) -> HealthReadBoundaryException(t)
            else -> HealthReadFailedException(t)
        }

        /** Registry type → connect-client record class (the 41 readable records). */
        fun recordClassFor(type: HealthDataType): KClass<out Record>? = when (type.hcRecord) {
            "StepsRecord" -> StepsRecord::class
            "DistanceRecord" -> DistanceRecord::class
            "WheelchairPushesRecord" -> WheelchairPushesRecord::class
            "FloorsClimbedRecord" -> FloorsClimbedRecord::class
            "ElevationGainedRecord" -> ElevationGainedRecord::class
            "ActiveCaloriesBurnedRecord" -> ActiveCaloriesBurnedRecord::class
            "TotalCaloriesBurnedRecord" -> TotalCaloriesBurnedRecord::class
            "ExerciseSessionRecord" -> ExerciseSessionRecord::class
            "PlannedExerciseSessionRecord" -> PlannedExerciseSessionRecord::class
            "SpeedRecord" -> SpeedRecord::class
            "PowerRecord" -> PowerRecord::class
            "CyclingPedalingCadenceRecord" -> CyclingPedalingCadenceRecord::class
            "StepsCadenceRecord" -> StepsCadenceRecord::class
            "WeightRecord" -> WeightRecord::class
            "HeightRecord" -> HeightRecord::class
            "BodyFatRecord" -> BodyFatRecord::class
            "LeanBodyMassRecord" -> LeanBodyMassRecord::class
            "BoneMassRecord" -> BoneMassRecord::class
            "BodyWaterMassRecord" -> BodyWaterMassRecord::class
            "BasalMetabolicRateRecord" -> BasalMetabolicRateRecord::class
            "HeartRateRecord" -> HeartRateRecord::class
            "RestingHeartRateRecord" -> RestingHeartRateRecord::class
            "HeartRateVariabilityRmssdRecord" -> HeartRateVariabilityRmssdRecord::class
            "BloodPressureRecord" -> BloodPressureRecord::class
            "Vo2MaxRecord" -> Vo2MaxRecord::class
            "SleepSessionRecord" -> SleepSessionRecord::class
            "BloodGlucoseRecord" -> BloodGlucoseRecord::class
            "BodyTemperatureRecord" -> BodyTemperatureRecord::class
            "SkinTemperatureRecord" -> SkinTemperatureRecord::class
            "RespiratoryRateRecord" -> RespiratoryRateRecord::class
            "OxygenSaturationRecord" -> OxygenSaturationRecord::class
            "HydrationRecord" -> HydrationRecord::class
            "NutritionRecord" -> NutritionRecord::class
            "MenstruationFlowRecord" -> MenstruationFlowRecord::class
            "MenstruationPeriodRecord" -> MenstruationPeriodRecord::class
            "IntermenstrualBleedingRecord" -> IntermenstrualBleedingRecord::class
            "OvulationTestRecord" -> OvulationTestRecord::class
            "CervicalMucusRecord" -> CervicalMucusRecord::class
            "SexualActivityRecord" -> SexualActivityRecord::class
            "BasalBodyTemperatureRecord" -> BasalBodyTemperatureRecord::class
            "MindfulnessSessionRecord" -> MindfulnessSessionRecord::class
            else -> null
        }
    }
}
