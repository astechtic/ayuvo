import Foundation

/// JSON value used by the analytics engine for its inputs and results. The engine is a line-by-line port of
/// `scripts/analytics_reference.py`, whose functions take and return plain dicts; keeping the same shape here makes
/// the port auditable against the reference and the shared vectors (docs/health-analytics.md).
nonisolated enum AJ: Sendable, Equatable {
    case null
    case bool(Bool)
    case num(Double)
    case str(String)
    case arr([AJ])
    case obj([String: AJ])

    // MARK: Building

    static func n(_ x: Double?) -> AJ { x.map { .num($0) } ?? .null }
    static func i(_ x: Int?) -> AJ { x.map { .num(Double($0)) } ?? .null }
    static func s(_ x: String?) -> AJ { x.map { .str($0) } ?? .null }

    // MARK: Reading

    subscript(key: String) -> AJ {
        if case .obj(let o) = self { return o[key] ?? .null }
        return .null
    }

    subscript(index: Int) -> AJ {
        if case .arr(let a) = self, a.indices.contains(index) { return a[index] }
        return .null
    }

    var isNull: Bool { if case .null = self { return true } else { return false } }
    var double: Double? { if case .num(let x) = self { return x } else { return nil } }
    var int: Int? { double.map { Int($0) } }
    var int64: Int64? { double.map { Int64($0) } }
    var string: String? { if case .str(let s) = self { return s } else { return nil } }
    var bool: Bool? { if case .bool(let b) = self { return b } else { return nil } }
    var array: [AJ] { if case .arr(let a) = self { return a } else { return [] } }
    var object: [String: AJ] { if case .obj(let o) = self { return o } else { return [:] } }
    var has: Bool { !isNull }

    /// `{day: number}` (nulls dropped).
    var numberMap: [String: Double] {
        var out: [String: Double] = [:]
        for (k, v) in object { if let x = v.double { out[k] = x } }
        return out
    }

    /// `{day: string}` (nulls dropped).
    var stringMap: [String: String] {
        var out: [String: String] = [:]
        for (k, v) in object { if let x = v.string { out[k] = x } }
        return out
    }

    // MARK: Foundation bridge

    static func parse(_ data: Data) -> AJ? {
        guard let any = try? JSONSerialization.jsonObject(with: data, options: [.fragmentsAllowed]) else { return nil }
        return from(any)
    }

    static func from(_ any: Any) -> AJ {
        switch any {
        case is NSNull: return .null
        case let n as NSNumber:
            if CFGetTypeID(n) == CFBooleanGetTypeID() { return .bool(n.boolValue) }
            return .num(n.doubleValue)
        case let s as String: return .str(s)
        case let a as [Any]: return .arr(a.map(from))
        case let d as [String: Any]: return .obj(d.mapValues(from))
        default: return .null
        }
    }

    /// Plain Foundation value (for `JSONSerialization` / storage).
    var foundation: Any {
        switch self {
        case .null: return NSNull()
        case .bool(let b): return b
        case .num(let x): return x
        case .str(let s): return s
        case .arr(let a): return a.map(\.foundation)
        case .obj(let o): return o.mapValues(\.foundation)
        }
    }

    /// Compact JSON with sorted keys (storage of results and provenance).
    var jsonString: String {
        guard JSONSerialization.isValidJSONObject(foundation) || !(foundation is NSNull),
              let data = try? JSONSerialization.data(withJSONObject: foundation, options: [.sortedKeys, .fragmentsAllowed])
        else { return "null" }
        return String(decoding: data, as: UTF8.self)
    }
}

/// Mutable dict helper so ports read like the reference (`out["x"] = ...`).
nonisolated struct AJObject: Sendable {
    var fields: [String: AJ] = [:]

    subscript(key: String) -> AJ {
        get { fields[key] ?? .null }
        set { fields[key] = newValue }
    }

    var value: AJ { .obj(fields) }
}
