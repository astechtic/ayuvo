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

/**
 * Port of iOS CalculationMethodsView. Documents every formula Ayuvo uses as the reference its AI
 * goal calculation starts from (BMR, TDEE, calorie target, macro split) plus per-meal estimates,
 * with peer-reviewed sources. Styled to match the rest of Android Settings (glass cards, back row,
 * 28sp title). Reachable from Settings → Goals & Nutrition → Calculation Methods.
 */
@Composable
fun CalculationMethodsScreen(
    onBack: () -> Unit
) {
    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                top = 14.dp,
                bottom = BottomNavScrollPadding
            ),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(16.dp))
                            .clickable { onBack() }
                            .padding(horizontal = 2.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = null,
                            tint = AppColors.Calorie,
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.nav_settings), color = AppColors.Calorie, fontWeight = FontWeight.SemiBold)
                    }
                }
            }

            item {
                Text(
                    stringResource(R.string.settings_calc_methods),
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.settings_calc_intro),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.62f)
                )
            }

            item {
                CalcMethodSection(stringResource(R.string.settings_calc_sec_bmr)) {
                    CalcFormulaCard(
                        name = stringResource(R.string.settings_calc_mifflin_name),
                        usedWhen = stringResource(R.string.settings_calc_mifflin_used),
                        formula = stringResource(R.string.settings_calc_mifflin_formula),
                        citation = stringResource(R.string.settings_calc_mifflin_citation),
                        url = "https://pubmed.ncbi.nlm.nih.gov/2305711/"
                    )
                    CalcFormulaCard(
                        name = stringResource(R.string.settings_calc_katch_name),
                        usedWhen = stringResource(R.string.settings_calc_katch_used),
                        formula = stringResource(R.string.settings_calc_katch_formula),
                        citation = stringResource(R.string.settings_calc_katch_citation),
                        url = null
                    )
                }
            }

            item {
                CalcMethodSection(stringResource(R.string.settings_calc_sec_tdee)) {
                    CalcFormulaCard(
                        name = stringResource(R.string.settings_calc_tdee_name),
                        usedWhen = stringResource(R.string.settings_calc_tdee_used),
                        formula = stringResource(R.string.settings_calc_tdee_formula),
                        citation = stringResource(R.string.settings_calc_tdee_citation),
                        url = "https://www.fao.org/3/y5686e/y5686e00.htm"
                    )
                }
            }

            item {
                CalcMethodSection(stringResource(R.string.settings_calc_calorie_target)) {
                    CalcFormulaCard(
                        name = stringResource(R.string.settings_calc_target_name),
                        usedWhen = stringResource(R.string.settings_calc_target_used),
                        formula = stringResource(R.string.settings_calc_target_formula),
                        citation = stringResource(R.string.settings_calc_target_citation),
                        url = "https://www.thelancet.com/journals/lancet/article/PIIS0140-6736(11)60812-X/fulltext"
                    )
                }
            }

            item {
                CalcMethodSection(stringResource(R.string.settings_calc_macro_split)) {
                    CalcFormulaCard(
                        name = stringResource(R.string.settings_calc_split_name),
                        usedWhen = stringResource(R.string.settings_calc_split_used),
                        formula = stringResource(R.string.settings_calc_split_formula),
                        citation = stringResource(R.string.settings_calc_split_citation),
                        url = "https://bjsm.bmj.com/content/52/6/376"
                    )
                }
            }

            item {
                CalcMethodSection(stringResource(R.string.settings_calc_micro_values)) {
                    CalcFormulaCard(
                        name = stringResource(R.string.settings_calc_micro_name),
                        usedWhen = stringResource(R.string.settings_calc_micro_used),
                        formula = null,
                        citation = stringResource(R.string.settings_calc_micro_citation),
                        url = "https://fdc.nal.usda.gov/"
                    )
                }
            }

            item {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color(0xFFFF9800).copy(alpha = 0.09f))
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        stringResource(R.string.settings_not_medical_title),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Text(
                        stringResource(R.string.settings_not_medical_body),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
                    )
                }
            }
        }
    }
}

@Composable
internal fun CalcMethodSection(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(start = 4.dp)
        )
        content()
    }
}

@Composable
internal fun CalcFormulaCard(
    name: String,
    usedWhen: String,
    formula: String?,
    citation: String,
    url: String?
) {
    val uriHandler = LocalUriHandler.current
    GlassSurface(
        modifier = Modifier.fillMaxWidth(),
        cornerRadius = 18.dp,
        padding = 14.dp
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                name,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                usedWhen,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.62f)
            )
            if (formula != null) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f))
                        .padding(10.dp)
                ) {
                    Text(
                        formula,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f)
                    )
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    "SOURCE",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
                )
                Text(
                    citation,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
                if (url != null) {
                    Text(
                        "Open source ↗",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium,
                        color = AppColors.Calorie,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .clickable { uriHandler.openUri(url) }
                            .padding(vertical = 2.dp)
                    )
                }
            }
        }
    }
}
