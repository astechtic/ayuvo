package com.ayuvo.health.ui.settings.groups

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ayuvo.health.AppContainer
import com.ayuvo.health.R
import com.ayuvo.health.ui.design.AyuvoColors
import com.ayuvo.health.ui.design.GroupRow
import com.ayuvo.health.ui.design.InsetGroup
import com.ayuvo.health.ui.design.RowTrailing
import com.ayuvo.health.ui.settings.SettingsPage
import com.ayuvo.health.ui.theme.AppColors
import kotlinx.coroutines.launch

/**
 * Settings › Health Records › AI processing (docs/health-records.md §16): the four modes with
 * availability notes; on-device setup and provider keys live in the AI Providers page.
 */
@Composable
internal fun HealthRecordsAiSection(container: AppContainer, onOpenAiProviders: () -> Unit) {
    val scope = rememberCoroutineScope()
    val rawMode by container.prefs.healthRecordsAiMode.collectAsState(initial = null)
    val localStates by container.localModels.states.collectAsState()
    val resolver = container.recordsAiResolver
    var options by remember { mutableStateOf<com.ayuvo.health.records.ai.RecordsAiOptions?>(null) }
    LaunchedEffect(localStates, rawMode) { options = runCatching { resolver.options() }.getOrNull() }
    InsetGroup(header = stringResource(R.string.records_ai_settings_title)) {
        row {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    stringResource(R.string.records_ai_question),
                    style = MaterialTheme.typography.bodyMedium,
                    color = AyuvoColors.secondaryLabel()
                )
                com.ayuvo.health.ui.records.RecordsAiModeOptionList(
                    selected = com.ayuvo.health.records.model.RecordsAiMode.fromRaw(rawMode),
                    onSelect = { mode -> scope.launch { container.prefs.setHealthRecordsAiMode(mode.raw) } },
                    options = options,
                    onSetUpLocal = onOpenAiProviders,
                    onAddProvider = onOpenAiProviders
                )
            }
        }
    }
}

/**
 * Settings › Health Records › Coach (docs/health-records.md §26): "Let Coach use my health records".
 * Turning it on shows the consent sheet (an affirmative act stores the consent time); off removes the
 * records tools immediately.
 */
@Composable
internal fun HealthRecordsCoachSection(container: AppContainer) {
    val scope = rememberCoroutineScope()
    val enabled by container.prefs.healthRecordsCoachAccessEnabled.collectAsState(initial = false)
    var consentProvider by remember { mutableStateOf<String?>(null) }
    var showConsent by remember { mutableStateOf(false) }
    InsetGroup(header = stringResource(R.string.records_coach_settings_title)) {
        row {
            GroupRow(
                title = stringResource(R.string.records_coach_settings_toggle),
                subtitle = stringResource(R.string.records_coach_settings_subtitle),
                icon = Icons.Filled.SmartToy,
                iconTint = SettingsPage.HEALTH_RECORDS.tint,
                modifier = Modifier.settingsRow("recordsCoach"),
                trailing = RowTrailing.Toggle(enabled, { on ->
                    if (on) {
                        scope.launch {
                            val provider = container.chatService.coachProvider(hasImage = false)
                            consentProvider = if (provider == com.ayuvo.health.models.AIProvider.LOCAL_GEMMA) null else container.appContext.getString(provider.displayNameRes)
                            showConsent = true
                        }
                    } else {
                        scope.launch { container.prefs.setHealthRecordsCoachAccess(false) }
                    }
                })
            )
        }
    }
    if (showConsent) {
        com.ayuvo.health.ui.records.CoachRecordsConsentSheet(
            providerName = consentProvider,
            onAllow = {
                showConsent = false
                scope.launch { container.prefs.setHealthRecordsCoachAccess(true, java.time.Instant.now().toString()) }
            },
            onNotNow = { showConsent = false }
        )
    }
}

/** Settings › Health Records: the Phase 1 "How Ayuvo handles your health records" explainer. */
@Composable
internal fun HealthRecordsPrivacySection() {
    InsetGroup(header = stringResource(R.string.records_privacy_title)) {
        row {
            Column(
                Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                listOf(
                    R.string.records_privacy_point_device,
                    R.string.records_privacy_point_backup,
                    R.string.records_privacy_point_ai,
                    R.string.records_privacy_point_sharing,
                    R.string.records_privacy_point_delete
                ).forEach { res ->
                    Row(verticalAlignment = Alignment.Top) {
                        Text("•", color = AppColors.Calorie, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.width(10.dp))
                        Text(
                            stringResource(res),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.82f)
                        )
                    }
                }
            }
        }
    }
}
