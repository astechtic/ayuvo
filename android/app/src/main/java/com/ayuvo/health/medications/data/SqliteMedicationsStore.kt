package com.ayuvo.health.medications.data

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import com.ayuvo.health.medications.logic.Adherence
import com.ayuvo.health.medications.logic.ArchiveCodec
import com.ayuvo.health.medications.logic.AutoComplete
import com.ayuvo.health.medications.logic.DoseActions
import com.ayuvo.health.medications.logic.Lifecycle
import com.ayuvo.health.medications.logic.MedicationConstants
import com.ayuvo.health.medications.logic.MedicationJson
import com.ayuvo.health.medications.logic.MedicationLocalTime
import com.ayuvo.health.medications.logic.MissedMaterializer
import com.ayuvo.health.medications.logic.Occurrences
import com.ayuvo.health.medications.logic.PrnLogging
import com.ayuvo.health.medications.logic.ReminderPlanner
import com.ayuvo.health.medications.logic.TodayTimelineBuilder
import com.ayuvo.health.medications.model.AdherenceSummary
import com.ayuvo.health.medications.model.DoseAction
import com.ayuvo.health.medications.model.DoseActionResult
import com.ayuvo.health.medications.model.DoseLog
import com.ayuvo.health.medications.model.DoseLogCursor
import com.ayuvo.health.medications.model.DoseStatus
import com.ayuvo.health.medications.model.DoseUnit
import com.ayuvo.health.medications.model.FoodRelation
import com.ayuvo.health.medications.model.ImportResult
import com.ayuvo.health.medications.model.LifecycleAction
import com.ayuvo.health.medications.model.LifecycleOp
import com.ayuvo.health.medications.model.LifecycleResult
import com.ayuvo.health.medications.model.Medication
import com.ayuvo.health.medications.model.MedicationFilter
import com.ayuvo.health.medications.model.MedicationForm
import com.ayuvo.health.medications.model.MedicationSchedule
import com.ayuvo.health.medications.model.MedicationStatus
import com.ayuvo.health.medications.model.MedicationsSnapshot
import com.ayuvo.health.medications.model.MissedOp
import com.ayuvo.health.medications.model.Occurrence
import com.ayuvo.health.medications.model.PrnLogResult
import com.ayuvo.health.medications.model.ReminderPlan
import com.ayuvo.health.medications.model.ScheduleDraft
import com.ayuvo.health.medications.model.ScheduleFrequency
import com.ayuvo.health.medications.model.ScheduleJson
import com.ayuvo.health.medications.model.TodayTimeline
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import java.util.UUID

/**
 * [MedicationsStore] over [MedicationsDatabase]. Writes run in one transaction on `Dispatchers.IO`
 * and bump [revision] afterwards; the shared rules (`medications/logic`) decide every row.
 */
class SqliteMedicationsStore(
    private val helper: MedicationsDatabase,
    private val photos: MedicationPhotoStore
) : MedicationsStore {

    private val _revision = MutableStateFlow(0L)
    override val revision: StateFlow<Long> = _revision

    private val db: SQLiteDatabase get() = helper.writableDatabase

    private fun newId(): String = UUID.randomUUID().toString()

    private inline fun <T> write(bump: Boolean = true, block: (SQLiteDatabase) -> T): T {
        val database = db
        database.beginTransactionNonExclusive()
        var committed = false
        try {
            val result = block(database)
            database.setTransactionSuccessful()
            committed = true
            return result
        } finally {
            database.endTransaction()
            if (committed && bump) _revision.value = _revision.value + 1
        }
    }

    // -- medications ------------------------------------------------------------------------------

    override suspend fun list(filter: MedicationFilter): List<Medication> = withContext(Dispatchers.IO) {
        val where = mutableListOf<String>()
        val args = mutableListOf<String>()
        filter.status?.let { where += "status = ?"; args += it.raw }
        filter.query.trim().takeIf { it.isNotEmpty() }?.let { q ->
            val pattern = "%${escapeLike(q)}%"
            where += "(name LIKE ? ESCAPE '\\' OR brand_name LIKE ? ESCAPE '\\' OR generic_name LIKE ? ESCAPE '\\')"
            args += pattern; args += pattern; args += pattern
        }
        val sql = "SELECT $MED_COLUMNS FROM medications" +
            (if (where.isEmpty()) "" else " WHERE " + where.joinToString(" AND ")) +
            " ORDER BY name COLLATE NOCASE, id"
        db.rawQuery(sql, args.toTypedArray()).use { it.readMedications() }
    }

    override suspend fun medication(id: String): Medication? = withContext(Dispatchers.IO) { medicationRow(db, id) }

    override suspend fun medicationsForRecord(recordId: String): List<Medication> = withContext(Dispatchers.IO) {
        db.rawQuery("SELECT $MED_COLUMNS FROM medications WHERE related_record_id = ? ORDER BY name COLLATE NOCASE, id", arrayOf(recordId))
            .use { it.readMedications() }
    }

    override suspend fun countByStatus(): Map<MedicationStatus, Int> = withContext(Dispatchers.IO) {
        db.rawQuery("SELECT status, COUNT(*) FROM medications GROUP BY status", null).use { c ->
            val out = mutableMapOf<MedicationStatus, Int>()
            while (c.moveToNext()) out[MedicationStatus.fromRaw(c.getString(0))] = c.getInt(1)
            out
        }
    }

    override suspend fun create(medication: Medication, schedule: MedicationSchedule?): String = withContext(Dispatchers.IO) {
        write { database ->
            database.insertOrThrow("medications", null, medicationValues(medication))
            if (schedule != null && !medication.isPrn) {
                val row = schedule.copy(
                    id = schedule.id.ifBlank { newId() }, medicationId = medication.id, activeUntilMs = null
                )
                database.insertOrThrow("medication_schedules", null, scheduleValues(row))
            }
        }
        medication.id
    }

    override suspend fun update(medication: Medication, newSchedule: MedicationSchedule?, nowMs: Long): String? = withContext(Dispatchers.IO) {
        write<String?> { database ->
            val current = medicationRow(database, medication.id) ?: return@write "unknown_medication"
            val rows = schedulesOf(database, medication.id, openOnly = false)
            val med = medication.copy(status = current.status, createdMs = current.createdMs, updatedMs = nowMs)
            database.update("medications", medicationValues(med), "id = ?", arrayOf(med.id))
            val open = rows.firstOrNull { it.isOpen }
            if (med.isPrn) {
                if (open != null) closeOpenRows(database, med.id, nowMs)
                return@write null
            }
            if (newSchedule == null) return@write null
            val draft = ScheduleDraft.from(newSchedule)
            val ruleChanged = open == null || !draft.sameRuleAs(open)
            when {
                ruleChanged && current.status == MedicationStatus.ACTIVE -> {
                    val r = Lifecycle.apply(LifecycleAction.EDIT_SCHEDULE, med, rows, nowMs, draft.toJson(), ::newId)
                    if (!r.ok) return@write r.error
                    applyLifecycle(database, r)
                }
                ruleChanged && current.status == MedicationStatus.PAUSED -> {
                    // Stored as the latest closed generation so `resume` copies it (docs §12).
                    val row = draft.toSchedule(newId(), med.id, nowMs).copy(activeUntilMs = nowMs)
                    database.insertOrThrow("medication_schedules", null, scheduleValues(row))
                }
                !ruleChanged && open != null && open.reminderEnabled != draft.reminderEnabled -> {
                    val r = Lifecycle.apply(
                        LifecycleAction.SET_REMINDER_ENABLED, med, rows, nowMs,
                        MedicationJson.obj("reminder_enabled" to if (draft.reminderEnabled) 1 else 0), ::newId
                    )
                    if (!r.ok) return@write r.error
                    applyLifecycle(database, r)
                }
            }
            null
        }
    }

    override suspend fun setStatus(id: String, action: LifecycleAction, nowMs: Long): String? = withContext(Dispatchers.IO) {
        val med = medicationRow(db, id) ?: return@withContext "unknown_medication"
        val rows = schedulesOf(db, id, openOnly = false)
        val r = Lifecycle.apply(action, med, rows, nowMs, null, ::newId)
        if (!r.ok) return@withContext r.error
        write { database -> applyLifecycle(database, r) }
        null
    }

    override suspend fun setReminderEnabled(medicationId: String, enabled: Boolean, nowMs: Long): String? = withContext(Dispatchers.IO) {
        val med = medicationRow(db, medicationId) ?: return@withContext "unknown_medication"
        val rows = schedulesOf(db, medicationId, openOnly = false)
        val r = Lifecycle.apply(
            LifecycleAction.SET_REMINDER_ENABLED, med, rows, nowMs,
            MedicationJson.obj("reminder_enabled" to if (enabled) 1 else 0), ::newId
        )
        if (!r.ok) return@withContext r.error
        write { database -> applyLifecycle(database, r) }
        null
    }

    override suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        write { database -> database.delete("medications", "id = ?", arrayOf(id)) }
        photos.delete(id)
    }

    // -- schedules --------------------------------------------------------------------------------

    override suspend fun schedules(medicationId: String, openOnly: Boolean): List<MedicationSchedule> =
        withContext(Dispatchers.IO) { schedulesOf(db, medicationId, openOnly) }

    override suspend fun openSchedules(): List<MedicationSchedule> = withContext(Dispatchers.IO) { openSchedules(db) }

    // -- today / doses ----------------------------------------------------------------------------

    override suspend fun today(nowMs: Long, zoneId: String): TodayTimeline = withContext(Dispatchers.IO) {
        val database = db
        val meds = allMedications(database)
        val schedules = allSchedules(database)
        val date = MedicationLocalTime.localDateOf(nowMs, zoneId)
        val (start, end) = MedicationLocalTime.dayWindow(date, zoneId)
        val logs = LinkedHashMap<String, DoseLog>()
        logsBetween(database, start, end).forEach { logs[it.id] = it }
        for (m in meds) {
            if (m.isPrn && m.isActive) latestPrnTaken(database, m.id)?.let { logs.putIfAbsent(it.id, it) }
        }
        TodayTimelineBuilder.build(meds, schedules, logs.values.toList(), nowMs, zoneId)
            .copy(medications = meds.associateBy { it.id })
    }

    override suspend fun act(
        medicationId: String,
        scheduleId: String?,
        scheduledAtMs: Long,
        action: DoseAction,
        nowMs: Long,
        snoozeMinutes: Int,
        takenAtMs: Long?,
        note: String?,
        logId: String?
    ): DoseActionResult = withContext(Dispatchers.IO) {
        val database = db
        val med = medicationRow(database, medicationId) ?: return@withContext DoseActionResult(false, "unknown_medication", null, null)
        val existing = when {
            logId != null -> logById(database, logId)
            scheduleId != null -> logByOccurrence(database, scheduleId, scheduledAtMs)
            else -> null
        }
        val occurrence = Occurrence(medicationId, scheduleId ?: existing?.scheduleId ?: "", scheduledAtMs)
        val r = DoseActions.apply(action, occurrence, existing, med, nowMs, snoozeMinutes, takenAtMs, note, ::newId)
        val log = r.log
        if (!r.ok || log == null) return@withContext r
        write { d ->
            when (r.op) {
                "insert" -> upsertLog(d, log)
                "update" -> updateLog(d, log)
                "delete" -> d.delete("dose_logs", "id = ?", arrayOf(log.id))
            }
        }
        r
    }

    override suspend fun logPrn(medicationId: String, nowMs: Long, takenAtMs: Long?, doseQuantity: Double?, note: String?): PrnLogResult =
        withContext(Dispatchers.IO) {
            val med = medicationRow(db, medicationId) ?: return@withContext PrnLogResult(false, "unknown_medication", null)
            val r = PrnLogging.log(med, nowMs, takenAtMs, doseQuantity, note, ::newId)
            val log = r.log
            if (!r.ok || log == null) return@withContext r
            write { d -> d.insertOrThrow("dose_logs", null, logValues(log)) }
            r
        }

    override suspend fun doseLog(scheduleId: String, scheduledAtMs: Long): DoseLog? = withContext(Dispatchers.IO) {
        logByOccurrence(db, scheduleId, scheduledAtMs)
    }

    override suspend fun history(medicationId: String?, before: DoseLogCursor?, limit: Int): List<DoseLog> = withContext(Dispatchers.IO) {
        val where = mutableListOf<String>()
        val args = mutableListOf<String>()
        if (medicationId != null) { where += "medication_id = ?"; args += medicationId }
        if (before != null) {
            where += "(scheduled_at_ms < ? OR (scheduled_at_ms = ? AND id < ?))"
            args += before.scheduledAtMs.toString(); args += before.scheduledAtMs.toString(); args += before.id
        }
        val sql = "SELECT $LOG_COLUMNS FROM dose_logs" +
            (if (where.isEmpty()) "" else " WHERE " + where.joinToString(" AND ")) +
            " ORDER BY scheduled_at_ms DESC, id DESC LIMIT ?"
        args += limit.toString()
        db.rawQuery(sql, args.toTypedArray()).use { it.readLogs() }
    }

    override suspend fun adherence(medicationId: String?, nowMs: Long, zoneId: String): AdherenceSummary = withContext(Dispatchers.IO) {
        val database = db
        val start = nowMs - MedicationConstants.ADHERENCE_WINDOW_MS
        val meds = if (medicationId != null) listOfNotNull(medicationRow(database, medicationId)) else allMedications(database)
        val schedules = if (medicationId != null) schedulesOf(database, medicationId, openOnly = false) else allSchedules(database)
        val occurrences = Occurrences.expandAll(meds, schedules, start, nowMs + 1, zoneId, null)
        val logs = adherenceLogs(database, medicationId, start, nowMs)
        Adherence.compute(occurrences, logs, nowMs, medicationId)
    }

    override suspend fun materializeMissed(nowMs: Long, zoneId: String): Int = withContext(Dispatchers.IO) {
        val database = db
        val meds = allMedications(database)
        val byId = meds.associateBy { it.id }
        val schedules = allSchedules(database)
        val until = nowMs - MedicationConstants.GRACE_MS
        val cursor = metaRow(database, MedicationConstants.META_MISSED_CURSOR)?.toLongOrNull()
            ?: (nowMs - MedicationConstants.MISSED_LOOKBACK_MS)
        val windowOpen = until > cursor
        val windowed = if (windowOpen) Occurrences.expandAll(meds, schedules, cursor, until, zoneId, null) else emptyList()
        val snoozed = snoozedLogs(database, sinceMs = null)
        val keys = windowed.mapTo(HashSet()) { it.scheduleId to it.scheduledAtMs }
        val extra = snoozed.mapNotNull { l -> l.scheduleId?.let { Occurrence(l.medicationId, it, l.scheduledAtMs) } }
            .filter { keys.add(it.scheduleId to it.scheduledAtMs) }
        val logs = LinkedHashMap<String, DoseLog>()
        if (windowOpen) logsBetween(database, cursor, until).forEach { logs[it.id] = it }
        snoozed.forEach { logs.putIfAbsent(it.id, it) }
        val ops = MissedMaterializer.pending(windowed + extra, logs.values.toList(), byId, nowMs, ::newId)
        write(bump = ops.isNotEmpty()) { d ->
            for (op in ops) {
                if (op.op == MissedOp.INSERT) upsertLog(d, op.log) else updateLog(d, op.log)
            }
            if (windowOpen) setMetaRow(d, MedicationConstants.META_MISSED_CURSOR, until.toString())
        }
        ops.size
    }

    override suspend fun autoComplete(todayLocal: String, nowMs: Long): Int = withContext(Dispatchers.IO) {
        val database = db
        val meds = allMedications(database)
        val ids = AutoComplete.due(meds, todayLocal)
        if (ids.isEmpty()) return@withContext 0
        var completed = 0
        write { d ->
            for (id in ids) {
                val med = medicationRow(d, id) ?: continue
                val rows = schedulesOf(d, id, openOnly = false)
                val r = Lifecycle.apply(LifecycleAction.COMPLETE, med, rows, nowMs, null, ::newId)
                if (r.ok) { applyLifecycle(d, r); completed++ }
            }
        }
        completed
    }

    override suspend fun planReminders(nowMs: Long, horizonMs: Long, zoneId: String, budget: Int?): ReminderPlan = withContext(Dispatchers.IO) {
        val database = db
        val meds = allMedications(database)
        val schedules = openSchedules(database)
        val logs = LinkedHashMap<String, DoseLog>()
        logsBetween(database, nowMs - MedicationConstants.LATE_FIRE_MS, nowMs + maxOf(horizonMs, 0L)).forEach { logs[it.id] = it }
        snoozedLogs(database, sinceMs = nowMs - MedicationConstants.LATE_FIRE_MS).forEach { logs.putIfAbsent(it.id, it) }
        ReminderPlanner.plan(meds, schedules, logs.values.toList(), nowMs, horizonMs, zoneId, budget)
    }

    // -- archive ----------------------------------------------------------------------------------

    override suspend fun exportSnapshot(): MedicationsSnapshot = withContext(Dispatchers.IO) { snapshot(db) }

    override suspend fun importArchive(archive: JsonObject, nowMs: Long): ImportResult = withContext(Dispatchers.IO) {
        val local = snapshot(db)
        val r = ArchiveCodec.merge(local, archive, nowMs)
        if (!r.ok) return@withContext ImportResult(false, r.error)
        var inserted = 0
        var updated = 0
        var skipped = 0
        write(bump = r.ops.any { it.op != "skip" }) { d ->
            for (op in r.ops) {
                val row = op.row
                if (op.op == "skip" || row == null) { skipped++; continue }
                when (op.table) {
                    ArchiveCodec.TABLE_MEDICATIONS -> {
                        val m = MedicationJson.medication(row)
                        if (op.op == "insert") d.insertOrThrow("medications", null, medicationValues(m))
                        else d.update("medications", medicationValues(m), "id = ?", arrayOf(m.id))
                    }
                    ArchiveCodec.TABLE_SCHEDULES -> {
                        val s = MedicationJson.schedule(row)
                        if (op.op == "insert") d.insertOrThrow("medication_schedules", null, scheduleValues(s))
                        else d.update("medication_schedules", scheduleValues(s), "id = ?", arrayOf(s.id))
                    }
                    ArchiveCodec.TABLE_DOSE_LOGS -> {
                        val l = MedicationJson.doseLog(row)
                        if (op.op == "insert") d.insertOrThrow("dose_logs", null, logValues(l))
                        else d.update("dose_logs", logValues(l), "id = ?", arrayOf(l.id))
                    }
                }
                if (op.op == "insert") inserted++ else updated++
            }
        }
        ImportResult(true, null, inserted, updated, skipped)
    }

    // -- meta -------------------------------------------------------------------------------------

    override suspend fun meta(key: String): String? = withContext(Dispatchers.IO) { metaRow(db, key) }

    override suspend fun setMeta(key: String, value: String) = withContext(Dispatchers.IO) {
        write(bump = false) { d -> setMetaRow(d, key, value) }
    }

    // -- internals --------------------------------------------------------------------------------

    private fun applyLifecycle(d: SQLiteDatabase, r: LifecycleResult) {
        val med = r.medication
        val byId = r.schedules.associateBy { it.id }
        for (op in r.ops) {
            when (op.op) {
                LifecycleOp.SET_STATUS -> d.update(
                    "medications",
                    ContentValues().apply { put("status", med.status.raw); put("updated_ms", med.updatedMs) },
                    "id = ?", arrayOf(med.id)
                )
                LifecycleOp.CLOSE_SCHEDULE -> {
                    val row = byId[op.id] ?: continue
                    d.update(
                        "medication_schedules",
                        ContentValues().apply { put("active_until_ms", row.activeUntilMs); put("updated_ms", row.updatedMs) },
                        "id = ?", arrayOf(row.id)
                    )
                }
                LifecycleOp.INSERT_SCHEDULE -> {
                    val row = byId[op.id] ?: continue
                    d.insertOrThrow("medication_schedules", null, scheduleValues(row))
                }
                LifecycleOp.UPDATE_SCHEDULE -> {
                    val row = byId[op.id] ?: continue
                    d.update(
                        "medication_schedules",
                        ContentValues().apply { put("reminder_enabled", if (row.reminderEnabled) 1 else 0); put("updated_ms", row.updatedMs) },
                        "id = ?", arrayOf(row.id)
                    )
                }
            }
        }
        if (r.ops.none { it.op == LifecycleOp.SET_STATUS } && r.ops.isNotEmpty()) {
            d.update("medications", ContentValues().apply { put("updated_ms", med.updatedMs) }, "id = ?", arrayOf(med.id))
        }
    }

    private fun closeOpenRows(d: SQLiteDatabase, medicationId: String, nowMs: Long) {
        d.update(
            "medication_schedules",
            ContentValues().apply { put("active_until_ms", nowMs); put("updated_ms", nowMs) },
            "medication_id = ? AND active_until_ms IS NULL", arrayOf(medicationId)
        )
    }

    /** UPDATE-then-INSERT on `(schedule_id, scheduled_at_ms)` (no UPSERT on SQLite 3.18); PRN rows always insert. */
    private fun upsertLog(d: SQLiteDatabase, log: DoseLog) {
        val sid = log.scheduleId
        if (sid != null) {
            val values = logValues(log).apply { remove("id"); remove("created_ms") }
            val changed = d.update("dose_logs", values, "schedule_id = ? AND scheduled_at_ms = ?", arrayOf(sid, log.scheduledAtMs.toString()))
            if (changed > 0) return
        }
        d.insertOrThrow("dose_logs", null, logValues(log))
    }

    private fun updateLog(d: SQLiteDatabase, log: DoseLog) {
        val changed = d.update("dose_logs", logValues(log), "id = ?", arrayOf(log.id))
        if (changed == 0) upsertLog(d, log)
    }

    private fun snapshot(d: SQLiteDatabase): MedicationsSnapshot = MedicationsSnapshot(
        medications = d.rawQuery("SELECT $MED_COLUMNS FROM medications ORDER BY id", null).use { it.readMedications() },
        schedules = d.rawQuery("SELECT $SCH_COLUMNS FROM medication_schedules ORDER BY id", null).use { it.readSchedules() },
        doseLogs = d.rawQuery("SELECT $LOG_COLUMNS FROM dose_logs ORDER BY id", null).use { it.readLogs() }
    )

    private fun medicationRow(d: SQLiteDatabase, id: String): Medication? =
        d.rawQuery("SELECT $MED_COLUMNS FROM medications WHERE id = ?", arrayOf(id)).use { it.readMedications().firstOrNull() }

    private fun allMedications(d: SQLiteDatabase): List<Medication> =
        d.rawQuery("SELECT $MED_COLUMNS FROM medications ORDER BY name COLLATE NOCASE, id", null).use { it.readMedications() }

    private fun schedulesOf(d: SQLiteDatabase, medicationId: String, openOnly: Boolean): List<MedicationSchedule> =
        d.rawQuery(
            "SELECT $SCH_COLUMNS FROM medication_schedules WHERE medication_id = ?" +
                (if (openOnly) " AND active_until_ms IS NULL" else "") + " ORDER BY active_from_ms, id",
            arrayOf(medicationId)
        ).use { it.readSchedules() }

    private fun openSchedules(d: SQLiteDatabase): List<MedicationSchedule> =
        d.rawQuery("SELECT $SCH_COLUMNS FROM medication_schedules WHERE active_until_ms IS NULL ORDER BY medication_id, active_from_ms, id", null)
            .use { it.readSchedules() }

    private fun allSchedules(d: SQLiteDatabase): List<MedicationSchedule> =
        d.rawQuery("SELECT $SCH_COLUMNS FROM medication_schedules ORDER BY medication_id, active_from_ms, id", null).use { it.readSchedules() }

    private fun logsBetween(d: SQLiteDatabase, startMs: Long, endMs: Long): List<DoseLog> =
        d.rawQuery(
            "SELECT $LOG_COLUMNS FROM dose_logs WHERE scheduled_at_ms >= ? AND scheduled_at_ms < ? ORDER BY scheduled_at_ms, id",
            arrayOf(startMs.toString(), endMs.toString())
        ).use { it.readLogs() }

    private fun adherenceLogs(d: SQLiteDatabase, medicationId: String?, startMs: Long, nowMs: Long): List<DoseLog> {
        val sql = "SELECT $LOG_COLUMNS FROM dose_logs WHERE " +
            (if (medicationId != null) "medication_id = ? AND " else "") +
            "scheduled_at_ms >= ? AND scheduled_at_ms <= ? AND schedule_id IS NOT NULL ORDER BY scheduled_at_ms, id"
        val args = (if (medicationId != null) listOf(medicationId) else emptyList()) + listOf(startMs.toString(), nowMs.toString())
        return d.rawQuery(sql, args.toTypedArray()).use { it.readLogs() }
    }

    private fun snoozedLogs(d: SQLiteDatabase, sinceMs: Long?): List<DoseLog> =
        if (sinceMs == null) {
            d.rawQuery("SELECT $LOG_COLUMNS FROM dose_logs WHERE status = 'snoozed' ORDER BY scheduled_at_ms, id", null).use { it.readLogs() }
        } else {
            d.rawQuery(
                "SELECT $LOG_COLUMNS FROM dose_logs WHERE status = 'snoozed' AND snoozed_until_ms >= ? ORDER BY scheduled_at_ms, id",
                arrayOf(sinceMs.toString())
            ).use { it.readLogs() }
        }

    private fun latestPrnTaken(d: SQLiteDatabase, medicationId: String): DoseLog? =
        d.rawQuery(
            "SELECT $LOG_COLUMNS FROM dose_logs WHERE medication_id = ? AND schedule_id IS NULL AND status = 'taken' " +
                "ORDER BY taken_at_ms DESC, id DESC LIMIT 1",
            arrayOf(medicationId)
        ).use { it.readLogs().firstOrNull() }

    private fun logByOccurrence(d: SQLiteDatabase, scheduleId: String, scheduledAtMs: Long): DoseLog? =
        d.rawQuery(
            "SELECT $LOG_COLUMNS FROM dose_logs WHERE schedule_id = ? AND scheduled_at_ms = ?",
            arrayOf(scheduleId, scheduledAtMs.toString())
        ).use { it.readLogs().firstOrNull() }

    private fun logById(d: SQLiteDatabase, id: String): DoseLog? =
        d.rawQuery("SELECT $LOG_COLUMNS FROM dose_logs WHERE id = ?", arrayOf(id)).use { it.readLogs().firstOrNull() }

    private fun metaRow(d: SQLiteDatabase, key: String): String? =
        d.rawQuery("SELECT value FROM medications_meta WHERE key = ?", arrayOf(key)).use { if (it.moveToFirst()) it.getString(0) else null }

    private fun setMetaRow(d: SQLiteDatabase, key: String, value: String) {
        d.execSQL("INSERT OR REPLACE INTO medications_meta(key, value) VALUES (?, ?)", arrayOf(key, value))
    }

    private fun escapeLike(q: String): String = q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

    // -- rows -------------------------------------------------------------------------------------

    private fun medicationValues(m: Medication) = ContentValues().apply {
        put("id", m.id); put("name", m.name); put("generic_name", m.genericName); put("brand_name", m.brandName)
        put("strength", m.strength); put("form", m.form.raw); put("dose_quantity", m.doseQuantity); put("dose_unit", m.doseUnit.raw)
        put("food_relation", m.foodRelation.raw); put("instructions", m.instructions); put("start_date", m.startDate)
        put("end_date", m.endDate); put("status", m.status.raw); put("is_prn", if (m.isPrn) 1 else 0); put("photo_path", m.photoPath)
        put("related_record_id", m.relatedRecordId); put("created_ms", m.createdMs); put("updated_ms", m.updatedMs)
    }

    private fun scheduleValues(s: MedicationSchedule) = ContentValues().apply {
        put("id", s.id); put("medication_id", s.medicationId); put("frequency_kind", s.frequency.raw)
        put("times_json", ScheduleJson.encodeTimes(s.times)); put("days_json", ScheduleJson.encodeDays(s.days))
        if (s.intervalHours != null) put("interval_hours", s.intervalHours) else putNull("interval_hours")
        put("anchor_time", s.anchorTime); put("reminder_enabled", if (s.reminderEnabled) 1 else 0)
        put("active_from_ms", s.activeFromMs)
        if (s.activeUntilMs != null) put("active_until_ms", s.activeUntilMs) else putNull("active_until_ms")
        put("created_ms", s.createdMs); put("updated_ms", s.updatedMs)
    }

    private fun logValues(l: DoseLog) = ContentValues().apply {
        put("id", l.id); put("medication_id", l.medicationId); put("schedule_id", l.scheduleId); put("scheduled_at_ms", l.scheduledAtMs)
        put("status", l.status.raw)
        if (l.takenAtMs != null) put("taken_at_ms", l.takenAtMs) else putNull("taken_at_ms")
        if (l.snoozedUntilMs != null) put("snoozed_until_ms", l.snoozedUntilMs) else putNull("snoozed_until_ms")
        put("dose_quantity", l.doseQuantity); put("dose_unit", l.doseUnit.raw); put("note", l.note)
        put("created_ms", l.createdMs); put("updated_ms", l.updatedMs)
    }

    private fun Cursor.getStringOrNull(index: Int): String? = if (isNull(index)) null else getString(index)
    private fun Cursor.getLongOrNull(index: Int): Long? = if (isNull(index)) null else getLong(index)
    private fun Cursor.getIntOrNull(index: Int): Int? = if (isNull(index)) null else getInt(index)

    private fun Cursor.readMedications(): List<Medication> {
        val out = mutableListOf<Medication>()
        while (moveToNext()) out += Medication(
            id = getString(0), name = getString(1), genericName = getStringOrNull(2), brandName = getStringOrNull(3),
            strength = getStringOrNull(4), form = MedicationForm.fromRaw(getString(5)), doseQuantity = getDouble(6),
            doseUnit = DoseUnit.fromRaw(getString(7)), foodRelation = FoodRelation.fromRaw(getString(8)),
            instructions = getStringOrNull(9), startDate = getString(10), endDate = getStringOrNull(11),
            status = MedicationStatus.fromRaw(getString(12)), isPrn = getInt(13) != 0, photoPath = getStringOrNull(14),
            relatedRecordId = getStringOrNull(15), createdMs = getLong(16), updatedMs = getLong(17)
        )
        return out
    }

    private fun Cursor.readSchedules(): List<MedicationSchedule> {
        val out = mutableListOf<MedicationSchedule>()
        while (moveToNext()) out += MedicationSchedule(
            id = getString(0), medicationId = getString(1), frequency = ScheduleFrequency.fromRaw(getString(2)),
            times = ScheduleJson.decodeTimes(getStringOrNull(3)), days = ScheduleJson.decodeDays(getStringOrNull(4)),
            intervalHours = getIntOrNull(5), anchorTime = getStringOrNull(6), reminderEnabled = getInt(7) != 0,
            activeFromMs = getLong(8), activeUntilMs = getLongOrNull(9), createdMs = getLong(10), updatedMs = getLong(11)
        )
        return out
    }

    private fun Cursor.readLogs(): List<DoseLog> {
        val out = mutableListOf<DoseLog>()
        while (moveToNext()) out += DoseLog(
            id = getString(0), medicationId = getString(1), scheduleId = getStringOrNull(2), scheduledAtMs = getLong(3),
            status = DoseStatus.fromRaw(getString(4)), takenAtMs = getLongOrNull(5), snoozedUntilMs = getLongOrNull(6),
            doseQuantity = getDouble(7), doseUnit = DoseUnit.fromRaw(getString(8)), note = getStringOrNull(9),
            createdMs = getLong(10), updatedMs = getLong(11)
        )
        return out
    }

    private companion object {
        const val MED_COLUMNS = "id, name, generic_name, brand_name, strength, form, dose_quantity, dose_unit, food_relation, " +
            "instructions, start_date, end_date, status, is_prn, photo_path, related_record_id, created_ms, updated_ms"
        const val SCH_COLUMNS = "id, medication_id, frequency_kind, times_json, days_json, interval_hours, anchor_time, " +
            "reminder_enabled, active_from_ms, active_until_ms, created_ms, updated_ms"
        const val LOG_COLUMNS = "id, medication_id, schedule_id, scheduled_at_ms, status, taken_at_ms, snoozed_until_ms, " +
            "dose_quantity, dose_unit, note, created_ms, updated_ms"
    }
}
