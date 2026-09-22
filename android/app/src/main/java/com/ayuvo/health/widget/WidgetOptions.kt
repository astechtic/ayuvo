package com.ayuvo.health.widget

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.ayuvo.health.R
import com.ayuvo.health.models.FoodLogMethod
import com.ayuvo.health.ui.summary.LogEntry

/** Tracking switch an action depends on; when it is off the tile is dimmed and opens Settings. */
enum class WidgetRequirement { WATER_TRACKING, FASTING_TRACKING }

/** Picker sections of the configure screen (ids from `shared/widgets/widget_options.json`). */
enum class QuickLogGroup(@param:StringRes val titleRes: Int) {
    FOOD(R.string.widget_group_food),
    NUTRITION(R.string.widget_group_nutrition),
    BODY(R.string.widget_group_body),
    ACTIVITY(R.string.widget_group_activity),
    MORE(R.string.widget_group_more)
}

/**
 * Quick Log widget actions (docs/widgets.md). Food actions open Browse › Nutrition with [foodMethod]
 * (`food.menu` opens the + food menu); the others run the Summary "+" entry [logEntry].
 */
enum class QuickLogAction(
    val id: String,
    val group: QuickLogGroup,
    @param:StringRes val labelRes: Int,
    @param:DrawableRes val iconRes: Int,
    val tint: Long,
    val foodMethod: FoodLogMethod? = null,
    val logEntry: LogEntry? = null,
    val requires: WidgetRequirement? = null
) {
    FOOD_MENU("food.menu", QuickLogGroup.FOOD, R.string.widget_action_food_menu, R.drawable.ic_widget_add, NUTRITION),
    FOOD_CAMERA("food.camera", QuickLogGroup.FOOD, R.string.widget_action_camera, R.drawable.ic_widget_camera, NUTRITION, FoodLogMethod.CAMERA),
    FOOD_PHOTOS("food.photos", QuickLogGroup.FOOD, R.string.widget_action_photos, R.drawable.ic_widget_photos, NUTRITION, FoodLogMethod.PHOTOS),
    FOOD_BARCODE("food.barcode", QuickLogGroup.FOOD, R.string.widget_action_barcode, R.drawable.ic_widget_barcode, NUTRITION, FoodLogMethod.BARCODE),
    FOOD_VOICE("food.voice", QuickLogGroup.FOOD, R.string.widget_action_voice, R.drawable.ic_widget_mic, NUTRITION, FoodLogMethod.VOICE),
    FOOD_TEXT("food.text", QuickLogGroup.FOOD, R.string.widget_action_text, R.drawable.ic_widget_edit, NUTRITION, FoodLogMethod.TEXT),
    FOOD_MANUAL("food.manual", QuickLogGroup.FOOD, R.string.widget_action_manual, R.drawable.ic_widget_restaurant, NUTRITION, FoodLogMethod.MANUAL),
    FOOD_FAVORITES("food.favorites", QuickLogGroup.FOOD, R.string.widget_action_favorites, R.drawable.ic_widget_favorite, NUTRITION, FoodLogMethod.FAVORITES),
    FOOD_RECENT("food.recent", QuickLogGroup.FOOD, R.string.widget_action_recent, R.drawable.ic_widget_history, NUTRITION, FoodLogMethod.RECENT),
    FOOD_FREQUENT("food.frequent", QuickLogGroup.FOOD, R.string.widget_action_frequent, R.drawable.ic_widget_repeat, NUTRITION, FoodLogMethod.FREQUENT),
    FOOD_COPY_FROM_DAY("food.copy_from_day", QuickLogGroup.FOOD, R.string.widget_action_copy_day, R.drawable.ic_widget_calendar, NUTRITION, FoodLogMethod.COPY_FROM_DAY),
    WATER("water", QuickLogGroup.NUTRITION, R.string.widget_action_water, R.drawable.ic_widget_water, HYDRATION, logEntry = LogEntry.WATER, requires = WidgetRequirement.WATER_TRACKING),
    FASTING("fasting", QuickLogGroup.NUTRITION, R.string.widget_action_start_fast, R.drawable.ic_widget_timer, FASTING_TINT, logEntry = LogEntry.FASTING, requires = WidgetRequirement.FASTING_TRACKING),
    WEIGHT("weight", QuickLogGroup.BODY, R.string.widget_action_weight, R.drawable.ic_widget_scale, BODY, logEntry = LogEntry.WEIGHT),
    BODY_FAT("body_fat", QuickLogGroup.BODY, R.string.widget_action_body_fat, R.drawable.ic_widget_percent, BODY, logEntry = LogEntry.BODY_FAT),
    WORKOUT("workout", QuickLogGroup.ACTIVITY, R.string.widget_action_workout, R.drawable.ic_widget_fitness, ACTIVITY, logEntry = LogEntry.WORKOUT),
    MEDICATION("medication", QuickLogGroup.MORE, R.string.widget_action_medication, R.drawable.ic_widget_pill, MEDICATIONS, logEntry = LogEntry.MEDICATION),
    RECORD("record", QuickLogGroup.MORE, R.string.widget_action_record, R.drawable.ic_widget_record, RECORDS, logEntry = LogEntry.RECORD);

    val isFood: Boolean get() = group == QuickLogGroup.FOOD

    companion object {
        val Defaults = listOf(FOOD_CAMERA, WATER, WEIGHT, WORKOUT)

        fun fromId(id: String?): QuickLogAction? = entries.firstOrNull { it.id == id }

        /** Four slots; a missing or unknown id falls back to that slot's default. Duplicates are kept. */
        fun slots(stored: List<String?>): List<QuickLogAction> =
            Defaults.indices.map { i -> fromId(stored.getOrNull(i)) ?: Defaults[i] }
    }
}

/** My Metrics widget keys (metric catalog ids plus the widget-only next dose). */
enum class WidgetMetric(
    val key: String,
    @param:DrawableRes val iconRes: Int,
    val tint: Long,
    /** Where a tap lands: the metric detail unless noted. */
    val tap: Tap = Tap.METRIC
) {
    CALORIES("app:calories", R.drawable.ic_widget_flame, NUTRITION),
    PROTEIN("app:protein", R.drawable.ic_widget_bolt, 0xFF007AFF),
    CARBS("app:carbs", R.drawable.ic_widget_restaurant, 0xFFFF9F0A),
    FAT("app:fat", R.drawable.ic_widget_restaurant, 0xFFBF5AF2),
    FIBER("app:fiber", R.drawable.ic_widget_restaurant, 0xFF30B0C7),
    WATER("app:water", R.drawable.ic_widget_water, HYDRATION),
    WEIGHT("app:weight", R.drawable.ic_widget_scale, BODY),
    BODY_FAT("app:body_fat", R.drawable.ic_widget_percent, BODY),
    FASTING("app:fasting", R.drawable.ic_widget_timer, FASTING_TINT, Tap.FASTING),
    WORKOUTS("app:workouts", R.drawable.ic_widget_fitness, ACTIVITY),
    WORKOUT_BURN("app:workout_burn", R.drawable.ic_widget_flame, ACTIVITY),
    STEPS("steps", R.drawable.ic_widget_walk, ACTIVITY),
    ACTIVE_ENERGY("active_energy", R.drawable.ic_widget_flame, ACTIVITY),
    SLEEP("sleep", R.drawable.ic_widget_sleep, 0xFF5E5CE6),
    HEART_RATE("heart_rate", R.drawable.ic_widget_favorite, 0xFFFF3B30),
    NEXT_DOSE("medications:next_dose", R.drawable.ic_widget_pill, MEDICATIONS, Tap.MEDICATIONS);

    enum class Tap { METRIC, FASTING, MEDICATIONS }

    companion object {
        val Defaults = listOf(CALORIES, STEPS, WATER, WEIGHT)

        fun fromKey(key: String?): WidgetMetric? = entries.firstOrNull { it.key == key }

        fun slots(stored: List<String?>): List<WidgetMetric> =
            Defaults.indices.map { i -> fromKey(stored.getOrNull(i)) ?: Defaults[i] }
    }
}

private const val NUTRITION = 0xFF34C759
private const val HYDRATION = 0xFF007AFF
private const val FASTING_TINT = 0xFF00C7BE
private const val BODY = 0xFFAF52DE
private const val ACTIVITY = 0xFFFF9500
private const val MEDICATIONS = 0xFF32ADE6
private const val RECORDS = 0xFF5856D6
