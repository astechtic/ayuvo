package com.ayuvo.health.services.googlehealth

import android.accounts.Account
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import com.ayuvo.health.data.KeyStore
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.net.URLDecoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

enum class GoogleHealthClientMode(val raw: String) {
    /** The app's own Android OAuth client through Identity AuthorizationClient. */
    BUNDLED("bundled"),
    /** The user's Desktop client (id + secret): Custom Tabs + PKCE + loopback redirect. */
    CUSTOM("custom");

    companion object {
        fun fromRaw(raw: String?): GoogleHealthClientMode = entries.firstOrNull { it.raw == raw } ?: BUNDLED
    }
}

/** The connected account (device-local prefs; tokens are kept apart in [KeyStore]). */
data class GoogleHealthAccount(
    val email: String?,
    val connectedAtMs: Long,
    val clientMode: GoogleHealthClientMode,
    val grantedScopes: Set<String>,
    val groups: Set<String>,
    val customClientId: String? = null
)

/** What a finished consent hands back. */
data class GoogleHealthGrant(
    val accessToken: String,
    val grantedScopes: Set<String>,
    val expiresAtMs: Long? = null
)

/**
 * Google Health OAuth (docs/google-health.md §2).
 *
 * - **Bundled:** Identity `AuthorizationClient.authorize(scopes)`, the Drive backup pattern. Every sync
 *   re-authorizes silently for a fresh access token, so no refresh token exists; a result that needs
 *   UI ([AuthorizationResult.hasResolution]) is reported as [GoogleHealthException.Auth] — the
 *   background never opens a consent screen.
 * - **Custom:** the user's Desktop client. Custom Tabs + PKCE (S256) + `state`, redirected to a
 *   one-shot `http://127.0.0.1:<ephemeral>` listener; the refresh token and client secret are kept
 *   in [KeyStore]. Google allows loopback redirects only for Desktop clients, which is why this mode
 *   asks for one.
 */
class GoogleHealthAuth(
    private val context: Context,
    private val map: GoogleHealthMap,
    private val keyStore: KeyStore,
    private val webClientId: String,
    private val http: OkHttpClient = GoogleHealthClient.defaultHttp,
    private val nowMs: () -> Long = { System.currentTimeMillis() }
) {
    sealed class BundledOutcome {
        data class Granted(val grant: GoogleHealthGrant) : BundledOutcome()
        data class Resolution(val intentSender: IntentSender) : BundledOutcome()
    }

    private val mutex = Mutex()
    @Volatile private var cached: GoogleHealthGrant? = null

    // -- Bundled ---------------------------------------------------------------------------------

    private fun request(scopes: List<String>, email: String?): AuthorizationRequest {
        val builder = AuthorizationRequest.builder().setRequestedScopes(scopes.map(::Scope))
        if (!email.isNullOrBlank()) builder.setAccount(Account(email, "com.google"))
        if (webClientId.isNotBlank()) builder.requestOfflineAccess(webClientId, /* forceCodeForRefreshToken = */ false)
        return builder.build()
    }

    /** Interactive consent from the setup flow / Reconnect; the caller launches a [BundledOutcome.Resolution]. */
    suspend fun authorizeBundled(activity: Activity, scopes: List<String>, email: String? = null): BundledOutcome {
        val result = Identity.getAuthorizationClient(activity).authorize(request(scopes, email)).await()
        return outcome(result)
    }

    fun bundledResultFromIntent(activity: Activity, data: Intent?): GoogleHealthGrant? {
        if (data == null) return null
        val result = runCatching { Identity.getAuthorizationClient(activity).getAuthorizationResultFromIntent(data) }.getOrNull()
            ?: return null
        return (outcome(result) as? BundledOutcome.Granted)?.grant?.also { cached = it }
    }

    private fun outcome(result: AuthorizationResult): BundledOutcome {
        val pending = result.pendingIntent
        if (result.hasResolution() && pending != null) return BundledOutcome.Resolution(pending.intentSender)
        val token = result.accessToken ?: throw GoogleHealthException.Auth("no access token")
        return BundledOutcome.Granted(
            GoogleHealthGrant(token, result.grantedScopes.toSet(), nowMs() + BUNDLED_TOKEN_TTL_MS).also { cached = it }
        )
    }

    // -- Tokens for the API client -----------------------------------------------------------------

    /** Fresh access token for [account] without UI; throws [GoogleHealthException.Auth] when consent is gone. */
    suspend fun accessToken(account: GoogleHealthAccount, forceRefresh: Boolean): String = mutex.withLock {
        val current = cached
        if (!forceRefresh && current != null && (current.expiresAtMs ?: 0L) > nowMs() + 60_000L) return@withLock current.accessToken
        if (forceRefresh && current != null && account.clientMode == GoogleHealthClientMode.BUNDLED) {
            // Play services hands back its cached token until it is cleared.
            withContext(Dispatchers.IO) { runCatching { GoogleAuthUtil.clearToken(context, current.accessToken) } }
        }
        val grant = when (account.clientMode) {
            GoogleHealthClientMode.BUNDLED -> {
                val scopes = account.grantedScopes.toList().ifEmpty { map.scopesFor(account.groups) }
                val result = try {
                    Identity.getAuthorizationClient(context).authorize(request(scopes, account.email)).await()
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    throw GoogleHealthException.Network(e)
                }
                when (val o = outcome(result)) {
                    is BundledOutcome.Granted -> o.grant
                    is BundledOutcome.Resolution -> throw GoogleHealthException.Auth("consent required")
                }
            }
            GoogleHealthClientMode.CUSTOM -> refreshCustom(account)
        }
        cached = grant
        grant.accessToken
    }

    fun tokenProvider(account: suspend () -> GoogleHealthAccount?): GoogleHealthTokenProvider =
        GoogleHealthTokenProvider { force ->
            val a = account() ?: throw GoogleHealthException.Auth("not connected")
            accessToken(a, force)
        }

    // -- Custom (Desktop client + loopback) --------------------------------------------------------

    /**
     * Runs the whole custom-client consent: opens the Custom Tab, waits (≤ 5 min) for the loopback
     * redirect, checks `state`, exchanges the code with the PKCE verifier and stores the refresh token.
     */
    suspend fun authorizeCustom(launcher: Context, clientId: String, clientSecret: String, scopes: List<String>): GoogleHealthGrant {
        val verifier = codeVerifier()
        val state = randomUrlSafe(24)
        val server = withContext(Dispatchers.IO) { ServerSocket(0, 1, InetAddress.getByName(LOOPBACK_HOST)) }
        try {
            val redirect = "http://$LOOPBACK_HOST:${server.localPort}"
            val url = authUrl(map.api.authUrl, clientId, redirect, scopes, codeChallenge(verifier), state)
            withContext(Dispatchers.Main) {
                val intent = CustomTabsIntent.Builder().setShowTitle(true).build()
                if (launcher !is Activity) intent.intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                intent.launchUrl(launcher, Uri.parse(url))
            }
            val params = withContext(Dispatchers.IO) { awaitRedirect(server) }
            if (params["state"] != state) throw GoogleHealthException.Auth("state mismatch")
            params["error"]?.let { throw if (it == "access_denied") GoogleHealthException.Cancelled() else GoogleHealthException.Auth(it) }
            val code = params["code"] ?: throw GoogleHealthException.Auth("no code")
            val body = FormBody.Builder()
                .add("code", code)
                .add("client_id", clientId)
                .add("client_secret", clientSecret)
                .add("redirect_uri", redirect)
                .add("grant_type", "authorization_code")
                .add("code_verifier", verifier)
                .build()
            val json = tokenCall(body)
            val refresh = json.optString("refresh_token").takeIf { it.isNotBlank() }
                ?: throw GoogleHealthException.Auth("no refresh token")
            keyStore.save(KEY_REFRESH_TOKEN, refresh)
            keyStore.save(KEY_CLIENT_SECRET, clientSecret)
            return grantFrom(json, fallbackScopes = scopes).also { cached = it }
        } finally {
            withContext(Dispatchers.IO) { runCatching { server.close() } }
        }
    }

    private suspend fun refreshCustom(account: GoogleHealthAccount): GoogleHealthGrant {
        val clientId = account.customClientId ?: throw GoogleHealthException.Auth("no client id")
        val refresh = keyStore.load(KEY_REFRESH_TOKEN) ?: throw GoogleHealthException.Auth("no refresh token")
        val secret = keyStore.load(KEY_CLIENT_SECRET) ?: throw GoogleHealthException.Auth("no client secret")
        val body = FormBody.Builder()
            .add("refresh_token", refresh)
            .add("client_id", clientId)
            .add("client_secret", secret)
            .add("grant_type", "refresh_token")
            .build()
        return grantFrom(tokenCall(body), fallbackScopes = account.grantedScopes.toList())
    }

    private suspend fun tokenCall(body: FormBody): JSONObject = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(map.api.tokenUrl).post(body).build()
        val (code, text) = try {
            http.newCall(request).execute().use { it.code to it.body?.string().orEmpty() }
        } catch (e: IOException) {
            throw GoogleHealthException.Network(e)
        }
        // 400 invalid_grant: revoked, expired (7-day testing-mode tokens) or the client was deleted.
        if (code == 400 || code == 401) throw GoogleHealthException.Auth("token http $code")
        if (code !in 200..299) throw GoogleHealthException.Http(code)
        runCatching { JSONObject(text) }.getOrElse { throw GoogleHealthException.Http(code) }
    }

    private fun grantFrom(json: JSONObject, fallbackScopes: List<String>): GoogleHealthGrant {
        val token = json.optString("access_token").takeIf { it.isNotBlank() } ?: throw GoogleHealthException.Auth("no access token")
        val scopes = json.optString("scope").split(' ').filter { it.isNotBlank() }.toSet().ifEmpty { fallbackScopes.toSet() }
        val expires = json.optLong("expires_in", 3600L)
        return GoogleHealthGrant(token, scopes, nowMs() + expires * 1000L)
    }

    // -- Account label, revoke ---------------------------------------------------------------------

    /** `openid email` userinfo → the address shown in Settings; null when it cannot be read. */
    suspend fun email(accessToken: String): String? = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(map.api.userinfoUrl).header("Authorization", "Bearer $accessToken").get().build()
        runCatching {
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) null
                else JSONObject(response.body?.string().orEmpty()).optString("email").takeIf { it.isNotBlank() }
            }
        }.getOrNull()
    }

    /** Best-effort revoke of the refresh token (custom) or the current access token (bundled), then forget them. */
    suspend fun revokeAndClear() {
        val token = keyStore.load(KEY_REFRESH_TOKEN) ?: cached?.accessToken
        if (token != null) {
            withContext(Dispatchers.IO) {
                runCatching {
                    val url = map.api.revokeUrl.toHttpUrl().newBuilder().addQueryParameter("token", token).build()
                    http.newCall(Request.Builder().url(url).post(ByteArray(0).toRequestBody(null)).build()).execute().close()
                }
                cached?.let { c -> runCatching { GoogleAuthUtil.clearToken(context, c.accessToken) } }
            }
        }
        cached = null
        keyStore.delete(KEY_REFRESH_TOKEN)
        keyStore.delete(KEY_CLIENT_SECRET)
    }

    fun forgetCachedToken() {
        cached = null
    }

    companion object {
        const val LOOPBACK_HOST = "127.0.0.1"
        private const val KEY_REFRESH_TOKEN = "google_health_refresh_token_v1"
        private const val KEY_CLIENT_SECRET = "google_health_client_secret_v1"
        /** Identity does not report expiry; Google access tokens live an hour. */
        private const val BUNDLED_TOKEN_TTL_MS = 50 * 60_000L
        private const val REDIRECT_TIMEOUT_MS = 5 * 60_000
        private val random = SecureRandom()

        fun randomUrlSafe(bytes: Int): String {
            val buffer = ByteArray(bytes)
            random.nextBytes(buffer)
            return Base64.getUrlEncoder().withoutPadding().encodeToString(buffer)
        }

        /** RFC 7636 verifier: 43–128 unreserved characters (64 random bytes → 86). */
        fun codeVerifier(): String = randomUrlSafe(64)

        /** S256 challenge = BASE64URL(SHA-256(verifier)) without padding. */
        fun codeChallenge(verifier: String): String =
            Base64.getUrlEncoder().withoutPadding().encodeToString(
                MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
            )

        fun authUrl(base: String, clientId: String, redirect: String, scopes: List<String>, challenge: String, state: String): String =
            base.toHttpUrl().newBuilder()
                .addQueryParameter("client_id", clientId)
                .addQueryParameter("redirect_uri", redirect)
                .addQueryParameter("response_type", "code")
                .addQueryParameter("scope", scopes.joinToString(" "))
                .addQueryParameter("code_challenge", challenge)
                .addQueryParameter("code_challenge_method", "S256")
                .addQueryParameter("state", state)
                .addQueryParameter("access_type", "offline")
                .addQueryParameter("prompt", "consent")
                .addQueryParameter("include_granted_scopes", "true")
                .build()
                .toString()

        /** Query of the redirect's request line (`GET /?code=…&state=… HTTP/1.1`). */
        fun parseRedirectRequestLine(line: String): Map<String, String>? {
            val target = line.split(' ').getOrNull(1) ?: return null
            if (!target.startsWith("/?") && target != "/") return null
            return target.substringAfter('?', "").split('&').filter { it.contains('=') }.associate { pair ->
                val (k, v) = pair.split('=', limit = 2)
                URLDecoder.decode(k, "UTF-8") to URLDecoder.decode(v, "UTF-8")
            }
        }

        /** Blocks for the browser's redirect; other paths (favicon) get 404 and the wait continues. */
        private fun awaitRedirect(server: ServerSocket): Map<String, String> {
            server.soTimeout = REDIRECT_TIMEOUT_MS
            while (true) {
                val socket = try {
                    server.accept()
                } catch (e: SocketTimeoutException) {
                    throw GoogleHealthException.Cancelled()
                }
                socket.use { s ->
                    s.soTimeout = 10_000
                    val line = s.getInputStream().bufferedReader().readLine().orEmpty()
                    val params = parseRedirectRequestLine(line)
                    val out = s.getOutputStream()
                    if (params == null || params.isEmpty()) {
                        out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                        out.flush()
                    } else {
                        val html = "<!doctype html><html><body style=\"font-family:sans-serif;text-align:center;padding:48px\">" +
                            "<p>You can close this tab and return to Ayuvo.</p></body></html>"
                        val bytes = html.toByteArray(Charsets.UTF_8)
                        out.write("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                        out.write(bytes)
                        out.flush()
                        return params
                    }
                }
            }
        }
    }
}
