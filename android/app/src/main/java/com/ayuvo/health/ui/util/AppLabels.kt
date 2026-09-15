package com.ayuvo.health.ui.util

import android.content.Context

/** Launcher label for an installed package, or the package id when Android hides it. */
object AppLabels {
    fun labelFor(context: Context, packageName: String): String = labelOrNull(context, packageName) ?: packageName

    /** Null when the package is not installed or not visible to this app. */
    fun labelOrNull(context: Context, packageName: String): String? = runCatching {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
    }.getOrNull()?.takeIf { it.isNotBlank() }
}
