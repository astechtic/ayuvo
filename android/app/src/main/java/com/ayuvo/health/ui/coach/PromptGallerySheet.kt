package com.ayuvo.health.ui.coach

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.NorthEast
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ayuvo.health.R
import com.ayuvo.health.ui.theme.AppColors

/** One gallery entry, already resolved into this device's language. */
data class GalleryPrompt(val id: String, val title: String, val prompt: String)

/**
 * The Coach prompt gallery (docs/coach.md §9): every predefined prompt, by category, for the
 * sources this device can actually answer from.
 *
 * Tapping a card **prefills** the composer rather than sending it, so the user can edit it first —
 * the same hand-off the Records screens use (docs/health-records.md §27).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PromptGallerySheet(
    groups: List<Pair<String, List<String>>>,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var query by remember { mutableStateOf("") }

    // Resolve once per composition: the search runs over what the user can actually read.
    val sections: List<Pair<String, List<GalleryPrompt>>> = groups.map { (category, ids) ->
        val label = PromptGalleryText.category(category)?.let { stringResource(it) } ?: category
        val entries = ids.map { id ->
            GalleryPrompt(
                id = id,
                title = PromptGalleryText.title(id)?.let { stringResource(it) } ?: id,
                prompt = PromptGalleryText.prompt(id)?.let { stringResource(it) } ?: id
            )
        }
        label to entries
    }
    val needle = query.trim().lowercase()
    val shown = if (needle.isEmpty()) sections else sections.mapNotNull { (label, entries) ->
        val hits = entries.filter {
            it.title.lowercase().contains(needle) || it.prompt.lowercase().contains(needle)
        }
        if (hits.isEmpty()) null else label to hits
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.fillMaxWidth().padding(bottom = 12.dp).testTag("coach.prompts")) {
            Text(
                stringResource(R.string.coach_prompts_title),
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)
            )
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                placeholder = { Text(stringResource(R.string.coach_prompts_search)) },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp)
            )

            if (shown.isEmpty()) {
                EmptyGallery(searching = needle.isNotEmpty())
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(bottom = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    for ((label, entries) in shown) {
                        item(key = "h:$label") {
                            Text(
                                label,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f),
                                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 10.dp)
                            )
                        }
                        for (entry in entries) {
                            item(key = entry.id) { PromptCard(entry, onPick) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PromptCard(entry: GalleryPrompt, onPick: (String) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.onBackground.copy(alpha = 0.05f))
            .clickable { onPick(entry.prompt) }
            .padding(horizontal = 14.dp, vertical = 12.dp)
            .testTag("coach.prompt.${entry.id}"),
        verticalAlignment = Alignment.Top
    ) {
        Icon(
            Icons.Filled.NorthEast,
            contentDescription = null,
            tint = AppColors.Calorie,
            modifier = Modifier.size(14.dp).padding(top = 1.dp)
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(entry.title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(2.dp))
            Text(
                entry.prompt,
                fontSize = 12.sp,
                maxLines = 2,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
            )
        }
    }
}

@Composable
private fun EmptyGallery(searching: Boolean) {
    Box(Modifier.fillMaxWidth().height(180.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                stringResource(
                    if (searching) R.string.coach_prompts_no_matches else R.string.coach_prompts_empty_title
                ),
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(
                    if (searching) R.string.coach_prompts_no_matches_subtitle
                    else R.string.coach_prompts_empty_subtitle
                ),
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.65f),
                modifier = Modifier.padding(horizontal = 40.dp)
            )
        }
    }
}
