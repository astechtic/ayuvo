package com.ayuvo.health.ui.browse

import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ayuvo.health.R
import com.ayuvo.health.data.metrics.MetricCatalogData
import com.ayuvo.health.data.metrics.MetricKey
import com.ayuvo.health.ui.design.InsetGroup
import com.ayuvo.health.ui.design.MetricRow
import com.ayuvo.health.ui.metrics.MetricCatalog

/** Derived metrics listed on the Browse page of [healthCategory] (energy is listed with activity). */
internal fun derivedRowsFor(rows: List<DerivedBrowseRow>, healthCategory: String): List<DerivedBrowseRow> =
    rows.filter { it.healthCategory == healthCategory }

/**
 * The "Estimated by Ayuvo" group of a Browse page (docs/derived-metrics.md): enabled derived metrics with values,
 * each with its latest value (the Health Connect value on days it has one).
 */
internal fun LazyListScope.derivedMetricGroup(
    rows: List<DerivedBrowseRow>,
    healthCategory: String,
    catalog: MetricCatalogData,
    onOpenMetric: (MetricKey) -> Unit
) {
    val list = derivedRowsFor(rows, healthCategory)
    if (list.isEmpty()) return
    item(key = "derived-$healthCategory") {
        InsetGroup(header = stringResource(R.string.derived_estimated_badge), dividerInset = 60.dp) {
            list.forEach { r -> row { DerivedMetricRow(r, catalog) { onOpenMetric(r.key) } } }
        }
    }
}

@Composable
internal fun DerivedMetricRow(row: DerivedBrowseRow, catalog: MetricCatalogData, caption: String? = null, onClick: () -> Unit) {
    val tile = row.tile
    MetricRow(
        title = row.info.title,
        value = tile.number.takeIf { tile.hasData },
        unit = tile.unit.takeIf { tile.hasData && it.isNotEmpty() },
        caption = caption ?: tileCaption(tile),
        icon = MetricCatalog.icon(catalog, row.key),
        tint = MetricCatalog.color(catalog, row.key),
        modifier = Modifier.testTag("browse.metric.${row.key.storageId}"),
        onClick = onClick
    )
}
