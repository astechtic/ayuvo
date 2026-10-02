package com.ayuvo.health.ui.cycle

import android.content.Context
import com.ayuvo.health.cycle.engine.CycleConfig
import com.ayuvo.health.cycle.engine.CycleInsight
import com.ayuvo.health.l10n.ContractStrings

/**
 * Display text from the cycle contract (`shared/cycle/cycle_config.json`), translated through the generated
 * `cycle.*` contract keys with the JSON English as fallback (docs/localization.md).
 */
object CycleText {
    private fun item(context: Context, catalog: String, list: List<CycleConfig.Item>, key: String): String =
        ContractStrings.text(context, "cycle.$catalog.$key.title", list.firstOrNull { it.key == key }?.title ?: key)

    fun flow(context: Context, cfg: CycleConfig, key: String): String =
        ContractStrings.text(context, "cycle.flow_levels.$key.title", cfg.flowLevels.firstOrNull { it.key == key }?.title ?: key)

    fun symptom(context: Context, cfg: CycleConfig, key: String) = item(context, "symptoms", cfg.symptoms, key)
    fun symptomGroup(context: Context, cfg: CycleConfig, key: String) = item(context, "symptom_groups", cfg.symptomGroups, key)
    fun mood(context: Context, cfg: CycleConfig, key: String) = item(context, "moods", cfg.moods, key)
    fun painLocation(context: Context, cfg: CycleConfig, key: String) = item(context, "pain_locations", cfg.painLocations, key)
    fun phase(context: Context, cfg: CycleConfig, key: String) = item(context, "phases", cfg.phases, key)
    fun basis(context: Context, cfg: CycleConfig, key: String) = item(context, "basis", cfg.basis, key)

    fun basisAbout(context: Context, cfg: CycleConfig, key: String): String =
        ContractStrings.text(context, "cycle.basis.$key.about", cfg.basis.firstOrNull { it.key == key }?.about.orEmpty())

    fun disclaimer(context: Context, cfg: CycleConfig): String = ContractStrings.text(context, "cycle.disclaimer", cfg.disclaimer)

    fun fertilityNote(context: Context, cfg: CycleConfig): String =
        ContractStrings.text(context, "cycle.fertility_note", cfg.fertilityNote)

    /** An insight with its `{placeholders}` filled in. */
    fun insight(context: Context, cfg: CycleConfig, insight: CycleInsight): String {
        val template = cfg.insights.firstOrNull { it.key == insight.key }?.template ?: return ""
        var text = ContractStrings.text(context, "cycle.insights.${insight.key}.template", template)
        for ((k, v) in insight.params) text = text.replace("{$k}", v.toString())
        return text
    }

    fun professional(cfg: CycleConfig, key: String): Boolean = cfg.insights.firstOrNull { it.key == key }?.professional == true
}
