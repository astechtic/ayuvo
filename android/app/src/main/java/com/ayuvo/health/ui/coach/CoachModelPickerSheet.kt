package com.ayuvo.health.ui.coach

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ayuvo.health.R
import com.ayuvo.health.models.AIProvider
import com.ayuvo.health.ui.settings.AIProviderBrandIcon
import com.ayuvo.health.ui.theme.AppColors

/**
 * Coach › ⋯ › **Model** (docs/ai-models.md §8).
 *
 * Picks which saved model answers *this* conversation. The choice belongs to the chat; the pin
 * beside a row is the separate, labelled action that makes it the app default.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CoachModelPickerSheet(
    profiles: List<CoachModelChoice>,
    selectedProfileId: String?,
    selectedProvider: AIProvider?,
    onPick: (ConversationOverride) -> Unit,
    onSetDefault: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 8.dp)
            .testTag("coach.model.picker")
    ) {
        Text(
            text = stringResource(R.string.coach_model_picker_title),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(bottom = 12.dp)
        )

        ChoiceRow(
            title = stringResource(R.string.coach_model_default),
            subtitle = stringResource(R.string.coach_model_default_note),
            provider = null,
            checked = selectedProfileId == null && selectedProvider == null,
            testTag = "coach.model.default",
            onClick = { onPick(ConversationOverride()); onDismiss() }
        )

        if (profiles.isEmpty()) {
            Text(
                text = stringResource(R.string.coach_model_none),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 12.dp)
            )
        } else {
            Text(
                text = stringResource(R.string.coach_model_saved),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 16.dp, bottom = 4.dp)
            )
            profiles.forEach { choice ->
                ChoiceRow(
                    title = choice.name,
                    subtitle = if (choice.model.isEmpty()) choice.providerToken
                    else "${choice.providerToken} · ${choice.model}",
                    provider = choice.provider,
                    checked = choice.id == selectedProfileId,
                    testTag = "coach.model.${choice.id}",
                    onClick = {
                        onPick(ConversationOverride(choice.id, choice.provider))
                        onDismiss()
                    },
                    trailing = {
                        IconButton(onClick = { onSetDefault(choice.id) }) {
                            Icon(
                                Icons.Filled.PushPin,
                                contentDescription = stringResource(R.string.coach_model_set_default),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                )
            }
        }

        // A conversation the records flow pinned to a bare provider keeps showing what it actually
        // uses, even though no saved model names it.
        if (selectedProfileId == null && selectedProvider != null) {
            ChoiceRow(
                title = stringResource(selectedProvider.displayNameRes),
                subtitle = stringResource(R.string.coach_model_picker_title),
                provider = selectedProvider,
                checked = true,
                testTag = "coach.model.provider",
                onClick = {}
            )
        }
    }
    }
}

@Composable
private fun ChoiceRow(
    title: String,
    subtitle: String,
    provider: AIProvider?,
    checked: Boolean,
    testTag: String,
    onClick: () -> Unit,
    trailing: @Composable (() -> Unit)? = null,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp)
            .testTag(testTag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        provider?.let { AIProviderBrandIcon(it, Modifier.size(22.dp)) }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        trailing?.invoke()
        if (checked) {
            Icon(Icons.Filled.Check, contentDescription = null, tint = AppColors.Calorie)
        }
    }
}
