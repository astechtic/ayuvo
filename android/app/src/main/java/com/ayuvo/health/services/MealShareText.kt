package com.ayuvo.health.services

import android.content.Context
import android.content.Intent
import com.ayuvo.health.R
import com.ayuvo.health.models.FoodEntry
import kotlin.math.roundToInt
import com.ayuvo.health.l10n.AppText

/**
 * Shares a meal as a plain-text summary (name, calories and macros per entry) through the
 * system share sheet. Nothing is uploaded anywhere: the text goes straight to the app the
 * user picks, so sharing never contacts a server.
 */
object MealShareText {
    /** Human-readable summary of every entry — the text put on the share sheet. */
    fun text(entries: List<FoodEntry>): String = entries.joinToString("\n") { e ->
        val prefix = e.emoji?.let { "$it " } ?: ""
        val p = e.protein.roundToInt()
        val c = e.carbs.roundToInt()
        val f = e.fat.roundToInt()
        AppText.orEnglish("$prefix${e.name} — ${e.calories} kcal · ${p}P · ${c}C · ${f}F", R.string.core_meal_share_line, prefix, e.name, e.calories, p, c, f)
    }

    /** Fire the system share sheet with the summary text. */
    fun share(context: Context, entries: List<FoodEntry>) {
        if (entries.isEmpty()) return
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text(entries))
        }
        runCatching {
            context.startActivity(Intent.createChooser(send, context.getString(R.string.share_meal)))
        }
    }
}
