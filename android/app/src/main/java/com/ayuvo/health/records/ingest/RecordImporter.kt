package com.ayuvo.health.records.ingest

import android.content.ContentResolver
import android.content.Context
import android.media.ExifInterface
import android.net.Uri
import android.provider.OpenableColumns
import com.ayuvo.health.R
import com.ayuvo.health.records.data.RecordFileStore
import com.ayuvo.health.records.data.RecordsStore
import com.ayuvo.health.records.model.DateMethod
import com.ayuvo.health.records.model.DatePrecision
import com.ayuvo.health.records.model.HealthRecord
import com.ayuvo.health.records.model.ImportMethod
import com.ayuvo.health.records.model.ProcessingStatus
import com.ayuvo.health.records.model.RecordCategory
import com.ayuvo.health.records.model.RecordFileType
import com.ayuvo.health.records.model.RecordPage
import com.ayuvo.health.records.model.RecordProcessingUpdate
import com.ayuvo.health.records.model.RecordSource
import com.ayuvo.health.records.model.RecordType
import com.ayuvo.health.records.model.TextSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.UUID

/** How a record arrived (docs/health-records.md §3 `source` / `import_method`). */
data class ImportSpec(
    val source: RecordSource,
    val method: ImportMethod,
    val userTitle: String? = null,
    val sourceApp: String? = null,
    val recordType: RecordType = RecordType.OTHER
)

sealed interface ImportOutcome {
    data class Saved(val record: HealthRecord, val duplicateOf: HealthRecord?) : ImportOutcome
    data class Refused(val reason: RefusalReason, val filename: String?) : ImportOutcome
}

enum class RefusalReason { TOO_LARGE, UNREADABLE }

/**
 * Phase 1 import (§4): stream-copy to `<id>/original.tmp` while hashing and sniffing, move it
 * into place, insert the row and return. [process] then fills page count, thumbnail and a
 * metadata date; its failures never remove the record.
 */
class RecordImporter(
    private val context: Context,
    private val store: RecordsStore,
    private val files: RecordFileStore,
    private val thumbnails: ThumbnailMaker = ThumbnailMaker()
) {
    private val resolver: ContentResolver get() = context.contentResolver

    suspend fun importUri(uri: Uri, spec: ImportSpec): ImportOutcome = withContext(Dispatchers.IO) {
        // Scanner output files carry generated names, which make poor titles.
        val name = if (spec.source == RecordSource.SCAN) null else displayName(uri)
        val stream = runCatching { resolver.openInputStream(uri) }.getOrNull()
            ?: return@withContext ImportOutcome.Refused(RefusalReason.UNREADABLE, name)
        importStream(stream, name, spec)
    }

    suspend fun importBytes(bytes: ByteArray, filename: String?, spec: ImportSpec): ImportOutcome =
        withContext(Dispatchers.IO) { importStream(ByteArrayInputStream(bytes), filename, spec) }

    /** Pasted text and notes: `original.txt` plus one plain page. */
    suspend fun importText(text: String, spec: ImportSpec): ImportOutcome = withContext(Dispatchers.IO) {
        importStream(ByteArrayInputStream(text.toByteArray(Charsets.UTF_8)), null, spec)
    }

    private suspend fun importStream(input: InputStream, filename: String?, spec: ImportSpec): ImportOutcome {
        val id = UUID.randomUUID().toString()
        val temp = files.tempOriginal(id)
        val digest = MessageDigest.getInstance("SHA-256")
        val text = Utf8TextDetector()
        val head = ByteArray(MimeSniffer.HEAD_BYTES)
        var headLength = 0
        var total = 0L
        try {
            input.use { source ->
                FileOutputStream(temp).use { out ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    while (true) {
                        val read = source.read(buffer)
                        if (read < 0) break
                        if (read == 0) continue
                        total += read
                        if (total > MAX_FILE_BYTES) {
                            throw TooLargeException()
                        }
                        if (headLength < head.size) {
                            val n = minOf(head.size - headLength, read)
                            System.arraycopy(buffer, 0, head, headLength, n)
                            headLength += n
                        }
                        digest.update(buffer, 0, read)
                        text.update(buffer, 0, read)
                        out.write(buffer, 0, read)
                    }
                    out.fd.sync()
                }
            }
        } catch (e: TooLargeException) {
            files.deleteRecord(id)
            return ImportOutcome.Refused(RefusalReason.TOO_LARGE, filename)
        } catch (e: Exception) {
            files.deleteRecord(id)
            return ImportOutcome.Refused(RefusalReason.UNREADABLE, filename)
        }

        val sniffed = MimeSniffer.sniff(head.copyOf(headLength), text.finish(), filename)
        val relativePath = files.commitOriginal(id, temp, sniffed.extension)
        val checksum = digest.digest().joinToString("") { "%02x".format(it) }
        val now = System.currentTimeMillis()

        val pages = if (sniffed.fileType == RecordFileType.TEXT) {
            val file = files.resolve(relativePath)
            val body = file?.let { readTextCapped(it) }
            listOf(RecordPage(recordId = id, pageIndex = 0, text = body, textSource = TextSource.PLAIN))
        } else {
            emptyList()
        }

        val type = spec.recordType
        val category = if (type == RecordType.OTHER && spec.source == RecordSource.NOTE) {
            RecordCategory.PERSONAL_NOTES
        } else {
            type.defaultCategory
        }
        val title = RecordTitles.derive(
            userTitle = spec.userTitle,
            filename = filename,
            typeLabel = fallbackTypeLabel(spec),
            dateLabel = LocalDate.now().format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))
        )
        val record = HealthRecord(
            id = id,
            title = title,
            recordType = type,
            category = category,
            source = spec.source,
            importMethod = spec.method,
            sourceApp = spec.sourceApp,
            originalFilename = filename,
            createdMs = now,
            updatedMs = now,
            mimeType = sniffed.mimeType,
            fileType = sniffed.fileType,
            fileSize = total,
            pageCount = pages.size,
            filePath = relativePath,
            checksumSha256 = checksum,
            processingStatus = ProcessingStatus.SAVED
        )
        val saved = try {
            store.insert(record, pages)
        } catch (e: Exception) {
            files.deleteRecord(id)
            return ImportOutcome.Refused(RefusalReason.UNREADABLE, filename)
        }
        val duplicate = runCatching { store.findByChecksum(checksum, excludingId = id) }.getOrNull()
        return ImportOutcome.Saved(saved, duplicate)
    }

    /** Background stage: page count, thumbnail and a basic metadata date. Never throws. */
    suspend fun process(record: HealthRecord) = withContext(Dispatchers.IO) {
        val file = files.resolve(record.filePath)
        if (file == null || !file.isFile) {
            runCatching {
                store.updateProcessing(record.id, RecordProcessingUpdate(status = ProcessingStatus.FAILED_PARTIAL, error = ThumbnailMaker.ERROR_UNREADABLE))
            }
            return@withContext
        }
        val result = runCatching { thumbnails.make(file, record.fileType, record.mimeType) }
            .getOrElse { ThumbnailMaker.Result(0, null, ThumbnailMaker.ERROR_UNREADABLE) }
        val thumbPath = result.thumbnail?.let { bitmap ->
            runCatching { files.writeThumbnail(record.id, bitmap) }.getOrNull().also { bitmap.recycle() }
        }
        val date = runCatching { metadataDate(file, record) }.getOrNull()
        runCatching {
            store.updateProcessing(
                record.id,
                RecordProcessingUpdate(
                    pageCount = result.pageCount,
                    thumbnailPath = thumbPath,
                    documentDate = date?.toString(),
                    documentDatePrecision = date?.let { DatePrecision.DAY },
                    documentDateMethod = date?.let { DateMethod.FILE_METADATA },
                    status = if (result.error == null) ProcessingStatus.READY else ProcessingStatus.FAILED_PARTIAL,
                    error = result.error
                )
            )
        }
    }

    private fun metadataDate(file: File, record: HealthRecord): LocalDate? {
        val fromFile = when (record.fileType) {
            RecordFileType.PDF -> pdfCreationDate(file)
            RecordFileType.IMAGE -> runCatching {
                RecordDates.fromExif(ExifInterface(file.absolutePath).getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL))
            }.getOrNull()
            else -> null
        }
        return fromFile ?: RecordDates.fromFilename(record.originalFilename)
    }

    private fun pdfCreationDate(file: File): LocalDate? {
        val length = file.length()
        RandomAccessFile(file, "r").use { raf ->
            fun window(start: Long): ByteArray {
                val size = minOf(RecordDates.PDF_SCAN_BYTES.toLong(), length - start).toInt().coerceAtLeast(0)
                val bytes = ByteArray(size)
                raf.seek(start)
                raf.readFully(bytes)
                return bytes
            }
            RecordDates.fromPdfBytes(window(0))?.let { return it }
            if (length > RecordDates.PDF_SCAN_BYTES) {
                return RecordDates.fromPdfBytes(window(length - RecordDates.PDF_SCAN_BYTES))
            }
        }
        return null
    }

    private fun readTextCapped(file: File): String = file.inputStream().use { input ->
        val bytes = ByteArray(minOf(file.length(), MAX_TEXT_BYTES.toLong()).toInt())
        var offset = 0
        while (offset < bytes.size) {
            val read = input.read(bytes, offset, bytes.size - offset)
            if (read < 0) break
            offset += read
        }
        // Truncating mid-character leaves a replacement char at worst; the original stays intact.
        String(bytes, 0, offset, Charsets.UTF_8)
    }

    private fun fallbackTypeLabel(spec: ImportSpec): String? = when (spec.source) {
        RecordSource.SCAN -> context.getString(R.string.records_title_scan)
        RecordSource.CAMERA -> context.getString(R.string.records_title_photo)
        RecordSource.PASTE -> context.getString(R.string.records_title_pasted)
        RecordSource.NOTE -> context.getString(R.string.records_title_note)
        RecordSource.PHOTOS -> context.getString(R.string.records_title_photo)
        else -> null
    }

    fun displayName(uri: Uri): String? {
        if (uri.scheme == ContentResolver.SCHEME_FILE) return uri.lastPathSegment
        return runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst() && !c.isNull(0)) c.getString(0) else null
            }
        }.getOrNull() ?: uri.lastPathSegment?.takeIf { it.contains('.') }
    }

    private class TooLargeException : Exception()

    companion object {
        const val MAX_FILE_BYTES = 512L * 1024 * 1024
        const val MAX_TEXT_BYTES = 2 * 1024 * 1024
        private const val BUFFER_SIZE = 64 * 1024
    }
}
