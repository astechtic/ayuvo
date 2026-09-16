package com.ayuvo.health.medications.data

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.annotation.VisibleForTesting
import com.ayuvo.health.services.FoodImageDecoder
import com.ayuvo.health.services.FoodImageDecoder.scaledToMaxDimension
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/**
 * Optional medication photos under `filesDir/ayuvo-medications/<medicationId>/photo.jpg`
 * (long side ≤ 1024, JPEG q85) plus a 320 px `thumb.jpg`. A separate root from the food photos
 * so these never enter the cloud backup; the directory is excluded in backup_rules.xml.
 * Paths returned here are relative to [root] and stored in `medications.photo_path`.
 */
class MedicationPhotoStore private constructor(
    private val filesRoot: File,
    private val contentResolver: (Uri) -> ByteArray?
) {
    private val dir: File = File(filesRoot, DIR_NAME)

    constructor(context: Context) : this(
        filesRoot = context.filesDir,
        contentResolver = contentResolverOf(context)
    )

    fun root(): File = dir

    /** Copies the image at [source] into the store; returns the relative photo path or null. */
    suspend fun save(medicationId: String, source: Uri): String? = withContext(Dispatchers.IO) {
        val bytes = contentResolver(source) ?: return@withContext null
        saveBytes(medicationId, bytes)
    }

    /** Stores already-loaded image bytes; returns the relative photo path or null. */
    suspend fun save(medicationId: String, bytes: ByteArray): String? = withContext(Dispatchers.IO) {
        saveBytes(medicationId, bytes)
    }

    private fun saveBytes(medicationId: String, bytes: ByteArray): String? = runCatching {
        val full = FoodImageDecoder.decode(bytes, PHOTO_MAX_DIMENSION) ?: return null
        val folder = File(dir, medicationId).apply { mkdirs() }
        writeJpeg(File(folder, PHOTO_NAME), full, PHOTO_QUALITY)
        runCatching {
            val thumb = full.scaledToMaxDimension(THUMBNAIL_MAX_DIMENSION)
            writeJpeg(File(folder, THUMBNAIL_NAME), thumb, THUMBNAIL_QUALITY)
            if (thumb !== full) thumb.recycle()
        }
        full.recycle()
        "$medicationId/$PHOTO_NAME"
    }.getOrNull()

    /** Absolute file of a stored relative path (`<id>/photo.jpg`). */
    fun file(relativePath: String): File = File(dir, relativePath)

    /** The thumbnail sibling of a stored photo path, when it exists. */
    fun thumbnail(relativePath: String): File? =
        File(dir, relativePath).parentFile?.let { File(it, THUMBNAIL_NAME) }?.takeIf { it.isFile }

    fun loadThumbnail(relativePath: String): Bitmap? =
        (thumbnail(relativePath) ?: file(relativePath)).takeIf { it.isFile }?.let {
            runCatching { FoodImageDecoder.decode(it, THUMBNAIL_MAX_DIMENSION) }.getOrNull()
        }

    fun load(relativePath: String, maxDimension: Int = PHOTO_MAX_DIMENSION): Bitmap? =
        file(relativePath).takeIf { it.isFile }?.let {
            runCatching { FoodImageDecoder.decode(it, maxDimension) }.getOrNull()
        }

    /** Removes the medication's photo folder. */
    fun delete(medicationId: String) {
        runCatching { File(dir, medicationId).deleteRecursively() }
    }

    /** Removes every stored photo (Delete All Data). */
    fun deleteAll() {
        runCatching { dir.deleteRecursively() }
    }

    fun totalBytes(): Long = dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    private fun writeJpeg(target: File, bitmap: Bitmap, quality: Int) {
        val temp = File(target.parentFile, "${target.name}.${UUID.randomUUID()}.tmp")
        try {
            FileOutputStream(temp).use { out -> bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out) }
            if (!temp.renameTo(target)) {
                temp.copyTo(target, overwrite = true)
                temp.delete()
            }
        } catch (e: Exception) {
            temp.delete()
            throw e
        }
    }

    companion object {
        const val DIR_NAME = "ayuvo-medications"
        private const val PHOTO_NAME = "photo.jpg"
        private const val THUMBNAIL_NAME = "thumb.jpg"
        private const val PHOTO_MAX_DIMENSION = 1024
        private const val THUMBNAIL_MAX_DIMENSION = 320
        private const val PHOTO_QUALITY = 85
        private const val THUMBNAIL_QUALITY = 76

        private fun contentResolverOf(context: Context): (Uri) -> ByteArray? {
            val appContext = context.applicationContext
            return { uri ->
                kotlin.runCatching { appContext.contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()
            }
        }

        @VisibleForTesting
        fun forTests(baseDir: File, resolver: (Uri) -> ByteArray? = { null }): MedicationPhotoStore =
            MedicationPhotoStore(baseDir.apply { mkdirs() }, resolver)
    }
}
