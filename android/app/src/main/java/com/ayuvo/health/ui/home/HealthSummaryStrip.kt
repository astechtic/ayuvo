package com.ayuvo.health.ui.home

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ayuvo.health.R
import com.ayuvo.health.ui.components.GlassSurface
import com.ayuvo.health.ui.components.IconBubble
import com.ayuvo.health.ui.health.HealthCategoryStyle
import com.ayuvo.health.ui.health.HealthTileUi
import com.ayuvo.health.ui.health.HealthValueFormatter
import com.ayuvo.health.ui.health.relativeTimeText
import com.ayuvo.health.ui.navigation.LocalLaunchFillEpoch
import com.ayuvo.health.ui.theme.AppColors
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Home's Health strip state, computed by HomeViewModel off the main thread. */
sealed class HealthStripState {
    /** Health Connect unavailable on this device: nothing to show. */
    object Hidden : HealthStripState()
    /** Hub off but Health Connect available: a single Connect tile keeps the entry point. */
    object Connect : HealthStripState()
    data class Tiles(
        val tiles: List<HealthTileUi>,
        val importing: Boolean,
        val permissionsReset: Boolean
    ) : HealthStripState() {
        val nothingYet: Boolean get() = tiles.none { it.hasData }
    }
}

/** Header "Health · See All" + horizontal 148×112 dp tiles; sits between the hero and the diary. */
@Composable
fun HealthSummaryStrip(
    state: HealthStripState,
    onOpenHealth: () -> Unit,
    onOpenType: (String) -> Unit
) {
    when (state) {
        HealthStripState.Hidden -> Unit
        HealthStripState.Connect -> ConnectTile(onOpenHealth)
        is HealthStripState.Tiles -> Column(Modifier.fillMaxWidth()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(stringResource(R.string.health_home_section), fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                Row(
                    Modifier.clickable(onClick = onOpenHealth).padding(vertical = 6.dp, horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(stringResource(R.string.health_home_see_all), color = AppColors.Calorie, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                    Icon(Icons.Filled.ChevronRight, null, tint = AppColors.Calorie, modifier = Modifier.size(18.dp))
                }
            }
            Spacer(Modifier.height(6.dp))
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(state.tiles, key = { it.typeId }) { tile ->
                    HealthTile(tile = tile, state = state, onClick = { onOpenType(tile.typeId) })
                }
                item(key = "all") { AllDataTile(onOpenHealth) }
            }
        }
    }
}

@Composable
private fun ConnectTile(onClick: () -> Unit) {
    GlassSurface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).clickable(onClick = onClick),
        cornerRadius = 20.dp,
        padding = 14.dp
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBubble(icon = Icons.Outlined.Favorite, size = 34.dp, iconSize = 20.dp, tint = AppColors.Calorie)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.health_home_connect_title), fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    stringResource(R.string.health_home_connect_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }
            Icon(Icons.Filled.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f))
        }
    }
}

@Composable
private fun AllDataTile(onClick: () -> Unit) {
    GlassSurface(
        modifier = Modifier.width(148.dp).height(112.dp).clickable(onClick = onClick),
        cornerRadius = 20.dp,
        padding = 14.dp
    ) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
            IconBubble(icon = Icons.Outlined.GridView, size = 26.dp, iconSize = 18.dp, tint = AppColors.Calorie)
            Text(stringResource(R.string.health_home_all_data), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 2)
        }
    }
}

@Composable
private fun HealthTile(tile: HealthTileUi, state: HealthStripState.Tiles, onClick: () -> Unit) {
    val context = LocalContext.current
    val name = HealthCategoryStyle.typeName(context, tile.typeId)
    val tint = HealthCategoryStyle.tintFor(tile.typeId)
    val caption = when {
        state.permissionsReset && !tile.hasData -> stringResource(R.string.health_home_grant_access)
        state.importing && !tile.hasData -> stringResource(R.string.health_home_importing)
        !tile.hasData -> stringResource(R.string.health_home_nothing_yet)
        tile.captionKind == HealthTileUi.CaptionKind.TODAY -> stringResource(R.string.health_home_today)
        tile.captionKind == HealthTileUi.CaptionKind.LAST_NIGHT -> stringResource(R.string.health_home_last_night)
        tile.captionKind == HealthTileUi.CaptionKind.RELATIVE -> relativeTimeText(tile.captionMs)
        tile.captionKind == HealthTileUi.CaptionKind.DATE && tile.captionMs != null -> remember(tile.captionMs) {
            DateTimeFormatter.ofPattern("MMM d", Locale.getDefault()).format(Instant.ofEpochMilli(tile.captionMs).atZone(ZoneId.systemDefault()))
        }
        else -> ""
    }
    val displayNumber = animatedNumber(tile)
    GlassSurface(
        modifier = Modifier
            .width(148.dp)
            .height(112.dp)
            .clickable(onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = "$name: $displayNumber ${tile.unit}, $caption" },
        cornerRadius = 20.dp,
        padding = 12.dp
    ) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).padding(0.dp)) {
                    Canvas(Modifier.fillMaxSize()) { drawCircle(tint) }
                }
                Spacer(Modifier.width(6.dp))
                Text(name, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f))
            }
            Row(verticalAlignment = Alignment.Bottom) {
                Text(displayNumber, fontSize = 22.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (tile.unit.isNotEmpty()) {
                    Spacer(Modifier.width(3.dp))
                    Text(tile.unit, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f), modifier = Modifier.padding(bottom = 3.dp))
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(caption, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                if (tile.spark.any { it > 0f }) Sparkline(tile.spark, tint)
            }
        }
    }
}

/** Count-up on app open (LocalLaunchFillEpoch), snap on tab returns — same rule as CalorieHero. */
@Composable
private fun animatedNumber(tile: HealthTileUi): String {
    val numeric = tile.numeric ?: return tile.number
    val epoch = LocalLaunchFillEpoch.current
    var lastEpoch by rememberSaveable(tile.typeId) { mutableIntStateOf(0) }
    val animatable = remember(tile.typeId) { Animatable(if (lastEpoch == epoch) numeric.toFloat() else 0f) }
    LaunchedEffect(epoch, numeric) {
        val spec = spring<Float>(dampingRatio = 0.85f, stiffness = 55f)
        if (lastEpoch != epoch) {
            animatable.snapTo(0f)
            animatable.animateTo(numeric.toFloat(), spec)
            lastEpoch = epoch
        } else {
            animatable.animateTo(numeric.toFloat(), spec)
        }
    }
    val current = animatable.value.toDouble()
    // Re-format the animated value with the same rules as the final number.
    return HealthValueFormatter.format(tile.typeId, current).number.ifEmpty { tile.number }
}

@Composable
private fun Sparkline(values: List<Float>, tint: androidx.compose.ui.graphics.Color) {
    val max = values.maxOrNull()?.takeIf { it > 0f } ?: return
    Canvas(Modifier.width(40.dp).height(14.dp)) {
        val stepX = size.width / (values.size - 1).coerceAtLeast(1)
        val path = Path()
        values.forEachIndexed { i, v ->
            val x = i * stepX
            val y = size.height - (v / max) * size.height
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, tint.copy(alpha = 0.8f), style = Stroke(width = 2f))
        drawCircle(tint, radius = 2.5f, center = Offset(size.width, size.height - (values.last() / max) * size.height))
    }
}
