package com.ayuvo.health.records.processing

import com.ayuvo.health.records.model.ExtractedField
import com.ayuvo.health.records.model.RecordType
import com.ayuvo.health.records.model.SplitSegment
import java.time.LocalDate
import java.time.chrono.IsoChronology
import java.time.format.DateTimeFormatterBuilder
import java.time.format.FormatStyle
import java.util.Locale

/**
 * The deterministic stages behind one object, built once from the bundled contract assets
 * (`assets/records/record_types.json`, `assets/records/units.json`).
 */
class RecordRules(recordTypesJson: String, unitsJson: String?) {
    private val config = RecordTypesConfig.parse(recordTypesJson)
    private val classifier = DocumentClassifier(config)
    private val units = UnitsCatalog.parseOrDefault(unitsJson)
    private val extractor = RulesExtractor(config, units)
    private val boundaryDetector = BoundaryDetector(classifier)

    fun classify(pageTexts: List<String>) = classifier.classify(pageTexts)

    fun extract(pageTexts: List<String>, type: RecordType, today: LocalDate, order: DateOrder): List<ExtractedField> =
        extractor.extract(pageTexts, type, today, order)

    fun boundaries(pageTexts: List<String>): List<SplitSegment> = boundaryDetector.detect(pageTexts)

    companion object {
        const val RECORD_TYPES_ASSET = "records/record_types.json"
        const val UNITS_ASSET = "records/units.json"

        /** Month-first when the device's short date pattern starts with the month (e.g. US). */
        fun deviceDateOrder(locale: Locale = Locale.getDefault()): DateOrder {
            val pattern = runCatching {
                DateTimeFormatterBuilder.getLocalizedDateTimePattern(FormatStyle.SHORT, null, IsoChronology.INSTANCE, locale)
            }.getOrDefault("d/M/yy")
            val first = pattern.firstOrNull { it == 'd' || it == 'M' || it == 'y' }
            return if (first == 'M') DateOrder.MDY else DateOrder.DMY
        }
    }
}
