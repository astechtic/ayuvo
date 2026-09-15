package com.ayuvo.health.records.processing

import com.ayuvo.health.records.model.ExtractedField
import com.ayuvo.health.records.model.RecordType
import java.time.LocalDate

/** Stage `rules` (§11–§13): extract_dates + extract_fields + parse_lab_rows, all `method = rules`. */
class RulesExtractor(private val config: RecordTypesConfig, private val units: UnitsCatalog = UnitsCatalog.DEFAULT) {
    private val fields = FieldExtractor(config, units)
    private val labRows = LabRowParser(units)

    fun extractItems(pageTexts: List<String>, recordType: RecordType, today: LocalDate, dateOrder: DateOrder = DateOrder.DMY): List<RuleItem> =
        DateDetector.extract(pageTexts, recordType, today, dateOrder) +
            fields.extractItems(pageTexts, recordType.raw) +
            labRows.parseRows(pageTexts, recordType)

    fun extract(pageTexts: List<String>, recordType: RecordType, today: LocalDate, dateOrder: DateOrder = DateOrder.DMY): List<ExtractedField> =
        extractItems(pageTexts, recordType, today, dateOrder).map { it.toExtracted() }
}
