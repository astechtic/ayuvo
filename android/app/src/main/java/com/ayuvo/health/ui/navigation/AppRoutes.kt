package com.ayuvo.health.ui.navigation

object AppRoutes {
    const val ONBOARDING = "onboarding"
    const val HOME = "home"
    /** Health tab: "Progress | Health Data" segments. Type details push over it (health/type/…). */
    const val HEALTH = "health"
    const val COACH = "coach"
    const val WORKOUTS = "workouts"
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

    fun healthType(typeKey: String): String = "health/type/$typeKey"

    val bottomTabs = listOf(HOME, HEALTH, COACH, WORKOUTS, SETTINGS)

    /** Maps a Nav destination to the bottom-tab route that should appear selected. */
    fun selectedBottomTab(route: String?): String? = when {
        route == null -> null
        route in bottomTabs -> route
        route.startsWith("settings/") -> SETTINGS
        route.startsWith("health/") -> HEALTH
        else -> null
    }

    /** A pushed Health Data type detail (never the tab itself). */
    fun isHealthDetailRoute(route: String?): Boolean = route?.startsWith("health/") == true
}
