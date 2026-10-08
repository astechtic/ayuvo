package com.ayuvo.health.partner.logic

import android.content.Context
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive

/** One entry of `record_types.json` `types`. */
data class PartnerTypeSpec(
    val type: String,
    /** A category, or "health" when it comes from the `health_types` allow-list of the data's type_id. */
    val category: String,
    val day: Boolean,
    val ts: String?,
    val scope: String,
    val retentionDays: Int?,
    val required: List<String>,
    val optional: List<String>
)

/**
 * The shared catalogs `shared/partner/record_types.json` and `protocol.json`, shipped byte-identical in
 * `assets/partner/` (PartnerAssetsContractTest). Every logic function reads its constants from here.
 */
class PartnerCatalog(recordTypesJson: String, protocolJson: String) {
    val recordTypes: JsonObject = PartnerJson.parse(recordTypesJson) as JsonObject
    val protocol: JsonObject = PartnerJson.parse(protocolJson) as JsonObject

    private fun strings(o: JsonObject, key: String): List<String> =
        (o[key] as JsonArray).map { it.jsonPrimitive.content }

    private fun int(o: JsonObject, key: String): Int = (o[key] as JsonPrimitive).content.toInt()
    private fun string(o: JsonObject, key: String): String = (o[key] as JsonPrimitive).content

    val categories: List<String> = strings(recordTypes, "categories")
    val types: Map<String, PartnerTypeSpec> = (recordTypes["types"] as JsonArray).map { it as JsonObject }.associate { t ->
        val spec = PartnerTypeSpec(
            type = string(t, "type"),
            category = string(t, "category"),
            day = PartnerJson.bool(t["day"]) == true,
            ts = PartnerJson.str(t["ts"]),
            scope = string(t, "scope"),
            retentionDays = PartnerJson.long(t["retention_days"])?.toInt(),
            required = strings(t, "required"),
            optional = strings(t, "optional")
        )
        spec.type to spec
    }
    val healthTypeCategory: Map<String, String> = LinkedHashMap<String, String>().also { m ->
        val ht = recordTypes["health_types"] as JsonObject
        for ((cat, ids) in ht) for (id in ids as JsonArray) m[id.jsonPrimitive.content] = cat
    }
    val intradayTypes: Set<String> = strings(recordTypes, "intraday_types").toSet()
    val hourlyTypes: Set<String> = strings(recordTypes, "hourly_types").toSet()
    val analyticsMetrics: Set<String> = strings(recordTypes, "analytics_metrics").toSet()
    val forbiddenKeys: Set<String> = strings(recordTypes, "forbidden_keys").toSet()
    val stringMax: Int = int(recordTypes, "string_max")
    val arrayMax: Int = int(recordTypes, "array_max")
    val intradayDays: Int = int(recordTypes, "intraday_days")
    val refreshWindowDays: Int = int(recordTypes, "refresh_window_days")
    val intradayMs: Long = intradayDays * DAY_MS

    val protocolId: String = string(protocol, "protocol")
    val protocolVersion: Int = int(protocol, "version")
    val qrPrefix: String = string(protocol, "qr_prefix")
    val qrTtlMs: Long = int(protocol, "qr_ttl_ms").toLong()
    val nameMax: Int = int(protocol, "name_max")
    val batchMax: Int = int(protocol, "batch_records_max")
    val batchBytesMax: Int = int(protocol, "batch_bytes_max")
    val messages: Map<String, List<String>> = (protocol["messages"] as JsonObject).mapValues { (_, v) -> strings(v as JsonObject, "required") }
    val errorCodes: List<String> = strings(protocol, "error_codes")
    val statuses: List<String> = strings(protocol, "statuses")

    private val kdf = protocol["kdf"] as JsonObject
    val pskSalt: String = string(kdf, "psk_salt")
    val pskInfo: String = string(kdf, "psk_info")
    val sasLabel: String = string(kdf, "sas_label")
    val sasDigits: Int = int(kdf, "sas_digits")
    val fingerprintBytes: Int = int(kdf, "fingerprint_bytes")

    private val noise = protocol["noise"] as JsonObject
    val noisePairing: String = string(noise, "pairing")
    val noiseSession: String = string(noise, "session")
    val noisePrologue: String = string(noise, "prologue")
    val noiseMaxMessage: Int = int(noise, "max_message")

    private val pkg = protocol["package"] as JsonObject
    val packageFormat: String = string(pkg, "format")
    val packageVersion: Int = int(pkg, "version")
    val packageExtension: String = string(pkg, "extension")
    val packageEntriesFixed: List<String> = strings(pkg, "entries_fixed")

    companion object {
        const val DAY_MS: Long = 86_400_000L
        /** Sender clocks may run ahead this much; beyond it `updated_ms` is clamped (reference FUTURE_SKEW_MS). */
        const val FUTURE_SKEW_MS: Long = 10 * 60_000L
        const val ASSET_RECORD_TYPES = "partner/record_types.json"
        const val ASSET_PROTOCOL = "partner/protocol.json"

        @Volatile private var installed: PartnerCatalog? = null

        /** The installed catalog; [install] (app) or [installFromText] (tests) must run first. */
        val current: PartnerCatalog
            get() = installed ?: error("PartnerCatalog not installed")

        fun installFromText(recordTypesJson: String, protocolJson: String): PartnerCatalog =
            PartnerCatalog(recordTypesJson, protocolJson).also { installed = it }

        /** Loads the catalogs from `assets/partner/` once; cheap on later calls. */
        fun install(context: Context): PartnerCatalog {
            installed?.let { return it }
            synchronized(this) {
                installed?.let { return it }
                val assets = context.applicationContext.assets
                val rt = assets.open(ASSET_RECORD_TYPES).use { it.readBytes().toString(Charsets.UTF_8) }
                val proto = assets.open(ASSET_PROTOCOL).use { it.readBytes().toString(Charsets.UTF_8) }
                return installFromText(rt, proto)
            }
        }
    }
}
