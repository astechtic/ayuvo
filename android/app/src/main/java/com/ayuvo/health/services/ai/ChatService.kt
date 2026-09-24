package com.ayuvo.health.services.ai

import com.ayuvo.health.data.KeyStore
import com.ayuvo.health.data.PreferencesStore
import com.ayuvo.health.data.health.HealthCoachSnapshot
import com.ayuvo.health.models.AIProvider
import com.ayuvo.health.models.ActivityLevel
import com.ayuvo.health.models.BodyFatEntry
import com.ayuvo.health.models.BodyMeasurement
import com.ayuvo.health.coach.logic.CoachCatalogs
import com.ayuvo.health.coach.model.CoachDataSwitches
import com.ayuvo.health.coach.model.CoachMessage
import com.ayuvo.health.coach.model.CoachSource
import com.ayuvo.health.medications.coach.CoachMedicationsContext
import com.ayuvo.health.models.WeightGoal
import com.ayuvo.health.models.FoodEntry
import com.ayuvo.health.models.FastingSession
import com.ayuvo.health.models.UserProfile
import com.ayuvo.health.models.WorkoutDayPlan
import com.ayuvo.health.models.WorkoutPreferences
import com.ayuvo.health.models.WorkoutSession
import com.ayuvo.health.models.WorkoutWeightUnit
import com.ayuvo.health.models.WeightEntry
import com.ayuvo.health.records.coach.CoachRecordRef
import com.ayuvo.health.records.coach.RecordsCoachSwitchToOnDevice
import com.ayuvo.health.records.coach.RecordsCoachTools
import com.ayuvo.health.services.WeightAnalysisService
import com.ayuvo.health.services.WeightForecast
import com.ayuvo.health.services.ondevice.LocalGemmaRuntime
import kotlinx.coroutines.flow.first
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.Locale

/**
 * Multi-turn coach chat with **tool calling** — Coach can fetch any slice of
 * the user's data on demand instead of the previous fixed last-N-entries dump
 * in the system prompt. Tool definitions per provider are built inline below
 * (Gemini functionDeclarations / Anthropic input_schema / OpenAI function);
 * the executor side lives in [CoachTools].
 *
 * Per-format multi-turn loops are capped at MAX_TOOL_ROUNDS to bound any
 * runaway recursion from a misbehaving model.
 */
/**
 * Medication lines of `## Data available` plus the guardrails (docs/medications.md §20). The
 * "not available" line is only worth the tokens when the user actually asked about medicines.
 */
/**
 * The `## Charts` section that teaches the model the `ayuvo-chart` block, plus its guardrails
 * (docs/coach.md §5). Both come verbatim from `shared/coach/chart_spec.json`.
 */
internal fun chartPromptLines(): List<String> {
    val section = CoachCatalogs.chartsPromptSection()
    if (section.isEmpty()) return emptyList()
    return listOf("", section, "", CoachCatalogs.chartGuardrails())
}

internal fun medicationsDataLines(medications: CoachMedicationsContext?, message: String): List<String> {
    if (medications == null || !medications.toolsAvailable) {
        if (!CoachMedicationsContext.mentionsMedicines(message)) return emptyList()
        val line = CoachMedicationsContext.notAvailableLine()
        return if (line.isEmpty()) emptyList() else listOf("- $line")
    }
    return buildList {
        medications.promptLines().forEachIndexed { index, text ->
            // The first line is the availability sentence; the rest is the guardrail block.
            if (index == 0) add("- $text") else addAll(text.split("\n"))
        }
    }
}

class ChatService(
    private val prefs: PreferencesStore,
    private val keyStore: KeyStore,
    private val okHttp: OkHttpClient = FoodAnalysisService.defaultClient,
    private val localGemma: LocalGemmaRuntime? = null
) {

    /** A Coach reply plus the health records it relied on (docs/health-records.md §26). */
    data class CoachReply(val text: String, val recordRefs: List<CoachRecordRef> = emptyList())

    /**
     * Health Records context for one message (§26–§30), built by the Coach screen: the tools (cloud
     * providers), the `## Data available` lines + guardrails, and the §29 packed block for on-device Coach.
     */
    class RecordsTurn(
        val tools: RecordsCoachTools? = null,
        /** `prompt.available_line` (enabled with records), or null. */
        val availableLine: String? = null,
        /** `prompt.selected_header` + one `- ` line per selected record, or empty. */
        val selectedLines: List<String> = emptyList(),
        val guardrails: String? = null,
        /** `prompt.not_available_line` when access is off and the message names records. */
        val notAvailableLine: String? = null,
        val onDeviceBlock: String? = null,
        val onDeviceRefs: List<CoachRecordRef> = emptyList()
    ) {
        val hasCloudLines: Boolean get() = availableLine != null || notAvailableLine != null || selectedLines.isNotEmpty() || guardrails != null
    }

    /** The provider Coach would use for a message (separate text provider honoured). */
    suspend fun coachProvider(hasImage: Boolean): AIProvider =
        if (!hasImage && prefs.separateTextProviderEnabled.first()) prefs.selectedTextAIProvider.first() else prefs.selectedAIProvider.first()

    suspend fun sendMessage(
        history: List<CoachMessage>,
        newUserMessage: String,
        profile: UserProfile,
        weights: List<WeightEntry>,
        bodyFats: List<BodyFatEntry>,
        measurements: List<BodyMeasurement> = emptyList(),
        foods: List<FoodEntry>,
        fastingSessions: List<FastingSession> = emptyList(),
        heightMetric: Boolean,
        weightMetric: Boolean,
        imageBytes: ByteArray? = null,
        workoutSessions: List<WorkoutSession> = emptyList(),
        workoutPlans: List<WorkoutDayPlan> = emptyList(),
        workoutPreferences: WorkoutPreferences = WorkoutPreferences(),
        workoutPlanWeightUnit: WorkoutWeightUnit = WorkoutWeightUnit.LBS,
        /** Health Data hub snapshot — non-null only when the hub is on AND Coach consent is on. */
        healthSnapshot: HealthCoachSnapshot? = null,
        healthHubEnabled: Boolean = false
    ): String = send(
        history, newUserMessage, profile, weights, bodyFats, measurements, foods, fastingSessions, heightMetric, weightMetric,
        imageBytes, workoutSessions, workoutPlans, workoutPreferences, workoutPlanWeightUnit, healthSnapshot, healthHubEnabled
    ).text

    suspend fun send(
        history: List<CoachMessage>,
        newUserMessage: String,
        profile: UserProfile,
        weights: List<WeightEntry>,
        bodyFats: List<BodyFatEntry>,
        measurements: List<BodyMeasurement> = emptyList(),
        foods: List<FoodEntry>,
        fastingSessions: List<FastingSession> = emptyList(),
        heightMetric: Boolean,
        weightMetric: Boolean,
        imageBytes: ByteArray? = null,
        workoutSessions: List<WorkoutSession> = emptyList(),
        workoutPlans: List<WorkoutDayPlan> = emptyList(),
        workoutPreferences: WorkoutPreferences = WorkoutPreferences(),
        workoutPlanWeightUnit: WorkoutWeightUnit = WorkoutWeightUnit.LBS,
        healthSnapshot: HealthCoachSnapshot? = null,
        healthHubEnabled: Boolean = false,
        records: RecordsTurn? = null,
        medications: CoachMedicationsContext? = null,
        /** The conversation's data switches (docs/coach.md §8); they only ever narrow. */
        sources: CoachDataSwitches = CoachDataSwitches.ALL_ON,
        /** §30 "Use on-device Coach": this conversation runs on the on-device model. */
        providerOverride: AIProvider? = null
    ): CoachReply {
        // A switch that is off removes the source before the prompt or the tools see it, so nothing
        // downstream has to remember to check again.
        @Suppress("NAME_SHADOWING") val records = records?.takeIf { sources.isOn(CoachSource.RECORDS) }
        @Suppress("NAME_SHADOWING") val medications = medications?.takeIf { sources.isOn(CoachSource.MEDICATIONS) }
        @Suppress("NAME_SHADOWING") val healthSnapshot = healthSnapshot?.takeIf { sources.isOn(CoachSource.HEALTH) }
        val baseSystemPrompt = buildSystemPrompt(
            profile = profile,
            weights = weights,
            bodyFats = bodyFats,
            measurements = measurements,
            foods = foods,
            fastingSessions = fastingSessions,
            heightMetric = heightMetric,
            weightMetric = weightMetric,
            workoutSessions = workoutSessions,
            workoutPlans = workoutPlans,
            healthSnapshot = healthSnapshot,
            healthHubEnabled = healthHubEnabled,
            recordsLines = records?.let(::recordsDataLines).orEmpty()
                + medicationsDataLines(medications, newUserMessage)
                + chartPromptLines()
        )
        val userContext = prefs.userContext.first()
        val systemPrompt = if (userContext.isNotBlank())
            "$baseSystemPrompt\n\n## User-provided context\n$userContext"
        else baseSystemPrompt
        // On-device / no-tool providers cannot call tools: give them a ≤12-line health digest and the
        // §29 block of the selected records (never the records tool lines).
        val localBase = if (records == null || !records.hasCloudLines) systemPrompt else {
            val base = buildSystemPrompt(
                profile = profile, weights = weights, bodyFats = bodyFats, measurements = measurements, foods = foods,
                fastingSessions = fastingSessions, heightMetric = heightMetric, weightMetric = weightMetric,
                workoutSessions = workoutSessions, workoutPlans = workoutPlans, healthSnapshot = healthSnapshot,
                healthHubEnabled = healthHubEnabled
            )
            if (userContext.isNotBlank()) "$base\n\n## User-provided context\n$userContext" else base
        }
        val healthDigest = healthSnapshot?.let { CoachHealthData(it).promptSummary().joinToString("\n") }
        val withDigest = if (healthDigest.isNullOrBlank()) localBase else "$localBase\n\n$healthDigest"
        // §29/§32: the packed block, then the records guardrails (outside the 1,800-character limit).
        val withRecords = records?.onDeviceBlock?.takeIf { it.isNotBlank() }?.let { block ->
            withDigest + "\n\n" + onDeviceRecordsTail(block, records.guardrails)
        } ?: withDigest
        // docs/coach.md §3: names and schedules only, never a dose recommendation.
        val localSystemPrompt = medications?.takeIf { it.toolsAvailable }?.onDeviceBlock()?.let { block ->
            withRecords + "\n\n" + block
        } ?: withRecords
        val tools = CoachTools(
            medications = medications,
            sources = sources,
            weights = weights,
            bodyFats = bodyFats,
            foods = foods,
            fastingSessions = fastingSessions,
            workoutSessions = workoutSessions,
            workoutPlans = workoutPlans,
            workoutPreferences = workoutPreferences,
            workoutPlanWeightUnit = workoutPlanWeightUnit,
            healthSnapshot = healthSnapshot,
            records = records?.tools
        )

        val useSeparateTextProvider = imageBytes == null && prefs.separateTextProviderEnabled.first()
        val provider = providerOverride ?: if (useSeparateTextProvider) {
            prefs.selectedTextAIProvider.first()
        } else {
            prefs.selectedAIProvider.first()
        }
        val model = when {
            providerOverride != null -> provider.defaultModel
            useSeparateTextProvider -> provider.supportedTextModelOrDefault(prefs.selectedTextAIModel.first())
            else -> provider.supportedModelOrDefault(prefs.selectedAIModel.first())
        }
        val baseUrl = prefs.customBaseUrl(provider).first()?.takeIf { it.isNotEmpty() } ?: provider.baseUrl
        val apiKey = keyStore.apiKey(provider)
        val maxTokens = prefs.maxResponseTokens.first()
        val requestTimeoutSeconds = prefs.aiRequestTimeoutSeconds.first()

        fun reply(text: String, used: AIProvider) = CoachReply(
            text,
            if (used == AIProvider.LOCAL_GEMMA) records?.onDeviceRefs.orEmpty() else records?.tools?.readRefs.orEmpty()
        )
        return try {
            reply(
                runProvider(
                    provider, model, baseUrl, apiKey, systemPrompt, history, newUserMessage,
                    tools, imageBytes, maxTokens, requestTimeoutSeconds, localSystemPrompt
                ),
                provider
            )
        } catch (primaryError: Throwable) {
            if (primaryError is kotlinx.coroutines.CancellationException) throw primaryError
            if (primaryError is RecordsCoachSwitchToOnDevice) throw primaryError
            val fallback = currentFallbackConfig(
                hasImage = imageBytes != null,
                primary = provider,
                primaryModel = model,
                primaryBaseUrl = baseUrl
            ) ?: throw primaryError
            reply(
                runProvider(
                    fallback.provider, fallback.model, fallback.baseUrl, fallback.apiKey,
                    systemPrompt, history, newUserMessage, tools, imageBytes, maxTokens,
                    requestTimeoutSeconds, localSystemPrompt
                ),
                fallback.provider
            )
        }
    }

    /** Records tools run through their suspend executor; every other tool through [CoachTools.execute]. */
    private suspend fun executeTool(tools: CoachTools, name: String, args: JSONObject): String =
        tools.executeRecords(name, jsonToMap(args))
            ?: tools.executeMedications(name, jsonToMap(args))
            ?: tools.execute(name, args)

    private fun jsonToMap(o: JSONObject): Map<String, Any?> {
        val out = LinkedHashMap<String, Any?>()
        val keys = o.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            out[k] = o.opt(k).takeUnless { it == JSONObject.NULL }
        }
        return out
    }

    /** Records schemas are the contract's compact text (key order kept); others come from the map. */
    private fun schemaObject(tools: CoachTools, name: String): JSONObject =
        tools.rawSchemaFor(name)?.let { JSONObject(it) } ?: JSONObject(CoachTools.parameterSchemaFor(name))

    private suspend fun runProvider(
        provider: AIProvider,
        model: String,
        baseUrl: String,
        apiKey: String?,
        systemPrompt: String,
        history: List<CoachMessage>,
        newUserMessage: String,
        tools: CoachTools,
        imageBytes: ByteArray?,
        maxTokens: Int,
        requestTimeoutSeconds: Int,
        localSystemPrompt: String = systemPrompt
    ): String {
        if (provider == AIProvider.LOCAL_GEMMA) {
            val conversation = buildString {
                history.takeLast(12).forEach { message ->
                    append(if (message.role == CoachMessage.Role.USER) "User: " else "Assistant: ")
                    appendLine(message.content)
                }
                append("User: ")
                append(newUserMessage)
            }
            return localGemma?.generate(
                prompt = conversation,
                images = imageBytes?.let(::listOf).orEmpty(),
                maxOutputTokens = maxTokens,
                systemInstruction = localSystemPrompt
            ) ?: throw AiError.Api("The on-device Gemma runtime is unavailable.")
        }
        if (provider.requiresApiKey && apiKey.isNullOrEmpty()) throw AiError.NoApiKey
        if (baseUrl.isEmpty()) throw AiError.InvalidUrl(baseUrl)
        val requestClient = FoodAnalysisService.clientForProvider(okHttp, provider, requestTimeoutSeconds)
        return when (provider.apiFormat) {
            AIProvider.ApiFormat.GEMINI -> runGeminiToolLoop(requestClient, baseUrl, model, apiKey!!, systemPrompt, history, newUserMessage, tools, imageBytes)
            AIProvider.ApiFormat.ANTHROPIC -> runAnthropicToolLoop(requestClient, baseUrl, model, apiKey!!, systemPrompt, history, newUserMessage, tools, imageBytes, maxTokens)
            AIProvider.ApiFormat.OPENAI_COMPATIBLE -> runOpenAIToolLoop(requestClient, baseUrl, model, apiKey, systemPrompt, history, newUserMessage, provider, tools, imageBytes, maxTokens)
            AIProvider.ApiFormat.LOCAL -> error("Local inference must be dispatched before network setup.")
        }
    }

    private suspend fun currentFallbackConfig(
        hasImage: Boolean,
        primary: AIProvider,
        primaryModel: String,
        primaryBaseUrl: String
    ): ChatFallbackConfig? {
        prefs.migrateFallbackBaseUrls()
        val enabled = if (hasImage) prefs.fallbackEnabled.first() else prefs.textFallbackEnabled.first()
        if (!enabled) return null
        val provider = if (hasImage) {
            prefs.selectedFallbackProvider.first()
        } else {
            prefs.selectedTextFallbackProvider.first()
        }
        val model = if (hasImage) {
            provider.supportedModelOrDefault(prefs.selectedFallbackModel.first())
        } else {
            provider.supportedTextModelOrDefault(prefs.selectedTextFallbackModel.first())
        }
        val baseUrl = prefs.fallbackCustomBaseUrl(provider).first()?.takeIf { it.isNotEmpty() } ?: provider.baseUrl
        if (provider == primary && model == primaryModel && baseUrl == primaryBaseUrl) return null
        val apiKey = keyStore.apiKey(provider)
        if (provider.requiresApiKey && apiKey.isNullOrEmpty()) return null
        if (provider != AIProvider.LOCAL_GEMMA && baseUrl.isEmpty()) return null
        return ChatFallbackConfig(provider, model, baseUrl, apiKey)
    }

    private data class ChatFallbackConfig(
        val provider: AIProvider,
        val model: String,
        val baseUrl: String,
        val apiKey: String?
    )

    // MARK: - Slim system prompt

    private fun buildSystemPrompt(
        profile: UserProfile,
        weights: List<WeightEntry>,
        bodyFats: List<BodyFatEntry>,
        measurements: List<BodyMeasurement> = emptyList(),
        foods: List<FoodEntry>,
        fastingSessions: List<FastingSession> = emptyList(),
        heightMetric: Boolean,
        weightMetric: Boolean,
        workoutSessions: List<WorkoutSession> = emptyList(),
        workoutPlans: List<WorkoutDayPlan> = emptyList(),
        healthSnapshot: HealthCoachSnapshot? = null,
        healthHubEnabled: Boolean = false,
        recordsLines: List<String> = emptyList()
    ): String {
        val forecast: WeightForecast = WeightAnalysisService.compute(weights, foods, profile)
        val zone = ZoneId.systemDefault()
        val currentDate = LocalDate.now(zone).format(DateTimeFormatter.ISO_LOCAL_DATE)
        val currentTimeZone = zone.id

        fun wUnit(kg: Double): String =
            if (weightMetric) String.format(Locale.US, "%.1f kg", kg)
            else String.format(Locale.US, "%.1f lbs", kg * 2.20462)

        fun weekly(kg: Double): String =
            if (weightMetric) String.format(Locale.US, "%+.2f kg/week", kg)
            else String.format(Locale.US, "%+.2f lbs/week", kg * 2.20462)

        val bmrFormula = when {
            profile.usesBodyFatForBMR -> "Katch-McArdle (uses body fat %)"
            profile.bodyFatPercentage != null -> "Mifflin-St Jeor (user disabled the body-fat override in Settings)"
            else -> "Mifflin-St Jeor (body fat not set)"
        }

        val lines = mutableListOf<String>()
        lines.add("You are Ayuvo, the user's health companion inside the Ayuvo app. You help with nutrition, weight change, strength training, sleep, heart health, fasting and hydration, always grounded in the user's own logged data. Answer in plain English, be specific and factual. Avoid medical advice; when relevant, suggest consulting a doctor. Be concise — 2–5 sentences per response unless the user asks for detail.")
        lines.add("")
        lines.add("## Current date")
        lines.add("- Today: $currentDate ($currentTimeZone)")
        lines.add("- Treat \"today\" as $currentDate when choosing tool date ranges.")
        lines.add("")
        lines.add("## How to use the data tools")
        lines.add("You have access to functions that fetch the user's history on demand. The user profile + formulas + forecast below cover what's needed for most questions. Call a tool ONLY when the user asks about specific past dates, longer time ranges, individual meals, or trends that need raw data. Examples:")
        lines.add("- \"How was my weight in March?\" → call get_weight_history(from, to)")
        lines.add("- \"What did I eat last Tuesday?\" → call get_food_entries(from, to)")
        lines.add("- \"How consistent has my fasting been?\" → call get_fasting_history(from, to)")
        lines.add("- \"What's my data range?\" → call get_data_summary")
        lines.add("- \"How is my training progressing?\" → call get_training_summary(from, to), then get_workout_history only if individual sets are needed")
        lines.add("- \"What did I bench last?\" / one-lift trends → call get_exercise_lift_history(exercise)")
        lines.add("- \"What workout do I have planned?\" → call get_workout_plans")
        lines.add("- Use get_workout_preferences when injuries, available equipment, split, schedule, RPE scale, or strength baselines affect the answer.")
        if (healthSnapshot != null) {
            lines.add("- \"How did I sleep this week?\" → call get_sleep_history(from, to)")
            lines.add("- \"Average resting heart rate in March\" / step or heart-rate trends → call get_health_data_types first, then get_health_summary(data_type, from, to)")
            lines.add("- One specific blood-pressure reading or workout → call get_health_samples(data_type, from, to)")
        }
        lines.add("Do NOT call tools for questions you can answer from the profile/forecast below.")
        lines.add("")
        lines.add("## User profile")
        lines.add("- Gender: ${profile.gender.name.lowercase()}")
        lines.add("- Age: ${profile.age}")
        val heightStr = if (heightMetric) String.format(Locale.US, "%.0f cm", profile.heightCm)
        else String.format(Locale.US, "%.1f in", profile.heightCm / 2.54)
        lines.add("- Height: $heightStr")
        lines.add("- Current weight: ${wUnit(profile.weightKg)}")
        lines.add("- Activity: ${activityEnglish(profile.activityLevel)}")
        lines.add("- Goal: ${goalEnglish(profile.goal)}")
        profile.goalWeightKg?.let { lines.add("- Goal weight: ${wUnit(it)}") }
        profile.bodyFatPercentage?.let { lines.add("- Body fat: ${(it * 100).toInt()}%") }
        profile.goalBodyFatPercentage?.let { lines.add("- Goal body fat: ${(it * 100).toInt()}%") }
        lines.add("")
        lines.add("## Formulas in use")
        lines.add("- BMR: $bmrFormula. Current BMR ≈ ${profile.bmr.toInt()} kcal/day")
        lines.add("- TDEE: BMR × activity multiplier ≈ ${profile.tdee.toInt()} kcal/day")
        lines.add("- Calorie goal: ${profile.effectiveCalories} kcal/day")
        lines.add("- Macro targets: ${profile.effectiveProtein}g protein, ${profile.effectiveCarbs}g carbs, ${profile.effectiveFat}g fat")
        lines.add("")
        lines.add("## Computed forecast (from their logged data)")
        if (forecast.hasEnoughData) {
            lines.add("- Days of food logged (last 90d): ${forecast.daysOfFoodData}")
            lines.add("- Weight entries available: ${forecast.weightEntriesUsed}")
            lines.add("- Avg daily intake: ${forecast.avgDailyCalories} kcal")
            val balanceSign = if (forecast.dailyEnergyBalance >= 0) "+" else ""
            lines.add("- Daily energy balance: ${balanceSign}${forecast.dailyEnergyBalance} kcal")
            lines.add("- Predicted change (from diet): ${weekly(forecast.predictedWeeklyChangeKg)}")
            forecast.observedWeeklyChangeKg?.let { lines.add("- Observed change (from scale): ${weekly(it)}") }
            lines.add("- Expected weight in 30 days: ${wUnit(forecast.predictedWeight30dKg)}")
            lines.add("- Expected weight in 60 days: ${wUnit(forecast.predictedWeight60dKg)}")
            lines.add("- Expected weight in 90 days: ${wUnit(forecast.predictedWeight90dKg)}")
            forecast.daysToGoal?.let { lines.add("- Days to goal at current pace: ~$it days") }
            if (forecast.trendsDisagree) {
                lines.add("- NOTE: Predicted and observed trends differ by >0.3 kg/week — user may be under-logging food.")
            }
        } else {
            lines.add("- Not enough data yet (need ≥2 days food + ≥2 weights). Encourage the user to log more.")
        }
        lines.add("")
        lines.add("## Data available")
        lines.add("- ${weights.size} weight entries, ${bodyFats.size} body-fat readings, ${foods.size} food entries logged total. Use get_data_summary to see exact date ranges.")
        lines.add("- ${fastingSessions.size} explicitly tracked fasting sessions are available. Never treat a missing food log as a fast.")
        lines.add("- ${workoutSessions.size} completed strength workouts and ${workoutPlans.size} dated workout plans are available through the workout tools.")
        lines.add("- Workout logs may guide training, recovery, exercise selection, and progressive-overload advice. Never use estimated workout burn or workout volume to recalculate calorie/macro targets, alter the nutrition forecast, or invent energy expenditure.")
        when {
            healthSnapshot != null -> {
                val lastSync = healthSnapshot.lastSyncMs?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDateTime().withNano(0).toString() } ?: "never"
                lines.add("- ${healthSnapshot.types.size} health data types synced from ${healthSnapshot.platform} (last synced $lastSync). Call get_health_data_types first to learn the exact data_type keys.")
                lines.add("- Platform active energy already includes Ayuvo's own workout estimates (reported as own_sum). Never add Ayuvo workout burn on top of platform active energy; subtract own_sum when you need external burn only.")
                lines.add("- Use the diary tools (get_calorie_totals, get_food_entries) for intake, never dietary_* health types.")
                lines.add("- Never diagnose from heart rate, blood pressure, or glucose values: describe patterns plainly and suggest a clinician for anything concerning.")
            }
            healthHubEnabled -> lines.add("- Health data from Health Connect is not available to Coach (the user turned Coach access off in Settings › Health & Data).")
            else -> lines.add("- No health platform data is available; nutrition, weight, fasting, and workout tools still work.")
        }
        // Health Records (§26, §32): continues the `## Data available` list.
        lines.addAll(recordsLines)
        measurements.maxByOrNull { it.date }?.promptSummary(profile.gender, profile.heightCm)?.let { summary ->
            lines.add("")
            lines.add("## Body measurements (latest)")
            lines.add("- $summary")
            lines.add("A shrinking waist alongside steady or rising weight is recomposition (fat down, muscle up) — read it that way instead of calling a flat scale a plateau. Treat the US-Navy body-fat figure as an estimate.")
        }
        lines.add("")
        lines.add("When the user asks how to lose or gain, give a concrete calorie target and at least one actionable food or activity change. When they ask expected weight, reference the forecast numbers above.")
        return lines.joinToString("\n")
    }

    // MARK: - OpenAI-compatible tool loop (10 of 13 providers)

    private suspend fun runOpenAIToolLoop(
        requestClient: OkHttpClient,
        baseUrl: String,
        model: String,
        apiKey: String?,
        systemPrompt: String,
        history: List<CoachMessage>,
        newUserMessage: String,
        provider: AIProvider,
        tools: CoachTools,
        imageBytes: ByteArray?,
        maxTokens: Int
    ): String {
        val reasoningEffort = prefs.openRouterReasoningEffort.first()
        val url = "$baseUrl/chat/completions"
        // OpenAI tool schema: {type:function, function:{name, description, parameters}}
        val toolsArr = JSONArray()
        for (name in tools.advertisedToolNames) {
            toolsArr.put(JSONObject().apply {
                put("type", "function")
                put("function", JSONObject().apply {
                    put("name", name)
                    put("description", tools.descriptionFor(name))
                    put("parameters", schemaObject(tools, name))
                })
            })
        }
        // Mutable message list: system + history + new user message; grows with
        // assistant tool-call turns + role:tool result rows on each loop pass.
        val messages = JSONArray()
        messages.put(JSONObject().put("role", "system").put("content", systemPrompt))
        for (msg in history) {
            val role = if (msg.role == CoachMessage.Role.USER) "user" else "assistant"
            messages.put(JSONObject().put("role", role).put("content", msg.content))
        }
        messages.put(JSONObject().put("role", "user").put("content", openAIUserContent(newUserMessage, imageBytes)))

        repeat(MAX_TOOL_ROUNDS) {
            suspend fun request(compactRetry: Boolean): Pair<JSONObject, JSONObject> {
                val body = JSONObject().apply {
                    put("model", model)
                    put("messages", messages)
                    put("tools", toolsArr)
                    put("tool_choice", "auto")
                    put(OpenAICompatibleClient.tokenLimitParameter(provider, model), maxTokens)
                    if (provider == AIProvider.OPENROUTER) {
                        reasoningEffort.requestOptions(compactRetry, exclude = false)?.let {
                            put("reasoning", JSONObject(it))
                        }
                    }
                }
                val builder = Request.Builder()
                    .url(url)
                    .addHeader("Content-Type", "application/json")
                    .post(body.toString().toRequestBody(JSON_MEDIA))
                if (!apiKey.isNullOrEmpty()) builder.addHeader("Authorization", "Bearer $apiKey")
                if (provider == AIProvider.OPENROUTER) {
                    builder.addHeader("X-Title", "Ayuvo")
                }
                val raw = RetryPolicy.execute { requestClient.newCall(builder.build()) }
                val json = runCatching { JSONObject(raw) }.getOrNull() ?: throw AiError.InvalidResponse
                val error = json.optJSONObject("error")?.optString("message")?.takeIf { it.isNotBlank() }
                val choice = json.optJSONArray("choices")?.optJSONObject(0)
                    ?: throw if (error != null) AiError.Api(error) else AiError.InvalidResponse
                if (choice.optString("finish_reason") == "error") {
                    throw AiError.Api(error ?: "The AI provider returned an error.")
                }
                val message = choice.optJSONObject("message") ?: throw AiError.InvalidResponse
                return choice to message
            }

            var (choice, message) = request(compactRetry = false)
            val hasToolCalls = (message.optJSONArray("tool_calls")?.length() ?: 0) > 0
            val hasContent = message.optString("content").isNotBlank()
            val hasReasoning = message.optString("reasoning").isNotBlank() ||
                message.optString("reasoning_content").isNotBlank() ||
                (message.optJSONArray("reasoning_details")?.length() ?: 0) > 0
            if (choice.optString("finish_reason") == "length" || (!hasToolCalls && !hasContent && hasReasoning)) {
                val retry = request(compactRetry = true)
                choice = retry.first
                message = retry.second
                if (choice.optString("finish_reason") == "length") {
                    throw AiError.Api("The AI response was truncated twice. Try a shorter question or another model.")
                }
            }

            // Tool calls take precedence; loop until the model returns plain content.
            val toolCalls = message.optJSONArray("tool_calls")
            if (toolCalls != null && toolCalls.length() > 0) {
                messages.put(message)
                for (i in 0 until toolCalls.length()) {
                    val call = toolCalls.optJSONObject(i) ?: continue
                    val function = call.optJSONObject("function") ?: continue
                    val name = function.optString("name").takeIf { it.isNotEmpty() } ?: continue
                    val id = call.optString("id").takeIf { it.isNotEmpty() } ?: continue
                    val argsString = function.optString("arguments", "{}")
                    val args = runCatching { JSONObject(argsString) }.getOrNull() ?: JSONObject()
                    val result = executeTool(tools, name, args)
                    messages.put(JSONObject().apply {
                        put("role", "tool")
                        put("tool_call_id", id)
                        put("content", result)
                    })
                }
                return@repeat
            }
            val content = message.optString("content")
            if (content.isNotEmpty()) return content.trim()
            throw AiError.InvalidResponse
        }
        throw AiError.Api("Coach exceeded the tool-call round limit. Try rephrasing your question.")
    }

    // MARK: - Anthropic tool loop

    private suspend fun runAnthropicToolLoop(
        requestClient: OkHttpClient,
        baseUrl: String,
        model: String,
        apiKey: String,
        systemPrompt: String,
        history: List<CoachMessage>,
        newUserMessage: String,
        tools: CoachTools,
        imageBytes: ByteArray?,
        maxTokens: Int
    ): String {
        val url = "$baseUrl/messages"
        // Anthropic tool schema: {name, description, input_schema}
        val toolsArr = JSONArray()
        for (name in tools.advertisedToolNames) {
            toolsArr.put(JSONObject().apply {
                put("name", name)
                put("description", tools.descriptionFor(name))
                put("input_schema", schemaObject(tools, name))
            })
        }
        // History as plain text role:content; tool_use / tool_result blocks
        // get appended into messages as the loop runs.
        val messages = JSONArray()
        for (msg in history) {
            val role = if (msg.role == CoachMessage.Role.USER) "user" else "assistant"
            messages.put(JSONObject().put("role", role).put("content", msg.content))
        }
        messages.put(JSONObject().put("role", "user").put("content", anthropicUserContent(newUserMessage, imageBytes)))

        repeat(MAX_TOOL_ROUNDS) {
            val body = JSONObject().apply {
                put("model", model)
                put("max_tokens", maxTokens)
                put("system", systemPrompt)
                put("tools", toolsArr)
                put("messages", messages)
            }
            val raw = RetryPolicy.execute {
                requestClient.newCall(
                    Request.Builder()
                        .url(url)
                        .addHeader("Content-Type", "application/json")
                        .addHeader("x-api-key", apiKey)
                        .addHeader("anthropic-version", "2023-06-01")
                        .post(body.toString().toRequestBody(JSON_MEDIA))
                        .build()
                )
            }
            val json = runCatching { JSONObject(raw) }.getOrNull() ?: throw AiError.InvalidResponse
            val contentArr = json.optJSONArray("content") ?: throw AiError.InvalidResponse

            // Collect any tool_use blocks; if present, run them and loop with
            // a new user-role message containing tool_result blocks.
            val toolUses = mutableListOf<JSONObject>()
            for (i in 0 until contentArr.length()) {
                val block = contentArr.optJSONObject(i) ?: continue
                if (block.optString("type") == "tool_use") toolUses.add(block)
            }
            if (toolUses.isNotEmpty()) {
                messages.put(JSONObject().put("role", "assistant").put("content", contentArr))
                val toolResults = JSONArray()
                for (use in toolUses) {
                    val id = use.optString("id").takeIf { it.isNotEmpty() } ?: continue
                    val name = use.optString("name").takeIf { it.isNotEmpty() } ?: continue
                    val input = use.optJSONObject("input") ?: JSONObject()
                    val result = executeTool(tools, name, input)
                    toolResults.put(JSONObject().apply {
                        put("type", "tool_result")
                        put("tool_use_id", id)
                        put("content", result)
                    })
                }
                messages.put(JSONObject().put("role", "user").put("content", toolResults))
                return@repeat
            }
            // No tool calls — first text block is the answer.
            for (i in 0 until contentArr.length()) {
                val block = contentArr.optJSONObject(i) ?: continue
                if (block.optString("type") == "text") {
                    val text = block.optString("text").trim()
                    if (text.isNotEmpty()) return text
                }
            }
            throw AiError.InvalidResponse
        }
        throw AiError.Api("Coach exceeded the tool-call round limit. Try rephrasing your question.")
    }

    // MARK: - Gemini tool loop

    private suspend fun runGeminiToolLoop(
        requestClient: OkHttpClient,
        baseUrl: String,
        model: String,
        apiKey: String,
        systemPrompt: String,
        history: List<CoachMessage>,
        newUserMessage: String,
        tools: CoachTools,
        imageBytes: ByteArray?
    ): String {
        val url = "$baseUrl/models/$model:generateContent"
        // Gemini tool schema: tools=[{functionDeclarations:[{name,description,parameters}]}]
        val declarations = JSONArray()
        for (name in tools.advertisedToolNames) {
            declarations.put(JSONObject().apply {
                put("name", name)
                put("description", tools.descriptionFor(name))
                put("parameters", schemaObject(tools, name))
            })
        }
        val toolsObj = JSONObject().put("functionDeclarations", declarations)

        // Build the contents list (history + new user). Each turn's parts may
        // be either text or function_call / function_response.
        val contents = JSONArray()
        for (msg in history) {
            val role = if (msg.role == CoachMessage.Role.USER) "user" else "model"
            contents.put(JSONObject().apply {
                put("role", role)
                put("parts", JSONArray().put(JSONObject().put("text", msg.content)))
            })
        }
        contents.put(JSONObject().apply {
            put("role", "user")
            put("parts", geminiUserParts(newUserMessage, imageBytes))
        })

        repeat(MAX_TOOL_ROUNDS) {
            val body = JSONObject().apply {
                put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", systemPrompt))))
                put("contents", contents)
                put("tools", JSONArray().put(toolsObj))
            }
            val raw = RetryPolicy.execute {
                requestClient.newCall(
                    Request.Builder()
                        .url(url)
                        .addHeader("Content-Type", "application/json")
                        .addHeader("X-goog-api-key", apiKey)
                        .post(body.toString().toRequestBody(JSON_MEDIA))
                        .build()
                )
            }
            val json = runCatching { JSONObject(raw) }.getOrNull() ?: throw AiError.InvalidResponse
            val candidate = json.optJSONArray("candidates")?.optJSONObject(0) ?: throw AiError.InvalidResponse
            val content = candidate.optJSONObject("content") ?: throw AiError.InvalidResponse
            val parts = content.optJSONArray("parts") ?: throw AiError.InvalidResponse

            val functionCalls = mutableListOf<JSONObject>()
            val texts = StringBuilder()
            for (i in 0 until parts.length()) {
                val part = parts.optJSONObject(i) ?: continue
                part.optJSONObject("functionCall")?.let { functionCalls.add(it) }
                part.optString("text").takeIf { it.isNotEmpty() }?.let { texts.append(it) }
            }
            if (functionCalls.isNotEmpty()) {
                contents.put(JSONObject().apply { put("role", "model"); put("parts", parts) })
                val responseParts = JSONArray()
                for (call in functionCalls) {
                    val name = call.optString("name").takeIf { it.isNotEmpty() } ?: continue
                    val args = call.optJSONObject("args") ?: JSONObject()
                    val resultStr = executeTool(tools, name, args)
                    val resultObj = runCatching { JSONObject(resultStr) }.getOrNull() ?: JSONObject()
                    responseParts.put(JSONObject().apply {
                        put("functionResponse", JSONObject().apply {
                            put("name", name)
                            put("response", JSONObject().put("content", resultObj))
                            call.optString("id").takeIf { it.isNotEmpty() }?.let { put("id", it) }
                        })
                    })
                }
                contents.put(JSONObject().apply { put("role", "user"); put("parts", responseParts) })
                return@repeat
            }
            val combined = texts.toString().trim()
            if (combined.isNotEmpty()) return combined
            throw AiError.InvalidResponse
        }
        throw AiError.Api("Coach exceeded the tool-call round limit. Try rephrasing your question.")
    }

    private fun openAIUserContent(text: String, imageBytes: ByteArray?): Any {
        if (imageBytes == null) return text
        return JSONArray().apply {
            put(JSONObject().put("type", "text").put("text", text))
            put(
                JSONObject()
                    .put("type", "image_url")
                    .put(
                        "image_url",
                        JSONObject().put("url", "data:image/jpeg;base64,${base64(imageBytes)}")
                    )
            )
        }
    }

    private fun anthropicUserContent(text: String, imageBytes: ByteArray?): Any {
        if (imageBytes == null) return text
        return JSONArray().apply {
            put(JSONObject().put("type", "text").put("text", text))
            put(
                JSONObject()
                    .put("type", "image")
                    .put(
                        "source",
                        JSONObject()
                            .put("type", "base64")
                            .put("media_type", "image/jpeg")
                            .put("data", base64(imageBytes))
                    )
            )
        }
    }

    private fun geminiUserParts(text: String, imageBytes: ByteArray?): JSONArray =
        JSONArray().apply {
            imageBytes?.let {
                put(
                    JSONObject().put(
                        "inlineData",
                        JSONObject()
                            .put("mimeType", "image/jpeg")
                            .put("data", base64(it))
                    )
                )
            }
            put(JSONObject().put("text", text))
        }

    private fun base64(bytes: ByteArray): String =
        Base64.getEncoder().encodeToString(bytes)

    // MARK: - English label helpers (LLM input — not localized)

    private fun activityEnglish(level: ActivityLevel): String = when (level) {
        ActivityLevel.SEDENTARY -> "Sedentary"
        ActivityLevel.LIGHT -> "Light"
        ActivityLevel.MODERATE -> "Moderate"
        ActivityLevel.ACTIVE -> "Active"
        ActivityLevel.VERY_ACTIVE -> "Very Active"
        ActivityLevel.EXTRA_ACTIVE -> "Extra Active"
    }

    private fun goalEnglish(goal: WeightGoal): String = when (goal) {
        WeightGoal.LOSE -> "Lose Weight"
        WeightGoal.MAINTAIN -> "Maintain"
        WeightGoal.GAIN -> "Gain Weight"
    }

    @Suppress("unused")
    private fun Instant.toLocalDateInZone() = this.atZone(ZoneId.systemDefault()).toLocalDate()

    @Suppress("unused")
    private val dateFmt: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM d").withZone(ZoneId.systemDefault())

    companion object {
        /**
         * §32 layout of the records part of `## Data available` (appended after its last list item):
         * `- <available_line>`, a blank line, the guardrails; with a selection a blank line, then
         * `selected_header` and its `- ` lines. Access off: `- <not_available_line>` only.
         */
        internal fun recordsDataLines(turn: RecordsTurn): List<String> = buildList {
            turn.notAvailableLine?.let { add("- $it") }
            turn.availableLine?.let { add("- $it") }
            turn.guardrails?.let { add(""); add(it) }
            if (turn.selectedLines.isNotEmpty()) { add(""); addAll(turn.selectedLines) }
        }

        /** §29/§32 on-device prompt tail: packed block, one blank line, guardrails. */
        internal fun onDeviceRecordsTail(block: String, guardrails: String?): String =
            listOfNotNull(block, guardrails).joinToString("\n\n")

        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
        private const val MAX_TOOL_ROUNDS = 6
    }
}
