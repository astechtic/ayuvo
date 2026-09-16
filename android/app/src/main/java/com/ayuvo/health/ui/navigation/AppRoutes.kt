package com.ayuvo.health.ui.navigation

object AppRoutes {
    const val ONBOARDING = "onboarding"
    const val HOME = "home"
    /** Health tab: "Progress | Health Data | Workouts" segments. Type details push over it (health/type/…). */
    const val HEALTH = "health"
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

    // Medications (docs/medications.md): the Meds segment lives inside the Health tab; these screens
    // push over it and keep Health selected in the bar.
    const val MEDICATION_ID_ARG = "medicationId"
    /** Add a medication, optionally pre-linked to a Health Record (`recordId`). */
    const val MEDICATION_ADD = "medications/add?$RECORD_ID_ARG={$RECORD_ID_ARG}"
    const val MEDICATION_EDIT = "medications/edit/{$MEDICATION_ID_ARG}"
    const val MEDICATION_DETAIL = "medications/detail/{$MEDICATION_ID_ARG}"
    /** Dose history for one medication, or for all of them when the argument is absent. */
    const val MEDICATION_HISTORY = "medications/history?$MEDICATION_ID_ARG={$MEDICATION_ID_ARG}"
    /** "Add from prescription": candidates extracted from one Health Record (§13). */
    const val MEDICATION_IMPORT = "medications/import/{$RECORD_ID_ARG}"

    /** Workouts is no longer a tab; any legacy `workouts…` destination belongs to the Health tab. */
    private const val LEGACY_WORKOUTS = "workouts"

    fun healthType(typeKey: String): String = "health/type/$typeKey"
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

    val bottomTabs = listOf(HOME, HEALTH, RECORDS, COACH, SETTINGS)

    /** Maps a Nav destination to the bottom-tab route that should appear selected. */
    fun selectedBottomTab(route: String?): String? = when {
        route == null -> null
        route in bottomTabs -> route
        route.startsWith("settings/") -> SETTINGS
        route.startsWith("health/") -> HEALTH
        route.startsWith("records/") -> RECORDS
        route == LEGACY_WORKOUTS || route.startsWith("$LEGACY_WORKOUTS/") -> HEALTH
        route.startsWith("medications/") -> HEALTH
        else -> null
    }

    /** A pushed Health Data type detail (never the tab itself). */
    fun isHealthDetailRoute(route: String?): Boolean = route?.startsWith("health/") == true

    /** A pushed Medications screen (detail, editor, history, import); the Meds segment itself is the Health tab. */
    fun isMedicationsChildRoute(route: String?): Boolean = route?.startsWith("medications/") == true

    /** A pushed Records screen such as a record detail (never the tab itself). */
    fun isRecordsChildRoute(route: String?): Boolean = route?.startsWith("records/") == true
}
