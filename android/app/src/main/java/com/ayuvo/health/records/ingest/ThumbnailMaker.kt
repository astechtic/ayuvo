package com.ayuvo.health.records.ingest

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.pdf.PdfRenderer
import android.os.Build
import android.os.ParcelFileDescriptor
import com.ayuvo.health.records.data.RecordFileStore
import com.ayuvo.health.records.model.RecordFileType
import com.ayuvo.health.services.FoodImageDecoder
import androidx.core.graphics.createBitmap
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt

/** Page count + a 320 px page-1 / image thumbnail. Text and unknown files get none. */
class ThumbnailMaker {

    data class Result(val pageCount: Int, val thumbnail: Bitmap?, val error: String?)

    fun make(file: File, fileType: RecordFileType, mimeType: String): Result = when (fileType) {
        RecordFileType.PDF -> pdf(file)
        RecordFileType.IMAGE -> image(file, mimeType)
        RecordFileType.TEXT -> Result(pageCount = 1, thumbnail = null, error = null)
        RecordFileType.OTHER -> Result(pageCount = 0, thumbnail = null, error = null)
    }

    private fun pdf(file: File): Result = try {
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
            PdfRenderer(pfd).use { renderer ->
                val count = renderer.pageCount
                val bitmap = if (count > 0) {
                    renderer.openPage(0).use { page ->
                        val longest = max(page.width, page.height).coerceAtLeast(1)
                        val scale = RecordFileStore.THUMB_MAX_DIMENSION.toFloat() / longest
                        val width = (page.width * scale).roundToInt().coerceAtLeast(1)
                        val height = (page.height * scale).roundToInt().coerceAtLeast(1)
                        createBitmap(width, height).also { bmp ->
                            bmp.eraseColor(Color.WHITE)
                            page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        }
                    }
                } else {
                    null
                }
                Result(count, bitmap, null)
            }
        }
    } catch (e: SecurityException) {
        Result(0, null, ERROR_PROTECTED_PDF)
    } catch (e: Exception) {
        Result(0, null, ERROR_UNREADABLE)
    }

    private fun image(file: File, mimeType: String): Result {
        val max = RecordFileStore.THUMB_MAX_DIMENSION
        val decoded = if (mimeType == "image/heic" && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            runCatching {
                ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, info, _ ->
                    val longest = max(info.size.width, info.size.height).coerceAtLeast(1)
                    if (longest > max) {
                        val scale = max.toFloat() / longest
                        decoder.setTargetSize(
                            (info.size.width * scale).roundToInt().coerceAtLeast(1),
                            (info.size.height * scale).roundToInt().coerceAtLeast(1)
                        )
                    }
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                }
            }.getOrNull()
        } else {
            FoodImageDecoder.decode(file, max)
        }
        return Result(pageCount = 1, thumbnail = decoded, error = if (decoded == null) ERROR_UNREADABLE else null)
    }

    companion object {
        const val ERROR_PROTECTED_PDF = "protected_pdf"
        const val ERROR_UNREADABLE = "unreadable"
    }
}
