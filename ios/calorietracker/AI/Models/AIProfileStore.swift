import Foundation

/// Storage for the saved model profiles and the four role pointers (docs/ai-models.md §4).
///
/// Profiles are the source of truth. The legacy flat keys (`selectedAIProvider`, the three
/// fallback pairs, …) stay behind as a write-through mirror so every existing reader —
/// `GeminiService.dispatch`, `RecordsAIExtractor`, speech's initial provider — keeps working
/// untouched. Writes flow profiles → flat keys, with exactly one exception: onboarding still writes
/// the flat keys and nothing else, and `adoptLegacyPrimaryIfNeeded` folds that choice back in.
extension AIProviderSettings {

    // MARK: - Keys

    static let profilesKey = "aiModelProfiles"
    static let rolePointersKey = "aiRolePointers"
    static let primaryFingerprintKey = "aiPrimaryProjectedFingerprint"
    static let profilesMigrationVersionKey = "aiProfilesMigrationVersion"
    static let profileKeychainPrefix = "aiprofilekey_"

    // MARK: - Raw JSON (the form the reference reasons over)

    static func profilesRJ(_ defaults: UserDefaults = .standard) -> RJ {
        guard let text = defaults.string(forKey: profilesKey), let parsed = RJ.parse(text),
              parsed.array != nil else { return .arr([]) }
        return parsed
    }

    static func rolesRJ(_ defaults: UserDefaults = .standard) -> RJ {
        guard let text = defaults.string(forKey: rolePointersKey), let parsed = RJ.parse(text),
              parsed.object != nil else { return .obj([:]) }
        return parsed
    }

    private static func store(profiles: RJ, roles: RJ, in defaults: UserDefaults) {
        defaults.set(profiles.jsonText, forKey: profilesKey)
        defaults.set(roles.jsonText, forKey: rolePointersKey)
    }

    // MARK: - Typed view

    static var profiles: [AIModelProfile] {
        (profilesRJ().array ?? []).compactMap(AIModelProfile.init(rj:))
    }

    static func profile(id: String?) -> AIModelProfile? {
        guard let id else { return nil }
        return profiles.first { $0.id == id }
    }

    static var rolePointers: [AIRole: AIRolePointer] {
        let raw = rolesRJ()
        var out: [AIRole: AIRolePointer] = [:]
        for role in AIRole.allCases {
            let row = raw[role.rawValue]
            out[role] = AIRolePointer(profileID: row["profile_id"].string,
                                      enabled: row["enabled"].bool ?? (role == .image))
        }
        return out
    }

    static func profile(for role: AIRole) -> AIModelProfile? {
        profile(id: rolePointers[role]?.profileID)
    }

    // MARK: - Mutation (the single door, so the mirror can never go stale)

    /// Replaces the whole store and re-projects the flat keys in the same breath. Every mutation
    /// goes through here; that is what `AIProfileMirrorTests` checks.
    static func setStore(profiles: [AIModelProfile], roles: [AIRole: AIRolePointer],
                         affecting: Set<AIRole>? = nil, defaults: UserDefaults = .standard) {
        let profilesRJ = RJ.arr(profiles.map(\.rj))
        var rolesDict: [String: RJ] = [:]
        for role in AIRole.allCases {
            rolesDict[role.rawValue] = (roles[role] ?? AIRolePointer(profileID: nil,
                                                                    enabled: role == .image)).rj
        }
        let rolesRJ = RJ.obj(rolesDict)
        store(profiles: profilesRJ, roles: rolesRJ, in: defaults)
        projectToLegacyKeys(profiles: profilesRJ, roles: rolesRJ, only: affecting, defaults: defaults)
    }

    @discardableResult
    static func upsert(_ profile: AIModelProfile, defaults: UserDefaults = .standard)
        -> AIModelProfile {
        var all = profiles
        if let index = all.firstIndex(where: { $0.id == profile.id }) {
            all[index] = profile
        } else {
            all.append(profile)
        }
        setStore(profiles: all, roles: rolePointers, affecting: Set(roles(using: profile.id)),
                 defaults: defaults)
        return profile
    }

    /// Removes a profile, its own credential, and every role that pointed at it.
    ///
    /// The provider key is deliberately left alone: it has owners outside the profile layer —
    /// onboarding, the legacy per-provider screen, and `SpeechSettings`, which falls back to the
    /// matching AI provider's key (docs/ai-models.md rule 3).
    static func deleteProfile(id: String, defaults: UserDefaults = .standard) {
        guard let doomed = profile(id: id) else { return }
        let plan = AIRef.keysToDelete(.obj(["profile": doomed.rj]))
        for key in (plan["profile_keys"].array ?? []).compactMap(\.string) {
            KeychainHelper.delete(key: key)
        }
        var pointers = rolePointers
        for role in AIRole.allCases where pointers[role]?.profileID == id {
            pointers[role] = AIRolePointer(profileID: nil, enabled: false)
        }
        setStore(profiles: profiles.filter { $0.id != id }, roles: pointers, defaults: defaults)
    }

    /// The provider a role's flat keys currently name, read raw (no getter side effects).
    static func legacyProvider(for role: AIRole, defaults: UserDefaults = .standard) -> AIProvider? {
        let key: String
        switch role {
        case .image: key = "selectedAIProvider"
        case .text: key = "selectedTextAIProvider"
        case .imageFallback: key = "selectedFallbackAIProvider"
        case .textFallback: key = "selectedTextFallbackAIProvider"
        }
        return defaults.string(forKey: key).flatMap(AIProvider.init(rawValue:))
    }

    /// Folds a write to the legacy flat keys into the profile store.
    ///
    /// Every setter on `AIProviderSettings` calls this, which is why the mirror cannot go stale: the
    /// old Settings rows, onboarding and anything else that still assigns `selectedAIProvider` all
    /// end up creating or editing the right profile without knowing profiles exist. The
    /// fingerprint/adoption channel (§4) stays as the safety net for a writer that reaches past the
    /// setters straight into UserDefaults.
    static func syncRoleFromLegacy(_ role: AIRole, defaults: UserDefaults = .standard) {
        guard let provider = legacyProvider(for: role, defaults: defaults) else { return }
        let modelKey: String
        let urlPrefix: String
        switch role {
        case .image: modelKey = "selectedAIModel"; urlPrefix = "customBaseURL_"
        case .text: modelKey = "selectedTextAIModel"; urlPrefix = "customBaseURL_"
        case .imageFallback: modelKey = "selectedFallbackAIModel"; urlPrefix = "fallbackCustomBaseURL_"
        case .textFallback: modelKey = "selectedTextFallbackAIModel"; urlPrefix = "fallbackCustomBaseURL_"
        }
        assignRole(role, provider: provider,
                   model: defaults.string(forKey: modelKey) ?? "",
                   baseURL: defaults.string(forKey: urlPrefix + provider.rawValue),
                   enabled: legacyEnabled(for: role, defaults: defaults),
                   defaults: defaults)
    }

    /// The role's on/off flag as the flat keys hold it. The image role has no flag: it is always on.
    static func legacyEnabled(for role: AIRole, defaults: UserDefaults = .standard) -> Bool {
        switch role {
        case .image: return true
        case .text: return defaults.bool(forKey: "separateTextProviderEnabled")
        case .imageFallback: return defaults.bool(forKey: "aiFallbackEnabled")
        case .textFallback: return defaults.bool(forKey: "textAIFallbackEnabled")
        }
    }

    /// Keeps a role pointer's flag in step when only the flat toggle was flipped.
    static func syncRoleEnabledFromLegacy(_ role: AIRole, defaults: UserDefaults = .standard) {
        var pointers = rolePointers
        let enabled = legacyEnabled(for: role, defaults: defaults)
        guard pointers[role]?.enabled != enabled else { return }
        pointers[role]?.enabled = enabled
        setStore(profiles: profiles, roles: pointers, affecting: [role], defaults: defaults)
    }

    /// Points a role at a configuration, forking the profile when another role shares it.
    ///
    /// This is what the per-role editors in Settings write through. Changing the primary must not
    /// silently change a fallback that happens to share its profile — the four flat slots used to
    /// guarantee that, and `AIRef.assignRole` keeps the guarantee.
    @discardableResult
    static func assignRole(_ role: AIRole, provider: AIProvider, model: String,
                           baseURL: String? = nil, enabled: Bool? = nil,
                           nowMs: Int = Int(Date().timeIntervalSince1970 * 1000),
                           defaults: UserDefaults = .standard) -> String? {
        let result = AIRef.assignRole(.obj([
            "profiles": profilesRJ(defaults),
            "roles": rolesRJ(defaults),
            "role": .str(role.rawValue),
            "provider": .str(provider.rawValue),
            "model": .str(model),
            "base_url": RJ.string(baseURL),
            "now_ms": .int(nowMs),
            "env": .obj(["platform": .str(AIRef.platform)]),
        ]))
        guard result["action"].string != "ignored" else { return nil }
        let touched: Set<AIRole> = [role]
        // The role's on/off flag belongs to whoever set it; the assignment only moves the pointer.
        // Without this the projection would write the pointer's stale flag back over a toggle the
        // caller had just made.
        var roles = result["roles"].object ?? [:]
        if let enabled {
            roles[role.rawValue] = .obj(["profile_id": result["profile_id"], "enabled": .bool(enabled)])
        }
        let resolvedRoles = RJ.obj(roles)
        // A key issued for one provider is meaningless at another, so the reference tells us when
        // the profile's own key has to go.
        if let stale = result["cleared_profile_key"].string {
            KeychainHelper.delete(key: profileKeychainPrefix + stale)
        }
        store(profiles: result["profiles"], roles: resolvedRoles, in: defaults)
        projectToLegacyKeys(profiles: result["profiles"], roles: resolvedRoles, only: touched,
                            defaults: defaults)
        return result["profile_id"].string
    }

    /// Adds a saved configuration that no role uses yet — Settings' "Add model".
    @discardableResult
    static func addProfile(provider: AIProvider, model: String, baseURL: String? = nil,
                           nickname: String? = nil,
                           nowMs: Int = Int(Date().timeIntervalSince1970 * 1000),
                           defaults: UserDefaults = .standard) -> String? {
        let result = AIRef.addProfile(.obj([
            "profiles": profilesRJ(defaults),
            "provider": .str(provider.rawValue),
            "model": .str(model),
            "base_url": RJ.string(baseURL),
            "nickname": RJ.string(nickname),
            "now_ms": .int(nowMs),
            "env": .obj(["platform": .str(AIRef.platform)]),
        ]))
        guard result["action"].string != "ignored" else { return nil }
        // No role changed, so nothing is mirrored into the flat keys.
        store(profiles: result["profiles"], roles: rolesRJ(defaults), in: defaults)
        return result["profile_id"].string
    }

    static func renameProfile(id: String, to name: String, defaults: UserDefaults = .standard) {
        guard var profile = profile(id: id) else { return }
        profile.nickname = name.trimmingCharacters(in: .whitespacesAndNewlines)
        profile.updatedMs = Int(Date().timeIntervalSince1970 * 1000)
        upsert(profile, defaults: defaults)
    }

    /// Which roles a profile currently serves, for the "used by" line in the Models list.
    static func roles(using profileID: String) -> [AIRole] {
        let pointers = rolePointers
        return AIRole.allCases.filter { pointers[$0]?.profileID == profileID }
    }

    static func setRole(_ role: AIRole, profileID: String?, enabled: Bool,
                        defaults: UserDefaults = .standard) {
        var pointers = rolePointers
        pointers[role] = AIRolePointer(profileID: profileID, enabled: enabled)
        setStore(profiles: profiles, roles: pointers, defaults: defaults)
    }

    // MARK: - Credentials (docs/ai-models.md §4)

    static func apiKey(for profile: AIModelProfile) -> String? {
        let source = AIRef.resolveCredential(
            profile.credentialRef,
            profile.provider?.requiresAPIKey ?? true,
            KeychainHelper.load(key: profileKeychainPrefix + profile.id) != nil,
            KeychainHelper.load(key: "apikey_" + profile.providerToken) != nil
        )
        switch source {
        case "profile": return KeychainHelper.load(key: profileKeychainPrefix + profile.id)
        case "provider": return KeychainHelper.load(key: "apikey_" + profile.providerToken)
        default: return nil
        }
    }

    /// Stores a key for one profile.
    ///
    /// A value equal to the provider's existing key keeps the `provider:` reference and writes
    /// nothing: re-pasting the same key must not fork a credential that a later rotation on the
    /// per-provider screen would then miss.
    static func setAPIKey(_ key: String?, for profile: AIModelProfile,
                          defaults: UserDefaults = .standard) {
        var updated = profile
        let providerKey = KeychainHelper.load(key: "apikey_" + profile.providerToken)
        let trimmed = key?.trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmed == nil || trimmed?.isEmpty == true {
            KeychainHelper.delete(key: profileKeychainPrefix + profile.id)
            updated.credentialRef = "provider:" + profile.providerToken
        } else if trimmed == providerKey {
            KeychainHelper.delete(key: profileKeychainPrefix + profile.id)
            updated.credentialRef = "provider:" + profile.providerToken
        } else {
            KeychainHelper.save(key: profileKeychainPrefix + profile.id, value: trimmed!)
            updated.credentialRef = "profile:" + profile.id
            // Keep the legacy mirror complete for unconverted readers and for a rollback, but only
            // when the provider has no key of its own to overwrite.
            if providerKey == nil, rolePointers[.image]?.profileID == profile.id {
                KeychainHelper.save(key: "apikey_" + profile.providerToken, value: trimmed!)
            }
        }
        upsert(updated, defaults: defaults)
    }

    static var profileIDsWithOwnKey: [String] {
        profiles.filter { KeychainHelper.load(key: profileKeychainPrefix + $0.id) != nil }
            .map(\.id)
    }

    // MARK: - The legacy slots

    /// The four flat slots, read raw. The `selectedProvider` / `selectedModel` getters write
    /// defaults as a side effect, which a migration must not depend on.
    static func legacySlots(_ defaults: UserDefaults = .standard) -> RJ {
        func slot(_ providerKey: String, _ modelKey: String, _ urlPrefix: String,
                  enabled: Bool) -> RJ {
            let provider = defaults.string(forKey: providerKey)
            let baseURL = provider.flatMap { defaults.string(forKey: urlPrefix + $0) }
            return .obj([
                "provider": RJ.string(provider),
                "model": .str(defaults.string(forKey: modelKey) ?? ""),
                "base_url": RJ.string(baseURL),
                "enabled": .bool(enabled),
            ])
        }
        return .obj([
            "image": slot("selectedAIProvider", "selectedAIModel", "customBaseURL_", enabled: true),
            "text": slot("selectedTextAIProvider", "selectedTextAIModel", "customBaseURL_",
                         enabled: defaults.bool(forKey: "separateTextProviderEnabled")),
            "image_fallback": slot("selectedFallbackAIProvider", "selectedFallbackAIModel",
                                   "fallbackCustomBaseURL_",
                                   enabled: defaults.bool(forKey: "aiFallbackEnabled")),
            "text_fallback": slot("selectedTextFallbackAIProvider", "selectedTextFallbackAIModel",
                                  "fallbackCustomBaseURL_",
                                  enabled: defaults.bool(forKey: "textAIFallbackEnabled")),
        ])
    }

    /// Profiles → the flat keys. Lossy on purpose (§4): nickname, id, `vertex{}` and
    /// `credential_ref` have no legacy home.
    ///
    /// A base URL is written when the profile has one and left alone when it does not: clearing a
    /// shared per-provider key here could destroy a setting the profile layer does not own.
    static func projectToLegacyKeys(profiles: RJ, roles: RJ, only: Set<AIRole>? = nil,
                                    defaults: UserDefaults = .standard) {
        let projection = AIRef.projectLegacy(profiles.array ?? [], roles)
        let slots = projection["slots"]
        func write(_ role: String, _ providerKey: String, _ modelKey: String,
                   _ urlPrefix: String, _ enabledKey: String?) {
            // Only the role that changed is mirrored. Rewriting all four from the store would undo
            // a slot somebody set straight in UserDefaults since the last sync — which is exactly
            // what tests and the older Settings rows do.
            if let only, let named = AIRole(rawValue: role), !only.contains(named) { return }
            let slot = slots[role]
            guard let provider = slot["provider"].string, !provider.isEmpty else { return }
            defaults.set(provider, forKey: providerKey)
            defaults.set(slot["model"].string ?? "", forKey: modelKey)
            if let url = slot["base_url"].string, !url.isEmpty {
                defaults.set(url, forKey: urlPrefix + provider)
            }
            if let enabledKey { defaults.set(slot["enabled"].bool ?? false, forKey: enabledKey) }
        }
        write("image", "selectedAIProvider", "selectedAIModel", "customBaseURL_", nil)
        write("text", "selectedTextAIProvider", "selectedTextAIModel", "customBaseURL_",
              "separateTextProviderEnabled")
        write("image_fallback", "selectedFallbackAIProvider", "selectedFallbackAIModel",
              "fallbackCustomBaseURL_", "aiFallbackEnabled")
        write("text_fallback", "selectedTextFallbackAIProvider", "selectedTextFallbackAIModel",
              "fallbackCustomBaseURL_", "textAIFallbackEnabled")
        defaults.set(projection["fingerprint"].string ?? "", forKey: primaryFingerprintKey)
    }

    // MARK: - Startup

    /// Runs the one-shot migration, then adopts anything onboarding wrote since the last launch.
    /// Safe to call on every launch and after a data reset.
    static func prepareProfiles(defaults: UserDefaults = .standard, nowMs: Int) {
        migrateProfilesIfNeeded(defaults: defaults, nowMs: nowMs)
        adoptLegacyPrimaryIfNeeded(defaults: defaults, nowMs: nowMs)
    }

    static func migrateProfilesIfNeeded(defaults: UserDefaults = .standard, nowMs: Int) {
        let version = defaults.integer(forKey: profilesMigrationVersionKey)
        guard version < AIRef.migrationVersion else { return }
        let result = AIRef.migrateProfiles(.obj([
            "migration_version": .int(version),
            "now_ms": .int(nowMs),
            "slots": legacySlots(defaults),
            "existing_profiles": profilesRJ(defaults),
            "roles": rolesRJ(defaults),
            "env": .obj(["platform": .str(AIRef.platform)]),
        ]))
        store(profiles: result["profiles"], roles: result["roles"], in: defaults)
        projectToLegacyKeys(profiles: result["profiles"], roles: result["roles"], defaults: defaults)
        defaults.set(AIRef.migrationVersion, forKey: profilesMigrationVersionKey)
    }

    /// Onboarding writes only the flat keys. When what it wrote differs from the last projection,
    /// that choice is adopted as a profile — never ignored, and never duplicated.
    @discardableResult
    static func adoptLegacyPrimaryIfNeeded(defaults: UserDefaults = .standard, nowMs: Int)
        -> String {
        let provider = defaults.string(forKey: "selectedAIProvider")
        let observed = RJ.obj([
            "provider": RJ.string(provider),
            "model": .str(defaults.string(forKey: "selectedAIModel") ?? ""),
            "base_url": RJ.string(provider.flatMap { defaults.string(forKey: "customBaseURL_" + $0) }),
        ])
        let result = AIRef.adoptLegacyPrimary(.obj([
            "observed": observed,
            "fingerprint": RJ.string(defaults.string(forKey: primaryFingerprintKey)),
            "profiles": profilesRJ(defaults),
            "roles": rolesRJ(defaults),
            "now_ms": .int(nowMs),
            "env": .obj(["platform": .str(AIRef.platform)]),
        ]))
        if result["changed"].bool == true {
            store(profiles: result["profiles"], roles: result["roles"], in: defaults)
            projectToLegacyKeys(profiles: result["profiles"], roles: result["roles"],
                                only: [.image], defaults: defaults)
        }
        return result["reason"].string ?? "ignored"
    }

    /// Clears everything the profile layer owns. Called from `deleteAllData()`; with the migration
    /// version gone, a reset followed by onboarding re-runs the migration and yields one profile.
    static func deleteAllProfileData(defaults: UserDefaults = .standard) {
        for profile in profiles { KeychainHelper.delete(key: profileKeychainPrefix + profile.id) }
        defaults.removeObject(forKey: profilesKey)
        defaults.removeObject(forKey: rolePointersKey)
        defaults.removeObject(forKey: primaryFingerprintKey)
        defaults.removeObject(forKey: profilesMigrationVersionKey)
    }
}
