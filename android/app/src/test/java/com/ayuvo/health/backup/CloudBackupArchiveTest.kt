package com.ayuvo.health.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class CloudBackupArchiveTest {
    @Test
    fun roundTripKeepsFoodEntryIdsAndPhotos() {
        val id = UUID.fromString("11111111-2222-3333-4444-555555555555")
        val values = mapOf(
            "foodEntries" to CloudBackupValue.string("""[{"id":"$id","name":"Oats"}]"""),
            "healthChangesToken" to CloudBackupValue.string("device-token"),
            "healthFoodRestoreDone" to CloudBackupValue.bool(false),
        )
        val photo = byteArrayOf(1, 2, 3, 4)
        val zip = CloudBackupArchive.pack(
            values = values,
            photos = mapOf("$id.jpg" to photo),
            exportedAt = "2026-09-10T12:00:00Z",
            appVersion = "7.0",
        )
        val unpack = CloudBackupArchive.unpack(zip)
        assertEquals(CloudBackupPolicy.FORMAT, unpack.document.format)
        assertEquals(1, unpack.document.format_version)
        assertEquals("""[{"id":"$id","name":"Oats"}]""", unpack.document.payload.values.getValue("foodEntries").s)
        assertFalse(unpack.document.payload.values.containsKey("healthChangesToken"))
        assertFalse(unpack.document.payload.values.containsKey("healthFoodRestoreDone"))
        assertTrue(unpack.photos.containsKey("$id.jpg"))
        assertEquals(photo.toList(), unpack.photos.getValue("$id.jpg").toList())
    }

    @Test
    fun newerFormatVersionFailsClosed() {
        val unpack = CloudBackupArchive.unpack(
            CloudBackupArchive.pack(
                values = mapOf("useMetric" to CloudBackupValue.bool(true)),
                photos = emptyMap(),
                exportedAt = "2026-09-10T12:00:00Z",
                appVersion = "7.0",
            )
        )
        val newer = unpack.document.copy(format_version = CloudBackupPolicy.VERSION + 1)
        val encoded = kotlinx.serialization.json.Json.encodeToString(
            CloudBackupDocument.serializer(),
            newer,
        ).toByteArray()
        val rawOut = java.io.ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(rawOut).use { zipOut ->
            zipOut.putNextEntry(java.util.zip.ZipEntry(CloudBackupPolicy.PAYLOAD_NAME))
            zipOut.write(encoded)
            zipOut.closeEntry()
        }
        val error = runCatching { CloudBackupArchive.unpack(rawOut.toByteArray()) }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
        assertTrue(error!!.message!!.contains("newer"))
    }

    @Test
    fun unchangedHashSkipsWork() {
        val values = mapOf("foodEntries" to CloudBackupValue.string("[]"))
        val hash1 = CloudBackupArchive.contentHash(values, emptyMap())
        val hash2 = CloudBackupArchive.contentHash(values, emptyMap())
        assertEquals(hash1, hash2)
        val hash3 = CloudBackupArchive.contentHash(
            mapOf("foodEntries" to CloudBackupValue.string("[1]")),
            emptyMap(),
        )
        assertTrue(hash1 != hash3)
    }

    @Test
    fun coachChatHistoryAndHealthHubDeviceStateNeverEnterTheArchive() {
        val values = mapOf(
            "coachChatHistory" to CloudBackupValue.string("""[{"role":"user","content":"my resting HR is 52"}]"""),
            "healthHubPromptedVersion" to CloudBackupValue.int(1),
            "healthHubLastSyncAt" to CloudBackupValue.string("2026-09-14T12:00:00Z"),
            "healthHubRateLimitedUntil" to CloudBackupValue.string("2026-09-14T12:45:00Z"),
            // Cloud-backed hub preferences do travel.
            "healthHubEnabled" to CloudBackupValue.bool(true),
            "coachHealthDataEnabled" to CloudBackupValue.bool(true),
            "coachHealthDataConsentedAt" to CloudBackupValue.string("2026-09-14T12:00:00Z"),
            "healthHomeTiles" to CloudBackupValue.string("steps,sleep"),
        )
        val unpack = CloudBackupArchive.unpack(
            CloudBackupArchive.pack(values = values, photos = emptyMap(), exportedAt = "2026-09-14T12:00:00Z", appVersion = "7.0")
        )
        val kept = unpack.document.payload.values.keys
        for (excluded in listOf("coachChatHistory", "healthHubPromptedVersion", "healthHubLastSyncAt", "healthHubRateLimitedUntil")) {
            assertFalse("$excluded must be excluded", excluded in kept)
        }
        assertEquals(
            setOf("healthHubEnabled", "coachHealthDataEnabled", "coachHealthDataConsentedAt", "healthHomeTiles"),
            kept
        )
        // No key in the archive may carry a health sample table name or the DB file.
        assertTrue(kept.none { it.startsWith("health_") || it.contains("ayuvo_health") })
    }

    @Test
    fun healthRecordsPreferencesNeverEnterTheArchive() {
        val values = mapOf(
            "healthRecordsViewMode" to CloudBackupValue.string("grid"),
            "healthRecordsAiMode" to CloudBackupValue.string("cloud"),
            "healthHubEnabled" to CloudBackupValue.bool(true),
        )
        val unpack = CloudBackupArchive.unpack(
            CloudBackupArchive.pack(values = values, photos = emptyMap(), exportedAt = "2026-09-15T12:00:00Z", appVersion = "7.0")
        )
        assertEquals(setOf("healthHubEnabled"), unpack.document.payload.values.keys)
    }

    @Test
    fun rejectsPathTraversalPhotoNames() {
        assertEquals("secret.jpg", CloudBackupPolicy.safePhotoName("../secret.jpg"))
        assertEquals("meal.jpg", CloudBackupPolicy.safePhotoName("photos/meal.jpg"))
        assertEquals(null, CloudBackupPolicy.safePhotoName("notes.txt"))
    }
}
