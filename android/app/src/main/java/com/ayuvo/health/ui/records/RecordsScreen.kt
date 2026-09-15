package com.ayuvo.health.ui.records

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ayuvo.health.AppContainer
import com.ayuvo.health.R
import com.ayuvo.health.records.ingest.DuplicateMatch
import com.ayuvo.health.records.model.HealthRecord
import com.ayuvo.health.records.model.RecordFilter
import com.ayuvo.health.records.model.RecordType
import com.ayuvo.health.ui.charts.IosStyleSegmentedControl
import com.ayuvo.health.ui.components.GlassDialog
import com.ayuvo.health.ui.components.GlassDialogActions
import com.ayuvo.health.ui.components.GlassSurface
import com.ayuvo.health.ui.components.GlassTextButton
import com.ayuvo.health.ui.components.GlassTextField
import com.ayuvo.health.ui.navigation.BottomNavScrollPadding
import com.ayuvo.health.ui.theme.AppColors
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * Records home (docs/health-records.md, Phase 1): header with search, Timeline | List | Grid,
 * filter chips, Recent strip, month-grouped timeline, long-press multi-select, and the
 * import notice ("Saved to Health Records") plus the exact-duplicate sheet.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun RecordsScreen(
    container: AppContainer,
    onOpenRecord: (String) -> Unit
) {
    val vm: RecordsViewModel = viewModel(factory = RecordsViewModel.Factory(container))
    val ui by vm.ui.collectAsState()
    val importing by vm.importing.collectAsState()
    val notice by vm.notice.collectAsState()
    val resources = LocalResources.current
    val snackbar = remember { SnackbarHostState() }
    val noticeScope = rememberCoroutineScope()
    var showAddSheet by remember { mutableStateOf(false) }
    var showFilters by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val duplicates = remember { mutableStateListOf<DuplicateMatch>() }
    val launchers = rememberAddRecordLaunchers(onImport = vm::import)

    BackHandler(enabled = ui.selecting) { vm.clearSelection() }

    LaunchedEffect(notice?.id) {
        val current = notice ?: return@LaunchedEffect
        duplicates += current.duplicates
        val res = resources
        val messages = buildList {
            if (current.savedCount > 0) {
                add(res.getQuantityString(R.plurals.records_saved_count, current.savedCount, current.savedCount))
            }
            if (current.tooLargeCount > 0) {
                add(res.getQuantityString(R.plurals.records_too_large, current.tooLargeCount, current.tooLargeCount))
            }
            if (current.unreadableCount > 0) {
                add(res.getQuantityString(R.plurals.records_unreadable, current.unreadableCount, current.unreadableCount))
            }
        }
        // Show on a screen-scoped coroutine: consuming the notice changes this effect's key,
        // which would otherwise cancel the snackbar before it appears.
        if (messages.isNotEmpty()) noticeScope.launch { snackbar.showSnackbar(messages.joinToString(" · ")) }
        vm.consumeNotice(current.id)
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar, modifier = Modifier.padding(bottom = BottomNavScrollPadding - 40.dp)) }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (ui.selecting) {
                SelectionBar(
                    count = ui.selection.size,
                    onClose = vm::clearSelection,
                    onFavorite = vm::favoriteSelection,
                    onArchive = vm::archiveSelection,
                    onDelete = { confirmDelete = true }
                )
            } else {
                RecordsHeader(onAdd = { showAddSheet = true })
            }
            if (ui.totalCount > 0 || !ui.isBrowsingAll) {
                RecordsSearchField(value = ui.search, onValueChange = vm::setSearch)
                ParsedChipsRow(chips = ui.chips, onRemove = vm::removeChip)
                IosStyleSegmentedControl(
                    options = RecordsViewMode.entries,
                    selected = ui.viewMode,
                    label = { stringResource(it.labelRes()) },
                    onSelect = vm::setViewMode,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                )
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    item(key = "filters") {
                        FiltersChip(count = ui.advanced.activeCount, onClick = { showFilters = true })
                    }
                    items(RecordFilter.entries, key = { it.name }) { filter ->
                        RecordChip(
                            text = stringResource(filter.labelRes()),
                            selected = ui.filter == filter,
                            onClick = { vm.setFilter(filter) }
                        )
                    }
                }
            }
            if (importing > 0) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = AppColors.Calorie)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        pluralStringResource(R.plurals.records_importing, importing, importing),
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
                    )
                }
            }
            ProcessingStrip(summary = ui.processing, progress = ui.progress)
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when {
                    ui.loadFailed && ui.items.isEmpty() -> LoadFailedBanner(onRetry = vm::retry)
                    ui.loading -> SkeletonRows()
                    ui.totalCount == 0L && ui.isBrowsingAll -> Column(
                        Modifier.fillMaxSize().padding(16.dp)
                    ) {
                        RecordsEmptyState(onAction = launchers.launch)
                    }
                    ui.searchHits != null -> SearchResults(
                        ui = ui,
                        container = container,
                        onOpen = { record -> if (ui.selecting) vm.toggleSelection(record.id) else onOpenRecord(record.id) },
                        onSearchWithAi = vm::searchWithAi
                    )
                    ui.items.isEmpty() -> Column {
                        if (ui.canSearchWithAi) Box(Modifier.padding(16.dp)) { AiSearchRow(ui.aiSearchRunning, vm::searchWithAi) }
                        NoMatches(search = ui.search)
                    }
                    else -> RecordsBody(
                        ui = ui,
                        container = container,
                        onOpen = { record -> if (ui.selecting) vm.toggleSelection(record.id) else onOpenRecord(record.id) },
                        onLongPress = { record -> vm.toggleSelection(record.id) },
                        onLoadMore = vm::loadMore,
                        onChooseAiMode = vm::chooseAiMode,
                        onApplyAiToAll = vm::applyAiToAllWaiting
                    )
                }
            }
        }
    }

    if (showAddSheet) {
        AddRecordSheet(onDismiss = { showAddSheet = false }, onAction = launchers.launch)
    }

    if (showFilters) {
        RecordsFilterSheet(
            initial = ui.advanced,
            tags = ui.allTags,
            onApply = {
                vm.setAdvanced(it)
                showFilters = false
            },
            onDismiss = { showFilters = false }
        )
    }

    duplicates.firstOrNull()?.let { match ->
        DuplicateRecordSheet(
            match = match,
            files = container.recordFiles,
            onKeepBoth = {
                duplicates.remove(match)
                vm.keepBoth(match.newRecord.id, match.existing.id)
            },
            onOpenExisting = {
                duplicates.remove(match)
                onOpenRecord(match.existing.id)
            },
            onCancelImport = {
                duplicates.remove(match)
                vm.discardImported(match.newRecord)
            },
            onReplace = {
                duplicates.remove(match)
                vm.replaceExisting(match.newRecord.id, match.existing.id)
            },
            onMerge = {
                duplicates.remove(match)
                vm.mergeIntoExisting(match.newRecord.id, match.existing.id)
            }
        )
    }

    if (confirmDelete) {
        GlassDialog(onDismissRequest = { confirmDelete = false }) {
            Text(
                pluralStringResource(R.plurals.records_delete_selected_title, ui.selection.size, ui.selection.size),
                fontSize = 21.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                stringResource(R.string.records_delete_message),
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.68f)
            )
            GlassDialogActions(
                primaryText = stringResource(R.string.action_delete),
                onPrimary = {
                    confirmDelete = false
                    vm.deleteSelection()
                },
                destructive = true,
                dismissText = stringResource(R.string.action_cancel),
                onDismiss = { confirmDelete = false }
            )
        }
    }
}

@Composable
private fun RecordsHeader(onAdd: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 12.dp, top = 12.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            stringResource(R.string.records_title),
            fontSize = 28.sp,
            lineHeight = 34.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.weight(1f)
        )
        GlassSurface(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .clickable(onClick = onAdd),
            cornerRadius = 22.dp,
            padding = 0.dp,
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.records_add_title), tint = AppColors.Calorie)
        }
    }
}

@Composable
private fun SelectionBar(
    count: Int,
    onClose: () -> Unit,
    onFavorite: () -> Unit,
    onArchive: () -> Unit,
    onDelete: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 58.dp)
            .padding(start = 4.dp, end = 8.dp, top = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onClose) { Icon(Icons.Filled.Close, stringResource(R.string.action_cancel)) }
        Text(
            pluralStringResource(R.plurals.records_selected_count, count, count),
            fontSize = 20.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f)
        )
        IconButton(onClick = onFavorite) {
            Icon(Icons.Outlined.StarOutline, stringResource(R.string.records_action_favorite), tint = AppColors.Calorie)
        }
        IconButton(onClick = onArchive) {
            Icon(Icons.Filled.Archive, stringResource(R.string.records_action_archive), tint = AppColors.Calorie)
        }
        IconButton(onClick = onDelete) {
            Icon(Icons.Filled.Delete, stringResource(R.string.action_delete), tint = Color(0xFFFF453A))
        }
    }
}

@Composable
private fun RecordsSearchField(value: String, onValueChange: (String) -> Unit) {
    Box(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
        GlassTextField(
            value = value,
            onValueChange = onValueChange,
            placeholder = stringResource(R.string.records_search_placeholder),
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Search)
        )
        if (value.isNotEmpty()) {
            IconButton(onClick = { onValueChange("") }, modifier = Modifier.align(Alignment.CenterEnd)) {
                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.action_clear))
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RecordsBody(
    ui: RecordsUiState,
    container: AppContainer,
    onOpen: (HealthRecord) -> Unit,
    onLongPress: (HealthRecord) -> Unit,
    onLoadMore: () -> Unit,
    onChooseAiMode: (com.ayuvo.health.records.model.RecordsAiMode) -> Unit,
    onApplyAiToAll: (String) -> Unit
) {
    val files = container.recordFiles
    val showRecent = ui.isBrowsingAll && ui.recent.isNotEmpty() && ui.viewMode != RecordsViewMode.GRID
    val bottomPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 6.dp, bottom = BottomNavScrollPadding)
    when (ui.viewMode) {
        RecordsViewMode.GRID -> {
            val gridState = rememberLazyGridState()
            LaunchedEffect(gridState, ui.items.size, ui.canLoadMore) {
                snapshotFlow { gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }
                    .distinctUntilChanged()
                    .collect { last -> if (ui.canLoadMore && last >= ui.items.size - PREFETCH_DISTANCE) onLoadMore() }
            }
            LazyVerticalGrid(
                state = gridState,
                columns = GridCells.Adaptive(minSize = 108.dp),
                contentPadding = bottomPadding,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                if (ui.isBrowsingAll) {
                    item(key = "sections", span = { GridItemSpan(maxLineSpan) }) {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            HomeSectionsContent(ui, container, onOpen, onChooseAiMode, onApplyAiToAll)
                        }
                    }
                }
                if (ui.isBrowsingAll && ui.recent.isNotEmpty()) {
                    item(key = "recent", span = { GridItemSpan(maxLineSpan) }) {
                        RecentStrip(ui, container, onOpen)
                    }
                }
                items(ui.items, key = { it.id }) { record ->
                    RecordGridCell(
                        record = record,
                        files = files,
                        selecting = ui.selecting,
                        selected = record.id in ui.selection,
                        onClick = { onOpen(record) },
                        onLongClick = { onLongPress(record) }
                    )
                }
            }
        }
        RecordsViewMode.TIMELINE, RecordsViewMode.LIST -> {
            val listState = rememberLazyListState()
            LaunchedEffect(listState, ui.items.size, ui.canLoadMore) {
                snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }
                    .distinctUntilChanged()
                    .collect { last ->
                        val total = listState.layoutInfo.totalItemsCount
                        if (ui.canLoadMore && last >= total - PREFETCH_DISTANCE) onLoadMore()
                    }
            }
            LazyColumn(
                state = listState,
                contentPadding = bottomPadding,
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                homeSections(ui, container, onOpen, onChooseAiMode, onApplyAiToAll)
                if (showRecent) {
                    item(key = "recent") { RecentStrip(ui, container, onOpen) }
                }
                if (ui.viewMode == RecordsViewMode.TIMELINE) {
                    val groups = ui.items.groupBy { it.sortDate.take(7) }
                    groups.forEach { (month, records) ->
                        stickyHeader(key = "month-$month") {
                            MonthHeader(RecordFormat.monthHeader(records.first().sortDate))
                        }
                        items(records, key = { it.id }) { record ->
                            RecordRow(
                                record = record,
                                files = files,
                                selecting = ui.selecting,
                                selected = record.id in ui.selection,
                                onClick = { onOpen(record) },
                                onLongClick = { onLongPress(record) }
                            )
                        }
                    }
                } else {
                    items(ui.items, key = { it.id }) { record ->
                        RecordRow(
                            record = record,
                            files = files,
                            selecting = ui.selecting,
                            selected = record.id in ui.selection,
                            onClick = { onOpen(record) },
                            onLongClick = { onLongPress(record) }
                        )
                    }
                }
                if (ui.canLoadMore) {
                    item(key = "more") {
                        Box(Modifier.fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = AppColors.Calorie)
                        }
                    }
                }
            }
        }
    }
}

/** One-time AI chooser, consent banner, Needs review and Important highlights (browsing only). */
private fun androidx.compose.foundation.lazy.LazyListScope.homeSections(
    ui: RecordsUiState,
    container: AppContainer,
    onOpen: (HealthRecord) -> Unit,
    onChooseAiMode: (com.ayuvo.health.records.model.RecordsAiMode) -> Unit,
    onApplyAiToAll: (String) -> Unit
) {
    if (!ui.isBrowsingAll || ui.selecting) return
    item(key = "home-sections") {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            HomeSectionsContent(ui, container, onOpen, onChooseAiMode, onApplyAiToAll)
        }
    }
}

@Composable
private fun HomeSectionsContent(
    ui: RecordsUiState,
    container: AppContainer,
    onOpen: (HealthRecord) -> Unit,
    onChooseAiMode: (com.ayuvo.health.records.model.RecordsAiMode) -> Unit,
    onApplyAiToAll: (String) -> Unit
) {
    val options = ui.aiOptions
    if (ui.aiModeLoaded && ui.aiMode == null && options != null) {
        RecordsAiChooserCard(onChoose = onChooseAiMode, options = options)
    }
    if (ui.processing.awaitingConsent > 1 && options != null) {
        AiConsentBanner(
            options = options,
            waitingCount = ui.processing.awaitingConsent,
            onLocal = { onApplyAiToAll("local") },
            onCloud = { onApplyAiToAll("cloud") },
            onNotNow = { onApplyAiToAll("off") },
            onApplyAll = onApplyAiToAll
        )
    }
    NeedsReviewSection(records = ui.needsReview, files = container.recordFiles, onOpen = onOpen)
    HighlightsSection(items = ui.highlights, onOpen = { onOpen(it.record) })
}

/** Universal search results (§17), grouped under "Records" with matched snippets. */
@Composable
private fun SearchResults(
    ui: RecordsUiState,
    container: AppContainer,
    onOpen: (HealthRecord) -> Unit,
    onSearchWithAi: () -> Unit
) {
    val hits = ui.searchHits.orEmpty()
    LazyColumn(
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 6.dp, bottom = BottomNavScrollPadding),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        if (ui.processing.processing > 0) {
            item(key = "processing-note") {
                Text(
                    stringResource(R.string.records_search_processing_note),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    modifier = Modifier.padding(start = 4.dp)
                )
            }
        }
        if (ui.canSearchWithAi) {
            item(key = "ai-search") { AiSearchRow(ui.aiSearchRunning, onSearchWithAi) }
        }
        if (hits.isEmpty()) {
            item(key = "none") { NoMatches(search = ui.search) }
        } else {
            item(key = "group-records") { SectionTitle(stringResource(R.string.records_search_group_records)) }
            items(hits, key = { "hit-${it.record.id}" }) { hit ->
                SearchHitRow(hit = hit, files = container.recordFiles, onClick = { onOpen(hit.record) })
            }
        }
    }
}

@Composable
private fun RecentStrip(ui: RecordsUiState, container: AppContainer, onOpen: (HealthRecord) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
        Text(
            stringResource(R.string.records_recent),
            fontSize = 17.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
        )
        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(ui.recent, key = { "recent-${it.id}" }) { record ->
                Column(
                    Modifier
                        .width(112.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .clickable { onOpen(record) },
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    RecordThumbnail(
                        record = record,
                        files = container.recordFiles,
                        size = null,
                        cornerRadius = 14.dp,
                        modifier = Modifier.fillMaxWidth().aspectRatio(0.85f)
                    )
                    Text(record.title, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun MonthHeader(text: String) {
    Box(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .padding(start = 4.dp, top = 10.dp, bottom = 6.dp)
    ) {
        Text(text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.72f))
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RecordRow(
    record: HealthRecord,
    files: com.ayuvo.health.records.data.RecordFileStore,
    selecting: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    GlassSurface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        cornerRadius = 18.dp,
        padding = 10.dp
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (selecting) {
                SelectionMark(selected)
                Spacer(Modifier.width(8.dp))
            }
            RecordThumbnail(record = record, files = files)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(record.title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    recordSubtitle(record),
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }
            RecordStatusPill(record)
            if (record.favorite) {
                Spacer(Modifier.width(6.dp))
                Icon(Icons.Filled.Star, stringResource(R.string.records_filter_favorites), tint = AppColors.Calorie, modifier = Modifier.size(18.dp))
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RecordGridCell(
    record: HealthRecord,
    files: com.ayuvo.health.records.data.RecordFileStore,
    selecting: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    Column(
        Modifier
            .clip(RoundedCornerShape(16.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Box {
            RecordThumbnail(
                record = record,
                files = files,
                size = null,
                cornerRadius = 16.dp,
                modifier = Modifier.fillMaxWidth().aspectRatio(0.8f)
            )
            if (selecting) {
                Box(Modifier.align(Alignment.TopEnd).padding(6.dp)) { SelectionMark(selected) }
            } else if (record.favorite) {
                Icon(
                    Icons.Filled.Star,
                    null,
                    tint = AppColors.Calorie,
                    modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).size(18.dp)
                )
            }
        }
        Text(record.title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
        RecordStatusPill(record)
        Text(
            RecordFormat.displayDate(record),
            fontSize = 11.sp,
            maxLines = 1,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
        )
    }
}

@Composable
private fun SelectionMark(selected: Boolean) {
    Icon(
        if (selected) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked,
        contentDescription = null,
        tint = if (selected) AppColors.Calorie else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
        modifier = Modifier.size(22.dp)
    )
}

@Composable
private fun recordSubtitle(record: HealthRecord): String {
    val parts = mutableListOf(RecordFormat.displayDate(record))
    if (record.recordType != RecordType.OTHER) parts += stringResource(record.recordType.labelRes())
    parts += when (record.fileType) {
        com.ayuvo.health.records.model.RecordFileType.PDF ->
            if (record.pageCount > 0) pluralStringResource(R.plurals.records_pages, record.pageCount, record.pageCount)
            else stringResource(R.string.records_file_pdf)
        com.ayuvo.health.records.model.RecordFileType.IMAGE -> stringResource(R.string.records_file_image)
        com.ayuvo.health.records.model.RecordFileType.TEXT -> stringResource(R.string.records_file_text)
        com.ayuvo.health.records.model.RecordFileType.OTHER -> stringResource(R.string.records_file_other)
    }
    if (record.isReceived) parts += stringResource(R.string.records_filter_received)
    if (record.archived) parts += stringResource(R.string.records_archived_badge)
    return parts.joinToString(" · ")
}

@Composable
private fun SkeletonRows() {
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val fill = if (isDark) Color.White.copy(alpha = 0.06f) else Color.Black.copy(alpha = 0.05f)
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        repeat(4) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(68.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(fill)
            )
        }
    }
}

@Composable
private fun NoMatches(search: String) {
    Column(
        Modifier.fillMaxWidth().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            if (search.isBlank()) stringResource(R.string.records_no_matches_filter)
            else stringResource(R.string.records_no_results, search.trim()),
            fontWeight = FontWeight.SemiBold
        )
        if (search.isNotBlank()) {
            Text(
                stringResource(R.string.records_no_results_hint),
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
        }
    }
}

@Composable
private fun LoadFailedBanner(onRetry: () -> Unit) {
    GlassSurface(Modifier.fillMaxWidth().padding(16.dp), cornerRadius = 18.dp, padding = 14.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.records_load_failed), modifier = Modifier.weight(1f))
            GlassTextButton(text = stringResource(R.string.action_retry), onClick = onRetry)
        }
    }
}

private const val PREFETCH_DISTANCE = 12
