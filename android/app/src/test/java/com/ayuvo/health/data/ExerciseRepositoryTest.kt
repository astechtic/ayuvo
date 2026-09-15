package com.ayuvo.health.data

import com.ayuvo.health.models.OutdoorActivitySettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ExerciseRepositoryTest {
    /** The exact file Gradle merges into assets (app/build.gradle.kts assets.srcDirs). */
    private val catalogFile = File("../../shared/exercises/${ExerciseRepository.CATALOG_ASSET_NAME}")

    private val items: List<ExerciseItem> by lazy {
        catalogFile.reader(Charsets.UTF_8).use(ExerciseRepository::parseCatalog)
    }

    @Test
    fun bundledCatalogParsesEveryExerciseWithUniqueIds() {
        assertEquals(1_326, items.size)
        assertEquals(items.size, items.map { it.id }.toSet().size)
        assertTrue(items.all { it.name.isNotBlank() && it.instructions.isNotEmpty() })
    }

    @Test
    fun catalogueMediaUsesPinnedGithubUrls() {
        val media = items.filter { it.id.all(Char::isDigit) }
        assertEquals(1_324, media.size)
        val prefix = "https://raw.githubusercontent.com/hasaneyldrm/exercises-dataset/"
        assertTrue(media.all { it.imageUrl!!.startsWith(prefix) && it.imageUrl!!.endsWith(".jpg") })
        assertTrue(media.all { it.gifUrl!!.startsWith(prefix) && it.gifUrl!!.endsWith(".gif") })
        assertTrue(media.none { it.imageUrl!!.contains("/main/") })
    }

    @Test
    fun quickLogActivitiesExistWithoutMedia() {
        for (id in listOf(OutdoorActivitySettings.WALKING_EXERCISE_ID, OutdoorActivitySettings.RUNNING_EXERCISE_ID)) {
            val item = items.firstOrNull { it.id == id }
            assertNotNull(id, item)
            assertTrue(item!!.isCardio)
            assertNull(ExerciseVisual.from(item).thumbnailUrl)
            assertFalse(ExerciseVisual.from(item).hasRemoteMedia)
        }
    }

    @Test
    fun filteringAndFacetsUseTheNewMetadata() {
        val repository = ExerciseRepository.of(items)
        assertTrue("Cardio" in repository.availableBodyParts)
        assertTrue("Pectorals" in repository.availablePrimaryMuscles)
        val chestBarbell = repository.filtered(bodyParts = setOf("Chest"), equipment = setOf("Barbell"))
        assertTrue(chestBarbell.isNotEmpty())
        assertTrue(chestBarbell.all { it.bodyPart == "Chest" && it.equipment == "Barbell" })
        val curls = repository.filtered(searchText = "curl")
        assertTrue(curls.size > PagedResultsPageSize)
        assertTrue(curls.all { "curl" in it.searchableText })
        assertEquals(curls.map { it.name.lowercase() }.sorted(), curls.map { it.name.lowercase() })
    }

    @Test
    fun searchAliasesAndMetHardcodedIdsExistInTheCatalog() {
        val ids = items.map { it.id }.toSet()
        listOf("0201", "2138", "0798", "3666", "0685", "0684", "3656", "2612", "2311", "2141")
            .forEach { assertTrue("missing $it", it in ids) }
    }

    private companion object {
        const val PagedResultsPageSize = 30
    }
}
