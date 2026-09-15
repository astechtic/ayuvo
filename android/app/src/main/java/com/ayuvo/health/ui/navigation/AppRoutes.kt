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

    /** Workouts is no longer a tab; any legacy `workouts…` destination belongs to the Health tab. */
    private const val LEGACY_WORKOUTS = "workouts"

    fun healthType(typeKey: String): String = "health/type/$typeKey"
    fun recordDetail(recordId: String): String = "records/detail/$recordId"
    fun recordSplit(recordId: String): String = "records/split/$recordId"

    val bottomTabs = listOf(HOME, HEALTH, RECORDS, COACH, SETTINGS)

    /** Maps a Nav destination to the bottom-tab route that should appear selected. */
    fun selectedBottomTab(route: String?): String? = when {
        route == null -> null
        route in bottomTabs -> route
        route.startsWith("settings/") -> SETTINGS
        route.startsWith("health/") -> HEALTH
        route.startsWith("records/") -> RECORDS
        route == LEGACY_WORKOUTS || route.startsWith("$LEGACY_WORKOUTS/") -> HEALTH
        else -> null
    }

    /** A pushed Health Data type detail (never the tab itself). */
    fun isHealthDetailRoute(route: String?): Boolean = route?.startsWith("health/") == true

    /** A pushed Records screen such as a record detail (never the tab itself). */
    fun isRecordsChildRoute(route: String?): Boolean = route?.startsWith("records/") == true
}
