package com.ayuvo.health.ui.medications

import android.Manifest
import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Medication
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.ayuvo.health.R
import com.ayuvo.health.medications.model.DoseAction
import com.ayuvo.health.medications.model.DoseStatus
import com.ayuvo.health.medications.model.Medication
import com.ayuvo.health.medications.model.MedicationStatus
import com.ayuvo.health.medications.model.PrnRow
import com.ayuvo.health.medications.model.TimelineItem
import com.ayuvo.health.medications.model.TodaySummary
import com.ayuvo.health.ui.components.GlassSurface
import com.ayuvo.health.ui.components.GlassTextButton
import com.ayuvo.health.ui.components.IconBubble
import com.ayuvo.health.ui.health.HealthNoticeCard
import com.ayuvo.health.ui.theme.AppColors

/** Snooze choices offered by the in-app dose sheet and the row overflow (docs/medications.md §11). */
internal val SNOOZE_OPTIONS = listOf(10, 30, 60)

/** Icon + word capsule; colour is redundant so the status never depends on it (docs §17). */
@Composable
internal fun StatusBadge(status: DoseStatus, modifier: Modifier = Modifier, late: Boolean = false) {
    val label = stringResource(status.labelRes()) + if (late) " · " + stringResource(R.string.medications_late) else ""
    BadgeCapsule(icon = status.icon(), label = label, modifier = modifier)
}

@Composable
internal fun MedicationStatusBadge(status: MedicationStatus, modifier: Modifier = Modifier) {
    BadgeCapsule(icon = status.icon(), label = stringResource(status.labelRes()), modifier = modifier)
}

@Composable
private fun BadgeCapsule(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, modifier: Modifier) {
    Row(
        modifier
            .clip(RoundedCornerShape(50))
            .background(AppColors.Calorie.copy(alpha = 0.12f))
            .padding(horizontal = 9.dp, vertical = 4.dp)
            .semantics { contentDescription = label },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Icon(icon, contentDescription = null, tint = AppColors.Calorie, modifier = Modifier.size(13.dp))
        Text(label, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = AppColors.Calorie, maxLines = 1)
    }
}

/** "Taken 2 / 4 · 1 due · 1 upcoming · 1 missed" as separate icon + text cells. */
@Composable
internal fun TodaySummaryStrip(summary: TodaySummary, modifier: Modifier = Modifier) {
    GlassSurface(modifier.fillMaxWidth().testTag("medications.summary"), cornerRadius = 20.dp, padding = 14.dp) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            SummaryCell(
                icon = DoseStatus.TAKEN.icon(),
                value = stringResource(R.string.medications_summary_taken, summary.taken, summary.total),
                label = stringResource(R.string.medications_status_taken)
            )
            if (summary.due + summary.snoozed > 0) {
                SummaryCell(icon = DoseStatus.DUE.icon(), value = (summary.due + summary.snoozed).toString(), label = stringResource(R.string.medications_status_due))
            }
            SummaryCell(icon = DoseStatus.SCHEDULED.icon(), value = summary.upcoming.toString(), label = stringResource(R.string.medications_summary_upcoming))
            SummaryCell(icon = DoseStatus.MISSED.icon(), value = summary.missed.toString(), label = stringResource(R.string.medications_status_missed))
        }
    }
}

@Composable
private fun SummaryCell(icon: androidx.compose.ui.graphics.vector.ImageVector, value: String, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.semantics { contentDescription = "$label $value" }) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Icon(icon, contentDescription = null, tint = AppColors.Calorie, modifier = Modifier.size(15.dp))
            Text(value, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        }
        Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f), maxLines = 1)
    }
}

@Composable
internal fun TimeSlotHeader(label: String, modifier: Modifier = Modifier) {
    Text(
        label,
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
        modifier = modifier.padding(start = 6.dp, top = 6.dp, bottom = 2.dp)
    )
}

/** One scheduled dose of the Today timeline (docs §8): name · strength, dose, status, Take + overflow. */
@Composable
internal fun DoseRow(
    item: TimelineItem,
    medication: Medication?,
    onTake: () -> Unit,
    onSkip: () -> Unit,
    onSnooze: (Int) -> Unit,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val name = medication?.let { MedicationFormat.nameWithStrength(it) } ?: stringResource(R.string.medications_unknown_medicine)
    val dose = doseText(item.doseQuantity, item.doseUnit)
    val time = MedicationFormat.time(context, item.scheduledAtMs)
    val resolvable = item.status == DoseStatus.SCHEDULED || item.status == DoseStatus.DUE ||
        item.status == DoseStatus.SNOOZED || item.status == DoseStatus.MISSED
    val canSnooze = item.status == DoseStatus.DUE || item.status == DoseStatus.SNOOZED
    var menuOpen by remember { mutableStateOf(false) }
    GlassSurface(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .clickable(onClick = onOpen)
            .testTag("medications.today.${item.medicationId}.${item.scheduledAtMs}"),
        cornerRadius = 20.dp,
        padding = 14.dp
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconBubble(icon = Icons.Filled.Medication, size = 36.dp, iconSize = 20.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(name, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    stringResource(R.string.medications_dose_line, dose, time),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                StatusBadge(status = item.status, late = item.isLate)
            }
            if (resolvable) {
                Spacer(Modifier.width(8.dp))
                GlassTextButton(
                    text = stringResource(if (item.status == DoseStatus.MISSED) R.string.medications_action_take_late else R.string.medications_action_take),
                    onClick = onTake,
                    modifier = Modifier.testTag("medications.action.taken")
                )
                Box {
                    IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(36.dp)) {
                        Icon(
                            Icons.Filled.MoreVert,
                            contentDescription = stringResource(R.string.cd_medication_more_actions),
                            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                        )
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.medications_action_skip)) },
                            onClick = { menuOpen = false; onSkip() },
                            modifier = Modifier.testTag("medications.action.skip")
                        )
                        if (canSnooze) {
                            SNOOZE_OPTIONS.forEach { minutes ->
                                DropdownMenuItem(
                                    text = { Text(snoozeLabel(minutes)) },
                                    onClick = { menuOpen = false; onSnooze(minutes) },
                                    modifier = Modifier.testTag("medications.action.snooze")
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun snoozeLabel(minutes: Int): String =
    if (minutes >= 60) stringResource(R.string.medications_snooze_hour) else stringResource(R.string.medications_snooze_minutes, minutes)

/** An active as-needed medicine: today's count, last dose, "Log dose" (docs §8, §11). */
@Composable
internal fun PrnRowCard(
    medication: Medication,
    row: PrnRow,
    onLogDose: () -> Unit,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier
) {
    val subtitle = buildString {
        append(stringResource(R.string.medications_prn_today_count, row.todayCount))
        row.lastTakenMs?.let { append(" · "); append(stringResource(R.string.medications_prn_last_taken, relativeAgo(it))) }
    }
    GlassSurface(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .clickable(onClick = onOpen)
            .testTag("medications.prn.${medication.id}"),
        cornerRadius = 20.dp,
        padding = 14.dp
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconBubble(icon = Icons.Filled.Medication, size = 36.dp, iconSize = 20.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(MedicationFormat.nameWithStrength(medication), fontWeight = FontWeight.SemiBold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(subtitle, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f), maxLines = 2)
            }
            Spacer(Modifier.width(8.dp))
            GlassTextButton(text = stringResource(R.string.medications_action_log_dose), onClick = onLogDose)
        }
    }
}

/** A row of the "All medicines" list: name · strength, form + dose, status badge. */
@Composable
internal fun MedicationRow(medication: Medication, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val subtitle = listOf(
        stringResource(medication.form.labelRes()),
        doseText(medication.doseQuantity, medication.doseUnit),
        if (medication.isPrn) stringResource(R.string.medications_frequency_prn) else null
    ).filterNotNull().joinToString(" · ")
    Row(
        modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .padding(horizontal = 14.dp, vertical = 12.dp)
            .testTag("medications.list.${medication.id}"),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconBubble(icon = Icons.Filled.Medication, size = 34.dp, iconSize = 19.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(MedicationFormat.nameWithStrength(medication), fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(8.dp))
        MedicationStatusBadge(medication.status)
    }
}

/** The fixed disclaimer of docs §17. */
@Composable
internal fun MedicationsDisclaimer(modifier: Modifier = Modifier) {
    Text(
        stringResource(R.string.medications_disclaimer),
        fontSize = 12.sp,
        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f),
        modifier = modifier.padding(horizontal = 6.dp, vertical = 4.dp)
    )
}

/** Back arrow + title + trailing actions, the Records detail header shape. */
@Composable
internal fun MedicationTopBar(title: String, onBack: () -> Unit, actions: @Composable RowScope.() -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cd_medication_back))
        }
        Text(
            title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        actions()
    }
}

// -- reminder prerequisites -----------------------------------------------------------------------

internal fun notificationsAllowed(context: Context): Boolean {
    val permission = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED
    return permission && NotificationManagerCompat.from(context).areNotificationsEnabled()
}

internal fun exactAlarmsAllowed(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
    val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return true
    return am.canScheduleExactAlarms()
}

internal fun openNotificationSettings(context: Context) {
    val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    if (runCatching { context.startActivity(intent) }.isFailure) {
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }
}

internal fun openExactAlarmSettings(context: Context) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
    runCatching {
        context.startActivity(
            Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}

/**
 * Banners for a missing notification permission and, on Android 12+, a denied exact-alarm
 * permission (plan §3.3). Re-checked on every resume so they disappear as soon as the user
 * comes back from system settings.
 */
@Composable
internal fun ReminderNoticeCards(visible: Boolean, modifier: Modifier = Modifier) {
    if (!visible) return
    val context = LocalContext.current
    var notificationsOk by remember { mutableStateOf(notificationsAllowed(context)) }
    var exactOk by remember { mutableStateOf(exactAlarmsAllowed(context)) }
    var requestedOnce by remember { mutableStateOf(false) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                notificationsOk = notificationsAllowed(context)
                exactOk = exactAlarmsAllowed(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { _ ->
        requestedOnce = true
        notificationsOk = notificationsAllowed(context)
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (!notificationsOk) {
            HealthNoticeCard(
                message = stringResource(R.string.medications_notice_notifications),
                actionText = stringResource(R.string.medications_notice_turn_on),
                onAction = {
                    val permissionGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED
                    if (!permissionGranted && !requestedOnce && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        openNotificationSettings(context)
                    }
                }
            )
        }
        if (notificationsOk && !exactOk) {
            HealthNoticeCard(
                message = stringResource(R.string.medications_notice_exact),
                actionText = stringResource(R.string.medications_notice_allow),
                onAction = { openExactAlarmSettings(context) }
            )
        }
    }
}

/** Sheet container colour shared with the Records sheets. */
@Composable
internal fun medicationSheetColor(): Color =
    if (MaterialTheme.colorScheme.background.luminance() < 0.5f) Color(0xF2141416) else Color(0xFFFAF3EE)

/** A small circular accent button used for "+" on the Meds home header. */
@Composable
internal fun AccentCircleIconButton(icon: androidx.compose.ui.graphics.vector.ImageVector, contentDescription: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    IconButton(
        onClick = onClick,
        modifier = modifier
            .clip(CircleShape)
            .background(AppColors.Calorie.copy(alpha = 0.12f))
            .border(0.6.dp, AppColors.Calorie.copy(alpha = 0.18f), CircleShape)
    ) {
        Icon(icon, contentDescription = contentDescription, tint = AppColors.Calorie)
    }
}

/** Maps a store action error code to the copy shown in the dose sheet (docs §11). */
@Composable
internal fun doseActionErrorText(code: String?): String? = when (code) {
    null -> null
    "already_taken" -> stringResource(R.string.medications_error_already_taken)
    "not_due_yet" -> stringResource(R.string.medications_error_not_due_yet)
    "dose_missed" -> stringResource(R.string.medications_error_dose_missed)
    "taken_at_in_future" -> stringResource(R.string.medications_error_future_time)
    "cannot_undo_missed" -> stringResource(R.string.medications_error_cannot_undo_missed)
    else -> stringResource(R.string.medications_error_generic)
}

internal fun DoseAction.isTerminalAction(): Boolean = this == DoseAction.TAKEN || this == DoseAction.SKIPPED

@Composable
internal fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, modifier = modifier.padding(start = 4.dp, top = 6.dp, bottom = 4.dp))
}
