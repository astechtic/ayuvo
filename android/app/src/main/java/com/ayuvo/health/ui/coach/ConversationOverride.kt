package com.ayuvo.health.ui.coach

import com.ayuvo.health.medications.logic.MedicationJson.str
import com.ayuvo.health.models.AIProvider
import com.ayuvo.health.services.ai.AIReference

/**
 * A conversation's own model choice, as `conversations.provider_override` holds it
 * (docs/ai-models.md §8).
 *
 * Two forms live in that one column: a bare provider token, which the Health-Records "Use on-device
 * Coach" flow has always written, and `profile:<id>|<token>` from the model picker. The provider is
 * carried after the id so a conversation imported from another device -- where that profile id means
 * nothing -- degrades to the provider instead of quietly answering on the wrong model.
 */
data class ConversationOverride(
    val profileId: String? = null,
    val provider: AIProvider? = null,
) {
    val isSet: Boolean get() = profileId != null || provider != null

    fun encoded(): String? = AIReference.encodeOverride(profileId, provider?.token)

    companion object {
        fun parse(raw: String?): ConversationOverride {
            val parsed = AIReference.parseOverride(raw)
            return ConversationOverride(
                profileId = parsed.str("profile_id"),
                provider = AIProvider.fromToken(parsed.str("provider")),
            )
        }
    }
}

/** One saved model as the Coach picker lists it (docs/ai-models.md §8). */
data class CoachModelChoice(
    val id: String,
    val name: String,
    val providerToken: String,
    val provider: AIProvider?,
    val model: String,
)
