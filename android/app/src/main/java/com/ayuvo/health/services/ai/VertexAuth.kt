package com.ayuvo.health.services.ai

import android.util.Base64
import com.ayuvo.health.medications.logic.MedicationJson
import com.ayuvo.health.medications.logic.MedicationJson.double
import com.ayuvo.health.medications.logic.MedicationJson.str
import kotlinx.serialization.json.JsonObject
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.util.concurrent.ConcurrentHashMap

/**
 * Turns a Google service-account JSON into a short-lived access token (docs/ai-models.md 6).
 *
 * No Google SDK: the JWT is signed with `java.security`, exchanged for a token, and cached until
 * shortly before it expires. The JSON never leaves the key store and is never logged.
 */
object VertexAuth {

    data class ServiceAccount(
        val clientEmail: String,
        val privateKeyPem: String,
        val tokenUri: String,
        val projectId: String?,
    ) {
        companion object {
            /** Parses the file Google Cloud hands out. Returns null for anything that is not one. */
            fun parse(json: String?): ServiceAccount? {
                if (json.isNullOrBlank()) return null
                val parsed = runCatching {
                    MedicationJson.json.parseToJsonElement(json) as? JsonObject
                }.getOrNull() ?: return null
                val email = parsed.str("client_email")?.takeIf { it.isNotEmpty() } ?: return null
                val key = parsed.str("private_key")?.takeIf { it.isNotEmpty() } ?: return null
                return ServiceAccount(
                    clientEmail = email,
                    privateKeyPem = key,
                    tokenUri = parsed.str("token_uri") ?: "https://oauth2.googleapis.com/token",
                    projectId = parsed.str("project_id"),
                )
            }
        }
    }

    const val SCOPE = "https://www.googleapis.com/auth/cloud-platform"

    private data class CachedToken(val value: String, val expiresAtMs: Long)

    private val cache = ConcurrentHashMap<String, CachedToken>()

    /** A bearer token for this service account, minted or reused. */
    fun accessToken(
        account: ServiceAccount,
        client: OkHttpClient,
        scope: String = SCOPE,
        nowMs: Long = System.currentTimeMillis(),
    ): String {
        val key = "${account.clientEmail}|$scope"
        // Refresh a minute early so a request never starts with a token that expires mid-flight.
        cache[key]?.let { if (it.expiresAtMs > nowMs + 60_000) return it.value }

        val assertion = signedAssertion(account, scope, nowMs)
        val request = Request.Builder()
            .url(account.tokenUri)
            .post(
                FormBody.Builder()
                    .add("grant_type", "urn:ietf:params:oauth:grant-type:jwt-bearer")
                    .add("assertion", assertion)
                    .build(),
            )
            .build()
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            val parsed = runCatching {
                MedicationJson.json.parseToJsonElement(body) as? JsonObject
            }.getOrNull()
            val token = parsed?.str("access_token")
            if (!response.isSuccessful || token.isNullOrEmpty()) {
                val detail = parsed?.str("error_description") ?: parsed?.str("error")
                    ?: "no access token in the reply"
                throw AiError.Api("Google rejected the service account: $detail")
            }
            val lifetime = (parsed.double("expires_in") ?: 3600.0).toLong()
            cache[key] = CachedToken(token, nowMs + lifetime * 1000)
            return token
        }
    }

    fun forget(account: ServiceAccount) {
        cache.keys.filter { it.startsWith("${account.clientEmail}|") }.forEach(cache::remove)
    }

    fun signedAssertion(
        account: ServiceAccount,
        scope: String = SCOPE,
        nowMs: Long = System.currentTimeMillis(),
        lifetimeSeconds: Long = 3600,
    ): String {
        val issued = nowMs / 1000
        val header = """{"alg":"RS256","typ":"JWT"}"""
        val claims = MedicationJson.obj(
            "iss" to account.clientEmail,
            "scope" to scope,
            "aud" to account.tokenUri,
            "iat" to issued,
            "exp" to issued + lifetimeSeconds,
        ).toString()
        val signingInput = base64Url(header.toByteArray()) + "." + base64Url(claims.toByteArray())
        val signature = Signature.getInstance("SHA256withRSA").apply {
            initSign(privateKey(account.privateKeyPem))
            update(signingInput.toByteArray())
        }.sign()
        return signingInput + "." + base64Url(signature)
    }

    /** JWT base64: URL alphabet, no padding. */
    fun base64Url(bytes: ByteArray): String =
        Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)

    /** Reads the PKCS#8 PEM the service-account file carries. */
    fun privateKey(pem: String): java.security.PrivateKey {
        val body = pem
            .replace("\\n", "\n")
            .lineSequence()
            .filterNot { it.startsWith("-----") }
            .joinToString("")
            .trim()
        val der = Base64.decode(body, Base64.DEFAULT)
        return KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(der))
    }
}
