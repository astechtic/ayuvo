package com.ayuvo.health.ui.summary

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ayuvo.health.R
import com.ayuvo.health.data.metrics.AppMetricId
import com.ayuvo.health.data.metrics.MetricCatalogData
import com.ayuvo.health.data.metrics.MetricKey
import com.ayuvo.health.ui.design.AyuvoColors
import com.ayuvo.health.ui.design.AyuvoShapes
import com.ayuvo.health.ui.design.CategoryIcon
import com.ayuvo.health.ui.health.HealthHomeTiles
import com.ayuvo.health.ui.metrics.MetricCatalog
import com.ayuvo.health.ui.theme.AppColors

/**
 * Summary › Favourites › Edit: app metrics first, then readable Health Connect types. The order
 * of the checked keys is kept (new picks go last); at most [max] keys (catalog `favourites.max`).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FavoritesEditorSheet(
    catalog: MetricCatalogData,
    current: List<MetricKey>,
    onSave: (List<MetricKey>) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val max = catalog.favouritesMax
    var selected by remember(current) { mutableStateOf(current.map { it.storageId }) }
    val candidates = remember {
        AppMetricId.entries.map { MetricKey.App(it) as MetricKey } +
            HealthHomeTiles.candidates(emptySet()).map { MetricKey.Health(it.id) }
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = AyuvoShapes.Sheet,
        containerColor = AyuvoColors.sheetBackground()
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.summary_edit_favourites), fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                TextButton(onClick = { onSave(selected.mapNotNull(MetricKey::parse)) }) {
                    Text(stringResource(R.string.action_done), color = AppColors.Calorie, fontWeight = FontWeight.SemiBold)
                }
            }
            Text(
                stringResource(R.string.summary_edit_favourites_body, selected.size, max),
                fontSize = 13.sp,
                color = AyuvoColors.secondaryLabel()
            )
            LazyColumn(Modifier.heightIn(max = 520.dp)) {
                items(candidates, key = { it.storageId }) { key ->
                    val checked = key.storageId in selected
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .clickable {
                                selected = when {
                                    checked -> selected - key.storageId
                                    selected.size < max -> selected + key.storageId
                                    else -> selected
                                }
                            }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CategoryIcon(MetricCatalog.icon(catalog, key), MetricCatalog.color(catalog, key), size = 28.dp)
                        Spacer(Modifier.width(12.dp))
                        Text(MetricCatalog.title(context, key), modifier = Modifier.weight(1f), fontSize = 16.sp)
                        Checkbox(checked = checked, onCheckedChange = null, colors = CheckboxDefaults.colors(checkedColor = AppColors.Calorie))
                    }
                }
            }
            Spacer(Modifier.heightIn(min = 12.dp))
        }
    }
}
