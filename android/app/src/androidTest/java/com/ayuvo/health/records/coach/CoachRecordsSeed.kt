package com.ayuvo.health.records.coach

import com.ayuvo.health.records.data.RecordsStore
import com.ayuvo.health.records.model.ExtractedField
import com.ayuvo.health.records.model.ExtractionMethod
import com.ayuvo.health.records.model.FieldKey
import com.ayuvo.health.records.model.HealthRecord
import com.ayuvo.health.records.model.ImportMethod
import com.ayuvo.health.records.model.RecordFileType
import com.ayuvo.health.records.model.RecordPage
import com.ayuvo.health.records.model.RecordSource
import com.ayuvo.health.records.model.RecordType
import com.ayuvo.health.records.model.TextSource
import java.util.UUID

/** Text records for Coach tests: three CBCs (Jul/Aug/Sep) and a prescription, with promoted observations. */
object CoachRecordsSeed {
    private fun result(name: String, value: String, unit: String, ref: String, lo: Double, hi: Double, flag: String) = ExtractedField(
        FieldKey.TEST_RESULT, name,
        """{"name":"$name","value":"$value","value_num":$value,"unit":"$unit","ref_text":"$ref","ref_low":$lo,"ref_high":$hi,"flag":"$flag"}""",
        ExtractionMethod.RULES, 0.9, 0, null, "$name $value $unit $ref"
    )

    private fun field(key: String, value: String, confidence: Double = 0.85, json: String? = null) =
        ExtractedField(key, value, json, ExtractionMethod.RULES, confidence, 0, null, value)

    private suspend fun textRecord(store: RecordsStore, title: String, date: String, type: RecordType, text: String, createdMs: Long): HealthRecord {
        val id = UUID.randomUUID().toString()
        return store.insert(
            HealthRecord(
                id = id, title = title, recordType = type, category = type.defaultCategory, source = RecordSource.PASTE,
                importMethod = ImportMethod.PASTE_TEXT, createdMs = createdMs, updatedMs = createdMs, documentDate = date,
                mimeType = "text/plain", fileType = RecordFileType.TEXT, pageCount = 1
            ),
            listOf(RecordPage(id, 0, text, TextSource.USER))
        )
    }

    suspend fun cbc(store: RecordsStore, date: String, hb: String, mcv: String, wbc: String, hbFlag: String, createdMs: Long): HealthRecord {
        val text = "Metro Diagnostics\nComplete Blood Count\nPatient Name: Asha Rao\nMobile: 9876543210\n" +
            "Hemoglobin $hb g/dL 12.0 - 15.5\nMCV $mcv fL 80 - 100\nTotal WBC Count $wbc 10^3/µL 4.0 - 11.0\nReferred by Dr. A.K. Sharma"
        val r = textRecord(store, "Complete Blood Count", date, RecordType.LAB_REPORT, text, createdMs)
        store.applyExtraction(r.id, listOf(
            field(FieldKey.REPORT_DATE, date, 0.9),
            field(FieldKey.FACILITY, "Metro Diagnostics", 0.8),
            field(FieldKey.DOCTOR_NAME, "A.K. Sharma", 0.85),
            field(FieldKey.PATIENT_NAME, "Asha Rao", 0.75),
            field(FieldKey.REPORT_NAME, "Complete Blood Count", 0.8),
            result("Hemoglobin", hb, "g/dL", "12.0 - 15.5", 12.0, 15.5, hbFlag),
            result("MCV", mcv, "fL", "80 - 100", 80.0, 100.0, if (mcv.toDouble() < 80) "low" else "normal"),
            result("Total WBC Count", wbc, "10^3/µL", "4.0 - 11.0", 4.0, 11.0, "normal")
        )) { RecordsCoach.TYPE_LABELS[it.raw] }
        store.refreshKnowledge(r.id)
        return r
    }

    suspend fun prescription(store: RecordsStore, date: String, createdMs: Long): HealthRecord {
        val r = textRecord(store, "Prescription — City Care Clinic", date, RecordType.PRESCRIPTION, "City Care Clinic\nDr. Meera Iyer\nTab Ferrous Sulphate 200 mg 1-0-1 x 30 days", createdMs)
        store.applyExtraction(r.id, listOf(
            field(FieldKey.PRESCRIPTION_DATE, date, 0.9),
            field(FieldKey.FACILITY, "City Care Clinic", 0.8),
            field(FieldKey.DOCTOR_NAME, "Meera Iyer", 0.85),
            field(FieldKey.DIAGNOSIS, "Iron deficiency anaemia", 0.75),
            field(FieldKey.MEDICATION, "Ferrous Sulphate", 0.8, """{"name":"Ferrous Sulphate","strength":"200 mg","form":"tablet","dose":null,"frequency":"1-0-1","duration":"x 30 days","instructions":null}""")
        )) { RecordsCoach.TYPE_LABELS[it.raw] }
        store.refreshKnowledge(r.id)
        return r
    }

    /** Jul/Aug/Sep CBCs + a September prescription; returns them oldest first. */
    suspend fun seed(store: RecordsStore): List<HealthRecord> = listOf(
        cbc(store, "2026-07-10", "10.2", "78", "7.1", "low", 1_000),
        cbc(store, "2026-08-12", "11.1", "81", "6.8", "low", 2_000),
        cbc(store, "2026-09-09", "9.1", "76", "7.4", "low", 3_000),
        prescription(store, "2026-09-10", 4_000)
    )
}
