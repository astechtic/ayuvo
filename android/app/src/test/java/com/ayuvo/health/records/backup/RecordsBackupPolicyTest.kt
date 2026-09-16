package com.ayuvo.health.records.backup

import com.ayuvo.health.records.data.RecordsBackupKeys
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The §36 Drive gate and the §33 state keys, without touching Drive. */
class RecordsBackupPolicyTest {

    private fun gate(
        enabled: Boolean = true,
        unmetered: Boolean = true,
        lastMs: Long? = null,
        lastRevision: Long = 1,
        revision: Long = 2,
        nowMs: Long = 10_000_000L
    ) = DriveRecordsBackup.shouldUpload(enabled, unmetered, lastMs, lastRevision, revision, nowMs)

    @Test
    fun uploadsOnlyWhenEveryRuleHolds() {
        assertTrue(gate())
        assertFalse("off by default", gate(enabled = false))
        assertFalse("unmetered networks only", gate(unmetered = false))
        assertFalse("nothing changed", gate(lastRevision = 7, revision = 7))
    }

    @Test
    fun waitsFifteenMinutesBetweenUploads() {
        val now = 10_000_000L
        assertFalse(gate(lastMs = now - DriveRecordsBackup.MIN_INTERVAL_MS + 1, nowMs = now))
        assertTrue(gate(lastMs = now - DriveRecordsBackup.MIN_INTERVAL_MS, nowMs = now))
        assertEquals(15L * 60 * 1000, DriveRecordsBackup.MIN_INTERVAL_MS)
    }

    /** docs §33 key names are part of the contract: both platforms read the same rows. */
    @Test
    fun backupStateKeysMatchTheContract() {
        assertEquals(
            listOf(
                "last_archive_ms", "last_archive_size", "last_archive_records", "last_restore_ms",
                "drive_enabled", "drive_last_ms", "drive_file_id", "drive_last_revision"
            ),
            RecordsBackupKeys.ALL
        )
    }

    /** §36: one Drive object, never part of `ayuvo-backup.zip`. */
    @Test
    fun driveFileNameIsTheContractName() {
        assertEquals("ayuvo-records-backup.zip", RecordsBackupCoordinator.DRIVE_FILE_NAME)
        assertEquals(
            "ayuvo-records-2026-09-16.zip",
            RecordsBackupCoordinator.defaultFileName(java.time.LocalDate.parse("2026-09-16"))
        )
    }
}
