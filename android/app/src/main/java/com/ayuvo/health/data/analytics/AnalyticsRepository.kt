package com.ayuvo.health.data.analytics

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteStatement
import com.ayuvo.health.data.health.HealthDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

/** One versioned analytics result (`analytics_results`, docs/health-analytics.md). [resultJson] is the reference shape. */
data class AnalyticsResultRow(
    val metricId: String,
    val periodStart: String,
    val periodEnd: String,
    val algorithmId: String,
    val algorithmVersion: Int,
    val configVersion: Int,
    val status: String,
    val classification: String,
    val value: Double? = null,
    val value2: Double? = null,
    val value3: Double? = null,
    val unit: String? = null,
    val confidence: Double? = null,
    val coverage: Double? = null,
    val inputCount: Int? = null,
    val baselineWindowDays: Int? = null,
    val resultJson: String,
    val provenanceJson: String,
    val inputHash: String,
    val computedMs: Long
)

data class AnalyticsStateRow(
    val metricId: String,
    val algorithmVersion: Int,
    val configVersion: Int,
    val lastProcessedDay: String?,
    val updatedMs: Long
)

/** One trained per-user forecast model (`ml_models`); [modelVersion] increments on every retrain. */
data class MlModelRow(
    val modelId: String,
    val modelVersion: Int,
    val algorithmVersion: Int,
    val target: String,
    val featureSchemaVersion: Int,
    val trainStart: String?,
    val trainEnd: String?,
    val valStart: String?,
    val valEnd: String?,
    val testStart: String?,
    val testEnd: String?,
    val lambda: Double?,
    val coefficientsJson: String,
    val normalizationJson: String,
    val metricsJson: String,
    val baselineMetricsJson: String,
    val deployed: Boolean,
    val createdMs: Long
)

/**
 * Analytics results, processing state and forecast models in `ayuvo_health.db` v5. Results are keyed by
 * (metric, period start, algorithm version): a new algorithm version is written next to the old rows instead of
 * overwriting them, and only the [KEEP_VERSIONS] newest versions of a metric are kept. Everything here can be
 * recomputed from the mirror, so nothing is exported. Writes run in one transaction on `Dispatchers.IO`
 * (UPDATE-then-INSERT, SQLite 3.18 has no UPSERT); [revision] bumps after each committed write.
 */
class AnalyticsRepository(private val helper: HealthDatabase) {
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

    // -- results -------------------------------------------------------------------------------------

    /** Upserts [rows] and prunes each touched metric to its [KEEP_VERSIONS] newest algorithm versions. */
    suspend fun upsertResults(rows: List<AnalyticsResultRow>) = withContext(Dispatchers.IO) {
        if (rows.isEmpty()) return@withContext
        write { database ->
            val update = database.compileStatement(
                "UPDATE analytics_results SET ${RESULT_COLUMNS.drop(3).joinToString(", ") { "$it = ?" }} " +
                    "WHERE metric_id = ? AND period_start = ? AND algorithm_version = ?"
            )
            val insert = database.compileStatement(
                "INSERT INTO analytics_results(${RESULT_COLUMNS.joinToString(", ")}) VALUES (${RESULT_COLUMNS.joinToString(", ") { "?" }})"
            )
            for (r in rows) {
                update.clearBindings()
                bindResultTail(update, r, 1)
                val n = RESULT_COLUMNS.size - 3
                update.bindString(n + 1, r.metricId)
                update.bindString(n + 2, r.periodStart)
                update.bindLong(n + 3, r.algorithmVersion.toLong())
                if (update.executeUpdateDelete() == 0) {
                    insert.clearBindings()
                    insert.bindString(1, r.metricId)
                    insert.bindString(2, r.periodStart)
                    insert.bindLong(3, r.algorithmVersion.toLong())
                    bindResultTail(insert, r, 4)
                    insert.executeInsert()
                }
            }
            for (metric in rows.map { it.metricId }.toSet()) pruneVersions(database, metric)
        }
    }

    /** Result rows of [metricId] whose period starts in [fromDay]..[toDay]; latest algorithm version unless given. */
    suspend fun results(metricId: String, fromDay: String, toDay: String, algorithmVersion: Int? = null): List<AnalyticsResultRow> =
        withContext(Dispatchers.IO) {
            val version = algorithmVersion ?: latestVersion(metricId) ?: return@withContext emptyList()
            db.rawQuery(
                "SELECT ${RESULT_COLUMNS.joinToString(", ")} FROM analytics_results WHERE metric_id = ? AND algorithm_version = ? " +
                    "AND period_start >= ? AND period_start <= ? ORDER BY period_start",
                arrayOf(metricId, version.toString(), fromDay, toDay)
            ).use { c -> buildList { while (c.moveToNext()) add(readResult(c)) } }
        }

    suspend fun result(metricId: String, periodStart: String, algorithmVersion: Int? = null): AnalyticsResultRow? =
        results(metricId, periodStart, periodStart, algorithmVersion).firstOrNull()

    /** period_start -> input_hash for the incremental "did the inputs change" check. */
    suspend fun inputHashes(metricId: String, algorithmVersion: Int, fromDay: String, toDay: String): Map<String, String> =
        withContext(Dispatchers.IO) {
            db.rawQuery(
                "SELECT period_start, input_hash FROM analytics_results WHERE metric_id = ? AND algorithm_version = ? " +
                    "AND period_start >= ? AND period_start <= ?",
                arrayOf(metricId, algorithmVersion.toString(), fromDay, toDay)
            ).use { c -> buildMap { while (c.moveToNext()) put(c.getString(0), c.getString(1)) } }
        }

    private fun latestVersion(metricId: String): Int? =
        db.rawQuery("SELECT MAX(algorithm_version) FROM analytics_results WHERE metric_id = ?", arrayOf(metricId)).use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getInt(0) else null
        }

    private fun pruneVersions(database: SQLiteDatabase, metricId: String) {
        val versions = database.rawQuery(
            "SELECT DISTINCT algorithm_version FROM analytics_results WHERE metric_id = ? ORDER BY algorithm_version DESC",
            arrayOf(metricId)
        ).use { c -> buildList { while (c.moveToNext()) add(c.getInt(0)) } }
        for (v in versions.drop(KEEP_VERSIONS)) {
            database.delete("analytics_results", "metric_id = ? AND algorithm_version = ?", arrayOf(metricId, v.toString()))
        }
    }

    suspend fun deleteAll() = withContext(Dispatchers.IO) {
        write { database -> for (t in HealthDatabase.ANALYTICS_TABLES) database.delete(t, null, null) }
        Unit
    }

    // -- state ---------------------------------------------------------------------------------------

    suspend fun state(metricId: String): AnalyticsStateRow? = withContext(Dispatchers.IO) {
        db.rawQuery(
            "SELECT metric_id, algorithm_version, config_version, last_processed_day, updated_ms FROM analytics_state WHERE metric_id = ?",
            arrayOf(metricId)
        ).use { c ->
            if (!c.moveToFirst()) null
            else AnalyticsStateRow(c.getString(0), c.getInt(1), c.getInt(2), if (c.isNull(3)) null else c.getString(3), c.getLong(4))
        }
    }

    suspend fun putState(state: AnalyticsStateRow) = withContext(Dispatchers.IO) {
        write { database ->
            database.execSQL(
                "INSERT OR REPLACE INTO analytics_state(metric_id, algorithm_version, config_version, last_processed_day, updated_ms) VALUES (?, ?, ?, ?, ?)",
                arrayOf<Any?>(state.metricId, state.algorithmVersion, state.configVersion, state.lastProcessedDay, state.updatedMs)
            )
        }
    }

    // -- models --------------------------------------------------------------------------------------

    /** Inserts [model] as the next version of its model id (the given version is ignored) and returns that version. */
    suspend fun insertModel(model: MlModelRow): Int = withContext(Dispatchers.IO) {
        write { database ->
            val next = database.rawQuery("SELECT MAX(model_version) FROM ml_models WHERE model_id = ?", arrayOf(model.modelId)).use { c ->
                if (c.moveToFirst() && !c.isNull(0)) c.getInt(0) + 1 else 1
            }
            database.execSQL(
                "INSERT INTO ml_models(${MODEL_COLUMNS.joinToString(", ")}) VALUES (${MODEL_COLUMNS.joinToString(", ") { "?" }})",
                arrayOf<Any?>(
                    model.modelId, next, model.algorithmVersion, model.target, model.featureSchemaVersion, model.trainStart,
                    model.trainEnd, model.valStart, model.valEnd, model.testStart, model.testEnd, model.lambda,
                    model.coefficientsJson, model.normalizationJson, model.metricsJson, model.baselineMetricsJson,
                    if (model.deployed) 1 else 0, model.createdMs
                )
            )
            next
        }
    }

    suspend fun latestModel(modelId: String): MlModelRow? = withContext(Dispatchers.IO) {
        db.rawQuery(
            "SELECT ${MODEL_COLUMNS.joinToString(", ")} FROM ml_models WHERE model_id = ? ORDER BY model_version DESC LIMIT 1",
            arrayOf(modelId)
        ).use { c -> if (c.moveToFirst()) readModel(c) else null }
    }

    // -- mapping -------------------------------------------------------------------------------------

    private fun bindResultTail(s: SQLiteStatement, r: AnalyticsResultRow, first: Int) {
        var i = first
        s.bindString(i++, r.periodEnd)
        s.bindString(i++, r.algorithmId)
        s.bindLong(i++, r.configVersion.toLong())
        s.bindString(i++, r.status)
        s.bindString(i++, r.classification)
        bindDouble(s, i++, r.value)
        bindDouble(s, i++, r.value2)
        bindDouble(s, i++, r.value3)
        if (r.unit == null) s.bindNull(i++) else s.bindString(i++, r.unit)
        bindDouble(s, i++, r.confidence)
        bindDouble(s, i++, r.coverage)
        if (r.inputCount == null) s.bindNull(i++) else s.bindLong(i++, r.inputCount.toLong())
        if (r.baselineWindowDays == null) s.bindNull(i++) else s.bindLong(i++, r.baselineWindowDays.toLong())
        s.bindString(i++, r.resultJson)
        s.bindString(i++, r.provenanceJson)
        s.bindString(i++, r.inputHash)
        s.bindLong(i, r.computedMs)
    }

    private fun bindDouble(s: SQLiteStatement, i: Int, v: Double?) {
        if (v == null) s.bindNull(i) else s.bindDouble(i, v)
    }

    private fun Cursor.dbl(i: Int): Double? = if (isNull(i)) null else getDouble(i)
    private fun Cursor.int(i: Int): Int? = if (isNull(i)) null else getInt(i)
    private fun Cursor.str(i: Int): String? = if (isNull(i)) null else getString(i)

    private fun readResult(c: Cursor) = AnalyticsResultRow(
        metricId = c.getString(0), periodStart = c.getString(1), algorithmVersion = c.getInt(2), periodEnd = c.getString(3),
        algorithmId = c.getString(4), configVersion = c.getInt(5), status = c.getString(6), classification = c.getString(7),
        value = c.dbl(8), value2 = c.dbl(9), value3 = c.dbl(10), unit = c.str(11), confidence = c.dbl(12), coverage = c.dbl(13),
        inputCount = c.int(14), baselineWindowDays = c.int(15), resultJson = c.getString(16), provenanceJson = c.getString(17),
        inputHash = c.getString(18), computedMs = c.getLong(19)
    )

    private fun readModel(c: Cursor) = MlModelRow(
        modelId = c.getString(0), modelVersion = c.getInt(1), algorithmVersion = c.getInt(2), target = c.getString(3),
        featureSchemaVersion = c.getInt(4), trainStart = c.str(5), trainEnd = c.str(6), valStart = c.str(7), valEnd = c.str(8),
        testStart = c.str(9), testEnd = c.str(10), lambda = c.dbl(11), coefficientsJson = c.getString(12),
        normalizationJson = c.getString(13), metricsJson = c.getString(14), baselineMetricsJson = c.getString(15),
        deployed = c.getInt(16) != 0, createdMs = c.getLong(17)
    )

    companion object {
        const val KEEP_VERSIONS = 2

        /** Key columns first (metric, period start, version), then the columns [bindResultTail] binds in order. */
        val RESULT_COLUMNS = listOf(
            "metric_id", "period_start", "algorithm_version", "period_end", "algorithm_id", "config_version", "status",
            "classification", "value", "value2", "value3", "unit", "confidence", "coverage", "input_count",
            "baseline_window_days", "result_json", "provenance_json", "input_hash", "computed_ms"
        )

        val MODEL_COLUMNS = listOf(
            "model_id", "model_version", "algorithm_version", "target", "feature_schema_version", "train_start", "train_end",
            "val_start", "val_end", "test_start", "test_end", "lambda", "coefficients_json", "normalization_json",
            "metrics_json", "baseline_metrics_json", "deployed", "created_ms"
        )
    }
}
