package com.ayuvo.health.ui.browse

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddCircle
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.ayuvo.health.AppContainer
import com.ayuvo.health.R
import com.ayuvo.health.data.metrics.AppMetricId
import com.ayuvo.health.data.metrics.CatalogDomain
import com.ayuvo.health.data.metrics.MetricCatalogData
import com.ayuvo.health.data.metrics.MetricKey
import com.ayuvo.health.services.health.HealthSyncPhase
import com.ayuvo.health.ui.design.AyuvoCategory
import com.ayuvo.health.ui.design.GroupRow
import com.ayuvo.health.ui.design.MetricRow
import com.ayuvo.health.ui.design.RowTrailing
import com.ayuvo.health.ui.health.FormattedHealthValue
import com.ayuvo.health.ui.health.HealthCategoryStyle
import com.ayuvo.health.ui.health.HealthHubUiState
import com.ayuvo.health.ui.health.HealthHubViewModel
import com.ayuvo.health.ui.health.HealthTileUi
import com.ayuvo.health.ui.health.HealthTypeRowUi
import com.ayuvo.health.ui.health.HealthUnitPrefs
import com.ayuvo.health.ui.health.HealthValueFormatter
import com.ayuvo.health.ui.health.formatDayKey
import com.ayuvo.health.ui.health.relativeTimeText
import com.ayuvo.health.ui.metrics.MetricCatalog
import com.ayuvo.health.ui.metrics.MetricIcons
import com.ayuvo.health.ui.metrics.MetricTileBuilder
import com.ayuvo.health.ui.theme.AppColors
import java.time.ZoneId

/** Browse domain title (catalog `title_res` = `domain_<id>`). */
@StringRes
internal fun domainTitleRes(id: String): Int =
    AyuvoCategory.entries.firstOrNull { it.name.equals(id, ignoreCase = true) }?.labelRes ?: R.string.domain_other

internal fun domainIcon(domain: CatalogDomain) = MetricIcons.byNameOrDefault(domain.iconAndroid)

/** One Health Connect type: domain-coloured icon (metric → override → domain icon), latest value, caption. */
@Composable
internal fun HealthMetricRow(
    row: HealthTypeRowUi,
    unitPrefs: HealthUnitPrefs,
    catalog: MetricCatalogData,
    onClick: () -> Unit,
    dimmed: Boolean = false
) {
    val context = LocalContext.current
    val key = MetricKey.Health(row.typeId)
    val value = healthRowValue(row, unitPrefs)
    MetricRow(
        title = HealthCategoryStyle.typeName(context, row.typeId, row.displayNameHint),
        value = value?.number,
        unit = value?.unit,
        caption = healthRowCaption(row),
        icon = MetricCatalog.icon(catalog, key),
        tint = MetricCatalog.color(catalog, key),
        dimmed = dimmed,
        modifier = Modifier.testTag("browse.metric.${row.typeId}"),
        onClick = onClick
    )
}

internal fun healthRowValue(row: HealthTypeRowUi, unitPrefs: HealthUnitPrefs): FormattedHealthValue? {
    val latest = row.latest
    val dayValue = row.dayValue
    return when {
        dayValue != null -> HealthValueFormatter.format(row.typeId, dayValue, unitPrefs)
        latest == null -> null
        row.typeId == "blood_pressure" -> HealthValueFormatter.formatBloodPressure(latest.value, latest.value2)
        row.type?.kind?.name == "CATEGORY" -> FormattedHealthValue(HealthValueFormatter.categoryLabel(row.typeId, latest.categoryValue), "")
        else -> HealthValueFormatter.format(row.typeId, latest.value, unitPrefs, unitOverride = latest.unit.takeIf { row.type == null })
    }
}

@Composable
private fun healthRowCaption(row: HealthTypeRowUi): String {
    val latest = row.latest
    val dayValue = row.dayValue
    return when {
        row.featureGated -> stringResource(R.string.health_hub_requires_android_14)
        dayValue != null && row.dayIsToday -> stringResource(R.string.health_home_today)
        dayValue != null && row.dayKey != null -> formatDayKey(row.dayKey)
        latest != null -> relativeTimeText(latest.endMs)
        !row.granted && row.count == 0L -> stringResource(R.string.health_home_grant_access)
        else -> stringResource(R.string.health_hub_no_data_title)
    }
}

/** An app metric row: today's total (summed metrics) or the latest reading, "No Data" otherwise. */
@Composable
internal fun AppMetricRow(
    id: AppMetricId,
    state: BrowseAppState,
    catalog: MetricCatalogData,
    onClick: () -> Unit
) {
    val key = MetricKey.App(id)
    val tile = MetricTileBuilder.appTile(id, state.snapshot, System.currentTimeMillis(), ZoneId.systemDefault(), state.units)
    MetricRow(
        title = stringResource(MetricCatalog.titleRes(id)),
        value = tile.number.takeIf { tile.hasData },
        unit = tile.unit.takeIf { tile.hasData },
        caption = tileCaption(tile),
        icon = MetricCatalog.icon(catalog, key),
        tint = MetricCatalog.color(catalog, key),
        modifier = Modifier.testTag("browse.metric.${key.storageId}"),
        onClick = onClick
    )
}

@Composable
internal fun tileCaption(tile: HealthTileUi): String = when {
    !tile.hasData -> stringResource(R.string.browse_no_data)
    tile.captionKind == HealthTileUi.CaptionKind.TODAY -> stringResource(R.string.health_home_today)
    tile.captionKind == HealthTileUi.CaptionKind.LAST_NIGHT -> stringResource(R.string.health_home_last_night)
    tile.captionKind == HealthTileUi.CaptionKind.RELATIVE || tile.captionKind == HealthTileUi.CaptionKind.DATE ->
        tile.captionMs?.let { relativeTimeText(it) } ?: ""
    else -> ""
}

/** "Allow <Category> access" row shown while read permissions for a category are missing. */
@Composable
internal fun AllowAccessRow(categoryName: String, onClick: () -> Unit) {
    GroupRow(
        title = stringResource(R.string.health_hub_allow_category, categoryName),
        leading = { Icon(Icons.Outlined.AddCircle, contentDescription = null, tint = AppColors.Calorie) },
        trailing = RowTrailing.None,
        onClick = onClick
    )
}

/** Live sync status line for the Browse footer and the Settings health-sync row. */
@Composable
internal fun healthSyncStatusText(ui: HealthHubUiState): String {
    val status = ui.syncStatus
    return when {
        !ui.hubEnabled -> stringResource(R.string.browse_sync_off)
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
    }
}

/** Health Connect permission launcher wired to [vm]; returns the launch function. */
@Composable
internal fun rememberHealthPermissionLauncher(container: AppContainer, vm: HealthHubViewModel): (Set<String>) -> Unit {
    val launcher = rememberLauncherForActivityResult(container.health.permissionRequestContract()) { granted ->
        vm.onPermissionResult(granted)
    }
    return { permissions -> runCatching { launcher.launch(permissions) } }
}

/** Opens Health Connect's own access screen, or tells the user it is unavailable. */
@Composable
internal fun rememberManageAccess(container: AppContainer): () -> Unit {
    val context = LocalContext.current
    val unavailable = stringResource(R.string.health_hub_unavailable_title)
    return { if (!container.health.openManageAccess(context)) Toast.makeText(context, unavailable, Toast.LENGTH_SHORT).show() }
}

/** Runs the Browse sync loop only while this screen is resumed (docs plan §4.3). */
@Composable
internal fun HubVisibilityEffect(vm: HealthHubViewModel) {
    val lifecycleOwner = LocalLifecycleOwner.current
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
}
