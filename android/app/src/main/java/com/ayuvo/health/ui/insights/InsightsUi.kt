package com.ayuvo.health.ui.insights

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ShowChart
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.ayuvo.health.R
import com.ayuvo.health.insights.InsightsConfig
import com.ayuvo.health.insights.InsightsExplainer
import com.ayuvo.health.ui.components.ActivityRing
import com.ayuvo.health.ui.design.AyuvoColors
import com.ayuvo.health.ui.design.AyuvoShapes
import com.ayuvo.health.ui.design.AyuvoSpacing
import com.ayuvo.health.ui.design.AyuvoTopBar
import com.ayuvo.health.ui.design.EmptyState
import com.ayuvo.health.ui.design.InsetGroup
import com.ayuvo.health.ui.design.SectionHeader
import com.ayuvo.health.ui.design.SurfaceCard
import com.ayuvo.health.ui.navigation.BottomNavScrollPadding

/**
 * Pushed Insights screen: title bar with the ⓘ "How we calculate this" button, a grouped list and
 * the disclaimer footer (docs/insights.md §7).
 */
@Composable
internal fun InsightsScaffold(
    title: String,
    tag: String,
    onBack: () -> Unit,
    onInfo: () -> Unit,
    disclaimers: List<String>,
    content: LazyListScope.() -> Unit
) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            AyuvoTopBar(title = title, onBack = onBack, actions = {
                IconButton(onClick = onInfo, modifier = Modifier.testTag("$tag.info")) {
                    Icon(Icons.Outlined.Info, contentDescription = stringResource(R.string.insights_info))
                }
            })
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).testTag(tag),
            contentPadding = PaddingValues(start = AyuvoSpacing.ScreenH, end = AyuvoSpacing.ScreenH, top = 4.dp, bottom = BottomNavScrollPadding + 16.dp),
            verticalArrangement = Arrangement.spacedBy(AyuvoSpacing.ItemGap)
        ) {
            content()
            item(key = "disclaimer") { DisclaimerFooter(disclaimers) }
        }
    }
}

/** Re-reads when the screen resumes (a new day, freshly synced sleep). */
@Composable
internal fun RefreshOnResume(onResume: () -> Unit) {
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) onResume() }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
}

@Composable
internal fun DisclaimerFooter(texts: List<String>) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 12.dp).testTag("insights.disclaimer"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        texts.distinct().forEach {
            Text(it, fontSize = 12.sp, lineHeight = 16.sp, color = AyuvoColors.secondaryLabel(), textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        }
    }
}

/** A single score ring with the number in the middle; a missing score shows "—". */
@Composable
internal fun ScoreRing(score: Int?, color: Color, caption: String, size: Dp = 120.dp, stroke: Dp = 12.dp) {
    ActivityRing(
        progress = (score ?: 0) / 100f,
        size = size,
        strokeWidth = stroke,
        gradientColors = listOf(color),
        showEndDot = false,
        trackColor = color.copy(alpha = 0.18f)
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(score?.toString() ?: InsightsFormat.MISSING, fontSize = if (size > 90.dp) 34.sp else 20.sp, fontWeight = FontWeight.Bold)
            if (caption.isNotEmpty()) Text(caption, fontSize = 11.sp, color = AyuvoColors.secondaryLabel())
        }
    }
}

/** Collecting / missing-data state inside a card: never a zero, always the reason. */
@Composable
internal fun StateCard(icon: ImageVector, title: String, message: String, tag: String) {
    SurfaceCard(modifier = Modifier.testTag(tag)) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            EmptyState(icon = icon, title = title, message = message)
        }
    }
}

/** Small rounded label ("+5.6", "Moderate", "Pace 0.74 · improving"). */
@Composable
internal fun Chip(text: String, color: Color, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier = modifier.clip(RoundedCornerShape(50)).background(color.copy(alpha = 0.14f)).padding(horizontal = 10.dp, vertical = 4.dp),
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        color = color
    )
}

/**
 * "Explain with AI": on tap only. Without a configured text model it shows the Settings hint and
 * the screen keeps working; a rejected or failed answer leaves the deterministic text in place.
 */
@Composable
internal fun ExplainSection(state: ExplainUi, availability: InsightsExplainer.Availability?, disclaimer: String?, tag: String, onExplain: () -> Unit) {
    SurfaceCard(modifier = Modifier.testTag("$tag.explain"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        when {
            availability == InsightsExplainer.Availability.NotConfigured -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.AutoAwesome, contentDescription = null, tint = AyuvoColors.secondaryLabel())
                    Spacer(Modifier.width(10.dp))
                    Text(stringResource(R.string.insights_explain_setup), fontSize = 14.sp, color = AyuvoColors.secondaryLabel())
                }
            }
            state is ExplainUi.Done -> {
                Text(state.headline, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                state.bullets.forEach { Text("• $it", fontSize = 15.sp, lineHeight = 20.sp) }
                Text(state.status, fontSize = 12.sp, color = AyuvoColors.secondaryLabel(), modifier = Modifier.testTag("$tag.explain.status"))
                disclaimer?.let { Text(it, fontSize = 12.sp, color = AyuvoColors.secondaryLabel()) }
            }
            else -> {
                OutlinedButton(onClick = onExplain, enabled = state != ExplainUi.Loading && availability != null, modifier = Modifier.fillMaxWidth().testTag("$tag.explain.button")) {
                    Icon(Icons.Outlined.AutoAwesome, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(if (state == ExplainUi.Loading) R.string.insights_explaining else R.string.insights_explain))
                }
                when (state) {
                    ExplainUi.Rejected -> Text(stringResource(R.string.insights_explain_rejected), fontSize = 13.sp, color = AyuvoColors.secondaryLabel())
                    ExplainUi.Failed -> Text(stringResource(R.string.insights_explain_failed), fontSize = 13.sp, color = AyuvoColors.secondaryLabel())
                    else -> Unit
                }
            }
        }
    }
}

/** The per-screen chart card (30-day Recovery, 12-week Health Age), empty state when nothing plots. */
@Composable
internal fun ChartCard(title: String, hasData: Boolean, tag: String, chart: @Composable () -> Unit) {
    SectionHeader(title)
    SurfaceCard(modifier = Modifier.testTag(tag), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (hasData) chart() else Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            EmptyState(icon = Icons.AutoMirrored.Filled.ShowChart, title = stringResource(R.string.health_detail_no_data_range), message = "")
        }
    }
}

/**
 * "How we calculate this" (docs/insights.md §7): the config methodology text for the feature, the
 * weights table, the person's own inputs (with what was missing), the sources and the disclaimers.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
internal fun InsightMethodologySheet(config: InsightsConfig, content: MethodologyContent, onDismiss: () -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = AyuvoShapes.Sheet,
        containerColor = AyuvoColors.sheetBackground(),
        modifier = Modifier.testTag("insights.methodology").semantics { testTagsAsResourceId = true }
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .navigationBarsPadding()
                .padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.insights_info), fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f).padding(start = 4.dp))
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.insights_info_close)) }
            }
            for (id in content.methodologyIds) {
                val m = config.methodology[id] ?: continue
                InsetGroup(header = m.title, dividerInset = 16.dp) {
                    m.sections.forEach { s ->
                        row {
                            Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                                Text(s.heading, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                                Spacer(Modifier.height(4.dp))
                                Text(s.body, fontSize = 15.sp, lineHeight = 20.sp)
                            }
                        }
                    }
                }
            }
            if (content.weights.isNotEmpty()) {
                InsetGroup(header = stringResource(R.string.insights_info_weights), dividerInset = 16.dp) {
                    content.weights.forEach { (label, value) -> row { KeyValueRow(label, value) } }
                }
            }
            if (content.inputs.isNotEmpty()) {
                InsetGroup(header = stringResource(R.string.insights_info_inputs), dividerInset = 16.dp, modifier = Modifier.testTag("insights.methodology.inputs")) {
                    content.inputs.forEach { i -> row { KeyValueRow(i.label, i.value, dimmed = i.missing) } }
                }
            }
            if (content.sources.isNotEmpty()) {
                InsetGroup(header = stringResource(R.string.insights_info_source), dividerInset = 16.dp) {
                    content.sources.filter { it.isNotBlank() }.forEach { s ->
                        row { Text(s, fontSize = 13.sp, lineHeight = 18.sp, color = AyuvoColors.secondaryLabel(), modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) }
                    }
                }
            }
            DisclaimerFooter(content.disclaimers)
        }
    }
}

@Composable
internal fun KeyValueRow(label: String, value: String, dimmed: Boolean = false) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 15.sp, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(12.dp))
        Text(value, fontSize = 15.sp, color = if (dimmed) AyuvoColors.tertiaryLabel() else AyuvoColors.secondaryLabel(), textAlign = TextAlign.End, modifier = Modifier.weight(1f))
    }
}

/** A metric row whose trailing value reads "today" on top and "Baseline …" (or a learning count) below. */
@Composable
internal fun ValueWithBaselineRow(label: String, value: String, secondary: String?, dimmed: Boolean = false) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 15.sp, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(12.dp))
        Column(horizontalAlignment = Alignment.End) {
            Text(value, fontSize = 15.sp, color = if (dimmed) AyuvoColors.secondaryLabel() else MaterialTheme.colorScheme.onSurface, textAlign = TextAlign.End)
            if (secondary != null) Text(secondary, fontSize = 13.sp, color = AyuvoColors.secondaryLabel(), textAlign = TextAlign.End)
        }
    }
}
