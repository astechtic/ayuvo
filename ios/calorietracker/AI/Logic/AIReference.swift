import Foundation

/// Swift port of `scripts/ai_reference.py` (docs/ai-models.md). Every function is pure and is driven
/// by `shared/ai/test-vectors/*.json` in `AIVectorTests`. The Python reference wins over this file
/// and over the prose; keep the two in step in the same change.
///
/// Reuses `RJ` (the records JSON value) so a vector can be fed in and compared without a bespoke
/// model layer. Platform code wraps these results in value types; the contract lives here.
enum AIRef {

    // MARK: - Constants (docs/ai-models.md §3-§8)

    static let roles = ["image", "text", "image_fallback", "text_fallback"]
    /// The primary role each fallback backs up.
    static let fallbackOf = ["image_fallback": "image", "text_fallback": "text"]
    /// Profile ids are `aip_` + four digits: ASCII, sortable, and identical on both platforms, so
    /// the per-profile credential account name is portable in a way the provider token is not.
    static let profileIDPrefix = "aip_"
    static let profileIDDigits = 4
    static let profileIDChars = Set("abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789_")
    static let maxProfileIDLength = 64
    /// Separates the profile id from the provider token inside `conversations.provider_override`.
    static let overridePrefix = "profile:"
    static let overrideSeparator: Character = "|"
    /// Joins the three parts of the primary-projection fingerprint. NUL can appear in no token.
    static let fingerprintSeparator = "\u{0}"
    static let migrationVersion = 1
    /// Chosen when nothing else is usable, matching today's `executableAIProviderOrDefault`.
    static let defaultProvider = "Google Gemini"
    static let credentialSources = ["none", "profile", "provider", "missing"]
    static let catalogStates = ["installed", "available", "needs_token", "ineligible"]
    /// Providers whose "endpoint" is the device itself: no base URL, no key, no network.
    static let localFormats: Set<String> = ["on_device", "litert_local"]
    static let gib = 1024 * 1024 * 1024
    /// This build's row filter in `shared/ai/providers.json`.
    static let platform = "ios"

    // MARK: - Small helpers

    static func trim(_ value: RJ) -> String {
        (value.string ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
    }

    static func trim(_ value: String?) -> String {
        (value ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
    }

    /// Whitespace only. Deliberately NOT the platform's `normalizedModelID`: its legacy-id upgrades
    /// run BEFORE this reference is called, so folding them in would let a migration depend on which
    /// upgrade version the device happened to be on.
    static func normalizeModelID(_ model: String?) -> String { trim(model) }

    private static func fold(_ text: String) -> String {
        text.split(whereSeparator: { $0.isWhitespace }).joined(separator: " ").lowercased()
    }

    /// The provider table the resolver reasons over.
    ///
    /// Callers may pass their own map. Otherwise the registry is used, narrowed to `env.platform`
    /// when one is given: Apple Intelligence is iOS-only, and a build that does not have a provider
    /// must not be able to migrate a profile onto it or resolve a route to it.
    static func providerDefaults(_ env: RJ) -> [String: RJ] {
        if let supplied = env["provider_defaults"].object, !supplied.isEmpty { return supplied }
        return AICatalog.providerDefaults(platform: env["platform"].string)
    }

    static func isLocal(_ spec: RJ) -> Bool { localFormats.contains(spec["api_format"].string ?? "") }

    static func defaultModel(for provider: String, _ table: [String: RJ]) -> String {
        (table[provider]?["models"].array?.first?.string) ?? ""
    }

    /// Which key carries the output cap in an OpenAI-compatible body.
    static func tokenLimitKey(_ provider: String?, _ model: String?) -> String {
        let limits = AICatalog.tokenLimit
        let provider = provider ?? ""
        let completion = limits["completion"].string ?? "max_completion_tokens"
        let always = (limits["always_completion"].array ?? []).compactMap(\.string)
        if always.contains(provider) { return completion }
        let conditional = (limits["conditional"].array ?? []).compactMap(\.string)
        if conditional.contains(provider) {
            let tail = String(normalizeModelID(model).lowercased().split(separator: "/",
                                                                        omittingEmptySubsequences: false).last ?? "")
            for prefix in (limits["completion_prefixes"].array ?? []).compactMap(\.string)
            where tail.hasPrefix(prefix) {
                return completion
            }
        }
        return limits["default"].string ?? "max_tokens"
    }

    // MARK: - §4 Profiles

    /// The identity of a configuration. Two slots with this key are one profile.
    static func profileKey(_ provider: String?, _ model: String?, _ baseURL: String?) -> [String] {
        [trim(provider), normalizeModelID(model), trim(baseURL)]
    }

    private static func profileKey(_ profile: RJ) -> [String] {
        profileKey(profile["provider"].string, profile["model_id"].string, profile["base_url"].string)
    }

    private static func nextProfileNumber(_ profiles: [RJ]) -> Int {
        var highest = 0
        for profile in profiles {
            guard let pid = profile["id"].string, pid.hasPrefix(profileIDPrefix) else { continue }
            let tail = String(pid.dropFirst(profileIDPrefix.count))
            if !tail.isEmpty, tail.allSatisfy(\.isNumber), let value = Int(tail) {
                highest = max(highest, value)
            }
        }
        return highest + 1
    }

    private static func formatProfileID(_ number: Int) -> String {
        var digits = String(number)
        while digits.count < profileIDDigits { digits = "0" + digits }
        return profileIDPrefix + digits
    }

    private static func nickname(_ provider: String, _ model: String, _ taken: inout Set<String>)
        -> String {
        let base = autoNickname(provider, model)
        if !taken.contains(fold(base)) {
            taken.insert(fold(base))
            return base
        }
        var n = 2
        while true {
            let candidate = "\(base) (\(n))"
            if !taken.contains(fold(candidate)) {
                taken.insert(fold(candidate))
                return candidate
            }
            n += 1
        }
    }

    private static func newProfile(_ provider: String, _ model: String, _ baseURL: String,
                                   _ nowMs: Int, _ profiles: inout [RJ],
                                   _ taken: inout Set<String>) -> RJ {
        let profile = RJ.obj([
            "id": .str(formatProfileID(nextProfileNumber(profiles))),
            "nickname": .str(nickname(provider, model, &taken)),
            "provider": .str(provider),
            "model_id": .str(model),
            "base_url": baseURL.isEmpty ? .null : .str(baseURL),
            "vertex": .null,
            // Every migrated or adopted profile inherits the provider's key. That single line is the
            // whole backward-compatibility story: nobody has to re-enter a key.
            "credential_ref": .str("provider:" + provider),
            "created_ms": .int(nowMs),
            "updated_ms": .int(nowMs),
        ])
        profiles.append(profile)
        return profile
    }

    private static func findOrCreate(_ provider: String, _ model: String, _ baseURL: String,
                                     _ nowMs: Int, _ profiles: inout [RJ],
                                     _ index: inout [String: RJ], _ taken: inout Set<String>)
        -> (profile: RJ, created: Bool) {
        let key = profileKey(provider, model, baseURL)
        let joined = key.joined(separator: fingerprintSeparator)
        if let existing = index[joined] { return (existing, false) }
        let profile = newProfile(key[0], key[1], key[2], nowMs, &profiles, &taken)
        index[joined] = profile
        return (profile, true)
    }

    private static func indexProfiles(_ profiles: [RJ]) -> ([String: RJ], Set<String>) {
        var index: [String: RJ] = [:]
        var taken: Set<String> = []
        for profile in profiles {
            let joined = profileKey(profile).joined(separator: fingerprintSeparator)
            if index[joined] == nil { index[joined] = profile }
            taken.insert(fold(profile["nickname"].string ?? ""))
        }
        return (index, taken)
    }

    private static func role(_ roles: RJ, _ name: String) -> RJ {
        let row = roles[name]
        return .obj(["profile_id": row["profile_id"].isNull ? .null : row["profile_id"],
                     "enabled": .bool(row["enabled"].bool ?? (name == "image"))])
    }

    /// One-shot conversion of the four legacy slots into profiles + role pointers. Deterministic and
    /// idempotent, which is what lets it run on every launch behind a version guard.
    static func migrateProfiles(_ payload: RJ) -> RJ {
        let version = Int(payload["migration_version"].double ?? 0)
        var profiles = payload["existing_profiles"].array ?? []
        let rolesIn = payload["roles"]
        if version >= migrationVersion {
            var out: [String: RJ] = [:]
            for name in roles { out[name] = role(rolesIn, name) }
            return .obj(["migration_version": .int(version), "profiles": .arr(profiles),
                         "roles": .obj(out)])
        }

        let nowMs = Int(payload["now_ms"].double ?? 0)
        let slots = payload["slots"]
        let table = providerDefaults(payload["env"])
        var (index, taken) = indexProfiles(profiles)
        var out: [String: RJ] = [:]
        for name in roles {
            let slot = slots[name]
            let provider = trim(slot["provider"])
            let model = normalizeModelID(slot["model"].string)
            let enabled = slot["enabled"].bool ?? (name == "image")
            let spec = table[provider] ?? .obj([:])
            if table[provider] == nil || (model.isEmpty && !isLocal(spec)) {
                // A slot naming a provider this build does not have, or carrying no model, describes
                // nothing runnable. Dropping it is safer than inventing a model it never had.
                out[name] = .obj(["profile_id": .null, "enabled": .bool(false)])
                continue
            }
            let made = findOrCreate(provider, model, trim(slot["base_url"]), nowMs, &profiles,
                                    &index, &taken)
            out[name] = .obj(["profile_id": made.profile["id"], "enabled": .bool(enabled)])
        }

        if out["image"]?["profile_id"].isNull ?? true {
            // Resolution must never come up empty, so the primary always ends with something real.
            let made = findOrCreate(defaultProvider, defaultModel(for: defaultProvider, table), "",
                                    nowMs, &profiles, &index, &taken)
            out["image"] = .obj(["profile_id": made.profile["id"], "enabled": .bool(true)])
        }
        return .obj(["migration_version": .int(migrationVersion), "profiles": .arr(profiles),
                     "roles": .obj(out)])
    }

    private static func autoNickname(_ provider: String, _ model: String) -> String {
        model.isEmpty ? provider : "\(provider) \u{b7} \(model)"
    }

    /// Add a saved configuration without wiring it to any role.
    ///
    /// Settings' "Add model" writes through this, so a standalone profile gets its id and its
    /// unique nickname from the same place a migrated one does. An identical configuration is
    /// returned rather than duplicated -- two rows naming one endpoint would be a list nobody can
    /// reason about.
    static func addProfile(_ payload: RJ) -> RJ {
        var profiles = payload["profiles"].array ?? []
        let table = providerDefaults(payload["env"])
        let provider = trim(payload["provider"])
        let model = normalizeModelID(payload["model"].string)
        let baseURL = trim(payload["base_url"])
        guard table[provider] != nil else {
            return .obj(["profiles": .arr(profiles), "profile_id": .null, "action": .str("ignored")])
        }
        var (index, taken) = indexProfiles(profiles)
        let key = profileKey(provider, model, baseURL).joined(separator: fingerprintSeparator)
        if let existing = index[key] {
            return .obj(["profiles": .arr(profiles), "profile_id": existing["id"],
                         "action": .str("reused")])
        }
        var profile = newProfile(provider, model, baseURL, Int(payload["now_ms"].double ?? 0),
                                 &profiles, &taken)
        let nickname = trim(payload["nickname"])
        if !nickname.isEmpty, var fields = profile.object {
            fields["nickname"] = .str(nickname)
            profile = .obj(fields)
            profiles[profiles.count - 1] = profile
        }
        index[key] = profile
        return .obj(["profiles": .arr(profiles), "profile_id": profile["id"],
                     "action": .str("created")])
    }

    /// Point a role at the configuration {provider, model, base_url}.
    ///
    /// Settings' per-role editors write through this. It is copy-on-write: editing the primary must
    /// not silently change a fallback that happens to share its profile, which is exactly what the
    /// four separate flat slots used to guarantee. So a profile another role also points at is never
    /// edited in place -- a new one is created instead.
    ///
    /// Editing in place keeps the profile id, so a conversation pinned to it follows the change
    /// rather than losing its pin. Changing the provider resets `credential_ref` to that provider's
    /// key and reports `cleared_profile_key`, because a key issued for one provider is meaningless
    /// at another.
    static func assignRole(_ payload: RJ) -> RJ {
        var profiles = payload["profiles"].array ?? []
        var rolesOut: [String: RJ] = [:]
        for name in roles { rolesOut[name] = role(payload["roles"], name) }
        let roleName = payload["role"].string ?? ""
        func result(_ profileID: RJ, _ action: String, _ cleared: RJ = .null) -> RJ {
            .obj(["profiles": .arr(profiles), "roles": .obj(rolesOut), "profile_id": profileID,
                  "action": .str(action), "cleared_profile_key": cleared])
        }
        guard roles.contains(roleName) else { return result(.null, "ignored") }

        let table = providerDefaults(payload["env"])
        let provider = trim(payload["provider"])
        let model = normalizeModelID(payload["model"].string)
        let baseURL = trim(payload["base_url"])
        guard table[provider] != nil else {
            return result(rolesOut[roleName]?["profile_id"] ?? .null, "ignored")
        }

        let nowMs = Int(payload["now_ms"].double ?? 0)
        var (index, taken) = indexProfiles(profiles)
        var byID: [String: Int] = [:]
        for (offset, profile) in profiles.enumerated() {
            if let id = profile["id"].string { byID[id] = offset }
        }
        let key = profileKey(provider, model, baseURL).joined(separator: fingerprintSeparator)

        if let existing = index[key] {
            rolesOut[roleName] = .obj(["profile_id": existing["id"],
                                       "enabled": rolesOut[roleName]?["enabled"] ?? .bool(true)])
            return result(existing["id"], "reused")
        }

        let pointer = rolesOut[roleName]?["profile_id"].string
        let currentIndex = pointer.flatMap { byID[$0] }
        let shared = pointer.map { id in
            roles.contains { $0 != roleName && rolesOut[$0]?["profile_id"].string == id }
        } ?? false

        if let currentIndex, !shared {
            var current = profiles[currentIndex].object ?? [:]
            let oldProvider = current["provider"]?.string ?? ""
            let oldModel = current["model_id"]?.string ?? ""
            let oldNickname = current["nickname"]?.string ?? ""
            let wasAuto = oldNickname == autoNickname(oldProvider, oldModel)
            let providerChanged = oldProvider != provider
            let cleared = providerChanged
                && (current["credential_ref"]?.string ?? "").hasPrefix("profile:")
            current["provider"] = .str(provider)
            current["model_id"] = .str(model)
            current["base_url"] = baseURL.isEmpty ? .null : .str(baseURL)
            current["updated_ms"] = .int(nowMs)
            if providerChanged {
                current["credential_ref"] = .str("provider:" + provider)
                current["vertex"] = .null
            }
            if wasAuto {
                // The name was ours, so it follows the configuration. A typed name does not.
                taken.remove(fold(oldNickname))
                current["nickname"] = .str(nickname(provider, model, &taken))
            }
            profiles[currentIndex] = .obj(current)
            let id = current["id"] ?? .null
            rolesOut[roleName] = .obj(["profile_id": id,
                                       "enabled": rolesOut[roleName]?["enabled"] ?? .bool(true)])
            return result(id, "edited", cleared ? id : .null)
        }

        let profile = newProfile(provider, model, baseURL, nowMs, &profiles, &taken)
        index[key] = profile
        rolesOut[roleName] = .obj(["profile_id": profile["id"],
                                   "enabled": rolesOut[roleName]?["enabled"] ?? .bool(true)])
        return result(profile["id"], "created")
    }

    /// What the mirror last wrote for the primary role.
    static func fingerprint(_ provider: String?, _ model: String?, _ baseURL: String?) -> String {
        profileKey(provider, model, baseURL).joined(separator: fingerprintSeparator)
    }

    /// Profiles -> the flat keys, the one direction writes flow in. Lossy on purpose: nickname, id,
    /// `vertex{}` and `credential_ref` have no legacy home, and nothing that reads them wants them.
    static func projectLegacy(_ profiles: [RJ], _ rolesIn: RJ) -> RJ {
        var byID: [String: RJ] = [:]
        for profile in profiles { if let id = profile["id"].string { byID[id] = profile } }
        var slots: [String: RJ] = [:]
        for name in roles {
            let row = rolesIn[name]
            guard let id = row["profile_id"].string, let profile = byID[id] else {
                slots[name] = .obj(["provider": .null, "model": .str(""), "base_url": .null,
                                    "enabled": .bool(false)])
                continue
            }
            slots[name] = .obj([
                "provider": profile["provider"],
                "model": profile["model_id"],
                "base_url": trim(profile["base_url"]).isEmpty ? .null : profile["base_url"],
                "enabled": .bool(row["enabled"].bool ?? (name == "image")),
            ])
        }
        let primary = slots["image"] ?? .null
        return .obj(["slots": .obj(slots),
                     "fingerprint": .str(fingerprint(primary["provider"].string ?? "",
                                                     primary["model"].string,
                                                     primary["base_url"].string ?? ""))])
    }

    /// The one inbound channel: a flat primary the mirror did not write, i.e. onboarding's choice.
    static func adoptLegacyPrimary(_ payload: RJ) -> RJ {
        let observed = payload["observed"]
        let provider = trim(observed["provider"])
        let model = normalizeModelID(observed["model"].string)
        let baseURL = trim(observed["base_url"])
        var profiles = payload["profiles"].array ?? []
        var out: [String: RJ] = [:]
        for name in roles { out[name] = role(payload["roles"], name) }
        let table = providerDefaults(payload["env"])
        let stored = payload["fingerprint"]
        let observedPrint = fingerprint(provider, model, baseURL)

        if table[provider] == nil {
            return .obj(["reason": .str("ignored"), "changed": .bool(false),
                         "profiles": .arr(profiles), "roles": .obj(out), "fingerprint": stored])
        }
        if stored.string == observedPrint {
            return .obj(["reason": .str("match"), "changed": .bool(false),
                         "profiles": .arr(profiles), "roles": .obj(out), "fingerprint": stored])
        }
        var (index, taken) = indexProfiles(profiles)
        let made = findOrCreate(provider, model, baseURL, Int(payload["now_ms"].double ?? 0),
                                &profiles, &index, &taken)
        let changed = made.created || out["image"]?["profile_id"].string != made.profile["id"].string
        out["image"] = .obj(["profile_id": made.profile["id"], "enabled": .bool(true)])
        return .obj(["reason": .str(made.created ? "created" : "adopted"), "changed": .bool(changed),
                     "profiles": .arr(profiles), "roles": .obj(out),
                     "fingerprint": .str(observedPrint)])
    }

    // MARK: - §4 Credentials

    /// Which store holds the key for this profile.
    ///
    /// The profile -> provider fall-through is load-bearing: `KeyStore.openOrRecover` deliberately
    /// wipes every key after an `AEADBadTagException`, and without it a profile whose own key
    /// vanished would be permanently dead rather than merely back on the provider key.
    static func resolveCredential(_ credentialRef: String?, _ requiresKey: Bool,
                                  _ hasProfileKey: Bool, _ hasProviderKey: Bool) -> String {
        let ref = credentialRef ?? ""
        if !requiresKey || ref == "none" || ref.isEmpty { return "none" }
        if ref.hasPrefix("profile:") {
            if hasProfileKey { return "profile" }
            return hasProviderKey ? "provider" : "missing"
        }
        if ref.hasPrefix("provider:") { return hasProviderKey ? "provider" : "missing" }
        return "missing"
    }

    /// What deleting a profile may remove from the two credential stores.
    static func keysToDelete(_ payload: RJ) -> RJ {
        let pid = payload["profile"]["id"].string ?? ""
        return .obj([
            "profile_keys": .arr(pid.isEmpty ? [] : [.str("aiprofilekey_" + pid)]),
            // Never, under any condition. The provider key has owners outside the profile layer:
            // onboarding, the legacy per-provider screen, and `KeyStore.speechApiKey`, which falls
            // back to the matching AI provider's key.
            "provider_keys": .arr([]),
        ])
    }

    // MARK: - §5 Role resolution

    private static func localModel(_ env: RJ, _ modelID: String) -> RJ {
        env["local_models"][modelID]
    }

    private static func credential(for profile: RJ, _ spec: RJ, _ env: RJ) -> RJ {
        let pid = profile["id"].string ?? ""
        let provider = profile["provider"].string ?? ""
        let profileKeys = Set((env["profiles_with_keys"].array ?? []).compactMap(\.string))
        let providerKeys = Set((env["providers_with_keys"].array ?? []).compactMap(\.string))
        let source = resolveCredential(profile["credential_ref"].string,
                                       spec["requires_key"].bool ?? false,
                                       profileKeys.contains(pid), providerKeys.contains(provider))
        switch source {
        case "profile": return .obj(["source": .str(source), "ref": .str("aiprofilekey_" + pid)])
        case "provider": return .obj(["source": .str(source), "ref": .str("apikey_" + provider)])
        default: return .obj(["source": .str(source), "ref": .null])
        }
    }

    private static func effectiveBaseURL(_ profile: RJ, _ spec: RJ) -> String {
        let own = trim(profile["base_url"])
        return own.isEmpty ? (spec["base_url"].string ?? "") : own
    }

    /// Why this profile cannot answer, or nil when it can.
    private static func usable(_ profile: RJ, _ env: RJ, _ table: [String: RJ],
                               _ requiresVision: Bool) -> String? {
        let provider = profile["provider"].string ?? ""
        guard let spec = table[provider] else { return "unknown_provider" }
        if requiresVision && !(spec["supports_vision"].bool ?? false) { return "no_vision" }
        let format = spec["api_format"].string ?? ""
        if format == "litert_local" {
            let local = localModel(env, profile["model_id"].string ?? "")
            if !(local["installed"].bool ?? false) { return "model_not_installed" }
            if requiresVision && !(local["vision"].bool ?? false) { return "no_vision" }
        } else if format == "on_device" {
            if !(env["on_device_available"].bool ?? false) { return "model_not_installed" }
        }
        if credential(for: profile, spec, env)["source"].string == "missing" { return "no_key" }
        if !isLocal(spec) && effectiveBaseURL(profile, spec).isEmpty { return "no_base_url" }
        return nil
    }

    private static func route(_ profile: RJ, _ env: RJ, _ table: [String: RJ],
                              _ roleName: String) -> [String: RJ] {
        let provider = profile["provider"].string ?? ""
        let spec = table[provider] ?? .obj([:])
        let model = normalizeModelID(profile["model_id"].string)
        let local = isLocal(spec)
        let timeout: RJ = (spec["configurable_timeout"].bool ?? false)
            ? env["request_timeout_seconds"] : .null
        var context: RJ = .null
        if spec["api_format"].string == "litert_local" {
            context = localModel(env, model)["context_tokens"]
        }
        return [
            "role": .str(roleName),
            "profile_id": profile["id"],
            "provider": .str(provider),
            "model": .str(model),
            "base_url": .str(local ? "" : effectiveBaseURL(profile, spec)),
            "api_format": spec["api_format"],
            "credential": credential(for: profile, spec, env),
            "vertex": profile["vertex"].isNull ? .null : profile["vertex"],
            "request_timeout_seconds": timeout,
            "max_response_tokens": env["max_response_tokens"],
            "token_limit_key": .str(tokenLimitKey(provider, model)),
            "context_tokens": context,
        ]
    }

    private static func synthesizedDefault(_ env: RJ, _ table: [String: RJ],
                                           _ roleName: String) -> [String: RJ] {
        let profile = RJ.obj([
            "id": .null, "nickname": .str(defaultProvider), "provider": .str(defaultProvider),
            "model_id": .str(defaultModel(for: defaultProvider, table)), "base_url": .null,
            "vertex": .null, "credential_ref": .str("provider:" + defaultProvider),
            "created_ms": .int(0), "updated_ms": .int(0),
        ])
        return route(profile, env, table, roleName)
    }

    /// Image requests always use the vision role; text requests use the dedicated one when on.
    static func roleFor(_ rolesIn: RJ, _ requiresVision: Bool) -> String {
        if requiresVision { return "image" }
        return (rolesIn["text"]["enabled"].bool ?? false) ? "text" : "image"
    }

    /// The single answer to "which provider, which model, which key".
    ///
    /// It NEVER substitutes a different profile for the one the user chose. A chosen profile that
    /// cannot answer comes back with `blocked` set and the caller refuses -- silently answering
    /// elsewhere would be wrong for an ordinary cloud profile and unacceptable for an on-device one,
    /// where the substitute would put health data on someone else's server without a word.
    ///
    /// A route always comes back so the caller has something to name in that refusal. Only a device
    /// with nothing configured at all gets the unblocked default, which is what the app shows today
    /// before the first key is entered.
    static func resolveRole(_ payload: RJ) -> RJ {
        let env = payload["env"]
        let table = providerDefaults(env)
        let profiles = payload["profiles"].array ?? []
        let rolesIn = payload["roles"]
        let requiresVision = env["requires_vision"].bool ?? false
        let roleName = payload["role"].string ?? roleFor(rolesIn, requiresVision)
        var byID: [String: RJ] = [:]
        for profile in profiles { if let id = profile["id"].string { byID[id] = profile } }

        let pointer = rolesIn[roleName]["profile_id"].string
        guard let pointer, let profile = byID[pointer] else {
            var fallback = synthesizedDefault(env, table, roleName)
            fallback["fell_back"] = .bool(true)
            // A dangling pointer means a profile was deleted without repointing its role, which
            // rule 1 forbids; an absent one on an empty device means nothing is set up yet. Only
            // the second is allowed to answer.
            fallback["blocked"] = (profiles.isEmpty && pointer == nil) ? .null : .str("no_profile")
            return .obj(fallback)
        }
        var found = route(profile, env, table, roleName)
        found["fell_back"] = .bool(false)
        found["blocked"] = RJ.string(usable(profile, env, table, requiresVision))
        return .obj(found)
    }

    private static func sameEndpoint(_ a: [String: RJ], _ b: RJ) -> Bool {
        (a["provider"]?.string ?? "") == (b["provider"].string ?? "")
            && (a["model"]?.string ?? "") == (b["model"].string ?? "")
            && (a["base_url"]?.string ?? "") == (b["base_url"].string ?? "")
    }

    /// The one retry, or nothing. A fallback pointing at the same endpoint as the primary is not a
    /// fallback; it is a guaranteed second failure. Compare the endpoint triple, never the profile
    /// id: two profiles can describe one endpoint, and one profile can never be its own fallback.
    static func resolveFallback(_ payload: RJ) -> RJ {
        let env = payload["env"]
        let table = providerDefaults(env)
        let profiles = payload["profiles"].array ?? []
        let rolesIn = payload["roles"]
        let primary = payload["primary"]
        let requiresVision = env["requires_vision"].bool ?? false
        // Unchanged from today: an image request falls back on the image pair, anything else on the
        // text pair, whether or not a separate text provider is configured.
        let roleName = payload["role"].string
            ?? (requiresVision ? "image_fallback" : "text_fallback")
        guard fallbackOf[roleName] != nil else {
            return .obj(["route": .null, "reason": .str("not_a_fallback_role")])
        }
        let row = rolesIn[roleName]
        if !(row["enabled"].bool ?? false) {
            return .obj(["route": .null, "reason": .str("disabled")])
        }
        var byID: [String: RJ] = [:]
        for profile in profiles { if let id = profile["id"].string { byID[id] = profile } }
        guard let id = row["profile_id"].string, let profile = byID[id] else {
            return .obj(["route": .null, "reason": .str("no_profile")])
        }
        if let reason = usable(profile, env, table, requiresVision) {
            return .obj(["route": .null, "reason": .str(reason)])
        }
        let found = route(profile, env, table, roleName)
        if sameEndpoint(found, primary) {
            return .obj(["route": .null, "reason": .str("same_endpoint")])
        }
        return .obj(["route": .obj(found), "reason": .null])
    }

    // MARK: - §8 Per-conversation override

    private static func validProfileID(_ value: String) -> Bool {
        !value.isEmpty && value.count <= maxProfileIDLength
            && value.allSatisfy { profileIDChars.contains($0) }
    }

    /// Read `conversations.provider_override`, old form or new.
    ///
    /// Old rows hold a bare provider token, written by the Health-Records "Use on-device Coach" flow.
    /// New rows hold `profile:<id>|<provider token>`. The provider is carried AFTER the id on
    /// purpose: this column travels in the chat archive, so a conversation imported onto another
    /// device names a profile that does not exist there and must degrade to the provider rather than
    /// quietly answer on the wrong model.
    static func parseOverride(_ raw: String?) -> RJ {
        let value = trim(raw)
        if value.isEmpty { return .obj(["profile_id": .null, "provider": .null]) }
        if value.hasPrefix(overridePrefix) {
            let body = String(value.dropFirst(overridePrefix.count))
            let parts = body.split(separator: overrideSeparator, maxSplits: 1,
                                   omittingEmptySubsequences: false)
            let pid = trim(String(parts.first ?? ""))
            let provider = parts.count > 1 ? trim(String(parts[1])) : ""
            if validProfileID(pid) {
                return .obj(["profile_id": .str(pid),
                             "provider": provider.isEmpty ? .null : .str(provider)])
            }
            // Not a profile id after all; the whole string is somebody's provider token.
            return .obj(["profile_id": .null, "provider": .str(value)])
        }
        return .obj(["profile_id": .null, "provider": .str(value)])
    }

    enum OverrideError: Error { case separatorInToken, badProfileID }

    /// Write the column. `profileID` nil gives the bare token the records flow has always written.
    static func encodeOverride(_ profileID: String?, _ provider: String?) throws -> String? {
        let pid = trim(profileID)
        let token = trim(provider)
        if token.contains(overrideSeparator) || pid.contains(overrideSeparator) {
            throw OverrideError.separatorInToken
        }
        if pid.isEmpty { return token.isEmpty ? nil : token }
        guard validProfileID(pid) else { throw OverrideError.badProfileID }
        return overridePrefix + pid + String(overrideSeparator) + token
    }

    // MARK: - §6 Google Vertex AI

    private static func vertexHost(_ location: String) -> String {
        let hosts = AICatalog.vertex["hosts"].array ?? []
        for rule in hosts {
            let match = rule["match"].string
            if match == "global" && location == "global" { return rule["host"].string ?? "" }
            if match == "multi_region",
               (rule["values"].array ?? []).compactMap(\.string).contains(location) {
                return (rule["host"].string ?? "").replacingOccurrences(of: "{location}",
                                                                        with: location)
            }
        }
        for rule in hosts where rule["match"].string == "region" {
            return (rule["host"].string ?? "").replacingOccurrences(of: "{location}", with: location)
        }
        return ""
    }

    private static func vertexRoute(_ modelID: String) -> RJ? {
        let lowered = modelID.lowercased()
        let routes = AICatalog.vertex["routes"].array ?? []
        for route in routes {
            for prefix in (route["prefixes"].array ?? []).compactMap(\.string)
            where lowered.hasPrefix(prefix) {
                return route
            }
        }
        for route in routes where (route["prefixes"].array ?? []).isEmpty { return route }
        return nil
    }

    /// Host + path + transport for one Vertex call. One provider, three transports: Gemini and
    /// Claude are different APIs that happen to share a host, and everything else goes through the
    /// OpenAI-compatible surface.
    static func vertexEndpoint(_ project: String?, _ location: String?, _ modelID: String?) -> RJ {
        let project = trim(project)
        var location = trim(location)
        if location.isEmpty {
            location = AICatalog.vertex["default_location"].string ?? "global"
        }
        let modelID = trim(modelID)
        if project.isEmpty { return .obj(["ok": .bool(false), "reason": .str("no_project")]) }
        if modelID.isEmpty { return .obj(["ok": .bool(false), "reason": .str("no_model")]) }
        guard let route = vertexRoute(modelID) else {
            return .obj(["ok": .bool(false), "reason": .str("no_route")])
        }
        let host = vertexHost(location)
        if host.isEmpty { return .obj(["ok": .bool(false), "reason": .str("no_host")]) }
        let prefix = (AICatalog.vertex["path_prefix"].string ?? "")
            .replacingOccurrences(of: "{project}", with: project)
            .replacingOccurrences(of: "{location}", with: location)
        let path = (route["path"].string ?? "").replacingOccurrences(of: "{model}", with: modelID)
        return .obj([
            "ok": .bool(true),
            "url": .str(host + prefix + path),
            "publisher": route["publisher"],
            "transport": route["transport"],
            "body_extras": route["body_extras"],
            "drops_model_from_body": .bool(route["drops_model_from_body"].bool ?? false),
            "location": .str(location),
        ])
    }

    // MARK: - §7 Local model catalogue

    /// `max(6 GiB, artifact * 2 + 2 GiB rounded up to an even GiB)`. Reproduces the shipped
    /// Gemma 4 E2B gate of 8 GiB exactly, so adding models does not quietly re-gate the one already
    /// installed on people's phones.
    static func memoryGate(_ sizeBytes: Int) -> Int {
        let needed = Double(sizeBytes) / Double(gib) * 2 + 2
        let even = Int((needed / 2).rounded(.up)) * 2
        return max(6, even) * gib
    }

    /// Total RAM in GiB, rounded UP -- an "8 GB" phone reports a little less than 8 GiB.
    static func memoryClassGB(_ physicalMemoryBytes: Int) -> Int {
        Int((Double(physicalMemoryBytes) / Double(gib)).rounded(.up))
    }

    static func requiredHeadroom(_ sizeBytes: Int, _ policy: RJ) -> Int {
        let minimum = Int(policy["minimumHeadroomBytes"].double ?? 268_435_456)
        let percent = Int(policy["headroomPercent"].double ?? 10)
        return max(minimum, sizeBytes * percent / 100)
    }

    /// What Settings shows for every downloadable model, in the order it shows them.
    static func localCatalogState(_ payload: RJ) -> RJ {
        let catalog = payload["catalog"].object == nil ? AICatalog.models : payload["catalog"]
        let device = payload["device"]
        let installed = Set((payload["installed"].array ?? []).compactMap(\.string))
        let hasToken = payload["has_hf_token"].bool ?? false
        let platform = payload["platform"].string ?? "android"
        let abis = Set((device["abis"].array ?? []).compactMap(\.string))
        let supported = Set((catalog["supportedAbis"].array ?? []).compactMap(\.string))
        let memory = memoryClassGB(Int(device["physical_memory_bytes"].double ?? 0))
        let free = device["free_bytes"].double.map { Int($0) }
        let policy = catalog["downloadPolicy"]

        var rows: [RJ] = []
        for model in catalog["models"].array ?? [] {
            let platforms = (model["platforms"].array ?? []).compactMap(\.string)
            guard platforms.contains(platform) else { continue }
            let artifact = model["artifact"]
            let size = Int(artifact["sizeBytes"].double ?? 0)
            let gate = Int(model["memoryPolicy"]["minimumPhysicalMemoryBytes"].double ?? 0)
            let gated = artifact["access"]["gated"].bool ?? false
            let headroom = requiredHeadroom(size, policy)
            var state = "available"
            var reason: RJ = .null
            if installed.contains(model["id"].string ?? "") {
                state = "installed"
            } else if device["low_ram"].bool ?? false {
                state = "ineligible"; reason = .str("low_ram_device")
            } else if !supported.isEmpty && !abis.isEmpty && abis.isDisjoint(with: supported) {
                state = "ineligible"; reason = .str("unsupported_abi")
            } else if memory * gib < gate {
                state = "ineligible"; reason = .str("insufficient_memory")
            } else if gated && !hasToken {
                state = "needs_token"; reason = .str("needs_token")
            } else if let free, free < size + headroom {
                state = "ineligible"; reason = .str("insufficient_space")
            }
            rows.append(.obj([
                "id": model["id"],
                "display_name": model["displayName"],
                "size_bytes": .int(size),
                "vision": .bool((model["capabilities"].array ?? []).compactMap(\.string)
                    .contains("image")),
                "context_tokens": model["contextTokens"],
                "gated": .bool(gated),
                "minimum_memory_bytes": .int(gate),
                "required_headroom_bytes": .int(headroom),
                "state": .str(state),
                "reason": reason,
            ]))
        }
        let rank = Dictionary(uniqueKeysWithValues: catalogStates.enumerated().map { ($1, $0) })
        let ordered = rows.enumerated().sorted {
            let a = rank[$0.element["state"].string ?? ""] ?? 0
            let b = rank[$1.element["state"].string ?? ""] ?? 0
            return a == b ? $0.offset < $1.offset : a < b
        }.map(\.element)
        return .obj(["models": .arr(ordered)])
    }

    // MARK: - Vector dispatch

    static func runCase(_ function: String, _ input: RJ) -> RJ {
        switch function {
        case "migrate_profiles": return migrateProfiles(input)
        case "adopt_legacy_primary": return adoptLegacyPrimary(input)
        case "add_profile": return addProfile(input)
        case "assign_role": return assignRole(input)
        case "project_legacy": return projectLegacy(input["profiles"].array ?? [], input["roles"])
        case "resolve_role": return resolveRole(input)
        case "resolve_fallback": return resolveFallback(input)
        case "resolve_credential":
            return .obj(["source": .str(resolveCredential(input["credential_ref"].string,
                                                          input["requires_key"].bool ?? true,
                                                          input["has_profile_key"].bool ?? false,
                                                          input["has_provider_key"].bool ?? false))])
        case "keys_to_delete": return keysToDelete(input)
        case "vertex_endpoint":
            return vertexEndpoint(input["project"].string, input["location"].string,
                                  input["model"].string)
        case "override":
            if input["op"].string == "parse" { return parseOverride(input["raw"].string) }
            do {
                let raw = try encodeOverride(input["profile_id"].string, input["provider"].string)
                return .obj(["raw": RJ.string(raw)])
            } catch {
                return .obj(["raw": .null,
                             "error": .str("a provider token or profile id may not contain '|'")])
            }
        case "token_limit_key":
            return .obj(["key": .str(tokenLimitKey(input["provider"].string,
                                                   input["model"].string))])
        case "local_catalog_state": return localCatalogState(input)
        default: return .obj(["error": .str("unknown function \(function)")])
        }
    }
}

/// The three shared catalogues, bundled verbatim (`AI/Resources/`). A parity test in
/// `scripts/ai_contract_check.py` byte-compares them with `shared/ai/` and `local-models/`.
enum AICatalog {
    static let providers: RJ = load("providers")
    static let vertex: RJ = load("vertex")
    static let models: RJ = load("models_catalog")

    static var tokenLimit: RJ { providers["token_limit"] }

    /// The registry in the shape `AIRef` reasons over, optionally narrowed to one platform.
    /// `AIProvider` stays the owner of the model lineups in app code; this is the projection the
    /// resolver needs.
    static func providerDefaults(platform: String? = nil) -> [String: RJ] {
        var out: [String: RJ] = [:]
        for row in providers["providers"].array ?? [] {
            guard let id = row["id"].string else { continue }
            if let platform,
               !(row["platforms"].array ?? []).compactMap(\.string).contains(platform) { continue }
            out[id] = .obj([
                "api_format": row["api_format"],
                "base_url": row["base_url"],
                "requires_key": row["requires_key"],
                "supports_vision": row["supports_vision"],
                "configurable_timeout": row["configurable_timeout"],
                "models": row["models"],
            ])
        }
        return out
    }

    private static func load(_ name: String) -> RJ {
        for bundle in [Bundle.main, Bundle(for: BundleMarker.self)] {
            if let url = bundle.url(forResource: name, withExtension: "json"),
               let text = try? String(contentsOf: url, encoding: .utf8),
               let parsed = RJ.parse(text) {
                return parsed
            }
        }
        return .obj([:])
    }

    private final class BundleMarker {}
}
