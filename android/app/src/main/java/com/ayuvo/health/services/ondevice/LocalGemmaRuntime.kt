package com.ayuvo.health.services.ondevice

import android.content.Context
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ThinkingConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Real in-process inference backed by a downloaded LiteRT-LM package.
 *
 * Several chat models can be installed at once, but only one engine is loaded: switching closes the
 * previous one first, because two multi-gigabyte engines will not sit in a phone's memory together
 * (docs/ai-models.md 7).
 */
class LocalGemmaRuntime(
    context: Context,
    private val models: LocalModelManager
) {
    private val cacheDir = context.applicationContext.cacheDir.absolutePath
    private val mutex = Mutex()
    private var engine: Engine? = null
    private var loaded: LocalModelId? = null

    init {
        LocalModelCatalog.chatModels.forEach { models.registerReleaseHook(it.id) { close() } }
    }

    /** The installed chat models, newest choice first -- what `AIProvider.LOCAL_GEMMA.models` is. */
    fun installedChatModelIds(): List<String> =
        LocalModelCatalog.chatModels.filter { models.isExecutable(it.id) }.map { it.catalogId }

    private fun descriptorFor(modelId: String?): LocalModelDescriptor? =
        LocalModelCatalog.chatModels.firstOrNull {
            it.catalogId == modelId || it.fileName == modelId
        }

    fun isReady(): Boolean = LocalModelCatalog.chatModels.any { models.isExecutable(it.id) }

    fun isInstalled(id: LocalModelId): Boolean = models.isExecutable(id)

    fun isReady(modelId: String?): Boolean =
        descriptorFor(modelId)?.let { models.isExecutable(it.id) } ?: isReady()

    /** True while another generation (e.g. Coach) holds the runtime; callers may wait and retry. */
    val isBusy: Boolean get() = mutex.isLocked

    suspend fun generate(
        prompt: String,
        images: List<ByteArray> = emptyList(),
        maxOutputTokens: Int,
        systemInstruction: String? = null,
        /** Which installed model to run; null keeps whatever is loaded, else the first installed. */
        modelId: String? = null
    ): String = mutex.withLock {
        val descriptor = descriptorFor(modelId)
            ?: LocalModelCatalog.chatModels.firstOrNull { models.isExecutable(it.id) }
            ?: error("No on-device model is installed.")
        check(models.isExecutable(descriptor.id)) {
            "${descriptor.displayName} is not installed on this device."
        }
        withContext(Dispatchers.IO) {
            if (loaded != descriptor.id) {
                // One engine at a time: the previous model's memory goes back before the next loads.
                engine?.close()
                engine = null
            }
            val activeEngine = engine ?: initializeEngine(descriptor).also {
                engine = it
                loaded = descriptor.id
            }
            val config = ConversationConfig(
                systemInstruction = systemInstruction?.takeIf(String::isNotBlank)?.let(Contents::of)
                    ?: Contents.of("You are a helpful assistant."),
                // Clamped to the model's own context, not a constant: MedGemma's build exports a
                // 2048-entry KV cache and the engine's global 4096 would overrun it.
                maxOutputToken = maxOutputTokens.coerceIn(1, descriptor.contextTokens),
                thinkingConfig = ThinkingConfig(enableThinking = false)
            )
            activeEngine.createConversation(config).use { conversation ->
                val contents = Contents.of(buildList {
                    images.forEach { add(Content.ImageBytes(it)) }
                    add(Content.Text(prompt))
                })
                val response = conversation.sendMessage(contents)
                response.contents.contents.filterIsInstance<Content.Text>()
                    .joinToString(separator = "") { it.text }
                    .trim()
                    .ifEmpty { error("${descriptor.displayName} returned an empty response.") }
            }
        }
    }

    suspend fun close() = mutex.withLock {
        withContext(Dispatchers.IO) {
            engine?.close()
            engine = null
            loaded = null
        }
    }

    private fun initializeEngine(descriptor: LocalModelDescriptor): Engine = try {
        // Google's Android model allowlist recommends GPU for both the Gemma 4 decoder and
        // vision encoder. Some vendor drivers still fail during initialization, so retain a
        // complete CPU multimodal fallback rather than making the downloaded model unusable.
        initializedEngine(descriptor, Backend.GPU(), Backend.GPU())
    } catch (gpuError: Throwable) {
        Log.w(TAG, "GPU initialization failed; retrying ${descriptor.displayName} on CPU.", gpuError)
        try {
            initializedEngine(descriptor, Backend.CPU(), Backend.CPU())
        } catch (cpuError: Throwable) {
            cpuError.addSuppressed(gpuError)
            throw cpuError
        }
    }

    private fun initializedEngine(
        descriptor: LocalModelDescriptor,
        backend: Backend,
        visionBackend: Backend
    ): Engine {
        val candidate = Engine(
            EngineConfig(
                modelPath = models.modelFile(descriptor.id).absolutePath,
                backend = backend,
                visionBackend = visionBackend,
                maxNumTokens = descriptor.contextTokens,
                maxNumImages = if (descriptor.supportsVision) 8 else 0,
                cacheDir = cacheDir
            )
        )
        return try {
            candidate.initialize()
            candidate
        } catch (error: Throwable) {
            runCatching { candidate.close() }
            throw error
        }
    }

    private companion object {
        const val TAG = "LocalGemmaRuntime"
    }
}
