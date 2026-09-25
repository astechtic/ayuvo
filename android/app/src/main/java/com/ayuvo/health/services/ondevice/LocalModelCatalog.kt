package com.ayuvo.health.services.ondevice

import kotlin.math.ceil

enum class LocalModelId {
    GEMMA_4_E2B,
    QWEN3_1_7B,
    QWEN3_4B,
    QWEN3_8B,
    QWEN3_14B,
    MEDGEMMA_1_5_4B,
    WHISPER_BASE
}

data class LocalModelDescriptor(
    val id: LocalModelId,
    val displayName: String,
    val fileName: String,
    val downloadUrl: String,
    val expectedBytes: Long,
    val sha256: String,
    val licenseName: String,
    val licenseUrl: String,
    val sourceUrl: String,
    val minimumMemoryClassGb: Int?,
    val supportedAbis: Set<String>,
    /** The id in `local-models/catalog.v2.json`; what a profile's `model_id` carries. */
    val catalogId: String = "",
    /** Qwen3 has no vision tower, so this is per model, not per provider. */
    val supportsVision: Boolean = false,
    /** Per model: MedGemma's build exports a 2048-entry KV cache, not the engine's global 4096. */
    val contextTokens: Int = 4096,
    /** Gated on the Hub: the download needs a Hugging Face token. */
    val requiresAuth: Boolean = false,
    /** `owner/name` on Hugging Face. A gated model's terms are accepted on this page. */
    val repository: String = ""
) {
    /** The model's page -- where a gated model's terms are accepted. */
    val repositoryUrl: String? get() = repository.takeIf { it.isNotEmpty() }?.let {
        "https://huggingface.co/$it"
    }
}

enum class LocalModelIneligibility {
    LOW_RAM_DEVICE,
    UNSUPPORTED_ABI,
    INSUFFICIENT_MEMORY
}

object LocalModelCatalog {
    val gemma4E2b = LocalModelDescriptor(
        id = LocalModelId.GEMMA_4_E2B,
        displayName = "Gemma 4 E2B",
        fileName = "gemma-4-E2B-it.litertlm",
        downloadUrl = "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/6b78abd019e61a1ca4cbe3b212d2c9ce8ff38a94/gemma-4-E2B-it.litertlm",
        expectedBytes = 2_588_147_712L,
        sha256 = "181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c",
        licenseName = "Apache-2.0",
        licenseUrl = "https://ai.google.dev/gemma/apache_2",
        sourceUrl = "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/tree/6b78abd019e61a1ca4cbe3b212d2c9ce8ff38a94",
        repository = "litert-community/gemma-4-E2B-it-litert-lm",
        minimumMemoryClassGb = 8,
        supportedAbis = setOf("arm64-v8a", "x86_64"),
        catalogId = "gemma-4-e2b-it-litertlm",
        supportsVision = true,
        contextTokens = 4096
    )

    val qwen3_1_7b = LocalModelDescriptor(
        id = LocalModelId.QWEN3_1_7B,
        displayName = "Qwen3 1.7B",
        fileName = "Qwen3-1.7B_dynamic_wi4b32_afp32.litertlm",
        downloadUrl = "https://huggingface.co/litert-community/Qwen3-1.7B/resolve/73fbc3fe8271c162a603ee66f6e7ed25b6211195/Qwen3-1.7B_dynamic_wi4b32_afp32.litertlm",
        expectedBytes = 977184032L,
        sha256 = "2eeffef7b51bc3e1225ea69fe7aa5f417397934b56a5b6c20cc068d6fd2c918b",
        licenseName = "Apache-2.0",
        licenseUrl = "https://www.apache.org/licenses/LICENSE-2.0.txt",
        sourceUrl = "https://huggingface.co/litert-community/Qwen3-1.7B/tree/73fbc3fe8271c162a603ee66f6e7ed25b6211195",
        repository = "litert-community/Qwen3-1.7B",
        minimumMemoryClassGb = 6,
        supportedAbis = setOf("arm64-v8a", "x86_64"),
        catalogId = "qwen3-1.7b-litertlm",
        supportsVision = false,
        contextTokens = 4096,
        requiresAuth = false
    )

    val qwen3_4b = LocalModelDescriptor(
        id = LocalModelId.QWEN3_4B,
        displayName = "Qwen3 4B",
        fileName = "qwen3_4b_mixed_int4.litertlm",
        downloadUrl = "https://huggingface.co/litert-community/Qwen3-4B/resolve/84cc5a35c9c65cd18fcd65bb1f3a7d77a4acfe6e/qwen3_4b_mixed_int4.litertlm",
        expectedBytes = 2659057664L,
        sha256 = "f0794bc77efeaaf4f7af815f04c483b19b8f2ae4a102cef1b7b760a25848a18e",
        licenseName = "Apache-2.0",
        licenseUrl = "https://www.apache.org/licenses/LICENSE-2.0.txt",
        sourceUrl = "https://huggingface.co/litert-community/Qwen3-4B/tree/84cc5a35c9c65cd18fcd65bb1f3a7d77a4acfe6e",
        repository = "litert-community/Qwen3-4B",
        minimumMemoryClassGb = 8,
        supportedAbis = setOf("arm64-v8a", "x86_64"),
        catalogId = "qwen3-4b-litertlm",
        supportsVision = false,
        contextTokens = 4096,
        requiresAuth = false
    )

    val qwen3_8b = LocalModelDescriptor(
        id = LocalModelId.QWEN3_8B,
        displayName = "Qwen3 8B",
        fileName = "qwen3_8b_mixed_int4.litertlm",
        downloadUrl = "https://huggingface.co/litert-community/Qwen3-8B/resolve/71ff705588319d52d374977eff3da4eee0c0d26e/qwen3_8b_mixed_int4.litertlm",
        expectedBytes = 4887412736L,
        sha256 = "cb4e6d0de4bbf6656d177812cf0c6a983967dedd17e7f88e84b901c3a9862a42",
        licenseName = "Apache-2.0",
        licenseUrl = "https://www.apache.org/licenses/LICENSE-2.0.txt",
        sourceUrl = "https://huggingface.co/litert-community/Qwen3-8B/tree/71ff705588319d52d374977eff3da4eee0c0d26e",
        repository = "litert-community/Qwen3-8B",
        minimumMemoryClassGb = 12,
        supportedAbis = setOf("arm64-v8a", "x86_64"),
        catalogId = "qwen3-8b-litertlm",
        supportsVision = false,
        contextTokens = 4096,
        requiresAuth = false
    )

    val qwen3_14b = LocalModelDescriptor(
        id = LocalModelId.QWEN3_14B,
        displayName = "Qwen3 14B",
        fileName = "qwen3_14b_mixed_int4.litertlm",
        downloadUrl = "https://huggingface.co/litert-community/Qwen3-14B/resolve/e4122fd370cec85c61467274b180e0954e4f422d/qwen3_14b_mixed_int4.litertlm",
        expectedBytes = 8655863808L,
        sha256 = "71de7d58f1b46a3fcba2f7bb700ebcc3c3715877d9a7028d93dd1bcd89bbe946",
        licenseName = "Apache-2.0",
        licenseUrl = "https://www.apache.org/licenses/LICENSE-2.0.txt",
        sourceUrl = "https://huggingface.co/litert-community/Qwen3-14B/tree/e4122fd370cec85c61467274b180e0954e4f422d",
        repository = "litert-community/Qwen3-14B",
        minimumMemoryClassGb = 20,
        supportedAbis = setOf("arm64-v8a", "x86_64"),
        catalogId = "qwen3-14b-litertlm",
        supportsVision = false,
        contextTokens = 4096,
        requiresAuth = false
    )

    val medgemma_1_5_4b = LocalModelDescriptor(
        id = LocalModelId.MEDGEMMA_1_5_4B,
        displayName = "MedGemma 1.5 4B",
        fileName = "medgemma-1.5-4b-it_q4_block32_vision_ekv2048.litertlm",
        downloadUrl = "https://huggingface.co/litert-community/MedGemma-1.5-4B-IT/resolve/abba4da1132343617b51c180e7147a9742a26559/medgemma-1.5-4b-it_q4_block32_vision_ekv2048.litertlm",
        expectedBytes = 3023069488L,
        sha256 = "1627e2e433c3799e4ff06ff0895408ca65b255f786dc270fb5cfa325e349233a",
        licenseName = "LicenseRef-HealthAI-DeveloperFoundations",
        licenseUrl = "https://developers.google.com/health-ai-developer-foundations/terms",
        sourceUrl = "https://huggingface.co/litert-community/MedGemma-1.5-4B-IT/tree/abba4da1132343617b51c180e7147a9742a26559",
        repository = "litert-community/MedGemma-1.5-4B-IT",
        // Above the catalogue's derived 8 GiB gate: the 3 GB file plus an fp32 vision encoder ran an
        // 8 GB iPhone out of memory while loading, so it is offered on 12 GB phones only.
        minimumMemoryClassGb = 12,
        supportedAbis = setOf("arm64-v8a", "x86_64"),
        catalogId = "medgemma-1.5-4b-it-litertlm",
        supportsVision = true,
        contextTokens = 2048,
        requiresAuth = true
    )

    val whisperBase = LocalModelDescriptor(
        id = LocalModelId.WHISPER_BASE,
        displayName = "Whisper Base",
        fileName = "ggml-base.bin",
        downloadUrl = "https://huggingface.co/ggerganov/whisper.cpp/resolve/5359861c739e955e79d9a303bcbc70fb988958b1/ggml-base.bin",
        expectedBytes = 147_951_465L,
        sha256 = "60ed5bc3dd14eea856493d334349b405782ddcaf0028d4b5df4088345fba2efe",
        licenseName = "MIT",
        licenseUrl = "https://github.com/ggml-org/whisper.cpp/blob/master/LICENSE",
        sourceUrl = "https://huggingface.co/ggerganov/whisper.cpp/tree/5359861c739e955e79d9a303bcbc70fb988958b1",
        minimumMemoryClassGb = null,
        supportedAbis = setOf("arm64-v8a")
    )

    /** The chat models, in the order Settings lists them. Whisper is speech, not chat. */
    val chatModels: List<LocalModelDescriptor> = listOf(
        gemma4E2b, qwen3_1_7b, qwen3_4b, qwen3_8b, qwen3_14b, medgemma_1_5_4b
    )

    val all: List<LocalModelDescriptor> = chatModels + whisperBase

    fun descriptor(id: LocalModelId): LocalModelDescriptor = all.first { it.id == id }
}

/**
 * Android reserves some physical RAM before reporting [android.app.ActivityManager.MemoryInfo.totalMem].
 * Rounding the reported GiB upward maps common 5.5 GiB and 7.4 GiB readings back to their marketed
 * 6 GB and 8 GB device classes without relying on the much smaller per-app heap limit.
 */
object LocalModelEligibility {
    private const val GIB = 1_073_741_824.0

    fun memoryClassGb(totalMemoryBytes: Long): Int =
        ceil(totalMemoryBytes.coerceAtLeast(0L) / GIB).toInt()

    fun isEligible(
        descriptor: LocalModelDescriptor,
        totalMemoryBytes: Long,
        supportedAbis: Collection<String>,
        isLowRamDevice: Boolean = false
    ): Boolean = ineligibility(
        descriptor,
        totalMemoryBytes,
        supportedAbis,
        isLowRamDevice
    ) == null

    fun ineligibility(
        descriptor: LocalModelDescriptor,
        totalMemoryBytes: Long,
        supportedAbis: Collection<String>,
        isLowRamDevice: Boolean = false
    ): LocalModelIneligibility? = when {
        descriptor.minimumMemoryClassGb?.let { memoryClassGb(totalMemoryBytes) < it } == true ->
            LocalModelIneligibility.INSUFFICIENT_MEMORY
        isLowRamDevice && descriptor.minimumMemoryClassGb != null ->
            LocalModelIneligibility.LOW_RAM_DEVICE
        descriptor.supportedAbis.none(supportedAbis::contains) ->
            LocalModelIneligibility.UNSUPPORTED_ABI
        else -> null
    }
}
