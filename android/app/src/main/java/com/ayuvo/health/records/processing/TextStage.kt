package com.ayuvo.health.records.processing

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RectF
import android.graphics.pdf.PdfRenderer
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.ext.SdkExtensions
import androidx.annotation.ChecksSdkIntAtLeast
import androidx.annotation.RequiresExtension
import androidx.core.graphics.createBitmap
import com.ayuvo.health.records.data.RecordFileStore
import com.ayuvo.health.records.data.RecordsStore
import com.ayuvo.health.records.model.HealthRecord
import com.ayuvo.health.records.model.ProcessingError
import com.ayuvo.health.records.model.RecordFileType
import com.ayuvo.health.records.model.RecordPage
import com.ayuvo.health.records.model.TextSource
import com.ayuvo.health.services.FoodImageDecoder
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt

/** Line boxes of one recognised page, in the page's pixel (or point) space. */
interface OcrEngine {
    suspend fun recognize(bitmap: Bitmap): List<OcrLine>
    fun close() {}
}

/** Bundled ML Kit Text Recognition (Latin, offline). One page at a time. */
class MlKitOcrEngine : OcrEngine {
    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    override suspend fun recognize(bitmap: Bitmap): List<OcrLine> {
        val result = recognizer.process(InputImage.fromBitmap(bitmap, 0)).await()
        return result.textBlocks.flatMap { block ->
            block.lines.mapNotNull { line ->
                val box = line.boundingBox ?: return@mapNotNull null
                OcrLine(
                    text = line.text,
                    left = box.left.toFloat(),
                    top = box.top.toFloat(),
                    width = box.width().toFloat(),
                    height = box.height().toFloat(),
                    confidence = line.confidence.takeIf { it > 0f }
                )
            }
        }
    }

    override fun close() {
        runCatching { recognizer.close() }
    }
}

/**
 * Stage `text` (docs/health-records.md §9.1): PDF text layer when the platform exposes it
 * (`SdkExtensions` S ≥ 13), else — or for pages with fewer than 20 letters — a 2000 px render
 * through the render cache + OCR; images are OCR'd upright at ≤ 3000 px. Pages are committed one
 * by one so a crash resumes at the next missing page, and only one bitmap is alive at a time.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TextStage(
    private val store: RecordsStore,
    private val files: RecordFileStore,
    private val ocr: OcrEngine,
    private val pageDispatcher: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(1)
) {
    /** Outcome; [error] is a `processing_error` value when text could not be obtained. */
    data class Outcome(val pagesWithText: Int, val error: String?)

    suspend fun run(record: HealthRecord, onProgress: suspend (done: Int, total: Int) -> Unit = { _, _ -> }): Outcome =
        withContext(Dispatchers.IO) {
            val existing = store.pages(record.id).associateBy { it.pageIndex }
            when (record.fileType) {
                RecordFileType.TEXT -> Outcome(existing.values.count { !it.text.isNullOrBlank() }, null)
                RecordFileType.OTHER -> Outcome(0, ProcessingError.UNSUPPORTED)
                RecordFileType.IMAGE -> image(record, existing)
                RecordFileType.PDF -> pdf(record, existing, onProgress)
            }
        }

    private suspend fun image(record: HealthRecord, existing: Map<Int, RecordPage>): Outcome {
        existing[0]?.let { if (it.textSource == TextSource.OCR || it.textSource == TextSource.USER) return Outcome(if (it.text.isNullOrBlank()) 0 else 1, null) }
        val file = files.resolve(record.filePath)?.takeIf { it.isFile } ?: return Outcome(0, ProcessingError.TEXT_UNAVAILABLE)
        val bitmap = FoodImageDecoder.decode(file, MAX_IMAGE_DIMENSION) ?: return Outcome(0, ProcessingError.OCR_FAILED)
        return try {
            val lines = withContext(pageDispatcher) { ocr.recognize(bitmap) }
            val assembled = PageTextAssembler.assemble(lines, bitmap.width.toFloat(), bitmap.height.toFloat())
            store.upsertPage(
                RecordPage(record.id, 0, assembled.text, TextSource.OCR, assembled.ocrConfidence, bitmap.width, bitmap.height, assembled.blocksJson),
                pageCount = 1
            )
            Outcome(if (assembled.text.isBlank()) 0 else 1, null)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Outcome(0, ProcessingError.OCR_FAILED)
        } finally {
            bitmap.recycle()
        }
    }

    private suspend fun pdf(record: HealthRecord, existing: Map<Int, RecordPage>, onProgress: suspend (Int, Int) -> Unit): Outcome {
        val file = files.resolve(record.filePath)?.takeIf { it.isFile } ?: return Outcome(0, ProcessingError.TEXT_UNAVAILABLE)
        var pfd: ParcelFileDescriptor? = null
        val renderer = try {
            pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            PdfRenderer(pfd)
        } catch (e: SecurityException) {
            runCatching { pfd?.close() }
            return Outcome(0, ProcessingError.PROTECTED_PDF)
        } catch (e: Exception) {
            runCatching { pfd?.close() }
            return Outcome(0, ProcessingError.UNSUPPORTED)
        }
        try {
            val count = renderer.pageCount
            val range = if (record.isSplitChild) {
                record.pageStart!!.coerceAtLeast(0)..minOf(record.pageEnd!!, count - 1)
            } else {
                0 until minOf(count, MAX_PAGES)
            }
            val total = range.count()
            var withText = 0
            var ocrFailures = 0
            for ((done, index) in range.withIndex()) {
                val have = existing[index]
                if (have != null && have.textSource != TextSource.PLAIN) {
                    if (!have.text.isNullOrBlank()) withText++
                    continue
                }
                val page = runCatching { pdfPage(renderer, record, index) }.getOrElse { e ->
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    ocrFailures++
                    null
                } ?: continue
                store.upsertPage(page, pageCount = if (record.isSplitChild) null else count)
                if (!page.text.isNullOrBlank()) withText++
                onProgress(done + 1, total)
            }
            val error = when {
                withText > 0 -> null
                ocrFailures > 0 -> ProcessingError.OCR_FAILED
                else -> ProcessingError.TEXT_UNAVAILABLE
            }
            return Outcome(withText, error)
        } finally {
            runCatching { renderer.close() }
            runCatching { pfd?.close() }
        }
    }

    private suspend fun pdfPage(renderer: PdfRenderer, record: HealthRecord, index: Int): RecordPage {
        val layer = if (pdfTextAvailable()) runCatching { textLayer(renderer, index) }.getOrNull() else null
        // Some platform builds return the text layer without per-line bounds; such text has its
        // table columns glued with single spaces, so those pages go through OCR for line boxes
        // (the text layer is kept as the fallback when OCR finds nothing).
        if (layer != null && layer.hasGeometry && RecordText.letterCount(layer.page.text) >= MIN_TEXT_LETTERS) {
            return RecordPage(record.id, index, layer.page.text, TextSource.PDF_TEXT, null, layer.width, layer.height, layer.page.blocksJson)
        }
        val bitmap = render(renderer, record.id, index)
        try {
            val lines = withContext(pageDispatcher) { ocr.recognize(bitmap) }
            val assembled = PageTextAssembler.assemble(lines, bitmap.width.toFloat(), bitmap.height.toFloat())
            // The text layer stays only as a fallback when OCR yields fewer letters (§9.1).
            if (layer != null && RecordText.letterCount(assembled.text) < RecordText.letterCount(layer.page.text)) {
                return RecordPage(record.id, index, layer.page.text, TextSource.PDF_TEXT, null, layer.width, layer.height, layer.page.blocksJson)
            }
            return RecordPage(record.id, index, assembled.text, TextSource.OCR, assembled.ocrConfidence, bitmap.width, bitmap.height, assembled.blocksJson)
        } finally {
            bitmap.recycle()
        }
    }

    private data class Layer(val page: AssembledPage, val width: Int, val height: Int, val hasGeometry: Boolean)

    // getTextContents ships in the S extension 13 (Android 12+ with that update, all of 15+); lint's API
    // table only knows API 35, so the extension guard in pdfTextAvailable() is the real check.
    @SuppressLint("NewApi")
    @RequiresExtension(extension = Build.VERSION_CODES.S, version = 13)
    private fun textLayer(renderer: PdfRenderer, index: Int): Layer = renderer.openPage(index).use { page ->
        val width = page.width.toFloat().coerceAtLeast(1f)
        val height = page.height.toFloat().coerceAtLeast(1f)
        val lines = mutableListOf<OcrLine>()
        var geometry = true
        for (content in page.textContents) {
            val textLines = content.text.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
            if (textLines.isEmpty()) continue
            val bounds: List<RectF> = content.bounds
            if (bounds.size == textLines.size) {
                textLines.zip(bounds).forEach { (t, r) -> lines += OcrLine(t, r.left, r.top, r.width(), r.height(), null) }
            } else {
                if (bounds.isEmpty()) geometry = false
                // Bounds don't pair 1:1 with lines: spread the lines over the union box top-down.
                val union = RectF(bounds.firstOrNull() ?: RectF(0f, 0f, width, height))
                bounds.drop(1).forEach(union::union)
                val lineHeight = union.height() / textLines.size
                textLines.forEachIndexed { i, t ->
                    lines += OcrLine(t, union.left, union.top + i * lineHeight, union.width(), lineHeight, null)
                }
            }
        }
        Layer(PageTextAssembler.assemble(lines, width, height), page.width, page.height, geometry && lines.isNotEmpty())
    }

    /** Page render at long side 2000 px, shared with the viewer's render cache. */
    private fun render(renderer: PdfRenderer, recordId: String, index: Int): Bitmap = renderer.openPage(index).use { page ->
        val longest = max(page.width, page.height).coerceAtLeast(1)
        val scale = OCR_LONG_SIDE.toFloat() / longest
        val width = (page.width * scale).roundToInt().coerceAtLeast(1)
        val height = (page.height * scale).roundToInt().coerceAtLeast(1)
        files.renderPages.get(recordId, index, width)?.let { cached ->
            if (cached.config == Bitmap.Config.ARGB_8888 || cached.config == Bitmap.Config.RGB_565) return@use cached
            cached.recycle()
        }
        createBitmap(width, height).also { bmp ->
            bmp.eraseColor(Color.WHITE)
            page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            files.renderPages.put(recordId, index, width, bmp)
        }
    }

    companion object {
        const val MIN_TEXT_LETTERS = 20
        const val OCR_LONG_SIDE = 2000
        const val MAX_IMAGE_DIMENSION = 3000
        const val MAX_PAGES = 1_000

        /** `PdfRenderer.Page.getTextContents()` exists from S extension 13 (Android 15+ / updated 12+). */
        @ChecksSdkIntAtLeast(api = 13, extension = Build.VERSION_CODES.S)
        fun pdfTextAvailable(): Boolean =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && SdkExtensions.getExtensionVersion(Build.VERSION_CODES.S) >= 13

        /** Test hook: decodes an image file the same way the stage does. */
        internal fun decodeForOcr(file: File): Bitmap? = FoodImageDecoder.decode(file, MAX_IMAGE_DIMENSION)
    }
}
