import Foundation

/// The bundled contract catalogs: `Partner/Resources/partner_record_types.json` and `partner_protocol.json` are
/// byte-identical copies of `shared/partner/record_types.json` and `protocol.json` (renamed only because the
/// app bundle is flat and Records already ships a `record_types.json`; a test compares the bytes).
nonisolated struct PartnerCatalog: Sendable {
    nonisolated struct TypeSpec: Sendable {
        let type: String
        let category: String          // a category, or "health" (category from the type_id allow-list)
        let day: Bool
        let ts: String?
        let retentionDays: Int?
        let required: [String]
        let optional: [String]
        let scope: String?
    }

    let recordTypes: RJ
    let protocolDoc: RJ
    let categories: [String]
    let types: [String: TypeSpec]
    let typeOrder: [String]
    let healthTypeCategory: [String: String]
    let intradayTypes: Set<String>
    let hourlyTypes: Set<String>
    let analyticsMetrics: Set<String>
    let forbiddenKeys: Set<String>
    let stringMax: Int
    let arrayMax: Int
    let intradayDays: Int

    let protocolID: String
    let protocolVersion: Int
    let qrPrefix: String
    let qrTTLms: Int
    let nameMax: Int
    let batchMax: Int
    let batchBytesMax: Int
    let packageFormat: String
    let packageVersion: Int
    let packageExtension: String
    let packageEntriesFixed: [String]
    let messages: [String: [String]]   // t -> required fields
    let pskSalt: String
    let pskInfo: String
    let sasLabel: String
    let fingerprintBytes: Int
    let prologue: String
    let maxMessage: Int

    static let dayMs = 86_400_000
    static let futureSkewMs = 10 * 60_000

    var intradayMs: Int { intradayDays * Self.dayMs }

    static let shared: PartnerCatalog = {
        guard let types = load("partner_record_types"), let proto = load("partner_protocol") else {
            fatalError("Partner contract catalogs are missing from the app bundle")
        }
        return PartnerCatalog(recordTypes: types, protocolDoc: proto)
    }()

    static func load(_ name: String) -> RJ? {
        let bundles = [Bundle.main, Bundle(for: PartnerBundleToken.self)]
        for bundle in bundles {
            if let url = bundle.url(forResource: name, withExtension: "json"), let data = try? Data(contentsOf: url) {
                return PartnerJSON.parse(data)
            }
        }
        return nil
    }

    init(recordTypes rt: RJ, protocolDoc p: RJ) {
        recordTypes = rt
        protocolDoc = p
        categories = (rt["categories"].array ?? []).compactMap(\.string)
        var specs: [String: TypeSpec] = [:]
        var order: [String] = []
        for t in rt["types"].array ?? [] {
            guard let name = t["type"].string else { continue }
            order.append(name)
            specs[name] = TypeSpec(
                type: name,
                category: t["category"].string ?? "",
                day: t["day"].bool ?? false,
                ts: t["ts"].string,
                retentionDays: t["retention_days"].pyInt,
                required: (t["required"].array ?? []).compactMap(\.string),
                optional: (t["optional"].array ?? []).compactMap(\.string),
                scope: t["scope"].string
            )
        }
        types = specs
        typeOrder = order
        var htc: [String: String] = [:]
        for (cat, ids) in rt["health_types"].object ?? [:] {
            for id in ids.array ?? [] { if let s = id.string { htc[s] = cat } }
        }
        healthTypeCategory = htc
        intradayTypes = Set((rt["intraday_types"].array ?? []).compactMap(\.string))
        hourlyTypes = Set((rt["hourly_types"].array ?? []).compactMap(\.string))
        analyticsMetrics = Set((rt["analytics_metrics"].array ?? []).compactMap(\.string))
        forbiddenKeys = Set((rt["forbidden_keys"].array ?? []).compactMap(\.string))
        stringMax = rt["string_max"].pyInt ?? 2000
        arrayMax = rt["array_max"].pyInt ?? 500
        intradayDays = rt["intraday_days"].pyInt ?? 7

        protocolID = p["protocol"].string ?? ""
        protocolVersion = p["version"].pyInt ?? 1
        qrPrefix = p["qr_prefix"].string ?? ""
        qrTTLms = p["qr_ttl_ms"].pyInt ?? 600_000
        nameMax = p["name_max"].pyInt ?? 40
        batchMax = p["batch_records_max"].pyInt ?? 500
        batchBytesMax = p["batch_bytes_max"].pyInt ?? 524_288
        let pkg = p["package"]
        packageFormat = pkg["format"].string ?? ""
        packageVersion = pkg["version"].pyInt ?? 1
        packageExtension = pkg["extension"].string ?? ""
        packageEntriesFixed = (pkg["entries_fixed"].array ?? []).compactMap(\.string)
        var msgs: [String: [String]] = [:]
        for (t, spec) in p["messages"].object ?? [:] {
            msgs[t] = (spec["required"].array ?? []).compactMap(\.string)
        }
        messages = msgs
        let kdf = p["kdf"]
        pskSalt = kdf["psk_salt"].string ?? ""
        pskInfo = kdf["psk_info"].string ?? ""
        sasLabel = kdf["sas_label"].string ?? ""
        fingerprintBytes = kdf["fingerprint_bytes"].pyInt ?? 16
        prologue = p["noise"]["prologue"].string ?? ""
        maxMessage = p["noise"]["max_message"].pyInt ?? 65535
    }

    func isCategory(_ v: RJ) -> Bool { pyIn(v, categories) }

    /// `TYPES.get(v)` with code point equality (Swift dictionary lookups use canonical equivalence).
    func spec(_ v: RJ?) -> TypeSpec? {
        guard case .str(let s)? = v, let spec = types[s], utf8Equal(spec.type, s) else { return nil }
        return spec
    }

    /// `HEALTH_TYPE_CATEGORY.get(tid)`.
    func healthCategory(_ v: RJ?) -> String? {
        guard case .str(let s)? = v, let cat = healthTypeCategory[s],
              healthTypeCategory.keys.contains(where: { utf8Equal($0, s) }) else { return nil }
        return cat
    }

    func contains(_ set: Set<String>, _ v: RJ?) -> Bool {
        guard case .str(let s)? = v else { return false }
        return set.contains { utf8Equal($0, s) }
    }
}

/// Anchor class for `Bundle(for:)` (the catalogs ship in the app target).
nonisolated final class PartnerBundleToken {}
