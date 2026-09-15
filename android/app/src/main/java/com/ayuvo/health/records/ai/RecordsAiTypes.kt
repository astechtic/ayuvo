package com.ayuvo.health.records.ai

import com.ayuvo.health.models.AIProvider

/** Where one Health Records AI call runs (docs/health-records.md §16). */
enum class RecordsAiTarget(val raw: String) {
    LOCAL("local"), CLOUD("cloud");

    companion object {
        fun fromRaw(raw: String?): RecordsAiTarget? = entries.firstOrNull { it.raw == raw }
    }
}

/** Raw model text plus the provider that produced it (stored as `records.ai_provider` = [AIProvider.name]). */
data class RecordsAiReply(val text: String, val provider: AIProvider)

/** What the Ask flow, the chooser card and Settings can offer right now. */
data class RecordsAiOptions(
    val localAvailable: Boolean,
    /** The BYOK provider a cloud call would use (null when none is configured). */
    val cloudProvider: AIProvider?
) {
    val cloudConfigured: Boolean get() = cloudProvider != null

    /** Stored/serializable provider id ([AIProvider.name]). */
    val cloudProviderName: String? get() = cloudProvider?.name
}

enum class UnavailableReason { LOCAL_NOT_SET_UP, NO_CLOUD_PROVIDER }

sealed interface AiResolution {
    /** [provider] is LOCAL_GEMMA for [RecordsAiTarget.LOCAL]. */
    data class Run(val target: RecordsAiTarget, val provider: AIProvider) : AiResolution {
        /** Stable enum name (logging/tests); `records.ai_provider` stores the display name (§8). */
        val providerName: String get() = provider.name
    }
    data object Ask : AiResolution
    data object Off : AiResolution
    data class Unavailable(val reason: UnavailableReason) : AiResolution
}

class RecordsAiException(val kind: Kind, cause: Throwable? = null) :
    Exception("Records AI ${kind.name.lowercase()}", cause) {
    enum class Kind { OFFLINE, BUSY, FAILED, UNAVAILABLE }
}

data class AiPageText(val index: Int, val text: String)
data class AiPageImage(val index: Int, val jpeg: ByteArray) {
    override fun equals(other: Any?): Boolean = other is AiPageImage && other.index == index && other.jpeg.contentEquals(jpeg)
    override fun hashCode(): Int = 31 * index + jpeg.contentHashCode()
}

data class AiExtractionRequest(
    val recordType: String,
    val pages: List<AiPageText>,
    /** Pages with no usable text, rendered to JPEG. */
    val imagePages: List<AiPageImage> = emptyList()
)

/** One call of an extraction run: which pages it covered and the raw text returned. */
data class AiCallResult(val pageIndexes: List<Int>, val fromImage: Boolean, val text: String)

data class AiExtractionReply(val calls: List<AiCallResult>, val provider: AIProvider) {
    val responses: List<String> get() = calls.map { it.text }

    /** Stable enum name; the pipeline stores the user-facing display name (§8). */
    val providerName: String get() = provider.name
}
