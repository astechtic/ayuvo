package com.ayuvo.health.services

import android.content.Context
import android.content.Intent
import com.ayuvo.health.R
import com.ayuvo.health.models.FoodEntry
import kotlin.math.roundToInt

/**
 * Shares a meal as a plain-text summary (name, calories and macros per entry) through the
 * system share sheet. Nothing is uploaded anywhere: the text goes straight to the app the
 * user picks, so sharing never contacts a server.
 */
object MealShareText {
    /** Human-readable summary of every entry — the text put on the share sheet. */
    fun text(entries: List<FoodEntry>): String = entries.joinToString("\n") { e ->
        val macros = "${e.protein.roundToInt()}P · ${e.carbs.roundToInt()}C · ${e.fat.roundToInt()}F"
        val prefix = e.emoji?.let { "$it " } ?: ""
        "$prefix${e.name} — ${e.calories} kcal · $macros"
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
