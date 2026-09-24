package com.ayuvo.health.medications.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.io.File

/** One JUnit test per shared medication vector file (docs/medications.md §18). */
class OccurrencesVectorsTest {
    @Test fun allCases() = MedicationsVectors.assertAll("occurrences.json")
}

class DoseStatusVectorsTest {
    @Test fun allCases() = MedicationsVectors.assertAll("dose_status.json")
}

class MissedVectorsTest {
    @Test fun allCases() = MedicationsVectors.assertAll("missed.json")
}

class TimelineVectorsTest {
    @Test fun allCases() = MedicationsVectors.assertAll("timeline.json")
}

class AdherenceVectorsTest {
    @Test fun allCases() = MedicationsVectors.assertAll("adherence.json")
}

class RemindersVectorsTest {
    @Test fun allCases() = MedicationsVectors.assertAll("reminders.json")
}

class DoseActionsVectorsTest {
    @Test fun allCases() = MedicationsVectors.assertAll("dose_actions.json")
}

class LifecycleVectorsTest {
    @Test fun allCases() = MedicationsVectors.assertAll("lifecycle.json")
}

class AutoCompleteVectorsTest {
    @Test fun allCases() = MedicationsVectors.assertAll("auto_complete.json")
}

class FrequencyHintVectorsTest {
    @Test fun allCases() = MedicationsVectors.assertAll("frequency_hint.json")
}

class ArchiveVectorsTest {
    @Test fun allCases() = MedicationsVectors.assertAll("archive.json")
}

class ValidationVectorsTest {
    @Test fun allCases() = MedicationsVectors.assertAll("validation.json")
}

class MedicationsCoachToolsVectorsTest {
    @Test fun allCases() = MedicationsVectors.assertAll("coach_tools_payloads.json")
}

/** Files that have a runner above; a new shared vector file without one fails [MedicationsVectorCoverageTest]. */
val MEDICATION_VECTOR_FILES_WITH_RUNNERS = listOf(
    "occurrences.json", "dose_status.json", "missed.json", "timeline.json", "adherence.json", "reminders.json",
    "dose_actions.json", "lifecycle.json", "auto_complete.json", "frequency_hint.json", "archive.json", "validation.json",
    "coach_tools_payloads.json"
)

/** Every vector file in shared/medications/test-vectors has a test class above (and every runner a file). */
class MedicationsVectorCoverageTest {
    @Test
    fun everyVectorFileIsRun() {
        val dir = MedicationsTestFiles.shared("test-vectors")
        assertNotNull("shared/medications/test-vectors not found", dir)
        val files = dir!!.listFiles { f: File -> f.name.endsWith(".json") }.orEmpty().map { it.name }.sorted()
        assertEquals(MEDICATION_VECTOR_FILES_WITH_RUNNERS.sorted(), files)
    }
}
