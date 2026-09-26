import Foundation

/// Swift port of `validate` in `scripts/actions_reference.py`. `ActionContractTests` runs every case of
/// `shared/actions/test-vectors/validation.json` and `deeplinks.json` through it, so keep the check
/// order and coercion rules line-for-line with the reference.
nonisolated enum ActionValidator {
    static let entityMaxLength = 200

    static func validate(
        catalog: ActionCatalog,
        actionID: String,
        params rawParams: [String: ActionRawValue],
        source: ActionSource,
        prefs: [String: String]
    ) -> Result<ActionValidation, ActionError> {
        guard let action = catalog.action(actionID) else { return .failure(.invalid(code: "unknown_action", param: nil)) }
        if source != .app, !action.surfaces.contains(source.rawValue) {
            return .failure(.invalid(code: "not_allowed", param: nil))
        }
        let names = Set(action.params.map(\.name))
        for key in rawParams.keys.sorted(by: codePointLess) where !names.contains(key) {
            return .failure(.invalid(code: "unknown_param", param: key))
        }

        var params: [String: ActionValue] = [:]
        for spec in action.params {
            var raw = rawParams[spec.name]
            if case .string(let text)? = raw {
                let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
                raw = trimmed.isEmpty ? nil : .string(trimmed)
            }
            guard let raw else {
                if let fallback = spec.defaultValue, case .success(let value) = coerce(catalog: catalog, spec: spec, raw: fallback) {
                    params[spec.name] = value
                    continue
                }
                if let pref = spec.defaultPref, let enumName = spec.enumName,
                   let value = prefs[pref], catalog.enums[enumName]?.contains(value) == true {
                    params[spec.name] = .string(value)
                    continue
                }
                if spec.required { return .failure(.invalid(code: "missing_param", param: spec.name)) }
                continue
            }
            switch coerce(catalog: catalog, spec: spec, raw: raw) {
            case .success(let value): params[spec.name] = value
            case .failure(let code): return .failure(.invalid(code: code.rawValue, param: spec.name))
            }
        }

        for spec in action.params {
            guard var value = params[spec.name]?.double else { continue }
            var lo = spec.min, hi = spec.max
            if let by = spec.rangeBy, let key = params[by.param]?.string, let range = by.ranges[key] {
                lo = range.0
                hi = range.1
            }
            if lo == nil && hi == nil { continue }
            if let unitParam = spec.unitParam, let familyName = spec.unitFamily, let family = catalog.units[familyName] {
                let unit = params[unitParam]?.string ?? family.canonical
                value *= family.factors[unit] ?? 1
            }
            if let lo, value < lo { return .failure(.invalid(code: "out_of_range", param: spec.name)) }
            if let hi, value > hi { return .failure(.invalid(code: "out_of_range", param: spec.name)) }
        }

        if !action.requiresOneOf.isEmpty,
           !action.requiresOneOf.contains(where: { group in group.allSatisfy { params[$0] != nil } }) {
            return .failure(.invalid(code: "requires_one_of", param: nil))
        }
        let ai = action.confirmation == "when_ai" && !action.aiUnless.allSatisfy { params[$0] != nil }
        let confirm = action.kind == .set && !action.opensApp
            && (action.confirmation == "always" || ai || source == .deeplink || source == .coach)
        return .success(ActionValidation(actionID: actionID, params: params, confirm: confirm, ai: ai))
    }

    nonisolated enum CoerceError: String, Error { case badType = "bad_type", badEnum = "bad_enum", badValue = "bad_value", tooLong = "too_long" }

    static func coerce(catalog: ActionCatalog, spec: ActionCatalog.Param, raw: ActionRawValue) -> Result<ActionValue, CoerceError> {
        let value: ActionValue
        switch spec.type {
        case .number:
            switch raw {
            case .number(let v): value = .number(v)
            case .string(let text):
                guard let v = parseNumber(text) else { return .failure(.badType) }
                value = .number(v)
            default: return .failure(.badType)
            }
            if let v = value.double, v.isNaN || v.isInfinite { return .failure(.badType) }
        case .integer:
            switch raw {
            case .number(let v):
                guard v.rounded() == v, abs(v) < 9e15 else { return .failure(.badType) }
                value = .integer(Int(v))
            case .string(let text):
                guard isInteger(text), let v = Int(text) else { return .failure(.badType) }
                value = .integer(v)
            default: return .failure(.badType)
            }
        case .enum:
            guard case .string(let text) = raw else { return .failure(.badType) }
            guard let name = spec.enumName, catalog.enums[name]?.contains(text) == true else { return .failure(.badEnum) }
            value = .string(text)
        case .metric:
            guard case .string(let text) = raw else { return .failure(.badType) }
            guard isMetricKey(text) else { return .failure(.badValue) }
            value = .string(text)
        case .string, .entity:
            guard case .string(let text) = raw else { return .failure(.badType) }
            let limit = spec.type == .string ? (spec.maxLength ?? entityMaxLength) : entityMaxLength
            if text.unicodeScalars.count > limit { return .failure(.tooLong) }
            value = .string(text)
        }
        if let allowed = spec.allowed {
            guard let v = value.double, allowed.contains(v) else { return .failure(.badEnum) }
        }
        return .success(value)
    }

    /// `^[+-]?([0-9]+(\.[0-9]*)?|\.[0-9]+)$`
    static func parseNumber(_ text: String) -> Double? {
        var body = Substring(text)
        var sign = 1.0
        if let first = body.first, first == "+" || first == "-" {
            sign = first == "-" ? -1 : 1
            body = body.dropFirst()
        }
        let parts = body.split(separator: ".", maxSplits: 1, omittingEmptySubsequences: false)
        let whole = parts.first ?? ""
        let fraction = parts.count > 1 ? parts[1] : nil
        guard whole.allSatisfy(isDigit), fraction?.allSatisfy(isDigit) ?? true else { return nil }
        guard !whole.isEmpty || !(fraction?.isEmpty ?? true) else { return nil }
        let normalized = (whole.isEmpty ? "0" : String(whole)) + "." + (fraction.map { $0.isEmpty ? "0" : String($0) } ?? "0")
        return Double(normalized).map { sign * $0 }
    }

    /// `^[+-]?[0-9]+$`
    static func isInteger(_ text: String) -> Bool {
        var body = Substring(text)
        if let first = body.first, first == "+" || first == "-" { body = body.dropFirst() }
        return !body.isEmpty && body.allSatisfy(isDigit)
    }

    /// `^(app:)?[a-z][a-z0-9_]*$`
    static func isMetricKey(_ text: String) -> Bool {
        let body = text.hasPrefix("app:") ? Substring(text.dropFirst(4)) : Substring(text)
        guard let first = body.unicodeScalars.first, ("a"..."z").contains(first) else { return false }
        return body.unicodeScalars.allSatisfy { ("a"..."z").contains($0) || ("0"..."9").contains($0) || $0 == "_" }
    }

    private static func isDigit(_ c: Character) -> Bool { c.isASCII && c.isNumber }

    private static func codePointLess(_ a: String, _ b: String) -> Bool {
        Array(a.unicodeScalars.map(\.value)).lexicographicallyPrecedes(b.unicodeScalars.map(\.value))
    }
}
