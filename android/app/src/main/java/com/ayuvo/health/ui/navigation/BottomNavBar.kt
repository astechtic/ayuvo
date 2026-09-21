package com.ayuvo.health.ui.navigation

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ayuvo.health.R
import com.ayuvo.health.ui.design.AyuvoColors
import com.ayuvo.health.ui.theme.AppColors

data class BottomTab(val route: String, val icon: ImageVector, @get:StringRes val labelRes: Int)

val BottomTabs = listOf(
    BottomTab(AppRoutes.SUMMARY, Icons.Filled.Favorite, R.string.nav_summary),
    BottomTab(AppRoutes.BROWSE, Icons.Filled.GridView, R.string.nav_browse),
    BottomTab(AppRoutes.RECORDS, Icons.Filled.Description, R.string.nav_records),
    BottomTab(AppRoutes.COACH, Icons.Filled.Forum, R.string.nav_coach),
    BottomTab(AppRoutes.SETTINGS, Icons.Filled.Settings, R.string.nav_settings)
)

/**
 * Content now sits above the docked bar (AppNavHost applies the Scaffold padding),
 * so these constants are just breathing room below the last item / docked control.
 */
val BottomNavScrollPadding = 24.dp
val BottomNavDockedControlPadding = 0.dp

/** Gap between a floating action button and the tab bar. */
val BottomNavFabPadding = 16.dp

/**
 * Flat Material 3 tab bar (Apple-style): plain surface, hairline top separator,
 * accent tint for the selected tab only.
 */
@Composable
fun AppBottomNavBar(
    currentRoute: String?,
    showAboutBadge: Boolean = false,
    onTap: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val accent = AppColors.Calorie
    val unselected = AyuvoColors.secondaryLabel()
    Column(modifier.fillMaxWidth()) {
        HorizontalDivider(thickness = 0.5.dp, color = AyuvoColors.separator())
        NavigationBar(
            containerColor = MaterialTheme.colorScheme.surface,
            tonalElevation = 0.dp
        ) {
            for (tab in BottomTabs) {
                val selected = tab.route == currentRoute
                val label = stringResource(tab.labelRes)
                NavigationBarItem(
                    modifier = Modifier.testTag("tab.${tab.route}"),
                    selected = selected,
                    onClick = { onTap(tab.route) },
                    icon = {
                        BadgedBox(
                            badge = {
                                if (showAboutBadge && tab.route == AppRoutes.SETTINGS) {
                                    Badge(containerColor = accent)
                                }
                            }
                        ) {
                            Icon(tab.icon, contentDescription = label)
                        }
                    },
                    label = {
                        Text(
                            label,
                            fontSize = 11.sp,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                            maxLines = 1
                        )
                    },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = accent,
                        selectedTextColor = accent,
                        indicatorColor = Color.Transparent,
                        unselectedIconColor = unselected,
                        unselectedTextColor = unselected
                    )
                )
            }
        }
    }
}
