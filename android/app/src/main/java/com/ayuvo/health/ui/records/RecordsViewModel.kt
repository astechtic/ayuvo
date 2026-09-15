package com.ayuvo.health.ui.records

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.ayuvo.health.AppContainer
import com.ayuvo.health.records.data.RecordsStore
import com.ayuvo.health.records.ingest.ImportItem
import com.ayuvo.health.records.ingest.ImportSpec
import com.ayuvo.health.records.model.HealthRecord
import com.ayuvo.health.records.model.RecordCursor
import com.ayuvo.health.records.model.RecordFilter
import com.ayuvo.health.records.model.RecordPatch
import com.ayuvo.health.records.model.RecordQuery
import com.ayuvo.health.records.ai.RecordsAiOptions
import com.ayuvo.health.records.data.HighlightWithRecord
import com.ayuvo.health.records.data.ProcessingSummary
import com.ayuvo.health.records.data.RecordSearchHit
import com.ayuvo.health.records.model.RecordAdvancedFilters
import com.ayuvo.health.records.model.RecordPageResult
import com.ayuvo.health.records.model.RecordTag
import com.ayuvo.health.records.model.RecordsAiMode
import com.ayuvo.health.records.processing.PipelineProgress
import com.ayuvo.health.records.processing.RecordRules
import com.ayuvo.health.records.search.ParsedChip
import com.ayuvo.health.records.search.RecordQueryParser
import kotlinx.coroutines.FlowPreview
import java.time.LocalDate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class RecordsViewMode(val raw: String) {
    TIMELINE("timeline"), LIST("list"), GRID("grid");

    companion object {
        fun fromRaw(raw: String?): RecordsViewMode = entries.firstOrNull { it.raw == raw } ?: TIMELINE
    }
}

data class RecordsUiState(
    val loading: Boolean = true,
    val items: List<HealthRecord> = emptyList(),
    val recent: List<HealthRecord> = emptyList(),
    val totalCount: Long = 0,
    val canLoadMore: Boolean = false,
    val filter: RecordFilter = RecordFilter.ALL,
    val search: String = "",
    val viewMode: RecordsViewMode = RecordsViewMode.TIMELINE,
    val selection: Set<String> = emptySet(),
    val loadFailed: Boolean = false,
    // Phase 2
    val advanced: RecordAdvancedFilters = RecordAdvancedFilters(),
    /** Parsed query chips of [search] (§17), removable. */
    val chips: List<ParsedChip> = emptyList(),
    /** Ranked FTS hits while a search has free-text terms; null when browsing. */
    val searchHits: List<RecordSearchHit>? = null,
    val processing: ProcessingSummary = ProcessingSummary(0, 0),
    val progress: PipelineProgress? = null,
    val needsReview: List<HealthRecord> = emptyList(),
    val highlights: List<HighlightWithRecord> = emptyList(),
    /** `healthRecordsAiMode`; null = not chosen yet (one-time chooser card). */
    val aiMode: RecordsAiMode? = null,
    val aiModeLoaded: Boolean = false,
    val aiOptions: RecordsAiOptions? = null,
    /** "Search smarter with AI" row: shown when the mode allows it and the query looks natural-language. */
    val canSearchWithAi: Boolean = false,
    val aiSearchRunning: Boolean = false,
    val allTags: List<RecordTag> = emptyList()
) {
    val isBrowsingAll: Boolean get() = filter == RecordFilter.ALL && search.isBlank() && advanced.isEmpty
    val selecting: Boolean get() = selection.isNotEmpty()
}

/**
 * Records home: keyset-paged list over [RecordsStore] that re-queries on every store revision,
 * filter chip change or (debounced) search edit. The loaded window is kept on refresh so rows
 * don't jump back to page one while the user scrolls.
 */
class RecordsViewModel(private val container: AppContainer) : ViewModel() {
    private val store: RecordsStore get() = container.recordsStore

    private val _ui = MutableStateFlow(RecordsUiState())
    val ui: StateFlow<RecordsUiState> = _ui.asStateFlow()

    val importing: StateFlow<Int> = container.recordsImports.inFlight
    val notice = container.recordsImports.notice

    private val query = MutableStateFlow(RecordQuery())
    private val today: LocalDate get() = LocalDate.now()
    private val pageLock = Mutex()
    private var cursor: RecordCursor? = null
    private var loadedQuery: RecordQuery? = null

    init {
        viewModelScope.launch {
            container.prefs.healthRecordsViewMode.collect { raw ->
                _ui.update { it.copy(viewMode = RecordsViewMode.fromRaw(raw)) }
            }
        }
        viewModelScope.launch {
            @OptIn(FlowPreview::class)
            val debounced = query.debounce { if (it.search.isBlank()) 0L else SEARCH_DEBOUNCE_MS }
            combine(store.revision, debounced.distinctUntilChanged()) { _, q -> q }
                .collectLatest { q -> reload(q) }
        }
        viewModelScope.launch {
            container.prefs.healthRecordsAiMode.collect { raw ->
                _ui.update { it.copy(aiMode = RecordsAiMode.fromRaw(raw), aiModeLoaded = true) }
                refreshAiOptions()
            }
        }
        viewModelScope.launch {
            container.recordsPipeline.progress.collect { p -> _ui.update { it.copy(progress = p) } }
        }
    }

    private fun refreshAiOptions() {
        viewModelScope.launch {
            runCatching { container.recordsAiResolver.options() }.onSuccess { o -> _ui.update { it.copy(aiOptions = o) } }
        }
    }

    /** Filter chip + Filters sheet + the parsed structure of the search text (§17). */
    private fun effective(q: RecordQuery): Pair<RecordQuery, List<ParsedChip>> {
        if (q.search.isBlank()) return q.copy(terms = null) to emptyList()
        val parsed = RecordQueryParser.parse(q.search, today, RecordRules.deviceDateOrder())
        return q.copy(advanced = q.advanced.and(parsed.toFilters()), terms = parsed.terms) to parsed.chips
    }

    private suspend fun reload(q: RecordQuery) {
        pageLock.withLock {
            val keepWindow = loadedQuery == q
            val limit = if (keepWindow) maxOf(RecordsStore.PAGE_SIZE, _ui.value.items.size) else RecordsStore.PAGE_SIZE
            runCatching {
                val (effectiveQuery, chips) = effective(q)
                val browsing = q.filter == RecordFilter.ALL && q.search.isBlank() && q.advanced.isEmpty
                val hits = if (effectiveQuery.terms?.isNotEmpty() == true) store.search(effectiveQuery, today = today) else null
                val page = if (hits == null) store.page(effectiveQuery, after = null, limit = limit) else RecordPageResult(hits.map { it.record }, null)
                val recent = if (browsing) store.recent(RECENT_COUNT) else emptyList()
                val total = store.count()
                val summary = store.processingSummary()
                val review = if (browsing) store.needsReview(SECTION_COUNT) else emptyList()
                val highlights = if (browsing) store.importantHighlights(SECTION_COUNT) else emptyList()
                val tags = store.allTags()
                Loaded(page, recent, total, hits, chips, summary, review, highlights, tags, effectiveQuery.terms.orEmpty())
            }.onSuccess { loaded ->
                val page = loaded.page
                cursor = page.next
                loadedQuery = q
                val ids = page.items.mapTo(HashSet()) { it.id }
                val mode = _ui.value.aiMode
                _ui.update {
                    it.copy(
                        loading = false,
                        items = page.items,
                        recent = loaded.recent,
                        totalCount = loaded.total,
                        canLoadMore = page.next != null,
                        selection = it.selection.filterTo(HashSet()) { id -> id in ids },
                        loadFailed = false,
                        searchHits = loaded.hits,
                        chips = loaded.chips,
                        processing = loaded.summary,
                        needsReview = loaded.review,
                        highlights = loaded.highlights,
                        allTags = loaded.tags,
                        // §17: ≥ 2 unrecognised terms and zero FTS hits, and the mode allows AI.
                        canSearchWithAi = loaded.hits?.isEmpty() == true && loaded.terms.size >= MIN_AI_SEARCH_TERMS &&
                            mode != null && mode != RecordsAiMode.OFF
                    )
                }
            }.onFailure {
                _ui.update { it.copy(loading = false, loadFailed = true) }
            }
        }
    }

    private data class Loaded(
        val page: RecordPageResult,
        val recent: List<HealthRecord>,
        val total: Long,
        val hits: List<RecordSearchHit>?,
        val chips: List<ParsedChip>,
        val summary: ProcessingSummary,
        val review: List<HealthRecord>,
        val highlights: List<HighlightWithRecord>,
        val tags: List<RecordTag>,
        val terms: List<String>
    )

    fun loadMore() {
        val after = cursor ?: return
        if (pageLock.isLocked) return
        viewModelScope.launch {
            pageLock.withLock {
                val q = loadedQuery ?: return@withLock
                if (cursor != after) return@withLock
                runCatching { store.page(effective(q).first, after, RecordsStore.PAGE_SIZE) }.onSuccess { page ->
                    cursor = page.next
                    _ui.update { state ->
                        val existing = state.items.mapTo(HashSet()) { it.id }
                        state.copy(
                            items = state.items + page.items.filter { it.id !in existing },
                            canLoadMore = page.next != null
                        )
                    }
                }
            }
        }
    }

    fun retry() {
        viewModelScope.launch { reload(query.value) }
    }

    fun setFilter(filter: RecordFilter) {
        _ui.update { it.copy(filter = filter, selection = emptySet()) }
        query.update { it.copy(filter = filter) }
    }

    fun setSearch(text: String) {
        _ui.update { it.copy(search = text) }
        query.update { it.copy(search = text) }
    }

    fun setAdvanced(filters: RecordAdvancedFilters) {
        _ui.update { it.copy(advanced = filters, selection = emptySet()) }
        query.update { it.copy(advanced = filters) }
    }

    /** Removes a parsed chip by dropping its words from the search text. */
    fun removeChip(chip: ParsedChip) {
        val token = chip.token.trim()
        if (token.isEmpty()) return
        val current = _ui.value.search
        val index = current.indexOf(token, ignoreCase = true)
        val next = if (index >= 0) (current.substring(0, index) + current.substring(index + token.length)) else current
        setSearch(next.replace(Regex("\\s+"), " ").trim())
    }

    fun chooseAiMode(mode: RecordsAiMode) {
        viewModelScope.launch { container.prefs.setHealthRecordsAiMode(mode.raw) }
    }

    /** Ask flow for every waiting record (§16 "Apply to all waiting records"). */
    fun applyAiToAllWaiting(requestedMode: String) {
        viewModelScope.launch {
            runCatching {
                val ids = store.awaitingConsent()
                for (id in ids) {
                    store.job(id)?.let { store.saveJob(it.copy(requestedMode = requestedMode, awaitingConsent = false, nextAttemptMs = 0, attempts = 0)) }
                }
                container.recordsQueue.schedule(ids)
            }
        }
    }

    /** "Search smarter with AI": sends only the query text; the reply becomes removable chips. */
    fun searchWithAi() {
        val text = _ui.value.search.trim()
        if (text.isEmpty() || _ui.value.aiSearchRunning) return
        _ui.update { it.copy(aiSearchRunning = true) }
        viewModelScope.launch {
            val rewritten = runCatching { container.recordsQueryRewriter.rewrite(text, _ui.value.aiMode) }.getOrNull()
            _ui.update { it.copy(aiSearchRunning = false, canSearchWithAi = false) }
            if (rewritten != null) setAdvanced(_ui.value.advanced.and(rewritten.filters)).also {
                if (rewritten.terms != null) setSearch(rewritten.terms.joinToString(" "))
            }
        }
    }

    fun setViewMode(mode: RecordsViewMode) {
        _ui.update { it.copy(viewMode = mode) }
        viewModelScope.launch { container.prefs.setHealthRecordsViewMode(mode.raw) }
    }

    fun toggleSelection(id: String) {
        _ui.update { state ->
            val next = if (id in state.selection) state.selection - id else state.selection + id
            state.copy(selection = next)
        }
    }

    fun clearSelection() {
        _ui.update { it.copy(selection = emptySet()) }
    }

    fun favoriteSelection() {
        val ids = _ui.value.selection
        val allFavorite = _ui.value.items.filter { it.id in ids }.all { it.favorite }
        applyToSelection(RecordPatch(favorite = !allFavorite))
    }

    fun archiveSelection() {
        val ids = _ui.value.selection
        val allArchived = _ui.value.items.filter { it.id in ids }.all { it.archived }
        applyToSelection(RecordPatch(archived = !allArchived))
    }

    fun deleteSelection() {
        val ids = _ui.value.selection.toList()
        clearSelection()
        viewModelScope.launch { runCatching { container.deleteRecords(ids) } }
    }

    private fun applyToSelection(patch: RecordPatch) {
        val ids = _ui.value.selection.toList()
        clearSelection()
        viewModelScope.launch { runCatching { store.update(ids, patch) } }
    }

    fun import(items: List<ImportItem>, spec: ImportSpec) {
        container.recordsImports.import(items, spec)
    }

    fun consumeNotice(id: Long) = container.recordsImports.consumeNotice(id)

    fun discardImported(record: HealthRecord) = container.recordsImports.discard(record)

    fun keepBoth(newId: String, existingId: String) = container.recordsImports.keepBoth(newId, existingId)
    fun replaceExisting(newId: String, existingId: String) = container.recordsImports.replace(newId, existingId)
    fun mergeIntoExisting(newId: String, existingId: String) = container.recordsImports.merge(newId, existingId)

    class Factory(private val container: AppContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = RecordsViewModel(container) as T
    }

    private companion object {
        const val SEARCH_DEBOUNCE_MS = 150L
        const val RECENT_COUNT = 8
        const val SECTION_COUNT = 8
        const val MIN_AI_SEARCH_TERMS = 2
    }
}
