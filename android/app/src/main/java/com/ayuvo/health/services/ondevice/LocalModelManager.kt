package com.ayuvo.health.services.ondevice

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.StatFs
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request

sealed interface LocalModelInstallStatus {
    data object NotInstalled : LocalModelInstallStatus
    data class Downloading(val downloadedBytes: Long, val totalBytes: Long) : LocalModelInstallStatus
    data object Installed : LocalModelInstallStatus
    data class Failed(val message: String) : LocalModelInstallStatus
}

data class LocalModelState(
    val descriptor: LocalModelDescriptor,
    val eligible: Boolean,
    val status: LocalModelInstallStatus,
    val ineligibility: LocalModelIneligibility? = null
) {
    val executable: Boolean get() = eligible && status == LocalModelInstallStatus.Installed
}

/** Owns verified model artifacts in no-backup app storage. */
class LocalModelManager(
    context: Context,
    private val client: OkHttpClient,
    /**
     * The optional Hugging Face token, read at download time. Only entries marked gated send it
     * (docs/ai-models.md 7); everything else is fetched anonymously, as it always was.
     */
    private val huggingFaceToken: () -> String? = { null }
) {
    private val appContext = context.applicationContext
    private val modelDir = File(appContext.noBackupFilesDir, "local-models").apply { mkdirs() }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = ConcurrentHashMap<LocalModelId, Job>()
    private val activeCalls = ConcurrentHashMap<LocalModelId, Call>()
    private val deleting = ConcurrentHashMap.newKeySet<LocalModelId>()
    private val releaseHooks = ConcurrentHashMap<LocalModelId, suspend () -> Unit>()
    private val totalMemoryBytes: Long =
        (appContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager)
            .let { manager -> ActivityManager.MemoryInfo().also(manager::getMemoryInfo).totalMem }
    private val isLowRamDevice: Boolean =
        (appContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).isLowRamDevice
    private val supportedAbis = Build.SUPPORTED_ABIS.toSet()

    private val _states = MutableStateFlow(
        LocalModelCatalog.all.associate { descriptor ->
            val ineligibility = LocalModelEligibility.ineligibility(
                descriptor,
                totalMemoryBytes,
                supportedAbis,
                isLowRamDevice
            )
            descriptor.id to LocalModelState(
                descriptor = descriptor,
                eligible = ineligibility == null,
                status = installedStatus(descriptor),
                ineligibility = ineligibility
            )
        }
    )
    val states: StateFlow<Map<LocalModelId, LocalModelState>> = _states.asStateFlow()

    init {
        modelDir.listFiles { file -> file.name.endsWith(".part") }
            ?.forEach { it.delete() }
    }

    fun state(id: LocalModelId): LocalModelState = checkNotNull(_states.value[id])

    fun isExecutable(id: LocalModelId): Boolean = state(id).executable

    fun modelFile(id: LocalModelId): File = File(modelDir, LocalModelCatalog.descriptor(id).fileName)

    fun registerReleaseHook(id: LocalModelId, hook: suspend () -> Unit) {
        releaseHooks[id] = hook
    }

    fun download(id: LocalModelId) {
        synchronized(jobs) {
            if (id in deleting) return
            val current = state(id)
            if (!current.eligible || current.status is LocalModelInstallStatus.Downloading || current.executable) return
            update(id, LocalModelInstallStatus.Downloading(0L, current.descriptor.expectedBytes))
            val job = scope.launch(start = CoroutineStart.LAZY) { downloadVerified(current.descriptor) }
            jobs[id] = job
            job.start()
        }
    }

    suspend fun delete(id: LocalModelId) {
        val (ownsDelete, downloadJob) = synchronized(jobs) {
            val owns = deleting.add(id)
            owns to if (owns) jobs.remove(id)?.also { it.cancel() } else null
        }
        if (!ownsDelete) return
        try {
            activeCalls.remove(id)?.cancel()
            downloadJob?.join()
            releaseHooks[id]?.invoke()
            withContext(Dispatchers.IO) {
                val descriptor = LocalModelCatalog.descriptor(id)
                modelFile(id).delete()
                partialFile(descriptor).delete()
                markerFile(descriptor).delete()
            }
            update(id, LocalModelInstallStatus.NotInstalled)
        } finally {
            deleting.remove(id)
        }
    }

    private suspend fun downloadVerified(descriptor: LocalModelDescriptor) {
        val id = descriptor.id
        val part = partialFile(descriptor)
        try {
            val available = StatFs(modelDir.absolutePath).availableBytes
            val reserve = maxOf(256L * 1024L * 1024L, descriptor.expectedBytes / 10L)
            require(available >= descriptor.expectedBytes + reserve) {
                "Not enough free storage for ${descriptor.displayName}."
            }
            part.delete()
            val request = Request.Builder().url(descriptor.downloadUrl).get().apply {
                if (descriptor.requiresAuth) {
                    val token = huggingFaceToken()
                        ?: throw IllegalStateException(
                            descriptor.repositoryUrl?.let {
                                "${descriptor.displayName} is gated. Add a Hugging Face token in " +
                                    "Settings, and accept its terms at $it with the same account."
                            } ?: "${descriptor.displayName} needs a Hugging Face token. Add one in Settings."
                        )
                    addHeader("Authorization", "Bearer $token")
                }
            }.build()
            val digest = MessageDigest.getInstance("SHA-256")
            val call = client.newCall(request)
            activeCalls[id] = call
            currentCoroutineContext().ensureActive()
            call.execute().use { response ->
                if (!response.isSuccessful) {
                    // 401/403 on a gated model is almost always the token or the unaccepted
                    // licence, not a server fault. "HTTP 403" sends people looking in the wrong place.
                    if (response.code == 401 || response.code == 403) {
                        // 401/403 on a gated model is the token or the unaccepted licence, never a
                        // server fault -- and "accept the terms" without saying where is barely
                        // better than "HTTP 403", so name the page.
                        val page = descriptor.repositoryUrl
                        error(
                            if (page != null) {
                                "Hugging Face refused the download (HTTP ${response.code}). Open " +
                                    "$page, accept the model's terms with the account your token " +
                                    "belongs to, then try again."
                            } else {
                                "Hugging Face refused the download (HTTP ${response.code}). Check " +
                                    "the token in Settings and that its account has accepted this " +
                                    "model's terms."
                            }
                        )
                    }
                    error("Download failed (HTTP ${response.code}).")
                }
                val body = response.body ?: error("The download response was empty.")
                val declaredLength = body.contentLength()
                if (declaredLength >= 0L && declaredLength != descriptor.expectedBytes) {
                    error("The model download size did not match the published artifact.")
                }
                var copied = 0L
                var lastPublished = 0L
                FileOutputStream(part).use { output ->
                    body.byteStream().use { input ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE * 4)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                            digest.update(buffer, 0, count)
                            copied += count
                            if (copied - lastPublished >= 1L * 1024L * 1024L) {
                                lastPublished = copied
                                update(id, LocalModelInstallStatus.Downloading(copied, descriptor.expectedBytes))
                            }
                        }
                    }
                    output.fd.sync()
                }
                if (copied != descriptor.expectedBytes) error("The model download was incomplete.")
                val actualHash = digest.digest().joinToString("") { "%02x".format(it) }
                if (!actualHash.equals(descriptor.sha256, ignoreCase = true)) {
                    error("The model download failed integrity verification.")
                }
            }

            val destination = modelFile(id)
            destination.delete()
            check(part.renameTo(destination)) { "The verified model could not be installed." }
            val marker = markerFile(descriptor)
            val markerPart = File(marker.parentFile, "${marker.name}.part")
            markerPart.writeText(descriptor.sha256)
            marker.delete()
            check(markerPart.renameTo(marker)) { "The verification marker could not be installed." }
            update(id, LocalModelInstallStatus.Installed)
        } catch (cancelled: CancellationException) {
            part.delete()
            update(id, LocalModelInstallStatus.NotInstalled)
            throw cancelled
        } catch (error: Throwable) {
            part.delete()
            if (!currentCoroutineContext().isActive) {
                update(id, LocalModelInstallStatus.NotInstalled)
                throw CancellationException("Model download cancelled.").also { it.initCause(error) }
            }
            update(id, LocalModelInstallStatus.Failed(error.message ?: "Model download failed."))
        } finally {
            activeCalls.remove(id)
            jobs.remove(id)
        }
    }

    private fun installedStatus(descriptor: LocalModelDescriptor): LocalModelInstallStatus {
        val file = modelFile(descriptor.id)
        val marker = markerFile(descriptor)
        val installed = file.isFile && file.length() == descriptor.expectedBytes &&
            marker.isFile && runCatching { marker.readText().trim() }.getOrNull()
                .equals(descriptor.sha256, ignoreCase = true)
        return if (installed) LocalModelInstallStatus.Installed else LocalModelInstallStatus.NotInstalled
    }

    private fun partialFile(descriptor: LocalModelDescriptor) =
        File(modelDir, "${descriptor.fileName}.part")

    private fun markerFile(descriptor: LocalModelDescriptor) =
        File(modelDir, "${descriptor.fileName}.sha256")

    private fun update(id: LocalModelId, status: LocalModelInstallStatus) {
        _states.value = _states.value.toMutableMap().apply {
            val old = checkNotNull(this[id])
            this[id] = old.copy(status = status)
        }
    }
}
