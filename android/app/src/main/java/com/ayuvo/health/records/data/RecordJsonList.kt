package com.ayuvo.health.records.data

import com.ayuvo.health.records.processing.RecordJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive

/** `record_links.reasons_json`: a compact JSON array of reason strings. */
internal object RecordJsonList {
    fun encode(items: List<String>): String? = if (items.isEmpty()) null else JsonArray(items.map(::JsonPrimitive)).toString()

    fun decode(text: String?): List<String> =
        RecordJson.parseArray(text).orEmpty().mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
}
