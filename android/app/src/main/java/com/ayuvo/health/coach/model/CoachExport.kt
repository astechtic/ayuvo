package com.ayuvo.health.coach.model

/** What "Export this chat" produces (docs/coach.md §10). */
enum class CoachExportFormat(val raw: String, val mimeType: String) {
    /** A readable transcript for a person. */
    MARKDOWN("markdown", "text/markdown"),

    /**
     * One conversation in the `ayuvo-coach-chats` shape, so it imports through the same reader as a
     * full backup (§11).
     */
    JSON("json", "application/json")
}

data class CoachExportFile(val filename: String, val bytes: ByteArray, val mimeType: String) {
    override fun equals(other: Any?): Boolean =
        other is CoachExportFile && filename == other.filename && bytes.contentEquals(other.bytes)

    override fun hashCode(): Int = 31 * filename.hashCode() + bytes.contentHashCode()
}
