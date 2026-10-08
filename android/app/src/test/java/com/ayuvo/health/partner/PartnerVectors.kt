package com.ayuvo.health.partner

import com.ayuvo.health.partner.logic.LedgerCurrent
import com.ayuvo.health.partner.logic.LedgerRow
import com.ayuvo.health.partner.logic.LedgerScope
import com.ayuvo.health.partner.logic.MappedRecord
import com.ayuvo.health.partner.logic.PartnerCatalog
import com.ayuvo.health.partner.logic.PartnerEnvelopes
import com.ayuvo.health.partner.logic.PartnerJson
import com.ayuvo.health.partner.logic.PartnerKdf
import com.ayuvo.health.partner.logic.PartnerLedger
import com.ayuvo.health.partner.logic.PartnerMappers
import com.ayuvo.health.partner.logic.PartnerMerge
import com.ayuvo.health.partner.logic.PartnerPackage
import com.ayuvo.health.partner.logic.PartnerProtocol
import com.ayuvo.health.partner.logic.PartnerQr
import com.ayuvo.health.partner.logic.PartnerSummary
import com.ayuvo.health.partner.logic.PackageEntry
import com.ayuvo.health.partner.logic.ReceivedGrant
import com.ayuvo.health.partner.logic.RecordKey
import com.ayuvo.health.partner.logic.StoredRecord
import com.ayuvo.health.records.processing.RecordsVectors
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import java.io.File

/** Locates shared partner contract files from the Gradle unit-test working directory (android/app). */
object PartnerTestFiles {
    fun shared(relative: String): File? =
        listOf("../../shared/partner/$relative", "../shared/partner/$relative", "shared/partner/$relative")
            .map(::File).firstOrNull { it.exists() }

    /** Installs the shared catalogs (the assets copies are checked against them by PartnerAssetsContractTest). */
    fun install(): PartnerCatalog = PartnerCatalog.installFromText(
        shared("record_types.json")!!.readText(), shared("protocol.json")!!.readText()
    )
}

/**
 * Runs `shared/partner/test-vectors/<file>` through the Kotlin ports (dispatch mirrors the reference
 * `run_case`) and compares with the expected JSON using the records comparison rules (numbers by value,
 * key order ignored, array order kept).
 */
object PartnerVectors {
    data class Outcome(val file: String, val passed: Int, val total: Int, val failures: List<String>)

    fun cases(file: String): Pair<String, List<JsonObject>> {
        val f = PartnerTestFiles.shared("test-vectors/$file") ?: run { fail("shared/partner/test-vectors/$file missing"); error("") }
        val root = PartnerJson.parse(f.readText()) as JsonObject
        val function = PartnerJson.str(root["function"]) ?: error("$file has no function")
        return function to (root["cases"] as JsonArray).map { it as JsonObject }
    }

    fun run(file: String): Outcome {
        PartnerTestFiles.install()
        val (function, cases) = cases(file)
        val failures = mutableListOf<String>()
        var passed = 0
        for (case in cases) {
            val name = PartnerJson.str(case["name"]) ?: "?"
            val expected = case["expected"]!!
            val actual = try {
                runCase(function, case["input"] as JsonObject)
            } catch (e: Throwable) {
                failures += "$name: threw ${e.javaClass.simpleName}: ${e.message}\n    ${e.stackTrace.take(5).joinToString("\n    ")}"
                continue
            }
            val diff = RecordsVectors.diff(expected, actual, "$")
            if (diff == null) passed++ else failures += "$name: $diff\n    expected=${expected.toString().take(1500)}\n    actual  =${actual.toString().take(1500)}"
        }
        return Outcome(file, passed, cases.size, failures)
    }

    fun assertAll(file: String) {
        val o = run(file)
        println("VECTORS partner/${o.file}: ${o.passed}/${o.total}")
        assertTrue("${o.file}: ${o.passed}/${o.total} passed\n" + o.failures.joinToString("\n"), o.failures.isEmpty() && o.total > 0)
    }

    // -- input decoding --------------------------------------------------------------------------------

    private fun objects(e: JsonElement?): List<JsonObject> = (e as? JsonArray).orEmpty().map { it as JsonObject }
    private fun strings(e: JsonElement?): List<String> = (e as? JsonArray).orEmpty().mapNotNull { PartnerJson.str(it) }
    private fun JsonObject.s(k: String) = PartnerJson.str(this[k])
    private fun JsonObject.l(k: String) = PartnerJson.long(this[k])

    fun existing(e: JsonElement?): Map<RecordKey, Long> {
        val m = LinkedHashMap<RecordKey, Long>()
        for (r in objects(e)) m[RecordKey(r.s("type")!!, r.s("record_id")!!)] = r.l("rev")!!
        return m
    }

    fun ledgerRow(o: JsonObject) = LedgerRow(
        o.s("type")!!, o.s("record_id")!!, o.s("category")!!, o.s("day"), o.s("content_hash")!!, o.l("rev")!!,
        PartnerJson.truthy(o["deleted"])
    )

    private fun ledger(e: JsonElement?) = objects(e).map(::ledgerRow)

    fun runCase(function: String, input: JsonObject): JsonElement = when (function) {
        "qr" -> when (input.s("op")) {
            "encode" -> PartnerJson.obj("text" to PartnerQr.encode(input["payload"] as JsonObject))
            "parse" -> PartnerQr.parse(input.s("text"), input.l("now_ms")!!, input.s("self_device_id")).toJson()
            else -> error("qr op")
        }
        "kdf" -> when (input.s("op")) {
            "psk" -> PartnerJson.obj("psk" to PartnerKdf.hex(PartnerKdf.pairingPsk(input.s("token")!!)))
            "sas" -> PartnerJson.obj("sas" to PartnerKdf.sasCode(PartnerKdf.unhex(input.s("handshake_hash")!!)))
            "fingerprint" -> {
                val fp = PartnerKdf.fingerprint(input.s("x25519")!!, input.s("ed25519")!!)
                PartnerJson.obj("fingerprint" to fp, "display" to PartnerKdf.formatFingerprint(fp))
            }
            else -> error("kdf op")
        }
        "envelope_validate" -> PartnerEnvelopes.validate(input["envelope"], input.l("now_ms")!!).toJson()
        "merge_apply" -> PartnerMerge.apply(
            existing(input["existing"]), input["batch"] as JsonObject, input.l("cursor")!!, strings(input["granted"]), input.l("now_ms")!!
        ).toJson()
        "grants_received" -> PartnerJson.obj(
            "grants" to PartnerMerge.grantsReceivedUpdate(
                objects(input["previous"]).map { ReceivedGrant(it.s("category")!!, PartnerJson.truthy(it["granted"]), it.l("revoked_ms")) },
                strings(input["granted_now"]), input.l("now_ms")!!
            ).map { it.toJson() }
        )
        "retention_prune" -> PartnerJson.obj(
            "delete" to PartnerMerge.retentionPrune(
                objects(input["rows"]).map { Triple(it.s("type")!!, it.s("record_id")!!, it.l("ts_ms")) }, input.l("now_ms")!!
            ).map { it.toJson() }
        )
        "ledger" -> when (input.s("op")) {
            "refresh" -> PartnerLedger.refresh(
                ledger(input["ledger"]), input.l("rev")!!,
                objects(input["current"]).map { LedgerCurrent(it.s("type")!!, it.s("record_id")!!, it.s("category")!!, it.s("day"), it.s("content_hash")!!) },
                objects(input["scopes"]).map { LedgerScope(it.s("type")!!, it.s("day_from")) }
            ).toJson()
            "prune" -> PartnerJson.obj("ledger" to PartnerLedger.prune(ledger(input["ledger"]), input.s("day_from")!!).map { it.toJson() })
            "regrant" -> PartnerLedger.regrant(ledger(input["ledger"]), input.l("rev")!!, input.s("category")!!).toJson()
            "delta" -> PartnerLedger.delta(
                ledger(input["ledger"]), input.l("rev")!!, input.l("cursor")!!, strings(input["grants"]),
                input.l("limit")?.toInt() ?: PartnerCatalog.current.batchMax
            ).toJson()
            else -> error("ledger op")
        }
        "map" -> PartnerJson.obj(
            "envelope" to MappedRecord.toJsonOrNull(
                when (input.s("source")) {
                    "rollup" -> PartnerMappers.rollup(input["row"] as JsonObject)
                    "hourly" -> PartnerMappers.hourly(input["row"] as JsonObject)
                    "sample" -> PartnerMappers.sample(input["row"] as JsonObject, input.l("now_ms")!!)
                    "analytics" -> PartnerMappers.analytics(input["row"] as JsonObject)
                    "medication" -> PartnerMappers.medication(input["row"] as JsonObject)
                    "schedule" -> PartnerMappers.schedule(input["row"] as JsonObject)
                    "dose_log" -> PartnerMappers.doseLog(input["row"] as JsonObject, input.s("local_day")!!)
                    "report" -> PartnerMappers.reportOverview(
                        input["record"] as JsonObject, objects(input["fields"]), objects(input["highlights"]), objects(input["observations"])
                    )
                    else -> error("map source")
                }
            )
        )
        "message_validate" -> PartnerProtocol.validate(input["message"]).toJson()
        "session" -> PartnerProtocol.sessionScript(objects(input["inbound"])).toJson()
        "package" -> when (input.s("op")) {
            "validate" -> PartnerPackage.validate(
                input["manifest"],
                objects(input["entries"]).map { PackageEntry(it.s("name")!!, it.s("sha256")) },
                PartnerJson.truthy(input["signature_ok"]), strings(input["partners"]), input.s("me")!!, input.l("now_ms")!!
            ).toJson()
            "entries" -> PartnerJson.obj("entries" to PartnerPackage.entryNames(strings(input["categories"])))
            "filename" -> PartnerJson.obj("filename" to PartnerPackage.filename(input.s("name"), input.s("day")!!))
            "summary" -> PartnerPackage.summary(objects(input["files"]), strings(input["categories"])).toJson()
            "import" -> PartnerPackage.import(
                existing(input["existing"]), input.l("cursor")!!, input["manifest"] as JsonObject, input["files"] as JsonObject,
                input.l("now_ms")!!, input.l("batch_size")?.toInt() ?: PartnerCatalog.current.batchMax
            ).toJson()
            else -> error("package op")
        }
        "summary_metrics" -> PartnerJson.obj(
            "metrics" to PartnerSummary.metrics(
                objects(input["rows"]).map { StoredRecord(it.s("type")!!, it.s("record_id")!!, it.s("day"), it.l("ts_ms"), it["data"] as JsonObject) },
                input.s("today")!!, input.s("yesterday")!!, strings(input["grants"]), input.l("limit")?.toInt() ?: 3
            ).map { it.toJson() }
        )
        else -> error("unknown function $function")
    }
}
