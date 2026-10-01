package com.ayuvo.health.services.googlehealth

import com.ayuvo.health.services.SecureHttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/** One `dataPoints.list` page. */
data class GoogleHealthPage(val points: List<JsonObject>, val nextPageToken: String?)

/** Failures the sync engine tells apart; messages carry codes only, never health values. */
sealed class GoogleHealthException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    /** Consent lost or the refresh token was revoked: the user must reconnect in Settings. */
    class Auth(message: String = "reconnect required", cause: Throwable? = null) : GoogleHealthException(message, cause)
    /** HTTP 403: the scope for this type was not granted. */
    class Scope : GoogleHealthException("scope not granted")
    /** HTTP 400/404 on a type the map marks `optional`: this account or API version does not serve it. */
    class Unsupported(val code: Int) : GoogleHealthException("unsupported ($code)")
    class Http(val code: Int) : GoogleHealthException("http $code")
    class Network(cause: Throwable) : GoogleHealthException("network", cause)
    /** The user closed the consent screen or the flow timed out. */
    class Cancelled : GoogleHealthException("cancelled")
}

/** Supplies bearer tokens; [forceRefresh] after a 401. Throws [GoogleHealthException.Auth] when consent is gone. */
fun interface GoogleHealthTokenProvider {
    suspend fun accessToken(forceRefresh: Boolean): String
}

/** The engine's seam over the API (MockWebServer-backed in tests). */
interface GoogleHealthSource {
    suspend fun listDataPoints(entry: GoogleHealthMap.TypeEntry, filter: String, pageToken: String?, pageSize: Int): GoogleHealthPage
}

/**
 * `health.googleapis.com/v4` client (docs/google-health.md §1). OkHttp through [SecureHttpClient];
 * 401 → fresh token → retry once; 429/5xx/IO → exponential backoff (honouring `Retry-After`);
 * 403 → [GoogleHealthException.Scope]; 400/404 on `optional` types → [GoogleHealthException.Unsupported].
 */
class GoogleHealthClient(
    private val map: GoogleHealthMap,
    private val tokens: GoogleHealthTokenProvider,
    private val http: OkHttpClient = defaultHttp,
    private val baseUrl: String = map.api.baseUrl,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
    private val maxAttempts: Int = 4
) : GoogleHealthSource {

    override suspend fun listDataPoints(
        entry: GoogleHealthMap.TypeEntry,
        filter: String,
        pageToken: String?,
        pageSize: Int
    ): GoogleHealthPage {
        val url = baseUrl.toHttpUrl().newBuilder()
            .addPathSegments(map.api.listPath.replace("{gh_type}", entry.ghType).trim('/'))
            .addQueryParameter("filter", filter)
            .addQueryParameter("pageSize", pageSize.toString())
            .apply { if (!pageToken.isNullOrEmpty()) addQueryParameter("pageToken", pageToken) }
            .build()
        var forceRefresh = false
        var refreshed = false
        var attempt = 0
        while (true) {
            val token = tokens.accessToken(forceRefresh)
            forceRefresh = false
            val request = Request.Builder().url(url).header("Authorization", "Bearer $token").get().build()
            val result = try {
                withContext(Dispatchers.IO) {
                    http.newCall(request).execute().use { response ->
                        Triple(response.code, response.header("Retry-After"), if (response.isSuccessful) response.body?.string() else null)
                    }
                }
            } catch (e: IOException) {
                if (++attempt >= maxAttempts) throw GoogleHealthException.Network(e)
                sleep(backoffMs(attempt, null))
                continue
            }
            val (code, retryAfter, body) = result
            when {
                code in 200..299 -> return parsePage(body.orEmpty())
                code == 401 -> {
                    if (refreshed) throw GoogleHealthException.Auth("http 401")
                    refreshed = true
                    forceRefresh = true
                }
                code == 403 -> throw GoogleHealthException.Scope()
                (code == 400 || code == 404) && entry.optional -> throw GoogleHealthException.Unsupported(code)
                code == 429 || code in 500..599 -> {
                    if (++attempt >= maxAttempts) throw GoogleHealthException.Http(code)
                    sleep(backoffMs(attempt, retryAfter))
                }
                else -> throw GoogleHealthException.Http(code)
            }
        }
    }

    private fun backoffMs(attempt: Int, retryAfter: String?): Long {
        retryAfter?.trim()?.toLongOrNull()?.let { return (it * 1000).coerceIn(0, MAX_BACKOFF_MS) }
        return (BASE_BACKOFF_MS shl (attempt - 1)).coerceAtMost(MAX_BACKOFF_MS)
    }

    companion object {
        private const val BASE_BACKOFF_MS = 1_000L
        private const val MAX_BACKOFF_MS = 60_000L
        private val json = Json { ignoreUnknownKeys = true }

        val defaultHttp: OkHttpClient by lazy {
            SecureHttpClient.builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .build()
        }

        fun parsePage(body: String): GoogleHealthPage {
            if (body.isBlank()) return GoogleHealthPage(emptyList(), null)
            val root = json.parseToJsonElement(body) as? JsonObject ?: return GoogleHealthPage(emptyList(), null)
            val points = (root["dataPoints"] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
            val next = (root["nextPageToken"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() }
            return GoogleHealthPage(points, next)
        }
    }
}
