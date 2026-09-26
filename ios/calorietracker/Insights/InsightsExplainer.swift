import Foundation

/// Which model explains, resolved like Records: the configured Text provider decides; there is no silent
/// fallback, in particular never from on-device to cloud (docs/insights.md §5).
nonisolated enum InsightsAIRoute: Equatable, Sendable {
    case appleIntelligence
    case gemma
    case cloud(provider: AIProvider)

    var variant: InsightsAIVariant { if case .cloud = self { return .cloud } else { return .local } }

    /// "Explained on this device" / "Explained using online AI · <provider>".
    func statusLabel(config: InsightsConfig) -> String {
        switch self {
        case .appleIntelligence, .gemma:
            return config.ai.statusLabels["local"] ?? "Explained on this device"
        case .cloud(let provider):
            return (config.ai.statusLabels["cloud"] ?? "Explained using online AI · {provider}")
                .replacingOccurrences(of: "{provider}", with: provider.displayName)
        }
    }

    /// The Text provider, when it is ready to answer; nil means "Set up AI in Settings".
    @MainActor
    static func current() -> InsightsAIRoute? {
        let text = AIProviderSettings.currentConfig(requiresVision: false)
        switch text.provider {
        case .appleIntelligence:
            var available = false
            #if canImport(FoundationModels)
            if #available(iOS 26.0, *) { available = OnDeviceAIService.isAvailable }
            #endif
            return available ? .appleIntelligence : nil
        case .gemma4Local:
            return Gemma4LocalModelManager.isCurrentDeviceSelectable ? .gemma : nil
        default:
            guard !text.provider.requiresAPIKey || text.apiKey != nil else { return nil }
            return .cloud(provider: text.provider)
        }
    }
}

/// The model call seam; the app's transport hops to the existing AI services (Records' pattern).
nonisolated protocol InsightsAITransport: Sendable {
    func complete(prompt: InsightsPrompt, route: InsightsAIRoute, maxOutputTokens: Int) async throws -> String
}

nonisolated struct InsightsAppAITransport: InsightsAITransport {
    func complete(prompt: InsightsPrompt, route: InsightsAIRoute, maxOutputTokens: Int) async throws -> String {
        switch route {
        case .appleIntelligence:
            #if canImport(FoundationModels)
            if #available(iOS 26.0, *) {
                return try await OnDeviceAIService.respond(to: prompt.user, instructions: prompt.system)
            }
            #endif
            throw InsightsExplainer.Failure.unavailable
        case .gemma:
            return try await Gemma4LocalModelManager.shared.generate(
                prompt: prompt.user, images: [], systemPrompt: prompt.system, maxOutputTokens: maxOutputTokens
            )
        case .cloud:
            // The Text provider only (no image role, no fallback provider).
            return try await GeminiService.callRecordsAI(
                prompt: prompt.system + "\n\n" + prompt.user, imageDataList: [], maxOutputTokens: maxOutputTokens
            ).text
        }
    }
}

/// "Explain with AI" (on tap only). The numbers on screen always come from the engines; the model only words
/// them, and an answer that fails validation is replaced by the deterministic text.
@Observable
@MainActor
final class InsightsExplainer {
    nonisolated enum Failure: Error, Equatable { case unavailable }

    enum State: Equatable {
        case idle
        case running
        /// A validated explanation and the route label.
        case explained(InsightsExplanation, status: String)
        /// The model answered but the answer did not pass the checks, or the call failed.
        case failed(String)
    }

    private(set) var state: State = .idle
    @ObservationIgnored private let transport: InsightsAITransport
    @ObservationIgnored private let config: InsightsConfig
    @ObservationIgnored private let routeProvider: @MainActor () -> InsightsAIRoute?

    init(transport: InsightsAITransport = InsightsAppAITransport(), config: InsightsConfig = .shared,
         route: @escaping @MainActor () -> InsightsAIRoute? = { InsightsAIRoute.current() }) {
        self.transport = transport
        self.config = config
        self.routeProvider = route
    }

    /// False → the button shows "Set up AI in Settings".
    var isConfigured: Bool { routeProvider() != nil }

    func reset() { state = .idle }

    func explain(kind: InsightsAIKind, summary: InsightsSummary) async {
        guard let route = routeProvider(), let prompts = InsightsAI.bundledPrompts else {
            state = .failed(String(localized: "Set up AI in Settings to get an explanation."))
            return
        }
        state = .running
        let payload = InsightsAI.payload(summary)
        let prompt = InsightsAI.buildPrompt(kind: kind, payload: payload, variant: route.variant, config: config, prompts: prompts)
        let tokens = config.ai.maxOutputTokens[route.variant.rawValue] ?? 300
        do {
            let text = try await transport.complete(prompt: prompt, route: route, maxOutputTokens: tokens)
            let validation = InsightsAI.validate(text, payload: payload, config: config)
            if validation.ok, let output = validation.output {
                state = .explained(output, status: route.statusLabel(config: config))
            } else {
                state = .failed(String(localized: "The AI answer didn't match your numbers, so it isn't shown. The explanation above comes straight from your data."))
            }
        } catch is CancellationError {
            state = .idle
        } catch {
            state = .failed(String(localized: "The explanation couldn't be created right now. Your scores above are unaffected."))
        }
    }
}
