package com.ayuvo.health.cycle.data

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import com.ayuvo.health.cycle.engine.CycleDayLogInput
import com.ayuvo.health.cycle.engine.CyclePeriodInput
import com.ayuvo.health.cycle.engine.CyclePeriodOp
import com.ayuvo.health.cycle.engine.CycleState
import com.ayuvo.health.data.health.HealthDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.time.ZoneId
import java.util.UUID

/**
 * App-logged periods, day logs and settings in `ayuvo_cycle.db` (docs/cycle-tracking.md §2), plus the read-only
 * Health Connect periods from the health mirror. Writes run in one transaction on `Dispatchers.IO`; deletes are
 * tombstones (`deleted = 1`, contents blanked) so the platform samples can still be removed and imports can merge.
 * [revision] bumps after each committed write. Nothing here logs values.
 */
class CycleRepository(
    private val helper: CycleDatabase,
    private val healthDatabase: () -> HealthDatabase?,
    private val ownPackage: String,
    private val now: () -> Long = { System.currentTimeMillis() },
    private val newId: () -> String = { "local:" + UUID.randomUUID().toString().lowercase() }
) {
    private val _revision = MutableStateFlow(0L)
    val revision: StateFlow<Long> = _revision

    private val db: SQLiteDatabase get() = helper.writableDatabase

    private inline fun <T> write(block: (SQLiteDatabase) -> T): T {
        val database = db
        database.beginTransactionNonExclusive()
        return try {
            val result = block(database)
            database.setTransactionSuccessful()
            result
        } finally {
            database.endTransaction()
            _revision.value = _revision.value + 1
        }
    }

    // -- Periods ----------------------------------------------------------------------------------

    suspend fun periods(includeDeleted: Boolean = false): List<CyclePeriod> = withContext(Dispatchers.IO) {
        queryPeriods(db, if (includeDeleted) null else "deleted = 0", null)
    }

    suspend fun period(id: String): CyclePeriod? = withContext(Dispatchers.IO) {
        queryPeriods(db, "id = ?", arrayOf(id)).firstOrNull()
    }

    /** Inserts a new app period; returns it. Validate with `CycleEngine.validatePeriod` first. */
    suspend fun insertPeriod(startDay: String, endDay: String?): CyclePeriod = withContext(Dispatchers.IO) {
        val t = now()
        val p = CyclePeriod(newId(), startDay, endDay, createdMs = t, updatedMs = t)
        write { it.insertOrThrow("cycle_periods", null, periodValues(p)) }
        p
    }

    /** Moves a live period; false when it is missing or deleted. Marks it for Health re-sync. */
    suspend fun updatePeriod(id: String, startDay: String, endDay: String?): Boolean = withContext(Dispatchers.IO) {
        write {
            it.update(
                "cycle_periods",
                ContentValues().apply {
                    put("start_day", startDay)
                    if (endDay == null) putNull("end_day") else put("end_day", endDay)
                    put("sync_state", CycleSyncState.PENDING)
                    put("updated_ms", now())
                },
                "id = ? AND deleted = 0", arrayOf(id)
            ) > 0
        }
    }

    /** Tombstones a period (its written Health samples are removed by the sync). */
    suspend fun deletePeriod(id: String): Boolean = withContext(Dispatchers.IO) {
        write { tombstonePeriod(it, id) }
    }

    private fun tombstonePeriod(database: SQLiteDatabase, id: String): Boolean = database.update(
        "cycle_periods",
        ContentValues().apply {
            put("deleted", 1)
            put("sync_state", CycleSyncState.PENDING)
            put("updated_ms", now())
        },
        "id = ? AND deleted = 0", arrayOf(id)
    ) > 0

    /**
     * Applies the operations of `CycleEngine.applyPeriodDay` in one transaction; returns the id of an inserted
     * period, if any.
     */
    suspend fun applyPeriodOps(ops: List<CyclePeriodOp>): String? = withContext(Dispatchers.IO) {
        write { database ->
            var inserted: String? = null
            for (op in ops) {
                when (op.op) {
                    "insert" -> {
                        val t = now()
                        val p = CyclePeriod(newId(), op.start!!, op.end, createdMs = t, updatedMs = t)
                        database.insertOrThrow("cycle_periods", null, periodValues(p))
                        inserted = p.id
                    }
                    "update" -> database.update(
                        "cycle_periods",
                        ContentValues().apply {
                            put("start_day", op.start!!)
                            if (op.end == null) putNull("end_day") else put("end_day", op.end)
                            put("sync_state", CycleSyncState.PENDING)
                            put("updated_ms", now())
                        },
                        "id = ? AND deleted = 0", arrayOf(op.id!!)
                    )
                    "delete" -> tombstonePeriod(database, op.id!!)
                    else -> error("unknown period op ${op.op}")
                }
            }
            inserted
        }
    }

    // -- Day logs ---------------------------------------------------------------------------------

    /** Live day logs, optionally limited to [fromDay, toDay] inclusive, oldest first. */
    suspend fun dayLogs(fromDay: String? = null, toDay: String? = null, includeDeleted: Boolean = false): List<CycleDayLog> =
        withContext(Dispatchers.IO) {
            val where = mutableListOf<String>()
            val args = mutableListOf<String>()
            if (!includeDeleted) where += "deleted = 0"
            if (fromDay != null) { where += "day >= ?"; args += fromDay }
            if (toDay != null) { where += "day <= ?"; args += toDay }
            queryLogs(db, where.joinToString(" AND ").ifEmpty { null }, args.toTypedArray().ifEmpty { null })
        }

    suspend fun dayLog(day: String): CycleDayLog? = withContext(Dispatchers.IO) {
        queryLogs(db, "day = ? AND deleted = 0", arrayOf(day)).firstOrNull()
    }

    /**
     * Saves the log of [log].day (insert or replace, reviving a tombstone). An empty log is a delete. The stored
     * platform ids are kept so the sync can replace what it wrote before.
     */
    suspend fun saveDayLog(log: CycleDayLog): Unit = withContext(Dispatchers.IO) {
        if (log.isEmpty) {
            write { tombstoneLog(it, log.day) }
            return@withContext
        }
        write { database ->
            val values = ContentValues().apply {
                if (log.flow == null) putNull("flow") else put("flow", log.flow)
                if (log.pain == null) putNull("pain") else put("pain", log.pain.coerceIn(0, 10))
                put("pain_locations_json", CycleJsonLists.encode(log.painLocations))
                put("symptoms_json", CycleJsonLists.encode(log.symptoms))
                put("moods_json", CycleJsonLists.encode(log.moods))
                if (log.note.isNullOrBlank()) putNull("note") else put("note", log.note)
                put("sync_state", CycleSyncState.PENDING)
                put("updated_ms", now())
                put("deleted", 0)
            }
            if (database.update("cycle_day_logs", values, "day = ?", arrayOf(log.day)) == 0) {
                values.put("day", log.day)
                values.put("platform_ids_json", "{}")
                database.insertOrThrow("cycle_day_logs", null, values)
            }
        }
    }

    suspend fun deleteDayLog(day: String): Boolean = withContext(Dispatchers.IO) { write { tombstoneLog(it, day) } }

    private fun tombstoneLog(database: SQLiteDatabase, day: String): Boolean = database.update(
        "cycle_day_logs",
        ContentValues().apply {
            putNull("flow"); putNull("pain"); putNull("note")
            put("pain_locations_json", "[]"); put("symptoms_json", "[]"); put("moods_json", "[]")
            put("deleted", 1)
            put("sync_state", CycleSyncState.PENDING)
            put("updated_ms", now())
        },
        "day = ? AND deleted = 0", arrayOf(day)
    ) > 0

    // -- Sync bookkeeping (Wave 2: Health Connect writes) ----------------------------------------------

    /** Rows whose Health state must be written or removed (pending or failed), deleted ones included. */
    suspend fun pendingSync(): Pair<List<CyclePeriod>, List<CycleDayLog>> = withContext(Dispatchers.IO) {
        val where = "sync_state IN ('${CycleSyncState.PENDING}', '${CycleSyncState.FAILED}')"
        queryPeriods(db, where, null) to queryLogs(db, where, null)
    }

    /** Records the outcome of a Health write; skipped when the row changed since [expectedUpdatedMs]. */
    suspend fun markPeriodSync(id: String, state: String, platformIdsJson: String, expectedUpdatedMs: Long): Boolean =
        withContext(Dispatchers.IO) {
            write {
                it.update(
                    "cycle_periods",
                    ContentValues().apply { put("sync_state", state); put("platform_ids_json", platformIdsJson) },
                    "id = ? AND updated_ms = ?", arrayOf(id, expectedUpdatedMs.toString())
                ) > 0
            }
        }

    suspend fun markDayLogSync(day: String, state: String, platformIdsJson: String, expectedUpdatedMs: Long): Boolean =
        withContext(Dispatchers.IO) {
            write {
                it.update(
                    "cycle_day_logs",
                    ContentValues().apply { put("sync_state", state); put("platform_ids_json", platformIdsJson) },
                    "day = ? AND updated_ms = ?", arrayOf(day, expectedUpdatedMs.toString())
                ) > 0
            }
        }

    /** Turning Health sync on: every row is written (or removed) again on the next sync. */
    suspend fun markAllPending(): Unit = withContext(Dispatchers.IO) {
        write {
            val v = ContentValues().apply { put("sync_state", CycleSyncState.PENDING) }
            it.update("cycle_periods", v, null, null)
            it.update("cycle_day_logs", v, null, null)
        }
    }

    // -- Read-only Health Connect extras for the day sheet ---------------------------------------------

    /** Ovulation test result (canonical code, docs/health-data.md §5) and basal body temperature (°C) of [day]. */
    data class PlatformDay(val ovulationTest: Int?, val basalTempC: Double?)

    suspend fun platformDay(day: String): PlatformDay? = withContext(Dispatchers.IO) {
        val health = healthDatabase() ?: return@withContext null
        runCatching {
            var ovulation: Int? = null
            var temp: Double? = null
            health.readableDatabase.rawQuery(
                "SELECT type_id, value, category_value FROM health_samples WHERE deleted = 0 AND local_day = ? " +
                    "AND type_id IN ('ovulation_test', 'basal_body_temperature') ORDER BY end_ms",
                arrayOf(day)
            ).use { c ->
                while (c.moveToNext()) {
                    if (c.getString(0) == "ovulation_test") ovulation = c.intOrNull(2) ?: ovulation
                    else if (!c.isNull(1)) temp = c.getDouble(1)
                }
            }
            if (ovulation == null && temp == null) null else PlatformDay(ovulation, temp)
        }.getOrNull()
    }

    // -- Settings ---------------------------------------------------------------------------------

    suspend fun settings(): CycleSettingsRow = withContext(Dispatchers.IO) { readSettings(db) ?: CycleSettingsRow() }

    suspend fun saveSettings(row: CycleSettingsRow): CycleSettingsRow = withContext(Dispatchers.IO) {
        val saved = row.copy(updatedMs = now())
        write { writeSettings(it, saved) }
        saved
    }

    private fun readSettings(database: SQLiteDatabase): CycleSettingsRow? = database.rawQuery(
        "SELECT setup_done, cycle_length, period_length, luteal_length, settings_json, updated_ms FROM cycle_settings WHERE id = 1",
        null
    ).use { c ->
        if (!c.moveToFirst()) null else CycleSettingsRow(
            setupDone = c.getInt(0) == 1,
            cycleLength = c.intOrNull(1),
            periodLength = c.intOrNull(2),
            lutealLength = c.intOrNull(3),
            preferences = CyclePreferences.parse(c.getString(4)),
            updatedMs = c.getLong(5)
        )
    }

    private fun writeSettings(database: SQLiteDatabase, row: CycleSettingsRow) {
        val values = ContentValues().apply {
            put("setup_done", if (row.setupDone) 1 else 0)
            if (row.cycleLength == null) putNull("cycle_length") else put("cycle_length", row.cycleLength)
            if (row.periodLength == null) putNull("period_length") else put("period_length", row.periodLength)
            if (row.lutealLength == null) putNull("luteal_length") else put("luteal_length", row.lutealLength)
            put("settings_json", row.preferences.toJson().toString())
            put("updated_ms", row.updatedMs)
        }
        if (database.update("cycle_settings", values, "id = 1", null) == 0) {
            values.put("id", 1)
            database.insertOrThrow("cycle_settings", null, values)
        }
    }

    /** Settings › Cycle tracking › Delete all cycle data: every row, including the settings (setup starts over). */
    suspend fun deleteAll(): Unit = withContext(Dispatchers.IO) {
        write {
            it.delete("cycle_periods", null, null)
            it.delete("cycle_day_logs", null, null)
            it.delete("cycle_settings", null, null)
        }
    }

    // -- Engine input -----------------------------------------------------------------------------

    /** Health Connect periods from the health mirror (empty when the hub was never set up). */
    suspend fun platformPeriods(zone: ZoneId = ZoneId.systemDefault()): List<CyclePeriodInput> = withContext(Dispatchers.IO) {
        val health = healthDatabase() ?: return@withContext emptyList()
        val samples = runCatching {
            health.readableDatabase.rawQuery(
                "SELECT id, type_id, start_ms, end_ms, start_offset_s, end_offset_s, local_day, category_value, source_id, " +
                    "client_record_id, origin FROM health_samples WHERE deleted = 0 AND type_id IN (?, ?)",
                arrayOf(CyclePlatformPeriods.TYPE_PERIOD, CyclePlatformPeriods.TYPE_FLOW)
            ).use { c ->
                val out = ArrayList<CyclePlatformPeriods.Sample>(c.count)
                while (c.moveToNext()) {
                    out += CyclePlatformPeriods.Sample(
                        id = c.getString(0), typeId = c.getString(1), startMs = c.getLong(2), endMs = c.getLong(3),
                        startOffsetS = c.intOrNull(4), endOffsetS = c.intOrNull(5), localDay = c.getString(6),
                        categoryValue = c.intOrNull(7), sourceId = c.getString(8),
                        clientRecordId = if (c.isNull(9)) null else c.getString(9), origin = c.getInt(10)
                    )
                }
                out
            }
        }.getOrElse { emptyList() }
        CyclePlatformPeriods.periods(samples, ownPackage, zone)
    }

    /** Everything the engine needs for [today]: settings, live app periods, Health Connect periods, live logs. */
    suspend fun state(today: String, includePlatform: Boolean = true): CycleState {
        val settings = settings()
        val app = periods().map { CyclePeriodInput(it.id, it.startDay, it.endDay, "app") }
        val platform = if (includePlatform) platformPeriods() else emptyList()
        val logs = dayLogs().map {
            CycleDayLogInput(it.day, it.flow, it.pain, it.painLocations, it.symptoms, it.moods)
        }
        return CycleState(today, settings.toEngineInput(), app + platform, logs)
    }

    // -- Export / import (docs/cycle-tracking.md §7) ------------------------------------------------------

    /** Every row, tombstones included; null settings when setup never ran. */
    suspend fun exportBundle(): CycleArchive.Bundle = withContext(Dispatchers.IO) {
        val database = db
        CycleArchive.Bundle(queryPeriods(database, null, null), queryLogs(database, null, null), readSettings(database))
    }

    /** §7 merge: insert missing rows, replace older ones, keep newer local ones; nothing is deleted outright. */
    suspend fun importArchive(bundle: CycleArchive.Bundle): CycleArchive.ImportResult = withContext(Dispatchers.IO) {
        write { database ->
            var periods = 0
            var logs = 0
            for (p in bundle.periods) {
                val local = queryPeriods(database, "id = ?", arrayOf(p.id)).firstOrNull()
                if (!CycleArchive.shouldApply(local?.updatedMs, p.updatedMs)) continue
                val row = p.copy(
                    platformIdsJson = local?.platformIdsJson ?: "{}",
                    syncState = CycleSyncState.PENDING,
                    createdMs = local?.createdMs ?: p.createdMs
                )
                if (local == null) database.insertOrThrow("cycle_periods", null, periodValues(row))
                else database.update("cycle_periods", periodValues(row), "id = ?", arrayOf(p.id))
                periods++
            }
            for (l in bundle.dayLogs) {
                val local = queryLogs(database, "day = ?", arrayOf(l.day)).firstOrNull()
                if (!CycleArchive.shouldApply(local?.updatedMs, l.updatedMs)) continue
                val row = l.copy(platformIdsJson = local?.platformIdsJson ?: "{}", syncState = CycleSyncState.PENDING)
                if (local == null) database.insertOrThrow("cycle_day_logs", null, logValues(row))
                else database.update("cycle_day_logs", logValues(row), "day = ?", arrayOf(l.day))
                logs++
            }
            var settingsApplied = false
            bundle.settings?.let { incoming ->
                val local = readSettings(database)
                if (CycleArchive.shouldApply(local?.updatedMs, incoming.updatedMs)) {
                    writeSettings(database, incoming)
                    settingsApplied = true
                }
            }
            CycleArchive.ImportResult(periods, logs, settingsApplied, bundle.skippedLines)
        }
    }

    // -- Rows -------------------------------------------------------------------------------------

    private fun periodValues(p: CyclePeriod) = ContentValues().apply {
        put("id", p.id)
        put("start_day", p.startDay)
        if (p.endDay == null) putNull("end_day") else put("end_day", p.endDay)
        put("platform_ids_json", p.platformIdsJson)
        put("sync_state", p.syncState)
        put("created_ms", p.createdMs)
        put("updated_ms", p.updatedMs)
        put("deleted", if (p.deleted) 1 else 0)
    }

    private fun logValues(l: CycleDayLog) = ContentValues().apply {
        put("day", l.day)
        if (l.flow == null) putNull("flow") else put("flow", l.flow)
        if (l.pain == null) putNull("pain") else put("pain", l.pain)
        put("pain_locations_json", CycleJsonLists.encode(l.painLocations))
        put("symptoms_json", CycleJsonLists.encode(l.symptoms))
        put("moods_json", CycleJsonLists.encode(l.moods))
        if (l.note.isNullOrBlank() || l.deleted) putNull("note") else put("note", l.note)
        put("platform_ids_json", l.platformIdsJson)
        put("sync_state", l.syncState)
        put("updated_ms", l.updatedMs)
        put("deleted", if (l.deleted) 1 else 0)
    }

    private fun queryPeriods(database: SQLiteDatabase, where: String?, args: Array<String>?): List<CyclePeriod> =
        database.query(
            "cycle_periods",
            arrayOf("id", "start_day", "end_day", "platform_ids_json", "sync_state", "created_ms", "updated_ms", "deleted"),
            where, args, null, null, "start_day, id"
        ).use { c ->
            val out = ArrayList<CyclePeriod>(c.count)
            while (c.moveToNext()) {
                out += CyclePeriod(
                    id = c.getString(0), startDay = c.getString(1), endDay = if (c.isNull(2)) null else c.getString(2),
                    platformIdsJson = c.getString(3), syncState = c.getString(4), createdMs = c.getLong(5),
                    updatedMs = c.getLong(6), deleted = c.getInt(7) == 1
                )
            }
            out
        }

    private fun queryLogs(database: SQLiteDatabase, where: String?, args: Array<String>?): List<CycleDayLog> =
        database.query(
            "cycle_day_logs",
            arrayOf(
                "day", "flow", "pain", "pain_locations_json", "symptoms_json", "moods_json", "note",
                "platform_ids_json", "sync_state", "updated_ms", "deleted"
            ),
            where, args, null, null, "day"
        ).use { c ->
            val out = ArrayList<CycleDayLog>(c.count)
            while (c.moveToNext()) {
                out += CycleDayLog(
                    day = c.getString(0), flow = if (c.isNull(1)) null else c.getString(1), pain = c.intOrNull(2),
                    painLocations = CycleJsonLists.decode(c.getString(3)), symptoms = CycleJsonLists.decode(c.getString(4)),
                    moods = CycleJsonLists.decode(c.getString(5)), note = if (c.isNull(6)) null else c.getString(6),
                    platformIdsJson = c.getString(7), syncState = c.getString(8), updatedMs = c.getLong(9),
                    deleted = c.getInt(10) == 1
                )
            }
            out
        }

    private fun Cursor.intOrNull(i: Int): Int? = if (isNull(i)) null else getInt(i)
}
