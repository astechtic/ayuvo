package com.ayuvo.health.records.coach

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ayuvo.health.records.analytes.AnalyteCatalog
import com.ayuvo.health.records.data.RecordFileStore
import com.ayuvo.health.records.data.RecordsDatabase
import com.ayuvo.health.records.data.SqliteRecordsStore
import com.ayuvo.health.records.model.HealthRecord
import com.ayuvo.health.records.processing.DateOrder
import com.ayuvo.health.records.processing.UnitsCatalog
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.LocalDate
import java.util.UUID

/**
 * The app's SQLite path ([StoreCoachData]: framework FTS4 matchinfo BM25, SQL row loading) produces the
 * same Coach payloads as the reference-shaped in-memory snapshot of the same rows ([SnapshotCoachData],
 * which the shared vectors exercise), on real CBC records.
 */
@RunWith(AndroidJUnit4::class)
class RecordsCoachStoreParityTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var helper: RecordsDatabase
    private lateinit var store: SqliteRecordsStore
    private lateinit var tempRoot: File
    private lateinit var catalog: AnalyteCatalog
    private lateinit var contract: RecordsCoachContract
    private val today = LocalDate.parse("2026-09-15")

    @Before
    fun setUp() {
        context.deleteDatabase(DB)
        tempRoot = File(context.cacheDir, "records-coach-${UUID.randomUUID()}").apply { mkdirs() }
        val units = UnitsCatalog.parseOrDefault(context.assets.open("records/units.json").bufferedReader().use { it.readText() })
        catalog = AnalyteCatalog.parse(context.assets.open(AnalyteCatalog.ASSET_PATH).bufferedReader().use { it.readText() }, units)
        contract = RecordsCoachContract.parse(context.assets.open(RecordsCoachContract.ASSET_PATH).bufferedReader().use { it.readText() })
        helper = RecordsDatabase(context, DB)
        store = SqliteRecordsStore(helper, RecordFileStore(File(tempRoot, "files"), File(tempRoot, "render"), File(tempRoot, "share")), catalog = { catalog })
    }

    @After
    fun tearDown() {
        store.close()
        context.deleteDatabase(DB)
        tempRoot.deleteRecursively()
    }

    private suspend fun snapshotOf(records: List<HealthRecord>): SnapshotCoachData {
        val all = store.allRecords()
        val ids = all.map { it.id }
        val fields = store.fieldsFor(ids)
        val observations = store.observationsFor(ids)
        val highlights = store.highlightsFor(ids)
        return SnapshotCoachData(
            records = all,
            tags = all.associate { r -> r.id to store.tags(r.id).map { it.name } },
            fields = ids.flatMap { fields[it].orEmpty() },
            observations = ids.flatMap { observations[it].orEmpty() },
            highlights = ids.flatMap { highlights[it].orEmpty() },
            pages = ids.flatMap { store.pages(it) },
            links = ids.flatMap { store.links(it) }.distinctBy { it.aId to it.bId },
            aliases = store.userAliases(),
            catalog = catalog
        ).also { assertEquals(records.size, all.size) }
    }

    private fun args(vararg pairs: Pair<String, Any?>) = RecordsCoachTools.toJson(mapOf(*pairs))

    @Test
    fun storeAndSnapshotGiveIdenticalPayloads() = runBlocking {
        val seeded = CoachRecordsSeed.seed(store)
        val sqlite = StoreCoachData(store) { catalog }
        val memory = snapshotOf(seeded)
        val (jul, aug, sep, rx) = seeded

        suspend fun same(label: String, block: suspend (RecordsCoachData) -> JsonObject): JsonObject {
            val a = block(sqlite)
            val b = block(memory)
            assertEquals(label, b, a)
            return a
        }

        val hemoglobin = same("search hemoglobin") { RecordsCoach.search(it, contract, args("query" to "hemoglobin"), emptyList(), today, DateOrder.DMY) }
        assertEquals(3, hemoglobin["count"]!!.jsonPrimitive.content.toInt())
        assertEquals(3, hemoglobin["values"]!!.jsonArray.size)
        same("search cbc low") { RecordsCoach.search(it, contract, args("query" to "complete blood count hemoglobin was low", "limit" to 2), emptyList(), today, DateOrder.DMY) }
        same("search abnormal") { RecordsCoach.search(it, contract, args("query" to "abnormal"), emptyList(), today, DateOrder.DMY) }
        same("search sharma") { RecordsCoach.search(it, contract, args("query" to "sharma", "from" to "2026-08-01"), emptyList(), today, DateOrder.DMY) }
        same("search restricted") { RecordsCoach.search(it, contract, args("query" to ""), listOf(jul.id, rx.id), today, DateOrder.DMY) }

        val get = same("get with text") { RecordsCoach.get(it, contract, args("record_id" to sep.id, "include_text" to true), emptyList()) }
        assertFalse("no patient name or phone in the payload", get.toString().contains("Asha") || get.toString().contains("9876543210"))
        assertTrue(get["text"]!!.jsonPrimitive.content.contains("Hemoglobin 9.1 g/dL"))
        same("get restricted") { RecordsCoach.get(it, contract, args("record_id" to sep.id), listOf(jul.id)) }

        val series = same("series Hb") { RecordsCoach.series(it, contract, args("analyte" to "Hb"), emptyList()) }
        assertEquals(listOf(jul.id, aug.id, sep.id), series["points"]!!.jsonArray.map { it.jsonObject["record_id"]!!.jsonPrimitive.content })

        assertEquals(RecordsCoach.compareCandidate(memory, sep.id), RecordsCoach.compareCandidate(sqlite, sep.id))
        assertEquals(aug.id, RecordsCoach.compareCandidate(sqlite, sep.id).previousId)
        assertEquals(RecordsCoach.latestLabSelection(memory), RecordsCoach.latestLabSelection(sqlite))
        val packed = RecordsCoach.pack(sqlite, listOf(jul.id, sep.id, rx.id))
        assertEquals(RecordsCoach.pack(memory, listOf(jul.id, sep.id, rx.id)).text, packed.text)
        assertTrue(packed.text, packed.text!!.startsWith("## Health records (selected by the user)\n### Prescription — City Care Clinic — 2026-09-10 (Prescription)"))
        assertEquals(
            RecordsCoach.promptLines(memory, contract, true, listOf(sep.id)),
            RecordsCoach.promptLines(sqlite, contract, true, listOf(sep.id))
        )
        assertEquals(JsonPrimitive("health records are not available"), RecordsCoachTools(contract, sqlite, stillAvailable = { false }).executeJson("records_search", args("query" to ""))["error"])
    }

    private companion object {
        const val DB = "records_coach_parity_test.db"
    }
}
