package com.ayuvo.health.cycle.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.io.File

/**
 * `ayuvo_cycle.db`: app-logged periods, day logs and cycle settings (docs/cycle-tracking.md §2).
 * Framework SQLite, separate from the Health Data mirror and the medications database so their
 * parity tests stay untouched. WAL, synchronous NORMAL, busy_timeout 5000.
 *
 * The file and its journal siblings are excluded from Android backup and device transfer; Export
 * All Data (section `cycle`) is the only way the data leaves the device besides Health Connect sync.
 */
class CycleDatabase(
    private val context: Context,
    name: String? = NAME
) : SQLiteOpenHelper(context, name, null, VERSION) {

    override fun onConfigure(db: SQLiteDatabase) {
        db.enableWriteAheadLogging()
    }

    override fun onCreate(db: SQLiteDatabase) {
        CycleSchema.STATEMENTS.forEach(db::execSQL)
        migrate(db, CycleSchema.VERSION, VERSION)
    }

    override fun onOpen(db: SQLiteDatabase) {
        db.rawQuery("PRAGMA synchronous=NORMAL", null).use { it.moveToFirst() }
        db.rawQuery("PRAGMA busy_timeout=5000", null).use { it.moveToFirst() }
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        migrate(db, oldVersion, newVersion)
    }

    fun files(): List<File> = databaseFiles(context)

    companion object {
        const val NAME = "ayuvo_cycle.db"
        const val VERSION = CycleSchema.VERSION

        /** Later versions run `shared/cycle/migrations/NNN_*.sql` here; v1 only records the version. */
        fun migrate(db: SQLiteDatabase, @Suppress("UNUSED_PARAMETER") from: Int, to: Int) {
            db.execSQL("INSERT OR REPLACE INTO cycle_meta(key, value) VALUES ('schema_version', ?)", arrayOf(to.toString()))
        }

        fun databaseFiles(context: Context, name: String = NAME): List<File> {
            val base = context.getDatabasePath(name)
            return listOf(base, File(base.path + "-wal"), File(base.path + "-shm"), File(base.path + "-journal"))
        }

        /** True once the database file exists; callers use it to avoid creating the DB just to read nothing. */
        fun exists(context: Context, name: String = NAME): Boolean = context.getDatabasePath(name).exists()

        /** Deletes the database and every journal sibling; callers must close the helper first. */
        fun deleteDatabaseFiles(context: Context, name: String = NAME) {
            context.deleteDatabase(name)
            databaseFiles(context, name).forEach { runCatching { if (it.exists()) it.delete() } }
        }
    }
}
