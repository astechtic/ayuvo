package com.ayuvo.health.records.processing

import com.ayuvo.health.records.model.RecordType
import com.ayuvo.health.records.model.SplitSegment
import com.ayuvo.health.records.processing.RecordJson.double
import com.ayuvo.health.records.processing.RecordJson.int
import com.ayuvo.health.records.processing.RecordJson.string
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/** `split_proposals.segments_json` (§8): `[{"page_start","page_end","record_type","title","confidence"}]`. */
object SplitSegmentsJson {
    fun encode(segments: List<SplitSegment>): String = JsonArray(segments.map { s ->
        RecordJson.json.parseToJsonElement(
            RecordJson.obj(
                "page_start" to s.pageStart,
                "page_end" to s.pageEnd,
                "record_type" to s.recordType.raw,
                "title" to s.title,
                "confidence" to s.confidence
            )
        )
    }).toString()

    fun decode(text: String?): List<SplitSegment> = RecordJson.parseArray(text).orEmpty().mapNotNull { element ->
        val o = element as? JsonObject ?: return@mapNotNull null
        val start = o.int("page_start") ?: return@mapNotNull null
        val end = o.int("page_end") ?: return@mapNotNull null
        SplitSegment(
            pageStart = start,
            pageEnd = end,
            recordType = RecordType.fromRaw(o.string("record_type")),
            title = o.string("title").orEmpty(),
            confidence = o.double("confidence") ?: 0.0
        )
    }
}
