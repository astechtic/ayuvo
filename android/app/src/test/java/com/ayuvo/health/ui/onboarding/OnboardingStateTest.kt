package com.ayuvo.health.ui.onboarding

import com.ayuvo.health.models.AIProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OnboardingStateTest {

    @Test
    fun restoredOnboardingHasNothingToGoBackToAtTheFirstRemainingStep() {
        val first = OnboardingState(step = RESTORED_STEPS.first(), restored = true)
        assertFalse(first.canGoBack)
        assertTrue(OnboardingState(step = OnboardingStep.HEALTH_CONNECT, restored = true).canGoBack)
        // A normal onboarding can still go back from the same step.
        assertTrue(OnboardingState(step = OnboardingStep.NOTIFICATIONS).canGoBack)
        assertFalse(OnboardingState(step = OnboardingStep.WELCOME).canGoBack)
    }

    @Test
    fun restoredProgressMeasuresOnlyTheRemainingSteps() {
        assertEquals(RESTORED_STEPS, listOf(OnboardingStep.NOTIFICATIONS, OnboardingStep.HEALTH_CONNECT, OnboardingStep.PROVIDER))
        assertEquals(0.25f, OnboardingState(step = OnboardingStep.NOTIFICATIONS, restored = true).progress, 0.0001f)
        assertEquals(0.5f, OnboardingState(step = OnboardingStep.HEALTH_CONNECT, restored = true).progress, 0.0001f)
        assertEquals(0.75f, OnboardingState(step = OnboardingStep.PROVIDER, restored = true).progress, 0.0001f)
        // Unrestored: ordinal over the full step list.
        assertEquals(
            OnboardingStep.PROVIDER.ordinal / (OnboardingStep.values().size - 1f),
            OnboardingState(step = OnboardingStep.PROVIDER).progress,
            0.0001f
        )
    }

    @Test
    fun providerChoiceBlocksContinue() {
        val state = OnboardingState(
            step = OnboardingStep.PROVIDER,
            aiPhase = OnboardingAiPhase.CHOICE
        )
        assertFalse(state.canAdvance)
    }

    @Test
    fun byokWithoutKeyBlocksContinueForGemini() {
        val state = OnboardingState(
            step = OnboardingStep.PROVIDER,
            aiPhase = OnboardingAiPhase.BYOK,
            aiProvider = AIProvider.GEMINI,
            apiKey = ""
        )
        assertFalse(state.canAdvance)
        assertFalse(state.byokSetupComplete)
    }

    @Test
    fun byokWithKeyEnablesContinue() {
        val state = OnboardingState(
            step = OnboardingStep.PROVIDER,
            aiPhase = OnboardingAiPhase.BYOK,
            aiProvider = AIProvider.GEMINI,
            apiKey = "  test-key  "
        )
        assertTrue(state.byokSetupComplete)
        assertTrue(state.canAdvance)
    }

    @Test
    fun byokOllamaDoesNotRequireKey() {
        val state = OnboardingState(
            step = OnboardingStep.PROVIDER,
            aiPhase = OnboardingAiPhase.BYOK,
            aiProvider = AIProvider.OLLAMA,
            apiKey = ""
        )
        assertTrue(state.byokSetupComplete)
        assertTrue(state.canAdvance)
    }

    @Test
    fun providerStepReopensByokWhenConfigured() {
        val configured = OnboardingState(
            step = OnboardingStep.PLAN_READY,
            aiPhase = OnboardingAiPhase.BYOK,
            aiProvider = AIProvider.GEMINI,
            apiKey = "saved"
        )
        assertTrue(configured.aiPhaseForProviderStep() == OnboardingAiPhase.BYOK)

        val fresh = OnboardingState(step = OnboardingStep.PROVIDER)
        assertTrue(fresh.aiPhaseForProviderStep() == OnboardingAiPhase.CHOICE)
    }
}
