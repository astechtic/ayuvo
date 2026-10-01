package com.ayuvo.health.services.googlehealth

import com.ayuvo.health.data.health.HealthDayKeys
import com.ayuvo.health.data.health.HealthSampleRow
import com.ayuvo.health.data.health.HealthSleepCodes
import com.ayuvo.health.data.health.HealthSourceRow
import com.ayuvo.health.models.HealthDataType
import com.ayuvo.health.models.HealthDayAttribution
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.math.abs

/** One mapped data point: the canonical row (id `gh:<point>`) plus sleep stage rows. */
data class GoogleHealthMapped(
    val row: HealthSampleRow,
    val extraRows: List<HealthSampleRow> = emptyList(),
    val source: HealthSourceRow? = null,
    /** `dataSource` of the point, kept for the echo guard. */
    val dataSource: JsonObject? = null
) {
    val allRows: List<HealthSampleRow> get() = listOf(row) + extraRows
}

/**
 * Google Health API data point → `health_samples` rows, driven by [GoogleHealthMap]
 * (docs/google-health.md §3, `row_rules` / `converters` in the map). Pure Kotlin; the shared vectors in
 * `shared/health/test-vectors/google_health/mapping.json` pin the output (GoogleHealthMapperTest).
 */
class GoogleHealthMapper(
    private val map: GoogleHealthMap,
    private val zone: () -> ZoneId = { ZoneId.systemDefault() }
) {

    /** Null when the point has no usable time (malformed or a shape this build does not know). */
    fun map(entry: GoogleHealthMap.TypeEntry, point: JsonObject, nowMs: Long): GoogleHealthMapped? {
        val pointId = point.str("name")?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: return null
        val body = point[entry.union] as? JsonObject ?: return null
        val dataSource = point["dataSource"] as? JsonObject
        val timing = timing(entry, body) ?: return null
        val type = HealthDataType.byId(entry.typeId)
        val z = zone()
        val base = HealthSampleRow(
            id = ID_PREFIX + pointId,
            typeId = entry.typeId,
            startMs = timing.startMs,
            endMs = timing.endMs,
            startOffsetS = timing.startOffsetS,
            endOffsetS = timing.endOffsetS,
            localDay = HealthDayKeys.localDay(
                type?.dayAttribution ?: HealthDayAttribution.START,
                timing.startMs, timing.endMs, timing.startOffsetS, timing.endOffsetS, z
            ),
            unit = type?.unit ?: "count",
            count = 1,
            sourceId = sourceId(dataSource),
            device = device(dataSource),
            recordingMethod = recordingMethod(dataSource),
            clientRecordId = clientRecordId(pointId),
            origin = HealthSampleRow.ORIGIN_GOOGLE_HEALTH,
            updatedMs = nowMs
        )
        val gh = buildJsonObject {
            put("type", entry.ghType)
            put("platform", dataSource?.str("platform")?.let(::JsonPrimitive) ?: JsonNull)
        }
        val source = sourceRow(base.sourceId, dataSource, nowMs)
        return when (entry.converter) {
            "sleep" -> sleep(entry, body, base, gh)
            "exercise" -> GoogleHealthMapped(exercise(body, base, gh))
            "nutrition_log" -> GoogleHealthMapped(nutrition(entry, body, base, gh))
            else -> GoogleHealthMapped(simple(entry, body, base, gh))
        }.copy(source = source, dataSource = dataSource)
    }

    // -- Converters --------------------------------------------------------------------------------

    private fun simple(entry: GoogleHealthMap.TypeEntry, body: JsonObject, base: HealthSampleRow, gh: JsonObject): HealthSampleRow {
        val extra = buildJsonObject {
            put("gh", gh)
            for ((key, path) in entry.extra) {
                if (path in entry.dropFields) continue
                body.at(path)?.takeIf { it !is JsonNull }?.let { put(key, it) }
            }
        }
        return base.copy(
            value = entry.value?.let { value(body, it) },
            value2 = entry.value2?.let { value(body, it) },
            value3 = entry.value3?.let { value(body, it) },
            categoryValue = entry.category?.let { spec ->
                spec.path?.let { body.at(it)?.primitiveContent() }?.let { spec.map[it] } ?: spec.default
            },
            extraJson = extra.toString()
        )
    }

    private fun exercise(body: JsonObject, base: HealthSampleRow, gh: JsonObject): HealthSampleRow {
        val type = body.str("exerciseType")
        val meta = body["exerciseMetadata"] as? JsonObject
        val extra = buildJsonObject {
            put("gh", gh)
            put("activity_type", type)
            meta?.num("totalCaloriesKcal")?.let { put("energy_kcal", it) }
            meta?.num("totalDistanceMeters")?.let { put("distance_m", it) }
            meta?.num("averageHeartRateBeatsPerMinute")?.let { put("avg_hr_bpm", it) }
            meta?.num("maxHeartRateBeatsPerMinute")?.let { put("max_hr_bpm", it) }
            meta?.num("totalElevationGainMeters")?.let { put("elevation_gain_m", it) }
            (meta?.get("activeDuration") ?: body["activeDuration"])?.primitiveContent()?.let(::durationSeconds)
                ?.let { put("active_time_s", it) }
            put("segments", JsonNull)
            put("laps", (body["splitSummaries"] as? JsonArray)?.size ?: 0)
            put("has_route", false)
            put("platform", "google_health")
        }
        return base.copy(value = base.durationS, categoryValue = 0, title = type, extraJson = extra.toString())
    }

    private fun sleep(entry: GoogleHealthMap.TypeEntry, body: JsonObject, base: HealthSampleRow, gh: JsonObject): GoogleHealthMapped {
        val session = base.copy(
            value = base.durationS,
            categoryValue = HealthSleepCodes.IN_BED,
            title = body.str("sleepType"),
            extraJson = buildJsonObject { put("gh", gh) }.toString()
        )
        val stageExtra = buildJsonObject {
            put("gh", gh)
            put("session_id", base.id)
        }.toString()
        fun stageRow(index: Int, startMs: Long, endMs: Long, code: Int) = base.copy(
            id = "${base.id}:$index",
            startMs = startMs,
            endMs = endMs,
            value = (endMs - startMs).coerceAtLeast(0L) / 1000.0,
            categoryValue = code,
            clientRecordId = null,
            extraJson = stageExtra
        )
        val stages = mutableListOf<HealthSampleRow>()
        (body["sleepStages"] as? JsonArray)?.forEach { element ->
            val stage = element as? JsonObject ?: return@forEach
            val start = stage.str("startTime")?.let(::instantMs) ?: return@forEach
            val end = stage.str("endTime")?.let(::instantMs) ?: return@forEach
            val code = entry.stageMap[stage.str("stage")] ?: HealthSleepCodes.ASLEEP_UNSPECIFIED
            stages += stageRow(stages.size, start, end, code)
        }
        if (stages.isEmpty()) stages += stageRow(0, base.startMs, base.endMs, HealthSleepCodes.ASLEEP_UNSPECIFIED)
        (body["outOfBedSegments"] as? JsonArray)?.forEach { element ->
            val segment = element as? JsonObject ?: return@forEach
            val start = segment.str("startTime")?.let(::instantMs) ?: return@forEach
            val end = segment.str("endTime")?.let(::instantMs) ?: return@forEach
            stages += stageRow(stages.size, start, end, HealthSleepCodes.OUT_OF_BED)
        }
        return GoogleHealthMapped(session, stages)
    }

    private fun nutrition(entry: GoogleHealthMap.TypeEntry, body: JsonObject, base: HealthSampleRow, gh: JsonObject): HealthSampleRow {
        val energy = (body["totalEnergyQuantity"] as? JsonObject)?.let { q ->
            val v = q.num("value") ?: return@let null
            if (q.str("unit") == "KILOJOULE") v / 4.184 else v
        }
        val nutrients = LinkedHashMap<String, Double>()
        (body["nutrients"] as? JsonArray)?.forEach { element ->
            val n = element as? JsonObject ?: return@forEach
            val name = (n.str("nutrient") ?: n.str("type") ?: n.str("name"))?.uppercase() ?: return@forEach
            val slug = NUTRIENT_SLUGS[name] ?: return@forEach
            val quantity = (n["quantity"] ?: n["amount"]) as? JsonObject
            val raw = quantity?.num("value") ?: n.num("value") ?: return@forEach
            val unit = quantity?.str("unit") ?: n.str("unit")
            val canonical = HealthDataType.byId(slug)?.unit ?: return@forEach
            convertMass(raw, unit, canonical)?.let { nutrients[slug] = it }
        }
        val title = (body["foodConsumed"] as? JsonArray)?.firstNotNullOfOrNull { (it as? JsonObject)?.let { f -> f.str("name") ?: f.str("displayName") } }
            ?: (body["foodConsumed"] as? JsonObject)?.str("name")
        val extra = buildJsonObject {
            put("gh", gh)
            nutrients.forEach { (slug, v) -> put(slug, v) }
        }
        return base.copy(
            value = energy,
            value2 = nutrients["dietary_protein"],
            value3 = nutrients["dietary_carbohydrates"],
            categoryValue = entry.mealMap[body.str("mealType")] ?: 0,
            title = title,
            extraJson = extra.toString()
        )
    }

    // -- Values ------------------------------------------------------------------------------------

    private fun value(body: JsonObject, spec: GoogleHealthMap.ValueSpec): Double? {
        val element = body.at(spec.path) ?: return null
        val raw = when (spec.converter) {
            "duration_seconds" -> element.primitiveContent()?.let(::durationSeconds)
            "weight_quantity_kg" -> quantity(element, WEIGHT_UNITS)
            "length_quantity_m" -> quantity(element, LENGTH_UNITS)
            "volume_quantity_ml" -> quantity(element, VOLUME_UNITS)
            else -> element.primitiveContent()?.toDoubleOrNull()
        } ?: return null
        return spec.scale?.let { raw * it } ?: raw
    }

    /** `{value, unit}` → canonical unit; an unspecified or unknown unit keeps the value. */
    private fun quantity(element: JsonElement, factors: Map<String, Double>): Double? {
        val q = element as? JsonObject ?: return element.primitiveContent()?.toDoubleOrNull()
        val v = q.num("value") ?: return null
        return v * (factors[q.str("unit")] ?: 1.0)
    }

    // -- Timing ------------------------------------------------------------------------------------

    private data class Timing(val startMs: Long, val endMs: Long, val startOffsetS: Int?, val endOffsetS: Int?)

    private fun timing(entry: GoogleHealthMap.TypeEntry, body: JsonObject): Timing? = when (entry.time) {
        "sample" -> (body["sampleTime"] as? JsonObject)?.let { t ->
            val ms = t.str("physicalTime")?.let(::instantMs) ?: return@let null
            val offset = t.str("utcOffset")?.let(::offsetSeconds)
            Timing(ms, ms, offset, offset)
        }
        "interval" -> (body["interval"] as? JsonObject)?.let(::intervalTiming)
        "date" -> (body["date"] as? JsonObject)?.let { d ->
            val year = d.num("year")?.toInt() ?: return@let null
            val month = d.num("month")?.toInt() ?: return@let null
            val day = d.num("day")?.toInt() ?: return@let null
            val date = runCatching { LocalDate.of(year, month, day) }.getOrNull() ?: return@let null
            val z = zone()
            val start = date.atStartOfDay(z).toInstant().toEpochMilli()
            val end = date.plusDays(1).atStartOfDay(z).toInstant().toEpochMilli() - 1
            Timing(start, end, null, null)
        }
        "session" -> {
            val interval = (body["interval"] as? JsonObject)?.let(::intervalTiming)
            val start = body.str("sessionStartTime")?.let(::instantMs) ?: interval?.startMs
            val end = body.str("sessionEndTime")?.let(::instantMs) ?: interval?.endMs
            if (start == null || end == null) null else Timing(start, end, interval?.startOffsetS, interval?.endOffsetS)
        }
        else -> null
    }

    private fun intervalTiming(i: JsonObject): Timing? {
        val start = i.str("startTime")?.let(::instantMs) ?: return null
        val end = i.str("endTime")?.let(::instantMs) ?: return null
        return Timing(start, end, i.str("startUtcOffset")?.let(::offsetSeconds), i.str("endUtcOffset")?.let(::offsetSeconds))
    }

    // -- Row rules ---------------------------------------------------------------------------------

    private fun sourceId(ds: JsonObject?): String {
        val app = (ds?.get("application") as? JsonObject)?.str("packageName")?.takeIf { it.isNotBlank() }
        return SOURCE_PREFIX + (app ?: ds?.str("dataSourceFamily")?.takeIf { it.isNotBlank() } ?: "unknown")
    }

    private fun device(ds: JsonObject?): String? {
        val d = ds?.get("device") as? JsonObject ?: return null
        return listOfNotNull(d.str("manufacturer"), d.str("model")).joinToString(" ").trim().ifBlank { null }
    }

    private fun recordingMethod(ds: JsonObject?): Int = when (ds?.str("recordingMethod")) {
        "ACTIVELY_RECORDED" -> 1
        "PASSIVELY_RECORDED" -> 2
        else -> 0
    }

    private fun sourceRow(sourceId: String, ds: JsonObject?, nowMs: Long): HealthSourceRow {
        val d = ds?.get("device") as? JsonObject
        return HealthSourceRow(
            id = sourceId,
            name = ds?.str("name")?.takeIf { it.isNotBlank() } ?: sourceId.removePrefix(SOURCE_PREFIX),
            deviceModel = d?.str("model"),
            lastSeenMs = nowMs
        )
    }

    // -- Filters and the echo guard -----------------------------------------------------------------

    /** The map's AIP-160 filter with its `{from_*}` placeholder filled for [fromMs]. */
    fun filter(entry: GoogleHealthMap.TypeEntry, fromMs: Long): String {
        val instant = Instant.ofEpochMilli(fromMs)
        val local = instant.atZone(zone())
        return entry.filter
            .replace("{from_rfc3339}", RFC3339.format(instant.atOffset(ZoneOffset.UTC)))
            .replace("{from_civil}", CIVIL.format(local.toLocalDateTime()))
            .replace("{from_date}", local.toLocalDate().toString())
    }

    /**
     * True when the point came from the platform store this device writes to (the package list, or
     * `dataSource.platform` equal to [runningOs] = "ANDROID" here), so writing it back would echo it.
     */
    fun isEcho(dataSource: JsonObject?, runningOs: String): Boolean {
        val guard = map.echoGuard
        val pkg = (dataSource?.get("application") as? JsonObject)?.str("packageName")
        if (pkg != null && pkg in guard.skipMirrorWhenPackageIn) return true
        val platform = dataSource?.str("platform")
        return guard.skipMirrorWhenPlatformAndSameOs && platform != null && platform.equals(runningOs, ignoreCase = true)
    }

    /** An origin-0 row of the same type within the window and value epsilon already holds this sample. */
    fun isDuplicateOfPlatform(row: HealthSampleRow, platformRows: List<HealthSampleRow>): Boolean {
        val guard = map.echoGuard
        return platformRows.any { other ->
            other.origin == HealthSampleRow.ORIGIN_PLATFORM && !other.deleted && other.typeId == row.typeId &&
                abs(other.startMs - row.startMs) <= guard.duplicateWindowMs &&
                abs(other.endMs - row.endMs) <= guard.duplicateWindowMs &&
                valuesClose(other.value, row.value, guard.duplicateValueEpsilonRatio)
        }
    }

    private fun valuesClose(a: Double?, b: Double?, ratio: Double): Boolean {
        if (a == null || b == null) return a == b
        val scale = maxOf(abs(a), abs(b))
        return scale == 0.0 || abs(a - b) <= ratio * scale
    }

    companion object {
        const val ID_PREFIX = "gh:"
        const val SOURCE_PREFIX = "google_health:"
        const val CLIENT_RECORD_PREFIX = "ayuvo_gh_"
        const val RUNNING_OS = "ANDROID"

        private val RFC3339: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'")
        private val CIVIL: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")

        private val WEIGHT_UNITS = mapOf("KILOGRAM" to 1.0, "POUND" to 0.45359237, "GRAM" to 0.001)
        private val LENGTH_UNITS = mapOf("METER" to 1.0, "CENTIMETER" to 0.01, "INCH" to 0.0254, "FOOT" to 0.3048)
        private val VOLUME_UNITS = mapOf("MILLILITER" to 1.0, "LITER" to 1000.0, "US_FLUID_OUNCE" to 29.5735295625, "US_CUP" to 236.5882365)

        /** Google nutrient enum → registry `dietary_*` slug (unknown nutrients are dropped). */
        private val NUTRIENT_SLUGS = mapOf(
            "PROTEIN" to "dietary_protein",
            "TOTAL_CARBOHYDRATE" to "dietary_carbohydrates", "CARBOHYDRATE" to "dietary_carbohydrates", "CARBS" to "dietary_carbohydrates",
            "TOTAL_FAT" to "dietary_fat_total", "FAT" to "dietary_fat_total",
            "SATURATED_FAT" to "dietary_fat_saturated",
            "MONOUNSATURATED_FAT" to "dietary_fat_monounsaturated",
            "POLYUNSATURATED_FAT" to "dietary_fat_polyunsaturated",
            "CHOLESTEROL" to "dietary_cholesterol",
            "DIETARY_FIBER" to "dietary_fiber", "FIBER" to "dietary_fiber",
            "SUGAR" to "dietary_sugar", "SUGARS" to "dietary_sugar", "TOTAL_SUGARS" to "dietary_sugar",
            "SODIUM" to "dietary_sodium",
            "POTASSIUM" to "dietary_potassium",
            "CALCIUM" to "dietary_calcium",
            "IRON" to "dietary_iron",
            "MAGNESIUM" to "dietary_magnesium",
            "ZINC" to "dietary_zinc",
            "PHOSPHORUS" to "dietary_phosphorus",
            "VITAMIN_A" to "dietary_vitamin_a",
            "VITAMIN_B6" to "dietary_vitamin_b6",
            "VITAMIN_B12" to "dietary_vitamin_b12",
            "VITAMIN_C" to "dietary_vitamin_c",
            "VITAMIN_D" to "dietary_vitamin_d",
            "VITAMIN_E" to "dietary_vitamin_e",
            "VITAMIN_K" to "dietary_vitamin_k",
            "THIAMIN" to "dietary_thiamin",
            "RIBOFLAVIN" to "dietary_riboflavin",
            "NIACIN" to "dietary_niacin",
            "FOLATE" to "dietary_folate",
            "BIOTIN" to "dietary_biotin",
            "PANTOTHENIC_ACID" to "dietary_pantothenic_acid",
            "CAFFEINE" to "dietary_caffeine"
        )

        private val MASS_TO_GRAMS = mapOf("GRAM" to 1.0, "MILLIGRAM" to 1e-3, "MICROGRAM" to 1e-6, "KILOGRAM" to 1e3)
        private val CANONICAL_GRAMS = mapOf("g" to 1.0, "mg" to 1e-3, "mcg" to 1e-6)

        /** Mass in [unit] → the slug's canonical unit; an absent unit is taken as already canonical. */
        internal fun convertMass(value: Double, unit: String?, canonical: String): Double? {
            val target = CANONICAL_GRAMS[canonical] ?: return null
            val from = unit?.let { MASS_TO_GRAMS[it] } ?: return value
            return value * from / target
        }

        fun clientRecordId(pointId: String): String = CLIENT_RECORD_PREFIX + pointId

        /** `"123.5s"` → 123.5. */
        fun durationSeconds(raw: String): Double? = raw.trim().removeSuffix("s").toDoubleOrNull()

        /** `"19800s"` → 19800. */
        fun offsetSeconds(raw: String): Int? = durationSeconds(raw)?.toInt()

        fun instantMs(raw: String): Long? = runCatching { Instant.parse(raw).toEpochMilli() }.getOrNull()
            ?: runCatching { OffsetDateTime.parse(raw).toInstant().toEpochMilli() }.getOrNull()

        private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

        private fun JsonObject.num(key: String): Double? = this[key]?.primitiveContent()?.toDoubleOrNull()

        private fun JsonElement.primitiveContent(): String? = (this as? JsonPrimitive)?.contentOrNull

        /** Dotted path lookup (`exerciseMetadata.totalCaloriesKcal`). */
        private fun JsonObject.at(path: String): JsonElement? {
            var current: JsonElement = this
            for (part in path.split('.')) {
                current = (current as? JsonObject)?.get(part) ?: return null
            }
            return current
        }
    }
}
