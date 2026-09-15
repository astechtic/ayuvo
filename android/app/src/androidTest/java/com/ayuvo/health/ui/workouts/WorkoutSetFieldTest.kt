package com.ayuvo.health.ui.workouts

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.ayuvo.health.models.WorkoutSetInput
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class WorkoutSetFieldTest {
    @get:Rule val compose = createComposeRule()

    @Test fun compactRpeFieldKeepsAccessibleHelpForEveryScale() {
        val scale = mutableStateOf(com.ayuvo.health.models.WorkoutRpeScale.STRENGTH)
        compose.setContent {
            MaterialTheme {
                WorkoutSetRow(
                    index = 0,
                    set = com.ayuvo.health.models.PlannedSet(),
                    weightUnit = com.ayuvo.health.models.WorkoutWeightUnit.KG,
                    rpeScale = scale.value, onWeight = {}, onReps = {}, onRpe = {}
                )
            }
        }
        for (selected in com.ayuvo.health.models.WorkoutRpeScale.entries) {
            compose.runOnIdle { scale.value = selected }
            compose.onNodeWithText("RPE").assertIsDisplayed()
            compose.onNodeWithText("Effort (RPE)").assertDoesNotExist()
            val helpResource = when (selected) {
                com.ayuvo.health.models.WorkoutRpeScale.STRENGTH -> com.ayuvo.health.R.string.workout_rpe_help_strength
                com.ayuvo.health.models.WorkoutRpeScale.CR10 -> com.ayuvo.health.R.string.workout_rpe_help_cr10
                com.ayuvo.health.models.WorkoutRpeScale.BORG -> com.ayuvo.health.R.string.workout_rpe_help_borg
            }
            val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
            compose.onNodeWithContentDescription(context.getString(helpResource)).assertIsDisplayed()
        }
    }

    @Test fun delayedPersistencePreservesTypingSelectionAndFocusChanges() {
        val saved = mutableStateOf("")
        val updates = mutableListOf<String>()
        compose.setContent {
            MaterialTheme {
                Column {
                    WorkoutSetField(
                        value = saved.value, onValueChange = { updates.add(it) }, placeholder = "lbs",
                        keyboardType = KeyboardType.Decimal, modifier = Modifier.testTag("weight"),
                        sanitize = { text, _ -> WorkoutSetInput.weight(text) }
                    )
                    WorkoutSetField(
                        value = "", onValueChange = {}, placeholder = "Reps",
                        keyboardType = KeyboardType.Number, modifier = Modifier.testTag("reps"),
                        sanitize = { text, _ -> WorkoutSetInput.reps(text) }
                    )
                }
            }
        }
        val field = compose.onNodeWithTag("weight")
        field.performClick()
        field.performTextInput("2")
        field.performTextInput("0")
        field.assertTextEquals("20")
        compose.runOnIdle { saved.value = "2" }
        field.assertTextEquals("20")
        val reps = compose.onNodeWithTag("reps")
        reps.performClick()
        reps.performTextInput("2")
        reps.performTextInput("0")
        reps.assertTextEquals("20")
        field.assertTextEquals("20")
        compose.runOnIdle { saved.value = "20" }
        field.performClick()
        field.performTextInputSelection(TextRange(1))
        field.performTextInput("3")
        field.assertTextEquals("230")
        field.performTextInputSelection(TextRange(0, 2))
        field.performTextInput("8.")
        field.assertTextEquals("8.0")
        field.performTextClearance()
        field.performTextInput("82,5 kg")
        field.assertTextEquals("82.5")
        compose.runOnIdle { assertEquals("82.5", updates.last()) }
    }
}
