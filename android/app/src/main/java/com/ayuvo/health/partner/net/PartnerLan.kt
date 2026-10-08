package com.ayuvo.health.partner.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.net.SocketException

/** A host:port to dial. */
data class Endpoint(val host: String, val port: Int) {
    override fun toString(): String = "$host:$port"

    companion object {
        fun parse(text: String): Endpoint? {
            val i = text.lastIndexOf(':')
            if (i <= 0) return null
            val port = text.substring(i + 1).toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
            return Endpoint(text.substring(0, i).removePrefix("[").removeSuffix("]"), port)
        }
    }
}

/**
 * Local-network facts for Partner sync (docs/partner-sync.md §4, §11): whether there is a Wi-Fi / Ethernet LAN or a
 * hotspot this phone is hosting, and this phone's private IPv4 addresses on them. Uses ConnectivityManager
 * LinkProperties (ACCESS_NETWORK_STATE) plus the up interfaces, which is where a hotspot this phone hosts appears
 * (tethering interfaces are not ConnectivityManager networks). No Wi-Fi scan or location permission is involved.
 */
object PartnerLan {
    private fun isPrivateV4(a: InetAddress): Boolean = a is Inet4Address && !a.isLoopbackAddress && (a.isSiteLocalAddress || a.isLinkLocalAddress)

    @Suppress("DEPRECATION")
    fun lanAddresses(context: Context): List<String> {
        val out = LinkedHashSet<String>()
        val cm = context.getSystemService(ConnectivityManager::class.java)
        if (cm != null) {
            for (network in runCatching { cm.allNetworks.toList() }.getOrDefault(emptyList())) {
                val caps = cm.getNetworkCapabilities(network) ?: continue
                if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) && !caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) continue
                cm.getLinkProperties(network)?.linkAddresses?.forEach { la ->
                    val a = la.address
                    if (isPrivateV4(a) && !a.isLinkLocalAddress) out += a.hostAddress ?: return@forEach
                }
            }
        }
        // Hotspot / tethering interfaces (ap0, swlan0, wlan1, …) are not ConnectivityManager networks.
        runCatching {
            for (nif in NetworkInterface.getNetworkInterfaces().toList()) {
                if (!nif.isUp || nif.isLoopback || nif.isVirtual) continue
                val name = nif.name.lowercase()
                if (!(name.startsWith("wlan") || name.startsWith("ap") || name.startsWith("swlan") || name.startsWith("eth") ||
                        name.startsWith("rndis") || name.startsWith("softap"))
                ) continue
                for (a in nif.inetAddresses.toList()) if (isPrivateV4(a) && !a.isLinkLocalAddress) out += a.hostAddress ?: continue
            }
        }
        return out.toList().take(8)
    }

    /** True when there is a LAN to sync on: Wi-Fi / Ethernet, or a hotspot this phone hosts. */
    fun available(context: Context): Boolean = lanAddresses(context).isNotEmpty()

    /** Socket error caused by Android's local network protection (blocked sockets report EPERM). */
    fun isLocalNetworkDenied(e: Throwable?): Boolean {
        var t = e
        while (t != null) {
            val m = t.message.orEmpty()
            if (t is SocketException && (m.contains("EPERM") || m.contains("Operation not permitted"))) return true
            t = t.cause
        }
        return false
    }

    fun connect(endpoint: Endpoint, timeoutMs: Int = 4_000): Socket {
        val s = Socket()
        try {
            s.tcpNoDelay = true
            s.connect(InetSocketAddress(endpoint.host, endpoint.port), timeoutMs)
            return s
        } catch (e: Exception) {
            runCatching { s.close() }
            throw e
        }
    }
}
