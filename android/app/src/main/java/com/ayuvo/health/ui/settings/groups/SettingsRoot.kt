package com.ayuvo.health.ui.settings.groups

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.IconButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ayuvo.health.R
import com.ayuvo.health.models.UserProfile
import com.ayuvo.health.ui.design.AyuvoColors
import com.ayuvo.health.ui.design.AyuvoPalette
import com.ayuvo.health.ui.design.AyuvoPrivacyOpenSourceCard
import com.ayuvo.health.ui.design.AyuvoShapes
import com.ayuvo.health.ui.design.GroupRow
import com.ayuvo.health.ui.design.InsetGroup
import com.ayuvo.health.ui.design.RowTrailing
import com.ayuvo.health.ui.settings.SettingsGroup
import com.ayuvo.health.ui.settings.SettingsPage
import com.ayuvo.health.ui.settings.SettingsPageContext
import com.ayuvo.health.ui.settings.feetInchesLabel
import java.util.Locale

/** Settings tab root (plan §6): profile header, then one inset group per [SettingsGroup]. */
@Composable
internal fun SettingsRoot(ctx: SettingsPageContext) {
    SettingsProfileHeader(ctx.ui.profile, ctx.ui.heightMetric, ctx.ui.weightMetric) {
        ctx.actions.openPage(SettingsPage.PERSONAL_INFO)
    }
    AyuvoPrivacyOpenSourceCard(modifier = Modifier.testTag("settings.privacyOpenSource"))
    SettingsGroup.entries.forEach { group ->
        InsetGroup(
            modifier = Modifier.testTag("settings.group.${group.tag}"),
            header = group.titleRes?.let { stringResource(it) }
        ) {
            group.pages.forEach { page ->
                row {
                    GroupRow(
                        title = stringResource(page.titleRes),
                        icon = page.icon,
                        iconTint = page.tint,
                        destructive = page.destructive,
                        modifier = Modifier.testTag(page.testTag),
                        trailing = if (page == SettingsPage.APP_UPDATES && ctx.updateAvailable) {
                            RowTrailing.Custom { UpdateBadge() }
                        } else {
                            RowTrailing.Chevron
                        },
                        onClick = { ctx.actions.openPage(page) }
                    )
                }
            }
        }
    }
}

@Composable
private fun UpdateBadge() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            stringResource(R.string.settings_update_badge),
            modifier = Modifier
                .clip(AyuvoShapes.Capsule)
                .background(AyuvoPalette.Destructive)
                .padding(horizontal = 8.dp, vertical = 2.dp),
            color = Color.White,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold
        )
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = AyuvoColors.tertiaryLabel(),
            modifier = Modifier.size(22.dp)
        )
    }
}

/** Initials badge, name and "Age · height · weight" in the user's units; opens Personal Info. */
@Composable
internal fun SettingsProfileHeader(
    profile: UserProfile?,
    heightMetric: Boolean,
    weightMetric: Boolean,
    onClick: () -> Unit
) {
    val named = profile?.takeIf { !it.name.isNullOrBlank() }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(AyuvoShapes.Card)
            .background(MaterialTheme.colorScheme.surface)
            .clickable(role = Role.Button, onClick = onClick)
            .testTag("settings.profile")
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(56.dp)
                .clip(CircleShape)
                .background(AyuvoPalette.Body),
            contentAlignment = Alignment.Center
        ) {
            if (named != null) {
                Text(named.initials, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
            } else {
                Icon(Icons.Filled.Person, contentDescription = null, tint = Color.White, modifier = Modifier.size(30.dp))
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                named?.displayName ?: stringResource(R.string.settings_profile_your_profile),
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (profile != null) {
                val height = if (heightMetric) "${profile.heightCm.toInt()} cm" else feetInchesLabel(profile.heightCm.toInt())
                val weight = if (weightMetric) String.format(Locale.US, "%.1f kg", profile.weightKg)
                else String.format(Locale.US, "%.1f lbs", profile.weightKg * 2.20462)
                Text(
                    stringResource(R.string.settings_profile_summary, profile.age, height, weight),
                    fontSize = 14.sp,
                    color = AyuvoColors.secondaryLabel()
                )
            }
        }
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = AyuvoColors.tertiaryLabel(),
            modifier = Modifier.size(22.dp)
        )
    }
}

/** Renders the body of [page] (the host supplies the top bar and scrolling column). */
@Composable
internal fun SettingsPageContent(ctx: SettingsPageContext, page: SettingsPage) {
    when (page) {
        SettingsPage.PERSONAL_INFO -> PersonalInfoPage(ctx)
        SettingsPage.GOALS_TARGETS -> GoalsTargetsPage(ctx)
        SettingsPage.UNITS -> UnitsPage(ctx)
        SettingsPage.NUTRITION -> NutritionTrackingPage(ctx)
        SettingsPage.HYDRATION -> HydrationPage(ctx)
        SettingsPage.FASTING -> FastingTrackingPage(ctx)
        SettingsPage.ACTIVITY -> ActivityPage(ctx)
        SettingsPage.MEDICATIONS -> MedicationsSettingsPage(ctx)
        SettingsPage.NOTIFICATIONS -> NotificationsPage(ctx)
        SettingsPage.HEALTH_SYNC -> HealthSyncPage(ctx)
        SettingsPage.HEALTH_RECORDS -> HealthRecordsPage(ctx)
        SettingsPage.BACKUP_EXPORT -> BackupExportPage(ctx)
        SettingsPage.DELETE_DATA -> DeleteDataPage(ctx)
        SettingsPage.AI_PROVIDERS -> AiProvidersPage(ctx)
        SettingsPage.SPEECH_TO_TEXT -> SpeechToTextPage(ctx)
        SettingsPage.CUSTOM_INSTRUCTIONS -> CustomInstructionsPage(ctx)
        SettingsPage.APPEARANCE -> AppearancePage(ctx)
        SettingsPage.APP_UPDATES, SettingsPage.HELP_SUPPORT, SettingsPage.LEGAL -> AboutPage(ctx, page)
    }
}

/** Small info "i" button used as a row trailing control (Adaptive Goals, Energy Burn, Default to Grams). */
@Composable
internal fun InfoTrailing(contentDescription: String, onInfo: () -> Unit) {
    IconButton(onClick = onInfo, modifier = Modifier.size(36.dp)) {
        Icon(
            Icons.Outlined.Info,
            contentDescription = contentDescription,
            tint = AyuvoColors.secondaryLabel(),
            modifier = Modifier.size(20.dp)
        )
    }
}

/** The Apple-green switch [GroupRow] uses, for rows whose trailing slot holds more than a switch. */
@Composable
internal fun SettingsSwitch(checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Switch(
        checked = checked,
        onCheckedChange = onChange,
        enabled = enabled,
        colors = SwitchDefaults.colors(
            checkedTrackColor = AyuvoPalette.Success,
            checkedThumbColor = Color.White,
            checkedBorderColor = Color.Transparent,
            uncheckedThumbColor = Color.White,
            uncheckedTrackColor = AyuvoColors.fill(),
            uncheckedBorderColor = Color.Transparent
        )
    )
}
