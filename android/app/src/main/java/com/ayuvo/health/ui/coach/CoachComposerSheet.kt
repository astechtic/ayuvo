package com.ayuvo.health.ui.coach

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Medication
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ayuvo.health.R
import com.ayuvo.health.coach.model.CoachSource
import com.ayuvo.health.ui.theme.AppColors

/** What the composer's `+` can attach (docs/coach.md §6). */
enum class CoachAttachAction { CAMERA, PHOTOS, FILES, RECORD, NOTE }

/** Why a source cannot simply be switched on here (docs/coach.md §8). */
enum class CoachSourceState { ON, OFF, UNAVAILABLE, NOT_CONNECTED }

/**
 * What the composer's `+` opens: what to attach, and which of the user's data Coach may read in this
 * conversation.
 *
 * The switches only ever narrow. A source the user has not connected shows "Connect" and opens its
 * own consent instead of flipping here — turning a switch on never grants access.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CoachComposerSheet(
    states: Map<CoachSource, CoachSourceState>,
    onAttach: (CoachAttachAction) -> Unit,
    onToggle: (CoachSource, Boolean) -> Unit,
    onConnect: (CoachSource) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 20.dp)
        ) {
            SectionHeader(stringResource(R.string.coach_attach_header))
            AttachRow(CoachAttachAction.CAMERA, Icons.Filled.CameraAlt,
                      R.string.coach_attach_camera, R.string.coach_attach_camera_hint, onAttach)
            AttachRow(CoachAttachAction.PHOTOS, Icons.Filled.PhotoLibrary,
                      R.string.coach_attach_photos, R.string.coach_attach_photos_hint, onAttach)
            AttachRow(CoachAttachAction.FILES, Icons.Filled.Description,
                      R.string.coach_attach_files, R.string.coach_attach_files_hint, onAttach)
            AttachRow(CoachAttachAction.RECORD, Icons.AutoMirrored.Filled.List,
                      R.string.coach_attach_record, R.string.coach_attach_record_hint, onAttach)
            AttachRow(CoachAttachAction.NOTE, Icons.Filled.EditNote,
                      R.string.coach_attach_note, R.string.coach_attach_note_hint, onAttach)

            Spacer(Modifier.height(10.dp))
            SectionHeader(stringResource(R.string.coach_sources_header))
            for (source in CoachSource.entries) {
                SourceRow(
                    source = source,
                    state = states[source] ?: CoachSourceState.UNAVAILABLE,
                    onToggle = { onToggle(source, it) },
                    onConnect = { onConnect(source) }
                )
            }
            Text(
                stringResource(R.string.coach_sources_footer),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
            )
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 4.dp)
    )
}

@Composable
private fun AttachRow(
    action: CoachAttachAction,
    icon: ImageVector,
    titleRes: Int,
    hintRes: Int,
    onAttach: (CoachAttachAction) -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onAttach(action) }
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(34.dp)
                .background(AppColors.Calorie.copy(alpha = 0.12f), RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null, tint = AppColors.Calorie, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(stringResource(titleRes), fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text(
                stringResource(hintRes),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
            )
        }
    }
}

@Composable
private fun SourceRow(
    source: CoachSource,
    state: CoachSourceState,
    onToggle: (Boolean) -> Unit,
    onConnect: () -> Unit
) {
    val on = state == CoachSourceState.ON
    val tint = if (on) AppColors.Calorie else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.45f)
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(30.dp).background(tint.copy(alpha = 0.12f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(iconFor(source), contentDescription = null, tint = tint, modifier = Modifier.size(16.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(stringResource(titleFor(source)), fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            noteFor(state)?.let {
                Text(
                    stringResource(it),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
                )
            }
        }
        when (state) {
            CoachSourceState.ON, CoachSourceState.OFF ->
                Switch(
                    checked = on,
                    onCheckedChange = onToggle,
                    colors = SwitchDefaults.colors(checkedTrackColor = AppColors.Calorie)
                )
            CoachSourceState.NOT_CONNECTED ->
                TextButton(onClick = onConnect) { Text(stringResource(R.string.coach_source_connect)) }
            CoachSourceState.UNAVAILABLE ->
                Text(
                    stringResource(R.string.coach_source_no_data),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.45f)
                )
        }
    }
}

private fun iconFor(source: CoachSource): ImageVector = when (source) {
    CoachSource.FOOD -> Icons.Filled.Restaurant
    CoachSource.HEALTH -> Icons.Filled.Favorite
    CoachSource.MEDICATIONS -> Icons.Filled.Medication
    CoachSource.RECORDS -> Icons.AutoMirrored.Filled.List
}

private fun titleFor(source: CoachSource): Int = when (source) {
    CoachSource.FOOD -> R.string.coach_source_food
    CoachSource.HEALTH -> R.string.coach_source_health
    CoachSource.MEDICATIONS -> R.string.coach_source_medications
    CoachSource.RECORDS -> R.string.coach_source_records
}

private fun noteFor(state: CoachSourceState): Int? = when (state) {
    CoachSourceState.ON -> null
    CoachSourceState.OFF -> R.string.coach_source_off_here
    CoachSourceState.UNAVAILABLE -> R.string.coach_source_nothing_yet
    CoachSourceState.NOT_CONNECTED -> R.string.coach_source_not_connected
}

/**
 * A typed or pasted note. Redacted like any other text before it is attached (docs/coach.md §6).
 */
@Composable
fun CoachNoteDialog(onAttach: (String) -> Unit, onDismiss: () -> Unit) {
    val text = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf("") }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.coach_note_title)) },
        text = {
            androidx.compose.material3.OutlinedTextField(
                value = text.value,
                onValueChange = { text.value = it },
                minLines = 4
            )
        },
        confirmButton = {
            TextButton(onClick = { onAttach(text.value) }, enabled = text.value.isNotBlank()) {
                Text(stringResource(R.string.coach_note_attach))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        }
    )
}

/**
 * The one-time confirmation before Coach may read the user's medicines (docs/coach.md §3).
 * Turning the switch on is the affirmative act; this states plainly what leaves the device.
 */
@Composable
fun CoachMedicationsConsentDialog(onAllow: () -> Unit, onNotNow: () -> Unit) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onNotNow,
        title = { Text(stringResource(R.string.coach_medications_consent_title)) },
        text = {
            Column {
                Text(stringResource(R.string.coach_medications_consent_body_on_device))
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.coach_medications_consent_note),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onAllow) { Text(stringResource(R.string.coach_medications_consent_allow)) }
        },
        dismissButton = {
            TextButton(onClick = onNotNow) { Text(stringResource(R.string.coach_medications_consent_not_now)) }
        }
    )
}
