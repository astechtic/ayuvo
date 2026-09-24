package com.ayuvo.health.coach.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.io.File

/**
 * `ayuvo_coach.db`: conversations, messages and attachment rows (docs/coach.md §2). Framework
 * SQLite, separate from the Health Data mirror, Health Records and Medications so their
 * shared-schema parity tests stay untouched.
 * WAL, foreign keys on, synchronous NORMAL, busy_timeout 5000 (contract connection settings).
 *
 * The file, its journal siblings and the attachment directory are excluded from Android backup and
 * device transfer. Conversations quote health values, so they reach a cloud only through the
 * explicit opt-in of docs/cloud-backup.md; the user's export is otherwise the only way out.
 */
class CoachDatabase(
    private val context: Context,
    name: String? = NAME
) : SQLiteOpenHelper(context, name, null, VERSION) {

    override fun onConfigure(db: SQLiteDatabase) {
        db.setForeignKeyConstraintsEnabled(true)
        db.enableWriteAheadLogging()
    }

    override fun onCreate(db: SQLiteDatabase) {
        CoachSchema.STATEMENTS.forEach(db::execSQL)
        migrate(db, CoachSchema.BASE_VERSION, VERSION)
    }

    override fun onOpen(db: SQLiteDatabase) {
        // rawQuery so the pragma actually runs (execSQL discards PRAGMA result rows on some builds).
        db.rawQuery("PRAGMA synchronous=NORMAL", null).use { it.moveToFirst() }
        db.rawQuery("PRAGMA busy_timeout=5000", null).use { it.moveToFirst() }
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        migrate(db, oldVersion, newVersion)
    }

    fun files(): List<File> = databaseFiles(context)

    companion object {
        const val NAME = "ayuvo_coach.db"
        const val VERSION = CoachSchema.VERSION

        /** Runs every migration in (from, to], then records `coach_meta.schema_version`. */
        fun migrate(db: SQLiteDatabase, from: Int, to: Int) {
            for ((version, statements) in CoachSchema.MIGRATIONS) {
                if (version <= from || version > to) continue
                statements.forEach(db::execSQL)
            }
            db.execSQL(
                "INSERT OR REPLACE INTO coach_meta(key, value) VALUES ('schema_version', ?)",
                arrayOf(to.toString())
            )
        }

        fun databaseFiles(context: Context, name: String = NAME): List<File> {
            val base = context.getDatabasePath(name)
            return listOf(base, File(base.path + "-wal"), File(base.path + "-shm"), File(base.path + "-journal"))
        }

        fun exists(context: Context, name: String = NAME): Boolean = context.getDatabasePath(name).exists()

        /** Deletes the database and every journal sibling; callers must close the helper first. */
        fun deleteDatabaseFiles(context: Context, name: String = NAME) {
            context.deleteDatabase(name)
            databaseFiles(context, name).forEach { runCatching { if (it.exists()) it.delete() } }
        }
    }
}
