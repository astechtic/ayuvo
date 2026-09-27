import Foundation
import UIKit

/// Which model reads a supplement label (docs/nutrients.md §7, shared/nutrients/ai_supplement_label.md): the
/// vision role for a photo, the text role for name and strength, resolved like Insights and Health Records.
/// There is no silent fallback: never from on-device to cloud and never from photo to text.
nonisolated enum SupplementAIRoute: Equatable, Sendable {
    case appleIntelligence
    case gemma
    case cloud(provider: AIProvider)

    var isLocal: Bool { if case .cloud = self { return false } else { return true } }

    /// The configured provider for this input when it is ready; nil means "Set up AI in Settings".
    @MainActor
    static func current(photo: Bool) -> SupplementAIRoute? {
        let config = AIProviderSettings.currentConfig(requiresVision: photo)
        switch config.provider {
        case .appleIntelligence:
            // Apple Intelligence reads text only; a photo is not sent anywhere else instead.
            guard !photo else { return nil }
            var available = false
            #if canImport(FoundationModels)
            if #available(iOS 26.0, *) { available = OnDeviceAIService.isAvailable }
            #endif
            return available ? .appleIntelligence : nil
        case .gemma4Local:
            return Gemma4LocalModelManager.isCurrentDeviceSelectable ? .gemma : nil
        default:
            guard !config.provider.requiresAPIKey || config.apiKey != nil else { return nil }
            return .cloud(provider: config.provider)
        }
    }
}

/// "Get nutrients with AI" (on tap only). The answer goes through `parse_label_output`; nothing is saved here.
enum SupplementLabelAI {
    enum Failure: Error, Equatable {
        case notConfigured
        case promptMissing
        case failed(String)
    }

    /// The system prompt and the filled user template for a route (plain replacement, each placeholder once).
    static func prompt(photo: Bool, route: SupplementAIRoute, name: String, strength: String, doseUnit: String,
                       prompts: NutrientsReference.Prompts) -> (system: String, user: String) {
        let template = photo ? prompts.userPhoto : prompts.userText
        let user = template
            .replacingOccurrences(of: "{dose_unit}", with: doseUnit)
            .replacingOccurrences(of: "{name}", with: name.trimmingCharacters(in: .whitespacesAndNewlines))
            .replacingOccurrences(of: "{strength}", with: strength.trimmingCharacters(in: .whitespacesAndNewlines))
        return (route.isLocal ? prompts.local : prompts.cloud, user)
    }

    /// A label photo scaled for the model (JPEG, longest side ≤ 1600 px).
    static func preparedPhoto(_ data: Data) -> Data? {
        let image = MedicationPhotoStore.downsample(data, maxPixelSize: 1600) ?? UIImage(data: data)
        return image?.jpegData(compressionQuality: 0.8)
    }

    @MainActor
    static func read(photo: Data?, name: String, strength: String, doseUnit: String,
                     complete: ((_ system: String, _ user: String, _ images: [Data], _ route: SupplementAIRoute) async throws -> String)? = nil)
    async throws -> NutrientsReference.LabelResult {
        guard let route = SupplementAIRoute.current(photo: photo != nil) else { throw Failure.notConfigured }
        guard let prompts = NutrientsReference.bundledPrompts else { throw Failure.promptMissing }
        let text = prompt(photo: photo != nil, route: route, name: name, strength: strength, doseUnit: doseUnit, prompts: prompts)
        let images = photo.flatMap(preparedPhoto).map { [$0] } ?? []
        let answer: String
        if let complete {
            answer = try await complete(text.system, text.user, images, route)
        } else {
            answer = try await send(system: text.system, user: text.user, images: images, route: route)
        }
        return NutrientsReference.parseLabelOutput(answer)
    }

    private static func send(system: String, user: String, images: [Data], route: SupplementAIRoute) async throws -> String {
        switch route {
        case .appleIntelligence:
            #if canImport(FoundationModels)
            if #available(iOS 26.0, *) {
                return try await OnDeviceAIService.respond(to: user, instructions: system)
            }
            #endif
            throw Failure.notConfigured
        case .gemma:
            return try await Gemma4LocalModelManager.shared.generate(prompt: user, images: images, systemPrompt: system, maxOutputTokens: 400)
        case .cloud:
            // The provider of the role that matches the input (vision with a photo, text without); no fallback.
            return try await GeminiService.callRecordsAI(prompt: system + "\n\n" + user, imageDataList: images, maxOutputTokens: 800).text
        }
    }
}
