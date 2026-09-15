package com.ayuvo.health.records.processing

import com.ayuvo.health.records.model.FieldKey
import com.ayuvo.health.records.model.FieldState
import com.ayuvo.health.records.model.RecordField
import com.ayuvo.health.records.model.RecordHighlight
import com.ayuvo.health.records.model.RecordType

/** The one `records_fts` row of a record (docs/health-records.md §17), every column folded. */
object SearchIndexer {

    data class Row(
        val title: String,
        val people: String,
        val clinical: String,
        val body: String,
        val notesTags: String,
        val highlights: String
    )

    private val peopleKeys = listOf(FieldKey.DOCTOR_NAME, FieldKey.DOCTOR_SPECIALTY, FieldKey.FACILITY, FieldKey.DEPARTMENT, FieldKey.PATIENT_NAME)
    private val clinicalKeys = listOf(FieldKey.REPORT_NAME, FieldKey.TEST_RESULT, FieldKey.DIAGNOSIS, FieldKey.SYMPTOM, FieldKey.MEDICATION, FieldKey.PROCEDURE)

    /** English type label used in the index (search terms are English type words, §17). */
    fun typeLabel(type: RecordType): String = if (type == RecordType.OTHER) "" else type.raw.replace('_', ' ')

    fun build(
        title: String,
        recordType: RecordType,
        notes: String?,
        tagNames: List<String>,
        pageTexts: List<String>,
        fields: List<RecordField>,
        highlights: List<RecordHighlight>,
        /** Phase 3: display names of mapped analytes (§19 FTS `clinical`). */
        analyteNames: List<String> = emptyList()
    ): Row {
        val usable = fields.filter { it.state != FieldState.REJECTED }
        fun values(keys: List<String>) = keys.flatMap { key -> usable.filter { it.key == key }.map { it.valueText } }.distinct()
        return Row(
            title = RecordText.fold(title),
            people = RecordText.fold(values(peopleKeys).joinToString("\n")),
            clinical = RecordText.fold((listOf(typeLabel(recordType)) + values(clinicalKeys) + analyteNames).filter { it.isNotBlank() }.distinct().joinToString("\n")),
            body = RecordText.fold(pageTexts.joinToString("\n")),
            notesTags = RecordText.fold(listOfNotNull(notes).plus(tagNames).joinToString(" ")),
            highlights = RecordText.fold(highlights.filter { !it.dismissed }.joinToString("\n") { it.text })
        )
    }
}
