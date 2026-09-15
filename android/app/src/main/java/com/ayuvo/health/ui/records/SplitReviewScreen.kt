package com.ayuvo.health.ui.records

import android.graphics.Bitmap
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ayuvo.health.AppContainer
import com.ayuvo.health.R
import com.ayuvo.health.records.model.HealthRecord
import com.ayuvo.health.records.model.RecordType
import com.ayuvo.health.records.model.SplitSegment
import com.ayuvo.health.records.model.SplitStatus
import com.ayuvo.health.ui.components.GlassPrimaryButton
import com.ayuvo.health.ui.components.GlassSurface
import com.ayuvo.health.ui.components.GlassTextButton
import com.ayuvo.health.ui.components.GlassTextField
import com.ayuvo.health.ui.components.OptionPickerSheet
import com.ayuvo.health.ui.navigation.BottomNavScrollPadding
import com.ayuvo.health.ui.theme.AppColors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Editable segment of the split review (0-based inclusive pages of the parent file). */
data class SplitDraft(val pageStart: Int, val pageEnd: Int, val recordType: RecordType, val title: String, val confidence: Double)

data class SplitReviewUiState(
    val loading: Boolean = true,
    val record: HealthRecord? = null,
    val pageCount: Int = 0,
    val segments: List<SplitDraft> = emptyList(),
    val saving: Boolean = false
)

/**
 * Split review (plan §3.11): page strip with coloured segments; tapping a page starts a record
 * there (or merges it back into the previous one). Saving creates children that share the parent
 * file; nothing is split without this explicit action.
 */
class SplitReviewViewModel(private val container: AppContainer, private val recordId: String) : ViewModel() {
    private val store get() = container.recordsStore
    private val _ui = MutableStateFlow(SplitReviewUiState())
    val ui: StateFlow<SplitReviewUiState> = _ui.asStateFlow()

    init {
        viewModelScope.launch {
            runCatching {
                val record = store.record(recordId)
                val proposal = store.splitProposal(recordId)
                val pageCount = record?.pageCount?.takeIf { it > 0 } ?: store.pages(recordId).size
                val segments = proposal?.segments.orEmpty().map { SplitDraft(it.pageStart, it.pageEnd, it.recordType, it.title, it.confidence) }
                    .ifEmpty { listOf(SplitDraft(0, (pageCount - 1).coerceAtLeast(0), record?.recordType ?: RecordType.OTHER, record?.title.orEmpty(), 0.0)) }
                _ui.update { it.copy(loading = false, record = record, pageCount = pageCount, segments = segments) }
            }.onFailure { _ui.update { it.copy(loading = false) } }
        }
    }

    /** Tap on [page]: toggles a boundary starting at that page (page 0 always starts a record). */
    fun togglePage(page: Int) {
        if (page <= 0) return
        _ui.update { state ->
            val starts = state.segments.map { it.pageStart }.toMutableSet()
            if (page in starts) starts.remove(page) else starts.add(page)
            state.copy(segments = rebuild(state.segments, starts.sorted(), state.pageCount))
        }
    }

    fun setTitle(index: Int, title: String) = _ui.update { s ->
        s.copy(segments = s.segments.mapIndexed { i, seg -> if (i == index) seg.copy(title = title) else seg })
    }

    fun setType(index: Int, type: RecordType) = _ui.update { s ->
        s.copy(segments = s.segments.mapIndexed { i, seg -> if (i == index) seg.copy(recordType = type) else seg })
    }

    fun save(label: (RecordType) -> String, onDone: (Int) -> Unit) {
        val state = _ui.value
        if (state.segments.size < 2 || state.saving) return
        _ui.update { it.copy(saving = true) }
        viewModelScope.launch {
            val ids = runCatching {
                store.acceptSplit(
                    recordId,
                    state.segments.map { SplitSegment(it.pageStart, it.pageEnd, it.recordType, it.title.trim(), it.confidence) }
                ) { seg -> "${label(seg.recordType)} — ${seg.pageStart + 1}–${seg.pageEnd + 1}" }
            }.getOrDefault(emptyList())
            if (ids.isNotEmpty()) container.recordsQueue.schedule(ids)
            _ui.update { it.copy(saving = false) }
            onDone(ids.size)
        }
    }

    fun keepAsOne(onDone: () -> Unit) {
        viewModelScope.launch {
            runCatching {
                store.setSplitStatus(recordId, SplitStatus.REJECTED)
                container.recordsPipeline.evaluateReview(recordId)
            }
            onDone()
        }
    }

    class Factory(private val container: AppContainer, private val recordId: String) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = SplitReviewViewModel(container, recordId) as T
    }

    companion object {
        /** Segments for sorted [starts]; an existing segment keeps its title/type when its start survives. */
        fun rebuild(previous: List<SplitDraft>, starts: List<Int>, pageCount: Int): List<SplitDraft> {
            val all = (listOf(0) + starts).distinct().sorted().filter { it in 0 until pageCount.coerceAtLeast(1) }
            return all.mapIndexed { i, start ->
                val end = (all.getOrNull(i + 1)?.minus(1)) ?: (pageCount - 1).coerceAtLeast(start)
                val keep = previous.firstOrNull { it.pageStart == start }
                    ?: previous.lastOrNull { it.pageStart <= start }?.let { it.copy(title = "", confidence = 0.0) }
                SplitDraft(start, end, keep?.recordType ?: RecordType.OTHER, keep?.title.orEmpty(), keep?.confidence ?: 0.0)
            }
        }
    }
}

private val segmentColors = listOf(
    Color(0xFFFF7A45), Color(0xFF4C8DFF), Color(0xFF34C759), Color(0xFFAF52DE), Color(0xFFFFB020), Color(0xFF00B8D9)
)

@Composable
fun SplitReviewScreen(container: AppContainer, recordId: String, onBack: () -> Unit, onOpenRecord: (String) -> Unit) {
    val vm: SplitReviewViewModel = viewModel(key = "split-$recordId", factory = SplitReviewViewModel.Factory(container, recordId))
    val ui by vm.ui.collectAsState()
    val context = LocalContext.current
    val resources = LocalResources.current
    var typePickerFor by remember { mutableStateOf<Int?>(null) }
    val record = ui.record

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 12.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.records_title), tint = AppColors.Calorie) }
            Text(stringResource(R.string.records_split_title), fontSize = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        }
        if (ui.loading || record == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = AppColors.Calorie) }
            return@Column
        }
        val file = remember(record.filePath) { container.recordFiles.resolve(record.filePath)?.takeIf { it.isFile } }
        val source by produceState<PdfPageSource?>(initialValue = null, file) {
            value = file?.let { PdfPageSource.open(it, record.id, container.recordFiles.renderPages).getOrNull() }
        }
        val opened = source
        DisposableEffect(opened) {
            // Capture this value: the delegate would already hold the next source when disposing.
            onDispose { opened?.let { s -> splitCloseScope.launch { s.close() } } }
        }

        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = BottomNavScrollPadding),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            item(key = "hint") {
                Text(stringResource(R.string.records_split_hint), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f))
            }
            item(key = "strip") {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items((0 until ui.pageCount).toList(), key = { it }) { page ->
                        val segmentIndex = ui.segments.indexOfLast { it.pageStart <= page }.coerceAtLeast(0)
                        val color = segmentColors[segmentIndex % segmentColors.size]
                        val isStart = ui.segments.any { it.pageStart == page }
                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(84.dp)) {
                            Box(Modifier.height(6.dp).fillMaxWidth().clip(RoundedCornerShape(50)).background(color))
                            Spacer(Modifier.height(4.dp))
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .aspectRatio(0.72f)
                                    .clip(RoundedCornerShape(10.dp))
                                    .border(if (isStart) 2.5.dp else 1.dp, if (isStart) color else color.copy(alpha = 0.4f), RoundedCornerShape(10.dp))
                                    .background(Color.White)
                                    .clickable { vm.togglePage(page) },
                                contentAlignment = Alignment.Center
                            ) {
                                val bitmap by produceState<Bitmap?>(initialValue = null, source, page) {
                                    value = source?.render(page, THUMB_WIDTH_PX)
                                }
                                val bmp = bitmap
                                if (bmp != null) {
                                    Image(bmp.asImageBitmap(), null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                                } else {
                                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = color)
                                }
                            }
                            Text("${page + 1}", fontSize = 12.sp, fontWeight = if (isStart) FontWeight.Bold else FontWeight.Normal)
                        }
                    }
                }
            }
            items(ui.segments.size, key = { "seg-${ui.segments[it].pageStart}" }) { index ->
                val seg = ui.segments[index]
                val color = segmentColors[index % segmentColors.size]
                GlassSurface(Modifier.fillMaxWidth(), cornerRadius = 18.dp, padding = 14.dp) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(12.dp).clip(CircleShape).background(color))
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.records_split_pages, seg.pageStart + 1, seg.pageEnd + 1), fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                            RecordChip(text = stringResource(seg.recordType.labelRes()), selected = false, onClick = { typePickerFor = index })
                        }
                        GlassTextField(
                            value = seg.title,
                            onValueChange = { vm.setTitle(index, it) },
                            placeholder = stringResource(R.string.records_split_segment_title)
                        )
                    }
                }
            }
            item(key = "actions") {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (ui.segments.size < 2) {
                        Text(stringResource(R.string.records_split_need_two), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                    }
                    GlassPrimaryButton(
                        text = stringResource(R.string.records_split_save),
                        enabled = ui.segments.size >= 2 && !ui.saving,
                        onClick = {
                            vm.save(label = { resources.getString(it.labelRes()) }) { count ->
                                Toast.makeText(context, resources.getString(R.string.records_split_saved, count), Toast.LENGTH_LONG).show()
                                onBack()
                            }
                        }
                    )
                    GlassTextButton(text = stringResource(R.string.records_split_keep), onClick = { vm.keepAsOne(onBack) }, modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }

    typePickerFor?.let { index ->
        OptionPickerSheet(
            title = stringResource(R.string.records_field_type),
            items = RecordType.entries,
            label = { stringResource(it.labelRes()) },
            selected = { it == ui.segments.getOrNull(index)?.recordType },
            onSelect = {
                vm.setType(index, it)
                typePickerFor = null
            },
            onDismiss = { typePickerFor = null }
        )
    }
}

private const val THUMB_WIDTH_PX = 240

private val splitCloseScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)
