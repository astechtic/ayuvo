package com.ayuvo.health.ui.design

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PanTool
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ayuvo.health.AppLinks
import com.ayuvo.health.R

private fun openUrl(context: Context, url: String) {
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
}

/** Small capsule: lock + "Private by design" (or "Private · Open source"); taps open the privacy page. */
@Composable
fun AyuvoPrivacyPill(
    modifier: Modifier = Modifier,
    showsOpenSource: Boolean = false,
    opensPrivacyPage: Boolean = false
) {
    val context = LocalContext.current
    val tint = AyuvoPalette.Body
    Row(
        modifier = modifier
            .clip(AyuvoShapes.Capsule)
            .background(tint.copy(alpha = 0.14f))
            .then(
                if (opensPrivacyPage) {
                    Modifier.clickable(role = Role.Button) { openUrl(context, AppLinks.PRIVACY_FIRST_URL) }
                } else {
                    Modifier
                }
            )
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Filled.Lock, contentDescription = null, tint = tint, modifier = Modifier.size(13.dp))
        Spacer(Modifier.width(6.dp))
        Text(
            stringResource(if (showsOpenSource) R.string.privacy_pill_open_source else R.string.privacy_pill),
            color = tint,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

/** Settings card: what "private" means in Ayuvo, and where to read the code. */
@Composable
fun AyuvoPrivacyOpenSourceCard(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    SurfaceCard(modifier = modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(AyuvoShapes.Capsule)
                    .background(AyuvoPalette.Body),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Filled.Lock, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column {
                Text(
                    stringResource(R.string.privacy_card_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    stringResource(R.string.privacy_card_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = AyuvoColors.secondaryLabel()
                )
            }
        }
        Text(
            stringResource(R.string.privacy_card_body),
            style = MaterialTheme.typography.bodySmall,
            color = AyuvoColors.secondaryLabel()
        )
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            PrivacyLink(Icons.Filled.PanTool, stringResource(R.string.privacy_card_how)) {
                openUrl(context, AppLinks.PRIVACY_FIRST_URL)
            }
            PrivacyLink(Icons.Filled.Code, stringResource(R.string.privacy_card_source)) {
                openUrl(context, AppLinks.GITHUB_URL)
            }
        }
    }
}

@Composable
private fun PrivacyLink(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = AyuvoPalette.Body, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, color = AyuvoPalette.Body, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}
