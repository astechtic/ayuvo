package com.ayuvo.health.ui.records

import com.ayuvo.health.ui.design.AyuvoColors
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ayuvo.health.R
import com.ayuvo.health.records.data.RecordFileStore
import com.ayuvo.health.records.ingest.DuplicateMatch
import com.ayuvo.health.records.model.HealthRecord
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.ayuvo.health.records.model.DuplicateReason
import com.ayuvo.health.ui.components.GlassDialog
import com.ayuvo.health.ui.components.GlassDialogActions
import com.ayuvo.health.ui.components.GlassPrimaryButton
import com.ayuvo.health.ui.components.GlassTextButton
import com.ayuvo.health.ui.theme.AppColors

/**
 * "This looks like an existing record" (§4.5, plan §3.11): Keep both, Replace (the existing record
 * takes the new file and is reprocessed), Merge (notes and tags move into the existing record),
 * Open existing and Cancel (deletes the new import). Replace and Merge each ask once more.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DuplicateRecordSheet(
    match: DuplicateMatch,
    files: RecordFileStore,
    onKeepBoth: () -> Unit,
    onOpenExisting: () -> Unit,
    onCancelImport: () -> Unit,
    onReplace: () -> Unit,
    onMerge: () -> Unit,
    reason: DuplicateReason = DuplicateReason.CHECKSUM
) {
    var confirm by remember { mutableStateOf<DuplicateAction?>(null) }
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onKeepBoth,
        sheetState = state,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        containerColor = AyuvoColors.sheetBackground()
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .navigationBarsPadding()
                .padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(stringResource(R.string.records_duplicate_title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(
                stringResource(
                    when (reason) {
                        DuplicateReason.CHECKSUM -> R.string.records_duplicate_reason_identical
                        DuplicateReason.PHASH -> R.string.records_duplicate_reason_looks_same
                        DuplicateReason.CONTENT -> R.string.records_duplicate_reason_same_content
                    }
                ),
                color = AppColors.Calorie,
                fontWeight = FontWeight.SemiBold
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                DuplicateSide(
                    label = stringResource(R.string.records_duplicate_new),
                    record = match.newRecord,
                    files = files,
                    modifier = Modifier.weight(1f)
                )
                DuplicateSide(
                    label = stringResource(R.string.records_duplicate_existing),
                    record = match.existing,
                    files = files,
                    modifier = Modifier.weight(1f)
                )
            }
            GlassPrimaryButton(text = stringResource(R.string.records_duplicate_keep_both), onClick = onKeepBoth)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GlassTextButton(
                    text = stringResource(R.string.records_duplicate_replace),
                    onClick = { confirm = DuplicateAction.REPLACE },
                    modifier = Modifier.weight(1f)
                )
                GlassTextButton(
                    text = stringResource(R.string.records_duplicate_merge),
                    onClick = { confirm = DuplicateAction.MERGE },
                    modifier = Modifier.weight(1f)
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GlassTextButton(
                    text = stringResource(R.string.records_duplicate_open_existing),
                    onClick = onOpenExisting,
                    modifier = Modifier.weight(1f)
                )
                GlassTextButton(
                    text = stringResource(R.string.records_duplicate_cancel_import),
                    onClick = onCancelImport,
                    color = Color(0xFFFF453A),
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }

    confirm?.let { action ->
        GlassDialog(onDismissRequest = { confirm = null }) {
            Text(
                stringResource(if (action == DuplicateAction.REPLACE) R.string.records_duplicate_replace_title else R.string.records_duplicate_merge_title),
                fontSize = 21.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                stringResource(if (action == DuplicateAction.REPLACE) R.string.records_duplicate_replace_body else R.string.records_duplicate_merge_body),
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.68f)
            )
            GlassDialogActions(
                primaryText = stringResource(if (action == DuplicateAction.REPLACE) R.string.records_duplicate_replace else R.string.records_duplicate_merge),
                onPrimary = {
                    confirm = null
                    if (action == DuplicateAction.REPLACE) onReplace() else onMerge()
                },
                dismissText = stringResource(R.string.action_cancel),
                onDismiss = { confirm = null }
            )
        }
    }
}

private enum class DuplicateAction { REPLACE, MERGE }

@Composable
private fun DuplicateSide(label: String, record: HealthRecord, files: RecordFileStore, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
        RecordThumbnail(
            record = record,
            files = files,
            size = null,
            cornerRadius = 16.dp,
            modifier = Modifier.fillMaxWidth().aspectRatio(0.8f)
        )
        Text(record.title, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(
            RecordFormat.addedAt(record.createdMs),
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
        )
    }
}
