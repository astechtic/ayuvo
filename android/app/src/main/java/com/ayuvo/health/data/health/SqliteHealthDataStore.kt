package com.ayuvo.health.data.health

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteStatement
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

/**
 * [HealthDataStore] over [HealthDatabase]. Every write runs in one transaction on
 * `Dispatchers.IO`; [revision] bumps after each committed write.
 *
 * Upsert rule (shared with iOS): `UPDATE … WHERE id=? AND updated_ms<? AND deleted=0`,
 * then `INSERT` only when no row exists. Tombstones therefore always win over
 * re-imports, and older platform versions never overwrite newer ones.
 */
class SqliteHealthDataStore(private val helper: HealthDatabase) : HealthDataStore {

    private val _revision = MutableStateFlow(0L)
    override val revision: StateFlow<Long> = _revision

    private val db: SQLiteDatabase get() = helper.writableDatabase

    private fun bump() {
        _revision.value = _revision.value + 1
    }

    private inline fun <T> write(block: (SQLiteDatabase) -> T): T {
        val database = db
        database.beginTransactionNonExclusive()
        return try {
            val result = block(database)
            database.setTransactionSuccessful()
            result
        } finally {
            database.endTransaction()
            bump()
        }
    }

    // -- Samples -----------------------------------------------------------

    override suspend fun commit(page: HealthPageCommit): HealthPageCommitResult = withContext(Dispatchers.IO) {
        if (page.isEmpty) return@withContext HealthPageCommitResult(0, 0, 0)
        write { database ->
            var inserted = 0
            var updated = 0
            var tombstoned = 0
            val update = database.compileStatement(UPDATE_SAMPLE)
            val insert = database.compileStatement(INSERT_SAMPLE)
            val exists = database.compileStatement("SELECT COUNT(*) FROM health_samples WHERE id = ?")
            val deleteSeries = database.compileStatement("DELETE FROM health_series_points WHERE sample_id = ?")
            val insertPoint = database.compileStatement(
                "INSERT OR REPLACE INTO health_series_points(sample_id, type_id, t_ms, value) VALUES (?, ?, ?, ?)"
            )
            val pointsBySample = page.seriesPoints.groupBy { it.sampleId }
            for (row in page.rows) {
                bindSampleForUpdate(update, row)
                val changed = update.executeUpdateDelete()
                val wrote = if (changed > 0) {
                    updated++
                    true
                } else {
                    exists.bindString(1, row.id)
                    if (exists.simpleQueryForLong() == 0L) {
                        bindSampleForInsert(insert, row)
                        insert.executeInsert()
                        inserted++
                        true
                    } else {
                        false
                    }
                }
                if (wrote) {
                    deleteSeries.bindString(1, row.id)
                    deleteSeries.executeUpdateDelete()
                    pointsBySample[row.id]?.forEach { point ->
                        insertPoint.bindString(1, point.sampleId)
                        insertPoint.bindString(2, point.typeId)
                        insertPoint.bindLong(3, point.tMs)
                        insertPoint.bindDouble(4, point.value)
                        insertPoint.executeInsert()
                    }
                }
            }
            if (page.deletedIds.isNotEmpty() && page.deleteOrigins.isNotEmpty()) {
                val originClause = "origin IN (" + page.deleteOrigins.joinToString(",") { it.toString() } + ")"
                val tombstone = database.compileStatement(
                    "UPDATE health_samples SET deleted = 1, updated_ms = MAX(updated_ms, ?) WHERE id = ? AND deleted = 0 AND $originClause"
                )
                val now = System.currentTimeMillis()
                for (id in page.deletedIds) {
                    tombstone.bindLong(1, now)
                    tombstone.bindString(2, id)
                    tombstoned += tombstone.executeUpdateDelete()
                }
            }
            upsertSourcesIn(database, page.sources)
            page.syncStates.forEach { putSyncStateIn(database, it) }
            HealthPageCommitResult(inserted, updated, tombstoned)
        }
    }

    override suspend fun samplesBetween(typeId: String, fromMs: Long, toMs: Long, includeDeleted: Boolean): List<HealthSampleRow> =
        withContext(Dispatchers.IO) {
            val deletedClause = if (includeDeleted) "" else " AND deleted = 0"
            db.rawQuery(
                "SELECT $SAMPLE_COLUMNS FROM health_samples WHERE type_id = ? AND end_ms >= ? AND start_ms <= ?$deletedClause ORDER BY start_ms ASC, id ASC",
                arrayOf(typeId, fromMs.toString(), toMs.toString())
            ).use { it.readAll(::readSample) }
        }

    override suspend fun samplesByIds(ids: Collection<String>): List<HealthSampleRow> = withContext(Dispatchers.IO) {
        if (ids.isEmpty()) return@withContext emptyList()
        val out = mutableListOf<HealthSampleRow>()
        ids.chunked(500).forEach { chunk ->
            val placeholders = chunk.joinToString(",") { "?" }
            db.rawQuery(
                "SELECT $SAMPLE_COLUMNS FROM health_samples WHERE id IN ($placeholders)",
                chunk.toTypedArray()
            ).use { out += it.readAll(::readSample) }
        }
        out
    }

    override suspend fun samplesPage(typeId: String, beforeEndMs: Long?, beforeId: String?, limit: Int): List<HealthSampleRow> =
        withContext(Dispatchers.IO) {
            val sql = if (beforeEndMs == null || beforeId == null) {
                "SELECT $SAMPLE_COLUMNS FROM health_samples WHERE type_id = ? AND deleted = 0 ORDER BY end_ms DESC, id DESC LIMIT ?"
            } else {
                "SELECT $SAMPLE_COLUMNS FROM health_samples WHERE type_id = ? AND deleted = 0 AND (end_ms < ? OR (end_ms = ? AND id < ?)) ORDER BY end_ms DESC, id DESC LIMIT ?"
            }
            val args = if (beforeEndMs == null || beforeId == null) {
                arrayOf(typeId, limit.toString())
            } else {
                arrayOf(typeId, beforeEndMs.toString(), beforeEndMs.toString(), beforeId, limit.toString())
            }
            db.rawQuery(sql, args).use { it.readAll(::readSample) }
        }

    override suspend fun latestSample(typeId: String): HealthSampleRow? = withContext(Dispatchers.IO) {
        db.rawQuery(
            "SELECT $SAMPLE_COLUMNS FROM health_samples WHERE type_id = ? AND deleted = 0 ORDER BY end_ms DESC, id DESC LIMIT 1",
            arrayOf(typeId)
        ).use { it.readAll(::readSample).firstOrNull() }
    }

    override suspend fun sampleCount(typeId: String): Long = withContext(Dispatchers.IO) {
        db.compileStatement("SELECT COUNT(*) FROM health_samples WHERE type_id = ? AND deleted = 0").use {
            it.bindString(1, typeId)
            it.simpleQueryForLong()
        }
    }

    override suspend fun sourceCounts(typeId: String): Map<String, Int> = withContext(Dispatchers.IO) {
        val out = LinkedHashMap<String, Int>()
        db.rawQuery(
            "SELECT source_id, COUNT(*) FROM health_samples WHERE type_id = ? AND deleted = 0 GROUP BY source_id ORDER BY COUNT(*) DESC",
            arrayOf(typeId)
        ).use { c ->
            while (c.moveToNext()) out[c.getString(0)] = c.getInt(1)
        }
        out
    }

    override suspend fun typeSummaries(): List<HealthTypeSummary> = withContext(Dispatchers.IO) {
        val series = mutableMapOf<String, Long>()
        db.rawQuery("SELECT type_id, COUNT(*) FROM health_series_points GROUP BY type_id", null).use { c ->
            while (c.moveToNext()) series[c.getString(0)] = c.getLong(1)
        }
        db.rawQuery(
            "SELECT type_id, COUNT(*), MIN(start_ms), MAX(end_ms) FROM health_samples WHERE deleted = 0 GROUP BY type_id",
            null
        ).use { c ->
            c.readAll { cursor ->
                val typeId = cursor.getString(0)
                HealthTypeSummary(
                    typeId = typeId,
                    count = cursor.getLong(1),
                    firstMs = cursor.getLongOrNull(2),
                    lastMs = cursor.getLongOrNull(3),
                    seriesCount = series[typeId] ?: 0L
                )
            }
        }
    }

    override suspend fun forEachExportRow(exportedTypeIds: Set<String>, onRow: (HealthSampleRow) -> Unit) =
        withContext(Dispatchers.IO) {
            db.rawQuery(
                "SELECT $SAMPLE_COLUMNS FROM health_samples WHERE deleted = 0 ORDER BY type_id ASC, end_ms ASC, id ASC",
                null
            ).use { c ->
                while (c.moveToNext()) {
                    val row = readSample(c)
                    if (row.typeId in exportedTypeIds) onRow(row)
                }
            }
        }

    override suspend fun forEachSeriesPoint(onPoint: (HealthSeriesPoint) -> Unit) = withContext(Dispatchers.IO) {
        db.rawQuery(
            "SELECT p.sample_id, p.type_id, p.t_ms, p.value FROM health_series_points p JOIN health_samples s ON s.id = p.sample_id WHERE s.deleted = 0 ORDER BY p.type_id, p.sample_id, p.t_ms",
            null
        ).use { c ->
            while (c.moveToNext()) {
                onPoint(HealthSeriesPoint(c.getString(0), c.getString(1), c.getLong(2), c.getDouble(3)))
            }
        }
    }

    // -- Series ------------------------------------------------------------

    override suspend fun seriesPoints(typeId: String, fromMs: Long, toMs: Long): List<HealthSeriesPoint> =
        withContext(Dispatchers.IO) {
            db.rawQuery(
                "SELECT p.sample_id, p.type_id, p.t_ms, p.value FROM health_series_points p JOIN health_samples s ON s.id = p.sample_id WHERE p.type_id = ? AND p.t_ms >= ? AND p.t_ms <= ? AND s.deleted = 0 ORDER BY p.t_ms ASC",
                arrayOf(typeId, fromMs.toString(), toMs.toString())
            ).use { c ->
                c.readAll { HealthSeriesPoint(it.getString(0), it.getString(1), it.getLong(2), it.getDouble(3)) }
            }
        }

    override suspend fun upsertSeriesPoints(points: List<HealthSeriesPoint>): Int = withContext(Dispatchers.IO) {
        if (points.isEmpty()) return@withContext 0
        write { database ->
            val exists = database.compileStatement("SELECT COUNT(*) FROM health_samples WHERE id = ?")
            val insert = database.compileStatement(
                "INSERT OR REPLACE INTO health_series_points(sample_id, type_id, t_ms, value) VALUES (?, ?, ?, ?)"
            )
            var written = 0
            val known = HashMap<String, Boolean>()
            for (p in points) {
                val ok = known.getOrPut(p.sampleId) {
                    exists.bindString(1, p.sampleId)
                    exists.simpleQueryForLong() > 0
                }
                if (!ok) continue
                insert.bindString(1, p.sampleId)
                insert.bindString(2, p.typeId)
                insert.bindLong(3, p.tMs)
                insert.bindDouble(4, p.value)
                insert.executeInsert()
                written++
            }
            written
        }
    }

    override suspend fun pruneSeriesBefore(cutoffMs: Long): Int = withContext(Dispatchers.IO) {
        write { database ->
            database.compileStatement("DELETE FROM health_series_points WHERE t_ms < ?").use {
                it.bindLong(1, cutoffMs)
                it.executeUpdateDelete()
            }
        }
    }

    // -- Roll-ups ----------------------------------------------------------

    override suspend fun dailyRollups(typeId: String, fromDay: String, toDay: String): List<HealthDailyRollup> =
        withContext(Dispatchers.IO) {
            db.rawQuery(
                "SELECT type_id, day, tz, sum, avg, min, max, count, last_value, last_at_ms, v2_avg, v2_min, v2_max, duration_s, own_sum, from_platform_aggregate FROM health_daily_rollups WHERE type_id = ? AND day >= ? AND day <= ? ORDER BY day ASC",
                arrayOf(typeId, fromDay, toDay)
            ).use { c ->
                c.readAll { cursor ->
                    HealthDailyRollup(
                        typeId = cursor.getString(0),
                        day = cursor.getString(1),
                        tz = cursor.getString(2),
                        sum = cursor.getDoubleOrNull(3),
                        avg = cursor.getDoubleOrNull(4),
                        min = cursor.getDoubleOrNull(5),
                        max = cursor.getDoubleOrNull(6),
                        count = cursor.getInt(7),
                        lastValue = cursor.getDoubleOrNull(8),
                        lastAtMs = cursor.getLongOrNull(9),
                        v2Avg = cursor.getDoubleOrNull(10),
                        v2Min = cursor.getDoubleOrNull(11),
                        v2Max = cursor.getDoubleOrNull(12),
                        durationS = cursor.getDoubleOrNull(13),
                        ownSum = cursor.getDoubleOrNull(14),
                        fromPlatformAggregate = cursor.getInt(15) == 1
                    )
                }
            }
        }

    override suspend fun replaceDailyRollups(typeId: String, days: Collection<String>, rows: List<HealthDailyRollup>) =
        withContext(Dispatchers.IO) {
            write { database ->
                val delete = database.compileStatement("DELETE FROM health_daily_rollups WHERE type_id = ? AND day = ?")
                for (day in days) {
                    delete.bindString(1, typeId)
                    delete.bindString(2, day)
                    delete.executeUpdateDelete()
                }
                val insert = database.compileStatement(
                    "INSERT OR REPLACE INTO health_daily_rollups(type_id, day, tz, sum, avg, min, max, count, last_value, last_at_ms, v2_avg, v2_min, v2_max, duration_s, own_sum, from_platform_aggregate) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)"
                )
                for (row in rows) {
                    insert.bindString(1, row.typeId)
                    insert.bindString(2, row.day)
                    insert.bindString(3, row.tz)
                    insert.bindDoubleOrNull(4, row.sum)
                    insert.bindDoubleOrNull(5, row.avg)
                    insert.bindDoubleOrNull(6, row.min)
                    insert.bindDoubleOrNull(7, row.max)
                    insert.bindLong(8, row.count.toLong())
                    insert.bindDoubleOrNull(9, row.lastValue)
                    insert.bindLongOrNull(10, row.lastAtMs)
                    insert.bindDoubleOrNull(11, row.v2Avg)
                    insert.bindDoubleOrNull(12, row.v2Min)
                    insert.bindDoubleOrNull(13, row.v2Max)
                    insert.bindDoubleOrNull(14, row.durationS)
                    insert.bindDoubleOrNull(15, row.ownSum)
                    insert.bindLong(16, if (row.fromPlatformAggregate) 1L else 0L)
                    insert.executeInsert()
                }
            }
            Unit
        }

    override suspend fun hourlyRollups(typeId: String, day: String): List<HealthHourlyRollup> = withContext(Dispatchers.IO) {
        db.rawQuery(
            "SELECT type_id, day, hour, sum, avg, min, max, count FROM health_hourly_rollups WHERE type_id = ? AND day = ? ORDER BY hour ASC",
            arrayOf(typeId, day)
        ).use { c ->
            c.readAll { cursor ->
                HealthHourlyRollup(
                    typeId = cursor.getString(0),
                    day = cursor.getString(1),
                    hour = cursor.getInt(2),
                    sum = cursor.getDoubleOrNull(3),
                    avg = cursor.getDoubleOrNull(4),
                    min = cursor.getDoubleOrNull(5),
                    max = cursor.getDoubleOrNull(6),
                    count = cursor.getInt(7)
                )
            }
        }
    }

    override suspend fun replaceHourlyRollups(typeId: String, day: String, rows: List<HealthHourlyRollup>) =
        withContext(Dispatchers.IO) {
            write { database ->
                database.compileStatement("DELETE FROM health_hourly_rollups WHERE type_id = ? AND day = ?").use {
                    it.bindString(1, typeId)
                    it.bindString(2, day)
                    it.executeUpdateDelete()
                }
                val insert = database.compileStatement(
                    "INSERT OR REPLACE INTO health_hourly_rollups(type_id, day, hour, sum, avg, min, max, count) VALUES (?,?,?,?,?,?,?,?)"
                )
                for (row in rows) {
                    insert.bindString(1, row.typeId)
                    insert.bindString(2, row.day)
                    insert.bindLong(3, row.hour.toLong())
                    insert.bindDoubleOrNull(4, row.sum)
                    insert.bindDoubleOrNull(5, row.avg)
                    insert.bindDoubleOrNull(6, row.min)
                    insert.bindDoubleOrNull(7, row.max)
                    insert.bindLong(8, row.count.toLong())
                    insert.executeInsert()
                }
            }
            Unit
        }

    override suspend fun deleteRollupsFor(typeIds: Collection<String>) = withContext(Dispatchers.IO) {
        if (typeIds.isEmpty()) return@withContext
        write { database ->
            for (typeId in typeIds) {
                database.delete("health_daily_rollups", "type_id = ?", arrayOf(typeId))
                database.delete("health_hourly_rollups", "type_id = ?", arrayOf(typeId))
            }
        }
        Unit
    }

    // -- Sync state --------------------------------------------------------

    override suspend fun syncStates(): List<HealthSyncState> = withContext(Dispatchers.IO) {
        db.rawQuery("SELECT $SYNC_COLUMNS FROM health_sync_state", null).use { it.readAll(::readSyncState) }
    }

    override suspend fun syncState(typeId: String): HealthSyncState? = withContext(Dispatchers.IO) {
        db.rawQuery("SELECT $SYNC_COLUMNS FROM health_sync_state WHERE type_id = ?", arrayOf(typeId))
            .use { it.readAll(::readSyncState).firstOrNull() }
    }

    override suspend fun putSyncState(state: HealthSyncState) = withContext(Dispatchers.IO) {
        write { database -> putSyncStateIn(database, state) }
        Unit
    }

    override suspend fun clearSyncState(typeIds: Collection<String>) = withContext(Dispatchers.IO) {
        if (typeIds.isEmpty()) return@withContext
        write { database ->
            typeIds.forEach { database.delete("health_sync_state", "type_id = ?", arrayOf(it)) }
        }
        Unit
    }

    private fun putSyncStateIn(database: SQLiteDatabase, state: HealthSyncState) {
        database.compileStatement(
            "INSERT OR REPLACE INTO health_sync_state(type_id, cursor, cursor_issued_ms, last_sync_ms, earliest_authorized_ms, earliest_probe_ms, backfill_floor_ms, oldest_backfilled_ms, backfill_done, backfill_with_history, status, last_error, last_error_ms, ipc_calls_total) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)"
        ).use { s ->
            s.bindString(1, state.typeId)
            s.bindStringOrNull(2, state.cursor)
            s.bindLongOrNull(3, state.cursorIssuedMs)
            s.bindLongOrNull(4, state.lastSyncMs)
            s.bindLongOrNull(5, state.earliestAuthorizedMs)
            s.bindLongOrNull(6, state.earliestProbeMs)
            s.bindLongOrNull(7, state.backfillFloorMs)
            s.bindLongOrNull(8, state.oldestBackfilledMs)
            s.bindLong(9, if (state.backfillDone) 1L else 0L)
            s.bindLong(10, if (state.backfillWithHistory) 1L else 0L)
            s.bindString(11, state.status)
            s.bindStringOrNull(12, state.lastError)
            s.bindLongOrNull(13, state.lastErrorMs)
            s.bindLong(14, state.ipcCallsTotal)
            s.executeInsert()
        }
    }

    // -- Sources / meta ----------------------------------------------------

    override suspend fun sources(): List<HealthSourceRow> = withContext(Dispatchers.IO) {
        db.rawQuery("SELECT id, name, device_model, device_type, last_seen_ms FROM health_sources ORDER BY name", null).use { c ->
            c.readAll { HealthSourceRow(it.getString(0), it.getString(1), it.getStringOrNull(2), it.getIntOrNull(3), it.getLongOrNull(4)) }
        }
    }

    override suspend fun upsertSources(sources: List<HealthSourceRow>) = withContext(Dispatchers.IO) {
        if (sources.isEmpty()) return@withContext
        write { database -> upsertSourcesIn(database, sources) }
        Unit
    }

    private fun upsertSourcesIn(database: SQLiteDatabase, sources: List<HealthSourceRow>) {
        if (sources.isEmpty()) return
        val update = database.compileStatement(
            "UPDATE health_sources SET name = ?, device_model = COALESCE(?, device_model), device_type = COALESCE(?, device_type), last_seen_ms = MAX(COALESCE(last_seen_ms, 0), COALESCE(?, 0)) WHERE id = ?"
        )
        val insert = database.compileStatement(
            "INSERT INTO health_sources(id, name, device_model, device_type, last_seen_ms) VALUES (?,?,?,?,?)"
        )
        for (source in sources) {
            update.bindString(1, source.name)
            update.bindStringOrNull(2, source.deviceModel)
            update.bindLongOrNull(3, source.deviceType?.toLong())
            update.bindLongOrNull(4, source.lastSeenMs)
            update.bindString(5, source.id)
            if (update.executeUpdateDelete() == 0) {
                insert.bindString(1, source.id)
                insert.bindString(2, source.name)
                insert.bindStringOrNull(3, source.deviceModel)
                insert.bindLongOrNull(4, source.deviceType?.toLong())
                insert.bindLongOrNull(5, source.lastSeenMs)
                insert.executeInsert()
            }
        }
    }

    override suspend fun typeMeta(): List<HealthTypeMeta> = withContext(Dispatchers.IO) {
        db.rawQuery("SELECT type_id, category, kind, aggregation, unit, display_name, platform, native_id FROM health_type_meta", null).use { c ->
            c.readAll {
                HealthTypeMeta(it.getString(0), it.getString(1), it.getString(2), it.getString(3), it.getString(4), it.getStringOrNull(5), it.getStringOrNull(6), it.getStringOrNull(7))
            }
        }
    }

    override suspend fun upsertTypeMeta(meta: List<HealthTypeMeta>) = withContext(Dispatchers.IO) {
        if (meta.isEmpty()) return@withContext
        write { database ->
            val insert = database.compileStatement(
                "INSERT OR REPLACE INTO health_type_meta(type_id, category, kind, aggregation, unit, display_name, platform, native_id) VALUES (?,?,?,?,?,?,?,?)"
            )
            for (m in meta) {
                insert.bindString(1, m.typeId)
                insert.bindString(2, m.category)
                insert.bindString(3, m.kind)
                insert.bindString(4, m.aggregation)
                insert.bindString(5, m.unit)
                insert.bindStringOrNull(6, m.displayName)
                insert.bindStringOrNull(7, m.platform)
                insert.bindStringOrNull(8, m.nativeId)
                insert.executeInsert()
            }
        }
        Unit
    }

    override suspend fun meta(key: String): String? = withContext(Dispatchers.IO) {
        db.rawQuery("SELECT value FROM health_meta WHERE key = ?", arrayOf(key)).use { c ->
            if (c.moveToFirst()) c.getStringOrNull(0) else null
        }
    }

    override suspend fun setMeta(key: String, value: String?) = withContext(Dispatchers.IO) {
        write { database ->
            if (value == null) {
                database.delete("health_meta", "key = ?", arrayOf(key))
            } else {
                database.execSQL("INSERT OR REPLACE INTO health_meta(key, value) VALUES (?, ?)", arrayOf(key, value))
            }
        }
        Unit
    }

    // -- Maintenance -------------------------------------------------------

    override suspend fun storageBytes(): Long = withContext(Dispatchers.IO) {
        helper.files().sumOf { if (it.exists()) it.length() else 0L }
    }

    override suspend fun deleteAll() = withContext(Dispatchers.IO) {
        write { database ->
            HealthDatabase.TABLES.forEach { table ->
                if (table != "health_meta") database.delete(table, null, null)
            }
        }
        Unit
    }

    override fun close() {
        runCatching { helper.close() }
    }

    // -- Row mapping -------------------------------------------------------

    private fun bindSampleForUpdate(s: SQLiteStatement, row: HealthSampleRow) {
        s.clearBindings()
        s.bindString(1, row.typeId)
        s.bindLong(2, row.startMs)
        s.bindLong(3, row.endMs)
        s.bindLongOrNull(4, row.startOffsetS?.toLong())
        s.bindLongOrNull(5, row.endOffsetS?.toLong())
        s.bindString(6, row.localDay)
        s.bindDoubleOrNull(7, row.value)
        s.bindDoubleOrNull(8, row.value2)
        s.bindDoubleOrNull(9, row.value3)
        s.bindStringOrNull(10, row.valueText)
        s.bindString(11, row.unit)
        s.bindLongOrNull(12, row.categoryValue?.toLong())
        s.bindStringOrNull(13, row.title)
        s.bindStringOrNull(14, row.extraJson)
        s.bindLong(15, row.count.toLong())
        s.bindString(16, row.sourceId)
        s.bindStringOrNull(17, row.device)
        s.bindLongOrNull(18, row.deviceType?.toLong())
        s.bindLongOrNull(19, row.recordingMethod?.toLong())
        s.bindStringOrNull(20, row.clientRecordId)
        s.bindLong(21, row.origin.toLong())
        s.bindLong(22, row.updatedMs)
        s.bindString(23, row.id)
        s.bindLong(24, row.updatedMs)
    }

    private fun bindSampleForInsert(s: SQLiteStatement, row: HealthSampleRow) {
        s.clearBindings()
        s.bindString(1, row.id)
        s.bindString(2, row.typeId)
        s.bindLong(3, row.startMs)
        s.bindLong(4, row.endMs)
        s.bindLongOrNull(5, row.startOffsetS?.toLong())
        s.bindLongOrNull(6, row.endOffsetS?.toLong())
        s.bindString(7, row.localDay)
        s.bindDoubleOrNull(8, row.value)
        s.bindDoubleOrNull(9, row.value2)
        s.bindDoubleOrNull(10, row.value3)
        s.bindStringOrNull(11, row.valueText)
        s.bindString(12, row.unit)
        s.bindLongOrNull(13, row.categoryValue?.toLong())
        s.bindStringOrNull(14, row.title)
        s.bindStringOrNull(15, row.extraJson)
        s.bindLong(16, row.count.toLong())
        s.bindString(17, row.sourceId)
        s.bindStringOrNull(18, row.device)
        s.bindLongOrNull(19, row.deviceType?.toLong())
        s.bindLongOrNull(20, row.recordingMethod?.toLong())
        s.bindStringOrNull(21, row.clientRecordId)
        s.bindLong(22, row.origin.toLong())
        s.bindLong(23, if (row.deleted) 1L else 0L)
        s.bindLong(24, row.updatedMs)
    }

    private fun readSample(c: Cursor): HealthSampleRow = HealthSampleRow(
        id = c.getString(0),
        typeId = c.getString(1),
        startMs = c.getLong(2),
        endMs = c.getLong(3),
        startOffsetS = c.getIntOrNull(4),
        endOffsetS = c.getIntOrNull(5),
        localDay = c.getString(6),
        value = c.getDoubleOrNull(7),
        value2 = c.getDoubleOrNull(8),
        value3 = c.getDoubleOrNull(9),
        valueText = c.getStringOrNull(10),
        unit = c.getString(11),
        categoryValue = c.getIntOrNull(12),
        title = c.getStringOrNull(13),
        extraJson = c.getStringOrNull(14),
        count = c.getInt(15),
        sourceId = c.getString(16),
        device = c.getStringOrNull(17),
        deviceType = c.getIntOrNull(18),
        recordingMethod = c.getIntOrNull(19),
        clientRecordId = c.getStringOrNull(20),
        origin = c.getInt(21),
        deleted = c.getInt(22) == 1,
        updatedMs = c.getLong(23)
    )

    private fun readSyncState(c: Cursor): HealthSyncState = HealthSyncState(
        typeId = c.getString(0),
        cursor = c.getStringOrNull(1),
        cursorIssuedMs = c.getLongOrNull(2),
        lastSyncMs = c.getLongOrNull(3),
        earliestAuthorizedMs = c.getLongOrNull(4),
        earliestProbeMs = c.getLongOrNull(5),
        backfillFloorMs = c.getLongOrNull(6),
        oldestBackfilledMs = c.getLongOrNull(7),
        backfillDone = c.getInt(8) == 1,
        backfillWithHistory = c.getInt(9) == 1,
        status = c.getString(10),
        lastError = c.getStringOrNull(11),
        lastErrorMs = c.getLongOrNull(12),
        ipcCallsTotal = c.getLong(13)
    )

    private inline fun <T> Cursor.readAll(read: (Cursor) -> T): List<T> {
        val out = ArrayList<T>(count.coerceAtLeast(0))
        while (moveToNext()) out += read(this)
        return out
    }

    private fun Cursor.getStringOrNull(i: Int): String? = if (isNull(i)) null else getString(i)
    private fun Cursor.getLongOrNull(i: Int): Long? = if (isNull(i)) null else getLong(i)
    private fun Cursor.getIntOrNull(i: Int): Int? = if (isNull(i)) null else getInt(i)
    private fun Cursor.getDoubleOrNull(i: Int): Double? = if (isNull(i)) null else getDouble(i)

    private fun SQLiteStatement.bindStringOrNull(i: Int, v: String?) = if (v == null) bindNull(i) else bindString(i, v)
    private fun SQLiteStatement.bindLongOrNull(i: Int, v: Long?) = if (v == null) bindNull(i) else bindLong(i, v)
    private fun SQLiteStatement.bindDoubleOrNull(i: Int, v: Double?) = if (v == null) bindNull(i) else bindDouble(i, v)

    private companion object {
        const val SAMPLE_COLUMNS =
            "id, type_id, start_ms, end_ms, start_offset_s, end_offset_s, local_day, value, value2, value3, value_text, unit, category_value, title, extra_json, count, source_id, device, device_type, recording_method, client_record_id, origin, deleted, updated_ms"
        const val SYNC_COLUMNS =
            "type_id, cursor, cursor_issued_ms, last_sync_ms, earliest_authorized_ms, earliest_probe_ms, backfill_floor_ms, oldest_backfilled_ms, backfill_done, backfill_with_history, status, last_error, last_error_ms, ipc_calls_total"
        const val UPDATE_SAMPLE =
            "UPDATE health_samples SET type_id = ?, start_ms = ?, end_ms = ?, start_offset_s = ?, end_offset_s = ?, local_day = ?, value = ?, value2 = ?, value3 = ?, value_text = ?, unit = ?, category_value = ?, title = ?, extra_json = ?, count = ?, source_id = ?, device = ?, device_type = ?, recording_method = ?, client_record_id = ?, origin = ?, updated_ms = ? WHERE id = ? AND updated_ms < ? AND deleted = 0"
        const val INSERT_SAMPLE =
            "INSERT INTO health_samples(id, type_id, start_ms, end_ms, start_offset_s, end_offset_s, local_day, value, value2, value3, value_text, unit, category_value, title, extra_json, count, source_id, device, device_type, recording_method, client_record_id, origin, deleted, updated_ms) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)"
    }
}
