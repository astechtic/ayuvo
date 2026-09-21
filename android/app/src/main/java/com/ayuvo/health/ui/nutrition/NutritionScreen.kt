package com.ayuvo.health.ui.nutrition

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.ViewModelStoreOwner
import com.ayuvo.health.AppContainer
import com.ayuvo.health.R
import com.ayuvo.health.data.metrics.AppMetricId
import com.ayuvo.health.data.metrics.MetricKey
import com.ayuvo.health.models.QuickActionRequest
import com.ayuvo.health.ui.design.AyuvoPalette
import com.ayuvo.health.ui.design.AyuvoSpacing
import com.ayuvo.health.ui.design.AyuvoTopBar
import com.ayuvo.health.ui.design.GroupRow
import com.ayuvo.health.ui.design.InsetGroup
import com.ayuvo.health.ui.home.FoodLogRequest
import com.ayuvo.health.ui.home.FoodTabScreen
import com.ayuvo.health.ui.metrics.MetricCatalog

/** App metrics linked from the Nutrition "Trends" group, in catalog order. */
private val TREND_METRICS = listOf(AppMetricId.CALORIES, AppMetricId.PROTEIN, AppMetricId.CARBS, AppMetricId.FAT, AppMetricId.FIBER)

/**
 * Browse › Nutrition (docs/ui-structure.md §2): the food diary plus a Trends group linking to the
 * nutrition metric details. The diary's view model is scoped to the Activity, so a back press
 * or a tab switch never cancels an AI analysis that is still running.
 */
@Composable
fun NutritionScreen(
    container: AppContainer,
    onBack: () -> Unit,
    onOpenMetric: (MetricKey) -> Unit,
    onOpenNutrients: () -> Unit,
    quickActionRequest: QuickActionRequest? = null,
    onQuickActionHandled: (Long) -> Unit = {},
    logRequest: FoodLogRequest? = null,
    onLogRequestHandled: (Long) -> Unit = {}
) {
    val context = LocalContext.current
    val catalog = container.metricCatalog
    val owner = context.findViewModelStoreOwner()
    FoodTabScreen(
        container = container,
        quickActionRequest = quickActionRequest,
        onQuickActionHandled = onQuickActionHandled,
        logRequest = logRequest,
        onLogRequestHandled = onLogRequestHandled,
        viewModelOwner = owner,
        topBar = { AyuvoTopBar(title = stringResource(R.string.domain_nutrition), onBack = onBack) },
        footer = {
            item(key = "nutrition-trends") {
                Box(Modifier.padding(start = AyuvoSpacing.ScreenH, end = AyuvoSpacing.ScreenH, top = AyuvoSpacing.SectionGap)) {
                    InsetGroup(header = stringResource(R.string.nutrition_trends)) {
                        TREND_METRICS.forEach { id ->
                            row {
                                val key = MetricKey.App(id)
                                GroupRow(
                                    title = stringResource(MetricCatalog.titleRes(id)),
                                    icon = MetricCatalog.icon(catalog, key),
                                    iconTint = MetricCatalog.color(catalog, key),
                                    modifier = Modifier.testTag("browse.metric.${key.storageId}"),
                                    onClick = { onOpenMetric(key) }
                                )
                            }
                        }
                        row {
                            GroupRow(
                                title = stringResource(R.string.nutrition_all_nutrients),
                                icon = Icons.AutoMirrored.Filled.List,
                                iconTint = AyuvoPalette.Nutrition,
                                onClick = onOpenNutrients
                            )
                        }
                    }
                }
            }
        }
    )
}

/** The hosting Activity when it owns view models (the app's MainActivity always does). */
internal fun Context.findViewModelStoreOwner(): ViewModelStoreOwner? {
    var ctx: Context? = this
    while (ctx != null) {
        if (ctx is Activity) return ctx as? ViewModelStoreOwner
        ctx = (ctx as? ContextWrapper)?.baseContext
    }
    return null
}
