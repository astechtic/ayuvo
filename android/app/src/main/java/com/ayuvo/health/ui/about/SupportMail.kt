package com.ayuvo.health.ui.about

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.annotation.StringRes
import com.ayuvo.health.AppLinks
import com.ayuvo.health.BuildConfig
import com.ayuvo.health.R
import java.util.Locale

/**
 * "Contact Support" / "Send Feedback": opens the user's mail app with the subject and a
 * diagnostics footer (app version, Android version, device, locale) prefilled. No server is
 * involved; if no mail app is installed the address is copied to the clipboard instead.
 */
object SupportMail {
    /** Pure formatter so the footer layout is unit-testable without Android. */
    fun format(
        versionName: String,
        versionCode: Int,
        androidRelease: String,
        sdkInt: Int,
        manufacturer: String,
        model: String,
        localeTag: String
    ): String = buildString {
        appendLine()
        appendLine("— — —")
        appendLine("App: Ayuvo $versionName ($versionCode)")
        appendLine("Android: $androidRelease (API $sdkInt)")
        appendLine("Device: $manufacturer $model")
        append("Locale: $localeTag")
    }

    fun diagnostics(): String = format(
        versionName = BuildConfig.VERSION_NAME,
        versionCode = BuildConfig.VERSION_CODE,
        androidRelease = Build.VERSION.RELEASE ?: "?",
        sdkInt = Build.VERSION.SDK_INT,
        manufacturer = Build.MANUFACTURER ?: "?",
        model = Build.MODEL ?: "?",
        localeTag = Locale.getDefault().toLanguageTag()
    )

    /** Builds the `mailto:` URI (subject + body percent-encoded) — exposed for tests. */
    fun mailtoUri(address: String, subject: String, body: String): Uri =
        Uri.parse("mailto:${Uri.encode(address)}?subject=${Uri.encode(subject)}&body=${Uri.encode(body)}")

    fun compose(context: Context, @StringRes subjectRes: Int) {
        val subject = context.getString(subjectRes)
        val body = context.getString(R.string.support_body_intro) + diagnostics()
        val intent = Intent(Intent.ACTION_SENDTO, mailtoUri(AppLinks.SUPPORT_EMAIL, subject, body)).apply {
            // Some mail clients ignore the mailto query — the extras carry the same values.
            putExtra(Intent.EXTRA_EMAIL, arrayOf(AppLinks.SUPPORT_EMAIL))
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_TEXT, body)
        }
        runCatching { context.startActivity(intent) }.onFailure {
            runCatching {
                context.getSystemService(ClipboardManager::class.java)
                    ?.setPrimaryClip(ClipData.newPlainText("email", AppLinks.SUPPORT_EMAIL))
            }
            Toast.makeText(
                context,
                context.getString(R.string.about_no_email_app, AppLinks.SUPPORT_EMAIL),
                Toast.LENGTH_LONG
            ).show()
        }
    }
}
