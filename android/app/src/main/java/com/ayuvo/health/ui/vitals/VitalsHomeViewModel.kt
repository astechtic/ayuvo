package com.ayuvo.health.ui.vitals

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.ayuvo.health.AppContainer
import com.ayuvo.health.data.health.HealthChartPoint
import com.ayuvo.health.vitals.camera.VitalsMode
import com.ayuvo.health.vitals.engine.VitalsConfig
import com.ayuvo.health.vitals.engine.VitalsEngine
import com.ayuvo.health.vitals.storage.VitalScanRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.time.LocalDate
import java.time.ZoneId

/** One history row: mode, time, HR and quality grade. */
data class VitalScanRowUi(
    val id: String,
    val mode: VitalsMode,
    val startMs: Long,
    val day: LocalDate,
    val hr: Double?,
    val grade: String,
    val rejected: Boolean
)

/** Baselines of one metric for one mode: window key ("7d" | "14d" | "30d" | "all") -> median (null below min_n). */
data class VitalBaselineUi(val mode: VitalsMode, val hr: Map<String, Double?>, val rmssd: Map<String, Double?>)

data class VitalsHomeUi(
    val loading: Boolean = true,
    val latest: Map<VitalsMode, VitalScanRowUi> = emptyMap(),
    /** Daily average HR of valid scans per mode, over the last [TREND_DAYS] days (one point per day). */
    val trend: Map<VitalsMode, List<HealthChartPoint>> = emptyMap(),
    val baselines: List<VitalBaselineUi> = emptyList(),
    val history: List<Pair<LocalDate, List<VitalScanRowUi>>> = emptyList(),
    /** §7.1: the Calibration link shows only while experimental or research estimates are on. */
    val calibrationVisible: Boolean = false
) {
    companion object {
        const val TREND_DAYS = 30
    }
}

/** Vitals home (§7.1): latest scan per mode, HR trend per mode, baselines and the history grouped by day. */
class VitalsHomeViewModel(private val container: AppContainer) : ViewModel() {
    val cfg: VitalsConfig = container.vitalsConfig
    private val _ui = MutableStateFlow(VitalsHomeUi())
    val ui: StateFlow<VitalsHomeUi> = _ui

    init {
        viewModelScope.launch { container.vitalScans.revision.collect { reload() } }
        viewModelScope.launch {
            combine(container.prefs.vitalsExperimentalEnabled, container.prefs.vitalsResearchEnabled) { e, r -> e || r }
                .collect { visible -> calibrationVisible = visible; _ui.update { it.copy(calibrationVisible = visible) } }
        }
    }

    @Volatile
    private var calibrationVisible = false

    fun reload() {
        viewModelScope.launch {
            val scans = runCatching { container.vitalScans.scans(null, 0L, Long.MAX_VALUE) }.getOrDefault(emptyList())
            _ui.value = withContext(Dispatchers.Default) { build(scans, cfg, System.currentTimeMillis(), ZoneId.systemDefault()) }
                .copy(calibrationVisible = calibrationVisible)
        }
    }

    class Factory(private val container: AppContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = VitalsHomeViewModel(container) as T
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        private fun metricValue(record: VitalScanRecord, id: String): Double? {
            val results = runCatching { json.parseToJsonElement(record.resultsJson) as JsonObject }.getOrNull() ?: return null
            val env = (results["metrics"] as? JsonObject)?.get(id) as? JsonObject ?: return null
            if (VitalResultUi.str(env["status"]) != "valid") return null
            return VitalResultUi.num(env["value"])
        }

        private fun grade(record: VitalScanRecord, cfg: VitalsConfig): String {
            val q = runCatching { json.parseToJsonElement(record.qualityJson) as JsonObject }.getOrNull()
            return VitalResultUi.str(q?.get("grade")) ?: VitalsEngine.grade(record.qualityScore ?: 0.0, cfg)
        }

        fun row(record: VitalScanRecord, cfg: VitalsConfig, zone: ZoneId): VitalScanRowUi = VitalScanRowUi(
            id = record.id,
            mode = VitalsMode.fromId(record.mode) ?: VitalsMode.FINGER,
            startMs = record.startMs,
            day = runCatching { LocalDate.parse(record.localDay) }.getOrElse { java.time.Instant.ofEpochMilli(record.startMs).atZone(zone).toLocalDate() },
            hr = metricValue(record, "heart_rate"),
            grade = grade(record, cfg),
            rejected = record.rejectReason != null
        )

        fun build(scans: List<VitalScanRecord>, cfg: VitalsConfig, nowMs: Long, zone: ZoneId): VitalsHomeUi {
            val live = scans.filter { !it.deleted }.sortedByDescending { it.startMs }
            val rows = live.map { row(it, cfg, zone) }
            val latest = LinkedHashMap<VitalsMode, VitalScanRowUi>()
            for (r in rows) if (r.mode !in latest) latest[r.mode] = r
            val today = java.time.Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
            val trend = LinkedHashMap<VitalsMode, List<HealthChartPoint>>()
            val baselines = ArrayList<VitalBaselineUi>()
            for (mode in VitalsMode.entries) {
                val valid = live.filter { it.mode == mode.storage && it.rejectReason == null }
                val hrs = valid.mapNotNull { r -> metricValue(r, "heart_rate")?.let { VitalsEngine.TimedValue(r.startMs.toDouble(), it) } }
                val rmssd = valid.mapNotNull { r -> metricValue(r, "hrv_rmssd")?.let { VitalsEngine.TimedValue(r.startMs.toDouble(), it) } }
                if (hrs.isNotEmpty()) {
                    val byDay = rows.filter { it.mode == mode && !it.rejected && it.hr != null }.groupBy { it.day }
                    trend[mode] = (VitalsHomeUi.TREND_DAYS - 1 downTo 0).map { back ->
                        val day = today.minusDays(back.toLong())
                        val start = day.atStartOfDay(zone).toInstant().toEpochMilli()
                        val end = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
                        val values = byDay[day].orEmpty().mapNotNull { it.hr }
                        if (values.isEmpty()) HealthChartPoint(start, end)
                        else HealthChartPoint(start, end, sum = values.sum(), avg = values.average(), min = values.min(), max = values.max(), count = values.size)
                    }
                }
                if (valid.isNotEmpty()) {
                    baselines += VitalBaselineUi(mode, baselineMap(hrs, nowMs, cfg), baselineMap(rmssd, nowMs, cfg))
                }
            }
            val history = rows.groupBy { it.day }.toList().sortedByDescending { it.first }
            return VitalsHomeUi(loading = false, latest = latest, trend = trend, baselines = baselines, history = history)
        }

        private fun baselineMap(values: List<VitalsEngine.TimedValue>, nowMs: Long, cfg: VitalsConfig): Map<String, Double?> {
            val b = VitalsEngine.baselines(values, nowMs.toDouble(), cfg.baseline.windowsDays, cfg)
            return b.mapValues { (_, v) -> VitalResultUi.num((v as? JsonObject)?.get("median")) }
        }
    }
}
