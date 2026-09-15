package com.ayuvo.health.services

import okhttp3.ConnectionSpec
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import java.io.IOException
import java.security.interfaces.ECKey
import java.security.interfaces.RSAKey
import java.security.cert.X509Certificate
import javax.net.ssl.SSLPeerUnverifiedException

/** Shared HTTPS defaults. Platform trust and hostname verification remain enabled. */
internal object SecureHttpClient {
    fun builder(): OkHttpClient.Builder = OkHttpClient.Builder()
        .connectionSpecs(listOf(ConnectionSpec.RESTRICTED_TLS, ConnectionSpec.CLEARTEXT))
        .addInterceptor(PrivateCleartextOnly)
        .addNetworkInterceptor { chain ->
            // A network interceptor runs after platform TLS validation but before request
            // headers/body are sent, including each redirect and pooled connection use.
            if (chain.request().url.isHttps) {
                val handshake = chain.connection()?.handshake()
                    ?: throw SSLPeerUnverifiedException("Missing HTTPS handshake")
                if (handshake.peerCertificates.isEmpty()) {
                    throw SSLPeerUnverifiedException("Missing HTTPS peer certificate")
                }
                handshake.peerCertificates.forEach { certificate ->
                    val cert = certificate as? X509Certificate
                        ?: throw SSLPeerUnverifiedException("Unsupported peer certificate")
                    val strongKey = when (val key = cert.publicKey) {
                        is RSAKey -> key.modulus.bitLength() >= 2048
                        is ECKey -> key.params.curve.field.fieldSize >= 256
                        else -> key.algorithm == "Ed25519" || key.algorithm == "Ed448"
                    }
                    if (!strongKey) {
                        throw SSLPeerUnverifiedException("HTTPS certificate key is below the minimum strength")
                    }
                    val signature = cert.sigAlgName.uppercase(java.util.Locale.ROOT).replace("-", "")
                    if (signature.contains("MD2") || signature.contains("MD5") || signature.contains("SHA1")) {
                        throw SSLPeerUnverifiedException("HTTPS certificate uses a weak signature hash")
                    }
                }
            }
            // User-selected local HTTP endpoints have no cryptographic handshake.
            chain.proceed(chain.request())
        }

    /**
     * Cleartext (`http://`) is only ever legitimate for a user-supplied local model server
     * (Ollama / an OpenAI-compatible endpoint on the LAN). The network-security config allows
     * loopback, the emulator host alias and `.local`; this application interceptor extends the
     * same rule to private IPv4 ranges — which the XML cannot express — and refuses everything
     * else before a single byte leaves the device.
     */
    internal object PrivateCleartextOnly : Interceptor {
        override fun intercept(chain: Interceptor.Chain): okhttp3.Response {
            val url = chain.request().url
            if (!url.isHttps && !isPrivateHost(url.host)) {
                throw IOException("Cleartext HTTP is only allowed to local network addresses: ${url.host}")
            }
            return chain.proceed(chain.request())
        }
    }

    /** Loopback, link-local, RFC 1918 IPv4, IPv6 loopback/ULA/link-local, and mDNS `.local` names. */
    internal fun isPrivateHost(host: String): Boolean {
        val h = host.lowercase(java.util.Locale.ROOT).trimEnd('.')
        if (h == "localhost" || h.endsWith(".localhost") || h.endsWith(".local")) return true
        if (h == "::1" || h.startsWith("fc") || h.startsWith("fd") || h.startsWith("fe80:")) return true
        val parts = h.split('.')
        if (parts.size != 4) return false
        val octets = parts.map { it.toIntOrNull() ?: return false }
        if (octets.any { it !in 0..255 }) return false
        val (a, b) = octets[0] to octets[1]
        return a == 127 ||
            a == 10 ||
            (a == 172 && b in 16..31) ||
            (a == 192 && b == 168) ||
            (a == 169 && b == 254)
    }
}
