package com.ayuvo.health.widget

import com.ayuvo.health.data.metrics.AppMetricId
import com.ayuvo.health.models.HealthDataType
import com.ayuvo.health.models.LogEntryIntents
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.io.File

/** `shared/widgets/widget_options.json` is the contract; Android's enums must match it exactly. */
class WidgetOptionsContractTest {
    private val root: JsonObject by lazy {
        val file = listOf("../../shared/widgets/widget_options.json", "../shared/widgets/widget_options.json", "shared/widgets/widget_options.json")
            .map(::File).firstOrNull { it.exists() }
        assertNotNull("shared/widgets/widget_options.json not found", file)
        Json.parseToJsonElement(file!!.readText()).jsonObject
    }

    private fun JsonObject.str(key: String): String? = this[key]?.let { if (it is JsonNull) null else it.jsonPrimitive.content }

    @Test
    fun envelope() {
        assertEquals("ayuvo-widget-options", root.str("format"))
        assertEquals("1", root.str("version"))
    }

    @Test
    fun quickLogActionsMatchInOrder() {
        val quickLog = root.getValue("quick_log").jsonObject
        assertEquals(WidgetSlotKeys.SLOT_COUNT.toString(), quickLog.str("slots"))
        val actions = quickLog.getValue("actions").jsonArray.map { it.jsonObject }
        assertEquals(actions.map { it.str("id") }, QuickLogAction.entries.map { it.id })
        actions.forEach { a ->
            val action = QuickLogAction.fromId(a.str("id"))!!
            assertEquals(action.id, a.str("group"), action.group.name.lowercase())
            assertEquals(action.id, a.str("food_method"), action.foodMethod?.storageKey)
            val requires = when (action.requires) {
                WidgetRequirement.WATER_TRACKING -> "water_tracking"
                WidgetRequirement.FASTING_TRACKING -> "fasting_tracking"
                null -> null
            }
            assertEquals(action.id, a.str("requires"), requires)
            // Every non-food action runs a Summary "+" entry.
            if (!action.isFood) assertNotNull(action.id, action.logEntry)
        }
        assertEquals(quickLog.getValue("defaults").jsonArray.map { it.jsonPrimitive.content }, QuickLogAction.Defaults.map { it.id })
    }

    @Test
    fun myMetricsMatchInOrderAndResolve() {
        val metrics = root.getValue("my_metrics").jsonObject
        assertEquals(WidgetSlotKeys.SLOT_COUNT.toString(), metrics.str("slots"))
        val list = metrics.getValue("metrics").jsonArray.map { it.jsonObject }
        assertEquals(list.map { it.str("key") }, WidgetMetric.entries.map { it.key })
        list.forEach { m ->
            val metric = WidgetMetric.fromKey(m.str("key"))!!
            assertEquals(metric.key, m.str("tap"), metric.tap.name.lowercase())
        }
        assertEquals(metrics.getValue("defaults").jsonArray.map { it.jsonPrimitive.content }, WidgetMetric.Defaults.map { it.key })
        // Catalog keys resolve to a real app metric or health registry id.
        WidgetMetric.entries.filter { it != WidgetMetric.NEXT_DOSE }.forEach { metric ->
            val resolved = if (metric.key.startsWith("app:")) AppMetricId.bySlug(metric.key.removePrefix("app:")) else HealthDataType.byId(metric.key)
            assertNotNull(metric.key, resolved)
        }
    }

    @Test
    fun androidDeepLinksMatch() {
        val android = root.getValue("deep_links").jsonObject.getValue("android").jsonObject
        assertEquals(LogEntryIntents.ACTION_LOG, android.str("log_action"))
        assertEquals(LogEntryIntents.EXTRA_LOG, android.str("log_extra"))
        assertEquals(LogEntryIntents.ACTION_METRIC, android.str("metric_action"))
        assertEquals(LogEntryIntents.EXTRA_METRIC, android.str("metric_extra"))
        assertEquals(LogEntryIntents.ACTION_SUMMARY, android.str("summary_action"))
    }
}
