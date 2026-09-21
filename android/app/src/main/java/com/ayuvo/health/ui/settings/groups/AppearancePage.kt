package com.ayuvo.health.ui.settings.groups

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Brightness6
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ayuvo.health.R
import com.ayuvo.health.ui.design.GroupRow
import com.ayuvo.health.ui.design.InsetGroup
import com.ayuvo.health.ui.settings.SettingsPage
import com.ayuvo.health.ui.settings.SettingsPageContext
import com.ayuvo.health.ui.settings.SettingsSheet
import com.ayuvo.health.ui.settings.ThemeColorSwatch
import com.ayuvo.health.ui.theme.AppColors
import com.ayuvo.health.ui.theme.AppThemeColor

/** Appearance: light/dark mode and the theme colour (used only for buttons and primary actions). */
@Composable
internal fun AppearancePage(ctx: SettingsPageContext) {
    val ui = ctx.ui
    val tint = SettingsPage.APPEARANCE.tint
    var themeMenuExpanded by remember { mutableStateOf(false) }
    InsetGroup(footer = stringResource(R.string.settings_theme_color_footer)) {
        row {
            GroupRow(
                title = stringResource(R.string.settings_appearance),
                value = when (ui.appearanceMode) {
                    "light" -> stringResource(R.string.settings_appearance_light)
                    "dark" -> stringResource(R.string.settings_appearance_dark)
                    else -> stringResource(R.string.settings_appearance_system)
                },
                icon = Icons.Filled.Brightness6, iconTint = tint,
                modifier = Modifier.settingsRow("appearanceMode"),
                onClick = { ctx.state.sheet = SettingsSheet.APPEARANCE }
            )
        }
        row {
            Box {
                GroupRow(
                    title = stringResource(R.string.settings_theme_color),
                    value = stringResource(ui.appThemeColor.displayNameRes),
                    icon = Icons.Filled.Palette, iconTint = tint,
                    modifier = Modifier.settingsRow("themeColor"),
                    onClick = { themeMenuExpanded = true }
                )
                // Zero-size anchor at the row's trailing edge so the menu drops under the value.
                Box(Modifier.align(Alignment.BottomEnd)) {
                    DropdownMenu(
                        expanded = themeMenuExpanded,
                        onDismissRequest = { themeMenuExpanded = false },
                        modifier = Modifier.heightIn(max = 420.dp)
                    ) {
                        AppThemeColor.entries.forEach { themeColor ->
                            DropdownMenuItem(
                                text = { Text(stringResource(themeColor.displayNameRes)) },
                                leadingIcon = { ThemeColorSwatch(themeColor, Modifier.size(22.dp)) },
                                trailingIcon = if (themeColor == ui.appThemeColor) {
                                    {
                                        Icon(
                                            Icons.Filled.Check,
                                            contentDescription = stringResource(R.string.sheet_selected_a11y),
                                            tint = AppColors.Calorie,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                } else null,
                                onClick = {
                                    ctx.vm.setAppThemeColor(themeColor)
                                    themeMenuExpanded = false
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}
