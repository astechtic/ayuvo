package com.ayuvo.health.ui.about

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.statusBars
import com.ayuvo.health.AppLinks
import androidx.compose.foundation.layout.WindowInsets
import com.ayuvo.health.ui.design.SurfaceCard
import com.ayuvo.health.ui.design.AyuvoTopBar
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ayuvo.health.R
import com.ayuvo.health.ui.navigation.BottomNavScrollPadding
import com.ayuvo.health.ui.theme.AppColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Settings → Legal → Open-source licence. Ayuvo is built on an MIT-licensed project; the MIT
 * licence requires the original copyright + permission notice to ship with every copy, so the
 * verbatim notice is bundled as an asset and shown here (offline, selectable).
 */
@Composable
fun LicensesScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var chunks by remember(context) { mutableStateOf<List<String>?>(null) }
    LaunchedEffect(context) {
        chunks = withContext(Dispatchers.IO) {
            readNoticeChunks(context, BASE_PROJECT_LICENSE_ASSET, R.string.about_licence_load_error)
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            AyuvoTopBar(
                title = stringResource(R.string.about_open_source_licence),
                onBack = onBack,
                windowInsets = WindowInsets.statusBars
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            contentPadding = PaddingValues(top = 14.dp, bottom = BottomNavScrollPadding),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            item {
                SurfaceCard(
                    modifier = Modifier.fillMaxWidth(),
                    padding = PaddingValues(16.dp),
                    onClick = { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(AppLinks.GITHUB_URL))) } }
                ) {
                    Text(
                        stringResource(R.string.about_source_github),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        stringResource(R.string.about_open_source_blurb),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.62f)
                    )
                }
            }
            item {
                Text(
                    stringResource(R.string.about_licenses_intro),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.62f)
                )
            }
            val visible = chunks
            if (visible != null) {
                item {
                    SurfaceCard(modifier = Modifier.fillMaxWidth(), padding = PaddingValues(16.dp)) {
                        LicenseText(visible)
                    }
                }
            }
        }
    }
}

@Composable
private fun LicenseText(chunks: List<String>) {
    SelectionContainer {
        Text(
            text = chunks.joinToString(""),
            modifier = Modifier.fillMaxWidth(),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
        )
    }
}

internal const val BASE_PROJECT_LICENSE_ASSET = "legal/LICENSE_BASE_PROJECT.txt"
internal const val THIRD_PARTY_NOTICES_ASSET = "legal/THIRD_PARTY_NOTICES.txt"
