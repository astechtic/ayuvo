package com.ayuvo.health.records.processing

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/** One JUnit test per shared vector file (docs/health-records.md §9.2). */
class FoldVectorsTest {
    @Test fun allCases() = RecordsVectors.assertAll("fold.json")
}

class ClassifierVectorsTest {
    @Test fun allCases() = RecordsVectors.assertAll("classifier.json")
}

class DatesVectorsTest {
    @Test fun allCases() = RecordsVectors.assertAll("dates.json")
}

class FieldsVectorsTest {
    @Test fun allCases() = RecordsVectors.assertAll("fields.json")
}

class LabRowsVectorsTest {
    @Test fun allCases() = RecordsVectors.assertAll("lab_rows.json")
}

class BoundariesVectorsTest {
    @Test fun allCases() = RecordsVectors.assertAll("boundaries.json")
}

class HighlightsVectorsTest {
    @Test fun allCases() = RecordsVectors.assertAll("highlights.json")
}

class ReviewVectorsTest {
    @Test fun allCases() = RecordsVectors.assertAll("review.json")
}

class ApplyExtractionVectorsTest {
    @Test fun allCases() = RecordsVectors.assertAll("apply_extraction.json")
}

class AiValidationVectorsTest {
    @Test fun allCases() = RecordsVectors.assertAll("ai_validation.json")
}

class AiChunksVectorsTest {
    @Test fun allCases() = RecordsVectors.assertAll("ai_chunks.json")
}

class HashingVectorsTest {
    @Test fun allCases() = RecordsVectors.assertAll("hashing.json")
}

class QueryParserVectorsTest {
    @Test fun allCases() = RecordsVectors.assertAll("query_parser.json")
}

/** Every vector file in shared/records/test-vectors has a test class above. */
class VectorFilesCoveredTest {
    @Test
    fun everyVectorFileIsRun() {
        val dir = RecordsTestFiles.shared("test-vectors") ?: return
        val files = dir.listFiles { f: File -> f.name.endsWith(".json") }.orEmpty().map { it.name }.sorted()
        assertEquals(listOf("fold.json", "classifier.json", "dates.json", "fields.json", "lab_rows.json", "boundaries.json", "highlights.json", "review.json", "apply_extraction.json", "ai_validation.json", "ai_chunks.json", "hashing.json", "query_parser.json").sorted(), files)
    }
}
