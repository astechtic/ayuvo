package com.ayuvo.health.widget

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.getAppWidgetState
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.lifecycle.lifecycleScope
import com.ayuvo.health.AyuvoApp
import com.ayuvo.health.R
import com.ayuvo.health.data.metrics.MetricKey
import com.ayuvo.health.ui.metrics.MetricCatalog
import com.ayuvo.health.ui.theme.AppThemeColor
import com.ayuvo.health.ui.theme.AyuvoTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Configure screen for Quick Log and My Metrics (docs/widgets.md): four slots, tap a slot then an
 * option. Launched on placement (API < 31) and from "Reconfigure"; on API 31+ the widget can be
 * placed with the default slots (`configuration_optional`). Slots live in the widget's own Glance
 * state, so every widget instance keeps its own choice.
 */
class WidgetConfigureActivity : ComponentActivity() {

    /** One pickable option, shared by both widget kinds. */
    private data class Option(val id: String, val label: String, val iconRes: Int, val tint: Long, val section: String)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val appWidgetId = intent?.extras?.getInt(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            ?: AppWidgetManager.INVALID_APPWIDGET_ID
        // Backing out before saving leaves the widget unplaced on launchers that require configuration.
        setResult(RESULT_CANCELED, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId))
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }
        val provider = AppWidgetManager.getInstance(this).getAppWidgetInfo(appWidgetId)?.provider?.className
        val metrics = provider == MyMetricsWidgetReceiver::class.java.name
        val options = if (metrics) metricOptions() else actionOptions()
        val defaults = if (metrics) WidgetMetric.Defaults.map { it.key } else QuickLogAction.Defaults.map { it.id }
        val container = (application as AyuvoApp).container

        lifecycleScope.launch {
            val glanceId = runCatching { GlanceAppWidgetManager(this@WidgetConfigureActivity).getGlanceIdBy(appWidgetId) }.getOrNull()
            val state = glanceId?.let {
                runCatching { getAppWidgetState(this@WidgetConfigureActivity, PreferencesGlanceStateDefinition, it) }.getOrNull()
            }
            val stored = (0 until WidgetSlotKeys.SLOT_COUNT).map { i ->
                state?.get(if (metrics) WidgetSlotKeys.metric(i) else WidgetSlotKeys.action(i))
            }
            val initial = if (metrics) WidgetMetric.slots(stored).map { it.key } else QuickLogAction.slots(stored).map { it.id }
            val themeKey = container.prefs.appThemeColor.first()
            val appearance = container.prefs.appearanceMode.first()

            setContent {
                val dark = when (appearance) {
                    "light" -> false
                    "dark" -> true
                    else -> isSystemInDarkTheme()
                }
                AyuvoTheme(darkTheme = dark, themeColor = AppThemeColor.fromKey(themeKey)) {
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                        ConfigureScreen(
                            title = stringResource(if (metrics) R.string.widget_config_title_metrics else R.string.widget_config_title_quick_log),
                            options = options,
                            initial = initial,
                            defaults = defaults,
                            onSave = { slots -> save(appWidgetId, metrics, slots) }
                        )
                    }
                }
            }
        }
    }

    private fun save(appWidgetId: Int, metrics: Boolean, slots: List<String>) {
        lifecycleScope.launch {
            val manager = GlanceAppWidgetManager(this@WidgetConfigureActivity)
            val glanceId = runCatching { manager.getGlanceIdBy(appWidgetId) }.getOrNull()
            if (glanceId != null) {
                updateAppWidgetState(this@WidgetConfigureActivity, glanceId) { prefs ->
                    slots.forEachIndexed { i, value ->
                        prefs[if (metrics) WidgetSlotKeys.metric(i) else WidgetSlotKeys.action(i)] = value
                    }
                }
                if (metrics) MyMetricsAppWidget().update(this@WidgetConfigureActivity, glanceId)
                else QuickLogAppWidget().update(this@WidgetConfigureActivity, glanceId)
            }
            setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId))
            finish()
        }
    }

    private fun actionOptions(): List<Option> = QuickLogAction.entries.map {
        Option(it.id, getString(it.labelRes), it.iconRes, it.tint, getString(it.group.titleRes))
    }

    private fun metricOptions(): List<Option> = WidgetMetric.entries.map { metric ->
        val label = if (metric == WidgetMetric.NEXT_DOSE) getString(R.string.widget_metric_next_dose)
        else MetricKey.parse(metric.key)?.let { MetricCatalog.title(this, it) } ?: metric.key
        val section = when (metric) {
            WidgetMetric.CALORIES, WidgetMetric.PROTEIN, WidgetMetric.CARBS, WidgetMetric.FAT, WidgetMetric.FIBER,
            WidgetMetric.WATER, WidgetMetric.FASTING -> R.string.widget_group_metrics_nutrition
            WidgetMetric.WEIGHT, WidgetMetric.BODY_FAT -> R.string.widget_group_metrics_body
            WidgetMetric.NEXT_DOSE -> R.string.widget_group_metrics_more
            else -> R.string.widget_group_metrics_activity
        }
        Option(metric.key, label, metric.iconRes, metric.tint, getString(section))
    }

    @Composable
    private fun ConfigureScreen(
        title: String,
        options: List<Option>,
        initial: List<String>,
        defaults: List<String>,
        onSave: (List<String>) -> Unit
    ) {
        var slots by remember { mutableStateOf(initial) }
        var selected by remember { mutableIntStateOf(0) }
        val byId = remember(options) { options.associateBy { it.id } }
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                Text(title, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                Text(stringResource(R.string.widget_config_subtitle), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f), fontSize = 14.sp)
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    slots.forEachIndexed { index, id ->
                        val option = byId[id]
                        val isSelected = index == selected
                        Column(
                            Modifier
                                .weight(1f)
                                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(14.dp))
                                .border(
                                    BorderStroke(if (isSelected) 2.dp else 0.dp, if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent),
                                    RoundedCornerShape(14.dp)
                                )
                                .clickable { selected = index }
                                .padding(vertical = 10.dp, horizontal = 4.dp)
                                .testTag("widget.config.slot.$index"),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(stringResource(R.string.widget_config_slot, index + 1), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                            Spacer(Modifier.height(6.dp))
                            OptionIcon(option)
                            Spacer(Modifier.height(6.dp))
                            Text(option?.label.orEmpty(), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
            LazyColumn(
                Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                options.groupBy { it.section }.forEach { (section, list) ->
                    item(key = "h-$section") {
                        Text(
                            section.uppercase(),
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                            modifier = Modifier.padding(start = 4.dp, top = 14.dp, bottom = 4.dp)
                        )
                    }
                    items(list, key = { it.id }) { option ->
                        val chosen = slots[selected] == option.id
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp))
                                .clickable {
                                    slots = slots.toMutableList().also { it[selected] = option.id }
                                    if (selected < slots.lastIndex) selected += 1
                                }
                                .padding(horizontal = 12.dp, vertical = 10.dp)
                                .testTag("widget.config.option.${option.id}"),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OptionIcon(option)
                            Spacer(Modifier.size(12.dp))
                            Text(option.label, modifier = Modifier.weight(1f), fontSize = 16.sp)
                            if (chosen) Icon(Icons.Filled.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = { slots = defaults; selected = 0 }) { Text(stringResource(R.string.widget_config_reset)) }
                Spacer(Modifier.weight(1f))
                Button(onClick = { onSave(slots) }, modifier = Modifier.testTag("widget.config.save")) {
                    Text(stringResource(R.string.widget_config_save))
                }
            }
        }
    }

    @Composable
    private fun OptionIcon(option: Option?) {
        val tint = Color(option?.tint ?: 0xFF8E8E93)
        Box(
            Modifier.size(32.dp).background(tint.copy(alpha = 0.18f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            if (option != null) Icon(painterResource(option.iconRes), contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
        }
    }
}
