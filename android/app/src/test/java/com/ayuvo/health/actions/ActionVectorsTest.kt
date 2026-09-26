package com.ayuvo.health.actions

import com.ayuvo.health.data.metrics.WeekStart
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.ZoneId

/** Locates shared actions contract files from the Gradle unit-test working directory (android/app). */
object ActionsTestFiles {
    fun shared(relative: String): File? =
        listOf("../../shared/actions/$relative", "../shared/actions/$relative", "shared/actions/$relative")
            .map(::File).firstOrNull { it.exists() }

    val catalog: ActionCatalog by lazy { ActionCatalog.parse(shared("action_catalog.json")!!.readText()) }

    /** Structural JSON equality where numbers compare by value (1 == 1.0). */
    fun same(a: JsonElement?, b: JsonElement?): Boolean = when {
        a == null || b == null -> a == b
        a is JsonNull || b is JsonNull -> a is JsonNull && b is JsonNull
        a is JsonObject && b is JsonObject -> a.keys == b.keys && a.keys.all { same(a[it], b[it]) }
        a is JsonArray && b is JsonArray -> a.size == b.size && a.indices.all { same(a[it], b[it]) }
        a is JsonPrimitive && b is JsonPrimitive -> when {
            a.isString || b.isString -> a.isString && b.isString && a.content == b.content
            a.booleanOrNull != null || b.booleanOrNull != null -> a.booleanOrNull == b.booleanOrNull
            else -> a.doubleOrNull == b.doubleOrNull
        }
        else -> false
    }
}

class ActionVectorsTest {
    private val catalog = ActionsTestFiles.catalog
    private val validator = ActionValidator(catalog)

    private fun cases(file: String, function: String): List<JsonObject> {
        val f = ActionsTestFiles.shared("test-vectors/$file")
        assertNotNull("shared/actions/test-vectors/$file missing", f)
        val doc = Json.parseToJsonElement(f!!.readText()).jsonObject
        assertEquals(function, doc["function"]!!.jsonPrimitive.content)
        return doc["cases"]!!.jsonArray.map { it.jsonObject }
    }

    private fun validationJson(r: ValidationResult): JsonElement = when (r) {
        is ValidationResult.Failed -> ActionValues.toJson(mapOf("ok" to false, "error" to mapOf("code" to r.code.raw, "param" to r.param)))
        is ValidationResult.Ok -> ActionValues.toJson(mapOf("ok" to true, "params" to r.action.params, "confirm" to r.action.confirm, "ai" to r.action.ai))
    }

    private fun validate(input: JsonObject): JsonElement {
        val ctx = input["context"]?.jsonObject
        val source = ActionSource.entries.first { it.raw == (ctx?.get("source")?.jsonPrimitive?.content ?: "app") }
        val prefs = ctx?.get("prefs")?.jsonObject?.mapValues { it.value.jsonPrimitive.content }.orEmpty()
        @Suppress("UNCHECKED_CAST")
        val params = input["params"]?.let { ActionValues.fromJson(it) } as Map<String, Any?>?
        return validationJson(validator.validate(input["id"]!!.jsonPrimitive.content, params, source, prefs))
    }

    private fun check(file: String, function: String, run: (JsonObject) -> JsonElement) {
        val failures = mutableListOf<String>()
        val all = cases(file, function)
        for (c in all) {
            val got = runCatching { run(c["input"]!!.jsonObject) }.getOrElse { failures += "${c["name"]}: threw $it"; continue }
            if (!ActionsTestFiles.same(c["expected"], got)) failures += "${c["name"]}: expected ${c["expected"]} got $got"
        }
        assertTrue("$file: ${failures.size}/${all.size} failed\n" + failures.joinToString("\n"), failures.isEmpty())
    }

    @Test
    fun validationVectors() = check("validation.json", "validate", ::validate)

    @Test
    fun dateRangeVectors() = check("date_ranges.json", "resolve_date_range") { input ->
        val r = ActionDateRange.resolve(
            input["preset"]!!.jsonPrimitive.content,
            input["now_ms"]!!.jsonPrimitive.long,
            ZoneId.of(input["time_zone"]!!.jsonPrimitive.content),
            WeekStart.entries.first { it.raw == input["week_start"]!!.jsonPrimitive.content }
        )
        ActionValues.toJson(mapOf("from_ms" to r.fromMs, "to_ms" to r.toMs))
    }

    @Test
    fun deepLinkVectors() = check("deeplinks.json", "deeplink") { input ->
        val prefs = input["prefs"]?.jsonObject?.mapValues { it.value.jsonPrimitive.content }.orEmpty()
        when (val parsed = ActionDeepLink.parse(input["url"]!!.jsonPrimitive.content)) {
            is ActionDeepLink.Parsed.Failed -> ActionValues.toJson(mapOf(
                "parse" to mapOf("ok" to false, "error" to mapOf("code" to parsed.error.raw)),
                "validate" to null
            ))
            is ActionDeepLink.Parsed.Ok -> ActionValues.toJson(mapOf(
                "parse" to mapOf("ok" to true, "request" to mapOf("id" to parsed.id, "params" to parsed.params)),
                "validate" to validationJson(validator.validate(parsed.id, parsed.params, ActionSource.DEEPLINK, prefs))
            ))
        }
    }

    @Test
    fun computeVectors() = check("compute.json", "compute") { input ->
        val args = input["args"]!!.jsonObject
        fun d(k: String): Double? = (args[k] as? JsonPrimitive)?.doubleOrNull
        fun l(k: String): Long = args[k]!!.jsonPrimitive.long
        val out: Any? = when (val op = input["op"]!!.jsonPrimitive.content) {
            "convert" -> mapOf("value" to ActionMath.convert(catalog, args["family"]!!.jsonPrimitive.content,
                args["from"]!!.jsonPrimitive.content, args["to"]!!.jsonPrimitive.content, d("value")!!))
            "aggregate" -> ActionMath.aggregate(args["entries"]!!.jsonArray.map {
                val o = it.jsonObject
                ActionMath.Sample(o["t_ms"]!!.jsonPrimitive.long, (o["value"] as? JsonPrimitive)?.doubleOrNull)
            }, args["aggregation"]!!.jsonPrimitive.content)
            "water_status" -> ActionMath.waterStatus(l("intake_ml"), d("goal_ml")?.toLong())
            "progress" -> ActionMath.progress(d("value")!!, d("target"))
            "bmi" -> mapOf("bmi" to ActionMath.bmi(d("weight_kg"), d("height_cm")))
            "weight_change" -> ActionMath.weightChange(args["entries"]!!.jsonArray.map {
                ActionMath.WeightPoint(it.jsonObject["t_ms"]!!.jsonPrimitive.long, it.jsonObject["kg"]!!.jsonPrimitive.doubleOrNull!!)
            })
            "set_volume" -> ActionMath.setVolume(args["sets"]!!.jsonArray.map {
                ActionMath.SetWork(it.jsonObject["weight_kg"]!!.jsonPrimitive.doubleOrNull!!, it.jsonObject["reps"]!!.jsonPrimitive.long.toInt())
            })
            "fasting_progress" -> ActionMath.fastingProgress(l("started_ms"), l("goal_minutes"), l("now_ms"))
            else -> error("unknown op $op")
        }
        ActionValues.toJson(out)
    }

    @Test
    fun bundledCatalogIsByteIdentical() {
        val shared = ActionsTestFiles.shared("action_catalog.json")!!.readBytes()
        val asset = listOf("src/main/assets/actions/action_catalog.json", "app/src/main/assets/actions/action_catalog.json")
            .map(::File).first { it.exists() }.readBytes()
        assertTrue("assets/actions/action_catalog.json must be a byte copy of shared/actions/action_catalog.json", shared.contentEquals(asset))
    }

    @Test
    fun catalogParses() {
        assertEquals(44, catalog.actions.size)
        assertTrue(catalog.coachReadActions.all { it.coachTool != null })
        assertTrue(catalog.coachProposeActions.all { it.kind == ActionKind.SET })
        assertTrue(catalog.coachProposeActions.none { it.domain == "medications" || it.domain == "goals" })
    }
}
