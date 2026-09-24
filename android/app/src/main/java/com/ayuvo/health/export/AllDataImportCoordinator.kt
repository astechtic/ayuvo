package com.ayuvo.health.export

import android.net.Uri
import android.util.Log
import com.ayuvo.health.AppContainer
import com.ayuvo.health.coach.export.CoachChatArchiveReader
import com.ayuvo.health.medications.export.MedicationsArchive
import com.ayuvo.health.records.backup.RecordsArchiveFormat
import com.ayuvo.health.services.health.HealthSyncTrigger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipInputStream

/** What happened to one section of an Import All Data run. */
sealed interface AllDataImportOutcome {
    val section: String

    /** [count] rows added or updated (records: records added). */
    data class Imported(override val section: String, val count: Long) : AllDataImportOutcome
    data class Skipped(override val section: String, val reason: AllDataImportPlan.SkipReason) : AllDataImportOutcome
    data class Failed(override val section: String, val message: String?) : AllDataImportOutcome
}

sealed interface AllDataImportUi {
    data object Idle : AllDataImportUi
    data object Reading : AllDataImportUi
    data class Invalid(val reason: AllDataImportPlan.Invalid?) : AllDataImportUi
    data class Preview(val plan: AllDataImportPlan.Plan) : AllDataImportUi
    data class Running(val section: String, val number: Int, val total: Int) : AllDataImportUi
    data class Done(val outcomes: List<AllDataImportOutcome>) : AllDataImportUi
}

/**
 * Settings › Backup & Export › Import All Data: reads an [AllDataExportArchive] zip picked with
 * SAF, shows its [AllDataImportPlan] and, once confirmed, hands each section to the importer that
 * reads that format (settings backup restore, DiaryImporter, HealthDataImporter, medications merge,
 * Health Records archive restore). Runs on the app scope so leaving Settings does not cancel it; a failing
 * section is reported and the rest still run.
 */
class AllDataImportCoordinator(private val container: AppContainer) {
    private val _ui = MutableStateFlow<AllDataImportUi>(AllDataImportUi.Idle)
    val ui: StateFlow<AllDataImportUi> = _ui.asStateFlow()

    private var pendingUri: Uri? = null

    val busy: Boolean get() = _ui.value is AllDataImportUi.Reading || _ui.value is AllDataImportUi.Running

    fun open(uri: Uri) {
        if (busy) return
        _ui.value = AllDataImportUi.Reading
        container.scope.launch {
            val result = runCatching { withContext(Dispatchers.IO) { readPlan(uri) } }
                .onFailure { Log.w(TAG, "Couldn't read import file: ${it.javaClass.simpleName}") }
                .getOrNull()
            _ui.value = when (result) {
                is AllDataImportPlan.Result.Ok -> {
                    pendingUri = uri
                    AllDataImportUi.Preview(result.plan)
                }
                is AllDataImportPlan.Result.Failed -> AllDataImportUi.Invalid(result.reason)
                null -> AllDataImportUi.Invalid(null)
            }
        }
    }

    fun confirm() {
        val preview = _ui.value as? AllDataImportUi.Preview ?: return
        val uri = pendingUri ?: return
        val sections = preview.plan.sections
        val importing = sections.filter { it.imports }
        _ui.value = AllDataImportUi.Running(importing.firstOrNull()?.id.orEmpty(), 1, importing.size.coerceAtLeast(1))
        container.scope.launch {
            val outcomes = runCatching { run(uri, sections) }
                .getOrElse { error ->
                    Log.w(TAG, "Import all data failed: ${error.javaClass.simpleName}")
                    importing.map { AllDataImportOutcome.Failed(it.id, error.localizedMessage) }
                }
            pendingUri = null
            _ui.value = AllDataImportUi.Done(outcomes)
        }
    }

    fun dismiss() {
        if (busy) return
        pendingUri = null
        _ui.value = AllDataImportUi.Idle
    }

    private fun readPlan(uri: Uri): AllDataImportPlan.Result {
        val entries = LinkedHashSet<String>()
        var manifest: String? = null
        val input = container.appContext.contentResolver.openInputStream(uri) ?: error("Couldn't open the file")
        ZipInputStream(input.buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory) continue
                entries += entry.name
                if (entry.name == AllDataExportArchive.MANIFEST_NAME && manifest == null) {
                    val bytes = zip.readNBytesCompat(MAX_MANIFEST_BYTES + 1)
                    if (bytes.size > MAX_MANIFEST_BYTES) return AllDataImportPlan.Result.Failed(AllDataImportPlan.Invalid.NOT_AYUVO)
                    manifest = bytes.toString(Charsets.UTF_8)
                }
            }
        }
        return AllDataImportPlan.plan(manifest, entries)
    }

    private suspend fun run(uri: Uri, sections: List<AllDataImportPlan.Section>): List<AllDataImportOutcome> = withContext(Dispatchers.IO) {
        val work = File(container.appContext.cacheDir, "all-data-import").apply {
            deleteRecursively()
            mkdirs()
        }
        try {
            val files = extract(uri, sections.filter { it.imports }, work)
            val importing = sections.filter { it.imports }
            sections.map { section ->
                val skip = section.skip
                if (skip != null) return@map AllDataImportOutcome.Skipped(section.id, skip)
                _ui.value = AllDataImportUi.Running(section.id, importing.indexOf(section) + 1, importing.size)
                val file = files[section.id]
                    ?: return@map AllDataImportOutcome.Failed(section.id, null)
                runCatching { AllDataImportOutcome.Imported(section.id, import(section.id, file)) }
                    .onFailure { Log.w(TAG, "Section ${section.id} failed: ${it.javaClass.simpleName}") }
                    .getOrElse { AllDataImportOutcome.Failed(section.id, it.localizedMessage) }
                    .also { runCatching { file.delete() } }
            }
        } finally {
            runCatching { work.deleteRecursively() }
        }
    }

    /** One streaming pass over the zip, copying each wanted entry to its own temp file. */
    private fun extract(uri: Uri, wanted: List<AllDataImportPlan.Section>, work: File): Map<String, File> {
        val byEntry = wanted.associateBy { it.entry }
        val out = HashMap<String, File>()
        val input = container.appContext.contentResolver.openInputStream(uri) ?: error("Couldn't open the file")
        ZipInputStream(input.buffered()).use { zip ->
            while (out.size < byEntry.size) {
                val entry = zip.nextEntry ?: break
                val section = byEntry[entry.name] ?: continue
                if (section.id in out) continue
                val file = File(work, section.id)
                file.outputStream().use { zip.copyTo(it, BUFFER) }
                out[section.id] = file
            }
        }
        return out
    }

    private suspend fun import(section: String, file: File): Long = when (section) {
        AllDataExportCoordinator.SECTION_APP_BACKUP -> {
            // Same restore as Google Drive, minus the Drive state: settings, profile, logs, photos.
            container.cloudBackup.applyArchive(file.readBytes(), fromFile = true)
            1L
        }
        AllDataExportCoordinator.SECTION_FOOD_DIARY -> {
            require(file.length() <= DiaryImporter.MAXIMUM_FILE_SIZE) { "This file is too large to import." }
            val preview = DiaryImporter.parse(file.readText())
            val current = container.foodRepository.entries.first()
            val updated = withContext(Dispatchers.Default) {
                DiaryImporter.applying(preview, current, DiaryImportMode.MERGE)
            }
            container.foodRepository.replaceFromImport(updated)
            container.waterRepository.importDiary(preview, DiaryImportMode.MERGE)
            (preview.entries.size + preview.waterEntries.size).toLong()
        }
        AllDataExportCoordinator.SECTION_HEALTH_DATA -> {
            val result = container.healthSync.withPaused {
                file.inputStream().buffered().use {
                    HealthDataImporter(container.healthStore).apply(it, HealthImportMode.MERGE, file.length())
                }
            }
            container.requestHealthSync(HealthSyncTrigger.IMPORT_COMPLETED)
            (result.inserted + result.updated).toLong()
        }
        AllDataExportCoordinator.SECTION_MEDICATIONS -> {
            val archive = MedicationsArchive.read(file.readBytes())
            val result = container.medicationsStore.importArchive(archive, System.currentTimeMillis())
            check(result.ok) { result.error ?: "Couldn't import medications" }
            container.medicationReminders.replanAsync()
            (result.inserted + result.updated).toLong()
        }
        AllDataExportCoordinator.SECTION_HEALTH_RECORDS -> {
            container.recordsBackup.importFile(file, RecordsArchiveFormat.ImportMode.MERGE).importedRecords.toLong()
        }
        AllDataExportCoordinator.SECTION_COACH_CHATS -> {
            // Merge, never delete: a chat the user removed here stays removed (docs/coach.md §11).
            val result = CoachChatArchiveReader(container.coachRepository).import(file)
            check(result.ok) { importErrorMessage(result.error) }
            result.imported.toLong()
        }
        else -> error("Unknown section")
    }

    /** The two refusals the archive reader can return, in the user's words. */
    private fun importErrorMessage(error: String?): String = when (error) {
        "newer_version" -> "This file was made by a newer version of Ayuvo"
        else -> "Couldn't import chats"
    }

    private fun ZipInputStream.readNBytesCompat(limit: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        while (out.size() < limit) {
            val n = read(buffer, 0, minOf(buffer.size, limit - out.size()))
            if (n < 0) break
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }

    companion object {
        private const val TAG = "AyuvoImportAll"
        private const val BUFFER = 64 * 1024
        private const val MAX_MANIFEST_BYTES = 1024 * 1024
    }
}
