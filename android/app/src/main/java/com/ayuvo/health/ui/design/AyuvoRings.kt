package com.ayuvo.health.ui.design

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ayuvo.health.ui.charts.IosStyleSegmentedControl
import com.ayuvo.health.ui.components.ActivityRing

enum class RingId { EAT, MOVE, DRINK }

/** CONNECT = no data source (e.g. Health Connect off); the ring shows only its track. */
enum class RingState { NORMAL, CONNECT }

data class RingSpec(
    val id: RingId,
    val progress: Float,
    val color: Color,
    val state: RingState = RingState.NORMAL,
    val contentDescription: String = ""
)

/**
 * Apple-Fitness-style concentric rings, outermost first. Each ring is tappable;
 * [animationKey] restarts the fill (pass the app-open epoch so tab switches don't replay it).
 */
@Composable
fun RingTrio(
    rings: List<RingSpec>,
    modifier: Modifier = Modifier,
    size: Dp = 156.dp,
    stroke: Dp = 16.dp,
    gap: Dp = 3.dp,
    animationKey: Any = Unit,
    onRingClick: (RingId) -> Unit = {}
) {
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        rings.forEachIndexed { index, ring ->
            val ringSize = size - (stroke + gap) * 2 * index
            if (ringSize <= stroke * 2) return@forEachIndexed
            val progress = if (ring.state == RingState.CONNECT) 0f else ring.progress.coerceAtLeast(0f)
            ActivityRing(
                progress = progress,
                size = ringSize,
                strokeWidth = stroke,
                gradientColors = listOf(ring.color),
                animationKey = animationKey,
                showEndDot = false,
                trackColor = ring.color.copy(alpha = 0.18f),
                modifier = Modifier
                    .clip(CircleShape)
                    .clickable(role = Role.Button) { onRingClick(ring.id) }
                    .semantics { contentDescription = ring.contentDescription }
            )
        }
    }
}

/** Segmented range control (D · W · M · 6M · Y) in the flat Apple style. */
@Composable
fun <T> RangeSegmentedControl(
    options: List<T>,
    selected: T,
    label: @Composable (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier
) {
    IosStyleSegmentedControl(
        options = options,
        selected = selected,
        label = label,
        onSelect = onSelect,
        modifier = modifier
    )
}
