package com.ayuvo.health.models

import androidx.annotation.StringRes
import com.ayuvo.health.R

/** [title] is the English name; show [labelRes]. */
enum class QuickAction(val title: String, @StringRes val labelRes: Int) {
    CAMERA("Camera + Note", R.string.core_quick_action_camera),
    PHOTOS("Photos", R.string.core_quick_action_photos),
    VOICE("Voice", R.string.core_quick_action_voice),
    TEXT("Text", R.string.core_quick_action_text),
    BARCODE("Barcode", R.string.core_quick_action_barcode),
    FAVORITES("Favorites", R.string.core_quick_action_favorites),
    FREQUENT("Frequent", R.string.core_quick_action_frequent),
    RECENT("Recent", R.string.core_quick_action_recent),
    MANUAL("Manual", R.string.core_quick_action_manual),
    FASTING("Fasting", R.string.core_quick_action_fasting);

    companion object {
        val Defaults = listOf(CAMERA, VOICE, BARCODE)

        fun fromStorage(value: String?, fallback: QuickAction = CAMERA): QuickAction =
            entries.firstOrNull { it.name == value } ?: fallback
    }
}

data class QuickActionRequest(
    val action: QuickAction,
    val id: Long = System.nanoTime()
)
