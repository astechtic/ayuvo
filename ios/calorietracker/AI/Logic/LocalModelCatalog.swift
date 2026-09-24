import Foundation

/// One downloadable LiteRT-LM package, as `local-models/catalog.v2.json` describes it.
///
/// iOS used to bake a single artifact into `Gemma4LocalModelManager` as `static let`s. The manager
/// is now driven by one of these, so several models can be installed at once (docs/ai-models.md §7).
struct LocalModelDescriptor: Identifiable, Equatable, Sendable {
    /// The catalogue id, which is what a profile's `model_id` carries.
    var id: String
    var displayName: String
    var filename: String
    var url: URL
    var sizeBytes: Int64
    var sha256: String
    var minimumMemoryBytes: Int64

    /// The gate as a *memory class*, which is how it has to be compared.
    ///
    /// iOS reports slightly less than the marketed RAM, so an 8 GB phone reads as ~7.6 GiB. The
    /// device's reported bytes are rounded up to a whole GiB before the comparison; comparing raw
    /// bytes would fail every 8 GB phone against an 8 GiB model.
    var minimumMemoryClassGB: UInt64 {
        UInt64(max(0, minimumMemoryBytes)) / (1_024 * 1_024 * 1_024)
    }
    /// Qwen3 has no vision tower, so this is per model, not per provider.
    var supportsVision: Bool
    /// Per model: MedGemma's build exports a 2048-entry KV cache, not the engine's global 4096.
    var contextTokens: Int
    /// Gated on the Hub: the download needs a Hugging Face token.
    var requiresAuth: Bool
    /// `owner/name` on Hugging Face. A gated model's terms are accepted on this page.
    var repository: String
    var licenseName: String
    var licenseURL: URL?
    var sourceURL: URL?

    /// The directory name under `LocalModels/`. Derived from the id so two models never collide.
    var storageName: String {
        id.replacingOccurrences(of: ".", with: "-")
    }

    var preparedMarkerContents: String { "LiteRTLM-0.16.0-" + sha256 }

    /// The model's page — where a gated model's terms are accepted.
    var repositoryURL: URL? {
        repository.isEmpty ? nil : URL(string: "https://huggingface.co/" + repository)
    }
}

enum LocalModelCatalog {
    /// The chat models, in catalogue order. Whisper is speech and has its own manager.
    static let chatModels: [LocalModelDescriptor] = parse(AICatalog.models)

    static func descriptor(id: String?) -> LocalModelDescriptor? {
        guard let id else { return nil }
        // A profile written before the catalogue existed carries the bare artifact id.
        if id == legacyGemmaModelID { return gemma }
        return chatModels.first { $0.id == id }
    }

    /// The id every build knew before the catalogue existed.
    static let legacyGemmaModelID = "gemma-4-E2B-it"
    static let gemmaCatalogID = "gemma-4-e2b-it-litertlm"

    static var gemma: LocalModelDescriptor? { chatModels.first { $0.id == gemmaCatalogID } }

    static func parse(_ catalog: RJ) -> [LocalModelDescriptor] {
        (catalog["models"].array ?? []).compactMap { model in
            guard let id = model["id"].string,
                  (model["platforms"].array ?? []).compactMap(\.string).contains("ios"),
                  let raw = model["artifact"]["url"].string, let url = URL(string: raw),
                  let filename = model["artifact"]["filename"].string,
                  let sha = model["artifact"]["sha256"].string else { return nil }
            let capabilities = (model["capabilities"].array ?? []).compactMap(\.string)
            return LocalModelDescriptor(
                id: id,
                displayName: model["displayName"].string ?? id,
                filename: filename,
                url: url,
                sizeBytes: Int64(model["artifact"]["sizeBytes"].double ?? 0),
                sha256: sha,
                minimumMemoryBytes: Int64(model["memoryPolicy"]["minimumPhysicalMemoryBytes"].double ?? 0),
                supportsVision: capabilities.contains("image"),
                contextTokens: Int(model["contextTokens"].double ?? 4096),
                requiresAuth: model["artifact"]["access"]["gated"].bool ?? false,
                repository: model["artifact"]["repository"].string ?? "",
                licenseName: model["license"]["spdx"].string ?? "",
                licenseURL: model["license"]["textURL"].string.flatMap(URL.init(string:)),
                sourceURL: model["license"]["declaredByURL"].string.flatMap(URL.init(string:))
            )
        }
    }
}
