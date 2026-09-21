package com.ayuvo.health.ui.design

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import com.ayuvo.health.R
import com.ayuvo.health.ui.theme.AppColors

/**
 * Tab-root title bar ("Summary", "Browse"): large bold title with an optional subtitle,
 * collapsing to a small title when [scrollBehavior] is attached. Insets default to none
 * because tab roots already sit inside TabInset (status bar handled there).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AyuvoLargeTopBar(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    scrollBehavior: TopAppBarScrollBehavior? = null,
    windowInsets: WindowInsets = WindowInsets(0, 0, 0, 0),
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {}
) {
    val background = MaterialTheme.colorScheme.background
    // M3's defaults (64 dp action row + 152 dp expanded) leave a tall empty band above the title.
    // Apple places the large title right under a slim action row, so both heights are trimmed and
    // the title is drawn into M3's fixed 28 dp title bottom padding.
    LargeTopAppBar(
        title = {
            Column(Modifier.offset(y = LargeTitleDrop)) {
                Text(
                    title,
                    modifier = Modifier.semantics { heading() },
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (subtitle != null) {
                    Text(
                        subtitle,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Normal,
                        color = AyuvoColors.secondaryLabel(),
                        maxLines = 1
                    )
                }
            }
        },
        modifier = modifier,
        navigationIcon = navigationIcon,
        actions = actions,
        windowInsets = windowInsets,
        collapsedHeight = LargeBarCollapsedHeight,
        expandedHeight = LargeBarCollapsedHeight + if (subtitle != null) 72.dp else 52.dp,
        scrollBehavior = scrollBehavior,
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = background,
            scrolledContainerColor = background,
            titleContentColor = MaterialTheme.colorScheme.onBackground,
            actionIconContentColor = AppColors.Calorie,
            navigationIconContentColor = AppColors.Calorie
        )
    )
}

/** Pushed-screen title bar: back chevron in the accent colour and an inline title. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AyuvoTopBar(
    title: String,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
    windowInsets: WindowInsets = WindowInsets(0, 0, 0, 0),
    actions: @Composable RowScope.() -> Unit = {}
) {
    val background = MaterialTheme.colorScheme.background
    CenterAlignedTopAppBar(
        title = {
            Text(
                title,
                modifier = Modifier.semantics { heading() },
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        modifier = modifier,
        navigationIcon = {
            if (onBack != null) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.design_back))
                }
            }
        },
        actions = actions,
        windowInsets = windowInsets,
        colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
            containerColor = background,
            scrolledContainerColor = background,
            titleContentColor = MaterialTheme.colorScheme.onBackground,
            actionIconContentColor = AppColors.Calorie,
            navigationIconContentColor = AppColors.Calorie
        )
    )
}

private val LargeBarCollapsedHeight = 48.dp
private val LargeTitleDrop = 12.dp
