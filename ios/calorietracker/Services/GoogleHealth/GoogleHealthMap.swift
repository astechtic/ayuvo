import Foundation

/// Decoded `shared/health/google_health_map.json` (docs/google-health.md), bundled
/// byte-for-byte like `shared/exercises/exercises.json`. `GoogleHealthMapTests` compares the
/// bundled copy with the shared file and runs the shared test vectors against the mapper.
/// The Health Connect (`hc`) half of each entry is Android's and is not decoded here.
nonisolated struct GoogleHealthMap: Decodable, Sendable {
    nonisolated struct API: Decodable, Sendable {
        let baseURL: String
        let listPath: String
        let authURL: String
        let tokenURL: String
        let revokeURL: String
        let userinfoURL: String
        let scopePrefix: String
        let identityScopes: [String]
        let initialBackfillDays: Int
        let overlapDays: Int
        let maxConcurrentTypes: Int
        let autoSyncMinIntervalS: Int
        let manualSyncMinIntervalS: Int

        enum CodingKeys: String, CodingKey {
            case baseURL = "base_url"
            case listPath = "list_path"
            case authURL = "auth_url"
            case tokenURL = "token_url"
            case revokeURL = "revoke_url"
            case userinfoURL = "userinfo_url"
            case scopePrefix = "scope_prefix"
            case identityScopes = "identity_scopes"
            case initialBackfillDays = "initial_backfill_days"
            case overlapDays = "overlap_days"
            case maxConcurrentTypes = "max_concurrent_types"
            case autoSyncMinIntervalS = "auto_sync_min_interval_s"
            case manualSyncMinIntervalS = "manual_sync_min_interval_s"
        }

        /// `GET …/dataTypes/{gh_type}/dataPoints`.
        func listURL(ghType: String) -> URL? {
            URL(string: baseURL + listPath.replacingOccurrences(of: "{gh_type}", with: ghType))
        }
    }

    /// One consent checkbox in the setup flow; its scopes are suffixes of `api.scopePrefix`.
    nonisolated struct ScopeGroup: Decodable, Sendable, Identifiable, Hashable {
        let id: String
        let scopes: [String]
        let displayName: String

        enum CodingKeys: String, CodingKey {
            case id, scopes
            case displayName = "display_name"
        }
    }

    nonisolated struct EchoGuard: Decodable, Sendable {
        let skipMirrorWhenPackageIn: [String]
        let skipMirrorWhenPlatformAndSameOS: Bool
        let duplicateWindowMs: Int64
        let duplicateValueEpsilonRatio: Double

        enum CodingKeys: String, CodingKey {
            case skipMirrorWhenPackageIn = "skip_mirror_when_package_in"
            case skipMirrorWhenPlatformAndSameOS = "skip_mirror_when_platform_and_same_os"
            case duplicateWindowMs = "duplicate_window_ms"
            case duplicateValueEpsilonRatio = "duplicate_value_epsilon_ratio"
        }
    }

    nonisolated enum TimeKind: String, Decodable, Sendable {
        case sample, interval, date, session
    }

    nonisolated struct ValueSpec: Decodable, Sendable {
        let path: String
        let scale: Double?
        let converter: String?
    }

    nonisolated struct CategorySpec: Decodable, Sendable {
        let path: String?
        let map: [String: Int]
        let defaultValue: Int

        enum CodingKeys: String, CodingKey {
            case path, map
            case defaultValue = "default"
        }
    }

    /// HealthKit write target; `unit` is a `HealthKitUnits` spelling (nil for categories).
    nonisolated struct HKTarget: Decodable, Sendable {
        let identifier: String
        let unit: String?
    }

    nonisolated struct DataType: Decodable, Sendable, Identifiable {
        let ghType: String
        /// Key of the data point's union member (`point.steps`, `point.heartRate`, …).
        let union: String
        let scopeGroup: String
        let time: TimeKind
        let filter: String
        let pageSize: Int
        let typeID: String
        let optional: Bool?
        let value: ValueSpec?
        let value2: ValueSpec?
        let value3: ValueSpec?
        let category: CategorySpec?
        let extra: [String: String]?
        let converter: String?
        let stageMap: [String: Int]?
        let mealMap: [String: Int]?
        let hk: HKTarget?

        var id: String { ghType }
        /// Optional types may not exist for every account / API revision: a 400/404 marks them `unsupported`.
        var isOptional: Bool { optional ?? false }

        enum CodingKeys: String, CodingKey {
            case ghType = "gh_type"
            case union
            case scopeGroup = "scope_group"
            case time, filter
            case pageSize = "page_size"
            case typeID = "type_id"
            case optional, value, value2, value3, category, extra, converter
            case stageMap = "stage_map"
            case mealMap = "meal_map"
            case hk
        }
    }

    let mapVersion: Int
    let api: API
    let scopeGroups: [ScopeGroup]
    let echoGuard: EchoGuard
    let types: [DataType]

    enum CodingKeys: String, CodingKey {
        case mapVersion = "map_version"
        case api
        case scopeGroups = "scope_groups"
        case echoGuard = "echo_guard"
        case types
    }

    // MARK: - Loading

    static let resourceName = "google_health_map"

    static func load(from data: Data) throws -> GoogleHealthMap {
        try JSONDecoder().decode(GoogleHealthMap.self, from: data)
    }

    /// The copy in the app bundle; nil only when the resource is missing (a build problem).
    static let bundled: GoogleHealthMap? = {
        guard let url = Bundle.main.url(forResource: resourceName, withExtension: "json"),
              let data = try? Data(contentsOf: url) else { return nil }
        return try? load(from: data)
    }()

    // MARK: - Lookups

    func type(_ ghType: String) -> DataType? {
        types.first { $0.ghType == ghType }
    }

    func scopeGroup(_ id: String) -> ScopeGroup? {
        scopeGroups.first { $0.id == id }
    }

    /// Full scope URLs for the chosen groups plus the identity scopes (`openid email`).
    func requestedScopes(groups: Set<String>) -> [String] {
        let data = scopeGroups.filter { groups.contains($0.id) }.flatMap { $0.scopes.map { api.scopePrefix + $0 } }
        return api.identityScopes + data
    }

    /// A group counts as granted only when every one of its scopes was granted.
    func grantedGroups(scopes granted: Set<String>) -> Set<String> {
        Set(scopeGroups.filter { group in group.scopes.allSatisfy { granted.contains(api.scopePrefix + $0) } }.map(\.id))
    }

    /// The type's scope group is granted (types are gated by the scopes actually granted).
    func isGranted(_ type: DataType, scopes granted: Set<String>) -> Bool {
        guard let group = scopeGroup(type.scopeGroup) else { return false }
        return group.scopes.allSatisfy { granted.contains(api.scopePrefix + $0) }
    }

    // MARK: - Filters

    /// Fills the AIP-160 filter template: `{from_rfc3339}` (UTC instant), `{from_civil}`
    /// (wall time in `timeZone`) and `{from_date}` (local date in `timeZone`).
    func filter(for type: DataType, fromMs: Int64, timeZone: TimeZone) -> String {
        let offset = timeZone.secondsFromGMT(for: Date(timeIntervalSince1970: Double(fromMs) / 1000))
        let utc = Self.civilText(ms: fromMs, offsetS: 0) + "Z"
        let civil = Self.civilText(ms: fromMs, offsetS: offset)
        return type.filter
            .replacingOccurrences(of: "{from_rfc3339}", with: utc)
            .replacingOccurrences(of: "{from_civil}", with: civil)
            .replacingOccurrences(of: "{from_date}", with: String(civil.prefix(10)))
    }

    /// `yyyy-MM-ddTHH:mm:ss` of `ms` shifted by `offsetS` (no fraction, no offset).
    static func civilText(ms: Int64, offsetS: Int) -> String {
        String(ISO8601Fast.format(ms: ms, offsetS: offsetS).prefix(19))
    }
}
