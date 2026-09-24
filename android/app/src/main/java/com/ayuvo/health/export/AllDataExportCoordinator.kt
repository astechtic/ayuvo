package com.ayuvo.health.export

import android.net.Uri
import android.util.Log
import com.ayuvo.health.AppContainer
import com.ayuvo.health.BuildConfig
import com.ayuvo.health.backup.CloudBackupArchive
import com.ayuvo.health.coach.export.CoachChatArchiveFormat
import com.ayuvo.health.coach.export.CoachChatArchiveWriter
import com.ayuvo.health.backup.CloudBackupPolicy
import com.ayuvo.health.data.health.HealthDatabase
import com.ayuvo.health.medications.export.MedicationsArchive
import com.ayuvo.health.medications.logic.MedicationConstants
import com.ayuvo.health.records.backup.RecordsArchiveFormat
import com.ayuvo.health.records.backup.RecordsBackupCoordinator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** The steps of one "Export All Data" run, in order (progress + the UI's step label). */
enum class AllDataExportStep { FOOD_DIARY, HEALTH_DATA, MEDICATIONS, HEALTH_RECORDS, COACH_CHATS, APP_BACKUP, WRITING }

sealed interface AllDataExportOutcome {
    data class Done(val fileCount: Int, val skippedSections: List<String>) : AllDataExportOutcome
    data object NothingToExport : AllDataExportOutcome
    data class Failed(val message: String?) : AllDataExportOutcome
}

data class AllDataExportUi(
    val running: Boolean = false,
    val step: AllDataExportStep? = null,
    val outcome: AllDataExportOutcome? = null
) {
    val stepNumber: Int get() = (step?.ordinal ?: 0) + 1
    val stepCount: Int get() = AllDataExportStep.entries.size
}

/**
 * Settings › Backup & Export › Export All Data: runs every existing exporter into a private
 * temp folder, then assembles one [AllDataExportArchive] zip at the SAF [Uri]. Runs on the app
 * scope so leaving the Settings page does not cancel a long records archive. A section that is
 * empty, not set up, or whose exporter fails is skipped and listed in the manifest.
 */
class AllDataExportCoordinator(private val container: AppContainer) {
    private val _ui = MutableStateFlow(AllDataExportUi())
    val ui: StateFlow<AllDataExportUi> = _ui.asStateFlow()

    fun start(uri: Uri) {
        if (_ui.value.running) return
        _ui.value = AllDataExportUi(running = true, step = AllDataExportStep.FOOD_DIARY)
        container.scope.launch {
            val outcome = runCatching { export(uri) }
                .onFailure { Log.w(TAG, "Export all data failed: ${it.javaClass.simpleName}") }
                .getOrElse { AllDataExportOutcome.Failed(it.localizedMessage) }
            _ui.value = AllDataExportUi(running = false, step = null, outcome = outcome)
        }
    }

    fun consumeOutcome() {
        _ui.update { it.copy(outcome = null) }
    }

    private fun step(step: AllDataExportStep) {
        _ui.update { it.copy(step = step) }
    }

    private suspend fun export(uri: Uri): AllDataExportOutcome = withContext(Dispatchers.IO) {
        val context = container.appContext
        val work = File(context.cacheDir, "all-data-export").apply {
            deleteRecursively()
            mkdirs()
        }
        try {
            val sections = mutableListOf<AllDataExportArchive.Section>()
            val skipped = linkedMapOf<String, String>()
            suspend fun section(id: String, step: AllDataExportStep, build: suspend () -> SectionResult) {
                step(step)
                when (val r = runCatching { build() }.getOrElse {
                    Log.w(TAG, "Section $id failed: ${it.javaClass.simpleName}")
                    SectionResult.Skip(AllDataExportArchive.REASON_FAILED)
                }) {
                    is SectionResult.Ok -> sections += r.section
                    is SectionResult.Skip -> skipped[id] = r.reason
                }
            }

            section(SECTION_FOOD_DIARY, AllDataExportStep.FOOD_DIARY) { foodDiary(work) }
            section(SECTION_HEALTH_DATA, AllDataExportStep.HEALTH_DATA) { healthData(work) }
            section(SECTION_MEDICATIONS, AllDataExportStep.MEDICATIONS) { medications(work) }
            section(SECTION_HEALTH_RECORDS, AllDataExportStep.HEALTH_RECORDS) { records(work) }
            section(SECTION_COACH_CHATS, AllDataExportStep.COACH_CHATS) { coachChats(work) }
            section(SECTION_APP_BACKUP, AllDataExportStep.APP_BACKUP) { appBackup(work) }

            if (sections.isEmpty()) return@withContext AllDataExportOutcome.NothingToExport
            step(AllDataExportStep.WRITING)
            context.contentResolver.openOutputStream(uri)?.use { out ->
                AllDataExportArchive.write(
                    out = out,
                    sections = sections,
                    skipped = skipped,
                    createdAt = Instant.now(),
                    appVersion = BuildConfig.VERSION_NAME
                )
            } ?: error("Couldn't open the destination file")
            AllDataExportOutcome.Done(sections.size, skipped.keys.toList())
        } finally {
            runCatching { work.deleteRecursively() }
        }
    }

    private sealed interface SectionResult {
        data class Ok(val section: AllDataExportArchive.Section) : SectionResult
        data class Skip(val reason: String) : SectionResult
    }

    /** Food diary: the "All time" diary JSON (read back by Import All Data and DiaryImporter). */
    private suspend fun foodDiary(work: File): SectionResult {
        val entries = container.foodRepository.entries.first()
        val water = container.waterRepository.entries.first()
        if (entries.isEmpty() && water.isEmpty()) return SectionResult.Skip(AllDataExportArchive.REASON_EMPTY)
        val (lo, hi) = DiaryExporter.resolveRange(DiaryRange.ALL_TIME, LocalDate.now(), LocalDate.now(), entries, water)
        val (name, content) = DiaryExporter.build(
            entries = entries, start = lo, end = hi, format = DiaryFormat.JSON,
            profile = container.profileRepository.current(), mealDisplay = { it.name }, waterEntries = water
        ) ?: return SectionResult.Skip(AllDataExportArchive.REASON_EMPTY)
        val file = File(work, name).apply { writeText(content) }
        val zone = ZoneId.systemDefault()
        val days = (entries.map { it.timestamp.atZone(zone).toLocalDate() } + water.map { it.date.atZone(zone).toLocalDate() }).toSet().size
        return SectionResult.Ok(
            AllDataExportArchive.Section(
                id = SECTION_FOOD_DIARY,
                format = "ayuvo-food-diary",
                path = "food-diary/$name",
                description = "Food diary (all time), JSON. Import with Settings › Backup & Export › Import All Data.",
                counts = mapOf("food_entries" to entries.size.toLong(), "water_entries" to water.size.toLong(), "days" to days.toLong()),
                source = file
            )
        )
    }

    /** Health Data mirror: the `ayuvo-health-data` zip. */
    private suspend fun healthData(work: File): SectionResult {
        if (!container.appContext.getDatabasePath(HealthDatabase.NAME).exists()) {
            return SectionResult.Skip(AllDataExportArchive.REASON_NOT_SET_UP)
        }
        val name = "Ayuvo-Health-Data-${LocalDate.now()}.zip"
        val file = File(work, name)
        val result = file.outputStream().use { HealthDataExporter(container.healthStore).write(it, BuildConfig.VERSION_NAME) }
        if (result.sampleCount == 0L && result.seriesCount == 0L) return SectionResult.Skip(AllDataExportArchive.REASON_EMPTY)
        return SectionResult.Ok(
            AllDataExportArchive.Section(
                id = SECTION_HEALTH_DATA,
                format = HealthExportFormat.FORMAT,
                path = "health-data/$name",
                description = "Synced Health Connect data (ayuvo-health-data zip). Import with Settings › Backup & Export › Import All Data.",
                counts = mapOf("samples" to result.sampleCount, "series_points" to result.seriesCount, "types" to result.typeCount.toLong()),
                source = file
            )
        )
    }

    /** Medications: the `ayuvo-medications.json` archive. */
    private suspend fun medications(work: File): SectionResult {
        if (!container.medicationsDatabaseExists()) return SectionResult.Skip(AllDataExportArchive.REASON_NOT_SET_UP)
        val snapshot = container.medicationsStore.exportSnapshot()
        if (snapshot.medications.isEmpty() && snapshot.schedules.isEmpty() && snapshot.doseLogs.isEmpty()) {
            return SectionResult.Skip(AllDataExportArchive.REASON_EMPTY)
        }
        val bytes = MedicationsArchive.write(snapshot, System.currentTimeMillis(), ZoneId.systemDefault().id, BuildConfig.VERSION_NAME)
        val file = File(work, MedicationsArchive.FILE_NAME).apply { writeBytes(bytes) }
        return SectionResult.Ok(
            AllDataExportArchive.Section(
                id = SECTION_MEDICATIONS,
                format = MedicationConstants.ARCHIVE_FORMAT,
                path = "medications/${MedicationsArchive.FILE_NAME}",
                description = "Medications, schedules and dose history (ayuvo-medications JSON). Import with Settings › Backup & Export › Import All Data.",
                counts = mapOf(
                    "medications" to snapshot.medications.size.toLong(),
                    "schedules" to snapshot.schedules.size.toLong(),
                    "dose_logs" to snapshot.doseLogs.size.toLong()
                ),
                source = file
            )
        )
    }

    /** Health Records: the portable `ayuvo-records` archive with originals. */
    private suspend fun records(work: File): SectionResult {
        if (!container.recordsDatabaseExists()) return SectionResult.Skip(AllDataExportArchive.REASON_NOT_SET_UP)
        val name = RecordsBackupCoordinator.defaultFileName()
        val file = File(work, name)
        // buildArchive leaves records_backup_state alone: this export is not a records backup.
        val result = container.recordsBackup.buildArchive(file)
        if (result.recordCount == 0) return SectionResult.Skip(AllDataExportArchive.REASON_EMPTY)
        return SectionResult.Ok(
            AllDataExportArchive.Section(
                id = SECTION_HEALTH_RECORDS,
                format = RecordsArchiveFormat.FORMAT,
                path = "health-records/$name",
                description = "Health Records with their original files (ayuvo-records archive). Import with Settings › Backup & Export › Import All Data.",
                counts = mapOf("records" to result.recordCount.toLong(), "files" to result.fileCount.toLong()),
                source = file
            )
        )
    }

    /** Coach chats: the `ayuvo-coach-chats` archive with the files the user attached (docs/coach.md §11). */
    private suspend fun coachChats(work: File): SectionResult {
        if (!container.coachDatabaseExists()) return SectionResult.Skip(AllDataExportArchive.REASON_NOT_SET_UP)
        val file = File(work, CoachChatArchiveFormat.FILE_NAME)
        val result = CoachChatArchiveWriter(container.coachRepository, BuildConfig.VERSION_NAME).export(file)
        if (result.isEmpty) return SectionResult.Skip(AllDataExportArchive.REASON_EMPTY)
        return SectionResult.Ok(
            AllDataExportArchive.Section(
                id = SECTION_COACH_CHATS,
                format = CoachChatArchiveFormat.FORMAT,
                path = "coach-chats/${CoachChatArchiveFormat.FILE_NAME}",
                description = "Coach conversations with the files you attached (ayuvo-coach-chats archive). Import with Settings › Backup & Export › Import All Data.",
                counts = mapOf(
                    "conversations" to result.conversations.toLong(),
                    "messages" to result.messages.toLong(),
                    "attachments" to result.attachments.toLong(),
                    "files" to result.files.toLong()
                ),
                source = file
            )
        )
    }

    /** App data: the same `ayuvo-backup.zip` the Google Drive backup uploads (settings, profile, logs, meal photos). */
    private suspend fun appBackup(work: File): SectionResult {
        val values = container.prefs.snapshotCloudBackupValues()
        val photos = linkedMapOf<String, ByteArray>()
        for (name in container.imageStore.listedFilenames()) {
            val safe = CloudBackupPolicy.safePhotoName(name) ?: continue
            container.imageStore.loadBytes(safe)?.let { photos[safe] = it }
        }
        if (values.isEmpty() && photos.isEmpty()) return SectionResult.Skip(AllDataExportArchive.REASON_EMPTY)
        val bytes = CloudBackupArchive.pack(
            values = values,
            photos = photos,
            exportedAt = Instant.now().toString(),
            appVersion = BuildConfig.VERSION_NAME
        )
        val file = File(work, CloudBackupPolicy.FILE_NAME).apply { writeBytes(bytes) }
        return SectionResult.Ok(
            AllDataExportArchive.Section(
                id = SECTION_APP_BACKUP,
                format = CloudBackupPolicy.FORMAT,
                path = "app-backup/${CloudBackupPolicy.FILE_NAME}",
                description = "Settings, profile and logs with meal photos; the same file Google Drive backup uploads (API keys are never included).",
                counts = mapOf(
                    "settings_values" to values.keys.count { it !in CloudBackupPolicy.excludedKeys }.toLong(),
                    "meal_photos" to photos.size.toLong()
                ),
                source = file
            )
        )
    }

    companion object {
        private const val TAG = "AyuvoExportAll"
        const val SECTION_FOOD_DIARY = "food_diary"
        const val SECTION_HEALTH_DATA = "health_data"
        const val SECTION_MEDICATIONS = "medications"
        const val SECTION_HEALTH_RECORDS = "health_records"
        const val SECTION_COACH_CHATS = "coach_chats"
        const val SECTION_APP_BACKUP = "app_backup"
    }
}
