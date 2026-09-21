package com.ayuvo.health.ui.summary

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.Medication
import androidx.compose.material.icons.filled.MonitorWeight
import androidx.compose.material.icons.filled.Percent
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ayuvo.health.R
import com.ayuvo.health.ui.design.AyuvoColors
import com.ayuvo.health.ui.design.AyuvoPalette
import com.ayuvo.health.ui.design.AyuvoShapes
import com.ayuvo.health.ui.design.GroupRow
import com.ayuvo.health.ui.design.InsetGroup

/** Entries of the Summary "+" sheet, in the order of docs/ui-structure.md §8. */
enum class LogEntry(val tag: String) {
    FOOD("food"), WATER("water"), FASTING("fasting"), WEIGHT("weight"), BODY_FAT("bodyFat"),
    WORKOUT("workout"), MEDICATION("medication"), RECORD("record")
}

/** The Summary "+" log sheet; every entry hands off to the existing flow through [onEntry]. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
internal fun SummaryLogSheet(
    waterTracking: Boolean,
    /** True when the Fasting entry starts a fast right here (tracking on, none active). */
    canStartFast: Boolean,
    onEntry: (LogEntry) -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = AyuvoShapes.Sheet,
        containerColor = AyuvoColors.sheetBackground(),
        // The sheet is its own window, so it re-enables resource ids for uiautomator.
        modifier = Modifier.testTag("log.sheet").semantics { testTagsAsResourceId = true }
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .navigationBarsPadding()
                .padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            Text(
                stringResource(R.string.summary_log_title),
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 4.dp)
            )
            InsetGroup(header = stringResource(R.string.domain_nutrition)) {
                row { Entry(LogEntry.FOOD, R.string.summary_log_food, Icons.Filled.Restaurant, AyuvoPalette.Nutrition, onEntry) }
                if (waterTracking) row { Entry(LogEntry.WATER, R.string.summary_log_water, Icons.Filled.WaterDrop, AyuvoPalette.Hydration, onEntry) }
                row {
                    Entry(LogEntry.FASTING, if (canStartFast) R.string.fasting_start else R.string.summary_log_fasting_active, Icons.Filled.Timer, AyuvoPalette.Fasting, onEntry)
                }
            }
            InsetGroup(header = stringResource(R.string.domain_body)) {
                row { Entry(LogEntry.WEIGHT, R.string.summary_log_weight, Icons.Filled.MonitorWeight, AyuvoPalette.Body, onEntry) }
                row { Entry(LogEntry.BODY_FAT, R.string.summary_log_body_fat, Icons.Filled.Percent, AyuvoPalette.Body, onEntry) }
            }
            InsetGroup(header = stringResource(R.string.domain_activity)) {
                row { Entry(LogEntry.WORKOUT, R.string.summary_log_workout, Icons.Filled.FitnessCenter, AyuvoPalette.Activity, onEntry) }
            }
            InsetGroup(header = stringResource(R.string.domain_medications)) {
                row { Entry(LogEntry.MEDICATION, R.string.summary_log_medication, Icons.Filled.Medication, AyuvoPalette.Medications, onEntry) }
            }
            InsetGroup(header = stringResource(R.string.domain_records)) {
                row { Entry(LogEntry.RECORD, R.string.summary_log_record, Icons.Filled.Description, AyuvoPalette.Records, onEntry) }
            }
        }
    }
}

@Composable
private fun Entry(entry: LogEntry, title: Int, icon: androidx.compose.ui.graphics.vector.ImageVector, tint: androidx.compose.ui.graphics.Color, onEntry: (LogEntry) -> Unit) {
    GroupRow(
        title = stringResource(title),
        icon = icon,
        iconTint = tint,
        modifier = Modifier.testTag("log.entry.${entry.tag}"),
        onClick = { onEntry(entry) }
    )
}
