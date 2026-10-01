package com.ayuvo.health.services.googlehealth

import androidx.health.connect.client.permission.HealthPermission
import com.ayuvo.health.models.HealthDataType
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The bundled map is the shared file, and everything Android resolves from it exists. */
class GoogleHealthMapContractTest {
    private val map = GoogleHealthTestFiles.map

    @Test
    fun assetIsByteIdenticalToSharedMap() {
        val shared = GoogleHealthTestFiles.shared("google_health_map.json")
        assertNotNull(shared)
        val asset = listOf("src/main/assets/${GoogleHealthMap.ASSET_PATH}", "app/src/main/assets/${GoogleHealthMap.ASSET_PATH}")
            .map(::File).first { it.exists() }
        assertArrayEquals(shared!!.readBytes(), asset.readBytes())
    }

    @Test
    fun everyTypeIdIsARegistrySlug() {
        val missing = map.types.map { it.typeId }.filter { HealthDataType.byId(it) == null }
        assertTrue("not in the registry: $missing", missing.isEmpty())
        assertEquals(map.types.size, map.types.map { it.ghType }.toSet().size)
        for (type in map.types) assertNotNull("${type.ghType} group", map.group(type.scopeGroup))
    }

    @Test
    fun googleOnlySlugsAreNeverHealthConnectTypes() {
        val googleOnly = listOf(
            "active_zone_minutes", "activity_level", "sedentary_period", "calories_in_hr_zone", "swim_lengths",
            "daily_hrv", "daily_blood_oxygen", "nightly_temperature_deviation", "ecg_recording"
        )
        for (slug in googleOnly) {
            val type = HealthDataType.byId(slug)
            assertNotNull(slug, type)
            assertFalse("$slug must not be requested from Health Connect", type!!.sdkAvailable)
        }
        for (entry in map.types.filter { it.typeId in googleOnly }) assertEquals(entry.ghType, null, entry.hc)
    }

    @Test
    fun everyWriteTargetMatchesTheRecordFactory() {
        for (entry in map.types) {
            val hc = entry.hc ?: continue
            val cls = GoogleHealthRecordFactory.recordClass(entry.typeId)
            assertNotNull("${entry.ghType}: no record class", cls)
            assertEquals(entry.ghType, hc.record, cls!!.simpleName)
            assertEquals(entry.ghType, hc.writePermission, HealthPermission.getWritePermission(cls))
        }
    }

    @Test
    fun everyWritePermissionIsDeclaredInTheManifest() {
        val manifest = listOf("src/main/AndroidManifest.xml", "app/src/main/AndroidManifest.xml")
            .map(::File).first { it.exists() }.readText()
        val missing = map.writePermissions.filter { !manifest.contains("android:name=\"$it\"") }
        assertTrue("manifest lacks: $missing", missing.isEmpty())
    }

    @Test
    fun scopesCoverEveryGroupPlusIdentity() {
        val all = map.scopesFor(map.scopeGroups.map { it.id })
        assertTrue(all.containsAll(listOf("openid", "email")))
        assertTrue(all.contains("https://www.googleapis.com/auth/googlehealth.sleep.readonly"))
        val sleep = map.type("sleep")!!
        assertTrue(map.isGranted(sleep, setOf("https://www.googleapis.com/auth/googlehealth.sleep.readonly")))
        assertFalse(map.isGranted(sleep, setOf("https://www.googleapis.com/auth/googlehealth.nutrition.readonly")))
    }
}
