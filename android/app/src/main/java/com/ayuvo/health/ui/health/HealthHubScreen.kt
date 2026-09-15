package com.ayuvo.health.ui.health

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.AddCircle
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ayuvo.health.AppContainer
import com.ayuvo.health.R
import com.ayuvo.health.models.HealthCategory
import com.ayuvo.health.services.health.HealthConnectAvailability
import com.ayuvo.health.services.health.HealthSyncPhase
import com.ayuvo.health.ui.components.GlassDialog
import com.ayuvo.health.ui.components.GlassDialogActions
import com.ayuvo.health.ui.components.GlassSurface
import com.ayuvo.health.ui.components.IconBubble
import com.ayuvo.health.ui.navigation.BottomNavScrollPadding
import com.ayuvo.health.ui.theme.AppColors
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * Health Data hub — Apple Health "Browse" layout over the local mirror: category cards with
 * the latest value per type, per-category grant rows, pull-to-refresh, live sync subtitle.
 * The hub owns its own permission launcher; Settings' pendingHealthPermissionAction is untouched.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HealthHubScreen(
    container: AppContainer,
    onOpenType: (String) -> Unit
) {
    val vm: HealthHubViewModel = viewModel(factory = HealthHubViewModel.Factory(container))
    val ui by vm.ui.collectAsState()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var menuOpen by remember { mutableStateOf(false) }
    var showClearConfirm by remember { mutableStateOf(false) }
    var showTilesEditor by remember { mutableStateOf(false) }
    var showExportSheet by remember { mutableStateOf(false) }
    var importUri by remember { mutableStateOf<android.net.Uri?>(null) }
    val importLauncher = rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importUri = uri
    }
    val onExport = { showExportSheet = true }
    val onImport = { runCatching { importLauncher.launch(arrayOf("application/zip", "application/octet-stream")) }; Unit }

    DisposableEffect(lifecycleOwner, vm) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> vm.setVisible(true)
                Lifecycle.Event.ON_PAUSE -> vm.setVisible(false)
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) vm.setVisible(true)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            vm.setVisible(false)
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(container.health.permissionRequestContract()) { granted ->
        vm.onPermissionResult(granted)
    }
    fun launch(permissions: Set<String>) {
        runCatching { permissionLauncher.launch(permissions) }
    }
    val manageUnavailable = stringResource(R.string.health_hub_unavailable_title)
    fun openManageAccess() {
        if (!container.health.openManageAccess(context)) Toast.makeText(context, manageUnavailable, Toast.LENGTH_SHORT).show()
    }

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        PullToRefreshBox(
            isRefreshing = ui.refreshing,
            onRefresh = vm::refresh,
            modifier = Modifier.fillMaxSize().padding(padding)
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                contentPadding = PaddingValues(top = 9.dp, bottom = BottomNavScrollPadding),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                item {
                    // Title row: menu shares the row with the title, its 48 dp target centred on the
                    // title's first line (7 dp top inset = (48 - 34) / 2). The row keeps the 48 dp
                    // minimum without the menu so the connect state doesn't shift.
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.Top) {
                        Column(Modifier.weight(1f).padding(top = 7.dp)) {
                            Text(
                                stringResource(R.string.health_hub_title),
                                fontSize = 28.sp,
                                lineHeight = 34.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onBackground
                            )
                            Spacer(Modifier.height(4.dp))
                            HealthSyncSubtitle(ui)
                            // Subtitle -> first card = 14 dp list spacing + 2 dp.
                            Spacer(Modifier.height(2.dp))
                        }
                        if (ui.hubEnabled) {
                            Box {
                                IconButton(onClick = { menuOpen = true }) {
                                    Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.health_hub_more))
                                }
                                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(if (ui.showAllTypes) R.string.health_hub_hide_empty_types else R.string.health_hub_show_all_types)) },
                                        onClick = { menuOpen = false; vm.toggleShowAllTypes() }
                                    )
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.health_hub_storage_used, HealthValueFormatter.bytes(ui.storageBytes))) },
                                        onClick = { menuOpen = false },
                                        enabled = false
                                    )
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.health_hub_menu_edit_tiles)) },
                                        onClick = { menuOpen = false; showTilesEditor = true }
                                    )
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.health_hub_menu_export)) },
                                        onClick = { menuOpen = false; onExport() }
                                    )
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.health_hub_menu_import)) },
                                        onClick = { menuOpen = false; onImport() }
                                    )
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.health_hub_menu_manage_access)) },
                                        onClick = { menuOpen = false; openManageAccess() }
                                    )
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.health_settings_clear), color = MaterialTheme.colorScheme.error) },
                                        onClick = { menuOpen = false; showClearConfirm = true }
                                    )
                                }
                            }
                        }
                    }
                }

                when {
                    ui.loading -> Unit
                    ui.availability != HealthConnectAvailability.AVAILABLE -> item {
                        HealthUnavailableCard(ui.availability) { container.health.openPlayStore() }
                    }
                    !ui.hubEnabled -> item {
                        HealthConnectCard(
                            consentChecked = ui.consentChecked,
                            onConsentChange = vm::setConsentChecked,
                            onConnect = { launch(vm.connectPermissions()) },
                            onManageAccess = ::openManageAccess
                        )
                    }
                    else -> {
                        if (ui.permissionsReset) {
                            item {
                                HealthNoticeCard(
                                    message = stringResource(R.string.health_hub_subtitle_permissions_reset),
                                    actionText = stringResource(R.string.health_hub_regrant),
                                    onAction = { launch(vm.allPermissions()) }
                                )
                            }
                        }
                        ui.syncStatus.historyLimitedBeforeMs?.let { floor ->
                            if (!ui.historyGranted && ui.historySupported) {
                                item {
                                    HealthNoticeCard(
                                        message = stringResource(R.string.health_hub_subtitle_limited, formatDate(floor)),
                                        actionText = stringResource(R.string.health_hub_grant_history),
                                        onAction = { launch(vm.historyPermissions()) }
                                    )
                                }
                            }
                        }
                        if (!ui.hasAnyData && ui.categories.all { it.rows.all { r -> r.count == 0L } }) {
                            item { HealthNoDataCard() }
                        }
                        for (category in ui.categories) {
                            item(key = "cat-${category.category.id}") {
                                HealthCategoryCard(
                                    category = category,
                                    unitPrefs = ui.unitPrefs,
                                    onOpenType = onOpenType,
                                    onAllow = { launch(vm.categoryPermissions(category.category)) }
                                )
                            }
                        }
                        item { HealthReadOnlyFooter() }
                    }
                }
            }
        }
    }

    if (showClearConfirm) {
        GlassDialog(onDismissRequest = { showClearConfirm = false }) {
            Text(stringResource(R.string.health_settings_clear_title), fontSize = 21.sp, fontWeight = FontWeight.Bold)
            Text(stringResource(R.string.health_settings_clear_message), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.68f))
            GlassDialogActions(
                primaryText = stringResource(R.string.action_clear),
                onPrimary = { vm.clearSyncedData(); showClearConfirm = false },
                dismissText = stringResource(R.string.action_cancel),
                onDismiss = { showClearConfirm = false },
                destructive = true
            )
        }
    }

    if (showExportSheet) {
        com.ayuvo.health.ui.settings.ExportHealthDataSheet(container = container, onDismiss = { showExportSheet = false })
    }
    importUri?.let { uri ->
        com.ayuvo.health.ui.settings.ImportHealthDataSheet(container = container, uri = uri, onDismiss = { importUri = null })
    }

    if (showTilesEditor) {
        HealthHomeTilesEditor(
            container = container,
            withData = ui.categories.flatMap { it.rows }.filter { it.count > 0 }.map { it.typeId }.toSet(),
            onSave = { vm.setHomeTiles(it); showTilesEditor = false },
            onDismiss = { showTilesEditor = false }
        )
    }
}

/** Live-region subtitle: last synced / importing progress / busy / syncing. */
@Composable
private fun HealthSyncSubtitle(ui: HealthHubUiState) {
    val status = ui.syncStatus
    val text = when {
        !ui.hubEnabled -> null
        status.phase == HealthSyncPhase.RATE_LIMITED -> stringResource(R.string.health_hub_subtitle_busy)
        status.phase == HealthSyncPhase.IMPORTING_HISTORY && status.progress != null ->
            stringResource(
                R.string.health_hub_subtitle_importing,
                status.progressFromYear ?: 0, status.progressToYear ?: 0, (status.progress * 100).toInt()
            )
        status.running || ui.refreshing -> stringResource(R.string.health_hub_subtitle_syncing)
        status.lastSyncMs == null -> stringResource(R.string.health_hub_subtitle_never)
        else -> stringResource(
            R.string.health_hub_subtitle_last_synced,
            relativeTimeText(status.lastSyncMs),
            pluralStringResource(R.plurals.health_types_count, status.typeCount, status.typeCount)
        )
    } ?: return
    Column(Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f))
        if (status.phase == HealthSyncPhase.IMPORTING_HISTORY && status.progress != null) {
            Spacer(Modifier.height(6.dp))
            LinearProgressIndicator(
                progress = { status.progress },
                modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                color = AppColors.Calorie
            )
        }
    }
}

@Composable
private fun HealthCategoryCard(
    category: HealthCategoryUi,
    unitPrefs: HealthUnitPrefs,
    onOpenType: (String) -> Unit,
    onAllow: () -> Unit
) {
    val context = LocalContext.current
    val tint = HealthCategoryStyle.tint(category.category)
    GlassSurface(modifier = Modifier.fillMaxWidth(), cornerRadius = 22.dp, padding = 0.dp) {
        Column {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                IconBubble(icon = HealthCategoryStyle.icon(category.category), size = 28.dp, iconSize = 18.dp, tint = tint)
                Spacer(Modifier.width(12.dp))
                Text(HealthCategoryStyle.categoryName(context, category.category), fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = tint)
            }
            category.rows.forEachIndexed { index, row ->
                if (index > 0) HorizontalDivider(Modifier.padding(start = 16.dp), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                HealthTypeRow(row = row, unitPrefs = unitPrefs, onClick = { onOpenType(row.typeId) })
            }
            if (category.missingPermissions.isNotEmpty()) {
                HorizontalDivider(Modifier.padding(start = 16.dp), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .clickable(onClick = onAllow)
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Outlined.AddCircle, null, tint = AppColors.Calorie, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(
                        stringResource(R.string.health_hub_allow_category, HealthCategoryStyle.categoryName(context, category.category)),
                        color = AppColors.Calorie, fontWeight = FontWeight.Medium, fontSize = 15.sp
                    )
                }
            }
            if (category.hiddenEmptyCount > 0 && category.rows.isNotEmpty()) {
                Text(
                    stringResource(R.string.health_hub_rows_hidden, category.hiddenEmptyCount),
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 10.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                )
            } else {
                Spacer(Modifier.height(4.dp))
            }
        }
    }
}

/** Latest value + relative time + chevron; ≥48 dp tap target. */
@Composable
fun HealthTypeRow(row: HealthTypeRowUi, unitPrefs: HealthUnitPrefs, onClick: () -> Unit) {
    val context = LocalContext.current
    val name = HealthCategoryStyle.typeName(context, row.typeId, row.displayNameHint)
    val latest = row.latest
    val dayValue = row.dayValue
    val value = when {
        dayValue != null -> HealthValueFormatter.format(row.typeId, dayValue, unitPrefs)
        latest == null -> null
        row.typeId == "blood_pressure" -> HealthValueFormatter.formatBloodPressure(latest.value, latest.value2)
        row.type?.kind?.name == "CATEGORY" -> FormattedHealthValue(HealthValueFormatter.categoryLabel(row.typeId, latest.categoryValue), "")
        else -> HealthValueFormatter.format(row.typeId, latest.value, unitPrefs, unitOverride = latest.unit.takeIf { row.type == null })
    }
    val subtitle = when {
        row.featureGated -> stringResource(R.string.health_hub_requires_android_14)
        dayValue != null && row.dayIsToday -> stringResource(R.string.health_home_today)
        dayValue != null && row.dayKey != null -> formatDayKey(row.dayKey)
        latest != null -> relativeTimeText(latest.endMs)
        !row.granted && row.count == 0L -> stringResource(R.string.health_home_grant_access)
        else -> stringResource(R.string.health_hub_no_data_title)
    }
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(name, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f))
        }
        if (value != null) {
            Text(value.number, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            if (value.unit.isNotEmpty()) {
                Spacer(Modifier.width(3.dp))
                Text(value.unit, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
            }
        }
        Spacer(Modifier.width(6.dp))
        Icon(Icons.Filled.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f), modifier = Modifier.size(18.dp))
    }
}

@Composable
internal fun formatDate(ms: Long): String {
    val fmt = remember { DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM) }
    return Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalDate().format(fmt)
}

/** "yyyy-MM-dd" rollup day → localized medium date; the raw key when unparsable. */
@Composable
internal fun formatDayKey(dayKey: String): String {
    val fmt = remember { DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM) }
    return runCatching { LocalDate.parse(dayKey).format(fmt) }.getOrDefault(dayKey)
}
