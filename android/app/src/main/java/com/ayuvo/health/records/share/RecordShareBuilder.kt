package com.ayuvo.health.records.share

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.ayuvo.health.records.analytes.AnalyteCatalog
import com.ayuvo.health.records.data.RecordFileStore
import com.ayuvo.health.records.data.RecordsStore
import com.ayuvo.health.records.model.HealthRecord
import com.ayuvo.health.records.model.RecordFileType
import com.ayuvo.health.records.model.RecordType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.coroutineContext
import kotlin.math.max
import kotlin.math.roundToInt

/** One file the share sheet will carry. */
data class ShareItem(
    val file: File,
    val mimeType: String,
    val recordId: String?,
    /** Page-1 render of [file], for the confirmation preview (§34). */
    val preview: File? = null
)

data class ShareBuild(
    val items: List<ShareItem>,
    val summaryText: String?,
    val warnings: List<ShareWarningItem>,
    /** Records that actually contributed something (their `shared_count` is bumped). */
    val sharedRecordIds: List<String>
)

/**
 * Builds the files of a [SharePlan] (docs/health-records.md §34). Redacted output is rasterized at
 * [RedactionTargets.RASTER_LONG_SIDE] and has no text layer; a page without `blocks_json` cannot be
 * redacted and is left out with a warning.
 */
class RecordShareBuilder(
    private val store: RecordsStore,
    private val files: RecordFileStore,
    private val catalog: () -> AnalyteCatalog,
    private val typeLabel: (RecordType) -> String
) {

    suspend fun build(plan: SharePlan, onProgress: (Int, Int) -> Unit = { _, _ -> }): ShareBuild =
        withContext(Dispatchers.IO) {
            val dir = files.freshShareBuildDir()
            val records = store.records(plan.recordIds).associateBy { it.id }
            val ordered = plan.recordIds.mapNotNull { records[it] }
            val pagesByRecord = ordered.associate { it.id to store.pages(it.id) }
            val fieldsByRecord = ordered.associate { it.id to store.fields(it.id) }
            val summaryInputs = if (!plan.includeSummary) emptyList() else ordered.map { record ->
                ShareSummaryText.Input(
                    record = record,
                    fields = fieldsByRecord[record.id].orEmpty(),
                    observations = store.observations(record.id),
                    highlights = store.highlights(record.id)
                )
            }
            val labels = RecordType.entries.associate { it.raw to typeLabel(it) }
            val plan0 = SharePlanWarnings.build(plan, records, pagesByRecord, fieldsByRecord, summaryInputs, catalog())
            val summary = ShareSummaryText.build(summaryInputs, plan, catalog(), labels)

            val items = mutableListOf<ShareItem>()
            val used = LinkedHashSet<String>()
            if (plan.includeOriginal) {
                plan0.pages.forEachIndexed { index, entry ->
                    coroutineContext.ensureActive()
                    onProgress(index, plan0.pages.size)
                    val record = records[entry.recordId] ?: return@forEachIndexed
                    if (entry.pages.isEmpty()) return@forEachIndexed
                    val item = original(plan, record, entry.pages, pagesByRecord[record.id].orEmpty(), fieldsByRecord[record.id].orEmpty(), dir)
                    if (item != null) {
                        items += item
                        used += record.id
                    }
                }
            }
            if (summary.text != null) {
                val file = File(dir, "${safeName(summaryBaseName(ordered))}.txt")
                file.writeText(summary.text, Charsets.UTF_8)
                items += ShareItem(file, "text/plain", null)
                used += summary.recordIds
            }
            onProgress(plan0.pages.size, plan0.pages.size)
            ShareBuild(items, summary.text, plan0.warnings, used.toList())
        }

    private fun summaryBaseName(records: List<HealthRecord>): String =
        if (records.size == 1) "${records[0].title} summary" else "Health records summary"

    private fun original(
        plan: SharePlan,
        record: HealthRecord,
        pages: List<Int>,
        recordPages: List<com.ayuvo.health.records.model.RecordPage>,
        fields: List<com.ayuvo.health.records.model.RecordField>,
        dir: File
    ): ShareItem? {
        val source = files.resolve(record.filePath)?.takeIf { it.isFile } ?: return null
        val base = safeName(record.title)
        val wholeDocument = pages.size == record.pageCount.coerceAtLeast(1) && pages.firstOrNull() == 0

        if (plan.redactions.isEmpty()) {
            if (wholeDocument || record.fileType != RecordFileType.PDF) {
                val target = File(dir, "$base.${source.extension.ifEmpty { "bin" }}")
                source.copyTo(target, overwrite = true)
                return ShareItem(target, record.mimeType, record.id, preview(target, record, dir, base))
            }
            val target = File(dir, "$base-pages.pdf")
            if (!writePdf(source, record, pages, target, emptyMap())) return null
            return ShareItem(target, "application/pdf", record.id, preview(target, record, dir, base))
        }

        if (record.fileType != RecordFileType.PDF && record.fileType != RecordFileType.IMAGE) return null
        val targets = RedactionTargets.forRecord(record.id, recordPages, fields, plan.redactions)
        val boxes = targets.pages.associate { it.pageIndex to it.lines }
        val target = File(dir, "$base-redacted.pdf")
        if (!writePdf(source, record, pages, target, boxes)) return null
        return ShareItem(target, "application/pdf", record.id, preview(target, record, dir, base))
    }

    /** Renders [pages] of [source] into a new PDF, painting [boxes] on each page. */
    private fun writePdf(
        source: File,
        record: HealthRecord,
        pages: List<Int>,
        target: File,
        boxes: Map<Int, List<RedactionLine>>
    ): Boolean {
        val document = PdfDocument()
        var written = 0
        try {
            if (record.fileType == RecordFileType.IMAGE) {
                val bitmap = decodeImage(source) ?: return false
                try {
                    drawPage(document, bitmap, boxes[pages.firstOrNull() ?: 0].orEmpty(), written)
                    written++
                } finally {
                    bitmap.recycle()
                }
            } else {
                ParcelFileDescriptor.open(source, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                    PdfRenderer(pfd).use { renderer ->
                        for (index in pages) {
                            if (index !in 0 until renderer.pageCount) continue
                            val bitmap = renderPage(renderer, index) ?: continue
                            try {
                                drawPage(document, bitmap, boxes[index].orEmpty(), written)
                                written++
                            } finally {
                                bitmap.recycle()
                            }
                        }
                    }
                }
            }
            if (written == 0) return false
            target.outputStream().use { document.writeTo(it) }
            return true
        } catch (e: Exception) {
            return false
        } finally {
            document.close()
        }
    }

    private fun drawPage(document: PdfDocument, bitmap: Bitmap, boxes: List<RedactionLine>, pageNumber: Int) {
        val info = PdfDocument.PageInfo.Builder(bitmap.width, bitmap.height, pageNumber + 1).create()
        val page = document.startPage(info)
        val canvas = page.canvas
        canvas.drawColor(Color.WHITE)
        canvas.drawBitmap(bitmap, 0f, 0f, null)
        if (boxes.isNotEmpty()) paintBoxes(canvas, boxes, bitmap.width, bitmap.height)
        document.finishPage(page)
    }

    /** Opaque black rectangles; the boxes arrive already inflated in normalized space (§34). */
    private fun paintBoxes(canvas: Canvas, boxes: List<RedactionLine>, width: Int, height: Int) {
        val paint = Paint().apply {
            color = Color.BLACK
            style = Paint.Style.FILL
            isAntiAlias = false
        }
        for (line in boxes) {
            val b = line.box
            val rect = RectF(
                (b[0] * width).toFloat(),
                (b[1] * height).toFloat(),
                ((b[0] + b[2]) * width).toFloat(),
                ((b[1] + b[3]) * height).toFloat()
            )
            if (rect.width() <= 0f || rect.height() <= 0f) continue
            canvas.drawRect(rect, paint)
        }
    }

    private fun renderPage(renderer: PdfRenderer, index: Int): Bitmap? = runCatching {
        renderer.openPage(index).use { page ->
            val scale = RedactionTargets.RASTER_LONG_SIDE.toFloat() / max(page.width, page.height).coerceAtLeast(1)
            val width = (page.width * scale).roundToInt().coerceIn(1, RedactionTargets.RASTER_LONG_SIDE)
            val height = (page.height * scale).roundToInt().coerceIn(1, RedactionTargets.RASTER_LONG_SIDE)
            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { bmp ->
                bmp.eraseColor(Color.WHITE)
                page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
            }
        }
    }.getOrNull()

    private fun decodeImage(file: File): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / sample > RedactionTargets.RASTER_LONG_SIDE * 2) sample *= 2
        BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
    }.getOrNull()

    /** Page-1 JPEG of a produced file, shown before the final confirm (§34). */
    private fun preview(produced: File, record: HealthRecord, dir: File, base: String): File? = runCatching {
        val bitmap = when {
            produced.extension.equals("pdf", ignoreCase = true) ->
                ParcelFileDescriptor.open(produced, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                    PdfRenderer(pfd).use { renderer ->
                        if (renderer.pageCount == 0) null else renderer.openPage(0).use { page ->
                            val scale = PREVIEW_WIDTH.toFloat() / page.width.coerceAtLeast(1)
                            Bitmap.createBitmap(PREVIEW_WIDTH, (page.height * scale).roundToInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888).also { bmp ->
                                bmp.eraseColor(Color.WHITE)
                                page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            }
                        }
                    }
                }

            record.fileType == RecordFileType.IMAGE -> decodeImage(produced)
            else -> null
        } ?: return null
        val target = File(dir, "preview-$base-${produced.nameWithoutExtension.hashCode()}.jpg")
        target.outputStream().use { out -> bitmap.compress(Bitmap.CompressFormat.JPEG, 80, out) }
        bitmap.recycle()
        target
    }.getOrNull()

    private fun safeName(title: String): String =
        title.replace(Regex("[^\\p{L}\\p{N} ._()-]+"), " ").replace(Regex("\\s+"), " ").trim().take(60).ifEmpty { "record" }

    companion object {
        private const val PREVIEW_WIDTH = 900
    }
}
