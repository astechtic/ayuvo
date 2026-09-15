package com.ayuvo.health.ui.health

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ayuvo.health.AppContainer
import com.ayuvo.health.R
import com.ayuvo.health.models.HealthDataType
import com.ayuvo.health.ui.components.GlassPrimaryButton

/** Pick up to [HealthHomeTiles.MAX_TILES] types for the Home strip (persisted in `healthHomeTiles`). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HealthHomeTilesEditor(
    container: AppContainer,
    withData: Set<String>,
    onSave: (List<HealthDataType>) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val raw by container.prefs.healthHomeTiles.collectAsState(initial = null)
    var selected by remember(raw) { mutableStateOf(HealthHomeTiles.parse(raw)) }
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val candidates = remember(withData) {
        HealthHomeTiles.candidates(withData).sortedWith(compareByDescending<HealthDataType> { it.id in withData }.thenBy { it.ordinal })
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = state,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        containerColor = if (isDark) Color(0xF2141416) else Color(0xFFFAF3EE)
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.health_hub_edit_tiles_title), fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Text(
                stringResource(R.string.health_hub_edit_tiles_body, HealthHomeTiles.MAX_TILES),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.66f)
            )
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                items(candidates, key = { it.id }) { type ->
                    val checked = type in selected
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .clickable {
                                selected = if (checked) selected - type
                                else if (selected.size < HealthHomeTiles.MAX_TILES) selected + type
                                else selected
                            }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(checked = checked, onCheckedChange = null)
                        Spacer(Modifier.padding(4.dp))
                        Text(HealthCategoryStyle.typeName(context, type.id), modifier = Modifier.weight(1f), fontSize = 15.sp)
                        if (type.id !in withData) {
                            Text(stringResource(R.string.health_hub_no_data_title), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                        }
                    }
                }
            }
            GlassPrimaryButton(text = stringResource(R.string.action_done), onClick = { onSave(selected) })
        }
    }
}
