package com.ayuvo.health.ui.records

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ayuvo.health.R
import com.ayuvo.health.models.AIProvider
import com.ayuvo.health.records.ai.RecordsAiOptions
import com.ayuvo.health.records.model.AiModeUsed
import com.ayuvo.health.records.model.RecordsAiMode
import com.ayuvo.health.ui.components.GlassPrimaryButton
import com.ayuvo.health.ui.components.GlassSurface
import com.ayuvo.health.ui.components.GlassTextButton
import com.ayuvo.health.ui.theme.AppColors

internal val RecordsAiMode.titleRes: Int
    get() = when (this) {
        RecordsAiMode.LOCAL -> R.string.records_ai_mode_local
        RecordsAiMode.CLOUD -> R.string.records_ai_mode_cloud
        RecordsAiMode.ASK -> R.string.records_ai_mode_ask
        RecordsAiMode.OFF -> R.string.records_ai_mode_off
    }

internal val RecordsAiMode.detailRes: Int
    get() = when (this) {
        RecordsAiMode.LOCAL -> R.string.records_ai_mode_local_detail
        RecordsAiMode.CLOUD -> R.string.records_ai_mode_cloud_detail
        RecordsAiMode.ASK -> R.string.records_ai_mode_ask_detail
        RecordsAiMode.OFF -> R.string.records_ai_mode_off_detail
    }

/** Display order of the four §16 options. */
internal val RecordsAiModeOrder = listOf(RecordsAiMode.LOCAL, RecordsAiMode.CLOUD, RecordsAiMode.ASK, RecordsAiMode.OFF)


/** Detail status label (§16). */
@Composable
fun aiStatusLabel(aiModeUsed: AiModeUsed, provider: String?): String = when (aiModeUsed) {
    AiModeUsed.LOCAL -> stringResource(R.string.records_ai_status_local)
    AiModeUsed.CLOUD -> stringResource(R.string.records_ai_status_cloud, provider?.takeIf { it.isNotBlank() } ?: stringResource(R.string.records_ai_mode_cloud))
    AiModeUsed.NONE -> stringResource(R.string.records_ai_status_none)
}

/**
 * The four-option list shared by onboarding, the Records chooser card and Settings. Availability
 * notes appear under Local / Cloud when [options] says they can't run yet.
 */
@Composable
fun RecordsAiModeOptionList(
    selected: RecordsAiMode?,
    onSelect: (RecordsAiMode) -> Unit,
    options: RecordsAiOptions?,
    modifier: Modifier = Modifier,
    onSetUpLocal: (() -> Unit)? = null,
    onAddProvider: (() -> Unit)? = null
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (mode in RecordsAiModeOrder) {
            val isSelected = mode == selected
            val shape = RoundedCornerShape(14.dp)
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(shape)
                    .border(1.dp, if (isSelected) AppColors.Calorie.copy(alpha = 0.7f) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f), shape)
                    .clickable { onSelect(mode) }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    if (isSelected) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked,
                    contentDescription = null,
                    tint = if (isSelected) AppColors.Calorie else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                    modifier = Modifier.size(22.dp)
                )
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(mode.titleRes), fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                    Text(
                        stringResource(mode.detailRes),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                    if (options != null && isSelected) {
                        AvailabilityNote(mode, options, onSetUpLocal, onAddProvider)
                    }
                }
            }
        }
    }
}

@Composable
private fun AvailabilityNote(
    mode: RecordsAiMode,
    options: RecordsAiOptions,
    onSetUpLocal: (() -> Unit)?,
    onAddProvider: (() -> Unit)?
) {
    val warn = Color(0xFFFF9F0A)
    when {
        mode == RecordsAiMode.LOCAL && !options.localAvailable -> {
            Text(stringResource(R.string.records_ai_local_unavailable), fontSize = 12.sp, color = warn, modifier = Modifier.padding(top = 4.dp))
            onSetUpLocal?.let { GlassTextButton(text = stringResource(R.string.records_ai_set_up_local), onClick = it, modifier = Modifier.padding(top = 6.dp)) }
        }
        (mode == RecordsAiMode.CLOUD || mode == RecordsAiMode.ASK) && !options.cloudConfigured && !(mode == RecordsAiMode.ASK && options.localAvailable) -> {
            Text(stringResource(R.string.records_ai_no_cloud_provider), fontSize = 12.sp, color = warn, modifier = Modifier.padding(top = 4.dp))
            onAddProvider?.let { GlassTextButton(text = stringResource(R.string.records_ai_add_provider), onClick = it, modifier = Modifier.padding(top = 6.dp)) }
        }
        mode == RecordsAiMode.CLOUD && options.cloudProvider != null -> Text(
            stringResource(R.string.records_ai_cloud_provider_note, stringResource(options.cloudProvider.displayNameRes)),
            fontSize = 12.sp,
            color = AppColors.Calorie,
            modifier = Modifier.padding(top = 4.dp)
        )
    }
}

/** One-time card at the top of the Records tab while `healthRecordsAiMode` is unset (§16). */
@Composable
fun RecordsAiChooserCard(
    onChoose: (RecordsAiMode) -> Unit,
    options: RecordsAiOptions,
    modifier: Modifier = Modifier
) {
    GlassSurface(modifier = modifier.fillMaxWidth(), cornerRadius = 20.dp, padding = 14.dp) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.AutoAwesome, contentDescription = null, tint = AppColors.Calorie, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.records_ai_chooser_title), fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
            }
            Text(
                stringResource(R.string.records_ai_question),
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
            )
            RecordsAiModeOptionList(selected = null, onSelect = onChoose, options = options)
        }
    }
}

/**
 * Ask-mode banner (detail and Needs Review): "Use AI to find more details?" with the choices that can
 * actually run, plus "Apply to all waiting records" when more than one record waits.
 */
@Composable
fun AiConsentBanner(
    options: RecordsAiOptions,
    waitingCount: Int,
    onLocal: () -> Unit,
    onCloud: () -> Unit,
    onNotNow: () -> Unit,
    onApplyAll: ((String) -> Unit)?,
    modifier: Modifier = Modifier
) {
    GlassSurface(modifier = modifier.fillMaxWidth(), cornerRadius = 20.dp, padding = 14.dp) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.AutoAwesome, contentDescription = null, tint = AppColors.Calorie, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.records_ai_consent_title), fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
            }
            Text(
                stringResource(if (options.localAvailable || options.cloudConfigured) R.string.records_ai_consent_body else R.string.records_ai_consent_none_available),
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
            )
            if (options.localAvailable) {
                GlassPrimaryButton(text = stringResource(R.string.records_ai_consent_local), onClick = onLocal, height = 44.dp)
            }
            options.cloudProvider?.let { provider ->
                val label = stringResource(R.string.records_ai_consent_cloud, stringResource(provider.displayNameRes))
                if (options.localAvailable) {
                    GlassTextButton(text = label, onClick = onCloud, modifier = Modifier.fillMaxWidth())
                } else {
                    GlassPrimaryButton(text = label, onClick = onCloud, height = 44.dp)
                }
            }
            GlassTextButton(
                text = stringResource(R.string.records_ai_consent_not_now),
                onClick = onNotNow,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                modifier = Modifier.fillMaxWidth()
            )
            if (onApplyAll != null && waitingCount > 1 && (options.localAvailable || options.cloudConfigured)) {
                Text(
                    stringResource(R.string.records_ai_consent_apply_all),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (options.localAvailable) {
                        GlassTextButton(
                            text = stringResource(R.string.records_ai_consent_local),
                            onClick = { onApplyAll("local") },
                            modifier = Modifier.weight(1f)
                        )
                    }
                    options.cloudProvider?.let { provider ->
                        GlassTextButton(
                            text = stringResource(R.string.records_ai_consent_cloud, stringResource(provider.displayNameRes)),
                            onClick = { onApplyAll("cloud") },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }
    }
}
