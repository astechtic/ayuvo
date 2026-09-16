package com.ayuvo.health.ui.progress

import androidx.annotation.StringRes
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ShowChart
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ayuvo.health.R
import com.ayuvo.health.ui.theme.AppColors

import androidx.compose.material.icons.filled.Restaurant

/**
 * The segments of the Health tab: Food management flow, the classic Progress charts,
 * the Health Data hub and Workouts.
 */
enum class HealthTabDestination(
    @StringRes val labelRes: Int,
    val icon: ImageVector
) {
    FOOD(R.string.health_tab_food, Icons.Filled.Restaurant),
    PROGRESS(R.string.health_tab_progress, Icons.AutoMirrored.Filled.ShowChart),
    HEALTH_DATA(R.string.health_tab_data, Icons.Filled.MonitorHeart),
    WORKOUTS(R.string.nav_workouts, Icons.Filled.FitnessCenter)
}

/**
 * Matches iOS `ProgressOverviewModeSelector`: capsule track with gradient
 * selected chip + icon label. Segments share the track equally and the gradient
 * pill slides between them.
 */
@Composable
internal fun HealthTabSelector(
    selected: HealthTabDestination,
    onSelect: (HealthTabDestination) -> Unit,
    modifier: Modifier = Modifier
) {
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val track = if (isDark) AppColors.AppCardDark else AppColors.AppCardLight
    val destinations = HealthTabDestination.entries
    val selectedIndex = destinations.indexOf(selected).coerceAtLeast(0)
    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(50))
            .background(track)
            .border(0.75.dp, AppColors.Calorie.copy(alpha = 0.12f), RoundedCornerShape(50))
            .padding(4.dp)
    ) {
        val segmentWidth = maxWidth / destinations.size
        val indicatorOffset by animateDpAsState(
            targetValue = segmentWidth * selectedIndex,
            animationSpec = spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessMediumLow),
            label = "health_tab_indicator"
        )
        Box {
            // Sized to the segment row below; the pill slides underneath the labels.
            Box(Modifier.matchParentSize()) {
                Box(
                    Modifier
                        .offset(x = indicatorOffset)
                        .width(segmentWidth)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(50))
                        .background(AppColors.CalorieGradient)
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .selectableGroup(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                destinations.forEach { destination ->
                    val isSelected = selected == destination
                    val contentColor by animateColorAsState(
                        targetValue = if (isSelected) Color.White
                        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                        label = "health_tab_content"
                    )
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 44.dp)
                            .clip(RoundedCornerShape(50))
                            .selectable(
                                selected = isSelected,
                                onClick = { onSelect(destination) },
                                role = Role.Tab
                            )
                            .padding(horizontal = 8.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp, Alignment.CenterHorizontally)
                    ) {
                        Icon(
                            imageVector = destination.icon,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = contentColor
                        )
                        Text(
                            text = stringResource(destination.labelRes),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = contentColor,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                    }
                }
            }
        }
    }
}
