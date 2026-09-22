package com.ayuvo.health.ui.workouts

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ayuvo.health.R
import com.ayuvo.health.data.ExerciseItem
import com.ayuvo.health.data.ExerciseVisual

/** Maps the AI's clarification options to library exercises so the sheet can show their images. */
internal object ClarificationOptions {
    private fun key(text: String): String = text.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

    /** Exact name first, then the same words ignoring case and punctuation ("Dumbbell  biceps-curl"). */
    fun exerciseFor(option: String, library: List<ExerciseItem>): ExerciseItem? {
        val trimmed = option.trim()
        if (trimmed.isEmpty()) return null
        library.firstOrNull { it.name.equals(trimmed, ignoreCase = true) }?.let { return it }
        val wanted = key(trimmed)
        if (wanted.isEmpty()) return null
        return library.firstOrNull { key(it.name) == wanted }
    }

    /** Cards only when the question is about exercises; other answers ("3x10", "Barbell") stay chips. */
    fun matches(options: List<String>, library: List<ExerciseItem>): List<ExerciseItem?>? {
        val found = options.map { exerciseFor(it, library) }
        return found.takeIf { list -> list.any { it != null } }
    }
}

/**
 * The follow-up question of the text/voice workout flow: the question, the candidate exercises as a
 * horizontal row of image cards (or chips for non-exercise answers), then the free-text answer and
 * actions. Tapping a card fills the answer; Continue sends it through the unchanged [onAnswer].
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun WorkoutClarificationContent(
    description: String,
    previousAnswers: List<String>,
    question: String,
    options: List<String>,
    library: List<ExerciseItem>,
    reply: String,
    onReplyChange: (String) -> Unit,
    busy: Boolean,
    onVoiceReply: () -> Unit,
    onAnswer: (String) -> Unit
) {
    val colors = MaterialTheme.colorScheme
    val exercises = remember(options, library) { ClarificationOptions.matches(options, library) }
    Column(verticalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.testTag("workout.clarify")) {
        Text(
            stringResource(R.string.workout_clarify_title),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )
        if (description.isNotBlank()) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(colors.surfaceVariant.copy(alpha = 0.5f))
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(description, style = MaterialTheme.typography.bodyMedium, maxLines = 4, overflow = TextOverflow.Ellipsis)
                previousAnswers.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant) }
            }
        }
        Text(question, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        if (exercises != null) {
            Text(stringResource(R.string.workout_clarify_hint), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(end = 4.dp),
                modifier = Modifier.fillMaxWidth().testTag("workout.clarify.options")
            ) {
                itemsIndexed(options, key = { index, option -> "$index-$option" }) { index, option ->
                    ExerciseOptionCard(
                        name = exercises[index]?.name ?: option,
                        visual = exercises[index]?.let(ExerciseVisual::from) ?: ExerciseVisual.None,
                        selected = reply.trim().equals(option.trim(), ignoreCase = true),
                        enabled = !busy,
                        onClick = { onReplyChange(option) }
                    )
                }
            }
        } else if (options.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                options.forEach { option ->
                    FilterChip(
                        selected = reply.trim().equals(option.trim(), ignoreCase = true),
                        onClick = { onReplyChange(option) },
                        enabled = !busy,
                        label = { Text(option) }
                    )
                }
            }
        }
        OutlinedTextField(
            value = reply,
            onValueChange = { onReplyChange(it.take(500)) },
            label = { Text(stringResource(R.string.workout_clarify_answer)) },
            enabled = !busy,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth().testTag("workout.clarify.answer")
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = onVoiceReply, enabled = !busy, modifier = Modifier.weight(1f)) {
                Icon(Icons.Filled.Mic, contentDescription = null, modifier = Modifier.size(18.dp))
                Text(stringResource(R.string.workout_clarify_voice), modifier = Modifier.padding(start = 6.dp))
            }
            Button(
                onClick = { onAnswer(reply) },
                enabled = !busy && reply.isNotBlank(),
                modifier = Modifier.weight(1f).testTag("workout.clarify.continue")
            ) { Text(stringResource(R.string.action_continue)) }
        }
    }
}

@Composable
private fun ExerciseOptionCard(
    name: String,
    visual: ExerciseVisual,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(16.dp)
    Column(
        Modifier
            .width(CARD_WIDTH)
            .clip(shape)
            .border(
                BorderStroke(if (selected) 2.dp else 0.5.dp, if (selected) colors.primary else colors.outlineVariant),
                shape
            )
            .background(colors.surface)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { this.selected = selected }
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(12.dp))
        ) {
            // Missing media (custom activities) falls back to the exercise placeholder.
            AnimatedExerciseImage(visual, Modifier.fillMaxSize(), animatesFrames = false)
            if (selected) {
                Icon(
                    Icons.Filled.CheckCircle,
                    contentDescription = null,
                    tint = colors.primary,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .size(22.dp)
                        .clip(CircleShape)
                        .background(colors.surface)
                )
            }
        }
        Text(
            name,
            fontSize = 13.sp,
            lineHeight = 17.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            minLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Start,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

private val CARD_WIDTH = 136.dp
