package com.ayuvo.health.ui.browse

import androidx.annotation.StringRes
import com.ayuvo.health.R
import java.text.Normalizer
import java.util.Locale

/** Colour family of a Browse feature result (matches the domain rows). */
enum class BrowseFeatureDomain { ACTIVITY, NUTRITION, HYDRATION, FASTING, BODY, MEDICATIONS, RECORDS, INSIGHTS, COACH, SETTINGS }

/**
 * A place or action Browse search can open directly ("Features" section above the trends).
 * [id] is shared with iOS (`browse.feature.<id>` test tags); [keywords] are English search terms and
 * synonyms, matched together with the localized title.
 */
data class BrowseFeature(
    val id: String,
    @StringRes val titleRes: Int,
    @StringRes val subtitleRes: Int,
    val domain: BrowseFeatureDomain,
    val keywords: List<String>
)

object BrowseFeatures {
    private val WORKOUT = listOf("workout", "workouts", "gym", "exercise", "exercises", "training", "train", "fitness", "strength", "lifting", "cardio", "sport")
    private val FOOD = listOf("food", "meal", "meals", "diary", "nutrition", "eat", "eating", "calories", "kcal", "diet", "macros", "protein", "carbs", "journal")
    private val MEDS = listOf("medication", "medications", "meds", "medicine", "medicines", "pill", "pills", "tablet", "tablets", "capsule", "drug", "drugs", "dose", "doses", "supplement", "supplements", "prescription")
    private val RECORDS = listOf("record", "records", "health records", "report", "reports", "lab", "labs", "lab report", "test", "tests", "result", "results", "blood", "prescription", "prescriptions", "scan", "document", "documents", "pdf", "medical")

    private val INSIGHTS = listOf("insights", "insight")

    /** Table order is the result order. */
    val all: List<BrowseFeature> = listOf(
        BrowseFeature("workouts", R.string.browse_feature_workouts, R.string.browse_feature_workouts_sub, BrowseFeatureDomain.ACTIVITY,
            WORKOUT + listOf("activity", "history", "sessions")),
        BrowseFeature("workoutLog", R.string.browse_feature_workout_log, R.string.browse_feature_workout_log_sub, BrowseFeatureDomain.ACTIVITY,
            WORKOUT + listOf("log", "start", "new", "add", "session", "track")),
        BrowseFeature("exerciseLibrary", R.string.browse_feature_exercise_library, R.string.browse_feature_exercise_library_sub, BrowseFeatureDomain.ACTIVITY,
            WORKOUT + listOf("library", "explore", "browse", "movement", "movements", "muscle", "muscles", "stretch", "stretches", "how to")),
        BrowseFeature("nutrition", R.string.browse_feature_nutrition, R.string.browse_feature_nutrition_sub, BrowseFeatureDomain.NUTRITION,
            FOOD + listOf("history", "breakfast", "lunch", "dinner", "snack")),
        BrowseFeature("logFood", R.string.browse_feature_log_food, R.string.browse_feature_log_food_sub, BrowseFeatureDomain.NUTRITION,
            FOOD + listOf("log", "add", "track", "photo", "barcode", "voice", "breakfast", "lunch", "dinner", "snack")),
        BrowseFeature("water", R.string.browse_feature_water, R.string.browse_feature_water_sub, BrowseFeatureDomain.HYDRATION,
            listOf("water", "drink", "drinks", "hydration", "hydrate", "fluid", "fluids", "glass", "log", "add")),
        BrowseFeature("fasting", R.string.browse_feature_fasting, R.string.browse_feature_fasting_sub, BrowseFeatureDomain.FASTING,
            listOf("fasting", "fast", "fasts", "intermittent", "timer", "start")),
        BrowseFeature("bodyMeasurements", R.string.browse_feature_body_measurements, R.string.browse_feature_body_measurements_sub, BrowseFeatureDomain.BODY,
            listOf("body", "measurement", "measurements", "measure", "waist", "chest", "hips", "arm", "arms", "thigh", "neck", "tape", "size", "sizes", "log")),
        BrowseFeature("logWeight", R.string.browse_feature_log_weight, R.string.browse_feature_log_weight_sub, BrowseFeatureDomain.BODY,
            listOf("weight", "weigh", "weigh in", "scale", "kg", "lbs", "pounds", "body", "log", "add")),
        BrowseFeature("logBodyFat", R.string.browse_feature_log_body_fat, R.string.browse_feature_log_body_fat_sub, BrowseFeatureDomain.BODY,
            listOf("body fat", "fat", "percentage", "composition", "body", "log", "add")),
        BrowseFeature("medications", R.string.browse_feature_medications, R.string.browse_feature_medications_sub, BrowseFeatureDomain.MEDICATIONS,
            MEDS + listOf("reminder", "reminders", "today", "schedule", "history")),
        BrowseFeature("addMedication", R.string.browse_feature_add_medication, R.string.browse_feature_add_medication_sub, BrowseFeatureDomain.MEDICATIONS,
            MEDS + listOf("add", "new", "reminder", "reminders")),
        BrowseFeature("records", R.string.browse_feature_records, R.string.browse_feature_records_sub, BrowseFeatureDomain.RECORDS,
            RECORDS + listOf("highlights", "history")),
        BrowseFeature("addRecord", R.string.browse_feature_add_record, R.string.browse_feature_add_record_sub, BrowseFeatureDomain.RECORDS,
            RECORDS + listOf("add", "new", "upload", "import", "photo", "camera")),
        BrowseFeature("insights", R.string.browse_feature_insights, R.string.browse_feature_insights_sub, BrowseFeatureDomain.INSIGHTS,
            INSIGHTS + listOf("trends", "trend", "baseline", "baselines", "patterns", "pattern")),
        BrowseFeature("recovery", R.string.browse_feature_recovery, R.string.browse_feature_recovery_sub, BrowseFeatureDomain.INSIGHTS,
            INSIGHTS + listOf("recovery", "readiness", "hrv", "resting heart rate", "morning", "strain")),
        BrowseFeature("healthAge", R.string.browse_feature_health_age, R.string.browse_feature_health_age_sub, BrowseFeatureDomain.INSIGHTS,
            INSIGHTS + listOf("health age", "age", "vo2", "vo2 max", "fitness", "longevity", "pace")),
        BrowseFeature("dailyReview", R.string.browse_feature_daily_review, R.string.browse_feature_daily_review_sub, BrowseFeatureDomain.INSIGHTS,
            INSIGHTS + listOf("daily review", "review", "day score", "score", "summary", "reflection")),
        BrowseFeature("coach", R.string.browse_feature_coach, R.string.browse_feature_coach_sub, BrowseFeatureDomain.COACH,
            listOf("coach", "ai", "chat", "ask", "assistant", "advice", "question", "questions", "help")),
        BrowseFeature("settings", R.string.browse_feature_settings, R.string.browse_feature_settings_sub, BrowseFeatureDomain.SETTINGS,
            listOf("settings", "preferences", "profile", "goals", "goal", "reminders", "notifications", "units", "backup", "export", "import", "privacy", "provider", "api key", "theme", "appearance"))
    )

    /** Lower-case, accents removed, anything but letters and digits becomes a word break. */
    fun normalize(text: String): String {
        val stripped = Normalizer.normalize(text.lowercase(Locale.ROOT), Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")
        return stripped.replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
    }

    private fun words(text: String): List<String> = normalize(text).split(' ').filter { it.isNotEmpty() }

    /**
     * Every query word must be a prefix of a keyword or title word; a trailing plural "s" on words of
     * five or more letters is also tried without it ("pills" finds "pill"; "meds" does not become "med").
     */
    fun matches(feature: BrowseFeature, query: String, title: String = ""): Boolean {
        val tokens = words(query)
        if (tokens.isEmpty()) return false
        val vocabulary = (feature.keywords + title).flatMap(::words)
        return tokens.all { token ->
            val stem = if (token.length > 4 && token.endsWith("s")) token.dropLast(1) else token
            vocabulary.any { it.startsWith(token) || it.startsWith(stem) }
        }
    }

    /** Matching features in table order; [titleOf] supplies the localized title for matching. */
    fun search(query: String, titleOf: (BrowseFeature) -> String = { "" }): List<BrowseFeature> =
        all.filter { matches(it, query, titleOf(it)) }
}
