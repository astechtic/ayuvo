package com.ayuvo.health.ui.settings

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ayuvo.health.AppContainer
import com.ayuvo.health.R
import com.ayuvo.health.services.googlehealth.GoogleHealthAuth
import com.ayuvo.health.services.googlehealth.GoogleHealthClientMode
import com.ayuvo.health.services.googlehealth.GoogleHealthGrant
import com.ayuvo.health.services.googlehealth.GoogleHealthSyncOutcome
import com.ayuvo.health.services.googlehealth.GoogleHealthTrigger
import com.ayuvo.health.ui.design.AyuvoPalette
import com.ayuvo.health.ui.design.GroupRow
import com.ayuvo.health.ui.design.InsetGroup
import com.ayuvo.health.ui.design.RowTrailing
import com.ayuvo.health.ui.navigation.BottomNavScrollPadding
import com.ayuvo.health.ui.settings.groups.googleHealthGroupIcon
import com.ayuvo.health.ui.settings.groups.googleHealthGroupLabel
import com.ayuvo.health.ui.theme.AppColors
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

private const val STEP_COUNT = 4

/** First-sync phase of step 4. */
private enum class FirstSync { IDLE, RUNNING, DONE, FAILED }

/**
 * Settings › Google Health setup (docs/google-health.md §5), the onboarding step pattern in four
 * steps: 1 intro and privacy, 2 data groups (+ Advanced custom Desktop client), 3 Google consent,
 * 4 Health Connect write permission and the 90-day first sync. [startStep] 2 reopens the data
 * groups (Manage data types) and 3 goes straight to consent (Reconnect).
 */
@Composable
fun GoogleHealthSetupScreen(container: AppContainer, startStep: Int, onClose: () -> Unit) {
    val coordinator = container.googleHealth
    val map = coordinator.map
    val context = LocalContext.current
    val activity = context as? Activity
    val scope = rememberCoroutineScope()
    val ui by coordinator.ui.collectAsState()

    var step by rememberSaveable { mutableIntStateOf(startStep.coerceIn(1, STEP_COUNT)) }
    var groups by rememberSaveable { mutableStateOf(map.scopeGroups.map { it.id }) }
    var custom by rememberSaveable { mutableStateOf(false) }
    var showAdvanced by rememberSaveable { mutableStateOf(false) }
    var clientId by rememberSaveable { mutableStateOf("") }
    // The secret is never saved into the instance state bundle.
    var clientSecret by remember { mutableStateOf("") }
    var consentBusy by remember { mutableStateOf(false) }
    var consentError by remember { mutableStateOf(false) }
    var connectedEmail by rememberSaveable { mutableStateOf<String?>(null) }
    var connected by rememberSaveable { mutableStateOf(false) }
    var refused by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var writeGranted by rememberSaveable { mutableStateOf(false) }
    var firstSync by rememberSaveable { mutableStateOf(FirstSync.IDLE) }

    // Manage / Reconnect start from what the account already has.
    LaunchedEffect(Unit) {
        val account = container.prefs.googleHealthAccount.first() ?: return@LaunchedEffect
        if (startStep > 1) {
            groups = account.groups.toList().ifEmpty { groups }
            custom = account.clientMode == GoogleHealthClientMode.CUSTOM
            showAdvanced = custom
            clientId = account.customClientId.orEmpty()
        }
    }
    LaunchedEffect(step) {
        if (step == 4) {
            val missing = container.health.missingPermissions(container.health.googleHealthWritePermissions)
            writeGranted = missing != null && missing.size < container.health.googleHealthWritePermissions.size
        }
    }

    suspend fun finishConsent(grant: GoogleHealthGrant) {
        val mode = if (custom) GoogleHealthClientMode.CUSTOM else GoogleHealthClientMode.BUNDLED
        val account = coordinator.completeConnection(grant, groups.toSet(), mode, clientId.takeIf { custom })
        connectedEmail = account.email
        refused = coordinator.refusedGroups(groups.toSet(), grant.grantedScopes).map { it.id }
        connected = true
        consentError = false
    }

    val consentLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        consentBusy = false
        val grant = if (result.resultCode == Activity.RESULT_OK && activity != null) {
            coordinator.auth.bundledResultFromIntent(activity, result.data)
        } else null
        if (grant == null) consentError = true else scope.launch { finishConsent(grant) }
    }

    fun startConsent() {
        val host = activity ?: run { consentError = true; return }
        consentBusy = true
        consentError = false
        val scopes = map.scopesFor(groups.toSet())
        scope.launch {
            try {
                if (custom) {
                    finishConsent(coordinator.auth.authorizeCustom(host, clientId.trim(), clientSecret.trim(), scopes))
                    consentBusy = false
                } else {
                    when (val o = coordinator.auth.authorizeBundled(host, scopes, ui.account?.email)) {
                        is GoogleHealthAuth.BundledOutcome.Granted -> {
                            finishConsent(o.grant)
                            consentBusy = false
                        }
                        is GoogleHealthAuth.BundledOutcome.Resolution ->
                            consentLauncher.launch(IntentSenderRequest.Builder(o.intentSender).build())
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                consentBusy = false
                consentError = true
            }
        }
    }

    val writeLauncher = rememberLauncherForActivityResult(container.health.permissionRequestContract()) { granted ->
        writeGranted = granted.any { it in container.health.googleHealthWritePermissions }
    }

    fun back() {
        if (step > 1 && step > minOf(startStep, 2)) step -= 1 else onClose()
    }
    BackHandler { back() }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 12.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = ::back) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.google_health_title), tint = AppColors.Calorie)
            }
            Text(stringResource(R.string.google_health_connect), fontSize = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        }
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .testTag("googleHealth.setup.step.$step"),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                stringResource(R.string.google_health_setup_step, step, STEP_COUNT),
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
            )
            when (step) {
                1 -> {
                    StepTitle(stringResource(R.string.google_health_setup_intro_title), stringResource(R.string.google_health_setup_intro_body))
                    InsetGroup {
                        row { Bullet(Icons.Filled.PhoneAndroid, stringResource(R.string.google_health_setup_privacy_local)) }
                        row { Bullet(Icons.Filled.Lock, stringResource(R.string.google_health_setup_privacy_direct)) }
                        row { Bullet(Icons.Filled.CloudOff, stringResource(R.string.google_health_setup_privacy_control)) }
                    }
                }
                2 -> {
                    StepTitle(stringResource(R.string.google_health_setup_choose_title), stringResource(R.string.google_health_setup_choose_body))
                    InsetGroup {
                        map.scopeGroups.forEach { group ->
                            row {
                                val checked = group.id in groups
                                GroupRow(
                                    title = stringResource(googleHealthGroupLabel(group.id)),
                                    icon = googleHealthGroupIcon(group.id),
                                    iconTint = SettingsTint.GoogleHealth,
                                    modifier = Modifier.testTag("googleHealth.setup.group.${group.id}"),
                                    trailing = RowTrailing.Custom {
                                        Checkbox(
                                            checked = checked,
                                            onCheckedChange = { on -> groups = if (on) groups + group.id else groups - group.id },
                                            colors = CheckboxDefaults.colors(checkedColor = AppColors.Calorie)
                                        )
                                    },
                                    onClick = { groups = if (checked) groups - group.id else groups + group.id }
                                )
                            }
                        }
                    }
                    InsetGroup(header = stringResource(R.string.google_health_setup_advanced)) {
                        row {
                            GroupRow(
                                title = stringResource(R.string.google_health_setup_custom_client),
                                modifier = Modifier.testTag("googleHealth.setup.customClient"),
                                trailing = RowTrailing.Toggle(custom, { on -> custom = on; showAdvanced = on })
                            )
                        }
                    }
                    if (showAdvanced && custom) {
                        Text(
                            stringResource(R.string.google_health_setup_custom_body),
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f)
                        )
                        OutlinedTextField(
                            value = clientId,
                            onValueChange = { clientId = it },
                            label = { Text(stringResource(R.string.google_health_setup_client_id)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().testTag("googleHealth.setup.clientId")
                        )
                        OutlinedTextField(
                            value = clientSecret,
                            onValueChange = { clientSecret = it },
                            label = { Text(stringResource(R.string.google_health_setup_client_secret)) },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            modifier = Modifier.fillMaxWidth().testTag("googleHealth.setup.clientSecret")
                        )
                        Text(
                            stringResource(R.string.google_health_setup_how_to),
                            color = AppColors.Calorie,
                            fontSize = 14.sp,
                            modifier = Modifier
                                .clickable {
                                    runCatching {
                                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(HOW_TO_URL)))
                                    }
                                }
                                .padding(vertical = 4.dp)
                        )
                    }
                }
                3 -> {
                    StepTitle(stringResource(R.string.google_health_setup_consent_title), stringResource(R.string.google_health_setup_consent_body))
                    PrimaryButton(
                        text = stringResource(R.string.google_health_setup_consent_button),
                        enabled = !consentBusy,
                        busy = consentBusy,
                        modifier = Modifier.testTag("googleHealth.setup.consent"),
                        onClick = ::startConsent
                    )
                    if (connected) {
                        StatusLine(
                            Icons.Filled.CheckCircle, AyuvoPalette.Success,
                            connectedEmail?.let { stringResource(R.string.google_health_setup_consent_done, it) }
                                ?: stringResource(R.string.google_health_setup_consent_done_no_email)
                        )
                        if (refused.isNotEmpty()) {
                            StatusLine(
                                Icons.Filled.Warning, AyuvoPalette.Warning,
                                stringResource(
                                    R.string.google_health_setup_consent_partial,
                                    refused.map { stringResource(googleHealthGroupLabel(it)) }.joinToString(", ")
                                )
                            )
                        }
                    }
                    if (consentError) {
                        StatusLine(Icons.Filled.Warning, AyuvoPalette.Destructive, stringResource(R.string.google_health_setup_consent_failed))
                    }
                }
                else -> {
                    StepTitle(stringResource(R.string.google_health_setup_write_title), stringResource(R.string.google_health_setup_write_body))
                    if (container.health.isAvailable()) {
                        if (writeGranted) {
                            StatusLine(Icons.Filled.CheckCircle, AyuvoPalette.Success, stringResource(R.string.google_health_setup_write_granted))
                        } else {
                            PrimaryButton(
                                text = stringResource(R.string.google_health_setup_write_button),
                                modifier = Modifier.testTag("googleHealth.setup.writePermission"),
                                onClick = { writeLauncher.launch(container.health.googleHealthWritePermissions) }
                            )
                        }
                    }
                    when (firstSync) {
                        FirstSync.IDLE -> PrimaryButton(
                            text = stringResource(R.string.google_health_setup_sync_button),
                            modifier = Modifier.testTag("googleHealth.setup.firstSync"),
                            onClick = {
                                firstSync = FirstSync.RUNNING
                                scope.launch {
                                    val outcome = coordinator.sync(GoogleHealthTrigger.SETUP)
                                    firstSync = if (outcome is GoogleHealthSyncOutcome.Synced) FirstSync.DONE else FirstSync.FAILED
                                }
                            }
                        )
                        FirstSync.RUNNING -> {
                            LinearProgressIndicator(
                                progress = { ui.sync.progress ?: 0f },
                                modifier = Modifier.fillMaxWidth(),
                                color = AppColors.Calorie
                            )
                            Text(
                                stringResource(R.string.google_health_setup_syncing, ui.sync.typesDone, ui.sync.typesTotal),
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f)
                            )
                        }
                        FirstSync.DONE -> StatusLine(Icons.Filled.CheckCircle, AyuvoPalette.Success, stringResource(R.string.google_health_setup_sync_done))
                        FirstSync.FAILED -> StatusLine(Icons.Filled.Warning, AyuvoPalette.Warning, stringResource(R.string.google_health_setup_sync_failed))
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
        val canContinue = when (step) {
            1 -> true
            2 -> groups.isNotEmpty() && (!custom || (clientId.isNotBlank() && clientSecret.isNotBlank()))
            3 -> connected
            else -> firstSync != FirstSync.RUNNING
        }
        Button(
            onClick = {
                when {
                    step < STEP_COUNT -> {
                        // Manage data types with an unchanged client keeps the grant; new groups need consent again.
                        if (step == 2) connected = false
                        step += 1
                    }
                    else -> onClose()
                }
            },
            enabled = canContinue,
            shape = RoundedCornerShape(28.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.onBackground,
                contentColor = MaterialTheme.colorScheme.background
            ),
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = BottomNavScrollPadding)
                .height(52.dp)
                .testTag("googleHealth.setup.continue")
        ) {
            Text(
                stringResource(if (step == STEP_COUNT) R.string.action_done else R.string.action_continue),
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

private const val HOW_TO_URL = "https://console.cloud.google.com/apis/credentials"

@Composable
private fun StepTitle(title: String, subtitle: String) {
    Column {
        Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        Text(subtitle, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f))
    }
}

@Composable
private fun Bullet(icon: ImageVector, text: String) {
    GroupRow(title = text, icon = icon, iconTint = SettingsTint.GoogleHealth, trailing = RowTrailing.None)
}

@Composable
private fun StatusLine(icon: ImageVector, tint: androidx.compose.ui.graphics.Color, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(text, fontSize = 14.sp)
    }
}

@Composable
private fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    busy: Boolean = false
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(28.dp),
        colors = ButtonDefaults.buttonColors(containerColor = AppColors.Calorie),
        modifier = modifier.fillMaxWidth().height(48.dp)
    ) {
        if (busy) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
        } else {
            Text(text, fontWeight = FontWeight.SemiBold)
        }
    }
}
