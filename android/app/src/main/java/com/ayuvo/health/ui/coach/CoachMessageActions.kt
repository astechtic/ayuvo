package com.ayuvo.health.ui.coach

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.RadioButton
import androidx.compose.material3.TextButton
import androidx.compose.ui.platform.LocalContext
import android.net.Uri
import android.widget.Toast
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.ayuvo.health.R
import com.ayuvo.health.coach.model.CoachExportFile
import com.ayuvo.health.coach.model.CoachMessage
import kotlinx.coroutines.delay
import java.io.File

/**
 * Copy, regenerate, share and report, under each assistant reply (docs/coach.md §10).
 *
 * Regenerate never deletes: the new reply keeps the seq it replaces and becomes another version, so
 * the `‹ 1/2 ›` stepper can walk back to the answer the user preferred.
 */
@Composable
fun CoachMessageActions(
    message: CoachMessage,
    /** Every stored version of this reply, oldest first. One version means no stepper. */
    variants: List<CoachMessage>,
    isBusy: Boolean,
    onCopy: () -> Unit,
    onRegenerate: () -> Unit,
    onShare: () -> Unit,
    onShowVariant: (CoachMessage) -> Unit,
    modifier: Modifier = Modifier
) {
    var copied by remember(message.id) { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(1600)
            copied = false
        }
    }
    val index = variants.indexOfFirst { it.id == message.id }
        .let { if (it >= 0) it else (variants.size - 1).coerceAtLeast(0) }

    Row(
        modifier = modifier.padding(start = 46.dp, end = 16.dp, top = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        if (variants.size > 1) {
            ActionIcon(
                icon = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                description = stringResource(R.string.coach_message_version_previous),
                enabled = index > 0,
                tag = "coach.message.variant.previous",
                onClick = { onShowVariant(variants[index - 1]) }
            )
            Text(
                "${index + 1}/${variants.size}",
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                modifier = Modifier.testTag("coach.message.variant")
            )
            ActionIcon(
                icon = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                description = stringResource(R.string.coach_message_version_next),
                enabled = index + 1 < variants.size,
                tag = "coach.message.variant.next",
                onClick = { onShowVariant(variants[index + 1]) }
            )
            Spacer(Modifier.width(2.dp))
        }

        ActionIcon(
            icon = if (copied) Icons.Filled.Check else Icons.Filled.ContentCopy,
            description = stringResource(
                if (copied) R.string.coach_message_copied else R.string.coach_message_copy
            ),
            tag = "coach.message.copy",
            onClick = {
                onCopy()
                copied = true
            }
        )
        ActionIcon(
            icon = Icons.Filled.Refresh,
            description = stringResource(R.string.coach_message_regenerate),
            enabled = !isBusy,
            tag = "coach.message.regenerate",
            onClick = onRegenerate
        )
        ActionIcon(
            icon = Icons.Filled.Share,
            description = stringResource(R.string.coach_message_share),
            tag = "coach.message.share",
            onClick = onShare
        )
        CoachReportAction(message)
    }
}

/**
 * Reporting an AI reply (Google Play AI-generated content policy): the user picks a reason in an in-app dialog,
 * the reply is marked "Reported" on this device, and — only if the user keeps the box ticked — the reply and the
 * reason go to Ayuvo by email. Nothing is sent automatically; there is no server.
 */
@Composable
private fun CoachReportAction(message: CoachMessage) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(CoachReport.PREFS, Context.MODE_PRIVATE) }
    var reported by remember(message.id) { mutableStateOf(CoachReport.isReported(prefs, message.id)) }
    var open by remember { mutableStateOf(false) }
    ActionIcon(
        icon = if (reported) Icons.Filled.Flag else Icons.Outlined.Flag,
        description = stringResource(if (reported) R.string.coach_message_reported else R.string.coach_message_report),
        tag = "coach.message.report",
        onClick = { open = true }
    )
    if (!open) return
    val reasons = listOf(
        "offensive" to R.string.coach_report_reason_offensive,
        "harmful" to R.string.coach_report_reason_harmful,
        "incorrect" to R.string.coach_report_reason_incorrect,
        "other" to R.string.coach_report_reason_other
    )
    var reason by remember { mutableStateOf<String?>(null) }
    var sendEmail by remember { mutableStateOf(true) }
    val doneText = stringResource(R.string.coach_report_done)
    AlertDialog(
        onDismissRequest = { open = false },
        title = { Text(stringResource(R.string.coach_report_title)) },
        text = {
            Column {
                Text(stringResource(R.string.coach_report_intro), fontSize = 13.sp)
                reasons.forEach { (key, label) ->
                    Row(
                        Modifier.fillMaxWidth().selectable(selected = reason == key, onClick = { reason = key })
                            .padding(vertical = 2.dp).testTag("coach.report.reason.$key"),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = reason == key, onClick = { reason = key })
                        Text(stringResource(label))
                    }
                }
                Row(
                    Modifier.fillMaxWidth().clickable { sendEmail = !sendEmail }.testTag("coach.report.email"),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(checked = sendEmail, onCheckedChange = { sendEmail = it })
                    Text(stringResource(R.string.coach_report_email))
                }
                Text(stringResource(R.string.coach_report_email_note), fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
            }
        },
        confirmButton = {
            TextButton(
                enabled = reason != null,
                modifier = Modifier.testTag("coach.report.send"),
                onClick = {
                    val r = reason ?: return@TextButton
                    CoachReport.markReported(prefs, message.id)
                    reported = true
                    open = false
                    Toast.makeText(context, doneText, Toast.LENGTH_SHORT).show()
                    if (sendEmail) CoachReport.email(context, message, r)
                }
            ) { Text(stringResource(R.string.coach_report_send)) }
        },
        dismissButton = { TextButton(onClick = { open = false }) { Text(stringResource(android.R.string.cancel)) } }
    )
}

/** Device-local record of reported replies, and the optional report email. */
object CoachReport {
    const val PREFS = "coach_reports"
    private const val KEY = "reported_ids"
    private const val SUPPORT_EMAIL = "yaaratech@gmail.com"

    fun isReported(prefs: android.content.SharedPreferences, id: String) = prefs.getStringSet(KEY, emptySet())!!.contains(id)

    fun markReported(prefs: android.content.SharedPreferences, id: String) {
        prefs.edit().putStringSet(KEY, prefs.getStringSet(KEY, emptySet())!! + id).apply()
    }

    fun email(context: Context, message: CoachMessage, reason: String) {
        val version = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "?"
        val body = "AI response report\nReason: $reason\nApp: Ayuvo Android $version\n\nReported response:\n" + message.content.take(6000)
        val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:")).apply {
            putExtra(Intent.EXTRA_EMAIL, arrayOf(SUPPORT_EMAIL))
            putExtra(Intent.EXTRA_SUBJECT, "Ayuvo AI response report")
            putExtra(Intent.EXTRA_TEXT, body)
        }
        runCatching { context.startActivity(intent) }
    }
}

@Composable
private fun ActionIcon(
    icon: ImageVector,
    description: String,
    tag: String,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Box {
        IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(30.dp).testTag(tag)) {
            Icon(
                icon,
                contentDescription = description,
                modifier = Modifier.size(15.dp),
                tint = MaterialTheme.colorScheme.onBackground.copy(alpha = if (enabled) 0.6f else 0.25f)
            )
        }
    }
}

/** Sharing a reply or an exported chat (docs/coach.md §10). */
object CoachShare {
    /** One message's markdown, as plain text. */
    fun text(context: Context, body: String, title: String) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, body)
        }
        context.startActivity(Intent.createChooser(send, title))
    }

    /**
     * An exported chat, through the FileProvider `coach_share` path. The cache copy is replaced on
     * every export, so a chat the user has since deleted never lingers under a stale name.
     */
    fun file(context: Context, export: CoachExportFile, title: String): Boolean = runCatching {
        val dir = File(context.cacheDir, "coach-share").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val file = File(dir, export.filename)
        file.writeBytes(export.bytes)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = export.mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, title))
        true
    }.getOrDefault(false)
}
