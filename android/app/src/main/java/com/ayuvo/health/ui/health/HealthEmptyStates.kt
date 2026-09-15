package com.ayuvo.health.ui.health

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ayuvo.health.R
import com.ayuvo.health.services.health.HealthAvailabilityMessageKind
import com.ayuvo.health.services.health.HealthConnectAvailability
import com.ayuvo.health.services.health.healthAvailabilityMessageKind
import com.ayuvo.health.ui.components.GlassPrimaryButton
import com.ayuvo.health.ui.components.GlassSurface
import com.ayuvo.health.ui.components.GlassTextButton
import com.ayuvo.health.ui.components.IconBubble
import com.ayuvo.health.ui.theme.AppColors

/** Not-connected hero: copy, the visible pre-checked Coach consent toggle and the Connect CTA. */
@Composable
fun HealthConnectCard(
    consentChecked: Boolean,
    onConsentChange: (Boolean) -> Unit,
    onConnect: () -> Unit,
    onManageAccess: () -> Unit
) {
    GlassSurface(modifier = Modifier.fillMaxWidth(), cornerRadius = 22.dp, padding = 18.dp) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconBubble(icon = Icons.Outlined.Favorite, size = 36.dp, iconSize = 22.dp, tint = AppColors.Calorie)
                Spacer(Modifier.width(12.dp))
                Text(stringResource(R.string.health_hub_connect_title), fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
            }
            Text(
                stringResource(R.string.health_hub_connect_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f)
            )
            Row(
                Modifier
                    .fillMaxWidth()
                    .semantics { role = Role.Switch },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.health_hub_coach_consent_title), style = MaterialTheme.typography.bodyLarge)
                    Text(
                        stringResource(R.string.health_hub_coach_consent_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
                Spacer(Modifier.width(12.dp))
                Switch(checked = consentChecked, onCheckedChange = onConsentChange)
            }
            GlassPrimaryButton(text = stringResource(R.string.health_hub_connect_action), onClick = onConnect)
            GlassTextButton(
                text = stringResource(R.string.settings_manage_health_access),
                onClick = onManageAccess,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

/** Unsupported / unavailable Health Connect copy, with the Play Store CTA when installing helps. */
@Composable
fun HealthUnavailableCard(availability: HealthConnectAvailability, onOpenPlayStore: () -> Unit) {
    val kind = healthAvailabilityMessageKind(availability)
    val message = when (kind) {
        HealthAvailabilityMessageKind.PROFILE_UNSUPPORTED -> stringResource(R.string.settings_health_profile_unsupported)
        HealthAvailabilityMessageKind.PROVIDER_UPDATE_REQUIRED -> stringResource(R.string.settings_health_unavailable)
        HealthAvailabilityMessageKind.SYSTEM_UNAVAILABLE -> stringResource(R.string.settings_health_system_unavailable)
        null -> stringResource(R.string.settings_health_denied)
    }
    GlassSurface(modifier = Modifier.fillMaxWidth(), cornerRadius = 22.dp, padding = 18.dp) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.health_hub_unavailable_title), fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
            Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f))
            if (kind == HealthAvailabilityMessageKind.PROVIDER_UPDATE_REQUIRED || kind == HealthAvailabilityMessageKind.SYSTEM_UNAVAILABLE) {
                GlassPrimaryButton(text = stringResource(R.string.health_hub_open_play_store), onClick = onOpenPlayStore)
            }
        }
    }
}

/** Granted but nothing mirrored yet. */
@Composable
fun HealthNoDataCard() {
    GlassSurface(modifier = Modifier.fillMaxWidth(), cornerRadius = 22.dp, padding = 18.dp) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.health_hub_no_data_title), fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            Text(
                stringResource(R.string.health_hub_no_data_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.66f)
            )
        }
    }
}

/** Banner with a single action (permissions reset, limited history). */
@Composable
fun HealthNoticeCard(message: String, actionText: String?, onAction: (() -> Unit)?) {
    GlassSurface(modifier = Modifier.fillMaxWidth(), cornerRadius = 18.dp, padding = 14.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                message,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
            )
            if (actionText != null && onAction != null) {
                Spacer(Modifier.width(10.dp))
                GlassTextButton(text = actionText, onClick = onAction)
            }
        }
    }
}

/** Footer copy: never claims data "never leaves the device". */
@Composable
fun HealthReadOnlyFooter() {
    Text(
        stringResource(R.string.health_hub_read_only_footer),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f),
        modifier = Modifier.padding(horizontal = 6.dp)
    )
    Spacer(Modifier.height(4.dp))
}

/** "3 min ago" style relative time from plural resources. */
@Composable
fun relativeTimeText(ms: Long?, nowMs: Long = System.currentTimeMillis()): String {
    if (ms == null) return stringResource(R.string.health_hub_subtitle_never)
    val diffMin = ((nowMs - ms) / 60_000L).toInt()
    return when {
        diffMin < 1 -> stringResource(R.string.health_relative_just_now)
        diffMin < 60 -> pluralStringResource(R.plurals.health_relative_minutes, diffMin, diffMin)
        diffMin < 60 * 24 -> pluralStringResource(R.plurals.health_relative_hours, diffMin / 60, diffMin / 60)
        else -> pluralStringResource(R.plurals.health_relative_days, diffMin / (60 * 24), diffMin / (60 * 24))
    }
}
