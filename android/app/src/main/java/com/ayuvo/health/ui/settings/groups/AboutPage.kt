package com.ayuvo.health.ui.settings.groups

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import com.ayuvo.health.ui.about.AboutAppHeader
import com.ayuvo.health.ui.about.AboutSettingsCategory
import com.ayuvo.health.ui.about.AboutSettingsRows
import com.ayuvo.health.ui.design.SurfaceCard
import com.ayuvo.health.ui.navigation.AppRoutes
import com.ayuvo.health.ui.settings.SettingsPage
import com.ayuvo.health.ui.settings.SettingsPageContext

/** About › App & Updates, Help & Support, Legal (contents unchanged, see `AboutSettingsRows`). */
@Composable
internal fun AboutPage(ctx: SettingsPageContext, page: SettingsPage) {
    val category = page.aboutCategory ?: return
    if (category == AboutSettingsCategory.APP_UPDATES) {
        SurfaceCard(padding = PaddingValues(0.dp)) { AboutAppHeader() }
    }
    SurfaceCard(padding = PaddingValues(0.dp)) {
        AboutSettingsRows(
            category = category,
            onOpenLicence = { ctx.actions.navigate(AppRoutes.LICENSES) }
        )
    }
}
