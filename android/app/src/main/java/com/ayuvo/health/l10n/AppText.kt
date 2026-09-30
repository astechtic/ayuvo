package com.ayuvo.health.l10n

import android.content.Context
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes

/**
 * Localized text for code that has no Context at hand (pure parsers, exception messages the UI
 * shows via `message`). [AyuvoApp] installs the application context at start-up; JVM unit tests
 * install a resolver that reads `res/values` (see the test sources), otherwise a placeholder
 * naming the resource id is returned.
 */
object AppText {
    @Volatile
    var resolver: Resolver? = null

    interface Resolver {
        fun string(@StringRes res: Int, args: Array<out Any>): String
        fun plural(@PluralsRes res: Int, count: Int, args: Array<out Any>): String
    }

    fun install(context: Context) {
        val app = context.applicationContext
        resolver = object : Resolver {
            override fun string(res: Int, args: Array<out Any>): String =
                if (args.isEmpty()) app.getString(res) else app.getString(res, *args)

            override fun plural(res: Int, count: Int, args: Array<out Any>): String =
                app.resources.getQuantityString(res, count, *args)
        }
    }

    fun get(@StringRes res: Int, vararg args: Any): String =
        resolver?.string(res, args) ?: "#$res"

    fun plural(@PluralsRes res: Int, count: Int, vararg args: Any): String =
        resolver?.plural(res, count, args) ?: "#$res"

    /**
     * Text pinned by a shared contract (reference vectors): [english] when no resolver is installed
     * (the JVM vector tests), the localized [res] in the app.
     */
    fun orEnglish(english: String, @StringRes res: Int, vararg args: Any): String =
        resolver?.string(res, args) ?: english
}
