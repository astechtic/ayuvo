package com.ayuvo.health.ui.cycle

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ayuvo.health.R
import com.ayuvo.health.cycle.data.CyclePreferences
import com.ayuvo.health.ui.design.AyuvoColors
import com.ayuvo.health.ui.design.AyuvoSpacing
import com.ayuvo.health.ui.design.AyuvoTopBar
import com.ayuvo.health.ui.design.GroupRow
import com.ayuvo.health.ui.design.InsetGroup
import com.ayuvo.health.ui.design.RowTrailing
import com.ayuvo.health.ui.home.SheetDatePickerDialog
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/**
 * First-run setup (docs/cycle-tracking.md §5 "Setup"): only the minimum, and every question can be skipped — last
 * period start (or "I don't remember"), typical cycle and period length ("Not sure" = defaults), reminders and
 * Health Connect sync, with the privacy explanation.
 */
@Composable
fun CycleSetupScreen(vm: CycleViewModel, onBack: () -> Unit, onDone: () -> Unit) {
    val cfg = vm.config
    val context = LocalContext.current
    var lastStart by rememberSaveable { mutableStateOf<String?>(null) }
    var cycleLength by rememberSaveable { mutableStateOf<Int?>(null) }
    var periodLength by rememberSaveable { mutableStateOf<Int?>(null) }
    var remindSoon by rememberSaveable { mutableStateOf(true) }
    var healthSync by rememberSaveable { mutableStateOf(false) }
    var picking by rememberSaveable { mutableStateOf(false) }
    val fmt = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(Locale.getDefault())
    val healthLauncher = rememberLauncherForActivityResult(vm.healthPermissionContract()) { granted ->
        healthSync = com.ayuvo.health.cycle.health.CycleHealthConnectWriter.WRITE_PERMISSION in granted
    }
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { AyuvoTopBar(title = stringResource(R.string.cycle_setup_title), onBack = onBack) }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).testTag("cycle.setup"),
            contentPadding = PaddingValues(start = AyuvoSpacing.ScreenH, end = AyuvoSpacing.ScreenH, top = 8.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(AyuvoSpacing.SectionGap)
        ) {
            item {
                Text(stringResource(R.string.cycle_setup_intro), fontSize = 15.sp, color = AyuvoColors.secondaryLabel(), modifier = Modifier.padding(horizontal = 4.dp))
            }
            item {
                InsetGroup {
                    row {
                        GroupRow(
                            stringResource(R.string.cycle_setup_last_start),
                            value = lastStart?.let { fmt.format(LocalDate.parse(it)) } ?: stringResource(R.string.cycle_setup_dont_remember),
                            modifier = Modifier.testTag("cycle.setup.lastStart"),
                            onClick = { picking = true }
                        )
                    }
                    if (lastStart != null) {
                        row {
                            GroupRow(
                                stringResource(R.string.cycle_setup_dont_remember),
                                trailing = RowTrailing.None,
                                onClick = { lastStart = null }
                            )
                        }
                    }
                }
            }
            item {
                InsetGroup(footer = stringResource(R.string.cycle_setup_lengths_footer)) {
                    row {
                        LengthStepper(
                            title = stringResource(R.string.cycle_setup_cycle_length),
                            value = cycleLength, default = cfg.defaults.cycleLength,
                            min = cfg.limits.settingCycleMin, max = cfg.limits.settingCycleMax,
                            tag = "cycle.setup.cycleLength", onChange = { cycleLength = it }
                        )
                    }
                    row {
                        LengthStepper(
                            title = stringResource(R.string.cycle_setup_period_length),
                            value = periodLength, default = cfg.defaults.periodLength,
                            min = cfg.limits.settingPeriodMin, max = cfg.limits.settingPeriodMax,
                            tag = "cycle.setup.periodLength", onChange = { periodLength = it }
                        )
                    }
                }
            }
            item {
                InsetGroup(header = stringResource(R.string.cycle_reminders_header), footer = stringResource(R.string.cycle_rem_discreet_footer)) {
                    row {
                        GroupRow(
                            stringResource(R.string.cycle_rem_period_soon),
                            icon = Icons.Filled.Notifications, iconTint = CycleColors.Period,
                            trailing = RowTrailing.Toggle(remindSoon, { remindSoon = it }),
                            modifier = Modifier.testTag("cycle.setup.remind")
                        )
                    }
                }
            }
            if (vm.healthAvailable()) {
                item {
                    InsetGroup(footer = stringResource(R.string.cycle_health_sync_sub)) {
                        row {
                            GroupRow(
                                stringResource(R.string.cycle_health_sync),
                                icon = Icons.Filled.Favorite, iconTint = CycleColors.Period,
                                trailing = RowTrailing.Toggle(healthSync, { on ->
                                    if (on) runCatching { healthLauncher.launch(vm.healthPermissions) } else healthSync = false
                                }),
                                modifier = Modifier.testTag("cycle.setup.health")
                            )
                        }
                    }
                }
            }
            item {
                Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.Top) {
                    Icon(Icons.Filled.Lock, contentDescription = null, tint = AyuvoColors.secondaryLabel(), modifier = Modifier.size(18.dp))
                    Text(stringResource(R.string.cycle_privacy_note), fontSize = 13.sp, color = AyuvoColors.secondaryLabel(), modifier = Modifier.padding(start = 8.dp))
                }
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            if (remindSoon && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                runCatching { notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) }
                            }
                            val prefs = CyclePreferences(periodSoon = remindSoon, healthSync = healthSync)
                            vm.completeSetup(lastStart?.let(LocalDate::parse), cycleLength, periodLength, prefs, onDone)
                        },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("cycle.setup.done"),
                        colors = ButtonDefaults.buttonColors(containerColor = CycleColors.Period)
                    ) { Text(stringResource(R.string.cycle_setup_done), fontSize = 17.sp, fontWeight = FontWeight.SemiBold) }
                    Text(CycleText.disclaimer(context, cfg), fontSize = 12.sp, lineHeight = 16.sp, color = AyuvoColors.secondaryLabel(), modifier = Modifier.padding(horizontal = 8.dp))
                }
            }
        }
    }
    if (picking) {
        SheetDatePickerDialog(
            initialDate = lastStart?.let(LocalDate::parse) ?: LocalDate.now(),
            onConfirm = { d ->
                picking = false
                lastStart = (if (d.isAfter(LocalDate.now())) LocalDate.now() else d).toString()
            },
            onDismiss = { picking = false }
        )
    }
}

/** "Not sure" (null = configured default) or a number of days with − / + buttons (48 dp targets). */
@Composable
internal fun LengthStepper(title: String, value: Int?, default: Int, min: Int, max: Int, tag: String, onChange: (Int?) -> Unit) {
    val shown = value ?: default
    val valueText = if (value == null) stringResource(R.string.cycle_setup_not_sure) else pluralStringResource(R.plurals.cycle_days, shown, shown)
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = 16.dp, end = 4.dp).testTag(tag)
            .semantics { contentDescription = title; stateDescription = valueText },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 16.sp)
            Text(valueText, fontSize = 13.sp, color = AyuvoColors.secondaryLabel())
        }
        IconButton(onClick = { onChange((shown - 1).coerceAtLeast(min)) }, enabled = shown > min || value == null, modifier = Modifier.size(48.dp)) {
            Icon(Icons.Filled.Remove, contentDescription = stringResource(R.string.cycle_decrease, title))
        }
        IconButton(onClick = { onChange((shown + 1).coerceAtMost(max)) }, enabled = shown < max || value == null, modifier = Modifier.size(48.dp)) {
            Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.cycle_increase, title))
        }
    }
}
