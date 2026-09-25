package com.ayuvo.health.export

import com.ayuvo.health.backup.CloudBackupPolicy
import com.ayuvo.health.coach.export.CoachChatArchiveFormat
import com.ayuvo.health.medications.logic.MedicationConstants
import com.ayuvo.health.records.backup.RecordsArchiveFormat
import kotlinx.serialization.SerializationException

/**
 * Settings › Backup & Export › Import All Data, the pure part: validates the `manifest.json` of an
 * [AllDataExportArchive] zip (made on Android or iOS) and decides which sections to import, in
 * which order and which to skip. [AllDataImportCoordinator] does the file work.
 */
object AllDataImportPlan {
    const val PLATFORM_ANDROID = "android"
    const val PLATFORM_IOS = "ios"

    /** Import order: settings first (it replaces preferences), then the merged stores. */
    val ORDER = listOf(
        AllDataExportCoordinator.SECTION_APP_BACKUP,
        AllDataExportCoordinator.SECTION_PORTABLE,
        AllDataExportCoordinator.SECTION_FOOD_DIARY,
        AllDataExportCoordinator.SECTION_HEALTH_DATA,
        AllDataExportCoordinator.SECTION_MEDICATIONS,
        AllDataExportCoordinator.SECTION_HEALTH_RECORDS,
        AllDataExportCoordinator.SECTION_COACH_CHATS
    )

    /** The format each section's file must be in. */
    val EXPECTED_FORMAT = mapOf(
        AllDataExportCoordinator.SECTION_APP_BACKUP to CloudBackupPolicy.FORMAT,
        AllDataExportCoordinator.SECTION_PORTABLE to PortableFormat.FORMAT,
        AllDataExportCoordinator.SECTION_FOOD_DIARY to FOOD_DIARY_FORMAT,
        AllDataExportCoordinator.SECTION_HEALTH_DATA to HealthExportFormat.FORMAT,
        AllDataExportCoordinator.SECTION_MEDICATIONS to MedicationConstants.ARCHIVE_FORMAT,
        AllDataExportCoordinator.SECTION_HEALTH_RECORDS to RecordsArchiveFormat.FORMAT,
        AllDataExportCoordinator.SECTION_COACH_CHATS to CoachChatArchiveFormat.FORMAT
    )

    enum class Invalid { NOT_AYUVO, NEWER_VERSION }

    enum class SkipReason {
        /** The settings backup keeps platform-specific preference keys; an iPhone one can't be applied here. */
        OTHER_PLATFORM,
        /** The applied settings backup already holds this part (the food diary and water log, or the portable profile and logs). */
        IN_APP_BACKUP,
        /** The section's file is in a format this version can't read. */
        UNSUPPORTED
    }

    data class Section(
        val id: String,
        val entry: String,
        val format: String,
        val counts: Map<String, Long>,
        val skip: SkipReason? = null
    ) {
        val imports: Boolean get() = skip == null
    }

    data class Plan(val manifest: AllDataExportManifest, val sections: List<Section>) {
        val importCount: Int get() = sections.count { it.imports }
    }

    sealed interface Result {
        data class Ok(val plan: Plan) : Result
        data class Failed(val reason: Invalid) : Result
    }

    fun parseManifest(text: String): AllDataExportManifest? = try {
        AllDataExportArchive.json.decodeFromString(AllDataExportManifest.serializer(), text)
    } catch (_: SerializationException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

    /**
     * [manifestText] is `manifest.json` (null when the zip has none), [entries] every entry name in
     * the zip. A known section whose file is unsafe or missing makes the whole file invalid;
     * unknown sections are ignored.
     */
    fun plan(manifestText: String?, entries: Set<String>, platform: String = PLATFORM_ANDROID): Result {
        val manifest = manifestText?.let(::parseManifest) ?: return Result.Failed(Invalid.NOT_AYUVO)
        if (manifest.app != AllDataExportArchive.APP || manifest.format != AllDataExportArchive.FORMAT) {
            return Result.Failed(Invalid.NOT_AYUVO)
        }
        if (manifest.format_version > AllDataExportArchive.FORMAT_VERSION) return Result.Failed(Invalid.NEWER_VERSION)
        if (manifest.format_version < 1) return Result.Failed(Invalid.NOT_AYUVO)

        val byId = LinkedHashMap<String, AllDataExportFile>()
        for (file in manifest.files) {
            if (file.section !in ORDER) continue
            if (!isSafeEntry(file.name) || file.name !in entries || file.section in byId) return Result.Failed(Invalid.NOT_AYUVO)
            byId[file.section] = file
        }

        var appBackupApplied = false
        val sections = ORDER.mapNotNull { id ->
            val file = byId[id] ?: return@mapNotNull null
            val skip = when {
                file.format != EXPECTED_FORMAT[id] -> SkipReason.UNSUPPORTED
                id == AllDataExportCoordinator.SECTION_APP_BACKUP && manifest.platform != platform -> SkipReason.OTHER_PLATFORM
                // The portable part is what makes an iPhone zip restore here, so it is never an OTHER_PLATFORM skip.
                id == AllDataExportCoordinator.SECTION_PORTABLE && appBackupApplied -> SkipReason.IN_APP_BACKUP
                id == AllDataExportCoordinator.SECTION_FOOD_DIARY && appBackupApplied -> SkipReason.IN_APP_BACKUP
                else -> null
            }
            if (id == AllDataExportCoordinator.SECTION_APP_BACKUP && skip == null) appBackupApplied = true
            Section(id = id, entry = file.name, format = file.format, counts = file.counts, skip = skip)
        }
        return Result.Ok(Plan(manifest, sections))
    }

    /** Relative, no `..`, no backslashes or drive letters, not the manifest itself. */
    fun isSafeEntry(name: String): Boolean {
        if (name.isBlank() || name.length > 512) return false
        if (name.startsWith("/") || name.contains('\\') || name.contains(':') || name.contains('\u0000')) return false
        if (name == AllDataExportArchive.MANIFEST_NAME || name.endsWith("/")) return false
        return name.split('/').none { it.isEmpty() || it == "." || it == ".." }
    }

    const val FOOD_DIARY_FORMAT = "ayuvo-food-diary"
}
