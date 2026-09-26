package com.ayuvo.health.ui.about

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.annotation.StringRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Feedback
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.SupportAgent
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ayuvo.health.AppLinks
import com.ayuvo.health.R
import com.ayuvo.health.services.update.AndroidUpdateChecker
import com.ayuvo.health.services.update.AndroidUpdateState
import com.ayuvo.health.ui.components.GlassDialog
import com.ayuvo.health.ui.components.GlassDialogActions
import com.ayuvo.health.ui.design.AyuvoColors
import com.ayuvo.health.ui.design.AyuvoPalette
import com.ayuvo.health.ui.design.AyuvoSpacing
import com.ayuvo.health.ui.design.CategoryIcon
import com.ayuvo.health.ui.theme.AppColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The three "app information" destinations at the bottom of Settings. Every row here is local:
 * the update row talks to Google Play, the support rows open the user's mail app, and the legal
 * rows open the website or a bundled notice — no first-party server is involved.
 */
enum class AboutSettingsCategory(val titleRes: Int, val icon: ImageVector) {
    APP_UPDATES(R.string.about_category_app_updates, Icons.Filled.SystemUpdate),
    HELP_SUPPORT(R.string.about_category_help_support, Icons.Filled.SupportAgent),
    LEGAL(R.string.about_category_legal, Icons.Filled.Lock)
}

@Composable
fun AboutAppHeader() {
    val context = LocalContext.current
    val currentVersion = remember(context) { AndroidUpdateChecker.currentVersion(context) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Image(
            painter = painterResource(R.drawable.ic_logo),
            contentDescription = null,
            modifier = Modifier.size(64.dp)
        )
        Text(
            text = stringResource(R.string.app_name),
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = stringResource(R.string.about_version_format, currentVersion),
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
        )
    }
}

/** Rows for one focused About category, embedded in the existing Settings layout. */
@Composable
fun AboutSettingsRows(
    category: AboutSettingsCategory,
    onOpenLicence: () -> Unit
) {
    val ctx = LocalContext.current
    val shareText = stringResource(R.string.about_share_message, AppLinks.SITE_URL)
    val shareChooser = stringResource(R.string.about_share_chooser)
    val currentVersion = remember(ctx) { AndroidUpdateChecker.currentVersion(ctx) }
    var updateState by remember { mutableStateOf<AndroidUpdateState>(AndroidUpdateState.Idle) }
    var showLiteRtNotices by remember { mutableStateOf(false) }
    var showWhisperNotices by remember { mutableStateOf(false) }
    var showThirdPartyNotices by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun open(url: String) {
        runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
    }

    fun openPlayStore() = openPlayStore(ctx)

    fun refreshUpdateState() {
        scope.launch {
            updateState = AndroidUpdateState.Checking
            updateState = AndroidUpdateChecker.check(ctx, currentVersion)
        }
    }

    LaunchedEffect(category, currentVersion) {
        if (category == AboutSettingsCategory.APP_UPDATES) {
            updateState = AndroidUpdateState.Checking
            updateState = AndroidUpdateChecker.check(ctx, currentVersion)
        }
    }

    fun share() {
        runCatching {
            ctx.startActivity(
                Intent.createChooser(
                    Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, shareText)
                    },
                    shareChooser
                )
            )
        }
    }

    Column(Modifier.fillMaxWidth()) {
        when (category) {
            AboutSettingsCategory.APP_UPDATES -> {
                UpdateRow(
                    state = updateState,
                    currentVersion = currentVersion,
                    onRefresh = ::refreshUpdateState,
                    onOpenStore = ::openPlayStore
                )
                Hairline()
                AboutRow(Icons.Filled.Star, stringResource(R.string.about_rate), tint = AboutTint.Orange, onClick = ::openPlayStore)
                Hairline()
                AboutRow(Icons.Filled.Share, stringResource(R.string.about_share), tint = AboutTint.Blue, onClick = ::share)
            }

            AboutSettingsCategory.HELP_SUPPORT -> {
                AboutRow(Icons.Filled.Email, stringResource(R.string.about_contact), tint = AboutTint.Blue) {
                    SupportMail.compose(ctx, R.string.support_subject_contact)
                }
                Hairline()
                AboutRow(Icons.Filled.Feedback, stringResource(R.string.about_send_feedback), tint = AboutTint.Green) {
                    SupportMail.compose(ctx, R.string.support_subject_feedback)
                }
            }

            AboutSettingsCategory.LEGAL -> {
                AboutRow(Icons.Filled.Lock, stringResource(R.string.about_privacy), tint = AboutTint.Blue) {
                    open(AppLinks.PRIVACY_URL)
                }
                Hairline()
                AboutRow(Icons.Filled.Description, stringResource(R.string.about_terms), tint = AboutTint.Gray) {
                    open(AppLinks.TERMS_URL)
                }
                Hairline()
                AboutRow(Icons.Filled.Code, stringResource(R.string.about_source_github), tint = AboutTint.Gray) {
                    open(AppLinks.GITHUB_URL)
                }
                Hairline()
                AboutRow(Icons.Filled.Code, stringResource(R.string.about_open_source_licence), tint = AboutTint.Gray, onClick = onOpenLicence)
                Hairline()
                AboutRow(Icons.Filled.Description, stringResource(R.string.about_third_party_notices), tint = AboutTint.Gray) {
                    showThirdPartyNotices = true
                }
                Hairline()
                AboutRow(Icons.Filled.Description, stringResource(R.string.about_litert_notices), tint = AboutTint.Gray) {
                    showLiteRtNotices = true
                }
                Hairline()
                AboutRow(Icons.Filled.Description, stringResource(R.string.about_whisper_notices), tint = AboutTint.Gray) {
                    showWhisperNotices = true
                }
            }
        }
    }

    if (showThirdPartyNotices) {
        NoticeAssetDialog(
            titleRes = R.string.about_third_party_notices,
            loadErrorRes = R.string.about_licence_load_error,
            assetName = THIRD_PARTY_NOTICES_ASSET,
            onDismiss = { showThirdPartyNotices = false }
        )
    }
    if (showLiteRtNotices) {
        NoticeAssetDialog(
            titleRes = R.string.about_litert_notices,
            loadErrorRes = R.string.about_litert_notices_load_error,
            assetName = LITERT_NOTICE_ASSET,
            onDismiss = { showLiteRtNotices = false }
        )
    }
    if (showWhisperNotices) {
        NoticeAssetDialog(
            titleRes = R.string.about_whisper_notices,
            loadErrorRes = R.string.about_whisper_notices_load_error,
            assetName = WHISPER_NOTICE_ASSET,
            onDismiss = { showWhisperNotices = false }
        )
    }
}

/** Scrollable, selectable dialog over a bundled text asset (licence / notice files). */
@Composable
internal fun NoticeAssetDialog(
    @StringRes titleRes: Int,
    @StringRes loadErrorRes: Int,
    assetName: String,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var chunks by remember(context, assetName, loadErrorRes) {
        mutableStateOf<List<String>?>(null)
    }
    LaunchedEffect(context, assetName, loadErrorRes) {
        chunks = withContext(Dispatchers.IO) {
            readNoticeChunks(context, assetName, loadErrorRes)
        }
    }
    GlassDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.fillMaxHeight(0.9f)
    ) {
        Text(
            text = stringResource(titleRes),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )
        // The largest bundled notice is ~1.9 MB. Lazy 8 KiB chunks keep opening and scrolling
        // cheap, while SelectionContainer lets users select/copy every displayed section.
        val visibleChunks = chunks
        if (visibleChunks == null) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(color = AppColors.Calorie)
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                items(visibleChunks) { chunk ->
                    SelectionContainer {
                        Text(
                            text = chunk,
                            modifier = Modifier.fillMaxWidth(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
                        )
                    }
                }
            }
        }
        GlassDialogActions(
            primaryText = stringResource(R.string.action_done),
            onPrimary = onDismiss
        )
    }
}

internal fun readNoticeChunks(
    context: Context,
    assetName: String,
    @StringRes loadErrorRes: Int
): List<String> = runCatching {
    context.assets.open(assetName).bufferedReader().use { reader ->
        buildList {
            val chunk = StringBuilder(NOTICE_CHUNK_CHARACTERS)
            reader.forEachLine { line ->
                if (chunk.isNotEmpty() &&
                    chunk.length + line.length + 1 > NOTICE_CHUNK_CHARACTERS
                ) {
                    add(chunk.toString())
                    chunk.clear()
                }
                chunk.appendLine(line)
            }
            if (chunk.isNotEmpty()) add(chunk.toString())
        }
    }
}.getOrElse {
    listOf(context.getString(loadErrorRes))
}

private const val LITERT_NOTICE_ASSET = "THIRD_PARTY_NOTICES_LiteRTLM_v0.16.0.txt"
private const val WHISPER_NOTICE_ASSET = "THIRD_PARTY_NOTICES_WhisperBase.txt"
private const val NOTICE_CHUNK_CHARACTERS = 8 * 1024

private fun openPlayStore(context: Context) {
    val marketIntent = Intent(
        Intent.ACTION_VIEW,
        Uri.parse(AndroidUpdateChecker.PLAY_STORE_MARKET_URL)
    ).apply {
        setPackage("com.android.vending")
        addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
    }
    runCatching { context.startActivity(marketIntent) }.onFailure {
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(AndroidUpdateChecker.PLAY_STORE_WEB_URL))
            )
        }
    }
}

@Composable
private fun UpdateRow(
    state: AndroidUpdateState,
    currentVersion: String,
    onRefresh: () -> Unit,
    onOpenStore: () -> Unit
) {
    when (state) {
        AndroidUpdateState.Checking -> AboutRow(
            icon = Icons.Filled.Sync,
            tint = AboutTint.Gray,
            label = stringResource(R.string.about_update_checking),
            trailing = {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = AppColors.Calorie
                )
            },
            onClick = {}
        )
        is AndroidUpdateState.Available -> AboutRow(
            icon = Icons.Filled.SystemUpdate,
            tint = AboutTint.Blue,
            label = stringResource(R.string.about_update_available),
            subtitle = stringResource(R.string.about_update_details_format, state.current, state.latest),
            showDot = true,
            trailing = {
                Text(
                    stringResource(R.string.about_update_action),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = AppColors.Calorie
                )
            },
            onClick = onOpenStore
        )
        is AndroidUpdateState.Failed -> AboutRow(
            icon = Icons.Filled.Sync,
            tint = AboutTint.Gray,
            label = stringResource(R.string.about_check_updates),
            subtitle = stringResource(R.string.about_version_format, state.current),
            onClick = onRefresh
        )
        is AndroidUpdateState.UpToDate -> AboutRow(
            icon = Icons.Filled.CheckCircle,
            tint = AboutTint.Green,
            label = stringResource(R.string.about_app_version),
            trailing = {
                Text(
                    state.current,
                    fontSize = 15.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                )
            },
            onClick = onRefresh
        )
        AndroidUpdateState.Idle -> AboutRow(
            icon = Icons.Filled.Sync,
            tint = AboutTint.Gray,
            label = stringResource(R.string.about_check_updates),
            subtitle = stringResource(R.string.about_version_format, currentVersion),
            onClick = onRefresh
        )
    }
}

/** Fixed iOS-Settings icon colours for the About rows (same values as `SettingsTint`). */
private object AboutTint {
    val Gray = AyuvoPalette.Other
    val Blue = Color(0xFF007AFF)
    val Green = Color(0xFF34C759)
    val Orange = Color(0xFFFF9500)
}

@Composable
private fun AboutRow(
    icon: ImageVector,
    label: String,
    tint: Color,
    subtitle: String? = null,
    showDot: Boolean = false,
    trailing: (@Composable () -> Unit)? = null,
    onClick: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = AyuvoSpacing.RowMinHeight)
            .clickable(onClick = onClick)
            .padding(horizontal = AyuvoSpacing.RowH, vertical = AyuvoSpacing.RowV),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box {
            CategoryIcon(icon, tint)
            if (showDot) {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .offset(x = 3.dp, y = (-3).dp)
                        .size(9.dp)
                        .clip(CircleShape)
                        .background(AyuvoPalette.Destructive)
                )
            }
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(label, fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurface)
            if (!subtitle.isNullOrBlank()) {
                Text(
                    subtitle,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(12.dp))
            trailing()
        }
    }
}

@Composable
private fun Hairline() {
    Box(
        Modifier
            .padding(start = AyuvoSpacing.DividerInset)
            .fillMaxWidth()
            .height(0.5.dp)
            .background(AyuvoColors.separator())
    )
}
