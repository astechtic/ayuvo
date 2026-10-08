import CryptoKit
import Foundation

// Swift port of `scripts/partner_reference.py` §3–§4 (encodings, key derivations, QR payload) and §7 (record
// envelopes). The reference wins over prose; functions follow it line by line on `RJ` values.

nonisolated enum PartnerRef {
    static var catalog: PartnerCatalog { PartnerCatalog.shared }

    // MARK: §3 Encodings and key derivations

    /// RFC 5869 HKDF-SHA256 (extract + expand), written out with HMAC so it matches the reference exactly.
    static func hkdfSHA256(ikm: Data, salt: Data, info: Data, length: Int) -> Data {
        let prk = Data(HMAC<SHA256>.authenticationCode(for: ikm, using: SymmetricKey(data: salt)))
        var out = Data()
        var block = Data()
        var counter: UInt8 = 1
        let key = SymmetricKey(data: prk)
        while out.count < length {
            var input = block
            input.append(info)
            input.append(counter)
            block = Data(HMAC<SHA256>.authenticationCode(for: input, using: key))
            out.append(block)
            counter &+= 1
        }
        return out.prefix(length)
    }

    /// 32-byte Noise psk from the QR token (raw bytes).
    static func pairingPSK(token: Data) -> Data {
        hkdfSHA256(ikm: token, salt: Data(catalog.pskSalt.utf8), info: Data(catalog.pskInfo.utf8), length: 32)
    }

    /// `pairing_psk(token_b64)` → hex.
    static func pairingPSKHex(tokenB64: String) -> String? {
        guard let token = PartnerEncoding.b64urlDecode(tokenB64) else { return nil }
        return PartnerEncoding.hex(pairingPSK(token: token))
    }

    /// 6-digit short authentication string from the handshake hash.
    static func sasCode(handshakeHash: Data) -> String {
        var input = Data(catalog.sasLabel.utf8)
        input.append(handshakeHash)
        let d = Array(SHA256.hash(data: input))
        let n = (UInt32(d[0]) << 24 | UInt32(d[1]) << 16 | UInt32(d[2]) << 8 | UInt32(d[3])) % 1_000_000
        let digits = String(n)
        return String(repeating: "0", count: max(0, 6 - digits.count)) + digits
    }

    /// Hex of the first 16 bytes of SHA-256(x25519_pub ‖ ed25519_pub).
    static func fingerprint(x25519: Data, ed25519: Data) -> String {
        var raw = x25519
        raw.append(ed25519)
        return PartnerEncoding.hex(Array(SHA256.hash(data: raw)).prefix(catalog.fingerprintBytes))
    }

    static func fingerprint(x25519B64: String, ed25519B64: String) -> String? {
        guard let x = PartnerEncoding.b64urlDecode(x25519B64), let e = PartnerEncoding.b64urlDecode(ed25519B64) else { return nil }
        return fingerprint(x25519: x, ed25519: e)
    }

    /// 8 groups of 4 upper-case hex digits.
    static func formatFingerprint(_ hex: String) -> String {
        let up = Array(hex.uppercased())
        return stride(from: 0, to: up.count, by: 4).map { String(up[$0..<min($0 + 4, up.count)]) }.joined(separator: " ")
    }

    // MARK: §4 QR pairing payload

    static let qrFields = ["protocol", "v", "device_id", "name", "x25519", "ed25519", "token", "exp_ms", "hosts"]

    /// Trim, collapse whitespace, cap at NAME_MAX code points; empty → "Ayuvo".
    static func cleanName(_ name: RJ?) -> String {
        guard case .str(let raw)? = name else { return "Ayuvo" }
        let s = PyStr.splitASCIIWS(raw).joined(separator: " ")
        let capped = PyStr.rstripASCII(PyStr.slice(s, 0, catalog.nameMax))
        return capped.isEmpty ? "Ayuvo" : capped
    }

    static func cleanName(_ name: String?) -> String { cleanName(name.map(RJ.str)) }

    /// Canonical QR text: prefix + base64url(compact JSON with sorted keys).
    static func qrEncode(_ payload: RJ) -> String {
        var body: [String: RJ] = [:]
        for k in qrFields { if let v = payload.get(k) { body[k] = v } }
        body["name"] = .str(cleanName(body["name"]))
        return catalog.qrPrefix + PartnerEncoding.b64urlEncode(PartnerJSON.canonicalData(.obj(body)))
    }

    private static func qrError(_ code: String) -> RJ { .obj(["ok": .bool(false), "error": .str(code)]) }

    /// `{"ok": true, "payload": …}` | `{"ok": false, "error": code}`.
    static func qrParse(_ textValue: RJ, nowMs: Int, selfDeviceID: RJ) -> RJ {
        guard case .str(let rawText) = textValue else { return qrError("not_ayuvo") }
        let text = PyStr.stripASCII(rawText)
        guard PyStr.hasPrefix(text, "ayuvo-partner:") else { return qrError("not_ayuvo") }
        guard PyStr.hasPrefix(text, catalog.qrPrefix) else { return qrError("unsupported_version") }
        let body = String(decoding: Array(text.utf8).dropFirst(catalog.qrPrefix.utf8.count), as: UTF8.self)
        guard let raw = PartnerEncoding.b64urlDecode(body) else { return qrError("malformed") }
        guard let decoded = String(bytes: raw, encoding: .utf8), let obj = PartnerJSON.parse(decoded) else { return qrError("malformed") }
        guard obj.isObject, pyEq(obj.get("protocol") ?? .null, .str(catalog.protocolID)) else { return qrError("malformed") }
        guard obj.get("v")?.pyInt == catalog.protocolVersion else { return qrError("unsupported_version") }
        guard case .str(let dev)? = obj.get("device_id"), PyStr.isUUID(dev) else { return qrError("malformed") }
        guard let exp = obj.get("exp_ms")?.pyInt else { return qrError("malformed") }
        let hostsValue = obj.get("hosts") ?? .arr([])
        guard let hosts = hostsValue.array, hosts.count <= 8,
              hosts.allSatisfy({ if case .str(let h) = $0 { return PyStr.isHost(h) } else { return false } })
        else { return qrError("malformed") }
        for key in ["x25519", "ed25519", "token"] {
            guard let b = PartnerEncoding.b64urlDecode(obj.get(key) ?? .null), b.count == 32 else { return qrError("bad_key") }
        }
        if pyEq(.str(dev), selfDeviceID) { return qrError("self") }
        if nowMs > exp || exp - nowMs > catalog.qrTTLms { return qrError("expired") }
        let x = obj["x25519"].string ?? "", e = obj["ed25519"].string ?? ""
        let payload: [String: RJ] = [
            "device_id": .str(dev), "name": .str(cleanName(obj.get("name"))), "x25519": .str(x), "ed25519": .str(e),
            "token": obj["token"], "exp_ms": .int(exp), "hosts": .arr(hosts),
            "fingerprint": .str(fingerprint(x25519B64: x, ed25519B64: e) ?? ""),
        ]
        return .obj(["ok": .bool(true), "payload": .obj(payload)])
    }

    // MARK: §7 Record envelopes

    /// Category of a record; health-derived types take it from the type_id allow-list (nil = not shareable).
    static func categoryOf(_ rtype: RJ?, _ data: RJ?) -> String? {
        guard let spec = catalog.spec(rtype) else { return nil }
        if spec.category != "health" { return spec.category }
        let tid = data?.get("type_id")
        guard let cat = catalog.healthCategory(tid) else { return nil }
        if spec.type == "sample", !catalog.contains(catalog.intradayTypes, tid) { return nil }
        if spec.type == "metric_hour", !catalog.contains(catalog.hourlyTypes, tid) { return nil }
        return cat
    }

    static func hasForbiddenKey(_ value: RJ) -> Bool {
        switch value {
        case .obj(let o):
            for (k, v) in o {
                if catalog.forbiddenKeys.contains(where: { utf8Equal($0, k) }) { return true }
                if hasForbiddenKey(v) { return true }
            }
            return false
        case .arr(let a):
            return a.contains(where: hasForbiddenKey)
        default:
            return false
        }
    }

    static func tooBig(_ value: RJ) -> Bool {
        switch value {
        case .str(let s): return PyStr.length(s) > catalog.stringMax
        case .arr(let a): return a.count > catalog.arrayMax || a.contains(where: tooBig)
        case .obj(let o): return o.values.contains(where: tooBig)
        default: return false
        }
    }

    private static func envResult(_ ok: Bool, _ error: String?, _ category: String?) -> RJ {
        .obj(["ok": .bool(ok), "error": RJ.string(error), "category": RJ.string(category)])
    }

    /// `{"ok", "error", "category"}`.
    static func envelopeValidate(_ env: RJ, nowMs: Int) -> RJ {
        guard env.isObject else { return envResult(false, "malformed", nil) }
        guard case .str(let rid)? = env.get("id"), case .str? = env.get("type"),
              !rid.isEmpty, PyStr.length(rid) <= 200, let rev = env.get("rev")?.pyInt, rev >= 1
        else { return envResult(false, "malformed", nil) }
        guard case .bool(let deleted)? = env.get("deleted"), env.get("updated_ms")?.pyInt != nil else {
            return envResult(false, "malformed", nil)
        }
        guard let spec = catalog.spec(env.get("type")) else { return envResult(false, "unknown_type", nil) }
        let claimed = env.get("category") ?? .null
        if deleted {
            if !catalog.isCategory(claimed) || (spec.category != "health" && !pyEq(.str(spec.category), claimed)) {
                return envResult(false, "category_mismatch", nil)
            }
            return envResult(true, nil, claimed.string)
        }
        guard let data = env.get("data"), data.isObject else { return envResult(false, "malformed", nil) }
        guard let cat = categoryOf(env.get("type"), data) else { return envResult(false, "not_shareable", nil) }
        guard pyEq(claimed, .str(cat)) else { return envResult(false, "category_mismatch", nil) }
        for f in spec.required where (data.get(f) ?? .null).isNull {
            return envResult(false, "missing_field", nil)
        }
        if hasForbiddenKey(data) { return envResult(false, "forbidden_field", nil) }
        if tooBig(data) { return envResult(false, "too_large", nil) }
        let day = env.get("day") ?? .null
        if spec.day {
            guard case .str(let d) = day, PyStr.isDay(d) else { return envResult(false, "bad_day", nil) }
        } else if !day.isNull {
            return envResult(false, "bad_day", nil)
        }
        return envResult(true, nil, cat)
    }

    /// Primary instant of a live record (`ts` field of its type), else nil.
    static func envelopeTs(_ env: RJ) -> Int? {
        guard let spec = catalog.spec(env.get("type")), let field = spec.ts else { return nil }
        if (env.get("deleted") ?? .null).truthy { return nil }
        return env["data"].get(field)?.pyInt
    }
}
