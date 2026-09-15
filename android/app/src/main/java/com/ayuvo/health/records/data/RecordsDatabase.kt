package com.ayuvo.health.records.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.io.File

/**
 * `ayuvo_records.db`: Health Records (docs/health-records.md). Framework SQLite with FTS4,
 * separate from the Health Data mirror so `shared/health` parity stays untouched.
 * WAL, foreign keys on, synchronous NORMAL, busy_timeout 5000 (contract connection settings).
 *
 * The file and its journal siblings are excluded from Android backup and never ride on the
 * Drive `ayuvo-backup.zip`.
 */
class RecordsDatabase(
    private val context: Context,
    name: String? = NAME
) : SQLiteOpenHelper(context, name, null, VERSION) {

    override fun onConfigure(db: SQLiteDatabase) {
        db.setForeignKeyConstraintsEnabled(true)
        db.enableWriteAheadLogging()
    }

    override fun onCreate(db: SQLiteDatabase) {
        // Fresh install = schema.sql (v1) then every migration in order; there is no combined DDL.
        RecordsSchema.STATEMENTS.forEach(db::execSQL)
        migrate(db, RecordsSchema.BASE_VERSION, VERSION)
    }

    override fun onOpen(db: SQLiteDatabase) {
        // rawQuery so the pragma actually runs (execSQL discards PRAGMA result rows on some builds).
        db.rawQuery("PRAGMA synchronous=NORMAL", null).use { it.moveToFirst() }
        db.rawQuery("PRAGMA busy_timeout=5000", null).use { it.moveToFirst() }
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // SQLiteOpenHelper already runs onCreate/onUpgrade inside one transaction together with the
        // user_version bump, so a failed statement rolls the whole upgrade back.
        migrate(db, oldVersion, newVersion)
    }

    fun files(): List<File> = databaseFiles(context)

    companion object {
        const val NAME = "ayuvo_records.db"
        const val VERSION = RecordsSchema.VERSION

        /**
         * Runs `shared/records/migrations/NNN_*.sql` for every version in (from, to], then records
         * `records_meta.schema_version`. Callers own the transaction.
         */
        fun migrate(db: SQLiteDatabase, from: Int, to: Int) {
            for ((version, statements) in RecordsSchema.MIGRATIONS) {
                if (version <= from || version > to) continue
                statements.forEach(db::execSQL)
            }
            db.execSQL(
                "INSERT OR REPLACE INTO records_meta(key, value) VALUES ('schema_version', ?)",
                arrayOf(to.toString())
            )
        }

        fun databaseFiles(context: Context): List<File> {
            val base = context.getDatabasePath(NAME)
            return listOf(base, File(base.path + "-wal"), File(base.path + "-shm"), File(base.path + "-journal"))
        }

        /** Deletes the database and every journal sibling; callers must close the helper first. */
        fun deleteDatabaseFiles(context: Context) {
            context.deleteDatabase(NAME)
            databaseFiles(context).forEach { runCatching { if (it.exists()) it.delete() } }
        }
    }
}
