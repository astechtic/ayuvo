package com.ayuvo.health.ui.medications

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import com.ayuvo.health.R
import com.ayuvo.health.medications.model.DoseStatus
import com.ayuvo.health.medications.model.DoseUnit
import com.ayuvo.health.medications.model.Medication
import com.ayuvo.health.medications.model.MedicationForm
import com.ayuvo.health.medications.model.TimelineItem
import com.ayuvo.health.medications.model.TimelineKind
import com.ayuvo.health.medications.model.TodaySummary
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** Records which dose-row callback fired; a plain class so the test has no captured mutable locals. */
class DoseRowCalls {
    var taken = 0
    var skipped = 0
    var snoozedMinutes = -1

    fun onTake() { taken++ }
    fun onSkip() { skipped++ }
    fun onSnooze(minutes: Int) { snoozedMinutes = minutes }
}

/** A dose row under test, wrapped in the app theme. */
@Composable
fun TestDoseRow(item: TimelineItem, medication: Medication, calls: DoseRowCalls) {
    MaterialTheme {
        DoseRow(
            item = item,
            medication = medication,
            onTake = calls::onTake,
            onSkip = calls::onSkip,
            onSnooze = calls::onSnooze,
            onOpen = {}
        )
    }
}

@Composable
fun TestSummaryStrip(summary: TodaySummary) {
    MaterialTheme { TodaySummaryStrip(summary) }
}

/** The Today timeline row: Take / Skip / Snooze callbacks fire and the status is shown as text. */
class MedicationsTodayActionsTest {
    @get:Rule val compose = createComposeRule()

    @Test fun dueRowFiresTakeSkipAndSnooze() {
        val calls = DoseRowCalls()
        val item = timelineItem(DoseStatus.DUE)
        compose.setContent { TestDoseRow(item = item, medication = METFORMIN, calls = calls) }
        compose.onNodeWithText("Metformin 500 mg").assertIsDisplayed()
        compose.onNodeWithContentDescription(string(R.string.medications_status_due)).assertIsDisplayed()
        compose.onNodeWithTag("medications.action.taken").performClick()
        assertEquals(1, calls.taken)
        compose.onNodeWithContentDescription(string(R.string.cd_medication_more_actions)).performClick()
        compose.onNodeWithTag("medications.action.skip").performClick()
        assertEquals(1, calls.skipped)
        compose.onNodeWithContentDescription(string(R.string.cd_medication_more_actions)).performClick()
        compose.onAllNodesWithTag("medications.action.snooze")[0].performClick()
        assertEquals(10, calls.snoozedMinutes)
    }

    @Test fun takenRowShowsStatusWithoutActions() {
        val calls = DoseRowCalls()
        val item = timelineItem(DoseStatus.TAKEN)
        compose.setContent { TestDoseRow(item = item, medication = METFORMIN, calls = calls) }
        compose.onNodeWithContentDescription(string(R.string.medications_status_taken)).assertIsDisplayed()
        compose.onAllNodesWithTag("medications.action.taken").assertCountEquals(0)
    }

    @Test fun summaryStripShowsTakenOfTotal() {
        val summary = TodaySummary(total = 4, taken = 2, upcoming = 1, due = 0, snoozed = 0, missed = 1)
        compose.setContent { TestSummaryStrip(summary = summary) }
        compose.onNodeWithText("2 / 4").assertIsDisplayed()
        compose.onNodeWithTag("medications.summary").assertIsDisplayed()
    }

    private fun string(id: Int): String = InstrumentationRegistry.getInstrumentation().targetContext.getString(id)

    private fun timelineItem(status: DoseStatus): TimelineItem = TimelineItem(
        medicationId = "med-1",
        scheduleId = "sch-1",
        scheduledAtMs = 1_789_000_000_000L,
        status = status,
        isLate = false,
        logId = null,
        snoozedUntilMs = null,
        doseQuantity = 1.0,
        doseUnit = DoseUnit.TABLET,
        kind = TimelineKind.SCHEDULED
    )

    companion object {
        val METFORMIN = Medication(
            id = "med-1",
            name = "Metformin",
            strength = "500 mg",
            form = MedicationForm.TABLET,
            doseQuantity = 1.0,
            doseUnit = DoseUnit.TABLET,
            startDate = "2026-09-16",
            createdMs = 1,
            updatedMs = 1
        )
    }
}
