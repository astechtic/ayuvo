package com.ayuvo.health.ui.records

import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ayuvo.health.R
import com.ayuvo.health.records.data.RecordRenderCache
import com.ayuvo.health.records.model.HealthRecord
import com.ayuvo.health.records.model.RecordFileType
import com.ayuvo.health.records.model.RecordPage
import com.ayuvo.health.services.FoodImageDecoder
import com.ayuvo.health.ui.components.FullScreenImageViewer
import com.ayuvo.health.ui.components.GlassTextButton
import com.ayuvo.health.ui.theme.AppColors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import androidx.core.graphics.createBitmap
import java.io.File
import kotlin.math.roundToInt

/** Why the original can't be drawn; the record itself is always still shown. */
enum class ViewerProblem { PROTECTED, UNREADABLE, UNSUPPORTED, MISSING }

/**
 * One open PdfRenderer for the viewer. PdfRenderer allows a single open page at a time, so
 * every render is serialized by [mutex]; pages render at the requested width only.
 */
internal class PdfPageSource private constructor(
    private val pfd: ParcelFileDescriptor,
    private val renderer: PdfRenderer,
    private val recordId: String,
    private val cache: RecordRenderCache?
) {
    private val mutex = Mutex()
    private var closed = false
    val pageCount: Int = renderer.pageCount

    suspend fun render(index: Int, widthPx: Int): Bitmap? = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (closed || index !in 0 until pageCount) return@withLock null
            val width = widthPx.coerceIn(1, MAX_RENDER_WIDTH)
            cache?.get(recordId, index, width)?.let { return@withLock it }
            runCatching {
                renderer.openPage(index).use { page ->
                    val height = (width.toFloat() * page.height / page.width.coerceAtLeast(1)).roundToInt().coerceAtLeast(1)
                    createBitmap(width, height).also { bmp ->
                        bmp.eraseColor(AndroidColor.WHITE)
                        page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    }
                }
            }.getOrNull()?.also { bmp -> cache?.put(recordId, index, width, bmp) }
        }
    }

    suspend fun close() = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (closed) return@withLock
            closed = true
            runCatching { renderer.close() }
            runCatching { pfd.close() }
        }
    }

    companion object {
        private const val MAX_RENDER_WIDTH = 2048

        /** Opens [file]; the failure maps to a [ViewerProblem] instead of throwing. */
        suspend fun open(file: File, recordId: String, cache: RecordRenderCache?): Result<PdfPageSource> = withContext(Dispatchers.IO) {
            var pfd: ParcelFileDescriptor? = null
            try {
                pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                Result.success(PdfPageSource(pfd, PdfRenderer(pfd), recordId, cache))
            } catch (e: Exception) {
                runCatching { pfd?.close() }
                Result.failure(e)
            }
        }
    }
}

@Composable
internal fun RecordViewer(
    record: HealthRecord,
    file: File?,
    pages: List<RecordPage>,
    height: Dp,
    renderCache: RecordRenderCache?,
    onOpenElsewhere: () -> Unit,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(20.dp)
    Box(
        modifier
            .fillMaxWidth()
            .height(height)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
    ) {
        when {
            file == null && record.fileType != RecordFileType.TEXT -> ViewerProblemCard(ViewerProblem.MISSING, onOpenElsewhere)
            record.fileType == RecordFileType.PDF -> PdfPager(file!!, record.id, renderCache, onOpenElsewhere)
            record.fileType == RecordFileType.IMAGE -> ImageViewer(file!!, onOpenElsewhere)
            record.fileType == RecordFileType.TEXT -> TextViewer(pages)
            else -> ViewerProblemCard(ViewerProblem.UNSUPPORTED, onOpenElsewhere)
        }
    }
}

@Composable
private fun PdfPager(file: File, recordId: String, cache: RecordRenderCache?, onOpenElsewhere: () -> Unit) {
    val source by produceState<Result<PdfPageSource>?>(initialValue = null, file) {
        value = PdfPageSource.open(file, recordId, cache)
    }
    val opened = source?.getOrNull()
    DisposableEffect(opened) {
        onDispose {
            // Closing needs the mutex; run it on a detached scope so an in-flight render finishes first.
            opened?.let { pdf -> viewerCloseScope.launch { pdf.close() } }
        }
    }
    when {
        source == null -> Loading()
        opened == null -> {
            val problem = if (source?.exceptionOrNull() is SecurityException) ViewerProblem.PROTECTED else ViewerProblem.UNREADABLE
            ViewerProblemCard(problem, onOpenElsewhere)
        }
        opened.pageCount == 0 -> ViewerProblemCard(ViewerProblem.UNREADABLE, onOpenElsewhere)
        else -> {
            val pagerState = rememberPagerState(pageCount = { opened.pageCount })
            var fullScreen by remember { mutableStateOf<Bitmap?>(null) }
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val widthPx = with(LocalDensity.current) { maxWidth.roundToPx() }
                HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { index ->
                    val bitmap by produceState<Bitmap?>(initialValue = null, opened, index, widthPx) {
                        value = opened.render(index, widthPx)
                    }
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        val bmp = bitmap
                        if (bmp == null) {
                            Loading()
                        } else {
                            Image(
                                bitmap = bmp.asImageBitmap(),
                                contentDescription = stringResource(R.string.records_viewer_page, index + 1, opened.pageCount),
                                contentScale = ContentScale.Fit,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .clickable { fullScreen = bmp }
                            )
                        }
                    }
                }
                Text(
                    stringResource(R.string.records_viewer_page, pagerState.currentPage + 1, opened.pageCount),
                    fontSize = 12.sp,
                    color = Color.White,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(10.dp)
                        .clip(RoundedCornerShape(50))
                        .background(Color.Black.copy(alpha = 0.55f))
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                )
            }
            fullScreen?.let { bmp -> FullScreenImageViewer(bitmaps = listOf(bmp), onDismiss = { fullScreen = null }) }
        }
    }
}

/** Outlives the composition so a renderer closes after its in-flight page render. */
private val viewerCloseScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

@Composable
private fun ImageViewer(file: File, onOpenElsewhere: () -> Unit) {
    val decoded by produceState<Result<Bitmap?>?>(initialValue = null, file) {
        value = withContext(Dispatchers.IO) { runCatching { FoodImageDecoder.decode(file, VIEWER_MAX_DIMENSION) } }
    }
    var fullScreen by remember { mutableStateOf(false) }
    val bitmap = decoded?.getOrNull()
    when {
        decoded == null -> Loading()
        bitmap == null -> ViewerProblemCard(ViewerProblem.UNREADABLE, onOpenElsewhere)
        else -> {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().clickable { fullScreen = true }
            )
            if (fullScreen) FullScreenImageViewer(bitmaps = listOf(bitmap), onDismiss = { fullScreen = false })
        }
    }
}

@Composable
private fun TextViewer(pages: List<RecordPage>) {
    val text = pages.joinToString("\n\n") { it.text.orEmpty() }
    SelectionContainer {
        Text(
            text.ifBlank { " " },
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        )
    }
}

@Composable
private fun Loading() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.5.dp, color = AppColors.Calorie)
    }
}

@Composable
internal fun ViewerProblemCard(problem: ViewerProblem, onOpenElsewhere: () -> Unit) {
    val (title, body) = when (problem) {
        ViewerProblem.PROTECTED -> R.string.records_viewer_protected_title to R.string.records_viewer_protected_body
        ViewerProblem.UNREADABLE -> R.string.records_viewer_unreadable_title to R.string.records_viewer_unreadable_body
        ViewerProblem.UNSUPPORTED -> R.string.records_viewer_unsupported_title to R.string.records_viewer_unreadable_body
        ViewerProblem.MISSING -> R.string.records_viewer_missing_title to R.string.records_viewer_missing_body
    }
    Column(
        Modifier.fillMaxSize().padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically)
    ) {
        Icon(
            if (problem == ViewerProblem.PROTECTED) Icons.Filled.Lock else Icons.Filled.BrokenImage,
            null,
            tint = AppColors.Calorie,
            modifier = Modifier.size(36.dp)
        )
        Text(stringResource(title), fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
        Text(
            stringResource(body),
            fontSize = 13.sp,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
        )
        if (problem != ViewerProblem.MISSING) {
            GlassTextButton(text = stringResource(R.string.records_open_in_other_app), onClick = onOpenElsewhere)
        }
    }
}

private const val VIEWER_MAX_DIMENSION = 2048
