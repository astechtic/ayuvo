package com.ayuvo.health.ui.design

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ayuvo.health.ui.theme.AppColors

/** Plain grouped-list card: surface colour, 16dp radius, no shadow or border. */
@Composable
fun SurfaceCard(
    modifier: Modifier = Modifier,
    padding: PaddingValues = PaddingValues(AyuvoSpacing.CardPadding),
    onClick: (() -> Unit)? = null,
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(AyuvoShapes.Card)
            .background(MaterialTheme.colorScheme.surface)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(padding),
        verticalArrangement = verticalArrangement,
        content = content
    )
}

/** Section title above a card or group, with an optional trailing text action ("Edit", "Show All"). */
@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    trailing: String? = null,
    onTrailing: (() -> Unit)? = null
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 4.dp, end = 4.dp, top = 8.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            title,
            modifier = Modifier.weight(1f).semantics { heading() },
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground
        )
        if (trailing != null) {
            Text(
                trailing,
                modifier = Modifier
                    .clip(AyuvoShapes.Tile)
                    .then(if (onTrailing != null) Modifier.clickable(role = Role.Button, onClick = onTrailing) else Modifier)
                    .padding(horizontal = 6.dp, vertical = 4.dp),
                fontSize = 15.sp,
                color = AppColors.Calorie
            )
        }
    }
}

/** Filled rounded-square icon (Apple Settings style) or tinted bubble. */
@Composable
fun CategoryIcon(
    icon: ImageVector,
    tint: Color,
    modifier: Modifier = Modifier,
    size: Dp = AyuvoSpacing.IconSize,
    filled: Boolean = true
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(if (filled) AyuvoShapes.Icon else androidx.compose.foundation.shape.RoundedCornerShape(size * 0.26f))
            .background(if (filled) tint else tint.copy(alpha = 0.14f)),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = if (filled) Color.White else tint,
            modifier = Modifier.size(size * 0.62f)
        )
    }
}

sealed interface RowTrailing {
    data object Chevron : RowTrailing
    data object None : RowTrailing
    data class Toggle(val checked: Boolean, val onChange: (Boolean) -> Unit, val enabled: Boolean = true) : RowTrailing
    data class Custom(val content: @Composable () -> Unit) : RowTrailing
}

/** Collects the rows of an [InsetGroup]; separators are drawn between them. */
class InsetGroupScope internal constructor() {
    internal val rows = mutableListOf<@Composable () -> Unit>()

    fun row(content: @Composable () -> Unit) {
        rows += content
    }
}

/** Inset grouped list section (header, rounded card of rows with inset separators, footer). */
@Composable
fun InsetGroup(
    modifier: Modifier = Modifier,
    header: String? = null,
    footer: String? = null,
    dividerInset: Dp = AyuvoSpacing.DividerInset,
    content: InsetGroupScope.() -> Unit
) {
    val scope = InsetGroupScope().apply(content)
    Column(modifier.fillMaxWidth()) {
        if (header != null) {
            Text(
                header.uppercase(),
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 6.dp),
                fontSize = 12.sp,
                color = AyuvoColors.secondaryLabel(),
                letterSpacing = 0.3.sp
            )
        }
        if (scope.rows.isNotEmpty()) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(AyuvoShapes.Card)
                    .background(MaterialTheme.colorScheme.surface)
            ) {
                scope.rows.forEachIndexed { index, row ->
                    row()
                    if (index < scope.rows.lastIndex) {
                        HorizontalDivider(
                            modifier = Modifier.padding(start = dividerInset),
                            thickness = 0.5.dp,
                            color = AyuvoColors.separator()
                        )
                    }
                }
            }
        }
        if (footer != null) {
            Text(
                footer,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 6.dp),
                fontSize = 12.sp,
                lineHeight = 16.sp,
                color = AyuvoColors.secondaryLabel()
            )
        }
    }
}

/** One settings-style row: optional coloured icon, title/subtitle, value and trailing control. */
@Composable
fun GroupRow(
    title: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    iconTint: Color = AyuvoPalette.Other,
    subtitle: String? = null,
    value: String? = null,
    trailing: RowTrailing = RowTrailing.Chevron,
    destructive: Boolean = false,
    enabled: Boolean = true,
    leading: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null
) {
    val toggle = trailing as? RowTrailing.Toggle
    val clickModifier = when {
        toggle != null -> Modifier.clickable(enabled = enabled && toggle.enabled, role = Role.Switch) { toggle.onChange(!toggle.checked) }
        onClick != null -> Modifier.clickable(enabled = enabled, role = Role.Button, onClick = onClick)
        else -> Modifier
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = AyuvoSpacing.RowMinHeight)
            .then(clickModifier)
            .padding(horizontal = AyuvoSpacing.RowH, vertical = AyuvoSpacing.RowV),
        verticalAlignment = Alignment.CenterVertically
    ) {
        when {
            leading != null -> {
                leading()
                Spacer(Modifier.width(14.dp))
            }
            icon != null -> {
                CategoryIcon(icon, if (destructive) AyuvoPalette.Destructive else iconTint)
                Spacer(Modifier.width(16.dp))
            }
        }
        val contentAlpha = if (enabled) 1f else 0.38f
        Column(Modifier.weight(1f)) {
            Text(
                title,
                fontSize = 16.sp,
                color = when {
                    destructive -> AyuvoPalette.Destructive
                    else -> MaterialTheme.colorScheme.onSurface
                }.copy(alpha = contentAlpha),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            if (subtitle != null) {
                Text(
                    subtitle,
                    fontSize = 13.sp,
                    lineHeight = 17.sp,
                    color = AyuvoColors.secondaryLabel().let { it.copy(alpha = it.alpha * contentAlpha) }
                )
            }
        }
        if (value != null) {
            Spacer(Modifier.width(8.dp))
            Text(
                value,
                fontSize = 16.sp,
                color = AyuvoColors.secondaryLabel(),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        when (trailing) {
            RowTrailing.Chevron -> if (onClick != null) {
                Spacer(Modifier.width(4.dp))
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = AyuvoColors.tertiaryLabel(),
                    modifier = Modifier.size(22.dp)
                )
            }
            RowTrailing.None -> Unit
            is RowTrailing.Toggle -> {
                Spacer(Modifier.width(8.dp))
                Switch(
                    checked = trailing.checked,
                    onCheckedChange = trailing.onChange,
                    enabled = enabled && trailing.enabled,
                    colors = SwitchDefaults.colors(
                        checkedTrackColor = AyuvoPalette.Success,
                        checkedThumbColor = Color.White,
                        checkedBorderColor = Color.Transparent,
                        uncheckedThumbColor = Color.White,
                        uncheckedTrackColor = AyuvoColors.fill(),
                        uncheckedBorderColor = Color.Transparent
                    )
                )
            }
            is RowTrailing.Custom -> {
                Spacer(Modifier.width(8.dp))
                trailing.content()
            }
        }
    }
}

/** Mini 7-point trend line; nulls/NaN break nothing (missing points are skipped). */
@Composable
fun Sparkline(
    values: List<Float>,
    color: Color,
    modifier: Modifier = Modifier.size(56.dp, 20.dp)
) {
    val finite = values.filter { it.isFinite() }
    if (finite.size < 2) {
        Box(modifier)
        return
    }
    Canvas(modifier) {
        val min = finite.min()
        val max = finite.max()
        val span = (max - min).takeIf { it > 0f } ?: 1f
        val stepX = if (values.size > 1) size.width / (values.size - 1) else size.width
        val path = Path()
        var started = false
        var last = Offset.Zero
        values.forEachIndexed { i, v ->
            if (!v.isFinite()) return@forEachIndexed
            val pt = Offset(i * stepX, size.height - ((v - min) / span) * size.height)
            if (!started) {
                path.moveTo(pt.x, pt.y); started = true
            } else {
                path.lineTo(pt.x, pt.y)
            }
            last = pt
        }
        drawPath(path, color, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawCircle(color, radius = 2.5.dp.toPx(), center = last)
    }
}

/** Favourite tile: category label, big value, caption and a 7-day sparkline. */
@Composable
fun MetricTile(
    title: String,
    icon: ImageVector,
    tint: Color,
    value: String,
    unit: String?,
    caption: String?,
    spark: List<Float>,
    hasData: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    SurfaceCard(
        modifier = modifier.heightIn(min = 116.dp),
        padding = PaddingValues(14.dp),
        onClick = onClick
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(
                title,
                modifier = Modifier.weight(1f),
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = tint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.weight(1f, fill = false).heightIn(min = 12.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        if (hasData) value else "—",
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    if (hasData && !unit.isNullOrBlank()) {
                        Spacer(Modifier.width(3.dp))
                        Text(
                            unit,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = AyuvoColors.secondaryLabel(),
                            modifier = Modifier.padding(bottom = 3.dp),
                            maxLines = 1
                        )
                    }
                }
                if (!caption.isNullOrBlank()) {
                    Text(caption, fontSize = 12.sp, color = AyuvoColors.secondaryLabel(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (hasData) Sparkline(spark, tint)
        }
    }
}

/** Browse / detail list row: title with caption on the left, latest value on the right. */
@Composable
fun MetricRow(
    title: String,
    value: String?,
    unit: String?,
    caption: String?,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    tint: Color? = null,
    dimmed: Boolean = false,
    onClick: () -> Unit
) {
    val alpha = if (dimmed) 0.45f else 1f
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = AyuvoSpacing.RowH, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null && tint != null) {
            CategoryIcon(icon, tint.copy(alpha = alpha), filled = false, size = 32.dp)
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha), maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!caption.isNullOrBlank()) {
                Text(caption, fontSize = 12.sp, color = AyuvoColors.secondaryLabel(), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (!value.isNullOrBlank()) {
            Spacer(Modifier.width(8.dp))
            Text(value, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha), maxLines = 1)
            if (!unit.isNullOrBlank()) {
                Spacer(Modifier.width(3.dp))
                Text(unit, fontSize = 13.sp, color = AyuvoColors.secondaryLabel(), maxLines = 1)
            }
        }
        Spacer(Modifier.width(4.dp))
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = AyuvoColors.tertiaryLabel(),
            modifier = Modifier.size(22.dp)
        )
    }
}

/** "AVERAGE / 1,980 kcal / 10–16 Sep" headline above a metric chart. */
@Composable
fun HeadlineStat(
    label: String,
    value: String,
    unit: String,
    rangeText: String,
    modifier: Modifier = Modifier
) {
    Column(modifier) {
        Text(
            label.uppercase(),
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = AyuvoColors.secondaryLabel(),
            letterSpacing = 0.3.sp
        )
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value, fontSize = 30.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
            if (unit.isNotBlank()) {
                Spacer(Modifier.width(4.dp))
                Text(
                    unit,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = AyuvoColors.secondaryLabel(),
                    modifier = Modifier.padding(bottom = 5.dp)
                )
            }
        }
        if (rangeText.isNotBlank()) {
            Text(rangeText, fontSize = 14.sp, color = AyuvoColors.secondaryLabel())
        }
    }
}

/** Centered empty state with an optional action. */
@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(icon, contentDescription = null, tint = AyuvoColors.tertiaryLabel(), modifier = Modifier.size(44.dp))
        Text(title, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
        Text(
            message,
            fontSize = 15.sp,
            color = AyuvoColors.secondaryLabel(),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
        if (actionLabel != null && onAction != null) {
            Text(
                actionLabel,
                modifier = Modifier
                    .padding(top = 4.dp)
                    .clip(AyuvoShapes.Tile)
                    .clickable(role = Role.Button, onClick = onAction)
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = AppColors.Calorie
            )
        }
    }
}
