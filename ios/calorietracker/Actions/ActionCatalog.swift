import Foundation

/// `shared/actions/action_catalog.json`, bundled byte-identical as `Actions/Resources/action_catalog.json`
/// (docs/actions.md). Every Siri / Shortcuts / deep-link / Coach action is described here once; the
/// intents, the deep-link router and the Coach tools all read this instead of re-declaring rules.
nonisolated struct ActionCatalog: Sendable {
    nonisolated enum Kind: String, Sendable { case get, set, search, open }
    nonisolated enum ParamType: String, Sendable { case number, integer, string, `enum`, metric, entity }

    nonisolated struct UnitFamily: Sendable {
        let canonical: String
        let enumName: String
        let factors: [String: Double]
    }

    nonisolated struct RangeBy: Sendable {
        let param: String
        let ranges: [String: (Double, Double)]
    }

    nonisolated struct Param: Sendable {
        let name: String
        let type: ParamType
        let required: Bool
        let defaultValue: ActionRawValue?
        let defaultPref: String?
        let enumName: String?
        let entity: String?
        let min: Double?
        let max: Double?
        let maxLength: Int?
        let allowed: [Double]?
        let unitParam: String?
        let unitFamily: String?
        let rangeBy: RangeBy?
        let summary: String
    }

    nonisolated struct Action: Sendable, Identifiable {
        let id: String
        let kind: Kind
        let domain: String
        let title: String
        let summary: String
        let params: [Param]
        let requiresOneOf: [[String]]
        let aiUnless: [String]
        let outputKind: String
        let outputEntity: String?
        let outputFields: [String]
        let permissions: [String]
        let confirmation: String
        let requiresUnlock: Bool
        let opensApp: Bool
        let surfaces: Set<String>
        let coachMode: String
        let coachTool: String?
        let screen: String

        func param(_ name: String) -> Param? { params.first { $0.name == name } }
    }

    let enums: [String: [String]]
    let units: [String: UnitFamily]
    let entities: [String: String]
    let actions: [Action]
    private let byID: [String: Int]

    func action(_ id: String) -> Action? { byID[id].map { actions[$0] } }

    func unitFamily(forEnum name: String) -> UnitFamily? { units.values.first { $0.enumName == name } }

    init(data: Data) throws {
        guard let root = try JSONSerialization.jsonObject(with: data) as? [String: Any],
              root["format"] as? String == "ayuvo-actions-catalog"
        else { throw CocoaError(.coderReadCorrupt) }
        enums = (root["enums"] as? [String: [String]]) ?? [:]
        var units: [String: UnitFamily] = [:]
        for (name, value) in (root["units"] as? [String: [String: Any]]) ?? [:] {
            let factors = ((value["factors"] as? [String: NSNumber]) ?? [:]).mapValues(\.doubleValue)
            units[name] = UnitFamily(canonical: value["canonical"] as? String ?? "", enumName: value["enum"] as? String ?? "", factors: factors)
        }
        self.units = units
        entities = (root["entities"] as? [String: String]) ?? [:]
        actions = ((root["actions"] as? [[String: Any]]) ?? []).map(Self.action)
        byID = Dictionary(actions.enumerated().map { ($0.element.id, $0.offset) }, uniquingKeysWith: { first, _ in first })
    }

    private static func action(_ a: [String: Any]) -> Action {
        let output = a["output"] as? [String: Any] ?? [:]
        return Action(
            id: a["id"] as? String ?? "",
            kind: Kind(rawValue: a["kind"] as? String ?? "") ?? .get,
            domain: a["domain"] as? String ?? "",
            title: a["title"] as? String ?? "",
            summary: a["summary"] as? String ?? "",
            params: ((a["params"] as? [[String: Any]]) ?? []).map(param),
            requiresOneOf: (a["requires_one_of"] as? [[String]]) ?? [],
            aiUnless: (a["ai_unless"] as? [String]) ?? [],
            outputKind: output["kind"] as? String ?? "none",
            outputEntity: output["entity"] as? String,
            outputFields: (output["fields"] as? [String]) ?? [],
            permissions: (a["permissions"] as? [String]) ?? [],
            confirmation: a["confirmation"] as? String ?? "never",
            requiresUnlock: a["requires_unlock"] as? Bool ?? true,
            opensApp: a["opens_app"] as? Bool ?? false,
            surfaces: Set((a["surfaces"] as? [String]) ?? []),
            coachMode: a["coach_mode"] as? String ?? "none",
            coachTool: a["coach_tool"] as? String,
            screen: a["screen"] as? String ?? ""
        )
    }

    private static func param(_ p: [String: Any]) -> Param {
        var rangeBy: RangeBy?
        if let by = p["range_by"] as? [String: Any], let param = by["param"] as? String {
            var ranges: [String: (Double, Double)] = [:]
            for (key, pair) in (by["ranges"] as? [String: [NSNumber]]) ?? [:] where pair.count == 2 {
                ranges[key] = (pair[0].doubleValue, pair[1].doubleValue)
            }
            rangeBy = RangeBy(param: param, ranges: ranges)
        }
        return Param(
            name: p["name"] as? String ?? "",
            type: ParamType(rawValue: p["type"] as? String ?? "") ?? .string,
            required: p["required"] as? Bool ?? false,
            defaultValue: p.keys.contains("default") ? ActionRawValue.from(p["default"]) : nil,
            defaultPref: p["default_pref"] as? String,
            enumName: p["enum"] as? String,
            entity: p["entity"] as? String,
            min: (p["min"] as? NSNumber)?.doubleValue,
            max: (p["max"] as? NSNumber)?.doubleValue,
            maxLength: (p["max_length"] as? NSNumber)?.intValue,
            allowed: (p["allowed"] as? [NSNumber])?.map(\.doubleValue),
            unitParam: p["unit_param"] as? String,
            unitFamily: p["unit_family"] as? String,
            rangeBy: rangeBy,
            summary: p["summary"] as? String ?? ""
        )
    }

    static let shared: ActionCatalog = {
        for bundle in [Bundle.main, Bundle(for: BundleMarker.self)] {
            if let url = bundle.url(forResource: "action_catalog", withExtension: "json"),
               let data = try? Data(contentsOf: url),
               let catalog = try? ActionCatalog(data: data) {
                return catalog
            }
        }
        return ActionCatalog.empty
    }()

    private static let empty = ActionCatalog()

    private init() {
        enums = [:]
        units = [:]
        entities = [:]
        actions = []
        byID = [:]
    }

    private final class BundleMarker {}
}
