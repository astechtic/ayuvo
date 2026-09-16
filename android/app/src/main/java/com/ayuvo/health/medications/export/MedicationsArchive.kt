package com.ayuvo.health.medications.export

import com.ayuvo.health.medications.logic.ArchiveCodec
import com.ayuvo.health.medications.logic.MedicationConstants
import com.ayuvo.health.medications.logic.MedicationJson
import com.ayuvo.health.medications.model.MedicationsSnapshot
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull

/**
 * The portable `ayuvo-medications.json` file (docs/medications.md §14): a plain JSON document
 * with sorted keys and 2-space indentation, written by the share sheet / `CreateDocument` and read
 * back by `OpenDocument`. Photos are not part of the archive. Shape rules live in [ArchiveCodec].
 */
object MedicationsArchive {
    const val FILE_NAME = "ayuvo-medications.json"
    const val MIME_TYPE = "application/json"
    const val PLATFORM = "android"

    /** `bad_format` or `unsupported_version` (docs §14), or `parse_error` for non-JSON input. */
    class FormatException(val code: String) : Exception(code)

    private val pretty = Json { prettyPrint = true; prettyPrintIndent = "  " }

    fun write(snapshot: MedicationsSnapshot, exportedMs: Long, zoneId: String, appVersion: String): ByteArray =
        encode(ArchiveCodec.export(snapshot, exportedMs, zoneId, PLATFORM, appVersion)).toByteArray(Charsets.UTF_8)

    fun encode(archive: JsonObject): String = pretty.encodeToString(JsonElement.serializer(), sorted(archive)) + "\n"

    /** Parses and checks the envelope; the rows themselves are validated by the merge. */
    fun read(bytes: ByteArray): JsonObject {
        val element = runCatching { MedicationJson.json.parseToJsonElement(String(bytes, Charsets.UTF_8)) }.getOrNull()
        val archive = element as? JsonObject ?: throw FormatException("parse_error")
        val format = (archive["format"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (format != MedicationConstants.ARCHIVE_FORMAT) throw FormatException("bad_format")
        val version = (archive["version"] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull
        if (version != MedicationConstants.ARCHIVE_VERSION.toDouble()) throw FormatException("unsupported_version")
        return archive
    }

    private fun sorted(e: JsonElement): JsonElement = when (e) {
        is JsonObject -> JsonObject(e.entries.sortedBy { it.key }.associate { it.key to sorted(it.value) })
        is JsonArray -> JsonArray(e.map(::sorted))
        else -> e
    }
}
