package com.ayuvo.health.export

import com.ayuvo.health.export.AllDataImportPlan.Invalid
import com.ayuvo.health.export.AllDataImportPlan.SkipReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.Instant
import java.util.zip.ZipFile

class AllDataImportPlanTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun file(section: String, name: String, format: String = AllDataImportPlan.EXPECTED_FORMAT.getValue(section)) =
        """{"name":"$name","section":"$section","format":"$format","bytes":10,"counts":{"records":2}}"""

    private fun manifest(
        files: List<String>,
        app: String = "Ayuvo",
        format: String = "ayuvo-all-data",
        version: Int = 1,
        platform: String = "android"
    ) = """{"app":"$app","format":"$format","format_version":$version,"platform":"$platform","app_version":"1.0",
        |"created_at":"2026-09-22T10:15:30+05:30","files":[${files.joinToString(",")}],"skipped":[]}""".trimMargin()

    private val all = listOf(
        file("health_records", "health-records/r.zip"),
        file("medications", "medications/ayuvo-medications.json"),
        file("food_diary", "food-diary/d.json"),
        file("app_backup", "app-backup/ayuvo-backup.zip"),
        file("portable_data", "portable-data/ayuvo-portable-data.json"),
        file("health_data", "health-data/h.zip"),
        file("coach_chats", "coach-chats/ayuvo-coach-chats.zip")
    )
    private val entries = setOf(
        "manifest.json", "health-records/r.zip", "medications/ayuvo-medications.json",
        "food-diary/d.json", "app-backup/ayuvo-backup.zip", "portable-data/ayuvo-portable-data.json", "health-data/h.zip",
        "coach-chats/ayuvo-coach-chats.zip"
    )

    private fun ok(result: AllDataImportPlan.Result) = (result as AllDataImportPlan.Result.Ok).plan
    private fun failed(result: AllDataImportPlan.Result) = (result as AllDataImportPlan.Result.Failed).reason

    @Test
    fun sectionsRunInFixedOrderAndTheDiaryIsNotImportedTwice() {
        val plan = ok(AllDataImportPlan.plan(manifest(all), entries))
        assertEquals(AllDataImportPlan.ORDER, plan.sections.map { it.id })
        assertNull(plan.sections[0].skip)
        assertEquals(SkipReason.IN_APP_BACKUP, plan.sections[1].skip)
        assertEquals(SkipReason.IN_APP_BACKUP, plan.sections[2].skip)
        assertTrue(plan.sections.drop(3).all { it.imports })
        assertEquals(5, plan.importCount)
        assertEquals(mapOf("records" to 2L), plan.sections[5].counts)
    }

    @Test
    fun theOrderPutsPortableDataRightAfterTheAppBackup() {
        assertEquals(
            listOf("app_backup", "portable_data", "food_diary", "health_data", "medications", "health_records", "coach_chats"),
            AllDataImportPlan.ORDER
        )
        assertEquals("ayuvo-portable-data", AllDataImportPlan.EXPECTED_FORMAT.getValue("portable_data"))
    }

    @Test
    fun anIphoneExportSkipsSettingsAndImportsTheDiaryInstead() {
        val plan = ok(AllDataImportPlan.plan(manifest(all, platform = "ios"), entries))
        assertEquals(SkipReason.OTHER_PLATFORM, plan.sections.first { it.id == "app_backup" }.skip)
        assertTrue(plan.sections.first { it.id == "food_diary" }.imports)
        // The portable part is what restores the profile and logs from the other platform.
        assertTrue(plan.sections.first { it.id == "portable_data" }.imports)
        assertEquals(6, plan.importCount)
    }

    @Test
    fun portableDataIsNeverSkippedAsAnotherPlatform() {
        for (platform in listOf("ios", "android", "windows")) {
            val skip = ok(AllDataImportPlan.plan(manifest(all, platform = platform), entries)).sections.first { it.id == "portable_data" }.skip
            assertTrue(platform, skip != SkipReason.OTHER_PLATFORM)
        }
    }

    @Test
    fun portableDataImportsWhenThereIsNoAppBackupPartOrItCannotBeRead() {
        val onlyPortable = listOf(file("portable_data", "portable-data/ayuvo-portable-data.json"), file("food_diary", "food-diary/d.json"))
        val names = setOf("manifest.json", "portable-data/ayuvo-portable-data.json", "food-diary/d.json")
        val plan = ok(AllDataImportPlan.plan(manifest(onlyPortable), names))
        assertEquals(listOf("portable_data", "food_diary"), plan.sections.map { it.id })
        assertTrue(plan.sections.all { it.imports })

        // an app backup in a format this build can't read is not "applied", so the portable part still imports
        val unreadable = listOf(
            file("app_backup", "app-backup/ayuvo-backup.zip", format = "ayuvo-cloud-backup-v9"),
            file("portable_data", "portable-data/ayuvo-portable-data.json")
        )
        val plan2 = ok(AllDataImportPlan.plan(manifest(unreadable), entries))
        assertEquals(SkipReason.UNSUPPORTED, plan2.sections[0].skip)
        assertTrue(plan2.sections[1].imports)
    }

    @Test
    fun aPortableFileInAnotherFormatIsSkippedAsUnsupported() {
        val files = listOf(file("portable_data", "portable-data/ayuvo-portable-data.json", format = "ayuvo-portable-data-v9"))
        val plan = ok(AllDataImportPlan.plan(manifest(files), entries))
        assertEquals(SkipReason.UNSUPPORTED, plan.sections.single().skip)
    }

    @Test
    fun missingSectionsAreSimplyAbsent() {
        val plan = ok(AllDataImportPlan.plan(manifest(listOf(file("medications", "medications/m.json"))), setOf("manifest.json", "medications/m.json")))
        assertEquals(listOf("medications"), plan.sections.map { it.id })
    }

    @Test
    fun rejectsForeignOrNewerFiles() {
        assertEquals(Invalid.NOT_AYUVO, failed(AllDataImportPlan.plan(null, entries)))
        assertEquals(Invalid.NOT_AYUVO, failed(AllDataImportPlan.plan("not json", entries)))
        assertEquals(Invalid.NOT_AYUVO, failed(AllDataImportPlan.plan(manifest(all, app = "Fud"), entries)))
        assertEquals(Invalid.NOT_AYUVO, failed(AllDataImportPlan.plan(manifest(all, format = "ayuvo-health-data"), entries)))
        assertEquals(Invalid.NEWER_VERSION, failed(AllDataImportPlan.plan(manifest(all, version = 2), entries)))
    }

    @Test
    fun unsafeOrMissingEntriesInvalidateTheFile() {
        val unsafe = listOf(file("medications", "../evil.json"))
        assertEquals(Invalid.NOT_AYUVO, failed(AllDataImportPlan.plan(manifest(unsafe), entries + "../evil.json")))
        val absolute = listOf(file("medications", "/data/evil.json"))
        assertEquals(Invalid.NOT_AYUVO, failed(AllDataImportPlan.plan(manifest(absolute), entries + "/data/evil.json")))
        val missing = listOf(file("medications", "medications/gone.json"))
        assertEquals(Invalid.NOT_AYUVO, failed(AllDataImportPlan.plan(manifest(missing), entries)))
        val duplicate = listOf(file("medications", "medications/ayuvo-medications.json"), file("medications", "food-diary/d.json"))
        assertEquals(Invalid.NOT_AYUVO, failed(AllDataImportPlan.plan(manifest(duplicate), entries)))
    }

    @Test
    fun unknownSectionsAreIgnoredAndWrongFormatsSkipped() {
        val files = listOf(
            """{"name":"future/x.bin","section":"future_thing","format":"ayuvo-future","bytes":1,"counts":{}}""",
            file("health_data", "health-data/h.zip", format = "ayuvo-health-data-v9")
        )
        val plan = ok(AllDataImportPlan.plan(manifest(files), entries))
        assertEquals(listOf("health_data"), plan.sections.map { it.id })
        assertEquals(SkipReason.UNSUPPORTED, plan.sections.single().skip)
        assertEquals(0, plan.importCount)
    }

    @Test
    fun safeEntryRules() {
        assertTrue(AllDataImportPlan.isSafeEntry("health-records/ayuvo-records-2026-09-22.zip"))
        listOf("", "/abs", "a/../b", "a\\b", "C:x", "a//b", "./a", "dir/", "manifest.json").forEach {
            assertFalse(it, AllDataImportPlan.isSafeEntry(it))
        }
    }

    @Test
    fun roundTripsAnExportWrittenByThisApp() {
        val diary = tmp.newFile("diary.json").apply { writeText("{}") }
        val meds = tmp.newFile("meds.json").apply { writeText("{}") }
        val out = tmp.newFile("export.zip")
        out.outputStream().use {
            AllDataExportArchive.write(
                out = it,
                sections = listOf(
                    AllDataExportArchive.Section("food_diary", "ayuvo-food-diary", "food-diary/d.json", "Food", mapOf("food_entries" to 3L), diary),
                    AllDataExportArchive.Section("medications", "ayuvo-medications", "medications/m.json", "Meds", mapOf("medications" to 1L), meds)
                ),
                skipped = mapOf("health_data" to AllDataExportArchive.REASON_EMPTY),
                createdAt = Instant.parse("2026-09-22T10:15:30Z"),
                appVersion = "1.0"
            )
        }
        val (manifestText, names) = ZipFile(out).use { zip ->
            val text = zip.getInputStream(zip.getEntry("manifest.json")).readBytes().toString(Charsets.UTF_8)
            text to zip.entries().toList().map { it.name }.toSet()
        }
        val plan = ok(AllDataImportPlan.plan(manifestText, names))
        assertEquals(listOf("food_diary", "medications"), plan.sections.map { it.id })
        assertTrue(plan.sections.all { it.imports })
        assertEquals(mapOf("food_entries" to 3L), plan.sections[0].counts)
    }

    @Test
    fun readsAnIphoneManifestWithoutAndroidExtras() {
        // iOS AllDataExport.Manifest: no description, sha256 or skipped_reasons.
        val ios = """{"app":"Ayuvo","app_version":"1.4","created_at":"2026-09-22T08:00:00+05:30","files":[
            |{"bytes":120,"counts":{"food_entries":4,"water_entries":2},"format":"ayuvo-food-diary","name":"food-diary/Ayuvo.json","section":"food_diary"}],
            |"format":"ayuvo-all-data","format_version":1,"platform":"ios","skipped":["medications"]}""".trimMargin()
        val plan = ok(AllDataImportPlan.plan(ios, setOf("manifest.json", "food-diary/Ayuvo.json")))
        assertEquals("ios", plan.manifest.platform)
        assertTrue(plan.sections.single().imports)
    }
}
