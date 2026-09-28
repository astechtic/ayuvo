package com.ayuvo.health.ui.metrics

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.core.graphics.toColorInt
import com.ayuvo.health.R
import com.ayuvo.health.data.metrics.AppMetricId
import com.ayuvo.health.data.metrics.CatalogBrowseSection
import com.ayuvo.health.data.metrics.CatalogDomain
import com.ayuvo.health.data.metrics.CatalogMetric
import com.ayuvo.health.data.metrics.CatalogNutrientMetric
import com.ayuvo.health.data.metrics.MetricCatalogData
import com.ayuvo.health.data.metrics.MetricKey
import com.ayuvo.health.data.metrics.MetricRange
import com.ayuvo.health.data.metrics.MetricsReference
import com.ayuvo.health.data.metrics.RegistryFacts
import com.ayuvo.health.data.metrics.ResolvedMetric
import com.ayuvo.health.models.HealthCategory
import com.ayuvo.health.models.HealthDataType
import com.ayuvo.health.models.UserProfile
import com.ayuvo.health.models.WaterUnit
import com.ayuvo.health.nutrients.NutrientFields
import com.ayuvo.health.ui.design.AyuvoColors
import com.ayuvo.health.ui.health.HealthCategoryStyle
import com.ayuvo.health.ui.health.HealthChartRange
import com.ayuvo.health.ui.health.HealthValueFormatter
import java.util.Locale

/** Display units the app metrics honour (`unit.pref` in the catalog). */
data class MetricUnits(val weightMetric: Boolean = true, val waterUnit: WaterUnit = WaterUnit.Default)

/** Goal inputs resolved from `goal_source` (docs/ui-structure.md §4). */
data class MetricGoalInputs(val profile: UserProfile? = null, val waterGoalMl: Int? = null, val dailyStepGoal: Int? = null)

/**
 * Presentation facts for metric keys, backed by the bundled `metric_catalog.json`
 * ([com.ayuvo.health.AppContainer.metricCatalog]). Titles and about text come from string
 * resources named in the catalog; health types use the registry mirror.
 */
object MetricCatalog {

    fun registry(id: String): RegistryFacts? =
        HealthDataType.byId(id)?.let { RegistryFacts(it.category.id, it.aggregation.name, it.unit) }

    fun resolve(catalog: MetricCatalogData, key: MetricKey): ResolvedMetric =
        MetricsReference.resolveMetric(catalog, key.storageId, ::registry)

    fun spec(catalog: MetricCatalogData, id: AppMetricId): CatalogMetric = catalog.metricByKey.getValue(id.key)

    fun domain(catalog: MetricCatalogData, key: MetricKey): CatalogDomain =
        catalog.domainById.getValue(resolve(catalog, key).domain)

    @StringRes
    fun titleRes(id: AppMetricId): Int = when (id) {
        AppMetricId.CALORIES -> R.string.metric_calories
        AppMetricId.PROTEIN -> R.string.metric_protein
        AppMetricId.CARBS -> R.string.metric_carbs
        AppMetricId.FAT -> R.string.metric_fat
        AppMetricId.FIBER -> R.string.metric_fiber
        AppMetricId.WATER -> R.string.metric_water
        AppMetricId.FASTING -> R.string.metric_fasting
        AppMetricId.WEIGHT -> R.string.metric_weight
        AppMetricId.BODY_FAT -> R.string.metric_body_fat
        AppMetricId.WORKOUTS -> R.string.metric_workouts
        AppMetricId.WORKOUT_MINUTES -> R.string.metric_workout_minutes
        AppMetricId.WORKOUT_BURN -> R.string.metric_workout_burn
    }

    @StringRes
    fun aboutRes(id: AppMetricId): Int = when (id) {
        AppMetricId.CALORIES -> R.string.metric_about_calories
        AppMetricId.PROTEIN -> R.string.metric_about_protein
        AppMetricId.CARBS -> R.string.metric_about_carbs
        AppMetricId.FAT -> R.string.metric_about_fat
        AppMetricId.FIBER -> R.string.metric_about_fiber
        AppMetricId.WATER -> R.string.metric_about_water
        AppMetricId.FASTING -> R.string.metric_about_fasting
        AppMetricId.WEIGHT -> R.string.metric_about_weight
        AppMetricId.BODY_FAT -> R.string.metric_about_body_fat
        AppMetricId.WORKOUTS -> R.string.metric_about_workouts
        AppMetricId.WORKOUT_MINUTES -> R.string.metric_about_workout_minutes
        AppMetricId.WORKOUT_BURN -> R.string.metric_about_workout_burn
    }

    fun title(context: Context, key: MetricKey): String = when (key) {
        is MetricKey.App -> context.getString(titleRes(key.id))
        is MetricKey.Nutrient -> NutrientFields.displayName(context, key.key)
        is MetricKey.Health -> HealthCategoryStyle.typeName(context, key.typeId)
    }

    /** The catalog's `nutrient_metrics` entry of [key]; null for a key the catalog does not list. */
    fun nutrientSpec(catalog: MetricCatalogData, key: MetricKey.Nutrient): CatalogNutrientMetric? = catalog.nutrientByKey[key.storageId]

    /**
     * Browse › Nutrition nutrient sections (docs/ui-structure.md §4): the nutrition browse sections
     * that hold nutrient metrics, in section order, each with its visible entries (`browse_hidden`
     * dropped) in `browse_order`.
     */
    fun nutrientBrowseSections(catalog: MetricCatalogData): List<Pair<CatalogBrowseSection, List<CatalogNutrientMetric>>> =
        catalog.browseSections.filter { it.domain == "nutrition" }.sortedBy { it.order }.mapNotNull { s ->
            val rows = catalog.nutrientMetrics.filter { it.browseSection == s.id && !it.browseHidden }.sortedBy { it.browseOrder }
            if (rows.isEmpty()) null else s to rows
        }

    /** Title resource of a nutrition browse section; null for a section this build does not know. */
    @StringRes
    fun browseSectionTitleRes(sectionId: String): Int? = when (sectionId) {
        "nutrition.energy" -> R.string.browse_section_nutrition_energy
        "nutrition.macros" -> R.string.browse_section_nutrition_macros
        "nutrition.carbs" -> R.string.browse_section_nutrition_carbs
        "nutrition.fats" -> R.string.browse_section_nutrition_fats
        "nutrition.minerals" -> R.string.browse_section_nutrition_minerals
        "nutrition.vitamins" -> R.string.browse_section_nutrition_vitamins
        "nutrition.other" -> R.string.browse_section_nutrition_other
        "nutrition.supplements" -> R.string.browse_section_nutrition_supplements
        else -> null
    }

    fun ranges(catalog: MetricCatalogData, key: MetricKey.Nutrient): List<HealthChartRange> =
        (nutrientSpec(catalog, key)?.ranges ?: listOf("D", "W", "M", "6M", "Y")).map { HealthChartRange.of(MetricRange.fromRaw(it)) }

    /** Metric icon, then override icon, then domain icon, then the Other domain icon (docs/ui-structure.md §4). */
    fun icon(catalog: MetricCatalogData, key: MetricKey): ImageVector =
        MetricIcons.byName(resolve(catalog, key).iconAndroid)
            ?: (key as? MetricKey.Health)?.let { HealthCategoryStyle.icon(HealthDataType.byId(it.typeId)?.category ?: HealthCategory.OTHER) }
            ?: MetricIcons.byNameOrDefault(catalog.domainById.getValue("other").iconAndroid)

    fun parseColor(hex: String): Color = Color(hex.toColorInt())

    /** Domain colour, dark-mode variant when the current theme is dark. */
    @Composable
    fun color(domain: CatalogDomain): Color =
        parseColor(if (AyuvoColors.isDark()) domain.colourHexDark else domain.colourHex)

    @Composable
    fun color(catalog: MetricCatalogData, key: MetricKey): Color = color(domain(catalog, key))

    fun ranges(catalog: MetricCatalogData, id: AppMetricId): List<HealthChartRange> =
        spec(catalog, id).ranges.map { HealthChartRange.of(MetricRange.fromRaw(it)) }

    /** Unit label shown next to a value; durations carry their unit inside the formatted text. */
    fun unitLabel(id: AppMetricId, units: MetricUnits): String = when (id) {
        AppMetricId.CALORIES, AppMetricId.WORKOUT_BURN -> "kcal"
        AppMetricId.PROTEIN, AppMetricId.CARBS, AppMetricId.FAT, AppMetricId.FIBER -> "g"
        AppMetricId.WATER -> units.waterUnit.symbol
        AppMetricId.WEIGHT -> if (units.weightMetric) "kg" else "lb"
        AppMetricId.BODY_FAT -> "%"
        AppMetricId.FASTING, AppMetricId.WORKOUT_MINUTES, AppMetricId.WORKOUTS -> ""
    }

    /** Canonical value → display unit (chart axes and formatted numbers use this). */
    fun display(id: AppMetricId, canonical: Double, units: MetricUnits): Double = when (id) {
        AppMetricId.WATER -> if (units.waterUnit == WaterUnit.MILLILITERS) canonical else canonical / WaterUnit.MILLILITERS_PER_FLUID_OUNCE
        AppMetricId.WEIGHT -> if (units.weightMetric) canonical else canonical * KG_TO_LB
        else -> canonical
    }

    /** Display unit → canonical (goal lines are compared in canonical units, inputs converted back). */
    fun canonical(id: AppMetricId, display: Double, units: MetricUnits): Double = when (id) {
        AppMetricId.WATER -> if (units.waterUnit == WaterUnit.MILLILITERS) display else display * WaterUnit.MILLILITERS_PER_FLUID_OUNCE
        AppMetricId.WEIGHT -> if (units.weightMetric) display else display / KG_TO_LB
        else -> display
    }

    /** Formats an already-converted display value (see [display]). */
    fun formatDisplay(id: AppMetricId, value: Double, locale: Locale = Locale.getDefault()): String = when (id) {
        AppMetricId.FASTING, AppMetricId.WORKOUT_MINUTES -> HealthValueFormatter.duration(value)
        AppMetricId.WEIGHT, AppMetricId.BODY_FAT -> HealthValueFormatter.decimal(value, 1, locale)
        AppMetricId.WATER -> if (value >= 100 || value == Math.rint(value)) HealthValueFormatter.integer(value, locale) else HealthValueFormatter.decimal(value, 1, locale)
        else -> HealthValueFormatter.integer(value, locale)
    }

    fun format(id: AppMetricId, canonical: Double, units: MetricUnits, locale: Locale = Locale.getDefault()): String =
        formatDisplay(id, display(id, canonical, units), locale)

    /** Goal in canonical units from the catalog's `goal_source`; null when unset. */
    fun goal(catalog: MetricCatalogData, id: AppMetricId, inputs: MetricGoalInputs): Double? = goal(spec(catalog, id).goalSource, inputs)

    /**
     * Goal of a resolved `goal_source` (app metrics and health overrides, e.g. `dietary_energy` →
     * `profile.calories`); null when unset or when the source is not a single value
     * (`none`, `nutrient.reference`).
     */
    fun goal(goalSource: String, inputs: MetricGoalInputs): Double? {
        val p = inputs.profile
        return when (goalSource) {
            "profile.calories" -> p?.effectiveCalories?.toDouble()
            "profile.protein" -> p?.effectiveProtein?.toDouble()
            "profile.carbs" -> p?.effectiveCarbs?.toDouble()
            "profile.fat" -> p?.effectiveFat?.toDouble()
            "prefs.waterDailyGoalMl" -> inputs.waterGoalMl?.toDouble()
            "prefs.dailyStepGoal" -> inputs.dailyStepGoal?.toDouble()
            "profile.goalWeight" -> p?.goalWeightKg
            "profile.goalBodyFat" -> p?.goalBodyFatPercentage?.let { if (it <= 1.0) it * 100 else it }
            else -> null
        }?.takeIf { it > 0 }
    }

    const val KG_TO_LB = 2.20462
}
