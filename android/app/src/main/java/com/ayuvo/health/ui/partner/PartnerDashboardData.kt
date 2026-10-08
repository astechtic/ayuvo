package com.ayuvo.health.ui.partner

import com.ayuvo.health.partner.data.Partner
import com.ayuvo.health.partner.data.PartnerStore
import com.ayuvo.health.partner.data.PartnerSyncState
import com.ayuvo.health.partner.logic.PartnerJson
import com.ayuvo.health.partner.logic.ReceivedGrant
import com.ayuvo.health.partner.logic.StoredRecord
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.LocalDate

/** The partner's latest Recovery indicator (analytics_day recovery_indicator). */
data class PartnerRecovery(val value: Double, val classification: String?, val day: String)

/** One night (sleep_night); stage minutes only when the sender had them. */
data class PartnerSleep(
    val day: String,
    val asleepMin: Double,
    val inBedMin: Double?,
    val deepMin: Double?,
    val remMin: Double?,
    val lightMin: Double?,
    val awakeMin: Double?
) {
    val hasStages: Boolean get() = listOf(deepMin, remMin, lightMin, awakeMin).any { it != null && it > 0 }
}

enum class PartnerVital { RESTING_HR, HEART_RATE, HRV, SPO2, BLOOD_PRESSURE, WEIGHT }

/** A latest reading; [value2] is the diastolic of a blood pressure. [tsMs] for samples, [day] otherwise. */
data class PartnerVitalReading(val vital: PartnerVital, val value: Double, val value2: Double?, val day: String?, val tsMs: Long?)

data class PartnerFood(val name: String, val meal: String?, val calories: Double, val loggedMs: Long?)
data class PartnerMacros(val calories: Double, val proteinG: Double, val carbsG: Double, val fatG: Double)
data class PartnerWorkout(val title: String, val activity: String, val startMs: Long?, val durationS: Double, val kcal: Double?, val distanceM: Double?)
data class PartnerDose(val medicationName: String?, val status: String, val scheduledMs: Long?, val takenMs: Long?, val quantity: Double?, val unit: String?, val note: String?)

enum class PartnerTrend { HEART_RATE, RESTING_HR, WEIGHT, SLEEP, STEPS, ACTIVITY }

/** One trend: daily values oldest → newest over the last 30 days (null = no row that day; never filled in). */
data class PartnerTrendSeries(val trend: PartnerTrend, val days: List<String>, val values: List<Double?>) {
    fun window(n: Int): List<Double?> = values.takeLast(n)
    fun latest(n: Int): Double? = window(n).lastOrNull { it != null }
    fun average(n: Int): Double? = window(n).filterNotNull().takeIf { it.isNotEmpty() }?.average()
    fun hasData(n: Int): Boolean = window(n).count { it != null } >= 1
}

data class PartnerResult(
    val name: String,
    val value: String?,
    val unit: String?,
    val refLow: Double?,
    val refHigh: Double?,
    val refText: String?,
    val flag: String?,
    val observedDate: String?
) {
    val abnormal: Boolean get() = flag in ABNORMAL_FLAGS

    companion object {
        val ABNORMAL_FLAGS = setOf("low", "high", "critical_low", "critical_high", "abnormal", "critical")
    }
}

data class PartnerMedicationLine(val name: String, val strength: String?, val dose: String?, val frequency: String?, val duration: String?)

/** A report_overview: the structured overview only, never the document (docs §7.6). */
data class PartnerReport(
    val id: String,
    val title: String,
    val recordType: String?,
    val category: String?,
    val reportDate: String?,
    val doctor: String?,
    val specialty: String?,
    val facility: String?,
    val summary: String?,
    val highlights: List<String>,
    val results: List<PartnerResult>,
    val abnormal: List<PartnerResult>,
    val diagnoses: List<String>,
    val medications: List<PartnerMedicationLine>,
    val recommendations: List<String>
)

data class PartnerDashboard(
    val partner: Partner,
    val sync: PartnerSyncState?,
    val grants: List<ReceivedGrant>,
    val today: String,
    val recovery: PartnerRecovery?,
    val sleep: PartnerSleep?,
    val vitals: List<PartnerVitalReading>,
    val foods: List<PartnerFood>,
    val macros: PartnerMacros?,
    val waterMl: Double?,
    val workouts: List<PartnerWorkout>,
    val doses: List<PartnerDose>,
    val trends: List<PartnerTrendSeries>,
    val reports: List<PartnerReport>
) {
    fun share(category: PartnerCategory): ReceivedShare = receivedShare(grants, category.id)
}

/**
 * Builds the partner dashboard from `partner_records` only (docs/partner-sync.md §6): every value comes from a
 * stored row, a missing metric is left out and nothing is estimated or filled in. Pure apart from [load]'s queries.
 */
object PartnerDashboardBuilder {
    private const val TREND_DAYS = 30L

    private fun str(o: JsonObject, k: String): String? = PartnerJson.str(o[k])?.takeIf { it.isNotBlank() }
    private fun num(o: JsonObject, k: String): Double? = if (PartnerJson.isNum(o[k])) PartnerJson.double(o[k]) else null
    private fun long(o: JsonObject, k: String): Long? = num(o, k)?.toLong()
    private fun strings(e: JsonElement?): List<String> = (e as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.let(PartnerJson::str)?.takeIf(String::isNotBlank) }.orEmpty()

    fun days(today: LocalDate, n: Long): List<String> = (n - 1 downTo 0).map { today.minusDays(it).toString() }

    /** Reads what the dashboard needs, off the main thread (the caller runs it on Dispatchers.IO). */
    fun load(store: PartnerStore, ownerId: String, today: LocalDate): PartnerDashboard? {
        val partner = store.partner(ownerId) ?: return null
        val window = days(today, TREND_DAYS)
        val t = today.toString()
        val y = today.minusDays(1).toString()
        val rows = buildList {
            addAll(store.records(ownerId, listOf("metric_day", "analytics_day", "sleep_night", "water_day"), window, limit = 6000))
            addAll(store.records(ownerId, listOf("sample"), listOf(t, y), limit = 6000))
            addAll(store.records(ownerId, listOf("food_entry", "workout", "dose_log"), listOf(t), limit = 2000))
            addAll(store.records(ownerId, listOf("weight"), null, limit = 400))
            addAll(store.records(ownerId, listOf("medication"), null, limit = 500))
            addAll(store.records(ownerId, listOf("report_overview"), null, limit = 300))
        }
        return build(partner, store.syncState(ownerId), store.grantsReceived(ownerId), rows, today)
    }

    fun build(partner: Partner, sync: PartnerSyncState?, grants: List<ReceivedGrant>, rows: List<StoredRecord>, today: LocalDate): PartnerDashboard {
        val t = today.toString()
        val byType = rows.groupBy { it.type }
        fun of(type: String) = byType[type].orEmpty()
        val metricDays = of("metric_day")
        fun metric(typeIds: Set<String>) = metricDays.filter { str(it.data, "type_id") in typeIds && it.day != null }

        val recovery = of("analytics_day")
            .filter { str(it.data, "metric_id") == "recovery_indicator" && num(it.data, "value") != null && it.day != null }
            .maxByOrNull { it.day!! }
            ?.let { PartnerRecovery(num(it.data, "value")!!, str(it.data, "classification"), it.day!!) }

        val sleep = of("sleep_night").filter { it.day != null && num(it.data, "asleep_min") != null }.maxByOrNull { it.day!! }?.let {
            val d = it.data
            PartnerSleep(it.day!!, num(d, "asleep_min")!!, num(d, "in_bed_min"), num(d, "deep_min"), num(d, "rem_min"), num(d, "light_min"), num(d, "awake_min"))
        }

        val vitals = buildVitals(metricDays, of("sample"), of("weight"))

        val foods = of("food_entry").filter { it.day == t }.mapNotNull { r ->
            val name = str(r.data, "name") ?: return@mapNotNull null
            PartnerFood(name, str(r.data, "meal"), num(r.data, "calories") ?: 0.0, long(r.data, "logged_ms"))
        }.sortedBy { it.loggedMs ?: Long.MAX_VALUE }
        val todayFoodRows = of("food_entry").filter { it.day == t }
        val macros = if (todayFoodRows.isEmpty()) null else PartnerMacros(
            todayFoodRows.sumOf { num(it.data, "calories") ?: 0.0 },
            todayFoodRows.sumOf { num(it.data, "protein_g") ?: 0.0 },
            todayFoodRows.sumOf { num(it.data, "carbs_g") ?: 0.0 },
            todayFoodRows.sumOf { num(it.data, "fat_g") ?: 0.0 }
        )
        val water = of("water_day").firstOrNull { it.day == t }?.let { num(it.data, "total_ml") }

        val workouts = of("workout").filter { it.day == t }.map { r ->
            val activity = str(r.data, "activity") ?: ""
            PartnerWorkout(str(r.data, "title") ?: activity, activity, long(r.data, "start_ms"), num(r.data, "duration_s") ?: 0.0, num(r.data, "kcal"), num(r.data, "distance_m"))
        }.sortedBy { it.startMs ?: Long.MAX_VALUE }

        val medNames = of("medication").associate { it.recordId to str(it.data, "name") }
        val doses = of("dose_log").filter { it.day == t }.map { r ->
            PartnerDose(
                medNames[str(r.data, "medication_id")], str(r.data, "status") ?: "", long(r.data, "scheduled_at_ms"), long(r.data, "taken_at_ms"),
                num(r.data, "dose_quantity"), str(r.data, "dose_unit"), str(r.data, "note")
            )
        }.sortedBy { it.scheduledMs ?: Long.MAX_VALUE }

        val window = days(today, TREND_DAYS)
        fun series(trend: PartnerTrend, valueOf: (String) -> Double?) = PartnerTrendSeries(trend, window, window.map(valueOf))
        fun metricSeries(trend: PartnerTrend, typeId: String, key: String): PartnerTrendSeries {
            val byDay = metric(setOf(typeId)).associateBy({ it.day!! }, { num(it.data, key) })
            return series(trend) { byDay[it] }
        }
        val weightByDay = HashMap<String, Double>()
        metric(setOf("weight")).forEach { r -> num(r.data, "avg")?.let { weightByDay[r.day!!] = it } }
        of("weight").filter { it.day != null && it.day!! !in weightByDay }.groupBy { it.day!! }.forEach { (day, list) ->
            list.mapNotNull { num(it.data, "kg") }.takeIf { it.isNotEmpty() }?.let { weightByDay[day] = it.average() }
        }
        val sleepByDay = of("sleep_night").filter { it.day != null }.associateBy({ it.day!! }, { num(it.data, "asleep_min")?.div(60.0) })
        val trends = listOf(
            metricSeries(PartnerTrend.HEART_RATE, "heart_rate", "avg"),
            metricSeries(PartnerTrend.RESTING_HR, "resting_heart_rate", "avg"),
            series(PartnerTrend.WEIGHT) { weightByDay[it] },
            series(PartnerTrend.SLEEP) { sleepByDay[it] },
            metricSeries(PartnerTrend.STEPS, "steps", "sum"),
            metricSeries(PartnerTrend.ACTIVITY, "exercise_minutes", "sum")
        )

        val reports = of("report_overview").mapNotNull { report(it) }.sortedByDescending { it.reportDate ?: "" }

        return PartnerDashboard(partner, sync, grants, t, recovery, sleep, vitals, foods, macros, water, workouts, doses, trends, reports)
    }

    private fun buildVitals(metricDays: List<StoredRecord>, samples: List<StoredRecord>, weights: List<StoredRecord>): List<PartnerVitalReading> {
        val types = mapOf(
            PartnerVital.RESTING_HR to setOf("resting_heart_rate"),
            PartnerVital.HEART_RATE to setOf("heart_rate"),
            PartnerVital.HRV to setOf("hrv_rmssd", "daily_hrv", "hrv_sdnn"),
            PartnerVital.SPO2 to setOf("blood_oxygen", "daily_blood_oxygen"),
            PartnerVital.BLOOD_PRESSURE to setOf("blood_pressure")
        )
        val out = ArrayList<PartnerVitalReading>()
        for ((vital, ids) in types) {
            // Raw samples (last 7 days) are the most recent; otherwise the newest daily rollup.
            val sample = samples.filter { str(it.data, "type_id") in ids && num(it.data, "value") != null }
                .maxByOrNull { long(it.data, "end_ms") ?: it.tsMs ?: 0L }
            val reading = if (sample != null) {
                PartnerVitalReading(vital, spo2(vital, num(sample.data, "value")!!), num(sample.data, "value2"), sample.day, long(sample.data, "end_ms") ?: sample.tsMs)
            } else {
                metricDays.filter { str(it.data, "type_id") in ids && it.day != null && num(it.data, "avg") != null }.maxByOrNull { it.day!! }?.let {
                    PartnerVitalReading(vital, spo2(vital, num(it.data, "avg")!!), num(it.data, "v2_avg"), it.day, null)
                }
            }
            reading?.let { out += it }
        }
        // Weight: the newest of the app's weight entries and the Health weight rollups / samples.
        val candidates = buildList {
            weights.filter { num(it.data, "kg") != null }.maxByOrNull { long(it.data, "measured_ms") ?: 0L }?.let {
                add(PartnerVitalReading(PartnerVital.WEIGHT, num(it.data, "kg")!!, null, it.day, long(it.data, "measured_ms")))
            }
            samples.filter { str(it.data, "type_id") == "weight" && num(it.data, "value") != null }.maxByOrNull { long(it.data, "end_ms") ?: 0L }?.let {
                add(PartnerVitalReading(PartnerVital.WEIGHT, num(it.data, "value")!!, null, it.day, long(it.data, "end_ms")))
            }
            metricDays.filter { str(it.data, "type_id") == "weight" && it.day != null && num(it.data, "avg") != null }.maxByOrNull { it.day!! }?.let {
                add(PartnerVitalReading(PartnerVital.WEIGHT, num(it.data, "avg")!!, null, it.day, null))
            }
        }
        candidates.maxByOrNull { (it.day ?: "") + "|" + (it.tsMs ?: 0L).toString().padStart(15, '0') }?.let { out += it }
        return out
    }

    /** SpO2 may arrive as a fraction (0–1) or a percentage; it is shown as a percentage. */
    private fun spo2(vital: PartnerVital, v: Double): Double = if (vital == PartnerVital.SPO2 && v <= 1.0) v * 100.0 else v

    private fun result(o: JsonObject): PartnerResult? {
        val name = str(o, "name") ?: return null
        val value = str(o, "value") ?: num(o, "value_num")?.let { java.text.NumberFormat.getNumberInstance().apply { maximumFractionDigits = 2 }.format(it) }
        return PartnerResult(name, value, str(o, "unit"), num(o, "ref_low"), num(o, "ref_high"), str(o, "ref_text"), str(o, "flag"), str(o, "observed_date"))
    }

    fun report(r: StoredRecord): PartnerReport? {
        if (r.type != "report_overview") return null
        val d = r.data
        val title = str(d, "title") ?: return null
        val results = (d["results"] as? JsonArray)?.mapNotNull { (it as? JsonObject)?.let(::result) }.orEmpty()
        val abnormal = (d["abnormal"] as? JsonArray)?.mapNotNull { (it as? JsonObject)?.let(::result) }.orEmpty()
        val meds = (d["medications"] as? JsonArray)?.mapNotNull { e ->
            when (e) {
                is JsonObject -> str(e, "name")?.let { PartnerMedicationLine(it, str(e, "strength"), str(e, "dose"), str(e, "frequency"), str(e, "duration")) }
                is JsonPrimitive -> PartnerJson.str(e)?.takeIf(String::isNotBlank)?.let { PartnerMedicationLine(it, null, null, null, null) }
                else -> null
            }
        }.orEmpty()
        return PartnerReport(
            id = r.recordId, title = title, recordType = str(d, "record_type"), category = str(d, "category"),
            reportDate = str(d, "report_date") ?: r.day, doctor = str(d, "doctor"), specialty = str(d, "doctor_specialty"),
            facility = str(d, "facility"), summary = str(d, "summary"), highlights = strings(d["highlights"]),
            results = results, abnormal = abnormal, diagnoses = strings(d["diagnoses"]), medications = meds,
            recommendations = strings(d["recommendations"])
        )
    }
}
