package com.ayuvo.health.partner.net

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.util.Log
import com.ayuvo.health.partner.logic.PartnerCatalog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.net.Inet4Address
import java.security.SecureRandom
import java.util.concurrent.Executors
import kotlin.coroutines.resume

/**
 * DNS-SD for one sync or pairing window (docs/partner-sync.md §11): advertises `_ayuvo-partner._tcp` with a random
 * instance name and TXT `v=1` only (no names or ids), browses for the same type and resolves what it finds one at a
 * time (NsdManager rejects concurrent resolves below API 34; on 34+ `registerServiceInfoCallback` is used). Everything
 * is torn down by [close]; nothing stays registered after a window.
 */
class PartnerNsd(context: Context, private val scope: CoroutineScope) {
    private val nsd: NsdManager? = context.getSystemService(NsdManager::class.java)
    private val serviceType: String = com.ayuvo.health.partner.logic.PartnerJson.str(PartnerCatalog.current.protocol["service_type"]) ?: "_ayuvo-partner._tcp"
    val instanceName: String = "ayuvo-" + ByteArray(6).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
    private var registration: NsdManager.RegistrationListener? = null
    private var discovery: NsdManager.DiscoveryListener? = null
    private val toResolve = Channel<NsdServiceInfo>(Channel.UNLIMITED)
    private var resolver: Job? = null
    private val pool = Executors.newSingleThreadExecutor()
    /**
     * NsdManager can still post a ServiceInfoCallback (e.g. `onServiceInfoCallbackUnregistered`) after [close] shut
     * the pool down; executing it on its own handler thread then threw RejectedExecutionException and killed the
     * process. Late callbacks are dropped instead.
     */
    private val executor = java.util.concurrent.Executor { r -> if (!pool.isShutdown) runCatching { pool.execute(r) } }

    /** Set when advertising or browsing failed; the window then relies on the last known address. */
    @Volatile var failed: Boolean = false
        private set

    fun advertise(port: Int) {
        val m = nsd ?: run { failed = true; return }
        val info = NsdServiceInfo().apply {
            serviceName = instanceName
            serviceType = this@PartnerNsd.serviceType
            setPort(port)
            setAttribute("v", "1")
        }
        val l = object : NsdManager.RegistrationListener {
            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) { failed = true; Log.w(TAG, "register failed $errorCode") }
            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {}
            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {}
        }
        registration = l
        runCatching { m.registerService(info, NsdManager.PROTOCOL_DNS_SD, l) }.onFailure { failed = true; registration = null }
    }

    /** Browses and resolves; [onEndpoint] gets every resolved endpoint of another device. */
    fun browse(onEndpoint: (Endpoint) -> Unit) {
        val m = nsd ?: run { failed = true; return }
        val l = object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) { failed = true; Log.w(TAG, "discovery failed $errorCode") }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onServiceLost(serviceInfo: NsdServiceInfo) {}
            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                if (serviceInfo.serviceName == instanceName) return
                toResolve.trySend(serviceInfo)
            }
        }
        discovery = l
        runCatching { m.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, l) }.onFailure { failed = true; discovery = null }
        resolver = scope.launch {
            for (info in toResolve) {
                val endpoints = withTimeoutOrNull(RESOLVE_TIMEOUT_MS) { resolve(m, info) }.orEmpty()
                endpoints.forEach(onEndpoint)
            }
        }
    }

    private suspend fun resolve(m: NsdManager, info: NsdServiceInfo): List<Endpoint> = suspendCancellableCoroutine { cont ->
        if (Build.VERSION.SDK_INT >= 34) {
            val cb = object : NsdManager.ServiceInfoCallback {
                var done = false
                override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) { if (!done) { done = true; cont.resume(emptyList()) } }
                override fun onServiceUpdated(serviceInfo: NsdServiceInfo) {
                    if (done) return
                    done = true
                    val port = serviceInfo.port
                    val hosts = serviceInfo.hostAddresses.sortedBy { if (it is Inet4Address) 0 else 1 }
                        .mapNotNull { a -> a.hostAddress?.substringBefore('%')?.let { Endpoint(it, port) } }
                    runCatching { m.unregisterServiceInfoCallback(this) }
                    cont.resume(hosts)
                }
                override fun onServiceLost() {}
                override fun onServiceInfoCallbackUnregistered() {}
            }
            try {
                m.registerServiceInfoCallback(info, executor, cb)
            } catch (e: Exception) {
                cont.resume(emptyList())
                return@suspendCancellableCoroutine
            }
            cont.invokeOnCancellation { runCatching { m.unregisterServiceInfoCallback(cb) } }
        } else {
            @Suppress("DEPRECATION")
            val l = object : NsdManager.ResolveListener {
                override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) { if (cont.isActive) cont.resume(emptyList()) }
                override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                    @Suppress("DEPRECATION")
                    val host = serviceInfo.host?.hostAddress?.substringBefore('%')
                    if (cont.isActive) cont.resume(listOfNotNull(host?.let { Endpoint(it, serviceInfo.port) }))
                }
            }
            @Suppress("DEPRECATION")
            runCatching { m.resolveService(info, l) }.onFailure { if (cont.isActive) cont.resume(emptyList()) }
        }
    }

    fun close() {
        val m = nsd
        resolver?.cancel()
        toResolve.close()
        if (m != null) {
            discovery?.let { runCatching { m.stopServiceDiscovery(it) } }
            registration?.let { runCatching { m.unregisterService(it) } }
        }
        discovery = null
        registration = null
        pool.shutdown()
    }

    companion object {
        private const val TAG = "PartnerNsd"
        private const val RESOLVE_TIMEOUT_MS = 5_000L
    }
}
