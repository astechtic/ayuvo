package com.ayuvo.health.ui.actions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ayuvo.health.AppContainer
import com.ayuvo.health.R
import com.ayuvo.health.actions.ActionErrorCode
import com.ayuvo.health.actions.ActionException
import com.ayuvo.health.actions.ActionIntents
import com.ayuvo.health.actions.ActionKind
import com.ayuvo.health.actions.ActionResult
import com.ayuvo.health.actions.ActionResultText
import com.ayuvo.health.actions.ActionShortcutPublisher
import com.ayuvo.health.actions.ValidatedAction
import com.ayuvo.health.actions.ValidationResult
import com.ayuvo.health.ui.design.AyuvoPalette
import kotlinx.coroutines.launch

/** A parsed external action waiting for the UI (docs/actions.md). [id] consumes it exactly once. */
data class PendingAction(val parsed: ActionIntents.Parsed, val id: Long = System.nanoTime())

/** Where the host sends the user; implemented by AppNavHost with its existing helpers. */
class ActionNavigation(
    /** Opens a catalog `screen` (`metric:…`, `screen:…`, `tab:…`, `section:…`, `record:…`). */
    val openScreen: (String) -> Unit,
    /** Opens the in-app logger for a write that arrived without its value; false when there is none. */
    val openLogger: (String) -> Boolean,
    val openHealthSync: () -> Unit
)

/**
 * Runs external actions (deep links, launcher shortcuts, App Actions): validates, asks for
 * confirmation when the catalog or the entry point requires it, runs through
 * [com.ayuvo.health.actions.ActionExecutor], navigates to the result's screen and reports it in a
 * snackbar. Nothing runs before onboarding finishes ([ready]).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActionHost(
    container: AppContainer,
    pending: PendingAction?,
    ready: Boolean,
    onHandled: (Long) -> Unit,
    snackbar: SnackbarHostState,
    navigation: ActionNavigation
) {
    val context = LocalContext.current
    val resources = androidx.compose.ui.platform.LocalResources.current
    val scope = rememberCoroutineScope()
    var confirming by remember { mutableStateOf<ValidatedAction?>(null) }

    fun show(text: String) {
        scope.launch { snackbar.showSnackbar(text) }
    }

    fun execute(action: ValidatedAction) {
        scope.launch {
            try {
                val result: ActionResult = container.actions.run(action)
                if (action.spec.id == "open.coach") {
                    (action.params["prompt"] as? String)?.let { container.coachPromptRequests.value = com.ayuvo.health.actions.CoachPromptRequest(it) }
                }
                result.screen?.let(navigation.openScreen)
                ActionShortcutPublisher.report(context, action)
                if (action.spec.kind != ActionKind.OPEN && action.spec.id != "workout.start") {
                    show(ActionResultText.describe(context, action.spec, result, container.actions.prefs()))
                }
            } catch (e: ActionException) {
                if (e.code == ActionErrorCode.PERMISSION_REQUIRED) navigation.openHealthSync()
                show(ActionResultText.error(context, e))
            }
        }
    }

    suspend fun handle(request: PendingAction) {
        when (val parsed = request.parsed) {
            is ActionIntents.Parsed.BadLink -> show(resources.getString(R.string.action_error_bad_link))
            is ActionIntents.Parsed.Request -> when (val v = container.actions.validate(parsed.request)) {
                is ValidationResult.Failed -> {
                    val missing = v.code == ActionErrorCode.MISSING_PARAM || v.code == ActionErrorCode.REQUIRES_ONE_OF
                    if (!(missing && navigation.openLogger(parsed.request.id))) {
                        show(resources.getString(ActionResultText.errorRes(v.code)))
                    }
                }
                is ValidationResult.Ok ->
                    if (ActionIntents.needsConfirmation(v.action)) confirming = v.action else execute(v.action)
            }
        }
    }

    LaunchedEffect(pending?.id, ready) {
        val request = pending ?: return@LaunchedEffect
        if (!ready) return@LaunchedEffect
        onHandled(request.id)
        // Clearing the request re-keys this effect, so the work runs on the host scope instead.
        scope.launch { handle(request) }
    }

    confirming?.let { action ->
        ModalBottomSheet(
            onDismissRequest = { confirming = null },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ) {
            ConfirmActionContent(
                action = action,
                onCancel = { confirming = null },
                onConfirm = {
                    confirming = null
                    execute(action)
                }
            )
        }
    }
}

@Composable
private fun ConfirmActionContent(action: ValidatedAction, onCancel: () -> Unit, onConfirm: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
            .navigationBarsPadding()
            .padding(bottom = 16.dp)
            .testTag("action.confirm")
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Lock, contentDescription = null, tint = AyuvoPalette.Body)
            Spacer(Modifier.padding(start = 8.dp))
            Text(stringResource(R.string.action_confirm_title), style = MaterialTheme.typography.labelLarge, color = AyuvoPalette.Body)
        }
        Spacer(Modifier.height(12.dp))
        Text(action.spec.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(4.dp))
        Text(stringResource(R.string.action_confirm_body), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
        val lines = ActionResultText.paramLines(action.spec, action.params)
        if (lines.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            lines.forEach { Text(it, style = MaterialTheme.typography.bodyLarge) }
        }
        if (action.ai) {
            Spacer(Modifier.height(12.dp))
            Text(stringResource(R.string.action_confirm_ai_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
        }
        Spacer(Modifier.height(20.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f).testTag("action.confirm.cancel")) {
                Text(stringResource(R.string.action_cancel))
            }
            Button(onClick = onConfirm, modifier = Modifier.weight(1f).testTag("action.confirm.ok")) {
                Text(stringResource(R.string.action_confirm_button))
            }
        }
    }
}
