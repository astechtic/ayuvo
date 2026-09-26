package com.ayuvo.health.ui.insights

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.ayuvo.health.AppContainer
import com.ayuvo.health.insights.InsightsConfig
import com.ayuvo.health.insights.InsightsExplainer
import com.ayuvo.health.insights.InsightsSnapshot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import java.time.LocalDate

/** One "Explain with AI" request's state; keyed by kind (and day for the review). */
sealed interface ExplainUi {
    data object Idle : ExplainUi
    data object Loading : ExplainUi
    data class Done(val headline: String, val bullets: List<String>, val status: String) : ExplainUi
    data object Rejected : ExplainUi
    data object Failed : ExplainUi
}

data class InsightsUiState(
    val loaded: Boolean = false,
    val enabled: Boolean = true,
    val snapshot: InsightsSnapshot? = null,
    /** Null until checked; [InsightsExplainer.Availability.NotConfigured] shows the Settings hint. */
    val ai: InsightsExplainer.Availability? = null,
    val explanations: Map<String, ExplainUi> = emptyMap()
)

/**
 * State for every Insights screen (docs/insights.md §7): the recomputed snapshot, whether Insights
 * is on, and the on-tap AI explanations. Nothing here is stored.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class InsightsViewModel(private val container: AppContainer) : ViewModel() {
    private val _ui = MutableStateFlow(InsightsUiState())
    val ui: StateFlow<InsightsUiState> = _ui.asStateFlow()
    private val refreshTick = MutableStateFlow(0)

    val config: InsightsConfig get() = container.insightsConfig

    init {
        container.prefs.insightsEnabled
            .flatMapLatest { on ->
                if (!on) flowOf(on to null)
                else container.insightsRepository.snapshots(container.insightsTriggers() + refreshTick).map { on to it }
            }
            .onEach { (on, snap) -> _ui.value = _ui.value.copy(loaded = !on || snap != null, enabled = on, snapshot = snap) }
            .launchIn(viewModelScope)
        viewModelScope.launch {
            val availability = runCatching { container.insightsExplainer.availability() }.getOrNull()
            _ui.value = _ui.value.copy(ai = availability ?: InsightsExplainer.Availability.NotConfigured)
        }
    }

    /** Re-read on resume (the day may have changed). */
    fun refresh() {
        refreshTick.value += 1
    }

    fun explanationKey(kind: String, day: LocalDate? = null): String = if (day == null) kind else "$kind:$day"

    /** On tap only: builds the payload from the current snapshot and asks the configured text model. */
    fun explain(kind: String, day: LocalDate? = null) {
        val snap = _ui.value.snapshot ?: return
        val key = explanationKey(kind, day)
        if (_ui.value.explanations[key] == ExplainUi.Loading) return
        setExplanation(key, ExplainUi.Loading)
        viewModelScope.launch {
            val summary = snap.summaryJson(day ?: snap.today)
            val result = when (val r = container.insightsExplainer.explain(kind, summary)) {
                is InsightsExplainer.Result.Explained -> ExplainUi.Done(r.output.headline, r.output.bullets, r.statusLine)
                is InsightsExplainer.Result.Rejected -> ExplainUi.Rejected
                is InsightsExplainer.Result.Failed -> ExplainUi.Failed
                InsightsExplainer.Result.NotConfigured -> {
                    _ui.value = _ui.value.copy(ai = InsightsExplainer.Availability.NotConfigured)
                    ExplainUi.Idle
                }
            }
            setExplanation(key, result)
        }
    }

    private fun setExplanation(key: String, value: ExplainUi) {
        _ui.value = _ui.value.copy(explanations = _ui.value.explanations + (key to value))
    }

    class Factory(private val container: AppContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = InsightsViewModel(container) as T
    }
}
