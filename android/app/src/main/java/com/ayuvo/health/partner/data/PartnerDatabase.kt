package com.ayuvo.health.partner.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.io.File

/**
 * `ayuvo_partner.db` (docs/partner-sync.md §6): trust, grants, received partner data, sync cursors and my
 * outbound ledger. A separate database: the user's own health, medications and records stores are never touched.
 * WAL, foreign keys on, synchronous NORMAL, busy_timeout 5000 (contract connection settings).
 *
 * Excluded from Android backup and device transfer (backup_rules.xml, data_extraction_rules.xml); never in
 * Export All Data or the Drive backup.
 */
class PartnerDatabase(
    private val context: Context,
    name: String? = NAME
) : SQLiteOpenHelper(context, name, null, VERSION) {

    override fun onConfigure(db: SQLiteDatabase) {
        db.setForeignKeyConstraintsEnabled(true)
        db.enableWriteAheadLogging()
    }

    override fun onCreate(db: SQLiteDatabase) {
        PartnerSchema.STATEMENTS.forEach(db::execSQL)
        db.execSQL("INSERT INTO partner_meta(key, value) VALUES ('schema_version', ?)", arrayOf(VERSION.toString()))
        db.execSQL("INSERT INTO partner_meta(key, value) VALUES ('rev', '0')")
    }

    override fun onOpen(db: SQLiteDatabase) {
        // rawQuery so the pragma actually runs (execSQL discards PRAGMA result rows on some builds).
        db.rawQuery("PRAGMA synchronous=NORMAL", null).use { it.moveToFirst() }
        db.rawQuery("PRAGMA busy_timeout=5000", null).use { it.moveToFirst() }
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // v1 is the only version; shared/partner/migrations/ is empty.
    }

    fun files(): List<File> = databaseFiles(context)

    companion object {
        const val NAME = "ayuvo_partner.db"
        const val VERSION = PartnerSchema.SCHEMA_VERSION

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
