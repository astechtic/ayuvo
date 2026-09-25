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

@Composable
internal fun SectionCard(title: String? = null, content: @Composable () -> Unit) {
    Column {
        if (title != null) {
            Text(
                title,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.72f),
                modifier = Modifier.padding(start = 6.dp, bottom = 8.dp)
            )
        }
        GlassSurface(
            modifier = Modifier.fillMaxWidth(),
            cornerRadius = 20.dp,
            padding = 0.dp
        ) {
            Column(Modifier.padding(vertical = 2.dp)) { content() }
        }
    }
}

/** Download/cancel/delete row for an on-device model; shared with onboarding's on-device AI step. */
@Composable
internal fun LocalModelRow(
    state: LocalModelState,
    onDownload: () -> Unit,
    onDelete: () -> Unit,
    icon: ImageVector? = null,
    iconTint: Color = com.ayuvo.health.ui.design.AyuvoPalette.Other
) {
    val uriHandler = LocalUriHandler.current
    val unavailableText = when (state.ineligibility) {
        LocalModelIneligibility.INSUFFICIENT_MEMORY ->
            // The model's own gate: Qwen3 14B needs 20 GB and MedGemma 12 GB, not "8" for every row.
            stringResource(R.string.settings_model_requires_ram_format, state.descriptor.minimumMemoryClassGb ?: 8)
        LocalModelIneligibility.LOW_RAM_DEVICE ->
            stringResource(R.string.settings_model_low_ram_unsupported)
        LocalModelIneligibility.UNSUPPORTED_ABI ->
            stringResource(R.string.settings_model_processor_unsupported)
        null -> null
    }
    val statusText = unavailableText ?: when (val status = state.status) {
        LocalModelInstallStatus.NotInstalled -> stringResource(
            R.string.settings_local_model_not_downloaded_format,
            formatModelBytes(state.descriptor.expectedBytes)
        )
        is LocalModelInstallStatus.Downloading -> stringResource(
            R.string.settings_local_model_downloading_format,
            ((status.downloadedBytes * 100L) / status.totalBytes.coerceAtLeast(1L)).coerceIn(0L, 100L)
        )
        LocalModelInstallStatus.Installed -> stringResource(R.string.settings_local_model_installed)
        is LocalModelInstallStatus.Failed -> status.message
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            com.ayuvo.health.ui.design.CategoryIcon(icon, iconTint)
            Spacer(Modifier.width(16.dp))
        }
        Column(Modifier.weight(1f).padding(end = 8.dp)) {
            Text(
                state.descriptor.displayName,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium
            )
            Text(
                statusText,
                style = MaterialTheme.typography.bodySmall,
                color = if (state.status is LocalModelInstallStatus.Failed) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.58f)
                },
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Row(
                modifier = Modifier.padding(top = 3.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    stringResource(
                        R.string.settings_model_license_format,
                        // An SPDX `LicenseRef-…` id is a catalogue key, not something to show a person;
                        // Google's own short name for MedGemma's terms is HAI-DEF.
                        state.descriptor.licenseName.let {
                            if (it == "LicenseRef-HealthAI-DeveloperFoundations") "HAI-DEF" else it.removePrefix("LicenseRef-")
                        }
                    ),
                    color = AppColors.Calorie,
                    style = MaterialTheme.typography.labelMedium,
                    // The licence label shrinks, and the source link never wraps: a long licence name
                    // used to squeeze "Model source" into a one-letter-wide column.
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false).clickable {
                        uriHandler.openUri(state.descriptor.licenseUrl)
                    }
                )
                Text(
                    stringResource(R.string.settings_model_source),
                    color = AppColors.Calorie,
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    softWrap = false,
                    modifier = Modifier.clickable {
                        uriHandler.openUri(state.descriptor.sourceUrl)
                    }
                )
            }
        }
        when (val status = state.status) {
            is LocalModelInstallStatus.Downloading -> {
                CircularProgressIndicator(
                    progress = {
                        status.downloadedBytes.toFloat() / status.totalBytes.coerceAtLeast(1L).toFloat()
                    },
                    modifier = Modifier.size(24.dp),
                    strokeWidth = 2.dp,
                    color = AppColors.Calorie
                )
                TextButton(onClick = onDelete) { Text(stringResource(R.string.action_cancel)) }
            }
            LocalModelInstallStatus.Installed ->
                TextButton(onClick = onDelete) { Text(stringResource(R.string.action_delete)) }
            LocalModelInstallStatus.NotInstalled,
            is LocalModelInstallStatus.Failed ->
                TextButton(onClick = onDownload, enabled = state.eligible) {
                    Text(stringResource(R.string.action_download))
                }
        }
    }
}

internal fun formatModelBytes(bytes: Long): String = if (bytes >= 1_000_000_000L) {
    String.format(Locale.US, "%.1f GB", bytes / 1_000_000_000.0)
} else {
    String.format(Locale.US, "%.0f MB", bytes / 1_000_000.0)
}

@Composable
internal fun SettingRow(
    label: String,
    value: String,
    icon: ImageVector? = null,
    leadingContent: (@Composable () -> Unit)? = null,
    // iOS `.menu` Picker rows render a `chevron.up.chevron.down` instead of a
    // right-chevron to signal the inline dropdown affordance. Pass inlineMenu=true
    // for Gender, Weight Goal, and Activity Level.
    inlineMenu: Boolean = false,
    onClick: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (leadingContent != null) {
            Box(Modifier.size(22.dp), contentAlignment = Alignment.Center) {
                leadingContent()
            }
            Spacer(Modifier.width(14.dp))
        } else if (icon != null) {
            IconBubble(icon = icon, size = 22.dp, iconSize = 14.dp)
            Spacer(Modifier.width(14.dp))
        }
        Text(
            label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyLarge
        )
        if (value.isNotEmpty()) {
            Text(
                value,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.58f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 12.dp)
            )
        }
        Icon(
            if (inlineMenu) Icons.Filled.UnfoldMore else Icons.Filled.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
            modifier = if (inlineMenu) Modifier.size(18.dp) else Modifier
        )
    }
}

/**
 * Multi-line text editor with a Save row at the bottom that pulses brand pink
 * when the current text differs from the persisted value, mirrors iOS Custom
 * AI Instructions section.
 */
@Composable
internal fun CustomInstructionsBlock(
    initial: String,
    placeholder: String,
    onSave: (String) -> Unit
) {
    var text by remember(initial) { mutableStateOf(initial) }
    var saved by remember(initial) { mutableStateOf(initial) }
    val hasChanges = text != saved
    Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
        GlassTextField(
            value = text,
            onValueChange = { text = it },
            placeholder = placeholder,
            modifier = Modifier.fillMaxWidth().heightIn(min = 110.dp),
            singleLine = false,
            minLines = 4,
            maxLines = 6
        )
        Spacer(Modifier.height(8.dp))
        TextButton(
            onClick = {
                onSave(text)
                saved = text.trim()
                text = saved
            },
            enabled = hasChanges,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(
                Icons.Filled.Check,
                contentDescription = null,
                tint = if (hasChanges) AppColors.Calorie else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                stringResource(R.string.settings_save),
                color = if (hasChanges) AppColors.Calorie else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

internal fun feetInchesLabel(cm: Int): String {
    // Round to the nearest inch — truncating shows 5'6" for a 170 cm / 5'7" pick.
    val totalInches = Math.round(cm / 2.54).toInt()
    val feet = totalInches / 12
    val inches = totalInches % 12
    return "$feet' $inches\""
}

@Composable
internal fun optionalNutrientSummary(goals: OptionalNutrientGoals): String =
    stringResource(R.string.nutrient_fiber_format, goals.fiber.toString()) + ", " +
        stringResource(R.string.nutrient_sodium_format, goals.sodium.toString())

@Composable
internal fun birthdayDisplay(profile: UserProfile): String {
    val date = profile.birthday.atZone(ZoneId.systemDefault()).toLocalDate()
    val formatted = date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(Locale.getDefault()))
    return stringResource(R.string.settings_birthday_age_format, formatted, profile.age)
}
