package com.ayuvo.health.models

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import java.io.File

/**
 * The Kotlin registry must mirror `shared/health/metric_registry.json` (written alongside the
 * iOS port). The JSON comparison is skipped while the file is absent; the structural checks
 * always run.
 */
class HealthDataTypeContractTest {

    @Test
    fun slugsAreUniqueAndUnitsCanonical() {
        val ids = HealthDataType.entries.map { it.id }
        assertEquals("duplicate slugs: ${ids.groupBy { it }.filterValues { it.size > 1 }.keys}", ids.size, ids.toSet().size)
        for (type in HealthDataType.entries) {
            assertTrue("${type.id} has non-snake slug", type.id.matches(Regex("[a-z0-9_]+")))
            assertTrue("${type.id} unit '${type.unit}' is not canonical", type.unit in HealthDataType.canonicalUnits)
            assertEquals(type.id, HealthDataType.byId(type.id)?.id)
        }
        assertEquals(HealthCategory.entries.size, HealthDataType.entries.map { it.category }.toSet().size)
    }

    @Test
    fun sdkTypesCarryPermissionRecordAndTier() {
        for (type in HealthDataType.sdkTypes) {
            assertNotNull("${type.id} needs a permission", type.hcPermission)
            assertTrue(type.hcPermission!!.startsWith("android.permission.health.READ_"))
            assertNotNull("${type.id} needs a record class", type.hcRecord)
            assertNotNull("${type.id} needs an android_tier", type.androidTier)
        }
        assertEquals(41, HealthDataType.sdkTypes.map { it.hcRecord }.toSet().size)
        assertTrue(HealthDataType.coreTypes.containsAll(listOf(HealthDataType.STEPS, HealthDataType.HEART_RATE, HealthDataType.SLEEP, HealthDataType.WEIGHT)))
        assertTrue(HealthDataType.NUTRITION_RECORD.exported.not())
        assertTrue(HealthDataType.entries.filter { it.isVirtualDietary }.none { it.exported })
        assertTrue(HealthDataType.HYDRATION.exported)
    }

    @Test
    fun registryMatchesSharedJsonWhenPresent() {
        val file = listOf("../../shared/health/metric_registry.json", "../shared/health/metric_registry.json", "shared/health/metric_registry.json")
            .map(::File).firstOrNull { it.exists() }
        Assume.assumeTrue("shared/health/metric_registry.json not present yet", file != null)
        val root = Json.parseToJsonElement(file!!.readText())
        val entries: List<JsonObject> = when (root) {
            is JsonArray -> root.map { it.jsonObject }
            is JsonObject -> (root["types"] ?: root["metrics"] ?: root["entries"] ?: root["registry"])?.jsonArray?.map { it.jsonObject }
                ?: error("metric_registry.json: no types array found (keys=${root.keys})")
            else -> error("metric_registry.json: unexpected root")
        }
        val json = entries.associateBy { it.str("id") ?: error("entry without id") }
        val kotlin = HealthDataType.entries.associateBy { it.id }
        val mismatches = mutableListOf<String>()
        (json.keys - kotlin.keys).forEach { mismatches += "missing in Kotlin: $it" }
        (kotlin.keys - json.keys).forEach { mismatches += "missing in JSON: $it" }
        for ((id, entry) in json) {
            val type = kotlin[id] ?: continue
            fun check(field: String, expected: String?, actual: String?) {
                if (expected != null && !expected.equals(actual, ignoreCase = true)) mismatches += "$id.$field: json=$expected kotlin=$actual"
            }
            check("category", entry.str("category"), type.category.id)
            check("kind", entry.str("kind"), type.kind.id)
            check("aggregation", entry.str("aggregation"), type.aggregation.name)
            check("unit", entry.str("unit"), type.unit)
            check("day_attribution", entry.str("day_attribution"), type.dayAttribution.id)
            check("hc_record", entry.str("hc_record"), type.hcRecord)
            check("hc_permission", entry.str("hc_permission")?.substringAfterLast('.'), type.hcPermission?.substringAfterLast('.'))
            check("android_tier", entry.str("android_tier"), type.androidTier?.name)
            check("hc_feature_flag", entry.str("hc_feature_flag"), type.hcFeatureFlag?.registryName)
            entry["exported"]?.jsonPrimitive?.booleanOrNull?.let { if (it != type.exported) mismatches += "$id.exported: json=$it kotlin=${type.exported}" }
            entry.str("status")?.let { status ->
                val reserved = status == "reserved"
                if (reserved != type.reserved) mismatches += "$id.status: json=$status kotlin.reserved=${type.reserved}"
            }
            entry["sdk_available"]?.jsonPrimitive?.booleanOrNull?.let { if (it != type.sdkAvailable) mismatches += "$id.sdk_available: json=$it kotlin=${type.sdkAvailable}" }
            val jsonCodes = (entry["category_codes"] as? JsonObject)?.entries?.associate { (code, name) -> code.toInt() to name.jsonPrimitive.content } ?: emptyMap()
            if (jsonCodes != type.categoryCodes) mismatches += "$id.category_codes: json=$jsonCodes kotlin=${type.categoryCodes}"
            val jsonHk = (entry["hk_identifiers"] as? JsonArray)?.map { it.jsonPrimitive.content } ?: emptyList()
            if (jsonHk != type.hkIdentifiers) mismatches += "$id.hk_identifiers: json=$jsonHk kotlin=${type.hkIdentifiers}"
        }
        if (root is JsonObject) {
            root["registry_version"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()?.let {
                if (it != HealthDataType.REGISTRY_VERSION) mismatches += "registry_version: json=$it kotlin=${HealthDataType.REGISTRY_VERSION}"
            }
            (root["units"] as? JsonArray)?.mapNotNull { (it as? JsonObject)?.str("unit") }?.let { units ->
                if (units.toSet() != HealthDataType.canonicalUnits) mismatches += "units: json=${units.toSet()} kotlin=${HealthDataType.canonicalUnits}"
            }
            (root["categories"] as? JsonArray)?.mapNotNull { (it as? JsonObject)?.str("id") }?.let { ids ->
                if (ids != HealthCategory.entries.map { it.id }) mismatches += "categories: json=$ids kotlin=${HealthCategory.entries.map { it.id }}"
            }
            (root["kinds"] as? JsonArray)?.map { it.jsonPrimitive.content }?.let { ids ->
                if (ids.toSet() != HealthKind.entries.map { it.id }.toSet()) mismatches += "kinds: json=$ids kotlin=${HealthKind.entries.map { it.id }}"
            }
            (root["aggregations"] as? JsonArray)?.map { it.jsonPrimitive.content }?.let { ids ->
                if (ids.toSet() != HealthAggregation.entries.map { it.name }.toSet()) mismatches += "aggregations: json=$ids kotlin=${HealthAggregation.entries.map { it.name }}"
            }
            (root["day_attributions"] as? JsonArray)?.map { it.jsonPrimitive.content }?.let { ids ->
                if (ids.toSet() != HealthDayAttribution.entries.map { it.id }.toSet()) mismatches += "day_attributions: json=$ids kotlin=${HealthDayAttribution.entries.map { it.id }}"
            }
        }
        assertTrue("registry mismatches (${mismatches.size}):\n" + mismatches.joinToString("\n"), mismatches.isEmpty())
    }

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    @Suppress("unused")
    private fun JsonElement.asObjectOrNull(): JsonObject? = this as? JsonObject
}
