package com.ayuvo.health.backup

import android.accounts.Account
import android.accounts.AccountManager
import android.app.Activity
import android.content.Intent
import android.content.IntentSender
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.AccountPicker
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Google Drive appData folder client. OAuth is requested only when the user
 * turns on Google Drive Backup — never at install or first launch.
 */
class DriveCloudBackupClient(
    private val webClientId: String,
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build(),
) {
    sealed class AuthOutcome {
        data class Token(val accessToken: String) : AuthOutcome()
        data class Resolution(val intentSender: IntentSender) : AuthOutcome()
    }

    /** Always shows every Google account on the device (not Continue for the last one). */
    fun accountPickerIntent(): Intent {
        val options = AccountPicker.AccountChooserOptions.Builder()
            .setAllowableAccountsTypes(listOf("com.google"))
            .setAlwaysShowAccountPicker(true)
            .build()
        return AccountPicker.newChooseAccountIntent(options)
    }

    fun accountFromPickerResult(data: Intent?): Account? {
        val name = data?.getStringExtra(AccountManager.KEY_ACCOUNT_NAME)?.takeIf { it.isNotBlank() }
            ?: return null
        val type = data.getStringExtra(AccountManager.KEY_ACCOUNT_TYPE)?.takeIf { it.isNotBlank() }
            ?: "com.google"
        return Account(name, type)
    }

    suspend fun authorize(activity: Activity, account: Account): AuthOutcome {
        val scopes = listOf(
            Scope(DRIVE_APPDATA_SCOPE),
            Scope(EMAIL_SCOPE),
        )
        val builder = AuthorizationRequest.builder()
            .setRequestedScopes(scopes)
            .setAccount(account)
            .setOptOutIncludingGrantedScopes(true)
        if (webClientId.isNotBlank()) {
            builder.requestOfflineAccess(webClientId, /* forceCodeForRefreshToken = */ true)
        }
        val result = Identity.getAuthorizationClient(activity)
            .authorize(builder.build())
            .await()
        return outcome(result)
    }

    /** Clears One Tap / Identity cached Google session so the next pick is fresh. */
    suspend fun clearSignInSession(context: android.content.Context) {
        runCatching { Identity.getSignInClient(context).signOut().await() }
    }

    fun parseAuthorizationResult(activity: Activity, data: Intent?): String? {
        if (data == null) return null
        val result = Identity.getAuthorizationClient(activity).getAuthorizationResultFromIntent(data)
        return result.accessToken
    }

    private fun outcome(result: AuthorizationResult): AuthOutcome {
        val pending = result.pendingIntent
        return if (pending != null) {
            AuthOutcome.Resolution(pending.intentSender)
        } else {
            val token = result.accessToken ?: error("Google did not return an access token")
            AuthOutcome.Token(token)
        }
    }

    suspend fun accountEmail(accessToken: String): String? = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("https://www.googleapis.com/oauth2/v2/userinfo")
            .header("Authorization", "Bearer $accessToken")
            .get()
            .build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@use null
            val body = response.body?.string() ?: return@use null
            JSONObject(body).optString("email").takeIf { it.isNotBlank() }
        }
    }

    suspend fun findBackupFileId(accessToken: String): String? = withContext(Dispatchers.IO) {
        val url = "https://www.googleapis.com/drive/v3/files" +
            "?spaces=appDataFolder" +
            "&q=${DRIVE_FILE_QUERY}" +
            "&fields=files(id,modifiedTime,name)" +
            "&pageSize=1"
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $accessToken")
            .get()
            .build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("Drive list failed (${response.code})")
            val body = response.body?.string() ?: return@use null
            val files = JSONObject(body).optJSONArray("files") ?: return@use null
            if (files.length() == 0) null else files.getJSONObject(0).optString("id").takeIf { it.isNotBlank() }
        }
    }

    suspend fun upload(accessToken: String, zip: ByteArray, existingFileId: String?): String =
        withContext(Dispatchers.IO) {
            if (existingFileId != null) {
                update(accessToken, existingFileId, zip)
                existingFileId
            } else {
                create(accessToken, zip)
            }
        }

    /**
     * Health Records archive (docs/health-records.md §36): a **resumable** upload of a cache file to
     * appDataFolder, so a large archive never sits in memory and a dropped connection resumes at the
     * last confirmed byte. Returns the Drive file id.
     */
    suspend fun uploadResumable(
        accessToken: String,
        file: File,
        name: String,
        existingFileId: String?,
        onProgress: (sent: Long, total: Long) -> Unit = { _, _ -> },
    ): String = withContext(Dispatchers.IO) {
        val total = file.length()
        val session = startResumableSession(accessToken, name, existingFileId, total)
        var offset = 0L
        var attempts = 0
        while (offset < total) {
            val end = minOf(offset + RESUMABLE_CHUNK_BYTES, total)
            val chunk = FileChunkBody(file, offset, end - offset)
            val request = Request.Builder()
                .url(session)
                .header("Content-Range", "bytes $offset-${end - 1}/$total")
                .put(chunk)
                .build()
            val (done, next) = http.newCall(request).execute().use { response ->
                when {
                    response.isSuccessful -> {
                        val body = response.body?.string().orEmpty()
                        val id = runCatching { JSONObject(body).optString("id") }.getOrNull()?.takeIf { it.isNotBlank() }
                        (id ?: existingFileId ?: error("Drive resumable upload returned no id")) to total
                    }
                    // 308 Resume Incomplete: continue after the last byte Drive confirmed.
                    response.code == 308 -> {
                        val range = response.header("Range")
                        val confirmed = range?.substringAfter('-', "")?.toLongOrNull()?.plus(1) ?: end
                        null to confirmed
                    }
                    response.code in 500..599 && attempts < RESUMABLE_MAX_RETRIES -> {
                        attempts++
                        null to offset
                    }
                    else -> error("Drive records upload failed (${response.code})")
                }
            }
            offset = next
            onProgress(offset, total)
            if (done != null) return@withContext done
        }
        // A zero-byte archive still needs an id.
        existingFileId ?: findFileId(accessToken, name) ?: error("Drive records upload returned no id")
    }

    /** Starts the resumable session and returns its upload URL. */
    private fun startResumableSession(accessToken: String, name: String, existingFileId: String?, total: Long): String {
        val metadata = JSONObject().apply {
            put("name", name)
            if (existingFileId == null) put("parents", org.json.JSONArray().put("appDataFolder"))
        }.toString()
        val url = if (existingFileId == null) {
            "https://www.googleapis.com/upload/drive/v3/files?uploadType=resumable"
        } else {
            "https://www.googleapis.com/upload/drive/v3/files/$existingFileId?uploadType=resumable"
        }
        val body = metadata.toRequestBody("application/json; charset=UTF-8".toMediaType())
        val builder = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $accessToken")
            .header("X-Upload-Content-Type", "application/zip")
            .header("X-Upload-Content-Length", total.toString())
        val request = if (existingFileId == null) builder.post(body).build() else builder.patch(body).build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("Drive resumable session failed (${response.code})")
            return response.header("Location") ?: error("Drive returned no resumable session URL")
        }
    }

    /** Streams one chunk of [file] straight from disk. */
    private class FileChunkBody(private val file: File, private val offset: Long, private val length: Long) : RequestBody() {
        override fun contentType() = "application/zip".toMediaType()
        override fun contentLength() = length
        override fun writeTo(sink: okio.BufferedSink) {
            file.inputStream().use { input ->
                var skipped = 0L
                while (skipped < offset) {
                    val n = input.skip(offset - skipped)
                    if (n <= 0) break
                    skipped += n
                }
                val buffer = ByteArray(64 * 1024)
                var remaining = length
                while (remaining > 0) {
                    val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                    if (read <= 0) break
                    sink.write(buffer, 0, read)
                    remaining -= read
                }
            }
        }
    }

    /** Newest appDataFolder file with this exact name, or null. */
    suspend fun findFileId(accessToken: String, name: String): String? = withContext(Dispatchers.IO) {
        val query = java.net.URLEncoder.encode("name='$name'", "UTF-8")
        val request = Request.Builder()
            .url("https://www.googleapis.com/drive/v3/files?spaces=appDataFolder&q=$query&fields=files(id,modifiedTime,name)&pageSize=1")
            .header("Authorization", "Bearer $accessToken")
            .get()
            .build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("Drive list failed (${response.code})")
            val body = response.body?.string() ?: return@use null
            val fileList = JSONObject(body).optJSONArray("files") ?: return@use null
            if (fileList.length() == 0) null else fileList.getJSONObject(0).optString("id").takeIf { it.isNotBlank() }
        }
    }

    /** Streams a Drive file straight to [target] (records archives are far too big for memory). */
    suspend fun downloadTo(accessToken: String, fileId: String, target: File) = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("https://www.googleapis.com/drive/v3/files/$fileId?alt=media")
            .header("Authorization", "Bearer $accessToken")
            .get()
            .build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("Drive download failed (${response.code})")
            val source = response.body?.byteStream() ?: error("Empty backup file")
            target.parentFile?.mkdirs()
            target.outputStream().use { out -> source.copyTo(out, 64 * 1024) }
        }
    }

    suspend fun download(accessToken: String, fileId: String): ByteArray = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("https://www.googleapis.com/drive/v3/files/$fileId?alt=media")
            .header("Authorization", "Bearer $accessToken")
            .get()
            .build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("Drive download failed (${response.code})")
            response.body?.bytes() ?: error("Empty backup file")
        }
    }

    suspend fun delete(accessToken: String, fileId: String) = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("https://www.googleapis.com/drive/v3/files/$fileId")
            .header("Authorization", "Bearer $accessToken")
            .delete()
            .build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful && response.code != 404) {
                error("Drive delete failed (${response.code})")
            }
        }
    }

    suspend fun revoke(accessToken: String) = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("https://oauth2.googleapis.com/revoke?token=$accessToken")
            .post(ByteArray(0).toRequestBody(null))
            .build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful && response.code != 400) {
                error("Google token revoke failed (${response.code})")
            }
        }
    }

    private fun create(accessToken: String, zip: ByteArray): String {
        val metadata = JSONObject()
            .put("name", CloudBackupPolicy.FILE_NAME)
            .put("parents", org.json.JSONArray().put("appDataFolder"))
            .toString()
        val body = MultipartBody.Builder()
            .setType("multipart/related".toMediaType())
            .addPart(
                MultipartBody.Part.create(
                    metadata.toRequestBody("application/json; charset=UTF-8".toMediaType())
                )
            )
            .addPart(
                MultipartBody.Part.create(
                    zip.toRequestBody("application/zip".toMediaType())
                )
            )
            .build()
        val request = Request.Builder()
            .url("https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart")
            .header("Authorization", "Bearer $accessToken")
            .post(body)
            .build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("Drive upload failed (${response.code})")
            val json = JSONObject(response.body?.string() ?: error("Empty Drive create response"))
            return json.getString("id")
        }
    }

    private fun update(accessToken: String, fileId: String, zip: ByteArray) {
        val request = Request.Builder()
            .url("https://www.googleapis.com/upload/drive/v3/files/$fileId?uploadType=media")
            .header("Authorization", "Bearer $accessToken")
            .patch(zip.toRequestBody("application/zip".toMediaType()))
            .build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("Drive update failed (${response.code})")
        }
    }

    companion object {
        /** Resumable chunk size; Drive requires a multiple of 256 KiB. */
        const val RESUMABLE_CHUNK_BYTES = 8L * 1024 * 1024
        private const val RESUMABLE_MAX_RETRIES = 3
        const val DRIVE_APPDATA_SCOPE = "https://www.googleapis.com/auth/drive.appdata"
        const val EMAIL_SCOPE = "https://www.googleapis.com/auth/userinfo.email"
        private const val DRIVE_FILE_QUERY = "name%3D%27ayuvo-backup.zip%27"
    }
}
