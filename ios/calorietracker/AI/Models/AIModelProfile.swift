import Foundation

/// A saved model configuration the user named (docs/ai-models.md §3).
///
/// The reference (`AIRef`) reasons over these as JSON, so `RJ` is the canonical in-memory form and
/// this struct is a typed view of it. Keeping the JSON authoritative means a profile written here
/// and a profile written by Android are the same bytes.
struct AIModelProfile: Identifiable, Equatable, Sendable {
    var id: String
    /// The persisted provider token: `AIProvider.rawValue`, equal to Android's `@SerialName`.
    var providerToken: String
    var nickname: String
    var modelID: String
    var baseURL: String?
    var vertexProjectID: String?
    var vertexLocation: String?
    /// One of `provider:<token>`, `profile:<id>` or `none` (docs/ai-models.md §4).
    var credentialRef: String
    var createdMs: Int
    var updatedMs: Int

    var provider: AIProvider? { AIProvider(rawValue: providerToken) }

    /// The name shown when the user has not renamed the profile.
    var displayName: String { nickname.isEmpty ? "\(providerToken) · \(modelID)" : nickname }

    var rj: RJ {
        var vertex: RJ = .null
        if let vertexProjectID, let vertexLocation {
            vertex = .obj(["project_id": .str(vertexProjectID), "location": .str(vertexLocation)])
        }
        return .obj([
            "id": .str(id),
            "nickname": .str(nickname),
            "provider": .str(providerToken),
            "model_id": .str(modelID),
            "base_url": RJ.string(baseURL),
            "vertex": vertex,
            "credential_ref": .str(credentialRef),
            "created_ms": .int(createdMs),
            "updated_ms": .int(updatedMs),
        ])
    }

    init?(rj: RJ) {
        guard let id = rj["id"].string, let provider = rj["provider"].string else { return nil }
        self.id = id
        providerToken = provider
        nickname = rj["nickname"].string ?? ""
        modelID = rj["model_id"].string ?? ""
        baseURL = rj["base_url"].string
        vertexProjectID = rj["vertex"]["project_id"].string
        vertexLocation = rj["vertex"]["location"].string
        credentialRef = rj["credential_ref"].string ?? "provider:\(provider)"
        createdMs = Int(rj["created_ms"].double ?? 0)
        updatedMs = Int(rj["updated_ms"].double ?? 0)
    }

    init(id: String, providerToken: String, nickname: String, modelID: String,
         baseURL: String? = nil, vertexProjectID: String? = nil, vertexLocation: String? = nil,
         credentialRef: String? = nil, createdMs: Int, updatedMs: Int) {
        self.id = id
        self.providerToken = providerToken
        self.nickname = nickname
        self.modelID = modelID
        self.baseURL = baseURL
        self.vertexProjectID = vertexProjectID
        self.vertexLocation = vertexLocation
        self.credentialRef = credentialRef ?? "provider:\(providerToken)"
        self.createdMs = createdMs
        self.updatedMs = updatedMs
    }
}

/// The four pointers a profile can be wired to (docs/ai-models.md §3).
enum AIRole: String, CaseIterable, Sendable {
    case image
    case text
    case imageFallback = "image_fallback"
    case textFallback = "text_fallback"
}

struct AIRolePointer: Equatable, Sendable {
    var profileID: String?
    var enabled: Bool

    var rj: RJ { .obj(["profile_id": RJ.string(profileID), "enabled": .bool(enabled)]) }
}
