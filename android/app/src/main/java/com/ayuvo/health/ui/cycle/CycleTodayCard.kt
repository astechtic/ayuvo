package com.ayuvo.health.ui.cycle

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Loop
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.ayuvo.health.AppContainer
import com.ayuvo.health.R
import com.ayuvo.health.cycle.engine.CycleSnapshot
import com.ayuvo.health.ui.design.AyuvoPalette
import com.ayuvo.health.ui.summary.TodayCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** What the Summary cycle card shows; null hides the card. */
data class CycleSummary(val snapshot: CycleSnapshot, val today: LocalDate, val showFertility: Boolean)

/**
 * Loads the Summary card (docs/cycle-tracking.md §5): only when cycle tracking is shown, the database exists and setup
 * is done; never creates the database. Reloads after each cycle write and whenever [refreshKey] changes.
 */
@Composable
fun rememberCycleSummary(container: AppContainer, refreshKey: Any?): CycleSummary? {
    val enabled by container.prefs.cycleEnabled.collectAsState(initial = true)
    val fertility by container.prefs.cycleShowFertility.collectAsState(initial = true)
    val state = produceState<CycleSummary?>(null, enabled, fertility, refreshKey) {
        if (!enabled || !container.cycleDatabaseExists()) {
            value = null
            return@produceState
        }
        container.cycleRepository.revision.collect {
            value = runCatching {
                val repo = container.cycleRepository
                if (!repo.settings().setupDone) null else {
                    val today = LocalDate.now()
                    val s = repo.state(today.toString())
                    val snap = withContext(Dispatchers.Default) { container.cycleEngine.snapshot(s) }
                    if (snap.prediction.basis == "none") null else CycleSummary(snap, today, fertility)
                }
            }.getOrNull()
        }
    }
    return state.value
}

/** "Cycle day N · estimated period in X days" (or the running / expected period). */
@Composable
fun CycleTodayCard(summary: CycleSummary, onOpen: () -> Unit) {
    val p = summary.snapshot.prediction
    val day = p.currentCycleDay ?: 1
    val subtitle = when {
        p.ongoing -> stringResource(R.string.cycle_card_period_day, day)
        p.rawNextStart != null && !LocalDate.parse(p.rawNextStart).isAfter(summary.today) ->
            if (p.lateDays == 0) stringResource(R.string.cycle_status_today)
            else pluralStringResource(R.plurals.cycle_status_late, p.lateDays, p.lateDays)
        p.nextStart != null -> {
            val n = ChronoUnit.DAYS.between(summary.today, LocalDate.parse(p.nextStart)).toInt()
            pluralStringResource(R.plurals.cycle_status_next, n, n)
        }
        else -> null
    }
    TodayCard(
        icon = Icons.Filled.Loop,
        tint = AyuvoPalette.Cycle,
        title = stringResource(R.string.cycle_card_title, day),
        subtitle = subtitle,
        modifier = Modifier.testTag("summary.card.cycle"),
        onClick = onOpen
    )
}
