package com.ayuvo.health.nutrients

/**
 * Prompt assembly for "Get nutrients with AI" (shared/nutrients/ai_supplement_label.md): the cloud
 * or compact on-device system prompt, then the photo or name-and-strength template filled by plain
 * replacement. The answer goes through [NutrientLabel.parse]; nothing is saved without review.
 */
object SupplementLabelAi {
    const val CLOUD_MAX_TOKENS = 800
    /** Prompt v2: a full multivitamin label has 20+ items. */
    const val LOCAL_MAX_TOKENS = 600

    fun prompt(prompts: LabelPrompts, local: Boolean, photo: Boolean, name: String, strength: String, doseUnit: String): String {
        val system = if (local) prompts.local else prompts.cloud
        val user = prompts.fill(if (photo) prompts.userPhoto else prompts.userText, name, strength, doseUnit)
        return "$system\n\n$user"
    }

    fun maxTokens(local: Boolean, contextTokens: Int? = null): Int =
        if (local) minOf(LOCAL_MAX_TOKENS, contextTokens?.takeIf { it > 0 }?.div(4) ?: LOCAL_MAX_TOKENS) else CLOUD_MAX_TOKENS
}
