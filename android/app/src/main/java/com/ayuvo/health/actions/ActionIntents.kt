package com.ayuvo.health.actions

import android.content.Intent
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.Locale

/**
 * External entry points into the action layer (docs/actions.md):
 *  - `ayuvo://action/<id>?k=v` and `ayuvo://open/<section>` VIEW links ([ActionSource.DEEPLINK]);
 *  - `com.ayuvo.health.ACTION` with an `action_id` extra plus string params (static and dynamic
 *    launcher shortcuts, [ActionSource.ANDROID]);
 *  - `ayuvo://assistant/<bii>` from `res/xml/shortcuts.xml` App Actions capabilities, mapped to a
 *    catalog action by [AssistantIntents] ([ActionSource.ANDROID]).
 * Every one of them arrives through the exported MainActivity, so any app can send them: writes
 * from here always go through the confirm sheet (see [ActionIntents.needsConfirmation]).
 */
object ActionIntents {
    const val ACTION = "com.ayuvo.health.ACTION"
    const val EXTRA_ACTION_ID = "action_id"
    private const val ASSISTANT_PREFIX = "ayuvo://assistant/"

    sealed interface Parsed {
        data class Request(val request: ActionRequest) : Parsed
        /** An `ayuvo://action` link that could not be read; shown as an error, never guessed at. */
        data class BadLink(val error: ActionDeepLink.LinkError) : Parsed
    }

    fun parse(intent: Intent?): Parsed? {
        intent ?: return null
        val extras = intent.extras?.let { bundle ->
            bundle.keySet().mapNotNull { key -> bundle.getString(key)?.let { key to it } ?: bundle.get(key)?.let { key to it.toString() } }.toMap()
        }.orEmpty()
        return parse(intent.action, intent.dataString, extras)
    }

    /** Pure form of [parse] for unit tests. */
    fun parse(action: String?, data: String?, extras: Map<String, String>): Parsed? {
        if (action == ACTION) {
            val id = extras[EXTRA_ACTION_ID] ?: return null
            return Parsed.Request(ActionRequest(id, extras - EXTRA_ACTION_ID, ActionSource.ANDROID))
        }
        if (data == null) return null
        if (data.startsWith(ASSISTANT_PREFIX)) {
            val bii = data.removePrefix(ASSISTANT_PREFIX).substringBefore('?').substringBefore('#')
            return AssistantIntents.map(bii, extras)?.let { Parsed.Request(it) }
        }
        if (!ActionDeepLink.isActionLink(data)) return null
        return when (val parsed = ActionDeepLink.parse(data)) {
            is ActionDeepLink.Parsed.Ok -> Parsed.Request(ActionRequest(parsed.id, parsed.params, ActionSource.DEEPLINK))
            is ActionDeepLink.Parsed.Failed ->
                if (parsed.error == ActionDeepLink.LinkError.NOT_ACTION_LINK) null else Parsed.BadLink(parsed.error)
        }
    }

    /**
     * Writes that arrive from outside the app always ask first, on top of the catalog rule, because
     * nothing proves an exported intent came from the user's own assistant or launcher.
     */
    fun needsConfirmation(action: ValidatedAction): Boolean =
        action.confirm || (action.source.isExternal && action.spec.kind == ActionKind.SET && !action.spec.opensApp)
}

/**
 * Google App Actions built-in intents → catalog actions. Parameter keys are the `android:key`
 * values in `res/xml/shortcuts.xml`; values are the BII text values (docs/actions.md, Android).
 */
object AssistantIntents {
    const val OPEN_APP_FEATURE = "open_app_feature"
    const val GET_HEALTH_OBSERVATION = "get_health_observation"
    const val RECORD_HEALTH_OBSERVATION = "record_health_observation"
    const val GET_FOOD_OBSERVATION = "get_food_observation"
    const val RECORD_FOOD_OBSERVATION = "record_food_observation"
    const val START_EXERCISE = "start_exercise"
    const val STOP_EXERCISE = "stop_exercise"
    const val GET_EXERCISE_OBSERVATION = "get_exercise_observation"

    val ALL = listOf(
        OPEN_APP_FEATURE, GET_HEALTH_OBSERVATION, RECORD_HEALTH_OBSERVATION, GET_FOOD_OBSERVATION,
        RECORD_FOOD_OBSERVATION, START_EXERCISE, STOP_EXERCISE, GET_EXERCISE_OBSERVATION
    )

    fun map(bii: String, p: Map<String, String>, today: LocalDate = LocalDate.now(), zone: ZoneId = ZoneId.systemDefault()): ActionRequest? {
        fun req(id: String, vararg params: Pair<String, String?>) =
            ActionRequest(id, params.filter { it.second != null }.associate { it.first to it.second }, ActionSource.ANDROID)
        return when (bii) {
            OPEN_APP_FEATURE -> req("open.section", "section" to section(p["feature"]))
            GET_HEALTH_OBSERVATION -> when (norm(p["name"])) {
                "weight" -> req("weight.get")
                "heart rate" -> req("health.metric.latest", "metric" to "heart_rate")
                "blood pressure" -> req("health.metric.latest", "metric" to "blood_pressure")
                "blood sugar", "blood glucose" -> req("health.metric.latest", "metric" to "blood_glucose")
                "body mass index", "bmi" -> req("body.composition.get")
                "body temperature" -> req("health.metric.latest", "metric" to "body_temperature")
                "sleep duration", "sleep" -> req("health.sleep.lastNight")
                "water consumption", "water" -> req("water.get")
                "waist size" -> req("health.metric.latest", "metric" to "waist_circumference")
                "steps" -> req("health.metric.get", "metric" to "steps", "range" to range(p, today, zone))
                else -> req("open.section", "section" to "health")
            }
            RECORD_HEALTH_OBSERVATION -> {
                val value = p["value"]
                if (norm(p["name"]) == "weight" && value != null) {
                    req("weight.log", "value" to value, "unit" to massUnit(p["unitText"]))
                } else if (norm(p["name"]) == "weight") {
                    req("open.section", "section" to "body")
                } else req("open.section", "section" to "health")
            }
            RECORD_FOOD_OBSERVATION -> {
                val nutrient = norm(p["aboutNutrientName"])
                val food = p["aboutFoodName"]?.takeIf { it.isNotBlank() }
                when {
                    nutrient == "water" && p["value"] != null ->
                        req("water.log", "amount" to p["value"], "unit" to volumeUnit(p["unitText"]))
                    nutrient == "water" -> req("open.section", "section" to "water")
                    food != null && norm(food) == "water" && p["value"] != null ->
                        req("water.log", "amount" to p["value"], "unit" to volumeUnit(p["unitText"]))
                    food != null -> req("nutrition.food.log", "description" to food, "meal" to meal(p["forMeal"]))
                    else -> req("open.section", "section" to "nutrition")
                }
            }
            GET_FOOD_OBSERVATION -> {
                val range = range(p, today, zone)
                when (norm(p["aboutNutrientName"])) {
                    "calories" -> req("nutrition.nutrient.get", "nutrient" to "calories", "range" to range)
                    "protein" -> req("nutrition.nutrient.get", "nutrient" to "protein", "range" to range)
                    "carbohydrate", "carbohydrates", "carbs" -> req("nutrition.nutrient.get", "nutrient" to "carbs", "range" to range)
                    "fat" -> req("nutrition.nutrient.get", "nutrient" to "fat", "range" to range)
                    "dietary fiber", "fiber" -> req("nutrition.nutrient.get", "nutrient" to "fiber", "range" to range)
                    "water" -> req("water.get", "range" to range)
                    else -> req("nutrition.summary.get", "range" to range)
                }
            }
            START_EXERCISE -> req("workout.start")
            STOP_EXERCISE -> req("workout.finish")
            GET_EXERCISE_OBSERVATION -> req("workout.today.get")
            else -> null
        }
    }

    private fun norm(s: String?): String = s.orEmpty().trim().lowercase(Locale.ROOT)

    /** OPEN_APP_FEATURE inline inventory values are catalog section ids; spoken synonyms map too. */
    fun section(feature: String?): String? = when (val f = norm(feature).replace('_', ' ')) {
        "" -> null
        "health records", "records", "lab reports", "blood tests" -> "records"
        "medicines", "medicine", "medication", "medications", "meds" -> "medications"
        "ai coach", "coach", "ayuvo coach" -> "coach"
        "workout", "workouts", "exercise", "training" -> "workouts"
        "water", "hydration" -> "water"
        "fasting", "fast" -> "fasting"
        "food", "nutrition", "diary", "food diary" -> "nutrition"
        "health", "health data" -> "health"
        "summary", "today", "home" -> "summary"
        "body", "body measurements", "weight" -> "body"
        else -> f.replace(' ', '_')
    }

    private fun massUnit(unitText: String?): String? = when (norm(unitText)) {
        "kilogram", "kilograms", "kg" -> "kg"
        "pound", "pounds", "lb", "lbs" -> "lb"
        else -> null
    }

    private fun volumeUnit(unitText: String?): String? = when (norm(unitText)) {
        "milliliter", "milliliters", "millilitre", "millilitres", "ml" -> "ml"
        "liter", "liters", "litre", "litres", "l" -> "l"
        "fluid ounce", "fluid ounces", "ounce", "ounces", "fl oz", "oz" -> "floz"
        "cup", "cups" -> "cup"
        else -> null
    }

    private fun meal(forMeal: String?): String? {
        val m = norm(forMeal)
        return when {
            m.endsWith("breakfast") || m.endsWith("brunch") -> "breakfast"
            m.endsWith("lunch") -> "lunch"
            m.endsWith("dinner") -> "dinner"
            m.endsWith("snack") || m.endsWith("dessert") -> "snack"
            else -> null
        }
    }

    /** `startTime` on yesterday's date asks about yesterday; anything else is today. */
    private fun range(p: Map<String, String>, today: LocalDate, zone: ZoneId): String {
        val start = p["startTime"] ?: return "today"
        val date = runCatching { OffsetDateTime.parse(start).atZoneSameInstant(zone).toLocalDate() }.getOrNull()
            ?: runCatching { LocalDate.parse(start.take(10)) }.getOrNull()
        return if (date == today.minusDays(1)) "yesterday" else "today"
    }
}
