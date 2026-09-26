package com.ayuvo.health.actions

import android.content.Context
import android.content.Intent
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.ayuvo.health.MainActivity
import com.ayuvo.health.R
import java.util.Locale

/**
 * Pushes recently used actions as dynamic launcher shortcuts (docs/actions.md, Android). Labels
 * are the catalog title only — never a logged value or a health reading — and the intent carries
 * no values: a write opens Ayuvo's own logger, a read carries only its enum/metric choices.
 */
object ActionShortcutPublisher {
    const val ID_PREFIX = "action_"

    fun report(context: Context, action: ValidatedAction) {
        if (action.source == ActionSource.COACH) return
        val info = shortcutFor(action) ?: return
        runCatching { ShortcutManagerCompat.pushDynamicShortcut(context, build(context, info)) }
    }

    /** What a shortcut for [action] would contain; null for actions that should not be offered. */
    fun shortcutFor(action: ValidatedAction): ShortcutSpec? {
        val spec = action.spec
        if (spec.domain == "medications" || spec.id == "open.record" || spec.id == "records.labValue.get") return null
        val extras = LinkedHashMap<String, String>()
        extras[ActionIntents.EXTRA_ACTION_ID] = spec.id
        if (spec.kind != ActionKind.SET) {
            for (p in spec.params) {
                if (p.type != ParamType.ENUM && p.type != ParamType.METRIC) continue
                action.params[p.name]?.let { extras[p.name] = it.toString() }
            }
        }
        val qualifier = extras.filterKeys { it != ActionIntents.EXTRA_ACTION_ID }.values
            .joinToString(" · ") { it.removePrefix("app:").replace('_', ' ') }
        val label = if (spec.id == "open.section") "Open ${qualifier.replaceFirstChar { it.titlecase(Locale.getDefault()) }}"
        else spec.title
        val id = ID_PREFIX + (listOf(spec.id) + extras.filterKeys { it != ActionIntents.EXTRA_ACTION_ID }.values).joinToString("_")
        return ShortcutSpec(id, label.take(25), if (qualifier.isEmpty()) spec.title else "${spec.title} ($qualifier)", extras,
            (action.params["section"] as? String)?.takeIf { spec.id == "open.section" })
    }

    data class ShortcutSpec(val id: String, val shortLabel: String, val longLabel: String, val extras: Map<String, String>, val feature: String?)

    private fun build(context: Context, s: ShortcutSpec): ShortcutInfoCompat {
        val intent = Intent(context, MainActivity::class.java).apply {
            action = ActionIntents.ACTION
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            s.extras.forEach { (k, v) -> putExtra(k, v) }
        }
        val builder = ShortcutInfoCompat.Builder(context, s.id)
            .setShortLabel(s.shortLabel)
            .setLongLabel(s.longLabel)
            .setIcon(IconCompat.createWithResource(context, R.mipmap.ic_launcher))
            .setIntent(intent)
        s.feature?.let { builder.addCapabilityBinding("actions.intent.OPEN_APP_FEATURE", "feature", listOf(it)) }
        return builder.build()
    }
}
