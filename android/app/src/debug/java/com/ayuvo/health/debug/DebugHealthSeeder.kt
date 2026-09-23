package com.ayuvo.health.debug

import com.ayuvo.health.AyuvoApp
import com.ayuvo.health.data.health.HealthPageCommit
import com.ayuvo.health.data.health.HealthRollupMath
import com.ayuvo.health.data.health.HealthSampleRow
import com.ayuvo.health.data.health.HealthSleepCodes
import com.ayuvo.health.data.health.HealthSourceRow
import com.ayuvo.health.data.health.HealthTypeDescriptor
import com.ayuvo.health.models.HealthDataType
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlin.math.sin

/**
 * Debug-only demo health history for chart checks on an emulator: nights of staged sleep that
 * cross midnight, daily steps and heart-rate readings. Rows are written to the local health mirror
 * as imported rows (never to Health Connect, never in release), with deterministic ids so repeated
 * launches overwrite instead of duplicating.
 */
internal class DebugHealthSeeder(private val application: AyuvoApp) {
    private val store get() = application.container.healthStore
    private val zone: ZoneId = ZoneId.systemDefault()

    suspend fun seed(days: Int = 200): Int {
        val today = LocalDate.now(zone)
        val now = System.currentTimeMillis()
        val rows = ArrayList<HealthSampleRow>()
        for (k in 0 until days) {
            val day = today.minusDays(k.toLong())
            if (k % 9 != 4) rows += sleepNight(day, k, now)
            rows += steps(day, k, now)
            rows += heartRate(day, k, now)
        }
        store.commit(
            HealthPageCommit(
                rows = rows.filter { it.endMs <= now },
                sources = listOf(HealthSourceRow(SOURCE, "Demo data", lastSeenMs = now))
            )
        )
        val dirty = rows.groupBy { it.typeId }.mapValues { (_, r) -> r.map { it.localDay }.toSet() }
        val meta = store.typeMeta().associateBy { it.typeId }
        for ((typeId, dayKeys) in dirty) {
            val descriptor = HealthTypeDescriptor.resolve(typeId, meta)
            val sorted = dayKeys.map(LocalDate::parse).sorted()
            val from = sorted.first().minusDays(2).atStartOfDay(zone).toInstant().toEpochMilli()
            val to = sorted.last().plusDays(2).atStartOfDay(zone).toInstant().toEpochMilli()
            val live = store.samplesBetween(typeId, from, to).filter { it.localDay in dayKeys }
            store.replaceDailyRollups(typeId, dayKeys, HealthRollupMath.rebuildDaily(descriptor, live, zone.id))
        }
        return rows.size
    }

    private fun ms(day: LocalDate, h: Int, m: Int): Long = day.atTime(LocalTime.of(h, m)).atZone(zone).toInstant().toEpochMilli()

    private fun row(id: String, type: HealthDataType, start: Long, end: Long, localDay: LocalDate, now: Long, value: Double? = null, code: Int? = null) =
        HealthSampleRow(
            id = id, typeId = type.id, startMs = start, endMs = end, localDay = localDay.toString(), value = value,
            unit = type.unit, categoryValue = code, sourceId = SOURCE, origin = HealthSampleRow.ORIGIN_IMPORT, updatedMs = now
        )

    /** One night waking on [wake]: bedtime 22:15–00:40, then Core/Deep/REM cycles with short wakes. */
    private fun sleepNight(wake: LocalDate, k: Int, now: Long): List<HealthSampleRow> {
        val bedMin = 22 * 60 + 15 + (k * 37) % 145                     // 22:15 .. 00:40
        val bed = ms(wake.minusDays(1), 0, 0) + bedMin * 60_000L
        val cycles = listOf(
            HealthSleepCodes.LIGHT to 20, HealthSleepCodes.DEEP to 45 + k % 15, HealthSleepCodes.LIGHT to 25, HealthSleepCodes.REM to 18,
            HealthSleepCodes.AWAKE to 4, HealthSleepCodes.LIGHT to 40, HealthSleepCodes.DEEP to 30, HealthSleepCodes.REM to 28,
            HealthSleepCodes.LIGHT to 55, HealthSleepCodes.AWAKE to 6, HealthSleepCodes.REM to 35 + k % 20, HealthSleepCodes.LIGHT to 45,
            HealthSleepCodes.DEEP to 15, HealthSleepCodes.REM to 30, HealthSleepCodes.LIGHT to 25 + (k * 7) % 40, HealthSleepCodes.AWAKE to 8
        )
        val id = "debug-sleep-$wake"
        val out = ArrayList<HealthSampleRow>()
        var t = bed + 8 * 60_000L
        cycles.forEachIndexed { i, (code, minutes) ->
            val end = t + minutes * 60_000L
            out += row("$id:$i", HealthDataType.SLEEP, t, end, wake, now, value = minutes * 60.0, code = code)
            t = end
        }
        val wakeMs = t + 5 * 60_000L
        out += row(id, HealthDataType.SLEEP, bed, wakeMs, wake, now, value = (wakeMs - bed) / 1000.0, code = HealthSleepCodes.IN_BED)
        return out
    }

    private fun steps(day: LocalDate, k: Int, now: Long): List<HealthSampleRow> {
        val base = 7200 + 2600 * sin(k / 3.0) + (k * 131) % 2400
        return listOf(8 to 0.35, 13 to 0.25, 18 to 0.4).map { (h, share) ->
            row("debug-steps-$day-$h", HealthDataType.STEPS, ms(day, h, 0), ms(day, h, 55), day, now, value = (base * share).toInt().toDouble())
        }
    }

    private fun heartRate(day: LocalDate, k: Int, now: Long): List<HealthSampleRow> =
        listOf(7, 10, 13, 16, 19, 22).mapIndexed { i, h ->
            val bpm = 62.0 + (i * 11 + k * 3) % 38
            row("debug-hr-$day-$h", HealthDataType.HEART_RATE, ms(day, h, 5), ms(day, h, 5), day, now, value = bpm)
        }

    private companion object {
        const val SOURCE = "com.ayuvo.health.debug.demo"
    }
}
