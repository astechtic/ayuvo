package com.ayuvo.health.records.processing

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Compact JSON for `value_json`, `blocks_json` and `segments_json`. kotlinx.serialization (not
 * org.json) so the pure pipeline runs unchanged in JVM unit tests.
 */
object RecordJson {
    val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun parseObject(text: String?): JsonObject? =
        text?.let { runCatching { json.parseToJsonElement(it) as? JsonObject }.getOrNull() }

    fun parseArray(text: String?): JsonArray? =
        text?.let { runCatching { json.parseToJsonElement(it) as? JsonArray }.getOrNull() }

    fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull
    fun JsonObject.double(key: String): Double? = (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.doubleOrNull
    fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.intOrNull

    /** Object with keys in insertion order; null values are written as JSON null. */
    fun obj(vararg pairs: Pair<String, Any?>): String = JsonObject(pairs.associate { (k, v) -> k to element(v) }).toString()

    fun element(value: Any?): JsonElement = when (value) {
        null -> JsonNull
        is JsonElement -> value
        is String -> JsonPrimitive(value)
        is Number -> JsonPrimitive(value)
        is Boolean -> JsonPrimitive(value)
        is List<*> -> JsonArray(value.map(::element))
        is Map<*, *> -> JsonObject(value.entries.associate { (k, v) -> k.toString() to element(v) })
        else -> JsonPrimitive(value.toString())
    }

    fun primitiveString(element: JsonElement?): String? = (element as? JsonPrimitive)?.takeIf { it !is JsonNull }?.let {
        runCatching { it.jsonPrimitive.contentOrNull }.getOrNull()
    }
}
