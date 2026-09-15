package com.ayuvo.health.records.data

import android.content.Context
import android.graphics.Bitmap
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/**
 * Files for Health Records (docs/health-records.md §2):
 * - originals and thumbnails under `filesDir/ayuvo-records/<id>/`
 * - evictable page renders under `cacheDir/records-render/`
 * - share copies under `cacheDir/records-share/` (FileProvider `records_share`)
 *
 * Paths stored in the database are always relative to [root].
 */
class RecordFileStore(val root: File, val renderCache: File, val shareDir: File) {

    constructor(context: Context) : this(
        root = File(context.filesDir, ROOT_DIR),
        renderCache = File(context.cacheDir, RENDER_DIR),
        shareDir = File(context.cacheDir, SHARE_DIR)
    )

    /** Page renders shared by the viewer and (Phase 2) OCR; LRU-capped at 150 MB. */
    val renderPages: RecordRenderCache by lazy { RecordRenderCache(renderCache) }

    fun recordDir(id: String): File = File(root, safeId(id))

    /** Resolves a stored relative path; null when it would escape [root]. */
    fun resolve(relativePath: String?): File? {
        if (relativePath.isNullOrBlank()) return null
        val file = File(root, relativePath)
        val rootPath = root.canonicalPath + File.separator
        return file.takeIf { it.canonicalPath.startsWith(rootPath) }
    }

    fun tempOriginal(id: String): File {
        val dir = recordDir(id).apply { mkdirs() }
        return File(dir, "original.tmp")
    }

    /** Atomically moves [temp] to `<id>/original.<ext>`; returns the relative path. */
    fun commitOriginal(id: String, temp: File, ext: String): String {
        val name = "original.${ext.lowercase()}"
        val target = File(recordDir(id), name)
        if (!temp.renameTo(target)) {
            temp.copyTo(target, overwrite = true)
            temp.delete()
        }
        return "${safeId(id)}/$name"
    }

    /** Writes `<id>/thumb.jpg` (JPEG q76) through a temp file; returns the relative path. */
    fun writeThumbnail(id: String, bitmap: Bitmap): String {
        val dir = recordDir(id).apply { mkdirs() }
        val target = File(dir, THUMB_NAME)
        val temp = File(dir, "thumb.${UUID.randomUUID()}.tmp")
        try {
            FileOutputStream(temp).use { out -> bitmap.compress(Bitmap.CompressFormat.JPEG, 76, out) }
            if (!temp.renameTo(target)) {
                temp.copyTo(target, overwrite = true)
                temp.delete()
            }
        } catch (e: Exception) {
            temp.delete()
            throw e
        }
        return "${safeId(id)}/$THUMB_NAME"
    }

    fun deleteRecord(id: String) {
        runCatching { recordDir(id).deleteRecursively() }
        runCatching { File(renderCache, safeId(id)).deleteRecursively() }
    }

    /** Delete All Data: originals, thumbnails, render cache and share copies. */
    fun deleteAll() {
        runCatching { root.deleteRecursively() }
        runCatching { renderCache.deleteRecursively() }
        runCatching { shareDir.deleteRecursively() }
    }

    fun totalBytes(): Long = root.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    /** A fresh share copy directory (old copies are removed first). */
    fun freshShareDir(): File {
        runCatching { shareDir.deleteRecursively() }
        return shareDir.apply { mkdirs() }
    }

    private fun safeId(id: String): String {
        require(id.isNotEmpty() && id.all { it.isLetterOrDigit() || it == '-' }) { "Invalid record id" }
        return id
    }

    companion object {
        const val ROOT_DIR = "ayuvo-records"
        const val RENDER_DIR = "records-render"
        const val SHARE_DIR = "records-share"
        const val THUMB_NAME = "thumb.jpg"
        const val THUMB_MAX_DIMENSION = 320
    }
}
