package com.ayuvo.health.vitals.storage

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteStatement
import com.ayuvo.health.data.health.HealthDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

/**
 * Camera vitals scans in `ayuvo_health.db` v4 (docs/camera-vitals.md §7). Scans live only in the `vital_*` tables:
 * they are never written to `health_samples`, never rolled up and never sent to Health Connect, so they cannot leak
 * into charts or the platform store.
 *
 * Writes run in one transaction on `Dispatchers.IO`. SQLite 3.18 (minSdk 26) has no UPSERT, so upserts are
 * UPDATE-then-INSERT like the rest of the health database. Deletions are tombstones (`deleted = 1`); a tombstoned id is
 * never resurrected by a later save. [revision] bumps after each committed write.
 */
class VitalScanRepository(
    private val helper: HealthDatabase,
    private val now: () -> Long = { System.currentTimeMillis() }
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

    // -- Scans ------------------------------------------------------------------------------------

    /**
     * Saves [record] and replaces its signal rows with [signals], in one transaction. Returns false (and writes
     * nothing) when the id is a tombstone.
     */
    suspend fun saveScan(record: VitalScanRecord, signals: List<VitalSignal> = emptyList()): Boolean = withContext(Dispatchers.IO) {
        require(record.mode == VitalScanRecord.MODE_FINGER || record.mode == VitalScanRecord.MODE_FACE) { "unknown mode ${record.mode}" }
        require(signals.map { it.kind }.toSet().size == signals.size) { "duplicate signal kind" }
        write { database ->
            val update = database.compileStatement(
                "UPDATE vital_scans SET $SCAN_SET WHERE id = ? AND deleted = 0"
            )
            bindScan(update, record)
            update.bindString(SCAN_COLUMNS.size, record.id)
            val saved = if (update.executeUpdateDelete() > 0) {
                true
            } else if (exists(database, "SELECT COUNT(*) FROM vital_scans WHERE id = ?", record.id)) {
                false
            } else {
                val insert = database.compileStatement(
                    "INSERT INTO vital_scans(${SCAN_COLUMNS.joinToString(", ")}) VALUES (${SCAN_COLUMNS.joinToString(", ") { "?" }})"
                )
                insert.bindString(1, record.id)
                bindScan(insert, record, offset = 1)
                insert.executeInsert()
                true
            }
            if (saved) {
                database.delete("vital_scan_signals", "scan_id = ?", arrayOf(record.id))
                val insert = database.compileStatement(
                    "INSERT INTO vital_scan_signals(scan_id, kind, sample_rate, encoding, data, meta_json) VALUES (?, ?, ?, ?, ?, ?)"
                )
                for (s in signals) {
                    insert.clearBindings()
                    insert.bindString(1, record.id)
                    insert.bindString(2, s.kind)
                    if (s.sampleRate == null) insert.bindNull(3) else insert.bindDouble(3, s.sampleRate)
                    insert.bindString(4, s.encoding)
                    insert.bindBlob(5, s.data)
                    if (s.metaJson == null) insert.bindNull(6) else insert.bindString(6, s.metaJson)
                    insert.executeInsert()
                }
            }
            saved
        }
    }

    /** Scans whose start is in `[fromMs, toMs)`, newest first; [mode] null = both modes. */
    suspend fun scans(mode: String?, fromMs: Long, toMs: Long, includeDeleted: Boolean = false): List<VitalScanRecord> = withContext(Dispatchers.IO) {
        val where = StringBuilder("start_ms >= ? AND start_ms < ?")
        val args = mutableListOf(fromMs.toString(), toMs.toString())
        if (mode != null) {
            where.append(" AND mode = ?")
            args += mode
        }
        if (!includeDeleted) where.append(" AND deleted = 0")
        db.rawQuery(
            "SELECT ${SCAN_SELECT} FROM vital_scans WHERE $where ORDER BY start_ms DESC, id DESC", args.toTypedArray()
        ).use { c -> buildList { while (c.moveToNext()) add(readScan(c)) } }
    }

    /** The row of [id], tombstoned or not; null when there is none. */
    suspend fun scan(id: String): VitalScanRecord? = withContext(Dispatchers.IO) {
        db.rawQuery("SELECT $SCAN_SELECT FROM vital_scans WHERE id = ?", arrayOf(id)).use { c -> if (c.moveToFirst()) readScan(c) else null }
    }

    suspend fun signals(scanId: String): List<VitalSignal> = withContext(Dispatchers.IO) {
        db.rawQuery(
            "SELECT kind, sample_rate, encoding, data, meta_json FROM vital_scan_signals WHERE scan_id = ? ORDER BY kind", arrayOf(scanId)
        ).use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(VitalSignal(c.getString(0), c.doubleOrNull(1), c.getString(2), c.getBlob(3), c.stringOrNull(4)))
                }
            }
        }
    }

    /** Sets (or clears, with null) the user's reference readings of a live scan. */
    suspend fun updateReference(id: String, referenceJson: String?): Boolean = withContext(Dispatchers.IO) {
        write { database ->
            val s = database.compileStatement("UPDATE vital_scans SET reference_json = ?, updated_ms = MAX(updated_ms, ?) WHERE id = ? AND deleted = 0")
            if (referenceJson == null) s.bindNull(1) else s.bindString(1, referenceJson)
            s.bindLong(2, now())
            s.bindString(3, id)
            s.executeUpdateDelete() > 0
        }
    }

    /**
     * Tombstones a scan and deletes its signal rows. For privacy the tombstone keeps no measurement: `results_json`
     * and `quality_json` become `{}` and `reference_json` becomes NULL.
     */
    suspend fun deleteScan(id: String): Boolean = withContext(Dispatchers.IO) {
        write { database ->
            val s = database.compileStatement(
                "UPDATE vital_scans SET deleted = 1, $BLANK_SET, updated_ms = MAX(updated_ms, ?) WHERE id = ? AND deleted = 0"
            )
            s.bindLong(1, now())
            s.bindString(2, id)
            val changed = s.executeUpdateDelete()
            database.delete("vital_scan_signals", "scan_id = ?", arrayOf(id))
            changed > 0
        }
    }

    /** Settings "Delete all camera scans": tombstones (and blanks, like [deleteScan]) every scan and deletes every signal row. Returns the count. */
    suspend fun deleteAllScans(): Int = withContext(Dispatchers.IO) {
        write { database ->
            val s = database.compileStatement("UPDATE vital_scans SET deleted = 1, $BLANK_SET, updated_ms = MAX(updated_ms, ?) WHERE deleted = 0")
            s.bindLong(1, now())
            val changed = s.executeUpdateDelete()
            database.delete("vital_scan_signals", null, null)
            changed
        }
    }

    // -- Calibrations -----------------------------------------------------------------------------

    suspend fun saveCalibration(cal: VitalCalibration): Boolean = withContext(Dispatchers.IO) {
        require(cal.kind == VitalCalibration.KIND_SPO2 || cal.kind == VitalCalibration.KIND_BP) { "unknown calibration kind ${cal.kind}" }
        write { database ->
            val update = database.compileStatement(
                "UPDATE vital_calibrations SET kind = ?, device_model = ?, scan_id = ?, t_ms = ?, reference_json = ?, features_json = ?, " +
                    "updated_ms = ? WHERE id = ? AND deleted = 0"
            )
            bindCalibration(update, cal, 0)
            update.bindString(8, cal.id)
            if (update.executeUpdateDelete() > 0) {
                true
            } else if (exists(database, "SELECT COUNT(*) FROM vital_calibrations WHERE id = ?", cal.id)) {
                false
            } else {
                val insert = database.compileStatement(
                    "INSERT INTO vital_calibrations(id, kind, device_model, scan_id, t_ms, reference_json, features_json, updated_ms, deleted) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, 0)"
                )
                insert.bindString(1, cal.id)
                bindCalibration(insert, cal, 1)
                insert.executeInsert()
                true
            }
        }
    }

    /** Live calibrations of [kind] for [deviceModel], oldest first. */
    suspend fun calibrations(kind: String, deviceModel: String): List<VitalCalibration> = withContext(Dispatchers.IO) {
        db.rawQuery(
            "SELECT id, kind, device_model, scan_id, t_ms, reference_json, features_json, deleted, updated_ms FROM vital_calibrations " +
                "WHERE kind = ? AND device_model = ? AND deleted = 0 ORDER BY t_ms, id",
            arrayOf(kind, deviceModel)
        ).use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(
                        VitalCalibration(
                            id = c.getString(0), kind = c.getString(1), deviceModel = c.getString(2), scanId = c.stringOrNull(3),
                            tMs = c.getLong(4), referenceJson = c.getString(5), featuresJson = c.getString(6),
                            deleted = c.getInt(7) != 0, updatedMs = c.getLong(8)
                        )
                    )
                }
            }
        }
    }

    suspend fun deleteCalibration(id: String): Boolean = withContext(Dispatchers.IO) {
        write { database ->
            val s = database.compileStatement("UPDATE vital_calibrations SET deleted = 1, updated_ms = MAX(updated_ms, ?) WHERE id = ? AND deleted = 0")
            s.bindLong(1, now())
            s.bindString(2, id)
            s.executeUpdateDelete() > 0
        }
    }

    // -- Device profiles --------------------------------------------------------------------------

    suspend fun upsertDeviceProfile(profile: VitalDeviceProfile) = withContext(Dispatchers.IO) {
        write { database ->
            val update = database.compileStatement(
                "UPDATE vital_device_profiles SET capability_json = ?, updated_ms = ? WHERE device_model = ? AND camera_position = ?"
            )
            update.bindString(1, profile.capabilityJson)
            update.bindLong(2, profile.updatedMs)
            update.bindString(3, profile.deviceModel)
            update.bindString(4, profile.cameraPosition)
            if (update.executeUpdateDelete() == 0) {
                val insert = database.compileStatement(
                    "INSERT INTO vital_device_profiles(device_model, camera_position, capability_json, updated_ms) VALUES (?, ?, ?, ?)"
                )
                insert.bindString(1, profile.deviceModel)
                insert.bindString(2, profile.cameraPosition)
                insert.bindString(3, profile.capabilityJson)
                insert.bindLong(4, profile.updatedMs)
                insert.executeInsert()
            }
            Unit
        }
    }

    suspend fun deviceProfile(deviceModel: String, cameraPosition: String): VitalDeviceProfile? = withContext(Dispatchers.IO) {
        db.rawQuery(
            "SELECT device_model, camera_position, capability_json, updated_ms FROM vital_device_profiles WHERE device_model = ? AND camera_position = ?",
            arrayOf(deviceModel, cameraPosition)
        ).use { c -> if (c.moveToFirst()) VitalDeviceProfile(c.getString(0), c.getString(1), c.getString(2), c.getLong(3)) else null }
    }

    /** Every live calibration of [kind] on any device model (BP calibrations are per user, not per phone), oldest first. */
    suspend fun calibrationsOfKind(kind: String): List<VitalCalibration> = withContext(Dispatchers.IO) {
        db.rawQuery("SELECT $CAL_SELECT FROM vital_calibrations WHERE kind = ? AND deleted = 0 ORDER BY t_ms, id", arrayOf(kind))
            .use { c -> buildList { while (c.moveToNext()) add(readCalibration(c)) } }
    }

    /** Every live calibration, oldest first (calibration screen, export). */
    suspend fun allCalibrations(): List<VitalCalibration> = withContext(Dispatchers.IO) {
        db.rawQuery("SELECT $CAL_SELECT FROM vital_calibrations WHERE deleted = 0 ORDER BY t_ms, id", null)
            .use { c -> buildList { while (c.moveToNext()) add(readCalibration(c)) } }
    }

    /** Live scans sharing a compare [sessionId], oldest first. */
    suspend fun scansInSession(sessionId: String): List<VitalScanRecord> = withContext(Dispatchers.IO) {
        db.rawQuery("SELECT $SCAN_SELECT FROM vital_scans WHERE session_id = ? AND deleted = 0 ORDER BY start_ms, id", arrayOf(sessionId))
            .use { c -> buildList { while (c.moveToNext()) add(readScan(c)) } }
    }

    suspend fun deviceProfiles(): List<VitalDeviceProfile> = withContext(Dispatchers.IO) {
        db.rawQuery("SELECT device_model, camera_position, capability_json, updated_ms FROM vital_device_profiles ORDER BY device_model, camera_position", null)
            .use { c -> buildList { while (c.moveToNext()) add(VitalDeviceProfile(c.getString(0), c.getString(1), c.getString(2), c.getLong(3))) } }
    }

    // -- Export / restore (docs/camera-vitals.md §7.2) --------------------------------------------

    /**
     * The `camera_vitals` section: live scans (oldest first), their signals when [includeSignals], live calibrations and
     * device profiles. Deleted scans and calibrations are never exported.
     */
    suspend fun exportBundle(includeSignals: Boolean): CameraVitalsArchive.Bundle = withContext(Dispatchers.IO) {
        val scans = db.rawQuery("SELECT $SCAN_SELECT FROM vital_scans WHERE deleted = 0 ORDER BY start_ms, id", null)
            .use { c -> buildList { while (c.moveToNext()) add(readScan(c)) } }
        CameraVitalsArchive.Bundle(
            scans = scans.map { CameraVitalsArchive.ScanWithSignals(it, if (includeSignals) signals(it.id) else emptyList()) },
            calibrations = allCalibrations(),
            deviceProfiles = deviceProfiles()
        )
    }

    /**
     * Applies the §7.2 merge in one transaction: insert by id; an id that exists locally (live or tombstoned) is
     * skipped with its signals; device profiles merge by key and the newer `updated` wins. Nothing goes to Health.
     */
    suspend fun importArchive(bundle: CameraVitalsArchive.Bundle): CameraVitalsArchive.ImportResult = withContext(Dispatchers.IO) {
        write { database ->
            fun ids(sql: String): Set<String> = database.rawQuery(sql, null).use { c -> buildSet { while (c.moveToNext()) add(c.getString(0)) } }
            val scanIds = ids("SELECT id FROM vital_scans")
            val calIds = ids("SELECT id FROM vital_calibrations")
            val profiles = database.rawQuery("SELECT device_model, camera_position, updated_ms FROM vital_device_profiles", null).use { c ->
                buildMap { while (c.moveToNext()) put(CameraVitalsArchive.profileKey(c.getString(0), c.getString(1)), c.getLong(2)) }
            }
            val plan = CameraVitalsArchive.mergePlan(bundle, scanIds, calIds, profiles)
            val insertScan = database.compileStatement(
                "INSERT INTO vital_scans(${SCAN_COLUMNS.joinToString(", ")}) VALUES (${SCAN_COLUMNS.joinToString(", ") { "?" }})"
            )
            val insertSignal = database.compileStatement(
                "INSERT INTO vital_scan_signals(scan_id, kind, sample_rate, encoding, data, meta_json) VALUES (?, ?, ?, ?, ?, ?)"
            )
            var signalCount = 0
            for (s in plan.scans) {
                insertScan.clearBindings()
                insertScan.bindString(1, s.record.id)
                bindScan(insertScan, s.record.copy(deleted = false), offset = 1)
                insertScan.executeInsert()
                for (sig in s.signals) {
                    insertSignal.clearBindings()
                    insertSignal.bindString(1, s.record.id)
                    insertSignal.bindString(2, sig.kind)
                    if (sig.sampleRate == null) insertSignal.bindNull(3) else insertSignal.bindDouble(3, sig.sampleRate)
                    insertSignal.bindString(4, sig.encoding)
                    insertSignal.bindBlob(5, sig.data)
                    if (sig.metaJson == null) insertSignal.bindNull(6) else insertSignal.bindString(6, sig.metaJson)
                    insertSignal.executeInsert()
                    signalCount++
                }
            }
            val insertCal = database.compileStatement(
                "INSERT INTO vital_calibrations(id, kind, device_model, scan_id, t_ms, reference_json, features_json, updated_ms, deleted) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, 0)"
            )
            for (c in plan.calibrations) {
                insertCal.clearBindings()
                insertCal.bindString(1, c.id)
                bindCalibration(insertCal, c, 1)
                insertCal.executeInsert()
            }
            for (p in plan.deviceProfiles) {
                database.delete("vital_device_profiles", "device_model = ? AND camera_position = ?", arrayOf(p.deviceModel, p.cameraPosition))
                val insert = database.compileStatement(
                    "INSERT INTO vital_device_profiles(device_model, camera_position, capability_json, updated_ms) VALUES (?, ?, ?, ?)"
                )
                insert.bindString(1, p.deviceModel)
                insert.bindString(2, p.cameraPosition)
                insert.bindString(3, p.capabilityJson)
                insert.bindLong(4, p.updatedMs)
                insert.executeInsert()
            }
            CameraVitalsArchive.ImportResult(
                scans = plan.scans.size, signals = signalCount, calibrations = plan.calibrations.size,
                deviceProfiles = plan.deviceProfiles.size, skippedScans = plan.skippedScans, skippedCalibrations = plan.skippedCalibrations
            )
        }
    }

    // -- Row mapping ------------------------------------------------------------------------------

    private fun exists(database: SQLiteDatabase, sql: String, id: String): Boolean {
        val s = database.compileStatement(sql)
        s.bindString(1, id)
        return s.simpleQueryForLong() > 0
    }

    /** Binds every column after `id` starting at index [offset] + 1 (SCAN_COLUMNS order). */
    private fun bindScan(s: SQLiteStatement, r: VitalScanRecord, offset: Int = 0) {
        var i = offset
        s.bindString(++i, r.mode)
        s.bindNullable(++i, r.sessionId)
        s.bindLong(++i, r.startMs)
        s.bindLong(++i, r.endMs)
        s.bindLong(++i, r.tzOffsetS.toLong())
        s.bindString(++i, r.localDay)
        s.bindLong(++i, r.durationMs)
        s.bindString(++i, r.platform)
        s.bindString(++i, r.deviceModel)
        s.bindString(++i, r.cameraJson)
        s.bindString(++i, r.context)
        if (r.qualityScore == null) s.bindNull(++i) else s.bindDouble(++i, r.qualityScore)
        s.bindNullable(++i, r.rejectReason)
        s.bindString(++i, r.qualityJson)
        s.bindString(++i, r.resultsJson)
        s.bindLong(++i, r.algoVersion.toLong())
        s.bindNullable(++i, r.referenceJson)
        s.bindLong(++i, if (r.deleted) 1L else 0L)
        s.bindLong(++i, r.updatedMs)
    }

    private fun bindCalibration(s: SQLiteStatement, c: VitalCalibration, offset: Int) {
        var i = offset
        s.bindString(++i, c.kind)
        s.bindString(++i, c.deviceModel)
        s.bindNullable(++i, c.scanId)
        s.bindLong(++i, c.tMs)
        s.bindString(++i, c.referenceJson)
        s.bindString(++i, c.featuresJson)
        s.bindLong(++i, c.updatedMs)
    }

    private fun SQLiteStatement.bindNullable(index: Int, value: String?) {
        if (value == null) bindNull(index) else bindString(index, value)
    }

    private fun readCalibration(c: Cursor) = VitalCalibration(
        id = c.getString(0), kind = c.getString(1), deviceModel = c.getString(2), scanId = c.stringOrNull(3),
        tMs = c.getLong(4), referenceJson = c.getString(5), featuresJson = c.getString(6),
        deleted = c.getInt(7) != 0, updatedMs = c.getLong(8)
    )

    private fun readScan(c: Cursor) = VitalScanRecord(
        id = c.getString(0), mode = c.getString(1), sessionId = c.stringOrNull(2), startMs = c.getLong(3), endMs = c.getLong(4),
        tzOffsetS = c.getInt(5), localDay = c.getString(6), durationMs = c.getLong(7), platform = c.getString(8),
        deviceModel = c.getString(9), cameraJson = c.getString(10), context = c.getString(11), qualityScore = c.doubleOrNull(12),
        rejectReason = c.stringOrNull(13), qualityJson = c.getString(14), resultsJson = c.getString(15), algoVersion = c.getInt(16),
        referenceJson = c.stringOrNull(17), deleted = c.getInt(18) != 0, updatedMs = c.getLong(19)
    )

    private fun Cursor.stringOrNull(i: Int): String? = if (isNull(i)) null else getString(i)
    private fun Cursor.doubleOrNull(i: Int): Double? = if (isNull(i)) null else getDouble(i)

    companion object {
        /** `vital_scans` columns in schema order; `id` first. */
        val SCAN_COLUMNS = listOf(
            "id", "mode", "session_id", "start_ms", "end_ms", "tz_offset_s", "local_day", "duration_ms", "platform", "device_model",
            "camera_json", "context", "quality_score", "reject_reason", "quality_json", "results_json", "algo_version",
            "reference_json", "deleted", "updated_ms"
        )
        private val SCAN_SET = SCAN_COLUMNS.drop(1).joinToString(", ") { "$it = ?" }
        /** What a tombstone keeps of the measurement: nothing. */
        private const val BLANK_SET = "results_json = '{}', quality_json = '{}', reference_json = NULL"
        private val SCAN_SELECT = SCAN_COLUMNS.joinToString(", ")
        private const val CAL_SELECT = "id, kind, device_model, scan_id, t_ms, reference_json, features_json, deleted, updated_ms"
    }
}
