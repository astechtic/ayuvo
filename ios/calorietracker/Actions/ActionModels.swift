import AppIntents
import Foundation

/// Where an action request came from (shared catalog `surfaces`, plus `app` for Ayuvo's own UI).
nonisolated enum ActionSource: String, Sendable, CaseIterable {
    case app, siri, shortcuts, android, deeplink, coach
}

/// An untyped parameter as it arrives (deep-link text, Coach JSON, intent values), before
/// `ActionValidator` coerces it. Mirrors the Python reference's dynamic types.
nonisolated enum ActionRawValue: Sendable, Hashable {
    case bool(Bool)
    case number(Double)
    case string(String)
    /// Lists, objects: always `bad_type`.
    case other

    /// `nil` for `nil` / `NSNull` (treated as a missing parameter).
    static func from(_ any: Any?) -> ActionRawValue? {
        guard let any, !(any is NSNull) else { return nil }
        if let string = any as? String { return .string(string) }
        if let number = any as? NSNumber {
            if CFGetTypeID(number) == CFBooleanGetTypeID() { return .bool(number.boolValue) }
            return .number(number.doubleValue)
        }
        return .other
    }

    static func params(_ dictionary: [String: Any]) -> [String: ActionRawValue] {
        var out: [String: ActionRawValue] = [:]
        for (key, value) in dictionary {
            if let raw = from(value) { out[key] = raw }
        }
        return out
    }
}

/// A validated, typed parameter.
nonisolated enum ActionValue: Sendable, Hashable {
    case number(Double)
    case integer(Int)
    case string(String)

    var double: Double? {
        switch self {
        case .number(let v): v
        case .integer(let v): Double(v)
        case .string: nil
        }
    }

    var int: Int? {
        switch self {
        case .integer(let v): v
        case .number(let v): v.rounded() == v ? Int(v) : nil
        case .string: nil
        }
    }

    var string: String? {
        if case .string(let v) = self { return v }
        return nil
    }

    var raw: ActionRawValue {
        switch self {
        case .number(let v): .number(v)
        case .integer(let v): .number(Double(v))
        case .string(let v): .string(v)
        }
    }

    var jsonValue: Any {
        switch self {
        case .number(let v): v
        case .integer(let v): v
        case .string(let v): v
        }
    }
}

/// Result of `ActionValidator.validate` (shared reference `validate`).
nonisolated struct ActionValidation: Sendable, Equatable {
    let actionID: String
    let params: [String: ActionValue]
    /// The action must be confirmed by the user before it runs.
    let confirm: Bool
    /// The request will use the AI provider (`nutrition.food.log` from a description).
    let ai: Bool

    func double(_ name: String) -> Double? { params[name]?.double }
    func int(_ name: String) -> Int? { params[name]?.int }
    func string(_ name: String) -> String? { params[name]?.string }
}

/// Everything an action can fail with. Validation codes are the shared ones.
nonisolated enum ActionError: Error, Sendable, Equatable {
    case invalid(code: String, param: String?)
    case permissionRequired(String)
    case notFound(String)
    case conflict(String)
    case unavailable(String)
    case confirmationRequired

    var code: String {
        switch self {
        case .invalid(let code, _): code
        case .permissionRequired: "permission_required"
        case .notFound: "not_found"
        case .conflict: "conflict"
        case .unavailable: "unavailable"
        case .confirmationRequired: "confirmation_required"
        }
    }

    var message: String {
        switch self {
        case .invalid(let code, let param):
            let name = param.map { $0.replacingOccurrences(of: "_", with: " ") } ?? ""
            switch code {
            case "unknown_action": return String(localized: "Ayuvo doesn't have that action.")
            case "not_allowed": return String(localized: "That action isn't available from here.")
            case "unknown_param": return String(localized: "Ayuvo doesn't understand the \(name) value.")
            case "missing_param": return String(localized: "Please give a \(name).")
            case "bad_type", "bad_value": return String(localized: "The \(name) value isn't valid.")
            case "bad_enum": return String(localized: "That \(name) isn't one of the options.")
            case "too_long": return String(localized: "The \(name) is too long.")
            case "out_of_range": return String(localized: "The \(name) is outside the allowed range.")
            case "requires_one_of": return String(localized: "Describe the food, or give its name and calories.")
            default: return String(localized: "That request isn't valid.")
            }
        case .permissionRequired(let text), .notFound(let text), .conflict(let text), .unavailable(let text):
            return text
        case .confirmationRequired:
            return String(localized: "Please confirm this in Ayuvo first.")
        }
    }
}

extension ActionError: LocalizedError, CustomLocalizedStringResourceConvertible {
    var errorDescription: String? { message }
    var localizedStringResource: LocalizedStringResource { LocalizedStringResource(stringLiteral: message) }
}

/// A structured output value (shared catalog `output.fields`).
nonisolated indirect enum ActionField: Sendable, Hashable {
    case number(Double)
    case int(Int)
    case string(String)
    case bool(Bool)
    case null
    case list([ActionField])
    case object([String: ActionField])

    static func optional(_ value: Double?) -> ActionField { value.map { .number($0) } ?? .null }
    static func optional(_ value: Int?) -> ActionField { value.map { .int($0) } ?? .null }
    static func optional(_ value: Int64?) -> ActionField { value.map { .int(Int($0)) } ?? .null }
    static func optional(_ value: String?) -> ActionField { value.map { .string($0) } ?? .null }

    var double: Double? {
        switch self {
        case .number(let v): v
        case .int(let v): Double(v)
        default: nil
        }
    }

    var string: String? {
        if case .string(let v) = self { return v }
        return nil
    }

    var bool: Bool? {
        if case .bool(let v) = self { return v }
        return nil
    }

    var jsonValue: Any {
        switch self {
        case .number(let v): v
        case .int(let v): v
        case .string(let v): v
        case .bool(let v): v
        case .null: NSNull()
        case .list(let items): items.map(\.jsonValue)
        case .object(let fields): fields.mapValues(\.jsonValue)
        }
    }
}

/// Where the app should go (OPEN actions, deep links, "Open in Ayuvo" after a result).
/// `target` uses the shared catalog `screen` vocabulary: `metric:<key>`, `screen:<name>`,
/// `tab:<name>`, `section:<name>`, `record:<id>`.
nonisolated enum ActionRoute: Hashable, Sendable {
    case target(String)
    case coach(prompt: String?)

    var storageValue: String {
        switch self {
        case .target(let target): "target|\(target)"
        case .coach(let prompt): "coach|\(prompt ?? "")"
        }
    }

    init?(storageValue: String) {
        if storageValue.hasPrefix("target|") {
            self = .target(String(storageValue.dropFirst(7)))
        } else if storageValue.hasPrefix("coach|") {
            let prompt = String(storageValue.dropFirst(6))
            self = .coach(prompt: prompt.isEmpty ? nil : prompt)
        } else {
            return nil
        }
    }
}

/// A finished action: structured fields for Shortcuts / Coach, list items, and a spoken summary.
nonisolated struct ActionResult: Sendable {
    var actionID: String
    var fields: [String: ActionField] = [:]
    var items: [[String: ActionField]]?
    var dialog: String
    var route: ActionRoute?

    var value: Double? { fields["value"]?.double }

    func double(_ name: String) -> Double? { fields[name]?.double }
    func string(_ name: String) -> String? { fields[name]?.string }

    /// Coach tool payload.
    var jsonObject: [String: Any] {
        var out: [String: Any] = ["action": actionID, "ok": true, "summary": dialog]
        for (key, value) in fields { out[key] = value.jsonValue }
        if let items { out["items"] = items.map { $0.mapValues(\.jsonValue) } }
        return out
    }

    var jsonText: String { ActionJSON.text(jsonObject) }
}

nonisolated enum ActionJSON {
    static func text(_ object: Any) -> String {
        guard JSONSerialization.isValidJSONObject(object),
              let data = try? JSONSerialization.data(withJSONObject: object, options: [.sortedKeys]),
              let text = String(data: data, encoding: .utf8)
        else { return "{}" }
        return text
    }

    static func error(_ error: ActionError, actionID: String? = nil) -> String {
        var out: [String: Any] = ["ok": false, "error": error.code, "message": error.message]
        if let actionID { out["action"] = actionID }
        if case .invalid(_, let param) = error, let param { out["param"] = param }
        return text(out)
    }
}
