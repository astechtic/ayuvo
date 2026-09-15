package com.ayuvo.health.records.ingest

import com.ayuvo.health.records.model.RecordFileType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MimeSnifferTest {

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }
    private fun ascii(text: String) = text.toByteArray(Charsets.ISO_8859_1)

    private fun sniff(head: ByteArray, name: String? = null): SniffedType {
        val detector = Utf8TextDetector().apply { update(head) }
        return MimeSniffer.sniff(head, detector.finish(), name)
    }

    @Test
    fun pdfByMagicEvenWithMisleadingName() {
        val result = sniff(ascii("%PDF-1.7\n%âãÏÓ"), "photo.jpg")
        assertEquals(SniffedType(RecordFileType.PDF, "application/pdf", "pdf"), result)
    }

    @Test
    fun imageMagics() {
        assertEquals("image/jpeg", sniff(bytes(0xFF, 0xD8, 0xFF, 0xE0, 0, 0x10)).mimeType)
        assertEquals("image/png", sniff(bytes(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)).mimeType)
        assertEquals("image/gif", sniff(ascii("GIF89a....")).mimeType)
        val webp = sniff(ascii("RIFF") + bytes(0x24, 0, 0, 0) + ascii("WEBPVP8 "))
        assertEquals(SniffedType(RecordFileType.IMAGE, "image/webp", "webp"), webp)
    }

    @Test
    fun heifBrands() {
        for (brand in listOf("heic", "heix", "hevc", "heim", "heis", "mif1", "msf1")) {
            val head = bytes(0, 0, 0, 0x18) + ascii("ftyp$brand") + bytes(0, 0, 0, 0)
            assertEquals(brand, SniffedType(RecordFileType.IMAGE, "image/heic", "heic"), sniff(head))
        }
        val mp4 = bytes(0, 0, 0, 0x18) + ascii("ftypisom") + bytes(0, 0, 0, 0)
        assertEquals(RecordFileType.OTHER, sniff(mp4).fileType)
    }

    @Test
    fun textAndMarkdown() {
        assertEquals(SniffedType(RecordFileType.TEXT, "text/plain", "txt"), sniff("Hemoglobin 13.2 g/dL".toByteArray()))
        assertEquals(SniffedType(RecordFileType.TEXT, "text/markdown", "md"), sniff("# Visit".toByteArray(), "notes.MD"))
        assertEquals(RecordFileType.TEXT, sniff("Hémoglobine — ✓".toByteArray()).fileType)
    }

    @Test
    fun binaryIsOther() {
        assertEquals(SniffedType(RecordFileType.OTHER, "application/octet-stream", "bin"), sniff(bytes(0x50, 0x4B, 0x03, 0x04, 0x00)))
        assertEquals(RecordFileType.OTHER, sniff(bytes(0xC3, 0x28)).fileType)
    }

    @Test
    fun utf8DetectorHandlesChunkBoundariesAndTruncation() {
        val text = "Crème brûlée ✓ 𝄞".toByteArray(Charsets.UTF_8)
        val detector = Utf8TextDetector()
        for (b in text) detector.update(byteArrayOf(b))
        assertTrue(detector.finish())

        val truncated = Utf8TextDetector().apply { update(text, 0, text.size - 1) }
        assertFalse(truncated.finish())

        assertFalse(Utf8TextDetector().apply { update(bytes(0x41, 0x00, 0x42)) }.finish())
        // Overlong encoding and surrogates are not valid UTF-8.
        assertFalse(Utf8TextDetector().apply { update(bytes(0xC0, 0xAF)) }.finish())
        assertFalse(Utf8TextDetector().apply { update(bytes(0xED, 0xA0, 0x80)) }.finish())
    }
}
