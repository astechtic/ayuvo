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
 * Copy, regenerate and share, under each assistant reply (docs/coach.md §10).
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
