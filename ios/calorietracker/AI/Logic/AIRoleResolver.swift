import Foundation

/// Turns the saved profiles into "which provider, which model, which key" (docs/ai-models.md §5).
///
/// The decision itself lives in `AIRef.resolveRole` / `AIRef.resolveFallback`, which Android runs
/// line for line. This type only gathers the facts the pure function may not look up for itself:
/// which providers hold a key, which local models are installed, whether Apple Intelligence is
/// ready right now.
enum AIRoleResolver {

    /// One resolved request target.
    struct Route: Equatable, Sendable {
        var role: AIRole
        var profileID: String?
        var provider: AIProvider
        var model: String
        var baseURL: String
        var apiKey: String?
        var vertexProjectID: String?
        var vertexLocation: String?
        var requestTimeout: TimeInterval?
        var maxResponseTokens: Int
        var tokenLimitKey: String
        var contextTokens: Int?
        /// Why this route must not be sent, or nil. A chosen profile is never swapped for another
        /// one, so this is how the caller learns it cannot answer (docs/ai-models.md rule 1).
        var blocked: String?

        var isUsable: Bool { blocked == nil }
    }

    // MARK: - The facts the pure function cannot look up

    /// The installed on-device models, in the shape `AIRef` expects.
    ///
    /// Keyed by both the id a profile carries today (`AIProvider.gemma4Local.models`) and the
    /// catalogue id, so a profile written before or after the multi-model catalogue lands resolves
    /// the same way.
    @MainActor
    static func localModels() -> RJ {
        var rows: [String: RJ] = [:]
        for descriptor in LocalModelCatalog.chatModels {
            let entry = RJ.obj([
                "installed": .bool(Gemma4LocalModelManager.manager(for: descriptor).isSelectable),
                // Per model: Qwen3 has no vision tower, so it serves text and never images.
                "vision": .bool(descriptor.supportsVision),
                "context_tokens": .int(descriptor.contextTokens),
            ])
            rows[descriptor.id] = entry
            // A profile written before the catalogue carries the bare artifact id.
            if descriptor.id == LocalModelCatalog.gemmaCatalogID {
                rows[LocalModelCatalog.legacyGemmaModelID] = entry
            }
        }
        if rows.isEmpty {
            let entry = RJ.obj([
                "installed": .bool(Gemma4LocalModelManager.isCurrentDeviceSelectable),
                "vision": .bool(true),
                "context_tokens": .int(Gemma4LocalModelManager.maxContextTokens),
            ])
            rows[Gemma4LocalModelManager.modelID] = entry
            rows[LocalModelCatalog.gemmaCatalogID] = entry
        }
        return .obj(rows)
    }

    static var isAppleIntelligenceAvailable: Bool {
        if #available(iOS 26.0, *) { return OnDeviceAIService.isAvailable }
        return false
    }

    @MainActor
    static func env(requiresVision: Bool) -> RJ {
        .obj([
            "platform": .str(AIRef.platform),
            "requires_vision": .bool(requiresVision),
            "providers_with_keys": .arr(AIProvider.allCases
                .filter { KeychainHelper.load(key: "apikey_" + $0.rawValue) != nil }
                .map { RJ.str($0.rawValue) }),
            "profiles_with_keys": .arr(AIProviderSettings.profileIDsWithOwnKey.map(RJ.str)),
            "local_models": localModels(),
            "on_device_available": .bool(isAppleIntelligenceAvailable),
            "max_response_tokens": .int(AIProviderSettings.maxResponseTokens),
            "request_timeout_seconds": .int(AIProviderSettings.requestTimeoutSeconds),
        ])
    }

    // MARK: - Resolution

    static func route(from rj: RJ, requestedRole: AIRole) -> Route? {
        guard let provider = AIProvider(rawValue: rj["provider"].string ?? "") else { return nil }
        let profileID = rj["profile_id"].string
        let profile = AIProviderSettings.profile(id: profileID)
        return Route(
            role: AIRole(rawValue: rj["role"].string ?? "") ?? requestedRole,
            profileID: profileID,
            provider: provider,
            model: rj["model"].string ?? "",
            baseURL: rj["base_url"].string ?? "",
            apiKey: profile.flatMap { AIProviderSettings.apiKey(for: $0) }
                ?? KeychainHelper.load(key: "apikey_" + provider.rawValue),
            vertexProjectID: rj["vertex"]["project_id"].string,
            vertexLocation: rj["vertex"]["location"].string,
            requestTimeout: rj["request_timeout_seconds"].double,
            maxResponseTokens: Int(rj["max_response_tokens"].double ?? 1024),
            tokenLimitKey: rj["token_limit_key"].string ?? "max_tokens",
            contextTokens: rj["context_tokens"].double.map { Int($0) },
            blocked: rj["blocked"].string
        )
    }

    /// The primary route for a request. Always returns something: a blocked route still names the
    /// profile the user chose, which is what a refusal has to say.
    ///
    /// `pinnedProfileID` is a conversation's own model choice (docs/ai-models.md §8). It is resolved
    /// through the same guards as a role, so a pinned model that cannot answer refuses rather than
    /// being swapped for a different one.
    @MainActor
    static func resolve(requiresVision: Bool, role: AIRole? = nil,
                        pinnedProfileID: String? = nil) -> Route? {
        let environment = env(requiresVision: requiresVision)
        let requestedRole = role ?? (requiresVision ? AIRole.image : .text)
        var roles = AIProviderSettings.rolesRJ()
        if let pinnedProfileID {
            var pointers = roles.object ?? [:]
            pointers[requestedRole.rawValue] = .obj(["profile_id": .str(pinnedProfileID),
                                                     "enabled": .bool(true)])
            roles = .obj(pointers)
        }
        var payload: [String: RJ] = [
            "profiles": AIProviderSettings.profilesRJ(),
            "roles": roles,
            "env": environment,
        ]
        payload["role"] = .str(requestedRole.rawValue)
        let resolved = AIRef.resolveRole(.obj(payload))
        return route(from: resolved, requestedRole: requestedRole)
    }

    /// What to tell the user when a pinned model cannot answer. Names the model, never substitutes.
    static func refusal(for route: Route, reason: String) -> String {
        let name = AIProviderSettings.profile(id: route.profileID)?.displayName
            ?? route.provider.displayName
        switch reason {
        case "no_key":
            return String(localized: "\(name) has no API key. Add one in Settings, or pick another model for this chat.")
        case "model_not_installed":
            return String(localized: "\(name) is not installed on this device. Download it in Settings, or pick another model for this chat.")
        case "no_vision":
            return String(localized: "\(name) cannot read images. Pick another model for this chat, or send the message without the photo.")
        case "no_base_url":
            return String(localized: "\(name) has no server URL. Add one in Settings, or pick another model for this chat.")
        default:
            return String(localized: "\(name) is not available. Pick another model for this chat.")
        }
    }

    /// The one retry, or nil. Never a second attempt at the endpoint that just failed.
    @MainActor
    static func fallback(for primary: Route, requiresVision: Bool) -> Route? {
        let resolved = AIRef.resolveFallback(.obj([
            "profiles": AIProviderSettings.profilesRJ(),
            "roles": AIProviderSettings.rolesRJ(),
            "env": env(requiresVision: requiresVision),
            "primary": .obj([
                "provider": .str(primary.provider.rawValue),
                "model": .str(primary.model),
                "base_url": .str(primary.baseURL),
            ]),
        ]))
        guard resolved["route"].object != nil else { return nil }
        return route(from: resolved["route"],
                     requestedRole: requiresVision ? .imageFallback : .textFallback)
    }
}
