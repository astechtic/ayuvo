package com.ayuvo.health.records.data

/**
 * `records_backup_state` keys (docs/health-records.md §33). Values are stored as strings; numbers
 * are decimal and booleans are `"1"` / `"0"`.
 */
object RecordsBackupKeys {
    const val LAST_ARCHIVE_MS = "last_archive_ms"
    const val LAST_ARCHIVE_SIZE = "last_archive_size"
    const val LAST_ARCHIVE_RECORDS = "last_archive_records"
    const val LAST_RESTORE_MS = "last_restore_ms"
    const val DRIVE_ENABLED = "drive_enabled"
    const val DRIVE_LAST_MS = "drive_last_ms"
    const val DRIVE_FILE_ID = "drive_file_id"
    const val DRIVE_LAST_REVISION = "drive_last_revision"

    val ALL: List<String> = listOf(
        LAST_ARCHIVE_MS, LAST_ARCHIVE_SIZE, LAST_ARCHIVE_RECORDS, LAST_RESTORE_MS,
        DRIVE_ENABLED, DRIVE_LAST_MS, DRIVE_FILE_ID, DRIVE_LAST_REVISION
    )
}

/** One record's stored files (storage sizing, thumbnail rebuild, archive export). */
data class RecordFileRef(
    val id: String,
    val fileType: String,
    /** Relative to the files root; split children share their parent's path. */
    val filePath: String?,
    val thumbnailPath: String?
)

/**
 * Settings › Health Records › Storage (docs §37). Every field is bytes unless named `…Count`.
 * Originals are grouped by `records.file_type`.
 */
data class RecordsStorageStats(
    val pdfBytes: Long = 0,
    val imageBytes: Long = 0,
    val textBytes: Long = 0,
    val otherBytes: Long = 0,
    val thumbnailBytes: Long = 0,
    val renderCacheBytes: Long = 0,
    val databaseBytes: Long = 0,
    /** Archives still sitting in the share temp folder. */
    val backupBytes: Long = 0,
    val recordCount: Int = 0,
    val pageCount: Int = 0
) {
    val documentBytes: Long get() = pdfBytes + imageBytes + textBytes + otherBytes
    val totalBytes: Long get() = documentBytes + thumbnailBytes + renderCacheBytes + databaseBytes + backupBytes
}
