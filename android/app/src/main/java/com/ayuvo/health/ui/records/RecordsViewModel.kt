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
import kotlinx.coroutines.FlowPreview
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
    val loadFailed: Boolean = false
) {
    val isBrowsingAll: Boolean get() = filter == RecordFilter.ALL && search.isBlank()
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
    }

    private suspend fun reload(q: RecordQuery) {
        pageLock.withLock {
            val keepWindow = loadedQuery == q
            val limit = if (keepWindow) maxOf(RecordsStore.PAGE_SIZE, _ui.value.items.size) else RecordsStore.PAGE_SIZE
            runCatching {
                val page = store.page(q, after = null, limit = limit)
                val recent = if (q.filter == RecordFilter.ALL && q.search.isBlank()) store.recent(RECENT_COUNT) else emptyList()
                val total = store.count()
                Triple(page, recent, total)
            }.onSuccess { (page, recent, total) ->
                cursor = page.next
                loadedQuery = q
                val ids = page.items.mapTo(HashSet()) { it.id }
                _ui.update {
                    it.copy(
                        loading = false,
                        items = page.items,
                        recent = recent,
                        totalCount = total,
                        canLoadMore = page.next != null,
                        selection = it.selection.filterTo(HashSet()) { id -> id in ids },
                        loadFailed = false
                    )
                }
            }.onFailure {
                _ui.update { it.copy(loading = false, loadFailed = true) }
            }
        }
    }

    fun loadMore() {
        val after = cursor ?: return
        if (pageLock.isLocked) return
        viewModelScope.launch {
            pageLock.withLock {
                val q = loadedQuery ?: return@withLock
                if (cursor != after) return@withLock
                runCatching { store.page(q, after, RecordsStore.PAGE_SIZE) }.onSuccess { page ->
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
        viewModelScope.launch { runCatching { store.delete(ids) } }
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

    class Factory(private val container: AppContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = RecordsViewModel(container) as T
    }

    private companion object {
        const val SEARCH_DEBOUNCE_MS = 150L
        const val RECENT_COUNT = 8
    }
}
