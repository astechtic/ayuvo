package com.ayuvo.health.ui.navigation

import com.ayuvo.health.data.metrics.MetricKey
import java.net.URLEncoder

object AppRoutes {
    const val ONBOARDING = "onboarding"
    /** Summary tab (start destination): rings, today cards, favourites, highlights. */
    const val SUMMARY = "summary"
    /** Browse tab: Apple-Health-style category list (docs/ui-structure.md §2). */
    const val BROWSE = "browse"
    const val BROWSE_NUTRITION = "browse/nutrition"
    const val BROWSE_NUTRIENTS = "browse/nutrition/nutrients"
    const val BROWSE_FASTING = "browse/fasting"
    const val BROWSE_BODY = "browse/body"
    const val BROWSE_MEASUREMENTS = "browse/body/measurements"
    const val BROWSE_ACTIVITY = "browse/activity"
    const val CATEGORY_ID_ARG = "categoryId"
    const val BROWSE_CATEGORY = "browse/category/{$CATEGORY_ID_ARG}"
    /** Health Records tab. Record screens push over it (records/…). */
    const val RECORDS = "records"
    const val COACH = "coach"
    const val SETTINGS = "settings"
    const val OPTIONAL_NUTRIENT_GOALS = "settings/optional-nutrient-goals"
    const val CALCULATION_METHODS = "settings/calculation-methods"
    const val QUICK_ACTIONS = "settings/quick-actions"
    const val ADD_MENU = "settings/add-menu"
    const val BODY_MEASUREMENTS = "settings/body-measurements"
    const val ALLERGEN_SENSITIVITIES = "settings/allergen-sensitivities"
    const val LICENSES = "settings/licenses"
    const val HEALTH_TYPE_ARG = "typeKey"
    const val HEALTH_TYPE = "health/type/{$HEALTH_TYPE_ARG}"
    const val METRIC_KEY_ARG = "metricKey"
    /** Unified metric detail (docs/ui-structure.md §6): `app:` metrics and health types; the key is URL-encoded. */
    const val METRIC = "metric/{$METRIC_KEY_ARG}"
    const val RECORD_ID_ARG = "recordId"
    const val RECORD_DETAIL = "records/detail/{$RECORD_ID_ARG}"
    const val RECORD_SPLIT = "records/split/{$RECORD_ID_ARG}"
    const val RECORD_FOCUS_ARG = "obs"
    /** Record detail, optionally opened at an observation's source (trend point tap, §24). */
    const val RECORD_DETAIL_FOCUS = "records/detail/{$RECORD_ID_ARG}?$RECORD_FOCUS_ARG={$RECORD_FOCUS_ARG}"
    const val ANALYTE_ID_ARG = "analyteId"
    const val RECORD_TREND = "records/trend/{$ANALYTE_ID_ARG}"
    const val RECORD_IDS_ARG = "recordIds"
    /** "What will be shared" for one or more records (docs/health-records.md §34). */
    const val RECORD_SHARE = "records/share/{$RECORD_IDS_ARG}"
    /** Settings › Health Records › Storage (§37) and Backup & restore (§35, §36). */
    const val HEALTH_RECORDS_STORAGE = "settings/health-records/storage"
    const val HEALTH_RECORDS_BACKUP = "settings/health-records/backup"

    // Workouts (log shell and exercise library) and Medications are shared destinations: they keep
    // the tab of the screen that opened them selected (Browse when opened from nowhere).
    const val WORKOUTS_LOG = "workouts/log"
    const val WORKOUTS_LIBRARY = "workouts/library"
    const val MEDICATIONS = "medications"

    const val MEDICATION_ID_ARG = "medicationId"
    /** Add a medication, optionally pre-linked to a Health Record (`recordId`). */
    const val MEDICATION_ADD = "medications/add?$RECORD_ID_ARG={$RECORD_ID_ARG}"
    const val MEDICATION_EDIT = "medications/edit/{$MEDICATION_ID_ARG}"
    const val MEDICATION_DETAIL = "medications/detail/{$MEDICATION_ID_ARG}"
    /** Dose history for one medication, or for all of them when the argument is absent. */
    const val MEDICATION_HISTORY = "medications/history?$MEDICATION_ID_ARG={$MEDICATION_ID_ARG}"
    /** "Add from prescription": candidates extracted from one Health Record (§13). */
    const val MEDICATION_IMPORT = "medications/import/{$RECORD_ID_ARG}"

    fun browseCategory(categoryId: String): String = "browse/category/$categoryId"
    fun healthType(typeKey: String): String = "health/type/$typeKey"
    fun metric(key: MetricKey): String = "metric/" + URLEncoder.encode(key.storageId, "UTF-8").replace("+", "%20")
    fun recordDetail(recordId: String): String = "records/detail/$recordId"
    fun recordSplit(recordId: String): String = "records/split/$recordId"
    fun recordTrend(analyteId: String): String = "records/trend/$analyteId"
    fun recordDetailAt(recordId: String, observationId: String): String = "records/detail/$recordId?$RECORD_FOCUS_ARG=$observationId"

    /** Record ids are UUIDs, so a comma-joined path segment needs no escaping. */
    fun recordShare(recordIds: List<String>): String = "records/share/" + recordIds.joinToString(",")

    fun parseRecordIds(argument: String?): List<String> =
        argument?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()

    fun medicationAdd(recordId: String? = null): String =
        if (recordId == null) "medications/add" else "medications/add?$RECORD_ID_ARG=$recordId"
    fun medicationEdit(medicationId: String): String = "medications/edit/$medicationId"
    fun medicationDetail(medicationId: String): String = "medications/detail/$medicationId"
    fun medicationHistory(medicationId: String? = null): String =
        if (medicationId == null) "medications/history" else "medications/history?$MEDICATION_ID_ARG=$medicationId"
    fun medicationImport(recordId: String): String = "medications/import/$recordId"

    val bottomTabs = listOf(SUMMARY, BROWSE, RECORDS, COACH, SETTINGS)

    /** Destinations reachable from several tabs: metric/health details, workouts, medications. */
    fun isSharedRoute(route: String?): Boolean = route != null && (
        route.startsWith("metric/") || route.startsWith("health/") ||
            route == MEDICATIONS || route.startsWith("medications/") ||
            route.startsWith("workouts/")
        )

    /** The tab that owns [route] by itself; null for shared destinations and unknown routes. */
    private fun ownTab(route: String): String? = when {
        route in bottomTabs -> route
        route.startsWith("settings/") -> SETTINGS
        route.startsWith("browse/") -> BROWSE
        route.startsWith("records/") -> RECORDS
        else -> null
    }

    /**
     * Maps a Nav destination to the bottom-tab route that should appear selected. Shared routes
     * take the nearest tab below them in the back stack ([ancestors], nearest first) and fall
     * back to Browse.
     */
    fun selectedBottomTab(route: String?, ancestors: List<String?> = emptyList()): String? {
        if (route == null) return null
        ownTab(route)?.let { return it }
        if (!isSharedRoute(route)) return null
        for (ancestor in ancestors) {
            val a = ancestor ?: continue
            if (isSharedRoute(a)) continue
            ownTab(a)?.let { return it }
        }
        return BROWSE
    }

    /** A pushed metric / health type detail (never a tab). */
    fun isHealthDetailRoute(route: String?): Boolean = route?.startsWith("health/") == true || route?.startsWith("metric/") == true

    /** A pushed Medications screen (the home, detail, editor, history, import). */
    fun isMedicationsChildRoute(route: String?): Boolean = route == MEDICATIONS || route?.startsWith("medications/") == true

    /** A pushed Records screen such as a record detail (never the tab itself). */
    fun isRecordsChildRoute(route: String?): Boolean = route?.startsWith("records/") == true
}
