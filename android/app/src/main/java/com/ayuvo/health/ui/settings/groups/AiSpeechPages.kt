package com.ayuvo.health.ui.settings.groups

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Numbers
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Tune
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ayuvo.health.R
import com.ayuvo.health.models.AIProvider
import com.ayuvo.health.models.SpeechProvider
import com.ayuvo.health.services.ondevice.LocalModelId
import com.ayuvo.health.ui.design.AyuvoSpacing
import com.ayuvo.health.ui.design.GroupRow
import com.ayuvo.health.ui.design.InsetGroup
import com.ayuvo.health.ui.design.InsetGroupScope
import com.ayuvo.health.ui.design.RowTrailing
import com.ayuvo.health.ui.settings.AIProviderBrandIcon
import com.ayuvo.health.ui.settings.CustomInstructionsBlock
import com.ayuvo.health.ui.settings.LocalModelRow
import com.ayuvo.health.ui.settings.SettingsPage
import com.ayuvo.health.ui.settings.SettingsPageContext
import com.ayuvo.health.ui.settings.SettingsSheet
import com.ayuvo.health.ui.settings.SpeechProviderBrandIcon

@Composable
private fun BrandSlot(content: @Composable () -> Unit) {
    Box(Modifier.size(AyuvoSpacing.IconSize), contentAlignment = Alignment.Center) { content() }
}

/** Provider / model / key / endpoint rows for one AI route (primary, text, fallbacks). */
private fun InsetGroupScope.providerRows(
    ctx: SettingsPageContext,
    provider: AIProvider,
    model: String,
    keyMasked: String,
    providerSheet: SettingsSheet,
    modelSheet: SettingsSheet,
    keySheet: SettingsSheet,
    baseUrlSheet: SettingsSheet,
    showTimeout: Boolean,
    idPrefix: String
) {
    val tint = SettingsPage.AI_PROVIDERS.tint
    val state = ctx.state
    row {
        GroupRow(
            title = stringResource(R.string.settings_ai_provider),
            value = stringResource(provider.displayNameRes),
            leading = { BrandSlot { AIProviderBrandIcon(provider, Modifier.size(20.dp)) } },
            modifier = Modifier.settingsRow("${idPrefix}Provider"),
            onClick = { state.sheet = providerSheet }
        )
    }
    row {
        GroupRow(
            title = stringResource(R.string.settings_ai_model),
            value = model.ifEmpty { stringResource(R.string.settings_ai_model_unset) },
            icon = Icons.Filled.Tune, iconTint = tint,
            modifier = Modifier.settingsRow("${idPrefix}Model"),
            onClick = { state.sheet = modelSheet }
        )
    }
    if (provider.requiresApiKey) {
        row {
            GroupRow(
                title = stringResource(R.string.settings_api_key),
                value = keyMasked.ifEmpty { stringResource(R.string.settings_not_set) },
                icon = Icons.Filled.Key, iconTint = tint,
                modifier = Modifier.settingsRow("${idPrefix}Key"),
                onClick = { state.sheet = keySheet }
            )
        }
    }
    if (provider.requiresCustomEndpoint || provider == AIProvider.OLLAMA) {
        row {
            GroupRow(
                title = if (provider.requiresCustomEndpoint) stringResource(R.string.settings_base_url) else stringResource(R.string.settings_server_url),
                value = stringResource(R.string.settings_tap_to_edit),
                icon = Icons.Filled.Link, iconTint = tint,
                modifier = Modifier.settingsRow("${idPrefix}BaseUrl"),
                onClick = { state.sheet = baseUrlSheet }
            )
        }
        if (showTimeout) {
            row {
                GroupRow(
                    title = stringResource(R.string.settings_request_timeout),
                    value = stringResource(R.string.settings_seconds_format, ctx.ui.aiRequestTimeoutSeconds),
                    icon = Icons.Filled.Schedule, iconTint = tint,
                    modifier = Modifier.settingsRow("${idPrefix}Timeout"),
                    onClick = { state.sheet = SettingsSheet.REQUEST_TIMEOUT }
                )
            }
        }
    }
}

/** AI & Speech › AI Providers: on-device model, primary, text AI and the two fallbacks. */
@Composable
internal fun AiProvidersPage(ctx: SettingsPageContext) {
    val ui = ctx.ui
    val vm = ctx.vm
    val state = ctx.state
    val tint = SettingsPage.AI_PROVIDERS.tint
    ui.localModelStates[LocalModelId.GEMMA_4_E2B]?.let { localModel ->
        InsetGroup(
            header = stringResource(R.string.settings_on_device_models),
            footer = stringResource(R.string.settings_on_device_models_info)
        ) {
            row {
                LocalModelRow(
                    state = localModel,
                    onDownload = { vm.downloadLocalModel(localModel.descriptor.id) },
                    onDelete = { vm.deleteLocalModel(localModel.descriptor.id) }
                )
            }
        }
    }

    InsetGroup(
        header = stringResource(R.string.settings_section_ai),
        footer = stringResource(R.string.settings_info_primary_ai)
    ) {
        providerRows(
            ctx, ui.selectedAI, ui.selectedModel, ui.apiKeyMasked,
            SettingsSheet.AI_PROVIDER, SettingsSheet.AI_MODEL, SettingsSheet.API_KEY, SettingsSheet.CUSTOM_BASE_URL,
            showTimeout = true, idPrefix = "primary"
        )
        if (ui.selectedAI == AIProvider.OPENROUTER ||
            (ui.separateTextProviderEnabled && ui.selectedTextAI == AIProvider.OPENROUTER) ||
            (ui.fallbackEnabled && ui.fallbackProvider == AIProvider.OPENROUTER) ||
            (ui.textFallbackEnabled && ui.textFallbackProvider == AIProvider.OPENROUTER)
        ) {
            row {
                GroupRow(
                    title = stringResource(R.string.settings_reasoning_effort),
                    value = stringResource(ui.openRouterReasoningEffort.labelRes),
                    icon = Icons.Filled.Tune, iconTint = tint,
                    modifier = Modifier.settingsRow("reasoningEffort"),
                    onClick = { state.sheet = SettingsSheet.REASONING_EFFORT }
                )
            }
        }
        // Only OpenAI-compatible + Anthropic send a token cap; Gemini is left uncapped.
        if (ui.selectedAI.apiFormat != AIProvider.ApiFormat.GEMINI) {
            row {
                GroupRow(
                    title = stringResource(R.string.settings_max_tokens),
                    value = ui.maxResponseTokens.toString(),
                    icon = Icons.Filled.Numbers, iconTint = tint,
                    modifier = Modifier.settingsRow("maxTokens"),
                    onClick = { state.sheet = SettingsSheet.MAX_TOKENS }
                )
            }
        }
    }

    InsetGroup(
        header = stringResource(R.string.settings_section_text_ai),
        footer = stringResource(R.string.settings_info_text_ai)
    ) {
        row {
            GroupRow(
                title = stringResource(R.string.settings_use_separate_text_provider),
                icon = Icons.Filled.SmartToy, iconTint = tint,
                modifier = Modifier.settingsRow("separateTextProvider"),
                trailing = RowTrailing.Toggle(ui.separateTextProviderEnabled, vm::setSeparateTextProviderEnabled)
            )
        }
        if (ui.separateTextProviderEnabled) {
            providerRows(
                ctx, ui.selectedTextAI, ui.selectedTextModel, ui.textApiKeyMasked,
                SettingsSheet.TEXT_PROVIDER, SettingsSheet.TEXT_MODEL, SettingsSheet.TEXT_KEY, SettingsSheet.TEXT_BASE_URL,
                showTimeout = !ui.selectedAI.usesConfigurableRequestTimeout, idPrefix = "text"
            )
        }
    }

    InsetGroup(
        header = stringResource(R.string.settings_section_text_fallback),
        footer = stringResource(R.string.settings_info_text_fallback)
    ) {
        row {
            GroupRow(
                title = stringResource(R.string.settings_enable_fallback),
                icon = Icons.Filled.Refresh, iconTint = tint,
                modifier = Modifier.settingsRow("textFallback"),
                trailing = RowTrailing.Toggle(ui.textFallbackEnabled, vm::setTextFallbackEnabled)
            )
        }
        if (ui.textFallbackEnabled) {
            providerRows(
                ctx, ui.textFallbackProvider, ui.textFallbackModel, ui.textFallbackApiKeyMasked,
                SettingsSheet.TEXT_FALLBACK_PROVIDER, SettingsSheet.TEXT_FALLBACK_MODEL, SettingsSheet.TEXT_FALLBACK_KEY, SettingsSheet.TEXT_FALLBACK_BASE_URL,
                showTimeout = false, idPrefix = "textFallback"
            )
        }
    }

    InsetGroup(
        header = stringResource(R.string.settings_section_fallback),
        footer = stringResource(R.string.settings_info_image_fallback)
    ) {
        row {
            GroupRow(
                title = stringResource(R.string.settings_enable_fallback),
                icon = Icons.Filled.Refresh, iconTint = tint,
                modifier = Modifier.settingsRow("imageFallback"),
                trailing = RowTrailing.Toggle(ui.fallbackEnabled, { vm.setFallbackEnabled(it) })
            )
        }
        if (ui.fallbackEnabled) {
            providerRows(
                ctx, ui.fallbackProvider, ui.fallbackModel, ui.fallbackApiKeyMasked,
                SettingsSheet.FALLBACK_PROVIDER, SettingsSheet.FALLBACK_MODEL, SettingsSheet.FALLBACK_KEY, SettingsSheet.FALLBACK_BASE_URL,
                showTimeout = !ui.selectedAI.usesConfigurableRequestTimeout, idPrefix = "imageFallback"
            )
        }
    }
}

/** AI & Speech › Speech-to-Text: on-device Whisper, provider, language, key and fallback. */
@Composable
internal fun SpeechToTextPage(ctx: SettingsPageContext) {
    val ui = ctx.ui
    val vm = ctx.vm
    val state = ctx.state
    val tint = SettingsPage.SPEECH_TO_TEXT.tint
    ui.localModelStates[LocalModelId.WHISPER_BASE]?.let { localModel ->
        InsetGroup(
            header = stringResource(R.string.settings_on_device_models),
            footer = stringResource(R.string.settings_on_device_models_info)
        ) {
            row {
                LocalModelRow(
                    state = localModel,
                    onDownload = { vm.downloadLocalModel(localModel.descriptor.id) },
                    onDelete = { vm.deleteLocalModel(localModel.descriptor.id) }
                )
            }
        }
    }
    InsetGroup(
        header = stringResource(R.string.settings_section_speech),
        footer = stringResource(R.string.settings_info_speech_to_text)
    ) {
        row {
            GroupRow(
                title = stringResource(R.string.settings_ai_provider),
                value = stringResource(ui.selectedSpeech.displayNameRes),
                leading = { BrandSlot { SpeechProviderBrandIcon(ui.selectedSpeech, Modifier.size(20.dp)) } },
                modifier = Modifier.testTag("settings.speech.provider"),
                onClick = { state.sheet = SettingsSheet.SPEECH_PROVIDER }
            )
        }
        row {
            GroupRow(
                title = stringResource(R.string.settings_speech_language),
                value = stringResource(ui.selectedSpeechLanguage.displayNameRes),
                icon = Icons.Filled.Language, iconTint = tint,
                modifier = Modifier.settingsRow("speechLanguage"),
                onClick = { state.sheet = SettingsSheet.SPEECH_LANGUAGE }
            )
        }
        if (ui.selectedSpeech.requiresApiKey) {
            row {
                GroupRow(
                    title = stringResource(R.string.settings_api_key),
                    value = ui.speechApiKeyMasked.ifEmpty { stringResource(R.string.settings_not_set) },
                    icon = Icons.Filled.Key, iconTint = tint,
                    modifier = Modifier.settingsRow("speechKey"),
                    onClick = { state.sheet = SettingsSheet.SPEECH_KEY }
                )
            }
        }
    }
    if (ui.selectedSpeech != SpeechProvider.NATIVE) {
        InsetGroup(
            header = stringResource(R.string.settings_section_speech_fallback),
            footer = stringResource(R.string.settings_info_speech_fallback)
        ) {
            row {
                GroupRow(
                    title = stringResource(R.string.settings_enable_fallback),
                    icon = Icons.Filled.Refresh, iconTint = tint,
                    modifier = Modifier.settingsRow("speechFallback"),
                    trailing = RowTrailing.Toggle(ui.speechFallbackEnabled, vm::setSpeechFallbackEnabled)
                )
            }
            if (ui.speechFallbackEnabled) {
                row {
                    GroupRow(
                        title = stringResource(R.string.settings_ai_provider),
                        value = stringResource(ui.speechFallbackProvider.displayNameRes),
                        leading = { BrandSlot { SpeechProviderBrandIcon(ui.speechFallbackProvider, Modifier.size(20.dp)) } },
                        modifier = Modifier.settingsRow("speechFallbackProvider"),
                        onClick = { state.sheet = SettingsSheet.SPEECH_FALLBACK_PROVIDER }
                    )
                }
                row {
                    GroupRow(
                        title = stringResource(R.string.settings_speech_language),
                        value = stringResource(ui.speechFallbackLanguage.displayNameRes),
                        icon = Icons.Filled.Language, iconTint = tint,
                        modifier = Modifier.settingsRow("speechFallbackLanguage"),
                        onClick = { state.sheet = SettingsSheet.SPEECH_FALLBACK_LANGUAGE }
                    )
                }
                if (ui.speechFallbackProvider.requiresApiKey) {
                    row {
                        GroupRow(
                            title = stringResource(R.string.settings_api_key),
                            value = ui.speechFallbackApiKeyMasked.ifEmpty { stringResource(R.string.settings_not_set) },
                            icon = Icons.Filled.Key, iconTint = tint,
                            modifier = Modifier.settingsRow("speechFallbackKey"),
                            onClick = { state.sheet = SettingsSheet.SPEECH_FALLBACK_KEY }
                        )
                    }
                }
            }
        }
    }
}

/** AI & Speech › Custom Instructions: the free-text context sent with every AI request. */
@Composable
internal fun CustomInstructionsPage(ctx: SettingsPageContext) {
    InsetGroup(footer = stringResource(R.string.settings_custom_instructions_footer)) {
        row {
            CustomInstructionsBlock(
                initial = ctx.ui.userContext,
                placeholder = stringResource(R.string.settings_custom_instructions_placeholder),
                onSave = { ctx.vm.setUserContext(it) }
            )
        }
    }
}
