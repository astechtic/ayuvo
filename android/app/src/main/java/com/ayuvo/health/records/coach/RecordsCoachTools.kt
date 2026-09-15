package com.ayuvo.health.records.coach

import com.ayuvo.health.records.processing.DateOrder
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.LocalDate

/**
 * The platform tool executor for `records_search`, `records_get` and `records_observation_series`
 * (docs §26–§28) for one Coach message. Payloads come from [RecordsCoach]; this class adds what the
 * reference leaves to platforms: `errors.unavailable` when access was turned off (or no records
 * remain) mid-turn, the §30 online-send confirmation before the first call, and the turn's
 * record refs.
 */
class RecordsCoachTools(
    val contract: RecordsCoachContract,
    private val data: RecordsCoachData,
    val selectedIds: List<String> = emptyList(),
    private val today: () -> LocalDate = { LocalDate.now() },
    private val dateOrder: DateOrder = DateOrder.DMY,
    /** Re-checked before every call: access still enabled and ≥ 1 non-archived record. */
    private val stillAvailable: suspend () -> Boolean = { true },
    /** §30: asked once before the first records tool call; false = Cancel. May throw [RecordsCoachSwitchToOnDevice]. */
    private val beforeFirstCall: (suspend () -> Boolean)? = null
) {
    private var gateAsked = false
    private var gateAllowed = true
    private val calls = mutableListOf<RecordsCoach.ToolCall>()

    val names: List<String> get() = contract.names

    fun handles(name: String): Boolean = name in contract.names

    /** Records read successfully during this turn (§26 `record_refs`). */
    val readRefs: List<CoachRecordRef> get() = RecordsCoach.recordRefs(calls)

    suspend fun execute(name: String, args: Map<String, Any?>): String = executeJson(name, toJson(args)).toString()

    suspend fun executeJson(name: String, args: JsonObject): JsonObject {
        val unavailable = RecordsCoach.error(contract.errors.unavailable)
        if (!handles(name)) return unavailable
        if (!gateAsked) {
            gateAsked = true
            gateAllowed = beforeFirstCall?.invoke() ?: true
        }
        if (!gateAllowed) return unavailable
        val result = try {
            if (!stillAvailable()) return unavailable
            when (name) {
                "records_search" -> RecordsCoach.search(data, contract, args, selectedIds, today(), dateOrder)
                "records_get" -> RecordsCoach.get(data, contract, args, selectedIds)
                else -> RecordsCoach.series(data, contract, args, selectedIds)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: RecordsCoachSwitchToOnDevice) {
            throw e
        } catch (e: Exception) {
            unavailable
        }
        calls += RecordsCoach.ToolCall(name, result)
        return result
    }

    companion object {
        private val json = Json { isLenient = false }

        /** Provider arguments (org.json values or plain Kotlin) → JSON with their original types. */
        fun toJson(args: Map<String, Any?>): JsonObject = JsonObject(args.mapValues { (_, v) -> element(v) })

        private fun element(v: Any?): JsonElement = when (v) {
            null -> JsonNull
            is JsonElement -> v
            is String -> JsonPrimitive(v)
            is Boolean -> JsonPrimitive(v)
            is Number -> JsonPrimitive(v)
            is Map<*, *> -> JsonObject(v.entries.associate { (k, x) -> k.toString() to element(x) })
            is List<*> -> kotlinx.serialization.json.JsonArray(v.map(::element))
            else -> runCatching { json.parseToJsonElement(v.toString()) }.getOrElse { JsonPrimitive(v.toString()) }
        }
    }
}

/** Thrown by the §30 prompt when the user picks "Use on-device Coach" in the middle of a turn. */
class RecordsCoachSwitchToOnDevice : RuntimeException("Switch this conversation to on-device Coach")
