package com.ayuvo.health.partner

import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ayuvo.health.data.health.HealthDatabase
import com.ayuvo.health.data.health.SleepNight
import com.ayuvo.health.medications.data.MedicationsDatabase
import com.ayuvo.health.partner.logic.PartnerCatalog
import com.ayuvo.health.partner.logic.PartnerEnvelopes
import com.ayuvo.health.partner.logic.PartnerJson
import com.ayuvo.health.partner.sources.AnalyticsDaySource
import com.ayuvo.health.partner.sources.DerivedDaySource
import com.ayuvo.health.partner.sources.DoseLogSource
import com.ayuvo.health.partner.sources.MedicationScheduleSource
import com.ayuvo.health.partner.sources.MedicationSource
import com.ayuvo.health.partner.sources.MetricDaySource
import com.ayuvo.health.partner.sources.MetricHourSource
import com.ayuvo.health.partner.sources.PartnerSource
import com.ayuvo.health.partner.sources.ReportOverviewSource
import com.ayuvo.health.partner.sources.SampleSource
import com.ayuvo.health.partner.sources.SleepNightSource
import com.ayuvo.health.partner.sources.SourceRecord
import com.ayuvo.health.partner.sources.WorkoutSource
import com.ayuvo.health.records.data.RecordsDatabase
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.ZoneId

/**
 * The SQLite-backed Partner sources (docs/partner-sync.md §7) against throwaway copies of the real schemas: they
 * read named columns only, honour the allow-lists, page, and never emit a forbidden key.
 */
@RunWith(AndroidJUnit4::class)
class PartnerSourcesTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var health: SQLiteDatabase
    private lateinit var meds: MedicationsDatabase
    private lateinit var records: RecordsDatabase
    private val zone = ZoneId.of("UTC")
    private val now = System.currentTimeMillis()
    private val today = LocalDate.now(zone).toString()

    @Before
    fun setUp() {
        PartnerCatalog.install(context)
        health = SQLiteDatabase.create(null)
        HealthDatabase.SCHEMA_STATEMENTS.forEach(health::execSQL)
        context.deleteDatabase(MEDS); context.deleteDatabase(RECS)
        meds = MedicationsDatabase(context, MEDS)
        records = RecordsDatabase(context, RECS)
    }

    @After
    fun tearDown() {
        health.close(); meds.close(); records.close()
        context.deleteDatabase(MEDS); context.deleteDatabase(RECS)
    }

    private fun keys(e: JsonElement): Set<String> = when (e) {
        is JsonObject -> e.keys + e.values.flatMap(::keys)
        is JsonArray -> e.flatMap(::keys).toSet()
        else -> emptySet()
    }

    private suspend fun all(s: PartnerSource, dayFrom: String? = null): List<SourceRecord> {
        val out = mutableListOf<SourceRecord>()
        s.forEach(dayFrom) { out += it }
        for (r in out) {
            val env = r.envelope(1, now)
            val check = PartnerEnvelopes.validate(env, now)
            assertTrue("${r.type} ${r.recordId}: $check $env", check.ok)
            assertTrue("${r.type} leaks", keys(env).intersect(PartnerCatalog.current.forbiddenKeys).isEmpty())
        }
        // render() reproduces what forEach() produced.
        val rendered = s.render(out.map { it.recordId })
        for (r in out) assertEquals(r, rendered[r.recordId])
        return out
    }

    private fun sample(id: String, type: String, start: Long, value: Double?, unit: String, cat: Int? = null, deleted: Int = 0) {
        health.execSQL(
            "INSERT INTO health_samples(id, type_id, start_ms, end_ms, local_day, value, unit, category_value, source_id, updated_ms, deleted, value_text, extra_json) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'com.fitbit', ?, ?, 'secret text', '{\"notes\":\"x\"}')",
            arrayOf<Any?>(id, type, start, start + 60_000, java.time.Instant.ofEpochMilli(start).atZone(zone).toLocalDate().toString(), value, unit, cat, now, deleted)
        )
    }

    @Test
    fun healthSources() = runBlocking {
        health.execSQL("INSERT INTO health_type_meta VALUES ('steps','activity','cumulative','sum','count',NULL,NULL,NULL)")
        health.execSQL("INSERT INTO health_sources(id, name) VALUES ('com.fitbit', 'Fitbit')")
        for (i in 0 until 1500) {
            val day = LocalDate.parse("2020-01-01").plusDays(i.toLong()).toString()
            health.execSQL("INSERT INTO health_daily_rollups(type_id, day, tz, sum, count) VALUES ('steps', ?, 'UTC', ?, 10)", arrayOf<Any>(day, 1000 + i))
        }
        health.execSQL("INSERT INTO health_daily_rollups(type_id, day, tz, avg, count) VALUES ('menstrual_flow', ?, 'UTC', 2, 1)", arrayOf(today))
        health.execSQL("INSERT INTO health_daily_rollups(type_id, day, tz, count) VALUES ('resting_heart_rate', ?, 'UTC', 0)", arrayOf(today))
        val days = all(MetricDaySource { health })
        assertEquals("paged through every shareable rollup, cycle types excluded, empty rows skipped", 1500, days.size)
        assertEquals("count", PartnerJson.str(days[0].data["unit"]))
        assertEquals(30, all(MetricDaySource { health }, LocalDate.parse("2020-01-01").plusDays(1470).toString()).size)

        health.execSQL("INSERT INTO health_hourly_rollups(type_id, day, hour, sum, count) VALUES ('steps', ?, 9, 400, 3)", arrayOf(today))
        health.execSQL("INSERT INTO health_hourly_rollups(type_id, day, hour, sum, count) VALUES ('floors_climbed', ?, 9, 4, 1)", arrayOf(today))
        val hours = all(MetricHourSource({ health }) { zone })
        assertEquals(listOf("steps:$today:9"), hours.map { it.recordId })

        sample("hr-1", "heart_rate", now - 3_600_000, 71.0, "bpm")
        sample("hr-old", "heart_rate", now - 9 * 86_400_000L, 60.0, "bpm")
        sample("hr-del", "heart_rate", now - 3_600_000, 99.0, "bpm", deleted = 1)
        sample("steps-raw", "steps", now - 3_600_000, 99.0, "count")
        sample("sl-1", "sleep", now - 7_200_000, null, "s", cat = 4)
        val samples = all(SampleSource({ health }) { now })
        assertEquals(setOf("hr-1", "sl-1"), samples.map { it.recordId }.toSet())
        assertEquals("Fitbit", PartnerJson.str(samples.first { it.recordId == "hr-1" }.data["source_name"]))

        health.execSQL("INSERT INTO derived_daily_values(metric_id, day, value, algo_version, computed_ms) VALUES ('resting_hr_derived', ?, 54, 1, 0)", arrayOf(today))
        health.execSQL("INSERT INTO derived_daily_values(metric_id, day, value, algo_version, computed_ms) VALUES ('walking_speed_weekly', ?, 1.2, 1, 0)", arrayOf(today))
        val derived = all(DerivedDaySource({ health }) { it != "walking_speed_weekly" })
        assertEquals(listOf("resting_hr_derived:$today"), derived.map { it.recordId })

        for ((v, value) in listOf(1 to 60.0, 2 to 72.0)) {
            health.execSQL(
                "INSERT INTO analytics_results(metric_id, period_start, period_end, algorithm_id, algorithm_version, config_version, status, " +
                    "classification, value, unit, result_json, provenance_json, input_hash, computed_ms) VALUES ('recovery_indicator', ?, ?, 'a', ?, 1, 'ok', 'good', ?, 'score', '{}', '{}', 'h', 0)",
                arrayOf<Any>(today, today, v, value)
            )
        }
        health.execSQL(
            "INSERT INTO analytics_results(metric_id, period_start, period_end, algorithm_id, algorithm_version, config_version, status, " +
                "classification, result_json, provenance_json, input_hash, computed_ms) VALUES ('cycle_phase', ?, ?, 'a', 1, 1, 'ok', 'x', '{}', '{}', 'h', 0)",
            arrayOf<Any>(today, today)
        )
        val analytics = all(AnalyticsDaySource { health })
        assertEquals(1, analytics.size)
        assertEquals("newest algorithm version wins", 72.0, PartnerJson.double(analytics[0].data["value"])!!, 0.0)

        // Workouts: an external session, Ayuvo's own write-back, and a Google Health copy of the external one.
        fun workout(id: String, source: String, client: String?, origin: Int, start: Long) = health.execSQL(
            "INSERT INTO health_samples(id, type_id, start_ms, end_ms, local_day, value, unit, category_value, title, source_id, client_record_id, origin, updated_ms) " +
                "VALUES (?, 'workout', ?, ?, ?, 1800, 's', 56, 'Run', ?, ?, ?, ?)",
            arrayOf<Any?>(id, start, start + 1_800_000, today, source, client, origin, now)
        )
        workout("w-ext", "com.fitbit", null, 0, now - 7_200_000)
        workout("w-own", "com.ayuvo.health", "ayuvo_gps_workout|$today|x", 0, now - 5_000_000)
        workout("w-gh", "google_health:com.fitbit", null, 3, now - 7_200_000 + 30_000)
        workout("w-gh2", "google_health:com.strava", null, 3, now - 3_600_000)
        val workouts = all(WorkoutSource({ emptyList() }, { health }, "com.ayuvo.health") { zone })
        assertEquals(setOf("w-ext", "w-gh2"), workouts.map { it.recordId }.toSet())
        assertEquals("running", PartnerJson.str(workouts.first().data["activity"]))

        // Sleep: averages measured inside the night only.
        sample("hrv-1", "hrv_rmssd", now - 3 * 3_600_000L, 40.0, "ms")
        val night = SleepNight(today, now - 4 * 3_600_000L, now - 1_000, 14_000.0, 13_000.0, 7_000.0, 3_000.0, 3_000.0, 1_000.0, "com.fitbit")
        val sleep = all(SleepNightSource({ _, _ -> listOf(night) }, { health }) { zone }, today)
        assertEquals(1, sleep.size)
        assertEquals(40.0, PartnerJson.double(sleep[0].data["avg_hrv"])!!, 0.0)
        assertEquals(71.0, PartnerJson.double(sleep[0].data["avg_hr"])!!, 0.0)
    }

    @Test
    fun medicationSourcesDropPhotosAndNotes() = runBlocking {
        val db = meds.writableDatabase
        db.execSQL(
            "INSERT INTO medications(id, name, form, dose_quantity, dose_unit, start_date, status, is_prn, photo_path, related_record_id, created_ms, updated_ms) " +
                "VALUES ('m1', 'Vitamin D3', 'capsule', 1, 'capsule', '2026-09-01', 'active', 0, '/data/photo.jpg', 'rec-9', 0, 0)"
        )
        db.execSQL(
            "INSERT INTO medication_schedules(id, medication_id, frequency_kind, times_json, days_json, active_from_ms, created_ms, updated_ms) " +
                "VALUES ('s1', 'm1', 'daily', '[\"08:00\"]', '[]', 0, 0, 0)"
        )
        db.execSQL(
            "INSERT INTO dose_logs(id, medication_id, schedule_id, scheduled_at_ms, status, taken_at_ms, dose_quantity, dose_unit, note, created_ms, updated_ms) " +
                "VALUES ('d1', 'm1', 's1', ?, 'taken', ?, 1, 'capsule', 'felt dizzy', 0, 0)", arrayOf(now, now)
        )
        val provider = { meds.readableDatabase }
        val m = all(MedicationSource(provider))
        assertEquals(1, m.size)
        assertFalse(m[0].data.toString().contains("photo"))
        val s = all(MedicationScheduleSource(provider))
        assertEquals(JsonArray(listOf(PartnerJson.of("08:00"))), s[0].data["times"])
        db.execSQL(
            "INSERT INTO dose_logs(id, medication_id, schedule_id, scheduled_at_ms, status, taken_at_ms, dose_quantity, dose_unit, note, created_ms, updated_ms) " +
                "VALUES ('d2', 'm1', 's1', ?, 'skipped', NULL, 1, 'capsule', ?, 0, 0)", arrayOf(now + 60_000, "x".repeat(250)) // (schedule, time) is unique
        )
        val d = all(DoseLogSource(provider) { zone }).sortedBy { it.recordId }
        // docs §7.5: the user's own dose note is shared, capped at 200 characters.
        assertEquals(PartnerJson.of("felt dizzy"), d[0].data["note"])
        assertEquals(200, PartnerJson.str(d[1].data["note"])!!.length)
        assertTrue(all(MedicationSource { null }).isEmpty())
    }

    @Test
    fun reportOverviewNeverCarriesDocumentContent() = runBlocking {
        val db = records.writableDatabase
        fun record(id: String, archived: Int) = db.execSQL(
            "INSERT INTO records(id, title, record_type, category, source, import_method, original_filename, created_ms, updated_ms, document_date, sort_date, " +
                "mime_type, file_type, file_path, thumbnail_path, checksum_sha256, archived, notes) VALUES (?, 'CBC', 'lab_report', 'blood', 'scan', 'camera', " +
                "'IMG_1.pdf', 0, 0, '2026-10-01', '2026-10-01', 'application/pdf', 'pdf', '/data/r.pdf', '/data/t.jpg', 'abc', ?, 'private notes')",
            arrayOf<Any>(id, archived)
        )
        record("rec-1", 0)
        record("rec-2", 1)
        db.execSQL(
            "INSERT INTO record_fields(id, record_id, field_key, value_text, method, confidence, state, source_page, source_bbox, evidence, created_ms, updated_ms) " +
                "VALUES ('f1', 'rec-1', 'doctor_name', 'Dr. Sharma', 'ocr', 0.9, 'confirmed', 1, '[1,2,3,4]', 'Dr. Sharma MBBS', 0, 0)"
        )
        db.execSQL(
            "INSERT INTO record_fields(id, record_id, field_key, value_text, method, confidence, state, created_ms, updated_ms) " +
                "VALUES ('f2', 'rec-1', 'patient_name', 'Jane Doe', 'ocr', 0.9, 'confirmed', 0, 0)"
        )
        db.execSQL(
            "INSERT INTO record_highlights(id, record_id, section, text, method, created_ms) VALUES ('h1', 'rec-1', 'summary', 'Mild anaemia.', 'ai', 0)"
        )
        db.execSQL(
            "INSERT INTO observations(id, record_id, raw_name, value_num, value_text, unit, ref_low, ref_high, flag, method, state, source_bbox, evidence, created_ms, updated_ms) " +
                "VALUES ('o1', 'rec-1', 'Hemoglobin', 11.2, '11.2', 'g/dL', 12, 15, 'low', 'ocr', 'confirmed', '[0,0,1,1]', 'Hb 11.2', 0, 0)"
        )
        val out = all(ReportOverviewSource { records.readableDatabase })
        assertEquals(listOf("rec-1"), out.map { it.recordId })
        val text = out[0].data.toString()
        assertEquals("Dr. Sharma", PartnerJson.str(out[0].data["doctor"]))
        for (secret in listOf("Jane Doe", "private notes", "/data/", "IMG_1", "MBBS", "Hb 11.2", "abc")) assertFalse(secret, text.contains(secret))
        assertEquals(1, (out[0].data["abnormal"] as JsonArray).size)
    }

    companion object {
        const val MEDS = "partner_sources_test_meds.db"
        const val RECS = "partner_sources_test_records.db"
    }
}
