package com.ayuvo.health.ui.coach

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ayuvo.health.R
import com.ayuvo.health.coach.model.CoachExportFormat
import com.ayuvo.health.coach.model.ConversationSummary
import androidx.compose.material3.MaterialTheme
import com.ayuvo.health.ui.theme.AppColors
import java.text.DateFormat
import java.util.Date

/**
 * The Coach conversation list (docs/coach.md §7): search, open, rename, duplicate and delete.
 *
 * "New chat" adds a conversation rather than clearing one — the old Reset button destroyed the only
 * history there was, which is what this sheet exists to fix.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationListSheet(
    conversations: List<ConversationSummary>,
    currentId: String?,
    onSearch: suspend (String) -> List<ConversationSummary>,
    onOpen: (String) -> Unit,
    onNew: () -> Unit,
    onRename: (String, String) -> Unit,
    onDuplicate: (String) -> Unit,
    onPin: (String, Boolean) -> Unit,
    onDelete: (String) -> Unit,
    onExport: (String, CoachExportFormat) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<ConversationSummary>?>(null) }
    var renaming by remember { mutableStateOf<ConversationSummary?>(null) }
    var renameText by remember { mutableStateOf("") }
    var pendingDelete by remember { mutableStateOf<ConversationSummary?>(null) }

    LaunchedEffect(query) {
        results = if (query.isBlank()) null else onSearch(query)
    }

    val rows = results ?: conversations

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    stringResource(R.string.coach_chats_title),
                    fontSize = 20.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onNew) {
                    Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.coach_new_chat),
                         tint = AppColors.Calorie)
                }
            }

            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                placeholder = { Text(stringResource(R.string.coach_chats_search)) },
                keyboardOptions = KeyboardOptions.Default,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp)
            )

            if (rows.isEmpty()) {
                EmptyChats(searching = query.isNotBlank())
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    items(rows, key = { it.conversation.id }) { row ->
                        ConversationRow(
                            summary = row,
                            isCurrent = row.conversation.id == currentId,
                            onOpen = { onOpen(row.conversation.id) },
                            onRename = {
                                renameText = row.conversation.title
                                renaming = row
                            },
                            onDuplicate = { onDuplicate(row.conversation.id) },
                            onPin = { onPin(row.conversation.id, !row.conversation.pinned) },
                            onExport = { format -> onExport(row.conversation.id, format) },
                            onDelete = { pendingDelete = row }
                        )
                    }
                }
            }
        }
    }

    renaming?.let { target ->
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text(stringResource(R.string.coach_chat_rename_title)) },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.coach_chat_rename_label)) }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onRename(target.conversation.id, renameText)
                    renaming = null
                }) { Text(stringResource(R.string.action_save)) }
            },
            dismissButton = {
                TextButton(onClick = { renaming = null }) { Text(stringResource(R.string.action_cancel)) }
            }
        )
    }

    pendingDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.coach_chat_delete_title)) },
            text = { Text(stringResource(R.string.coach_chat_delete_message)) },
            confirmButton = {
                TextButton(onClick = {
                    onDelete(target.conversation.id)
                    pendingDelete = null
                }) { Text(stringResource(R.string.coach_chat_delete), color = Color(0xFFD32F2F)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text(stringResource(R.string.action_cancel)) }
            }
        )
    }
}

@Composable
private fun ConversationRow(
    summary: ConversationSummary,
    isCurrent: Boolean,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onDuplicate: () -> Unit,
    onPin: () -> Unit,
    onExport: (CoachExportFormat) -> Unit,
    onDelete: () -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }
    var exportOpen by remember { mutableStateOf(false) }
    val title = summary.conversation.title.ifEmpty { stringResource(R.string.coach_chat_untitled) }

    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.Top
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (summary.conversation.pinned) {
                    Icon(Icons.Filled.PushPin, contentDescription = null,
                         tint = AppColors.Calorie, modifier = Modifier.size(12.dp))
                    Spacer(Modifier.width(5.dp))
                }
                Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            }
            if (summary.snippet.isNotEmpty()) {
                Spacer(Modifier.height(2.dp))
                Text(summary.snippet, fontSize = 13.sp, maxLines = 2,
                     color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.65f))
            }
            Spacer(Modifier.height(3.dp))
            Text(relativeTime(summary), fontSize = 11.sp, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.45f))
        }
        if (isCurrent) {
            Icon(Icons.Filled.Check, contentDescription = null, tint = AppColors.Calorie,
                 modifier = Modifier.size(18.dp))
        }
        Box {
            IconButton(onClick = { menuOpen = true }) {
                Icon(Icons.Filled.MoreVert, contentDescription = null, tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.65f))
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.coach_chat_rename)) },
                    onClick = { menuOpen = false; onRename() }
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.coach_chat_duplicate)) },
                    onClick = { menuOpen = false; onDuplicate() }
                )
                DropdownMenuItem(
                    text = {
                        Text(stringResource(
                            if (summary.conversation.pinned) R.string.coach_chat_unpin
                            else R.string.coach_chat_pin
                        ))
                    },
                    onClick = { menuOpen = false; onPin() }
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.coach_chat_export)) },
                    onClick = { menuOpen = false; exportOpen = true }
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.coach_chat_delete), color = Color(0xFFD32F2F)) },
                    onClick = { menuOpen = false; onDelete() }
                )
            }
            // §10: a readable transcript, or the archive shape so it imports like a backup.
            DropdownMenu(expanded = exportOpen, onDismissRequest = { exportOpen = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.coach_chat_export_markdown)) },
                    onClick = { exportOpen = false; onExport(CoachExportFormat.MARKDOWN) }
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.coach_chat_export_json)) },
                    onClick = { exportOpen = false; onExport(CoachExportFormat.JSON) }
                )
            }
        }
    }
}

@Composable
private fun EmptyChats(searching: Boolean) {
    Box(Modifier.fillMaxWidth().height(180.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                stringResource(
                    if (searching) R.string.coach_chats_no_matches_title else R.string.coach_chats_empty_title
                ),
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(
                    if (searching) R.string.coach_chats_no_matches_subtitle
                    else R.string.coach_chats_empty_subtitle
                ),
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.65f),
                modifier = Modifier.padding(horizontal = 40.dp)
            )
        }
    }
}

private fun relativeTime(summary: ConversationSummary): String {
    val ms = summary.conversation.lastMessageMs ?: summary.conversation.updatedMs
    return DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(ms))
}
