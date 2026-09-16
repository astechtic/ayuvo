package com.ayuvo.health.records.backup

import android.content.Context
import android.net.Uri
import com.ayuvo.health.records.data.RecordFileStore
import com.ayuvo.health.records.data.RecordsBackupKeys
import com.ayuvo.health.records.data.RecordsDatabase
import com.ayuvo.health.records.data.RecordsStorageStats
import com.ayuvo.health.records.data.RecordsStore
import com.ayuvo.health.records.model.RecordFileType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate

/** Settings › Health Records › Backup status (§35, §36). */
data class RecordsBackupStatus(
    val lastArchiveMs: Long? = null,
    val lastArchiveSize: Long = 0,
    val lastArchiveRecords: Int = 0,
    val lastRestoreMs: Long? = null,
    val driveEnabled: Boolean = false,
    val driveLastMs: Long? = null,
    val driveFileId: String? = null,
    val driveLastRevision: Long = -1
)

/**
 * Export / restore of the portable `ayuvo-records` archive (docs §35) plus the
 * `records_backup_state` bookkeeping both it and the Drive backup (§36) share.
 */
class RecordsBackupCoordinator(
    private val context: Context,
    private val store: RecordsStore,
    private val helper: RecordsDatabase,
    private val files: RecordFileStore,
    private val appVersion: String
) {

    suspend fun status(): RecordsBackupStatus {
        val state = store.backupStateAll()
        return RecordsBackupStatus(
            lastArchiveMs = state[RecordsBackupKeys.LAST_ARCHIVE_MS]?.toLongOrNull(),
            lastArchiveSize = state[RecordsBackupKeys.LAST_ARCHIVE_SIZE]?.toLongOrNull() ?: 0,
            lastArchiveRecords = state[RecordsBackupKeys.LAST_ARCHIVE_RECORDS]?.toIntOrNull() ?: 0,
            lastRestoreMs = state[RecordsBackupKeys.LAST_RESTORE_MS]?.toLongOrNull(),
            driveEnabled = state[RecordsBackupKeys.DRIVE_ENABLED] == "1",
            driveLastMs = state[RecordsBackupKeys.DRIVE_LAST_MS]?.toLongOrNull(),
            driveFileId = state[RecordsBackupKeys.DRIVE_FILE_ID],
            driveLastRevision = state[RecordsBackupKeys.DRIVE_LAST_REVISION]?.toLongOrNull() ?: -1
        )
    }

    /** Writes an archive into the share temp folder and records it in `records_backup_state`. */
    suspend fun export(
        includeOriginals: Boolean = true,
        target: File = File(files.archiveDir(), defaultFileName()),
        onProgress: (ArchiveProgress) -> Unit = {}
    ): ArchiveExportResult {
        val result = RecordsArchiveWriter(helper, files, appVersion).export(target, includeOriginals, onProgress)
        store.setBackupState(RecordsBackupKeys.LAST_ARCHIVE_MS, System.currentTimeMillis().toString())
        store.setBackupState(RecordsBackupKeys.LAST_ARCHIVE_SIZE, result.archiveBytes.toString())
        store.setBackupState(RecordsBackupKeys.LAST_ARCHIVE_RECORDS, result.recordCount.toString())
        return result
    }

    /** Builds an archive at [target] without touching `records_backup_state` (Drive upload). */
    suspend fun buildArchive(target: File, onProgress: (ArchiveProgress) -> Unit = {}): ArchiveExportResult =
        RecordsArchiveWriter(helper, files, appVersion).export(target, includeOriginals = true, onProgress = onProgress)

    suspend fun importFrom(
        uri: Uri,
        mode: RecordsArchiveFormat.ImportMode,
        onProgress: (ArchiveProgress) -> Unit = {}
    ): ArchiveImportResult = withContext(Dispatchers.IO) {
        val staged = File(files.archiveDir(), "restore-${System.currentTimeMillis()}.zip")
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                staged.outputStream().use { output -> input.copyTo(output, RecordsArchiveWriter.COPY_BUFFER) }
            } ?: error("Couldn't open the archive")
            importFile(staged, mode, onProgress)
        } finally {
            runCatching { staged.delete() }
        }
    }

    suspend fun importFile(
        archive: File,
        mode: RecordsArchiveFormat.ImportMode,
        onProgress: (ArchiveProgress) -> Unit = {}
    ): ArchiveImportResult {
        val result = RecordsArchiveReader(helper, files).import(archive, mode, onProgress)
        onProgress(ArchiveProgress(STEP_INDEX))
        // §35: the FTS index is rebuilt at the end.
        store.reindexAll()
        store.setBackupState(RecordsBackupKeys.LAST_RESTORE_MS, System.currentTimeMillis().toString())
        store.bumpRevision()
        return result
    }

    /** §37 storage numbers; every stat is read off disk on a background thread. */
    suspend fun storage(): RecordsStorageStats = withContext(Dispatchers.IO) {
        val refs = store.fileRefs()
        var pdf = 0L
        var image = 0L
        var text = 0L
        var other = 0L
        var thumbs = 0L
        val counted = HashSet<String>()
        for (ref in refs) {
            val path = ref.filePath
            if (path != null && counted.add(path)) {
                val size = files.resolve(path)?.takeIf { it.isFile }?.length() ?: 0L
                when (RecordFileType.fromRaw(ref.fileType)) {
                    RecordFileType.PDF -> pdf += size
                    RecordFileType.IMAGE -> image += size
                    RecordFileType.TEXT -> text += size
                    RecordFileType.OTHER -> other += size
                }
            }
            val thumb = ref.thumbnailPath
            if (thumb != null && counted.add(thumb)) {
                thumbs += files.resolve(thumb)?.takeIf { it.isFile }?.length() ?: 0L
            }
        }
        RecordsStorageStats(
            pdfBytes = pdf,
            imageBytes = image,
            textBytes = text,
            otherBytes = other,
            thumbnailBytes = thumbs,
            renderCacheBytes = files.renderCacheBytes(),
            databaseBytes = RecordsDatabase.databaseFiles(context).filter { it.isFile }.sumOf { it.length() },
            // §37 "Backups": archives still waiting in the share temp folder.
            backupBytes = files.archiveBytes(),
            recordCount = refs.size,
            pageCount = store.pageCount().toInt()
        )
    }

    companion object {
        const val STEP_INDEX = "index"

        fun defaultFileName(today: LocalDate = LocalDate.now()): String = "ayuvo-records-$today.zip"

        /** The single Drive object name (§36). */
        const val DRIVE_FILE_NAME = "ayuvo-records-backup.zip"
    }
}
