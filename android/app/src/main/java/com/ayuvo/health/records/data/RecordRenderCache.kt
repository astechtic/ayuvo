package com.ayuvo.health.records.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/**
 * Evictable page renders under `cacheDir/records-render/<recordId>/<page>-<width>.jpg`
 * (docs/health-records.md §2). The viewer reads and writes it today; Phase 2 OCR reuses the
 * same rendered pages. Least-recently-used files are removed once the total passes [maxBytes].
 */
class RecordRenderCache(private val dir: File, private val maxBytes: Long = DEFAULT_MAX_BYTES) {

    fun file(recordId: String, pageIndex: Int, widthPx: Int): File {
        require(recordId.isNotEmpty() && recordId.all { it.isLetterOrDigit() || it == '-' }) { "Invalid record id" }
        return File(File(dir, recordId), "$pageIndex-$widthPx.jpg")
    }

    /** Cached page, or null. A hit refreshes the file's LRU timestamp. */
    fun get(recordId: String, pageIndex: Int, widthPx: Int): Bitmap? {
        val file = file(recordId, pageIndex, widthPx)
        if (!file.isFile) return null
        val bitmap = runCatching { BitmapFactory.decodeFile(file.absolutePath) }.getOrNull()
        if (bitmap == null) {
            file.delete()
            return null
        }
        file.setLastModified(System.currentTimeMillis())
        return bitmap
    }

    /** Writes [bitmap] atomically (JPEG q90), then trims the cache. Failures are ignored. */
    fun put(recordId: String, pageIndex: Int, widthPx: Int, bitmap: Bitmap) {
        runCatching {
            val target = file(recordId, pageIndex, widthPx)
            target.parentFile?.mkdirs()
            val temp = File(target.parentFile, "${target.name}.${UUID.randomUUID()}.tmp")
            try {
                FileOutputStream(temp).use { out -> bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out) }
                if (!temp.renameTo(target)) {
                    temp.copyTo(target, overwrite = true)
                    temp.delete()
                }
            } finally {
                if (temp.exists()) temp.delete()
            }
            trim()
        }
    }

    fun trim() {
        val files = dir.walkTopDown().filter { it.isFile }.toList()
        val entries = files.map { CacheEntry(it.path, it.length(), it.lastModified()) }
        val evict = evictions(entries, maxBytes).toSet()
        files.filter { it.path in evict }.forEach { it.delete() }
        dir.listFiles()?.filter { it.isDirectory && it.list().isNullOrEmpty() }?.forEach { it.delete() }
    }

    data class CacheEntry(val key: String, val bytes: Long, val lastUsedMs: Long)

    companion object {
        const val DEFAULT_MAX_BYTES = 150L * 1024 * 1024

        /** Keys to delete, oldest first, until the remaining total fits in [maxBytes]. */
        fun evictions(entries: List<CacheEntry>, maxBytes: Long): List<String> {
            var total = entries.sumOf { it.bytes }
            if (total <= maxBytes) return emptyList()
            val out = mutableListOf<String>()
            for (entry in entries.sortedWith(compareBy<CacheEntry> { it.lastUsedMs }.thenBy { it.key })) {
                if (total <= maxBytes) break
                out += entry.key
                total -= entry.bytes
            }
            return out
        }
    }
}
