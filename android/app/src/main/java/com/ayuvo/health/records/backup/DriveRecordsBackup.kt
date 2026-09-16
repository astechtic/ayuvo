package com.ayuvo.health.records.backup

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.ayuvo.health.backup.DriveCloudBackupClient
import com.ayuvo.health.records.data.RecordsBackupKeys
import com.ayuvo.health.records.data.RecordsStore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/**
 * The opt-in "Include Health Records in Google Drive backup" (docs/health-records.md §36).
 *
 * The archive is built to a cache file and uploaded **resumably** to appDataFolder as
 * `ayuvo-records-backup.zip`; it never joins the in-memory `ayuvo-backup.zip`. Automatic runs
 * happen after a successful `CloudBackupCoordinator` run, on unmetered networks, at most every
 * 15 minutes, and only when the records changed since `drive_last_revision`.
 */
class DriveRecordsBackup(
    private val context: Context,
    private val store: RecordsStore,
    private val archives: RecordsBackupCoordinator,
    private val drive: DriveCloudBackupClient,
    /** The Drive access token of the existing sign-in, or null when not signed in. */
    private val accessToken: suspend () -> String?
) {
    private val mutex = Mutex()

    suspend fun isEnabled(): Boolean = store.backupState(RecordsBackupKeys.DRIVE_ENABLED) == "1"

    suspend fun setEnabled(enabled: Boolean) {
        store.setBackupState(RecordsBackupKeys.DRIVE_ENABLED, if (enabled) "1" else "0")
    }

    /** True when the toggle is on but nothing can run yet (no Drive sign-in). */
    suspend fun needsSignIn(): Boolean = isEnabled() && accessToken() == null

    /** Called after the normal cloud backup run; skips silently unless every §36 rule passes. */
    suspend fun backupIfNeeded(nowMs: Long = System.currentTimeMillis()): Result<Boolean> = runCatching {
        val status = archives.status()
        val allowed = shouldUpload(
            enabled = status.driveEnabled,
            unmetered = onUnmeteredNetwork(),
            lastMs = status.driveLastMs,
            lastRevision = status.driveLastRevision,
            revision = store.contentRevision(),
            nowMs = nowMs
        )
        if (!allowed) return@runCatching false
        upload(nowMs)
        true
    }

    /** Settings "Back up now": ignores the interval and the unchanged check. */
    suspend fun backupNow(onProgress: (Long, Long) -> Unit = { _, _ -> }): Result<Unit> = runCatching {
        upload(System.currentTimeMillis(), onProgress)
    }

    private suspend fun upload(nowMs: Long, onProgress: (Long, Long) -> Unit = { _, _ -> }) = mutex.withLock {
        val token = accessToken() ?: error("Sign in to Google Drive first")
        val revision = store.contentRevision()
        val cache = File(context.cacheDir, DRIVE_CACHE_NAME)
        try {
            archives.buildArchive(cache)
            val existing = store.backupState(RecordsBackupKeys.DRIVE_FILE_ID)
                ?: drive.findFileId(token, RecordsBackupCoordinator.DRIVE_FILE_NAME)
            val fileId = drive.uploadResumable(
                accessToken = token,
                file = cache,
                name = RecordsBackupCoordinator.DRIVE_FILE_NAME,
                existingFileId = existing,
                onProgress = onProgress
            )
            store.setBackupState(RecordsBackupKeys.DRIVE_FILE_ID, fileId)
            store.setBackupState(RecordsBackupKeys.DRIVE_LAST_MS, nowMs.toString())
            store.setBackupState(RecordsBackupKeys.DRIVE_LAST_REVISION, revision.toString())
        } finally {
            // The archive is a full copy of the user's records: it never lingers in the cache.
            runCatching { cache.delete() }
        }
    }

    /** Settings › Backup › "Restore from Drive": downloads and runs the §35 import. */
    suspend fun restore(
        mode: RecordsArchiveFormat.ImportMode,
        onProgress: (ArchiveProgress) -> Unit = {}
    ): Result<ArchiveImportResult> = runCatching {
        mutex.withLock {
            val token = accessToken() ?: error("Sign in to Google Drive first")
            val fileId = store.backupState(RecordsBackupKeys.DRIVE_FILE_ID)
                ?: drive.findFileId(token, RecordsBackupCoordinator.DRIVE_FILE_NAME)
                ?: error("No Health Records backup in Drive")
            val cache = File(context.cacheDir, DRIVE_CACHE_NAME)
            try {
                onProgress(ArchiveProgress(STEP_DOWNLOAD))
                drive.downloadTo(token, fileId, cache)
                archives.importFile(cache, mode, onProgress)
            } finally {
                runCatching { cache.delete() }
            }
        }
    }

    private fun onUnmeteredNetwork(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    companion object {
        /** §36: at most every 15 minutes. */
        const val MIN_INTERVAL_MS = 15L * 60 * 1000
        const val DRIVE_CACHE_NAME = "ayuvo-records-drive.zip"
        const val STEP_DOWNLOAD = "download"

        /**
         * Pure §36 gate, so the rules are testable without Drive: enabled, unmetered, at least
         * 15 minutes since the last upload, and the records changed.
         */
        fun shouldUpload(
            enabled: Boolean,
            unmetered: Boolean,
            lastMs: Long?,
            lastRevision: Long,
            revision: Long,
            nowMs: Long
        ): Boolean = enabled && unmetered &&
            (lastMs == null || nowMs - lastMs >= MIN_INTERVAL_MS) &&
            revision != lastRevision
    }
}
