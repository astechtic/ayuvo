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
        runCatching { File(shareDir, EXPORT_DIR).deleteRecursively() }
        return File(shareDir, EXPORT_DIR).apply { mkdirs() }
    }

    /** A fresh folder for one §34 share build (previous builds are removed). */
    fun freshShareBuildDir(): File {
        runCatching { File(shareDir, BUILD_DIR).deleteRecursively() }
        return File(shareDir, BUILD_DIR).apply { mkdirs() }
    }

    /** Where `ayuvo-records` archives are written before the share sheet (§35, §37 "Backups"). */
    fun archiveDir(): File = File(shareDir, ARCHIVE_DIR).apply { mkdirs() }

    fun archiveBytes(): Long = File(shareDir, ARCHIVE_DIR).walkTopDown().filter { it.isFile }.sumOf { it.length() }

    /** §34/§35 temp cleanup: everything under the share folder, run once on the next launch. */
    fun clearShareTemp() {
        runCatching { shareDir.deleteRecursively() }
    }

    fun shareTempBytes(): Long = shareDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    fun renderCacheBytes(): Long = renderCache.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    /** §37 "Clear generated cache": page renders and share temp files; originals are untouched. */
    fun clearGeneratedCache() {
        runCatching { renderCache.deleteRecursively() }
        clearShareTemp()
    }

    private fun safeId(id: String): String {
        require(id.isNotEmpty() && id.all { it.isLetterOrDigit() || it == '-' }) { "Invalid record id" }
        return id
    }

    companion object {
        const val ROOT_DIR = "ayuvo-records"
        const val RENDER_DIR = "records-render"
        const val SHARE_DIR = "records-share"
        /** "Export original" copies. */
        const val EXPORT_DIR = "export"
        /** §34 produced share files. */
        const val BUILD_DIR = "build"
        /** §35 archives waiting for the share sheet. */
        const val ARCHIVE_DIR = "archives"
        const val THUMB_NAME = "thumb.jpg"
        const val THUMB_MAX_DIMENSION = 320
    }
}
