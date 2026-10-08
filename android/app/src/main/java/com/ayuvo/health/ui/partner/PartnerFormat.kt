package com.ayuvo.health.ui.partner

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.Medication
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ayuvo.health.R
import com.ayuvo.health.partner.data.Partner
import com.ayuvo.health.partner.data.PartnerSyncState
import com.ayuvo.health.partner.logic.PartnerKdf
import com.ayuvo.health.partner.logic.ReceivedGrant
import com.ayuvo.health.ui.design.AyuvoColors
import com.ayuvo.health.ui.design.AyuvoPalette
import com.ayuvo.health.ui.design.AyuvoShapes
import com.ayuvo.health.ui.health.relativeTimeText

/** The six shareable categories (record_types.json `categories`, docs/partner-sync.md §1), in catalog order. */
enum class PartnerCategory(
    val id: String,
    @param:StringRes val titleRes: Int,
    @param:StringRes val subtitleRes: Int,
    val icon: ImageVector,
    val color: Color
) {
    VITALS("vitals", R.string.partner_cat_vitals, R.string.partner_cat_vitals_sub, Icons.Filled.MonitorHeart, AyuvoPalette.Heart),
    SLEEP("sleep", R.string.partner_cat_sleep, R.string.partner_cat_sleep_sub, Icons.Filled.Bedtime, AyuvoPalette.Sleep),
    NUTRITION("nutrition", R.string.partner_cat_nutrition, R.string.partner_cat_nutrition_sub, Icons.Filled.Restaurant, AyuvoPalette.Nutrition),
    WORKOUTS("workouts", R.string.partner_cat_workouts, R.string.partner_cat_workouts_sub, Icons.Filled.FitnessCenter, AyuvoPalette.Activity),
    MEDICINES("medicines", R.string.partner_cat_medicines, R.string.partner_cat_medicines_sub, Icons.Filled.Medication, AyuvoPalette.Medications),
    REPORTS("report_overviews", R.string.partner_cat_reports, R.string.partner_cat_reports_sub, Icons.Filled.Description, AyuvoPalette.Records);

    companion object {
        fun of(id: String): PartnerCategory? = entries.firstOrNull { it.id == id }
    }
}

/** What a partner shares with me for one category (docs §9). */
enum class ReceivedShare { NEVER, SHARED, REVOKED }

fun receivedShare(grants: List<ReceivedGrant>, category: String): ReceivedShare {
    val g = grants.firstOrNull { it.category == category } ?: return ReceivedShare.NEVER
    return when {
        g.granted -> ReceivedShare.SHARED
        g.revokedMs != null -> ReceivedShare.REVOKED
        else -> ReceivedShare.NEVER
    }
}

object PartnerFormat {
    /** "5CBE 8B6C … 3931": the first two and the last group of the fingerprint. */
    fun shortFingerprint(hex: String): String {
        val groups = PartnerKdf.formatFingerprint(hex).split(' ')
        return if (groups.size <= 3) groups.joinToString(" ") else "${groups[0]} ${groups[1]} … ${groups.last()}"
    }

    fun fullFingerprint(hex: String): String = PartnerKdf.formatFingerprint(hex)

    /** A status shown after the freshness ("Partner offline"); null when nothing needs saying. */
    @StringRes
    fun statusNote(partner: Partner, sync: PartnerSyncState?): Int? {
        if (!partner.trusted) return R.string.partner_status_unpaired
        return when (sync?.status) {
            "connecting", "syncing" -> R.string.partner_status_syncing
            "partner_unavailable" -> R.string.partner_status_offline
            "waiting_for_network" -> R.string.partner_status_no_network
            "local_network_denied" -> R.string.partner_status_lan_denied
            "not_trusted" -> R.string.partner_status_not_trusted
            "sync_failed" -> R.string.partner_status_failed
            "pairing_required" -> R.string.partner_status_pairing_required
            else -> null
        }
    }

    /** The full status sentence for the dashboard's Sync Status section. */
    @StringRes
    fun statusLong(partner: Partner, sync: PartnerSyncState?): Int {
        if (!partner.trusted) return R.string.partner_status_long_unpaired
        return when (sync?.status) {
            "connecting", "syncing" -> R.string.partner_status_long_syncing
            "up_to_date" -> R.string.partner_status_long_up_to_date
            "partner_unavailable" -> R.string.partner_status_long_offline
            "waiting_for_network" -> R.string.partner_status_long_no_network
            "local_network_denied" -> R.string.partner_status_long_lan_denied
            "not_trusted" -> R.string.partner_status_long_not_trusted
            "sync_failed" -> R.string.partner_status_long_failed
            else -> R.string.partner_status_long_pairing_required
        }
    }

    /** Copy for a QR / pairing failure code (docs §4 qr_parse codes plus transport outcomes). */
    @StringRes
    fun pairingError(code: String): Int = when (code) {
        "not_ayuvo" -> R.string.partner_err_not_ayuvo
        "unsupported_version" -> R.string.partner_err_qr_version
        "malformed", "bad_key" -> R.string.partner_err_malformed
        "self" -> R.string.partner_err_self
        "expired" -> R.string.partner_err_expired
        "not_trusted" -> R.string.partner_err_handshake
        "partner_unavailable" -> R.string.partner_err_unreachable
        else -> R.string.partner_err_generic
    }

    /** Copy for a package_validate / import error code (docs §13). */
    @StringRes
    fun packageError(code: String?): Int = when (code) {
        "unknown_sender" -> R.string.partner_pkg_err_unknown_sender
        "bad_signature", "hash_mismatch", "missing_entry", "unexpected_entry", "unsafe_entry", "malformed" -> R.string.partner_pkg_err_changed
        "wrong_recipient" -> R.string.partner_pkg_err_wrong_recipient
        "unsupported_version" -> R.string.partner_pkg_err_version
        "not_package" -> R.string.partner_pkg_err_not_package
        else -> R.string.partner_pkg_err_generic
    }
}

/** "Updated 3 min ago" (or "Not synced yet"). */
@Composable
fun partnerFreshness(sync: PartnerSyncState?): String {
    val ms = sync?.lastSyncMs ?: return stringResource(R.string.partner_not_synced_yet)
    // One clock reading for both checks: the shared relativeTimeText starts with a capitalised "Just now" meant to
    // stand alone, which must never end up inside "Updated %1$s" (iOS PartnerDisplay: "Updated just now").
    val now = System.currentTimeMillis()
    if (now - ms < 60_000L) return stringResource(R.string.partner_updated_just_now)
    return stringResource(R.string.partner_updated_ago, relativeTimeText(ms, now))
}

/** Freshness plus a status note: "Updated yesterday · Partner offline". */
@Composable
fun partnerFreshnessLine(partner: Partner, sync: PartnerSyncState?): String {
    val fresh = partnerFreshness(sync)
    val note = PartnerFormat.statusNote(partner, sync)?.let { stringResource(it) }
    return if (note == null) fresh else stringResource(R.string.partner_join_dot, fresh, note)
}

/** Small pink capsule that marks partner data ("Partner", "No longer shared"). */
@Composable
fun PartnerBadge(text: String, modifier: Modifier = Modifier, color: Color = AyuvoPalette.Partner) {
    Text(
        text,
        modifier = modifier
            .clip(AyuvoShapes.Capsule)
            .background(color.copy(alpha = 0.14f))
            .padding(horizontal = 8.dp, vertical = 2.dp),
        color = color,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1
    )
}

/** The grey "No longer shared" badge for a revoked category. */
@Composable
fun NoLongerSharedBadge(modifier: Modifier = Modifier) {
    PartnerBadge(stringResource(R.string.partner_no_longer_shared), modifier, AyuvoColors.secondaryLabel())
}
