package com.ayuvo.health.coach.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import java.security.MessageDigest

/**
 * Attachment blobs under `filesDir/ayuvo-coach/<attachment id>/original.<ext>` plus an optional
 * `thumb.jpg` (docs/coach.md §2). Mirrors `RecordFileStore`; paths are stored in the database
 * **relative** to the root, and [resolve] refuses to escape it.
 *
 * The directory is excluded from Android backup and device transfer in `backup_rules.xml` and
 * `data_extraction_rules.xml`.
 */
class CoachFileStore(private val root: File) {

    constructor(context: Context) : this(File(context.filesDir, DIR_NAME))

    fun root(): File = root

    fun directory(attachmentId: String): File = File(root, attachmentId)

    /** A stored relative path -> an absolute file, or null when the path tries to escape the root. */
    fun resolve(relativePath: String?): File? {
        if (relativePath.isNullOrEmpty()) return null
        if (relativePath.startsWith("/") || relativePath.contains("..")) return null
        val file = File(root, relativePath)
        val base = root.canonicalPath
        return if (file.canonicalPath.startsWith("$base${File.separator}")) file else null
    }

    fun relativePath(file: File): String? {
        val base = root.canonicalPath
        val path = file.canonicalPath
        return if (path.startsWith("$base${File.separator}")) path.substring(base.length + 1) else null
    }

    /** Writes the attachment's original and returns the relative path, or null when it is too large. */
    fun writeOriginal(data: ByteArray, attachmentId: String, extension: String): String? {
        if (data.size > MAX_FILE_BYTES) return null
        val folder = directory(attachmentId)
        if (!folder.exists() && !folder.mkdirs()) return null
        val ext = extension.ifEmpty { "bin" }
        val file = File(folder, "$ORIGINAL_BASE.$ext")
        return runCatching {
            file.writeBytes(data)
            relativePath(file) ?: "$attachmentId/$ORIGINAL_BASE.$ext"
        }.getOrNull()
    }

    /**
     * Downsamples and writes `thumb.jpg`; returns the relative path, or null when the image could not
     * be decoded — a thumbnail is a convenience, never a reason to fail the attachment.
     */
    fun writeThumbnail(bitmap: Bitmap, attachmentId: String): String? {
        val folder = directory(attachmentId)
        if (!folder.exists() && !folder.mkdirs()) return null
        val scaled = downsample(bitmap, THUMB_MAX_DIMENSION)
        val file = File(folder, THUMB_NAME)
        return runCatching {
            file.outputStream().use { scaled.compress(Bitmap.CompressFormat.JPEG, 70, it) }
            relativePath(file)
        }.getOrNull()
    }

    fun bytes(relativePath: String?): ByteArray? =
        resolve(relativePath)?.takeIf { it.exists() }?.let { runCatching { it.readBytes() }.getOrNull() }

    fun bitmap(relativePath: String?): Bitmap? {
        val data = bytes(relativePath) ?: return null
        return runCatching { BitmapFactory.decodeByteArray(data, 0, data.size) }.getOrNull()
    }

    fun exists(relativePath: String?): Boolean = resolve(relativePath)?.exists() == true

    /** Removes one attachment's folder. Missing is success — the caller is deleting it either way. */
    fun delete(attachmentId: String) {
        runCatching { directory(attachmentId).deleteRecursively() }
    }

    fun deleteAll() {
        runCatching { root.deleteRecursively() }
    }

    /** Folders with no row left in the database, so a crash between the two never leaks a blob. */
    fun orphanedDirectories(knownIds: Set<String>): List<String> =
        root.listFiles().orEmpty().filter { it.isDirectory && it.name !in knownIds }.map { it.name }

    fun totalBytes(): Long =
        root.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    companion object {
        const val DIR_NAME = "ayuvo-coach"
        const val ORIGINAL_BASE = "original"
        const val THUMB_NAME = "thumb.jpg"
        const val THUMB_MAX_DIMENSION = 320
        const val MAX_FILE_BYTES = 64 * 1024 * 1024

        fun sha256(data: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }

        /** Longest side capped at [maxDimension], aspect preserved; the original when it already fits. */
        fun downsample(bitmap: Bitmap, maxDimension: Int): Bitmap {
            val longest = maxOf(bitmap.width, bitmap.height)
            if (longest <= maxDimension || longest == 0) return bitmap
            val scale = maxDimension.toFloat() / longest
            val width = (bitmap.width * scale).toInt().coerceAtLeast(1)
            val height = (bitmap.height * scale).toInt().coerceAtLeast(1)
            return Bitmap.createScaledBitmap(bitmap, width, height, true)
        }
    }
}
