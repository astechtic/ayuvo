package com.ayuvo.health.records.ingest

import com.ayuvo.health.records.model.RecordFileType

/** Result of byte sniffing: the stored file type, MIME type and original file extension. */
data class SniffedType(val fileType: RecordFileType, val mimeType: String, val extension: String)

/**
 * Decides a record's type from its bytes (docs/health-records.md §3). Sender MIME types and
 * filenames are hints only; the filename extension is consulted just to tell Markdown from
 * plain text once the bytes are already known to be text.
 */
object MimeSniffer {
    const val HEAD_BYTES = 32
    private val heifBrands = setOf("heic", "heix", "hevc", "heim", "heis", "mif1", "msf1")

    /**
     * @param head the first bytes of the file (at least [HEAD_BYTES] when available)
     * @param isText true when the whole file is valid UTF-8 without NUL bytes
     */
    fun sniff(head: ByteArray, isText: Boolean, filenameHint: String? = null): SniffedType {
        when {
            head.startsWithAscii("%PDF-") -> return SniffedType(RecordFileType.PDF, "application/pdf", "pdf")
            head.size >= 3 && head[0] == 0xFF.toByte() && head[1] == 0xD8.toByte() && head[2] == 0xFF.toByte() ->
                return SniffedType(RecordFileType.IMAGE, "image/jpeg", "jpg")
            head.size >= 4 && head[0] == 0x89.toByte() && head.asciiAt(1, 3) == "PNG" ->
                return SniffedType(RecordFileType.IMAGE, "image/png", "png")
            head.size >= 12 && head.asciiAt(4, 4) == "ftyp" && head.asciiAt(8, 4) in heifBrands ->
                return SniffedType(RecordFileType.IMAGE, "image/heic", "heic")
            head.size >= 12 && head.asciiAt(0, 4) == "RIFF" && head.asciiAt(8, 4) == "WEBP" ->
                return SniffedType(RecordFileType.IMAGE, "image/webp", "webp")
            head.startsWithAscii("GIF8") -> return SniffedType(RecordFileType.IMAGE, "image/gif", "gif")
        }
        if (isText) {
            val ext = filenameHint?.substringAfterLast('.', "")?.lowercase()
            return if (ext == "md" || ext == "markdown") {
                SniffedType(RecordFileType.TEXT, "text/markdown", "md")
            } else {
                SniffedType(RecordFileType.TEXT, "text/plain", "txt")
            }
        }
        return SniffedType(RecordFileType.OTHER, "application/octet-stream", "bin")
    }

    private fun ByteArray.startsWithAscii(prefix: String): Boolean =
        size >= prefix.length && prefix.indices.all { this[it] == prefix[it].code.toByte() }

    private fun ByteArray.asciiAt(offset: Int, length: Int): String {
        if (offset + length > size) return ""
        return String(CharArray(length) { (this[offset + it].toInt() and 0xFF).toChar() })
    }
}

/**
 * Streaming "valid UTF-8 without NUL bytes" check, fed chunk by chunk while the importer
 * copies the file so text detection never needs a second pass.
 */
class Utf8TextDetector {
    private var pendingContinuations = 0
    private var lowerBound = 0x80
    private var upperBound = 0xBF
    var isText: Boolean = true
        private set

    fun update(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size) {
        if (!isText) return
        for (i in offset until offset + length) {
            val b = bytes[i].toInt() and 0xFF
            if (pendingContinuations == 0) {
                when (b) {
                    0x00 -> { isText = false; return }
                    in 0x01..0x7F -> Unit
                    in 0xC2..0xDF -> expect(1, 0x80, 0xBF)
                    0xE0 -> expect(2, 0xA0, 0xBF)
                    in 0xE1..0xEC, 0xEE, 0xEF -> expect(2, 0x80, 0xBF)
                    0xED -> expect(2, 0x80, 0x9F)
                    0xF0 -> expect(3, 0x90, 0xBF)
                    in 0xF1..0xF3 -> expect(3, 0x80, 0xBF)
                    0xF4 -> expect(3, 0x80, 0x8F)
                    else -> { isText = false; return }
                }
            } else {
                if (b < lowerBound || b > upperBound) { isText = false; return }
                lowerBound = 0x80
                upperBound = 0xBF
                pendingContinuations--
            }
        }
    }

    /** Call after the last chunk: a truncated multi-byte sequence is not valid UTF-8. */
    fun finish(): Boolean {
        if (pendingContinuations != 0) isText = false
        return isText
    }

    private fun expect(continuations: Int, low: Int, high: Int) {
        pendingContinuations = continuations
        lowerBound = low
        upperBound = high
    }
}
