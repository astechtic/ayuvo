package com.ayuvo.health.coach.processing

import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import com.ayuvo.health.coach.data.CoachFileStore
import com.ayuvo.health.coach.logic.CoachReference
import com.ayuvo.health.coach.model.AttachmentKind
import com.ayuvo.health.coach.model.ChatAttachment
import com.ayuvo.health.medications.logic.MedicationJson.str
import com.ayuvo.health.records.processing.OcrEngine
import com.ayuvo.health.records.processing.PageTextAssembler
import com.ayuvo.health.records.processing.TextStage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File

/**
 * Turns a file the user attached into the text Coach will send (docs/coach.md §6).
 *
 * Everything happens **on device**: a PDF's text layer first, ML Kit OCR when the layer is empty,
 * plain text read as-is. The pages then run through the shared `attachment_excerpt`, which drops
 * every line the records redaction rule would drop — so a lab PDF attached to chat is treated
 * exactly like a record read through `records_get`.
 *
 * Only the resulting excerpt is sent, and rule 4 means the user can see it: the composer chip opens
 * a sheet showing this exact text.
 */
class CoachAttachmentProcessor(
    private val context: Context,
    private val files: CoachFileStore,
    private val ocr: () -> OcrEngine
) {
    data class Outcome(
        val attachment: ChatAttachment,
        /**
         * True when the file yielded no readable text at all (an unreadable scan, or everything
         * redacted). The composer says so rather than silently attaching nothing.
         */
        val isEmpty: Boolean
    )

    sealed class Failure(message: String) : Exception(message) {
        object TooLarge : Failure("too_large")
        object Unreadable : Failure("unreadable")
        object Unsupported : Failure("unsupported")
    }

    suspend fun process(uri: Uri, nowMs: Long = System.currentTimeMillis()): Outcome =
        withContext(Dispatchers.IO) {
            val data = runCatching {
                context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            }.getOrNull() ?: throw Failure.Unreadable
            if (data.size > CoachFileStore.MAX_FILE_BYTES) throw Failure.TooLarge

            val filename = displayName(uri) ?: "document"
            val mime = context.contentResolver.getType(uri)
            val kind = when {
                mime == "application/pdf" || filename.endsWith(".pdf", ignoreCase = true) -> AttachmentKind.PDF
                mime?.startsWith("text/") == true -> AttachmentKind.TEXT
                filename.endsWith(".txt", ignoreCase = true) ||
                    filename.endsWith(".md", ignoreCase = true) ||
                    filename.endsWith(".csv", ignoreCase = true) -> AttachmentKind.TEXT
                else -> throw Failure.Unsupported
            }

            val attachmentId = java.util.UUID.randomUUID().toString().lowercase()
            val pages = pageTexts(kind, data, attachmentId)
            val excerpt = CoachReference.attachmentExcerpt(pages)
            val text = excerpt.str("text")

            val extension = filename.substringAfterLast('.', if (kind == AttachmentKind.PDF) "pdf" else "txt")
            val path = files.writeOriginal(data, attachmentId, extension) ?: throw Failure.Unreadable

            Outcome(
                attachment = ChatAttachment(
                    id = attachmentId,
                    kind = kind,
                    filename = filename,
                    mimeType = mime,
                    bytes = data.size.toLong(),
                    sha256 = CoachFileStore.sha256(data),
                    pageCount = intOf(excerpt, "pages_total"),
                    charCount = intOf(excerpt, "chars"),
                    excerpt = text,
                    filePath = path,
                    createdMs = nowMs
                ),
                isEmpty = text == null
            )
        }

    /**
     * A typed or pasted note: no file, no extraction, but the same redaction so a note holding an
     * ID number is treated like any other text.
     */
    fun processNote(raw: String, nowMs: Long = System.currentTimeMillis()): Outcome {
        val excerpt = CoachReference.attachmentExcerpt(listOf(raw))
        val text = excerpt.str("text")
        return Outcome(
            attachment = ChatAttachment(
                kind = AttachmentKind.NOTE,
                filename = "Note",
                mimeType = "text/plain",
                bytes = raw.toByteArray().size.toLong(),
                charCount = intOf(excerpt, "chars"),
                excerpt = text,
                createdMs = nowMs
            ),
            isEmpty = text == null
        )
    }

    private suspend fun pageTexts(kind: AttachmentKind, data: ByteArray, attachmentId: String): List<String> =
        when (kind) {
            AttachmentKind.PDF -> pdfPages(data, attachmentId)
            AttachmentKind.TEXT, AttachmentKind.NOTE -> listOf(String(data, Charsets.UTF_8))
            AttachmentKind.IMAGE -> throw Failure.Unsupported
        }

    /** PDF text layer where the platform offers one, ML Kit OCR on a render otherwise. */
    private suspend fun pdfPages(data: ByteArray, attachmentId: String): List<String> {
        val temp = File.createTempFile("coach-$attachmentId", ".pdf", context.cacheDir)
        return try {
            temp.writeBytes(data)
            ParcelFileDescriptor.open(temp, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                PdfRenderer(descriptor).use { renderer ->
                    val engine = if (TextStage.pdfTextAvailable()) null else ocr()
                    try {
                        (0 until minOf(renderer.pageCount, CoachReference.MAX_ATTACHMENT_PAGES)).map { index ->
                            renderer.openPage(index).use { page ->
                                readPage(page, engine)
                            }
                        }
                    } finally {
                        engine?.close()
                    }
                }
            }
        } catch (e: Failure) {
            throw e
        } catch (_: Throwable) {
            throw Failure.Unreadable
        } finally {
            temp.delete()
        }
    }

    /**
     * The PDF's own text layer when the platform exposes one (S extension 13+), OCR otherwise —
     * the same order `TextStage` uses for a record.
     */
    private suspend fun readPage(page: PdfRenderer.Page, engine: OcrEngine?): String {
        // The guard is inline so lint can see it: pdfTextAvailable() carries @ChecksSdkIntAtLeast.
        @android.annotation.SuppressLint("NewApi")
        if (engine == null && TextStage.pdfTextAvailable()) {
            val layer = runCatching { textLayer(page) }.getOrNull()
            if (!layer.isNullOrBlank()) return layer
        }
        val ocrEngine = engine ?: ocr()
        val bitmap = Bitmap.createBitmap(
            maxOf(1, page.width * RENDER_SCALE),
            maxOf(1, page.height * RENDER_SCALE),
            Bitmap.Config.ARGB_8888
        )
        bitmap.eraseColor(android.graphics.Color.WHITE)
        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        val lines = runCatching { ocrEngine.recognize(bitmap) }.getOrDefault(emptyList())
        if (engine == null) ocrEngine.close()
        return PageTextAssembler.assemble(lines, bitmap.width.toFloat(), bitmap.height.toFloat()).text
    }

    // getTextContents ships in the S extension 13 (Android 12+ with that update, all of 15+); lint's
    // API table only knows API 35, so the extension guard in pdfTextAvailable() is the real check —
    // the same situation TextStage.textLayer documents.
    @android.annotation.SuppressLint("NewApi")
    @androidx.annotation.RequiresExtension(extension = android.os.Build.VERSION_CODES.S, version = 13)
    private fun textLayer(page: PdfRenderer.Page): String =
        page.textContents.joinToString("\n") { it.text }.trim()

    private fun displayName(uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
    }.getOrNull()

    private fun intOf(o: JsonObject, key: String): Int? =
        (o[key] as? JsonPrimitive)?.content?.toIntOrNull()

    companion object {
        /** 2x the page's natural size keeps small print legible to OCR without blowing memory. */
        const val RENDER_SCALE = 2
    }
}
