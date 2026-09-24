package com.ayuvo.health.ui.settings

import com.ayuvo.health.ui.design.AyuvoColors
import com.ayuvo.health.models.OpenRouterReasoningEffort
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import com.ayuvo.health.backup.DriveCloudBackupClient
import androidx.core.content.ContextCompat
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.automirrored.outlined.DirectionsRun
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.automirrored.outlined.DirectionsWalk
import androidx.compose.material.icons.outlined.FitnessCenter
import androidx.compose.material.icons.outlined.LocalDining
import androidx.compose.material.icons.outlined.SelfImprovement
import androidx.compose.material.icons.outlined.SportsMartialArts
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material.icons.outlined.SettingsBrightness
import androidx.compose.material.icons.outlined.Wc
import androidx.compose.material.icons.outlined.Female
import androidx.compose.material.icons.outlined.Male
import androidx.compose.material.icons.automirrored.filled.TrendingDown
import androidx.compose.material.icons.automirrored.filled.TrendingFlat
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Brightness6
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.CalendarToday
import androidx.compose.material.icons.outlined.Cake
import androidx.compose.material.icons.outlined.DataUsage
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Equalizer
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.MonitorHeart
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.Height
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Numbers
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.LocalFireDepartment
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.MonitorWeight
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Calculate
import androidx.compose.material.icons.outlined.Percent
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material.icons.outlined.TrackChanges
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.BatteryAlert
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Straighten
import androidx.compose.material.icons.automirrored.outlined.TrendingUp
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material.icons.outlined.Alarm
import androidx.compose.material.icons.outlined.Medication
import androidx.compose.material.icons.outlined.Snooze
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.res.pluralStringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.ayuvo.health.medications.logic.MedicationConstants
import com.ayuvo.health.medications.reminders.MedicationAlarms
import com.ayuvo.health.ui.components.OptionPickerSheet
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.ayuvo.health.R
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import com.ayuvo.health.AppContainer
import com.ayuvo.health.models.ActivityLevel
import com.ayuvo.health.models.AIProvider
import com.ayuvo.health.models.AutoBalanceMacro
import com.ayuvo.health.models.Gender
import com.ayuvo.health.models.MealSchedule
import com.ayuvo.health.models.OptionalNutrient
import com.ayuvo.health.models.OptionalNutrientGoals
import com.ayuvo.health.models.QuickAction
import com.ayuvo.health.models.SpeechLanguage
import com.ayuvo.health.models.SpeechProvider
import com.ayuvo.health.models.UserProfile
import com.ayuvo.health.models.WeightDisplayFormatter
import com.ayuvo.health.models.WeightGoal
import com.ayuvo.health.models.WaterUnit
import com.ayuvo.health.models.WorkoutRpeScale
import com.ayuvo.health.models.WorkoutSplit
import com.ayuvo.health.export.DiaryImportMode
import com.ayuvo.health.export.DiaryImportPreview
import com.ayuvo.health.export.DiaryImporter
import com.ayuvo.health.services.health.HealthAvailabilityMessageKind
import com.ayuvo.health.services.health.HealthConnectAvailability
import com.ayuvo.health.services.health.healthAvailabilityMessageKind
import com.ayuvo.health.services.ondevice.LocalModelId
import com.ayuvo.health.services.ondevice.LocalModelIneligibility
import com.ayuvo.health.services.ondevice.LocalModelInstallStatus
import com.ayuvo.health.services.ondevice.LocalModelState
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import com.ayuvo.health.ui.components.DecimalWheelPicker
import com.ayuvo.health.ui.components.DateWheelPicker
import com.ayuvo.health.ui.components.GlassDialog
import com.ayuvo.health.ui.components.GlassDialogActions
import com.ayuvo.health.ui.components.GlassPrimaryButton
import com.ayuvo.health.ui.components.GlassSurface
import com.ayuvo.health.ui.components.GlassTextButton
import com.ayuvo.health.ui.components.GlassTextField
import com.ayuvo.health.ui.components.IconBubble
import com.ayuvo.health.ui.components.FeetInchesWheelPicker
import com.ayuvo.health.ui.components.NumericWheelPicker
import com.ayuvo.health.ui.components.WheelPicker
import com.ayuvo.health.ui.about.AboutAppHeader
import com.ayuvo.health.ui.about.AboutSettingsCategory
import com.ayuvo.health.ui.about.AboutSettingsRows
import com.ayuvo.health.ui.components.SplitDecimalWheelPicker
import com.ayuvo.health.ui.components.UnitToggle
import com.ayuvo.health.ui.navigation.BottomNavScrollPadding
import com.ayuvo.health.ui.theme.AppColors
import com.ayuvo.health.ui.theme.AppThemeColor
import com.ayuvo.health.ui.navigation.AppRoutes
import com.ayuvo.health.ui.util.clockTimePattern
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.util.Locale
import java.time.LocalTime
import kotlin.math.roundToInt

internal enum class SettingsSheet {
    AI_PROVIDER, AI_MODEL, REASONING_EFFORT, MAX_TOKENS, REQUEST_TIMEOUT, API_KEY, CUSTOM_BASE_URL, SPEECH_PROVIDER, SPEECH_LANGUAGE, SPEECH_KEY,
    MODEL_PROFILE_ADD, MODEL_PROFILE_ACTIONS, MODEL_PROFILE_RENAME, MODEL_PROFILE_MODEL, MODEL_PROFILE_KEY,
    MODEL_PROFILE_VERTEX, HUGGING_FACE_TOKEN,
    TEXT_PROVIDER, TEXT_MODEL, TEXT_KEY, TEXT_BASE_URL,
    TEXT_FALLBACK_PROVIDER, TEXT_FALLBACK_MODEL, TEXT_FALLBACK_KEY, TEXT_FALLBACK_BASE_URL,
    FALLBACK_PROVIDER, FALLBACK_MODEL, FALLBACK_KEY, FALLBACK_BASE_URL,
    SPEECH_FALLBACK_PROVIDER, SPEECH_FALLBACK_LANGUAGE, SPEECH_FALLBACK_KEY,
    GENDER, BIRTHDAY, HEIGHT, WEIGHT, BODY_FAT, GOAL_BODY_FAT, ACTIVITY, GOAL, GOAL_WEIGHT, GOAL_SPEED,
    CALORIES, PROTEIN, CARBS, FAT, OPTIONAL_NUTRIENTS,
    APPEARANCE, WEEK_START, MEAL_TIMES, WATER_GOAL, WATER_UNIT, FASTING_GOAL, WORKOUT_SPLIT, WORKOUT_RPE,
    STEP_GOAL, HEIGHT_UNIT, WEIGHT_UNIT, GLUCOSE_UNIT
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsSheets(
    sheet: SettingsSheet,
    ui: SettingsUiState,
    vm: SettingsViewModel,
    onDismiss: () -> Unit,
    onInvalidGoalWeight: (String) -> Unit,
    onRebalanceBlocked: () -> Unit,
    selectedProfileId: String? = null,
    onProfileAction: (SettingsSheet) -> Unit = {}
) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val invalidLoseMsg = stringResource(R.string.settings_invalid_goal_lose)
    val invalidGainMsg = stringResource(R.string.settings_invalid_goal_gain)
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = state,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        containerColor = AyuvoColors.sheetBackground()
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
            when (sheet) {
                // -- Saved model profiles (docs/ai-models.md 3) --
                SettingsSheet.HUGGING_FACE_TOKEN -> ApiKeySheet(
                    title = stringResource(R.string.settings_hf_token),
                    placeholder = "hf_...",
                    onSave = { vm.setHuggingFaceToken(it); onDismiss() }
                )
                SettingsSheet.MODEL_PROFILE_ADD -> ListSheet(
                    title = stringResource(R.string.sheet_model_add_title),
                    items = ui.availableVisionProviders,
                    label = { stringResource(it.displayNameRes) },
                    selected = { false },
                    onSelect = { vm.addModelProfile(it); onDismiss() },
                    leadingContent = { AIProviderBrandIcon(it, Modifier.size(20.dp)) }
                )
                SettingsSheet.MODEL_PROFILE_ACTIONS -> {
                    val profile = ui.aiProfiles.firstOrNull { it.id == selectedProfileId }
                    if (profile == null) {
                        onDismiss()
                    } else {
                        ModelProfileActionsSheet(
                            profile = profile,
                            onAction = { onProfileAction(it) },
                            onDelete = { vm.deleteModelProfile(profile.id); onDismiss() }
                        )
                    }
                }
                SettingsSheet.MODEL_PROFILE_RENAME -> {
                    val profile = ui.aiProfiles.firstOrNull { it.id == selectedProfileId }
                    TextFieldSheet(
                        title = stringResource(R.string.sheet_model_rename),
                        initial = profile?.nickname.orEmpty(),
                        placeholder = stringResource(R.string.sheet_model_rename_placeholder),
                        onSave = {
                            if (profile != null) vm.renameModelProfile(profile.id, it)
                            onDismiss()
                        }
                    )
                }
                SettingsSheet.MODEL_PROFILE_MODEL -> {
                    val profile = ui.aiProfiles.firstOrNull { it.id == selectedProfileId }
                    val provider = profile?.provider
                    ListSheet(
                        title = stringResource(R.string.sheet_model),
                        items = provider?.models.orEmpty(),
                        label = { it },
                        selected = { it == profile?.model },
                        onSelect = {
                            if (profile != null) vm.setModelProfileModel(profile.id, it)
                            onDismiss()
                        },
                        footer = stringResource(R.string.sheet_model_footer),
                        customField = { m ->
                            if (profile != null) vm.setModelProfileModel(profile.id, m)
                            onDismiss()
                        }
                    )
                }
                SettingsSheet.MODEL_PROFILE_VERTEX -> {
                    val profile = ui.aiProfiles.firstOrNull { it.id == selectedProfileId }
                    TextFieldSheet(
                        title = stringResource(R.string.sheet_model_vertex),
                        initial = "",
                        placeholder = stringResource(R.string.sheet_model_vertex_placeholder),
                        onSave = {
                            if (profile != null) vm.setModelProfileVertex(profile.id, it)
                            onDismiss()
                        }
                    )
                }
                SettingsSheet.MODEL_PROFILE_KEY -> {
                    val profile = ui.aiProfiles.firstOrNull { it.id == selectedProfileId }
                    ApiKeySheet(
                        title = stringResource(R.string.sheet_model_key),
                        placeholder = profile?.provider?.let { stringResource(it.apiKeyPlaceholderRes) }.orEmpty(),
                        onSave = {
                            if (profile != null) vm.setModelProfileKey(profile.id, it)
                            onDismiss()
                        }
                    )
                }
                SettingsSheet.AI_PROVIDER -> ListSheet(
                    title = stringResource(R.string.sheet_ai_provider),
                    items = ui.availableVisionProviders,
                    label = { stringResource(it.displayNameRes) },
                    selected = { it == ui.selectedAI },
                    onSelect = { vm.selectProvider(it); onDismiss() },
                    leadingContent = { AIProviderBrandIcon(it, Modifier.size(20.dp)) }
                )
                SettingsSheet.AI_MODEL -> ListSheet(
                    title = stringResource(R.string.sheet_model),
                    items = ui.selectedAI.models,
                    label = { it },
                    selected = { it == ui.selectedModel },
                    onSelect = { vm.selectModel(it); onDismiss() },
                    footer = if (ui.selectedAI.supportsCustomModelName) stringResource(R.string.sheet_model_footer) else null,
                    customField = if (ui.selectedAI.supportsCustomModelName) {
                        { m -> vm.selectModel(m); onDismiss() }
                    } else null
                )
                SettingsSheet.API_KEY -> ApiKeySheet(
                    title = stringResource(R.string.sheet_api_key_format, stringResource(ui.selectedAI.displayNameRes)),
                    placeholder = stringResource(ui.selectedAI.apiKeyPlaceholderRes),
                    onSave = { vm.setApiKey(it); onDismiss() }
                )
                SettingsSheet.CUSTOM_BASE_URL -> {
                    val existing = remember { runBlocking { vm.container.prefs.customBaseUrl(ui.selectedAI).first().orEmpty() } }
                    TextFieldSheet(
                        title = stringResource(R.string.settings_custom_url_title),
                        initial = existing,
                        placeholder = stringResource(R.string.settings_custom_url_placeholder),
                        onSave = { vm.setCustomBaseUrl(ui.selectedAI, it); onDismiss() }
                    )
                }
                SettingsSheet.TEXT_PROVIDER -> ListSheet(
                    title = stringResource(R.string.settings_section_text_ai),
                    items = ui.availableTextProviders,
                    label = { stringResource(it.displayNameRes) },
                    selected = { it == ui.selectedTextAI },
                    onSelect = { vm.selectTextProvider(it); onDismiss() },
                    leadingContent = { AIProviderBrandIcon(it, Modifier.size(20.dp)) }
                )
                SettingsSheet.TEXT_MODEL -> ListSheet(
                    title = stringResource(R.string.sheet_model),
                    items = ui.selectedTextAI.textModels,
                    label = { it },
                    selected = { it == ui.selectedTextModel },
                    onSelect = { vm.selectTextModel(it); onDismiss() },
                    footer = if (ui.selectedTextAI.supportsCustomModelName) stringResource(R.string.sheet_model_footer) else null,
                    customField = if (ui.selectedTextAI.supportsCustomModelName) {
                        { model -> vm.selectTextModel(model); onDismiss() }
                    } else null
                )
                SettingsSheet.TEXT_KEY -> ApiKeySheet(
                    title = stringResource(R.string.sheet_api_key_format, stringResource(ui.selectedTextAI.displayNameRes)),
                    placeholder = stringResource(ui.selectedTextAI.apiKeyPlaceholderRes),
                    onSave = { vm.setTextApiKey(it); onDismiss() }
                )
                SettingsSheet.TEXT_BASE_URL -> {
                    val existing = remember { runBlocking { vm.container.prefs.customBaseUrl(ui.selectedTextAI).first().orEmpty() } }
                    TextFieldSheet(
                        title = stringResource(R.string.settings_custom_url_title),
                        initial = existing,
                        placeholder = stringResource(R.string.settings_custom_url_placeholder),
                        onSave = { vm.setCustomBaseUrl(ui.selectedTextAI, it); onDismiss() }
                    )
                }
                SettingsSheet.TEXT_FALLBACK_PROVIDER -> ListSheet(
                    title = stringResource(R.string.settings_section_text_fallback),
                    items = ui.availableTextProviders,
                    label = { stringResource(it.displayNameRes) },
                    selected = { it == ui.textFallbackProvider },
                    onSelect = { vm.selectTextFallbackProvider(it); onDismiss() },
                    leadingContent = { AIProviderBrandIcon(it, Modifier.size(20.dp)) }
                )
                SettingsSheet.TEXT_FALLBACK_MODEL -> {
                    val primaryTextProvider = if (ui.separateTextProviderEnabled) ui.selectedTextAI else ui.selectedAI
                    val primaryTextModel = if (ui.separateTextProviderEnabled) ui.selectedTextModel else ui.selectedModel
                    val primaryBaseUrl = remember(primaryTextProvider) {
                        runBlocking {
                            vm.container.prefs.migrateFallbackBaseUrls()
                            vm.container.prefs.customBaseUrl(primaryTextProvider).first()
                                ?.takeIf { it.isNotEmpty() } ?: primaryTextProvider.baseUrl
                        }
                    }
                    val fallbackBaseUrl = remember(ui.textFallbackProvider) {
                        runBlocking {
                            vm.container.prefs.migrateFallbackBaseUrls()
                            vm.container.prefs.fallbackCustomBaseUrl(ui.textFallbackProvider).first()
                                ?.takeIf { it.isNotEmpty() } ?: ui.textFallbackProvider.baseUrl
                        }
                    }
                    val sameServer = ui.textFallbackProvider == primaryTextProvider && primaryBaseUrl == fallbackBaseUrl
                    val options = if (sameServer) {
                        ui.textFallbackProvider.textModels.filter { it != primaryTextModel }
                    } else {
                        ui.textFallbackProvider.textModels
                    }
                    ListSheet(
                        title = stringResource(R.string.sheet_model),
                        items = options,
                        label = { it },
                        selected = { it == ui.textFallbackModel },
                        onSelect = { vm.selectTextFallbackModel(it); onDismiss() },
                        footer = if (ui.textFallbackProvider.supportsCustomModelName) stringResource(R.string.sheet_model_footer) else null,
                        customField = if (ui.textFallbackProvider.supportsCustomModelName) {
                            { model -> vm.selectTextFallbackModel(model); onDismiss() }
                        } else null
                    )
                }
                SettingsSheet.TEXT_FALLBACK_KEY -> ApiKeySheet(
                    title = stringResource(R.string.sheet_api_key_format, stringResource(ui.textFallbackProvider.displayNameRes)),
                    placeholder = stringResource(ui.textFallbackProvider.apiKeyPlaceholderRes),
                    onSave = { vm.setTextFallbackApiKey(it); onDismiss() }
                )
                SettingsSheet.TEXT_FALLBACK_BASE_URL -> {
                    val existing = remember(ui.textFallbackProvider) {
                        runBlocking {
                            vm.container.prefs.migrateFallbackBaseUrls()
                            vm.container.prefs.fallbackCustomBaseUrl(ui.textFallbackProvider).first().orEmpty()
                        }
                    }
                    TextFieldSheet(
                        title = stringResource(R.string.settings_custom_url_title),
                        initial = existing,
                        placeholder = stringResource(R.string.settings_custom_url_placeholder),
                        onSave = { vm.setFallbackCustomBaseUrl(ui.textFallbackProvider, it); onDismiss() }
                    )
                }
                SettingsSheet.REASONING_EFFORT -> ListSheet(
                    title = stringResource(R.string.settings_reasoning_effort),
                    items = OpenRouterReasoningEffort.entries,
                    label = { stringResource(it.labelRes) },
                    selected = { it == ui.openRouterReasoningEffort },
                    onSelect = { vm.setOpenRouterReasoningEffort(it); onDismiss() },
                    footer = stringResource(R.string.settings_reasoning_effort_help)
                )
                SettingsSheet.MAX_TOKENS -> {
                    TextFieldSheet(
                        title = stringResource(R.string.settings_max_tokens),
                        initial = ui.maxResponseTokens.toString(),
                        placeholder = "1024",
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        onSave = { it.trim().toIntOrNull()?.let(vm::setMaxResponseTokens); onDismiss() }
                    )
                }
                SettingsSheet.REQUEST_TIMEOUT -> {
                    TextFieldSheet(
                        title = stringResource(R.string.settings_request_timeout),
                        initial = ui.aiRequestTimeoutSeconds.toString(),
                        placeholder = AIProvider.DEFAULT_REQUEST_TIMEOUT_SECONDS.toString(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        onSave = {
                            it.trim().toIntOrNull()?.let(vm::setAiRequestTimeoutSeconds)
                            onDismiss()
                        }
                    )
                }
                SettingsSheet.SPEECH_PROVIDER -> ListSheet(
                    title = stringResource(R.string.sheet_speech_engine),
                    items = ui.availableSpeechProviders,
                    label = { stringResource(it.displayNameRes) },
                    selected = { it == ui.selectedSpeech },
                    onSelect = { vm.selectSpeech(it); onDismiss() },
                    leadingContent = { SpeechProviderBrandIcon(it, Modifier.size(20.dp)) }
                )
                SettingsSheet.SPEECH_LANGUAGE -> ListSheet(
                    title = stringResource(R.string.sheet_speech_language),
                    items = SpeechLanguage.optionsFor(ui.selectedSpeech),
                    label = { stringResource(it.displayNameRes) },
                    selected = { it == ui.selectedSpeechLanguage },
                    onSelect = { vm.selectSpeechLanguage(it); onDismiss() },
                    subtitle = {
                        when (it) {
                            SpeechLanguage.PROVIDER_AUTO -> stringResource(R.string.speech_language_provider_auto_subtitle)
                            SpeechLanguage.DEVICE -> stringResource(R.string.speech_language_device_subtitle)
                            else -> null
                        }
                    }
                )
                SettingsSheet.SPEECH_KEY -> ApiKeySheet(
                    title = stringResource(R.string.sheet_speech_api_key_format, stringResource(ui.selectedSpeech.displayNameRes)),
                    placeholder = stringResource(ui.selectedSpeech.apiKeyPlaceholderRes),
                    onSave = {
                        // Route through the VM so SettingsUiState.speechApiKeyMasked
                        // updates and the API Key row reflects the new value
                        // (was bypassing the VM and writing straight to KeyStore,
                        // which left the UI showing "Tap to edit" forever).
                        vm.setSpeechApiKey(it)
                        onDismiss()
                    }
                )
                SettingsSheet.SPEECH_FALLBACK_PROVIDER -> ListSheet(
                    title = stringResource(R.string.settings_section_speech_fallback),
                    items = ui.availableSpeechFallbackProviders.filter { it != ui.selectedSpeech },
                    label = { stringResource(it.displayNameRes) },
                    selected = { it == ui.speechFallbackProvider },
                    onSelect = { vm.selectSpeechFallbackProvider(it); onDismiss() },
                    leadingContent = { SpeechProviderBrandIcon(it, Modifier.size(20.dp)) }
                )
                SettingsSheet.SPEECH_FALLBACK_LANGUAGE -> ListSheet(
                    title = stringResource(R.string.sheet_speech_language),
                    items = SpeechLanguage.optionsFor(ui.speechFallbackProvider),
                    label = { stringResource(it.displayNameRes) },
                    selected = { it == ui.speechFallbackLanguage },
                    onSelect = { vm.selectSpeechFallbackLanguage(it); onDismiss() },
                    subtitle = {
                        when (it) {
                            SpeechLanguage.PROVIDER_AUTO -> stringResource(R.string.speech_language_provider_auto_subtitle)
                            SpeechLanguage.DEVICE -> stringResource(R.string.speech_language_device_subtitle)
                            else -> null
                        }
                    }
                )
                SettingsSheet.SPEECH_FALLBACK_KEY -> ApiKeySheet(
                    title = stringResource(R.string.sheet_speech_api_key_format, stringResource(ui.speechFallbackProvider.displayNameRes)),
                    placeholder = stringResource(ui.speechFallbackProvider.apiKeyPlaceholderRes),
                    onSave = { vm.setSpeechFallbackApiKey(it); onDismiss() }
                )
                SettingsSheet.WORKOUT_SPLIT -> ListSheet(
                    title = stringResource(R.string.settings_training_split),
                    items = WorkoutSplit.SelectableValues,
                    label = { it.title },
                    selected = { it == ui.workoutSplit },
                    onSelect = { vm.selectWorkoutSplit(it); onDismiss() }
                )
                SettingsSheet.WORKOUT_RPE -> ListSheet(
                    title = stringResource(R.string.settings_rpe_scale),
                    items = WorkoutRpeScale.entries,
                    label = { it.title },
                    selected = { it == ui.workoutRpeScale },
                    onSelect = { vm.selectWorkoutRpeScale(it); onDismiss() },
                    subtitle = { it.inputPlaceholder }
                )
                SettingsSheet.FALLBACK_PROVIDER -> ListSheet(
                    title = stringResource(R.string.sheet_ai_provider),
                    items = ui.availableVisionProviders,
                    label = { stringResource(it.displayNameRes) },
                    selected = { it == ui.fallbackProvider },
                    onSelect = { vm.selectFallbackProvider(it); onDismiss() },
                    leadingContent = { AIProviderBrandIcon(it, Modifier.size(20.dp)) }
                )
                SettingsSheet.FALLBACK_MODEL -> {
                    // Same provider + same server → exclude primary's model so fallback
                    // can't be a literal duplicate config. Different servers may share a model.
                    val primaryBaseUrl = remember(ui.selectedAI) {
                        runBlocking {
                            vm.container.prefs.migrateFallbackBaseUrls()
                            vm.container.prefs.customBaseUrl(ui.selectedAI).first()
                                ?.takeIf { it.isNotEmpty() } ?: ui.selectedAI.baseUrl
                        }
                    }
                    val fallbackBaseUrl = remember(ui.fallbackProvider) {
                        runBlocking {
                            vm.container.prefs.migrateFallbackBaseUrls()
                            vm.container.prefs.fallbackCustomBaseUrl(ui.fallbackProvider).first()
                                ?.takeIf { it.isNotEmpty() } ?: ui.fallbackProvider.baseUrl
                        }
                    }
                    val sameServer = ui.fallbackProvider == ui.selectedAI && primaryBaseUrl == fallbackBaseUrl
                    val opts = if (sameServer)
                        ui.fallbackProvider.models.filter { it != ui.selectedModel }
                    else ui.fallbackProvider.models
                    ListSheet(
                        title = stringResource(R.string.sheet_model),
                        items = opts,
                        label = { it },
                        selected = { it == ui.fallbackModel },
                        onSelect = { vm.selectFallbackModel(it); onDismiss() },
                        footer = if (ui.fallbackProvider.supportsCustomModelName) stringResource(R.string.sheet_model_footer) else null,
                        customField = if (ui.fallbackProvider.supportsCustomModelName) {
                            { m -> vm.selectFallbackModel(m); onDismiss() }
                        } else null
                    )
                }
                SettingsSheet.FALLBACK_KEY -> ApiKeySheet(
                    title = stringResource(R.string.sheet_api_key_format, stringResource(ui.fallbackProvider.displayNameRes)),
                    placeholder = stringResource(ui.fallbackProvider.apiKeyPlaceholderRes),
                    onSave = { vm.setFallbackApiKey(it); onDismiss() }
                )
                SettingsSheet.FALLBACK_BASE_URL -> {
                    val existing = remember(ui.fallbackProvider) {
                        runBlocking {
                            vm.container.prefs.migrateFallbackBaseUrls()
                            vm.container.prefs.fallbackCustomBaseUrl(ui.fallbackProvider).first().orEmpty()
                        }
                    }
                    TextFieldSheet(
                        title = stringResource(R.string.settings_custom_url_title),
                        initial = existing,
                        placeholder = stringResource(R.string.settings_custom_url_placeholder),
                        onSave = { vm.setFallbackCustomBaseUrl(ui.fallbackProvider, it); onDismiss() }
                    )
                }
                SettingsSheet.GENDER -> ListSheet(
                    title = stringResource(R.string.sheet_gender),
                    items = Gender.values().toList(),
                    label = { stringResource(it.displayNameRes) },
                    selected = { it == ui.profile?.gender },
                    onSelect = { g -> vm.updateProfile { it.copy(gender = g) }; onDismiss() },
                    icon = { genderIcon(it) }
                )
                SettingsSheet.HEIGHT -> {
                    val cm = ui.profile?.heightCm?.toInt() ?: 175
                    HeightSheet(
                        current = cm,
                        useMetric = ui.heightMetric,
                        onUnitChange = { metric -> vm.setHeightUnit(if (metric) "cm" else "ftin") },
                        onSave = { newCm -> vm.updateProfile { it.copy(heightCm = newCm.toDouble()) }; onDismiss() }
                    )
                }
                SettingsSheet.WEIGHT -> {
                    val kg = ui.profile?.weightKg ?: 70.0
                    WeightSheet(
                        titleText = stringResource(R.string.sheet_weight),
                        current = kg,
                        useMetric = ui.weightMetric,
                        onUnitChange = { metric -> vm.setWeightUnit(if (metric) "kg" else "lbs") },
                        onSave = { newKg -> vm.saveCurrentWeight(newKg); onDismiss() }
                    )
                }
                SettingsSheet.BODY_FAT -> BodyFatSheet(
                    current = ui.profile?.bodyFatPercentage,
                    // Clearing the current value also clears the goal so a stale
                    // goal doesn't linger on someone who opted out of the
                    // body-fat track entirely.
                    onSave = { bf ->
                        vm.updateProfile {
                            it.copy(
                                bodyFatPercentage = bf,
                                goalBodyFatPercentage = if (bf == null) null else it.goalBodyFatPercentage
                            )
                        }
                        onDismiss()
                    }
                )
                SettingsSheet.GOAL_BODY_FAT -> GoalBodyFatSheet(
                    currentGoal = ui.profile?.goalBodyFatPercentage,
                    currentBodyFat = ui.profile?.bodyFatPercentage,
                    // Goal body fat doesn't feed BMR/TDEE/macro math, so use
                    // updateProfile (no recompute) — editing the goal must
                    // never silently wipe the user's pinned macros.
                    onSave = { goal -> vm.updateProfile { it.copy(goalBodyFatPercentage = goal) }; onDismiss() }
                )
                SettingsSheet.ACTIVITY -> ListSheet(
                    title = stringResource(R.string.sheet_activity_level),
                    items = ActivityLevel.values().toList(),
                    label = { stringResource(it.displayNameRes) },
                    subtitle = { stringResource(it.subtitleRes) },
                    selected = { it == ui.profile?.activityLevel },
                    onSelect = { a -> vm.updateProfile { it.copy(activityLevel = a) }; onDismiss() },
                    icon = { activityIcon(it) }
                )
                SettingsSheet.GOAL -> ListSheet(
                    title = stringResource(R.string.sheet_goal),
                    items = WeightGoal.values().toList(),
                    label = { stringResource(it.displayNameRes) },
                    selected = { it == ui.profile?.goal },
                    icon = { goalIcon(it) },
                    onSelect = { g ->
                        // Mirrors iOS ContentView.swift profile.goal onChange:
                        //   - Switching to MAINTAIN clears weeklyChangeKg + goalWeightKg.
                        //   - Switching to LOSE/GAIN seeds weeklyChangeKg if missing and
                        //     clears goalWeightKg if it now contradicts the new direction.
                        // Then recompute calories+macros from the new goal.
                        vm.updateProfile { p ->
                            when (g) {
                                WeightGoal.MAINTAIN ->
                                    p.copy(goal = g, weeklyChangeKg = null, goalWeightKg = null)
                                else -> {
                                    val gw = p.goalWeightKg
                                    val mismatched = gw != null && (
                                        (g == WeightGoal.LOSE && gw >= p.weightKg) ||
                                        (g == WeightGoal.GAIN && gw <= p.weightKg)
                                    )
                                    p.copy(
                                        goal = g,
                                        weeklyChangeKg = p.weeklyChangeKg ?: 0.5,
                                        goalWeightKg = if (mismatched) null else p.goalWeightKg
                                    )
                                }
                            }
                        }
                        onDismiss()
                    }
                )
                SettingsSheet.GOAL_WEIGHT -> {
                    val kg = ui.profile?.goalWeightKg ?: (ui.profile?.weightKg ?: 70.0)
                    WeightSheet(
                        titleText = stringResource(R.string.sheet_target_weight),
                        current = kg,
                        useMetric = ui.weightMetric,
                        onUnitChange = { metric -> vm.setWeightUnit(if (metric) "kg" else "lbs") },
                        onSave = { newKg ->
                            // Mirrors iOS ContentView.swift case .editGoalWeight: a Lose goal
                            // requires target < current weight; a Gain goal requires target >
                            // current weight. Reject mismatched targets with an alert instead
                            // of silently saving an unreachable goal.
                            val p = ui.profile
                            val current = p?.weightKg
                            val invalid = p != null && current != null && (
                                (p.goal == WeightGoal.LOSE && newKg >= current) ||
                                (p.goal == WeightGoal.GAIN && newKg <= current)
                            )
                            if (invalid) {
                                onInvalidGoalWeight(
                                    if (p!!.goal == WeightGoal.LOSE)
                                        invalidLoseMsg
                                    else
                                        invalidGainMsg
                                )
                            } else {
                                vm.updateProfile { it.copy(goalWeightKg = newKg) }
                                onDismiss()
                            }
                        }
                    )
                }
                SettingsSheet.GOAL_SPEED -> GoalSpeedSheet(
                    current = ui.profile?.weeklyChangeKg ?: 0.5,
                    goal = ui.profile?.goal ?: WeightGoal.MAINTAIN,
                    useMetric = ui.weightMetric,
                    onSave = { kg -> vm.updateProfile { it.copy(weeklyChangeKg = kg) }; onDismiss() }
                )
                SettingsSheet.BIRTHDAY -> BirthdaySheet(
                    current = ui.profile?.birthday ?: Instant.now(),
                    onSave = { newInstant ->
                        vm.updateProfile { it.copy(birthday = newInstant) }
                        onDismiss()
                    }
                )
                SettingsSheet.APPEARANCE -> ListSheet(
                    title = stringResource(R.string.sheet_appearance),
                    items = listOf(
                        "system" to stringResource(R.string.settings_appearance_system),
                        "light" to stringResource(R.string.settings_appearance_light),
                        "dark" to stringResource(R.string.settings_appearance_dark)
                    ),
                    label = { it.second },
                    selected = { it.first == ui.appearanceMode },
                    onSelect = { vm.setAppearanceMode(it.first); onDismiss() },
                    icon = { appearanceIcon(it.first) }
                )
                SettingsSheet.WEEK_START -> ListSheet(
                    title = stringResource(R.string.sheet_week_starts),
                    items = listOf(
                        false to stringResource(R.string.settings_week_sunday),
                        true to stringResource(R.string.settings_week_monday)
                    ),
                    label = { it.second },
                    selected = { it.first == ui.weekStartsOnMonday },
                    onSelect = { vm.setWeekStartsOnMonday(it.first); onDismiss() }
                )
                SettingsSheet.MEAL_TIMES -> MealTimesSheet(
                    current = ui.mealSchedule,
                    onSave = {
                        vm.setMealSchedule(it)
                        onDismiss()
                    }
                )
                SettingsSheet.WATER_GOAL -> WaterGoalSheet(
                    current = ui.waterDailyGoalMl,
                    unit = ui.waterUnit,
                    onSave = {
                        vm.setWaterDailyGoalMl(it)
                        onDismiss()
                    }
                )
                SettingsSheet.WATER_UNIT -> ListSheet(
                    title = stringResource(R.string.settings_water_unit),
                    items = WaterUnit.entries,
                    label = {
                        if (it == WaterUnit.MILLILITERS) {
                            stringResource(R.string.settings_water_unit_ml)
                        } else {
                            stringResource(R.string.settings_water_unit_fl_oz)
                        }
                    },
                    selected = { it == ui.waterUnit },
                    onSelect = { vm.setWaterUnit(it); onDismiss() }
                )
                SettingsSheet.FASTING_GOAL -> FastingGoalSheet(
                    currentMinutes = ui.fastingDefaultGoalMinutes,
                    onSave = {
                        vm.setFastingDefaultGoalMinutes(it)
                        onDismiss()
                    }
                )
                SettingsSheet.CALORIES -> NutritionPickerSheet(
                    label = stringResource(R.string.macro_calories), unit = stringResource(R.string.unit_kcal),
                    currentValue = ui.profile?.effectiveCalories ?: 2000,
                    range = 800..6000, step = 50,
                    onSave = { v ->
                        vm.editCaloriesGoal(v)
                        onDismiss()
                    },
                    onResetToAuto = if (ui.profile?.caloriesLocked == true) {
                        { vm.resetCaloriesLock(); onDismiss() }
                    } else null
                )
                SettingsSheet.PROTEIN -> NutritionPickerSheet(
                    label = stringResource(R.string.macro_protein), unit = stringResource(R.string.unit_g),
                    currentValue = ui.profile?.effectiveProtein ?: 0,
                    range = 10..500, step = 5,
                    onSave = { v ->
                        vm.editMacroGoal(AutoBalanceMacro.PROTEIN, v) { onRebalanceBlocked() }
                        onDismiss()
                    },
                    onResetToAuto = if (ui.profile?.isMacroLocked(AutoBalanceMacro.PROTEIN) == true) {
                        { vm.resetMacroLock(AutoBalanceMacro.PROTEIN); onDismiss() }
                    } else null
                )
                SettingsSheet.CARBS -> NutritionPickerSheet(
                    label = stringResource(R.string.macro_carbs), unit = stringResource(R.string.unit_g),
                    currentValue = ui.profile?.effectiveCarbs ?: 0,
                    range = 0..800, step = 5,
                    onSave = { v ->
                        vm.editMacroGoal(AutoBalanceMacro.CARBS, v) { onRebalanceBlocked() }
                        onDismiss()
                    },
                    onResetToAuto = if (ui.profile?.isMacroLocked(AutoBalanceMacro.CARBS) == true) {
                        { vm.resetMacroLock(AutoBalanceMacro.CARBS); onDismiss() }
                    } else null
                )
                SettingsSheet.FAT -> NutritionPickerSheet(
                    label = stringResource(R.string.macro_fat), unit = stringResource(R.string.unit_g),
                    currentValue = ui.profile?.effectiveFat ?: 0,
                    range = 10..300, step = 5,
                    onSave = { v ->
                        vm.editMacroGoal(AutoBalanceMacro.FAT, v) { onRebalanceBlocked() }
                        onDismiss()
                    },
                    onResetToAuto = if (ui.profile?.isMacroLocked(AutoBalanceMacro.FAT) == true) {
                        { vm.resetMacroLock(AutoBalanceMacro.FAT); onDismiss() }
                    } else null
                )
                SettingsSheet.STEP_GOAL -> NutritionPickerSheet(
                    label = stringResource(R.string.settings_daily_step_goal),
                    unit = stringResource(R.string.summary_unit_steps),
                    currentValue = ui.dailyStepGoal,
                    range = 1_000..50_000,
                    step = 500,
                    onSave = { v ->
                        vm.setDailyStepGoal(v)
                        onDismiss()
                    }
                )
                SettingsSheet.HEIGHT_UNIT -> ListSheet(
                    title = stringResource(R.string.settings_units_height),
                    items = listOf("cm", "ftin"),
                    label = { stringResource(if (it == "cm") R.string.settings_unit_cm else R.string.settings_unit_ftin) },
                    selected = { it == ui.heightUnit },
                    onSelect = { vm.setHeightUnit(it); onDismiss() }
                )
                SettingsSheet.WEIGHT_UNIT -> ListSheet(
                    title = stringResource(R.string.settings_units_weight),
                    items = listOf("kg", "lbs"),
                    label = { stringResource(if (it == "kg") R.string.settings_unit_kg else R.string.settings_unit_lbs) },
                    selected = { it == ui.weightUnit },
                    onSelect = { vm.setWeightUnit(it); onDismiss() }
                )
                SettingsSheet.GLUCOSE_UNIT -> ListSheet(
                    title = stringResource(R.string.settings_units_glucose),
                    items = listOf("mmol/L", "mg/dL"),
                    label = { it },
                    selected = { it == ui.effectiveGlucoseUnit },
                    onSelect = { vm.setHealthGlucoseUnit(it); onDismiss() }
                )
                SettingsSheet.OPTIONAL_NUTRIENTS -> OptionalNutrientGoalsSheet(
                    goals = ui.optionalNutrientGoals,
                    onChange = vm::setOptionalNutrientGoals,
                    onDismiss = onDismiss
                )
            }
            Spacer(Modifier.height(14.dp))
        }

    }
}

@Composable
internal fun OptionalNutrientGoalsSheet(
    goals: OptionalNutrientGoals,
    onChange: (OptionalNutrientGoals) -> Unit,
    onDismiss: () -> Unit
) {
    var editing by remember { mutableStateOf<OptionalNutrient?>(null) }
    val nutrient = editing

    if (nutrient != null) {
        TextButton(onClick = { editing = null }) {
            Text(stringResource(R.string.settings_other_nutrients), color = AppColors.Calorie)
        }
        Spacer(Modifier.height(4.dp))
        NutritionPickerSheet(
            label = stringResource(nutrient.displayNameRes),
            unit = stringResource(nutrient.unitRes),
            currentValue = goals.valueFor(nutrient),
            range = nutrient.pickerRange(),
            step = nutrient.pickerStep(),
            allowCustomValue = true,
            guidanceUpperLimit = nutrient.generalAdultUpperLimit(),
            customValueDetail = nutrient::customValueDetail,
            onSave = { value ->
                onChange(goals.withValue(nutrient, value))
                editing = null
            }
        )
        return
    }

    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.settings_other_nutrient_goals), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Spacer(Modifier.weight(1f))
        TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_done), color = AppColors.Calorie) }
    }
    Text(
        stringResource(R.string.settings_other_nutrients_note),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
    )
    Spacer(Modifier.height(12.dp))
    LazyColumn(
        Modifier.fillMaxWidth().heightIn(max = 420.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(OptionalNutrient.values().toList()) { item ->
            OptionalNutrientGoalRow(
                nutrient = item,
                value = goals.valueFor(item),
                onClick = { editing = item }
            )
        }
    }
    TextButton(
        onClick = { onChange(OptionalNutrientGoals.Default) },
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(stringResource(R.string.settings_reset_defaults), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
    }
}

@Composable
internal fun OptionalNutrientGoalRow(
    nutrient: OptionalNutrient,
    value: Int,
    onClick: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconBubble(
            Icons.Outlined.DataUsage,
            size = 22.dp,
            iconSize = 15.dp
        )
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                stringResource(nutrient.displayNameRes),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium
            )
            Text(
                stringResource(nutrient.unitRes),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.48f)
            )
        }
        Text(
            "$value${nutrient.unit}",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
        )
        Spacer(Modifier.width(8.dp))
        Icon(
            Icons.Filled.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f),
            modifier = Modifier.size(18.dp)
        )
    }
}

@Composable
internal fun ThemeColorSwatch(themeColor: AppThemeColor, modifier: Modifier = Modifier) {
    Box(
        modifier
            .clip(CircleShape)
            .background(Brush.linearGradient(listOf(themeColor.start, themeColor.end)))
    )
}

@Composable
internal fun <T> ListSheet(
    title: String,
    items: List<T>,
    label: @Composable (T) -> String,
    selected: (T) -> Boolean,
    onSelect: (T) -> Unit,
    icon: ((T) -> ImageVector?)? = null,
    leadingContent: (@Composable (T) -> Unit)? = null,
    subtitle: (@Composable (T) -> String?)? = null,
    footer: String? = null,
    customField: ((String) -> Unit)? = null
) {
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(12.dp))
    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(items) { item ->
            val isSel = selected(item)
            val rowIcon = icon?.invoke(item)
            val sub = subtitle?.invoke(item)
            val shape = RoundedCornerShape(16.dp)
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(shape)
                    .background(
                        if (isSel) AppColors.Calorie.copy(alpha = 0.13f)
                        else if (isDark) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.38f)
                        else Color(0xFFEDE3DD).copy(alpha = 0.76f)
                    )
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                Color.White.copy(alpha = if (isDark) 0.08f else 0.18f),
                                Color.White.copy(alpha = if (isDark) 0.02f else 0.04f),
                                AppColors.Calorie.copy(alpha = if (isSel) 0.065f else if (isDark) 0.025f else 0.050f)
                            )
                        )
                    )
                    .border(
                        0.7.dp,
                        Brush.linearGradient(
                            listOf(
                                Color.White.copy(alpha = if (isDark) 0.16f else 0.46f),
                                AppColors.Calorie.copy(alpha = if (isSel) 0.22f else if (isDark) 0.08f else 0.16f)
                            )
                        ),
                        shape
                    )
                    .clickable { onSelect(item) }
                    .padding(horizontal = 14.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (leadingContent != null) {
                    Box(Modifier.size(22.dp), contentAlignment = Alignment.Center) {
                        leadingContent(item)
                    }
                    Spacer(Modifier.width(14.dp))
                } else if (rowIcon != null) {
                    IconBubble(rowIcon, size = 22.dp, iconSize = 14.dp)
                    Spacer(Modifier.width(14.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        label(item),
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium
                    )
                    if (!sub.isNullOrBlank()) {
                        Spacer(Modifier.height(2.dp))
                        Text(
                            sub,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                    }
                }
                if (isSel) {
                    Icon(
                        Icons.Filled.Check,
                        contentDescription = stringResource(R.string.sheet_selected_a11y),
                        tint = AppColors.Calorie,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
    if (customField != null) {
        footer?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
        }
        var custom by remember { mutableStateOf("") }
        Spacer(Modifier.height(8.dp))
        GlassTextField(
            value = custom,
            onValueChange = { custom = it },
            placeholder = stringResource(R.string.sheet_any_model_id),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        GlassPrimaryButton(
            text = stringResource(R.string.action_save),
            onClick = { if (custom.isNotBlank()) customField(custom.trim()) },
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
internal fun ApiKeySheet(title: String, placeholder: String, onSave: (String) -> Unit) {
    var value by remember { mutableStateOf("") }
    Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(12.dp))
    GlassTextField(
        value = value,
        onValueChange = { value = it },
        placeholder = placeholder,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )
    Spacer(Modifier.height(12.dp))
    GlassPrimaryButton(
        text = stringResource(R.string.action_save),
        onClick = { onSave(value) },
        modifier = Modifier.fillMaxWidth()
    )
    Spacer(Modifier.height(4.dp))
    TextButton(onClick = { onSave("") }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.settings_clear_key)) }
}

@Composable
internal fun TextFieldSheet(
    title: String,
    initial: String,
    placeholder: String,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    onSave: (String) -> Unit
) {
    var value by remember { mutableStateOf(initial) }
    Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(12.dp))
    GlassTextField(
        value = value,
        onValueChange = { value = it },
        placeholder = placeholder,
        singleLine = true,
        keyboardOptions = keyboardOptions,
        modifier = Modifier.fillMaxWidth()
    )
    Spacer(Modifier.height(12.dp))
    GlassPrimaryButton(
        text = stringResource(R.string.action_save),
        onClick = { onSave(value.trim()) },
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
internal fun HeightSheet(current: Int, useMetric: Boolean, onUnitChange: (Boolean) -> Unit, onSave: (Int) -> Unit) {
    var cm by remember(current) { mutableStateOf(current) }
    var metric by remember { mutableStateOf(useMetric) }
    Text(stringResource(R.string.sheet_height), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(12.dp))
    UnitToggle(stringResource(R.string.unit_cm), stringResource(R.string.unit_ft_in), metric, { metric = it; onUnitChange(it) }, Modifier.fillMaxWidth())
    Spacer(Modifier.height(20.dp))
    if (metric) NumericWheelPicker(cm, { cm = it }, 100, 250, stringResource(R.string.unit_cm))
    else FeetInchesWheelPicker(cm, { cm = it })
    Spacer(Modifier.height(16.dp))
    GradientSaveButton { onSave(cm) }
    Spacer(Modifier.height(8.dp))
}

@Composable
internal fun WeightSheet(titleText: String, current: Double, useMetric: Boolean, onUnitChange: (Boolean) -> Unit, onSave: (Double) -> Unit) {
    var kg by remember(current) { mutableStateOf(current) }
    var metric by remember { mutableStateOf(useMetric) }
    Text(titleText, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(12.dp))
    UnitToggle(stringResource(R.string.unit_kg), stringResource(R.string.unit_lbs), metric, { metric = it; onUnitChange(it) }, Modifier.fillMaxWidth())
    Spacer(Modifier.height(20.dp))
    if (metric) {
        SplitDecimalWheelPicker(kg, { kg = it }, 30, 250, stringResource(R.string.unit_kg))
    } else {
        SplitDecimalWheelPicker(kg * 2.20462, { lbs -> kg = lbs / 2.20462 }, 66, 551, stringResource(R.string.unit_lbs))
    }
    Spacer(Modifier.height(16.dp))
    GradientSaveButton { onSave(kg) }
    Spacer(Modifier.height(8.dp))
}

internal enum class MealBoundary {
    BREAKFAST, LUNCH, DINNER, SNACK
}

@Composable
internal fun MealTimesSheet(current: MealSchedule, onSave: (MealSchedule) -> Unit) {
    var schedule by remember(current) { mutableStateOf(current.validatedOrDefault()) }
    var editing by remember { mutableStateOf<MealBoundary?>(null) }
    val context = LocalContext.current
    val formatter = remember(context) { DateTimeFormatter.ofPattern(clockTimePattern(context)) }
    fun formattedTime(minutes: Int): String =
        LocalTime.of(minutes / 60, minutes % 60).format(formatter)

    val selectedBoundary = editing
    if (selectedBoundary == null) {
        Text(
            stringResource(R.string.settings_meal_times),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(6.dp))
        Text(
            stringResource(R.string.settings_meal_times_description),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.62f)
        )
        Spacer(Modifier.height(16.dp))
        GlassSurface(
            modifier = Modifier.fillMaxWidth(),
            cornerRadius = 18.dp,
            padding = 0.dp
        ) {
            Column {
                MealBoundary.values().forEachIndexed { index, boundary ->
                    SettingRow(
                        label = stringResource(boundary.labelRes()),
                        value = formattedTime(boundary.valueIn(schedule))
                    ) { editing = boundary }
                    if (index != MealBoundary.values().lastIndex) HorizontalDivider()
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(
            stringResource(R.string.settings_meal_times_help),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.58f)
        )
        Spacer(Modifier.height(16.dp))
        GradientSaveButton { onSave(schedule) }
        GlassTextButton(
            text = stringResource(R.string.settings_restore_default_times),
            onClick = { schedule = MealSchedule.Default },
            modifier = Modifier.fillMaxWidth(),
            color = AppColors.Calorie
        )
        Spacer(Modifier.height(8.dp))
    } else {
        val allowed = selectedBoundary.allowedRange(schedule)
        var selectedMinutes by remember(selectedBoundary, schedule) {
            mutableIntStateOf(selectedBoundary.valueIn(schedule))
        }
        val options = remember(allowed, selectedMinutes) {
            ((allowed.first..allowed.last step 15).toList() + selectedMinutes)
                .filter { it in allowed }
                .distinct()
                .sorted()
        }
        val label = stringResource(selectedBoundary.labelRes())
        Text(
            stringResource(R.string.settings_meal_time_edit_format, label),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(16.dp))
        WheelPicker(
            items = options,
            selected = selectedMinutes,
            onSelect = { selectedMinutes = it },
            label = { formattedTime(it) }
        )
        Spacer(Modifier.height(16.dp))
        GradientSaveButton {
            schedule = selectedBoundary.updatedSchedule(schedule, selectedMinutes)
            editing = null
        }
        GlassTextButton(
            text = stringResource(R.string.action_cancel),
            onClick = { editing = null },
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.62f)
        )
        Spacer(Modifier.height(8.dp))
    }
}

internal fun MealBoundary.labelRes(): Int = when (this) {
    MealBoundary.BREAKFAST -> R.string.settings_breakfast_starts
    MealBoundary.LUNCH -> R.string.settings_lunch_starts
    MealBoundary.DINNER -> R.string.settings_dinner_starts
    MealBoundary.SNACK -> R.string.settings_late_snack_starts
}

internal fun MealBoundary.valueIn(schedule: MealSchedule): Int = when (this) {
    MealBoundary.BREAKFAST -> schedule.breakfastStartMinutes
    MealBoundary.LUNCH -> schedule.lunchStartMinutes
    MealBoundary.DINNER -> schedule.dinnerStartMinutes
    MealBoundary.SNACK -> schedule.snackStartMinutes
}

internal fun MealBoundary.allowedRange(schedule: MealSchedule): IntRange = when (this) {
    MealBoundary.BREAKFAST -> 0..(schedule.lunchStartMinutes - 15)
    MealBoundary.LUNCH -> (schedule.breakfastStartMinutes + 15)..(schedule.dinnerStartMinutes - 15)
    MealBoundary.DINNER -> (schedule.lunchStartMinutes + 15)..(schedule.snackStartMinutes - 15)
    MealBoundary.SNACK -> (schedule.dinnerStartMinutes + 15)..1439
}

internal fun MealBoundary.updatedSchedule(schedule: MealSchedule, minutes: Int): MealSchedule = when (this) {
    MealBoundary.BREAKFAST -> schedule.copy(breakfastStartMinutes = minutes)
    MealBoundary.LUNCH -> schedule.copy(lunchStartMinutes = minutes)
    MealBoundary.DINNER -> schedule.copy(dinnerStartMinutes = minutes)
    MealBoundary.SNACK -> schedule.copy(snackStartMinutes = minutes)
}

@Composable
internal fun WaterGoalSheet(current: Int, unit: WaterUnit, onSave: (Int) -> Unit) {
    val initialGoal = if (unit == WaterUnit.MILLILITERS) {
        (((current.coerceIn(50, 10_000) + 25) / 50) * 50).coerceIn(50, 10_000)
    } else {
        (current / WaterUnit.MILLILITERS_PER_FLUID_OUNCE).roundToInt().coerceIn(2, 338)
    }
    var goal by remember(current) { mutableIntStateOf(initialGoal) }
    Text(stringResource(R.string.settings_water_goal), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(20.dp))
    NumericWheelPicker(
        value = goal,
        onValueChange = { goal = it },
        min = if (unit == WaterUnit.MILLILITERS) 50 else 2,
        max = if (unit == WaterUnit.MILLILITERS) 10_000 else 338,
        unit = unit.symbol,
        step = if (unit == WaterUnit.MILLILITERS) 50 else 1
    )
    Spacer(Modifier.height(8.dp))
    Text(
        if (unit == WaterUnit.MILLILITERS) stringResource(R.string.settings_water_goal_wheel_help)
        else stringResource(R.string.settings_water_goal_wheel_help_fl_oz),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
    )
    Spacer(Modifier.height(16.dp))
    GradientSaveButton { onSave(unit.toMilliliters(goal.toDouble())) }
    Spacer(Modifier.height(8.dp))
}

@Composable
internal fun FastingGoalSheet(currentMinutes: Int, onSave: (Int) -> Unit) {
    var hours by remember(currentMinutes) { mutableIntStateOf((currentMinutes / 60).coerceIn(1, 168)) }
    Text(
        stringResource(R.string.settings_fasting_goal),
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.Bold
    )
    Spacer(Modifier.height(20.dp))
    NumericWheelPicker(
        value = hours,
        onValueChange = { hours = it },
        min = 1,
        max = 168,
        unit = stringResource(R.string.fasting_hours),
        step = 1
    )
    Spacer(Modifier.height(16.dp))
    GradientSaveButton { onSave(hours * 60) }
    Spacer(Modifier.height(8.dp))
}

@Composable
internal fun BodyFatSheet(current: Double?, onSave: (Double?) -> Unit) {
    var pct by remember(current) { mutableStateOf((current ?: 0.20) * 100) }
    Text(stringResource(R.string.sheet_body_fat_percent), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(12.dp))
    DecimalWheelPicker(pct, { pct = it }, 5.0, 60.0, 0.5, stringResource(R.string.unit_percent))
    Spacer(Modifier.height(12.dp))
    GradientSaveButton { onSave(pct / 100.0) }
    Spacer(Modifier.height(4.dp))
    TextButton(onClick = { onSave(null) }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.action_clear)) }
    Spacer(Modifier.height(8.dp))
}

/** Same wheel UX as BodyFatSheet, but framed as a goal — separate, optional,
 *  display-only. Seeds from the existing goal, falling back to the user's
 *  current body fat % so the wheel lands somewhere sensible on first open. */
@Composable
internal fun GoalBodyFatSheet(currentGoal: Double?, currentBodyFat: Double?, onSave: (Double?) -> Unit) {
    val seed = currentGoal ?: currentBodyFat ?: 0.15
    var pct by remember(currentGoal) { mutableStateOf(seed * 100) }
    Text(stringResource(R.string.sheet_goal_body_fat), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
    if (currentBodyFat != null) {
        Spacer(Modifier.height(4.dp))
        Text(
            stringResource(R.string.sheet_goal_body_fat_currently, (currentBodyFat * 100).toInt()),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
        )
    }
    Spacer(Modifier.height(12.dp))
    DecimalWheelPicker(pct, { pct = it }, 3.0, 60.0, 0.5, stringResource(R.string.unit_percent))
    Spacer(Modifier.height(12.dp))
    GradientSaveButton { onSave(pct / 100.0) }
    Spacer(Modifier.height(4.dp))
    TextButton(onClick = { onSave(null) }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.action_remove_goal)) }
    Spacer(Modifier.height(8.dp))
}

@Composable
internal fun GoalSpeedSheet(current: Double, goal: WeightGoal, useMetric: Boolean, onSave: (Double) -> Unit) {
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    Text(stringResource(R.string.sheet_weekly_change), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(12.dp))
    val wUnit = if (useMetric) stringResource(R.string.unit_kg) else stringResource(R.string.unit_lbs)
    val paceRes = if (goal == WeightGoal.LOSE) R.string.settings_pace_loss_format else R.string.settings_pace_gain_format
    val options = listOf(
        Triple(0.25, stringResource(R.string.onboarding_pace_slow), stringResource(paceRes, "${WeightDisplayFormatter.weeklyChangeValue(0.25, useMetric)} $wUnit")),
        Triple(0.5, stringResource(R.string.onboarding_pace_recommended), stringResource(paceRes, "${WeightDisplayFormatter.weeklyChangeValue(0.5, useMetric)} $wUnit")),
        Triple(1.0, stringResource(R.string.onboarding_pace_fast), stringResource(paceRes, "${WeightDisplayFormatter.weeklyChangeValue(1.0, useMetric)} $wUnit"))
    )
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for ((kg, title, subtitle) in options) {
            val isSel = kotlin.math.abs(kg - current) < 0.01
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(
                        if (isSel) AppColors.Calorie.copy(alpha = 0.13f)
                        else if (isDark) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                        else Color(0xFFEDE3DD).copy(alpha = 0.78f)
                    )
                    .clickable { onSave(kg) }
                    .padding(horizontal = 14.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(2.dp))
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
                if (isSel) {
                    Icon(
                        Icons.Filled.Check,
                        contentDescription = stringResource(R.string.cd_selected),
                        tint = AppColors.Calorie,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
    Spacer(Modifier.height(8.dp))
}

/**
 * Wheel-picker sheet for a single macro / calorie target. Mirrors iOS
 * NutritionPickerSheet exactly: title, wheel picker stepped at the requested
 * step, gradient Save button, optional "Reset to Auto-balance" link when the
 * macro is currently pinned.
 */
@Composable
fun NutritionPickerSheet(
    label: String,
    unit: String,
    currentValue: Int,
    range: IntRange,
    step: Int,
    onSave: (Int) -> Unit,
    onResetToAuto: (() -> Unit)? = null,
    resetLabel: String? = null,
    // Live wheel-selection reporter, for hosts that need the current value
    // before Save (e.g. to convert it when a unit switcher flips).
    onValueChange: ((Int) -> Unit)? = null,
    allowCustomValue: Boolean = false,
    guidanceUpperLimit: Int? = null,
    customValueDetail: ((Int) -> String?)? = null
) {
    val items = remember(range, step) { (range.first..range.last step step).toList() }
    val snapped = (currentValue / step) * step
    val initial = snapped.coerceIn(range.first, range.last).let { v ->
        items.minByOrNull { kotlin.math.abs(it - v) } ?: items.first()
    }
    var selected by remember(initial) { mutableStateOf(initial) }
    val isPresetValue = currentValue in range && (currentValue - range.first) % step == 0
    var customMode by remember(currentValue, range, step, allowCustomValue) {
        mutableStateOf(allowCustomValue && !isPresetValue)
    }
    var customText by remember(currentValue) { mutableStateOf(currentValue.toString()) }
    val customValue = customText.toIntOrNull()
        ?.takeIf { it in 0..OptionalNutrientGoals.MaximumCustomGoal }
    val valueToSave = if (customMode) customValue else selected

    Text(label, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(12.dp))
    if (customMode) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            GlassTextField(
                value = customText,
                onValueChange = { value ->
                    customText = value.filter(Char::isDigit).take(7)
                },
                modifier = Modifier.weight(1f),
                placeholder = "0",
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                textStyle = MaterialTheme.typography.titleMedium.copy(
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Medium
                )
            )
            Spacer(Modifier.width(10.dp))
            Text(
                unit,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
        }
        customValue?.let { value ->
            customValueDetail?.invoke(value)?.let { detail ->
                Spacer(Modifier.height(8.dp))
                Text(
                    detail,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
                )
            }
        }
        if (customValue != null && guidanceUpperLimit != null && customValue > guidanceUpperLimit) {
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.nutrient_custom_upper_warning),
                modifier = Modifier.fillMaxWidth(),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
                color = Color(0xFFFF9F0A)
            )
        } else if (customValue == null) {
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.nutrient_custom_invalid),
                modifier = Modifier.fillMaxWidth(),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
        }
    } else {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            com.ayuvo.health.ui.components.WheelPicker(
                items = items,
                selected = selected,
                onSelect = { selected = it; onValueChange?.invoke(it) },
                modifier = Modifier.width(120.dp)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                unit,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
        }
    }
    if (allowCustomValue) {
        TextButton(
            onClick = {
                customMode = !customMode
                if (customMode) customText = selected.toString()
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                stringResource(if (customMode) R.string.nutrient_use_preset_wheel else R.string.nutrient_custom_amount),
                color = AppColors.Calorie,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
    Spacer(Modifier.height(16.dp))
    Box(
        Modifier
            .fillMaxWidth()
            .height(54.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(AppColors.CalorieGradient)
            .clickable(enabled = valueToSave != null) {
                valueToSave?.let(onSave)
            },
        contentAlignment = Alignment.Center
    ) {
        Text(
            stringResource(R.string.action_save),
            color = Color.White.copy(alpha = if (valueToSave == null) 0.45f else 1f),
            fontWeight = FontWeight.SemiBold,
            style = MaterialTheme.typography.titleMedium
        )
    }
    if (onResetToAuto != null) {
        Spacer(Modifier.height(4.dp))
        TextButton(
            onClick = onResetToAuto,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                resetLabel ?: stringResource(R.string.settings_reset_autobalance),
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
        }
    }
    Spacer(Modifier.height(8.dp))
}

@Composable
internal fun MacrosSheet(
    profile: com.ayuvo.health.models.UserProfile?,
    onSaveCalories: (Int?) -> Unit,
    onSaveMacro: (AutoBalanceMacro, Int?) -> Unit,
    onClearPin: (AutoBalanceMacro) -> Unit
) {
    profile ?: return
    var caloriesText by remember(profile) { mutableStateOf(profile.effectiveCalories.toString()) }
    var proteinText by remember(profile) { mutableStateOf(profile.effectiveProtein.toString()) }
    var carbsText by remember(profile) { mutableStateOf(profile.effectiveCarbs.toString()) }
    var fatText by remember(profile) { mutableStateOf(profile.effectiveFat.toString()) }
    Text(stringResource(R.string.sheet_macros), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
    Text(
        stringResource(R.string.settings_macro_pin_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
    )
    Spacer(Modifier.height(12.dp))
    MacroField(stringResource(R.string.macro_calories), caloriesText, { caloriesText = it }, stringResource(R.string.unit_kcal)) {
        caloriesText.toIntOrNull()?.let { onSaveCalories(it) }
    }
    Spacer(Modifier.height(6.dp))
    MacroField(
        label = if (profile.isPinned(AutoBalanceMacro.PROTEIN)) stringResource(R.string.settings_macro_pinned_label_format, stringResource(R.string.autobalance_protein)) else stringResource(R.string.settings_macro_auto_label_format, stringResource(R.string.autobalance_protein)),
        value = proteinText,
        onChange = { proteinText = it },
        unit = stringResource(R.string.unit_g),
        pinned = profile.isPinned(AutoBalanceMacro.PROTEIN),
        onClearPin = { onClearPin(AutoBalanceMacro.PROTEIN) }
    ) { proteinText.toIntOrNull()?.let { onSaveMacro(AutoBalanceMacro.PROTEIN, it) } }
    Spacer(Modifier.height(6.dp))
    MacroField(
        label = if (profile.isPinned(AutoBalanceMacro.CARBS)) stringResource(R.string.settings_macro_pinned_label_format, stringResource(R.string.autobalance_carbs)) else stringResource(R.string.settings_macro_auto_label_format, stringResource(R.string.autobalance_carbs)),
        value = carbsText,
        onChange = { carbsText = it },
        unit = stringResource(R.string.unit_g),
        pinned = profile.isPinned(AutoBalanceMacro.CARBS),
        onClearPin = { onClearPin(AutoBalanceMacro.CARBS) }
    ) { carbsText.toIntOrNull()?.let { onSaveMacro(AutoBalanceMacro.CARBS, it) } }
    Spacer(Modifier.height(6.dp))
    MacroField(
        label = if (profile.isPinned(AutoBalanceMacro.FAT)) stringResource(R.string.settings_macro_pinned_label_format, stringResource(R.string.autobalance_fat)) else stringResource(R.string.settings_macro_auto_label_format, stringResource(R.string.autobalance_fat)),
        value = fatText,
        onChange = { fatText = it },
        unit = stringResource(R.string.unit_g),
        pinned = profile.isPinned(AutoBalanceMacro.FAT),
        onClearPin = { onClearPin(AutoBalanceMacro.FAT) }
    ) { fatText.toIntOrNull()?.let { onSaveMacro(AutoBalanceMacro.FAT, it) } }
}

@Composable
internal fun MacroField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    unit: String,
    pinned: Boolean = false,
    onClearPin: (() -> Unit)? = null,
    onPin: () -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        GlassTextField(
            value = value,
            onValueChange = onChange,
            placeholder = "$label ($unit)",
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
            modifier = Modifier.weight(1f)
        )
        Spacer(Modifier.height(6.dp))
        TextButton(onClick = { if (pinned) onClearPin?.invoke() else onPin() }) {
            Text(if (pinned) stringResource(R.string.action_clear) else stringResource(R.string.action_pin), color = AppColors.Calorie)
        }
    }
}

internal fun OptionalNutrient.pickerRange(): IntRange = when (this) {
    OptionalNutrient.SUGAR -> 0..200
    OptionalNutrient.ADDED_SUGAR -> 0..100
    OptionalNutrient.FIBER -> 0..100
    OptionalNutrient.SATURATED_FAT -> 0..80
    OptionalNutrient.CHOLESTEROL -> 0..1000
    OptionalNutrient.CAFFEINE -> 0..1000
    OptionalNutrient.CREATINE,
    OptionalNutrient.BETA_ALANINE,
    OptionalNutrient.L_CITRULLINE,
    OptionalNutrient.L_CARNITINE,
    OptionalNutrient.L_ARGININE,
    OptionalNutrient.TAURINE,
    OptionalNutrient.BETAINE,
    OptionalNutrient.HMB -> 0..50
    OptionalNutrient.SODIUM -> 0..5000
    OptionalNutrient.POTASSIUM -> 0..7000
    OptionalNutrient.TRANS_FAT -> 0..10
    OptionalNutrient.CALCIUM -> 300..2000
    OptionalNutrient.IRON -> 5..45
    OptionalNutrient.MAGNESIUM -> 100..800
    OptionalNutrient.ZINC -> 3..40
    OptionalNutrient.VITAMIN_A -> 300..3000
    OptionalNutrient.VITAMIN_C -> 20..500
    OptionalNutrient.VITAMIN_D -> 5..100
    OptionalNutrient.VITAMIN_B12 -> 1..20
    OptionalNutrient.VITAMIN_E -> 5..100
    OptionalNutrient.VITAMIN_K -> 30..300
    OptionalNutrient.FOLATE -> 100..1000
    OptionalNutrient.OMEGA3 -> 0..10
}

internal fun OptionalNutrient.pickerStep(): Int = when (this) {
    OptionalNutrient.FIBER,
    OptionalNutrient.SATURATED_FAT,
    OptionalNutrient.TRANS_FAT,
    OptionalNutrient.IRON,
    OptionalNutrient.ZINC,
    OptionalNutrient.VITAMIN_D,
    OptionalNutrient.VITAMIN_B12,
    OptionalNutrient.VITAMIN_E,
    OptionalNutrient.OMEGA3,
    OptionalNutrient.CREATINE,
    OptionalNutrient.BETA_ALANINE,
    OptionalNutrient.L_CITRULLINE,
    OptionalNutrient.L_CARNITINE,
    OptionalNutrient.L_ARGININE,
    OptionalNutrient.TAURINE,
    OptionalNutrient.BETAINE,
    OptionalNutrient.HMB -> 1
    OptionalNutrient.CHOLESTEROL,
    OptionalNutrient.CAFFEINE -> 25
    OptionalNutrient.SODIUM,
    OptionalNutrient.POTASSIUM,
    OptionalNutrient.CALCIUM,
    OptionalNutrient.VITAMIN_A,
    OptionalNutrient.FOLATE -> 50
    OptionalNutrient.MAGNESIUM -> 25
    OptionalNutrient.VITAMIN_C,
    OptionalNutrient.VITAMIN_K -> 10
    OptionalNutrient.SUGAR,
    OptionalNutrient.ADDED_SUGAR -> 5
}

/**
 * General adult upper intake levels used as non-blocking custom-goal guidance.
 * Nutrients without a clear food-inclusive upper level intentionally return null.
 */
internal fun OptionalNutrient.generalAdultUpperLimit(): Int? = when (this) {
    OptionalNutrient.CALCIUM -> 2_500
    OptionalNutrient.IRON -> 45
    OptionalNutrient.ZINC -> 40
    OptionalNutrient.VITAMIN_A -> 3_000
    OptionalNutrient.VITAMIN_C -> 2_000
    OptionalNutrient.VITAMIN_D -> 100
    OptionalNutrient.VITAMIN_E -> 1_000
    OptionalNutrient.FOLATE -> 1_000
    else -> null
}

internal fun OptionalNutrient.customValueDetail(value: Int): String? =
    if (this == OptionalNutrient.VITAMIN_D) "${value * 40} IU" else null

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BirthdaySheet(current: Instant, onSave: (Instant) -> Unit) {
    // Material3 DatePicker stores selection as UTC-midnight millis. We store
    // birthdays as a local-zone Instant. Round-trip both sides through the
    // user's local date to avoid an off-by-one when the user is east of UTC.
    val localDate = current.atZone(ZoneId.systemDefault()).toLocalDate()
    var pickedDate by remember(current) { mutableStateOf(localDate) }
    Text(stringResource(R.string.sheet_birthday), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(8.dp))
    DateWheelPicker(
        selected = pickedDate,
        onSelect = { pickedDate = it },
        maxYear = LocalDate.now().year,
        modifier = Modifier.fillMaxWidth()
    )
    Spacer(Modifier.height(12.dp))
    GradientSaveButton {
        val newInstant = pickedDate.atStartOfDay(ZoneId.systemDefault()).toInstant()
        onSave(newInstant)
    }
    Spacer(Modifier.height(8.dp))
}

// Closest Material mappings for the iOS SF Symbols used in picker rows.
internal fun genderIcon(g: Gender): ImageVector = when (g) {
    Gender.MALE -> Icons.Outlined.Male
    Gender.FEMALE -> Icons.Outlined.Female
    Gender.OTHER -> Icons.Outlined.Wc
}

internal fun activityIcon(a: ActivityLevel): ImageVector = when (a) {
    ActivityLevel.SEDENTARY -> Icons.Outlined.SelfImprovement
    ActivityLevel.LIGHT -> Icons.AutoMirrored.Outlined.DirectionsWalk
    ActivityLevel.MODERATE -> Icons.AutoMirrored.Outlined.DirectionsRun
    ActivityLevel.ACTIVE -> Icons.Outlined.LocalDining
    ActivityLevel.VERY_ACTIVE -> Icons.Outlined.FitnessCenter
    ActivityLevel.EXTRA_ACTIVE -> Icons.Outlined.SportsMartialArts
}

internal fun goalIcon(g: WeightGoal): ImageVector = when (g) {
    WeightGoal.LOSE -> Icons.AutoMirrored.Filled.TrendingDown
    WeightGoal.MAINTAIN -> Icons.AutoMirrored.Filled.TrendingFlat
    WeightGoal.GAIN -> Icons.AutoMirrored.Outlined.TrendingUp
}

internal fun appearanceIcon(key: String): ImageVector = when (key) {
    "light" -> Icons.Outlined.LightMode
    "dark" -> Icons.Outlined.DarkMode
    else -> Icons.Outlined.SettingsBrightness
}

/**
 * Pink-gradient capsule "Save" button matching the iOS picker sheets
 * (`LinearGradient(colors: AppColors.calorieGradient)` over a 14dp rounded
 * rectangle, white semibold label).
 */
@Composable
internal fun GradientSaveButton(
    text: String? = null,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val brush = Brush.linearGradient(listOf(AppColors.CalorieStart, AppColors.CalorieEnd))
    val shape = RoundedCornerShape(14.dp)
    Box(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (enabled) brush else Brush.linearGradient(listOf(AppColors.Calorie.copy(alpha = 0.4f), AppColors.Calorie.copy(alpha = 0.4f))))
            .background(
                Brush.verticalGradient(
                    listOf(
                        Color.White.copy(alpha = 0.24f),
                        Color.White.copy(alpha = 0.04f)
                    )
                )
            )
            .border(0.7.dp, Color.White.copy(alpha = 0.22f), shape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 14.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text ?: stringResource(R.string.action_save), color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
    }
}

/** Rename, re-point or remove one saved model (docs/ai-models.md 3). */
@Composable
private fun ModelProfileActionsSheet(
    profile: AiProfileUi,
    onAction: (SettingsSheet) -> Unit,
    onDelete: () -> Unit
) {
    Column(Modifier.fillMaxWidth()) {
        Text(
            text = profile.nickname.ifEmpty { profile.providerToken },
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(bottom = 4.dp)
        )
        Text(
            text = if (profile.model.isEmpty()) profile.providerToken
            else "${profile.providerToken} \u00b7 ${profile.model}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 12.dp)
        )
        SheetAction(stringResource(R.string.sheet_model_rename)) {
            onAction(SettingsSheet.MODEL_PROFILE_RENAME)
        }
        SheetAction(stringResource(R.string.sheet_model_change_model)) {
            onAction(SettingsSheet.MODEL_PROFILE_MODEL)
        }
        if (profile.provider?.requiresApiKey == true) {
            SheetAction(stringResource(R.string.sheet_model_key)) {
                onAction(SettingsSheet.MODEL_PROFILE_KEY)
            }
        }
        if (profile.provider?.usesServiceAccount == true) {
            SheetAction(stringResource(R.string.sheet_model_vertex)) {
                onAction(SettingsSheet.MODEL_PROFILE_VERTEX)
            }
        }
        SheetAction(stringResource(R.string.sheet_model_use_primary)) {
            onAction(SettingsSheet.AI_PROVIDER)
        }
        Text(
            text = stringResource(R.string.sheet_model_delete_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 12.dp, bottom = 4.dp)
        )
        SheetAction(stringResource(R.string.sheet_model_delete), destructive = true, onClick = onDelete)
    }
}

@Composable
private fun SheetAction(label: String, destructive: Boolean = false, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            modifier = Modifier.fillMaxWidth()
        )
    }
}
