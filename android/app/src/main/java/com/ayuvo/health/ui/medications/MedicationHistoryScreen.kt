package com.ayuvo.health.ui.medications

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ayuvo.health.AppContainer
import com.ayuvo.health.R
import com.ayuvo.health.medications.logic.MedicationLocalTime
import com.ayuvo.health.ui.components.GlassSurface
import com.ayuvo.health.ui.navigation.BottomNavScrollPadding
import java.time.ZoneId

/** Dose history (docs/medications.md §9): keyset pages grouped by local day. */
@Composable
fun MedicationHistoryScreen(
    container: AppContainer,
    medicationId: String?,
    onBack: () -> Unit
) {
    val vm: MedicationHistoryViewModel = viewModel(key = "med-history-${medicationId ?: "all"}", factory = MedicationHistoryViewModel.Factory(container, medicationId))
    val ui by vm.ui.collectAsState()
    val context = LocalContext.current
    val zone = ZoneId.systemDefault().id
    val title = ui.medication?.name ?: stringResource(R.string.medications_history_title)
    Column(Modifier.fillMaxSize()) {
        MedicationTopBar(title = title, onBack = onBack)
        if (ui.loading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@Column
        }
        val groups = ui.logs.groupBy { MedicationLocalTime.localDateOf(it.takenAtMs ?: it.scheduledAtMs, zone) }
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, top = 4.dp, end = 16.dp, bottom = BottomNavScrollPadding),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (ui.logs.isEmpty()) {
                item(key = "empty") {
                    Text(
                        stringResource(R.string.medications_history_empty),
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                        modifier = Modifier.padding(8.dp)
                    )
                }
            }
            groups.forEach { (day, logs) ->
                item(key = "day-$day") { TimeSlotHeader(MedicationFormat.dayHeader(context, day)) }
                item(key = "card-$day") {
                    GlassSurface(Modifier.fillMaxWidth(), cornerRadius = 20.dp, padding = 0.dp) {
                        Column {
                            logs.forEachIndexed { index, log ->
                                if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                                val medication = ui.medications[log.medicationId]
                                Row(
                                    Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp).testTag("medications.history.${log.id}"),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        MedicationFormat.time(context, log.takenAtMs ?: log.scheduledAtMs),
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        modifier = Modifier.width(72.dp)
                                    )
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            medication?.let { MedicationFormat.nameWithStrength(it) } ?: stringResource(R.string.medications_unknown_medicine),
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.Medium,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        val detail = buildString {
                                            append(doseText(log.doseQuantity, log.doseUnit))
                                            if (log.isPrn) append(" · ").append(stringResource(R.string.medications_frequency_prn))
                                            log.note?.takeIf { it.isNotBlank() }?.let { append(" · ").append(it) }
                                        }
                                        Text(detail, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    }
                                    Spacer(Modifier.width(8.dp))
                                    StatusBadge(status = log.status, late = log.status.raw == "taken" && log.takenAtMs != null && log.scheduleId != null && log.takenAtMs > log.scheduledAtMs + 7_200_000L)
                                }
                            }
                        }
                    }
                }
            }
            if (ui.canLoadMore) {
                item(key = "more") {
                    LaunchedEffect(ui.logs.size) { vm.loadMore() }
                    Box(Modifier.fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(modifier = Modifier.width(24.dp)) }
                }
            }
            item(key = "disclaimer") { MedicationsDisclaimer() }
        }
    }
}
