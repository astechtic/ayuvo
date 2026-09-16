package com.ayuvo.health.records.share

import com.ayuvo.health.records.analytes.AnalyteCatalog
import com.ayuvo.health.records.model.HealthRecord
import com.ayuvo.health.records.model.RecordFileType
import com.ayuvo.health.records.model.RecordField
import com.ayuvo.health.records.model.RecordPage
import com.ayuvo.health.records.processing.UnitsCatalog

/** One §34 warning shown before the final confirm. */
data class ShareWarningItem(
    val code: String,
    val recordId: String? = null,
    val pageIndex: Int? = null,
    val text: String
)

/** The §34 warning catalogue and its `{title}` / `{page}` templates (reference `SHARE_WARNINGS`). */
object ShareWarnings {
    const val UNKNOWN_RECORD = "unknown_record"
    const val NO_ORIGINAL = "no_original"
    const val PAGE_MISSING = "page_missing"
    const val PAGE_NOT_REDACTABLE = "page_not_redactable"
    const val RECORD_NOT_REDACTABLE = "record_not_redactable"
    const val TEXT_LAYER_LOST = "text_layer_lost"
    const val REDACTED_TEXT_LAYER_LOST = "redacted_text_layer_lost"
    const val NOTHING_TO_SHARE = "nothing_to_share"

    val TEMPLATES: Map<String, String> = linkedMapOf(
        UNKNOWN_RECORD to "A selected record is no longer available and was left out",
        NO_ORIGINAL to "The original file of {title} is missing and was left out",
        PAGE_MISSING to "Page {page} of {title} doesn't exist and was left out",
        PAGE_NOT_REDACTABLE to "Page {page} can't be redacted and was left out",
        RECORD_NOT_REDACTABLE to "No page of {title} can be redacted, so it was left out",
        TEXT_LAYER_LOST to "Selected pages are shared as images",
        REDACTED_TEXT_LAYER_LOST to "Redacted pages are shared as images",
        NOTHING_TO_SHARE to "Nothing is selected to share"
    )

    private val PLACEHOLDER = Regex("\\{([a-z_]+)\\}")

    /** Single left-to-right pass; replaced text is never rescanned (reference `fill_placeholders`). */
    fun fill(template: String, values: Map<String, String>): String =
        PLACEHOLDER.replace(template) { m -> values[m.groupValues[1]] ?: m.value }

    fun warning(code: String, recordId: String? = null, pageIndex: Int? = null, title: String? = null): ShareWarningItem =
        ShareWarningItem(
            code = code,
            recordId = recordId,
            pageIndex = pageIndex,
            text = fill(
                TEMPLATES.getValue(code),
                mapOf("title" to (title ?: ""), "page" to (pageIndex?.plus(1)?.toString() ?: ""))
            )
        )

    fun pageNotRedactable(recordId: String, pageIndex: Int): ShareWarningItem =
        warning(PAGE_NOT_REDACTABLE, recordId = recordId, pageIndex = pageIndex)
}

/** The pages of one record that survive the plan (originals only). */
data class SharePlanPages(val recordId: String, val pages: List<Int>)

data class SharePlanWarningsResult(
    val warnings: List<ShareWarningItem>,
    val pages: List<SharePlanPages>,
    val records: List<String>
)

/**
 * §34 "what will actually go out" (reference `share_plan_warnings`): the warnings the share screen must
 * show before the final confirm, and the surviving pages per record.
 */
object SharePlanWarnings {

    /** The plan's pages for [record]: all pages, or the listed indexes de-duplicated in given order. */
    fun planPages(plan: SharePlan, record: HealthRecord): Triple<List<Int>, List<Int>, Boolean> {
        val spec = plan.pages[record.id] ?: return Triple((0 until record.pageCount).toList(), emptyList(), true)
        val wanted = mutableListOf<Int>()
        val missing = mutableListOf<Int>()
        val seen = HashSet<Int>()
        for (index in spec) {
            if (!seen.add(index)) continue
            if (index in 0 until record.pageCount) wanted += index else missing += index
        }
        return Triple(wanted, missing, false)
    }

    fun build(
        plan: SharePlan,
        records: Map<String, HealthRecord>,
        pagesByRecord: Map<String, List<RecordPage>> = emptyMap(),
        fieldsByRecord: Map<String, List<RecordField>> = emptyMap(),
        summaryInputs: List<ShareSummaryText.Input> = emptyList(),
        catalog: AnalyteCatalog = AnalyteCatalog.EMPTY,
        units: UnitsCatalog = UnitsCatalog.active
    ): SharePlanWarningsResult {
        val warnings = mutableListOf<ShareWarningItem>()
        val kept = mutableListOf<SharePlanPages>()
        val redacting = RedactionTargets.orderedClasses(plan.redactions).isNotEmpty()
        val original = plan.includeOriginal
        var anyOriginal = false

        for (recordId in plan.recordIds) {
            val record = records[recordId]
            if (record == null) {
                warnings += ShareWarnings.warning(ShareWarnings.UNKNOWN_RECORD, recordId = recordId)
                continue
            }
            val title = ShareSummaryText.collapse(record.title).orEmpty()
            val (wanted, missing, _) = planPages(plan, record)
            for (index in missing) {
                warnings += ShareWarnings.warning(ShareWarnings.PAGE_MISSING, recordId = recordId, pageIndex = index, title = title)
            }
            var pages = wanted
            if (original && redacting) {
                val targets = RedactionTargets.forRecord(
                    recordId,
                    pagesByRecord[recordId].orEmpty(),
                    fieldsByRecord[recordId].orEmpty(),
                    plan.redactions,
                    units
                )
                val excluded = targets.excludedPages.toSet()
                targets.warnings.filter { it.pageIndex in wanted }.forEach { warnings += it }
                pages = wanted.filter { it !in excluded }
                if (wanted.isNotEmpty() && pages.isEmpty()) {
                    warnings += ShareWarnings.warning(ShareWarnings.RECORD_NOT_REDACTABLE, recordId = recordId, title = title)
                }
            }
            if (original) {
                if (record.filePath.isNullOrEmpty()) {
                    warnings += ShareWarnings.warning(ShareWarnings.NO_ORIGINAL, recordId = recordId, title = title)
                    pages = emptyList()
                } else if (pages.isNotEmpty()) {
                    anyOriginal = true
                }
            } else {
                pages = emptyList()
            }
            kept += SharePlanPages(recordId, pages)
        }

        if (original && anyOriginal) {
            if (redacting) {
                warnings += ShareWarnings.warning(ShareWarnings.REDACTED_TEXT_LAYER_LOST)
            } else if (kept.any { entry ->
                    val record = records[entry.recordId]
                    record != null && entry.pages.isNotEmpty() && record.fileType == RecordFileType.PDF &&
                        !planPages(plan, record).third
                }
            ) {
                warnings += ShareWarnings.warning(ShareWarnings.TEXT_LAYER_LOST)
            }
        }

        val summary = ShareSummaryText.build(summaryInputs, plan, catalog)
        if (summary.text == null && !anyOriginal) {
            warnings += ShareWarnings.warning(ShareWarnings.NOTHING_TO_SHARE)
        }
        return SharePlanWarningsResult(warnings, kept, kept.map { it.recordId })
    }
}
