import Foundation
import Testing
@testable import calorietracker

/// `shared/actions` contract: the bundled catalog copy and every test vector (docs/actions.md).
@MainActor
struct ActionContractTests {
    static var sharedURL: URL { HealthTestFixtures.repoRootURL.appendingPathComponent("shared/actions") }

    static func sharedCatalog() throws -> ActionCatalog {
        try ActionCatalog(data: Data(contentsOf: sharedURL.appendingPathComponent("action_catalog.json")))
    }

    static func vectorCases(_ file: String) throws -> [[String: Any]] {
        let data = try Data(contentsOf: sharedURL.appendingPathComponent("test-vectors/\(file)"))
        let root = try #require(try JSONSerialization.jsonObject(with: data) as? [String: Any])
        return try #require(root["cases"] as? [[String: Any]])
    }

    // MARK: - JSON comparison (numbers compare by value, booleans stay booleans)

    static func same(_ a: Any?, _ b: Any?) -> Bool {
        let a = a is NSNull ? nil : a
        let b = b is NSNull ? nil : b
        if a == nil || b == nil { return a == nil && b == nil }
        if let x = a as? [String: Any], let y = b as? [String: Any] {
            return Set(x.keys) == Set(y.keys) && x.keys.allSatisfy { same(x[$0], y[$0]) }
        }
        if let x = a as? [Any], let y = b as? [Any] {
            return x.count == y.count && zip(x, y).allSatisfy { same($0, $1) }
        }
        if let x = a as? NSNumber, let y = b as? NSNumber {
            let xb = CFGetTypeID(x) == CFBooleanGetTypeID(), yb = CFGetTypeID(y) == CFBooleanGetTypeID()
            return xb == yb && (xb ? x.boolValue == y.boolValue : x.doubleValue == y.doubleValue)
        }
        if let x = a as? String, let y = b as? String { return x == y }
        return false
    }

    static func json(_ result: Result<ActionValidation, ActionError>) -> [String: Any] {
        switch result {
        case .success(let v):
            return ["ok": true, "params": v.params.mapValues(\.jsonValue), "confirm": v.confirm, "ai": v.ai]
        case .failure(let error):
            guard case .invalid(let code, let param) = error else { return ["ok": false, "error": ["code": error.code]] }
            return ["ok": false, "error": ["code": code, "param": param ?? NSNull()] as [String: Any]]
        }
    }

    static func context(_ input: [String: Any]) -> (ActionSource, [String: String]) {
        let context = input["context"] as? [String: Any] ?? [:]
        let source = ActionSource(rawValue: context["source"] as? String ?? "app") ?? .app
        return (source, context["prefs"] as? [String: String] ?? [:])
    }

    // MARK: - Tests

    @Test func bundledCatalogIsByteIdenticalToShared() throws {
        let shared = try Data(contentsOf: Self.sharedURL.appendingPathComponent("action_catalog.json"))
        let bundled = try Data(contentsOf: HealthTestFixtures.repoRootURL.appendingPathComponent("ios/calorietracker/Actions/Resources/action_catalog.json"))
        #expect(shared == bundled)
        let catalog = try Self.sharedCatalog()
        #expect(ActionCatalog.shared.actions.map(\.id) == catalog.actions.map(\.id))
        #expect(catalog.actions.count >= 40)
    }

    @Test func everyCatalogActionIsHandledOnIOS() throws {
        // A catalog action without a handler fails with `unavailable`; dispatching a dummy request
        // for each id must never hit that branch.
        let catalog = try Self.sharedCatalog()
        let source = try String(contentsOf: HealthTestFixtures.repoRootURL.appendingPathComponent("ios/calorietracker/Actions/ActionExecutor.swift"), encoding: .utf8)
        for action in catalog.actions {
            #expect(source.contains("\"\(action.id)\""), "no handler for \(action.id)")
        }
    }

    @Test func validationVectors() throws {
        let catalog = try Self.sharedCatalog()
        let cases = try Self.vectorCases("validation.json")
        #expect(cases.count > 40)
        for c in cases {
            let input = try #require(c["input"] as? [String: Any])
            let (source, prefs) = Self.context(input)
            let params = ActionRawValue.params(input["params"] as? [String: Any] ?? [:])
            let got = Self.json(ActionValidator.validate(catalog: catalog, actionID: input["id"] as? String ?? "", params: params, source: source, prefs: prefs))
            #expect(Self.same(got, c["expected"]), "\(c["name"] ?? "?"): \(got)")
        }
    }

    @Test func deepLinkVectors() throws {
        let catalog = try Self.sharedCatalog()
        for c in try Self.vectorCases("deeplinks.json") {
            let input = try #require(c["input"] as? [String: Any])
            let prefs = input["prefs"] as? [String: String] ?? [:]
            var parse: [String: Any]
            var validate: Any = NSNull()
            switch ActionDeepLink.parse(input["url"] as? String ?? "") {
            case .request(let request):
                parse = ["ok": true, "request": ["id": request.id, "params": request.params] as [String: Any]]
                validate = Self.json(ActionValidator.validate(catalog: catalog, actionID: request.id,
                                                              params: request.params.mapValues { .string($0) }, source: .deeplink, prefs: prefs))
            case .notActionLink:
                parse = ["ok": false, "error": ["code": "not_action_link"]]
            case .failure(let code):
                parse = ["ok": false, "error": ["code": code]]
            }
            let got: [String: Any] = ["parse": parse, "validate": validate]
            #expect(Self.same(got, c["expected"]), "\(c["name"] ?? "?"): \(got)")
        }
    }

    @Test func dateRangeVectors() throws {
        for c in try Self.vectorCases("date_ranges.json") {
            let input = try #require(c["input"] as? [String: Any])
            let zone = MetricsReference.Zone(input["time_zone"] as? String ?? "UTC")
            let weekStart = MetricsReference.WeekStart(rawValue: input["week_start"] as? String ?? "") ?? .monday
            let range = try #require(ActionMath.resolveDateRange(input["preset"] as? String ?? "", nowMs: (input["now_ms"] as? NSNumber)?.int64Value ?? 0,
                                                                zone: zone, weekStart: weekStart))
            let got: [String: Any] = ["from_ms": range.fromMs, "to_ms": range.toMs]
            #expect(Self.same(got, c["expected"]), "\(c["name"] ?? "?"): \(got)")
        }
    }

    @Test func computeVectors() throws {
        let catalog = try Self.sharedCatalog()
        for c in try Self.vectorCases("compute.json") {
            let input = try #require(c["input"] as? [String: Any])
            let got = ActionMath.compute(catalog: catalog, op: input["op"] as? String ?? "", args: input["args"] as? [String: Any] ?? [:])
            #expect(Self.same(got, c["expected"]), "\(c["name"] ?? "?"): \(String(describing: got))")
        }
    }
}
