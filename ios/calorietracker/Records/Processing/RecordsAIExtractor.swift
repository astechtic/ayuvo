import Foundation
import ImageIO
import PDFKit
import UniformTypeIdentifiers

/// Everything §16 resolution needs, captured on the main actor.
nonisolated struct RecordsAIEnvironment: Sendable, Equatable {
    var appleIntelligenceAvailable: Bool
    var gemmaInstalled: Bool
    var textProvider: AIProvider
    var textProviderReady: Bool
    var visionProvider: AIProvider
    var visionProviderReady: Bool

    static let none = RecordsAIEnvironment(
        appleIntelligenceAvailable: false, gemmaInstalled: false,
        textProvider: .gemini, textProviderReady: false, visionProvider: .gemini, visionProviderReady: false
    )

    var localAvailable: Bool { appleIntelligenceAvailable || gemmaInstalled }

    static func isLocalProvider(_ provider: AIProvider) -> Bool {
        provider == .appleIntelligence || provider == .gemma4Local
    }

    /// A configured BYOK provider that is not an on-device model.
    var cloudProviderName: String? {
        if textProviderReady, !Self.isLocalProvider(textProvider) { return textProvider.displayName }
        if visionProviderReady, !Self.isLocalProvider(visionProvider) { return visionProvider.displayName }
        return nil
    }

    @MainActor
    static func current() -> RecordsAIEnvironment {
        var apple = false
        #if canImport(FoundationModels)
        if #available(iOS 26.0, *) { apple = OnDeviceAIService.isAvailable }
        #endif
        let text = AIProviderSettings.currentConfig(requiresVision: false)
        let vision = AIProviderSettings.currentConfig(requiresVision: true)
        func ready(_ config: AIProviderSettings.RequestConfig) -> Bool {
            switch config.provider {
            case .appleIntelligence: return apple
            case .gemma4Local: return Gemma4LocalModelManager.isCurrentDeviceSelectable
            default: return !config.provider.requiresAPIKey || config.apiKey != nil
            }
        }
        return RecordsAIEnvironment(
            appleIntelligenceAvailable: apple,
            gemmaInstalled: Gemma4LocalModelManager.isCurrentDeviceSelectable,
            textProvider: text.provider,
            textProviderReady: ready(text),
            visionProvider: vision.provider,
            visionProviderReady: ready(vision)
        )
    }
}

nonisolated enum RecordsAIEngine: Sendable, Equatable {
    case appleIntelligence
    case gemma
    /// The configured BYOK providers (text for text chunks, vision for image pages).
    case cloud(provider: AIProvider)

    var modeUsed: RecordAIModeUsed {
        switch self {
        case .appleIntelligence, .gemma: .local
        case .cloud: .cloud
        }
    }

    var providerName: String {
        switch self {
        case .appleIntelligence: AIProvider.appleIntelligence.displayName
        case .gemma: AIProvider.gemma4Local.displayName
        case .cloud(let provider): provider.displayName
        }
    }

    var isCloud: Bool { if case .cloud = self { return true } else { return false } }
    var supportsImages: Bool { self != .appleIntelligence }
    var chunkLimit: Int { isCloud ? 12_000 : 2_500 }
}

nonisolated enum RecordsAIResolution: Sendable, Equatable {
    case run(RecordsAIEngine)
    /// No AI; `error` is stored as `processing_error` (nil for `off` / "Not now").
    case skip(RecordProcessingError?)
    case askConsent
}

/// Per-record decision stored in `processing_jobs.requested_mode`.
nonisolated enum RecordsAIRequest: String, Sendable {
    case local
    case cloud
    case none
}

/// §16 resolution per record.
nonisolated enum RecordsAIModeResolver {
    static func effectiveMode(_ stored: RecordsAIMode?) -> RecordsAIMode { stored ?? .ask }

    static func resolve(mode stored: RecordsAIMode?, request: RecordsAIRequest?, environment env: RecordsAIEnvironment) -> RecordsAIResolution {
        if let request {
            switch request {
            case .none: return .skip(nil)
            case .local: return resolveLocal(env)
            case .cloud: return resolveCloud(env)
            }
        }
        switch effectiveMode(stored) {
        case .off: return .skip(nil)
        case .local: return resolveLocal(env)
        case .cloud: return resolveCloud(env)
        case .ask: return .askConsent
        }
    }

    static func resolveLocal(_ env: RecordsAIEnvironment) -> RecordsAIResolution {
        if env.appleIntelligenceAvailable { return .run(.appleIntelligence) }
        if env.gemmaInstalled { return .run(.gemma) }
        return .skip(.aiUnavailable)
    }

    /// The configured text provider; an on-device provider selected as primary counts as local.
    static func resolveCloud(_ env: RecordsAIEnvironment) -> RecordsAIResolution {
        guard env.textProviderReady else {
            if env.visionProviderReady, !RecordsAIEnvironment.isLocalProvider(env.visionProvider) {
                return .run(.cloud(provider: env.visionProvider))
            }
            return .skip(.aiUnavailable)
        }
        switch env.textProvider {
        case .appleIntelligence: return .run(.appleIntelligence)
        case .gemma4Local: return .run(.gemma)
        default: return .run(.cloud(provider: env.textProvider))
        }
    }
}

/// §9.3 gaps that justify an AI call.
nonisolated enum RecordsAIGaps {

    static func gaps(record: HealthRecord, fields: [RecordField], pages: [RecordPage]) -> [String] {
        var gaps: [String] = []
        let visible = fields.filter { $0.state != .rejected }
        func has(_ key: RecordFieldKey) -> Bool { visible.contains { $0.key == key } }
        if record.recordType == .other || (record.typeMethod != .user && (record.typeConfidence ?? 0) < 0.7 && record.typeMethod != nil) {
            gaps.append("record_type")
        }
        if record.documentDate == nil || record.documentDateMethod == .fileMetadata || record.documentDateMethod == .importTime,
           !RecordFieldKey.dateKeys.contains(where: has) {
            gaps.append("date")
        }
        if !has(.doctorName), !has(.facility) { gaps.append("doctor_or_facility") }
        let lines = pages.map { RR.pageLines($0.text, $0.pageIndex) }
        if !has(.testResult), RR.countUnitLines(lines) > 0 {
            gaps.append("test_results")
        }
        if record.recordType == .prescription, !has(.medication) { gaps.append("medications") }
        if [.consultationNote, .dischargeSummary, .imagingReport].contains(record.recordType), !has(.diagnosis), !has(.recommendation) {
            gaps.append("diagnosis_or_recommendation")
        }
        return gaps
    }
}

nonisolated enum RecordsAIError: Error, Equatable, Sendable {
    /// Local model busy (Coach running): wait and retry without counting an attempt.
    case busy
    case offline
    case unavailable
    case failed(String)
}

/// The model call seam; the app's transport hops to the existing AI services.
nonisolated protocol RecordsAITransport: Sendable {
    func complete(prompt: String, images: [Data], engine: RecordsAIEngine, maxOutputTokens: Int) async throws -> String
}

nonisolated struct RecordsAppAITransport: RecordsAITransport {
    func complete(prompt: String, images: [Data], engine: RecordsAIEngine, maxOutputTokens: Int) async throws -> String {
        do {
            switch engine {
            case .appleIntelligence:
                #if canImport(FoundationModels)
                if #available(iOS 26.0, *) {
                    return try await OnDeviceAIService.respond(to: prompt, instructions: nil)
                }
                #endif
                throw RecordsAIError.unavailable
            case .gemma:
                return try await Gemma4LocalModelManager.shared.generate(
                    prompt: prompt, images: images, systemPrompt: nil, maxOutputTokens: maxOutputTokens
                )
            case .cloud:
                return try await GeminiService.callRecordsAI(prompt: prompt, imageDataList: images, maxOutputTokens: maxOutputTokens).text
            }
        } catch let error as Gemma4LocalModelManager.LocalModelError {
            switch error {
            case .busy: throw RecordsAIError.busy
            case .notDownloaded, .unsupportedDevice: throw RecordsAIError.unavailable
            default: throw RecordsAIError.failed(error.localizedDescription)
            }
        } catch let error as RecordsAIError {
            throw error
        } catch let error as GeminiService.AnalysisError {
            if case .noAPIKey = error { throw RecordsAIError.unavailable }
            if case .networkError(let underlying) = error, (underlying as? URLError)?.code == .notConnectedToInternet {
                throw RecordsAIError.offline
            }
            throw RecordsAIError.failed(error.localizedDescription)
        } catch is CancellationError {
            throw CancellationError()
        } catch {
            throw RecordsAIError.failed(error.localizedDescription)
        }
    }
}

/// §9.3 extractor: `ai_chunks` page text (12,000 chars cloud / 2,500 local), images only for pages
/// without usable text (never Apple Intelligence; one page per local call), output validated by
/// `validate_ai` and merged by `applyExtraction`.
nonisolated struct RecordsAIExtractor: Sendable {
    static let maxOutputTokens = 3_000
    static let usableTextLetters = 20

    var transport: RecordsAITransport = RecordsAppAITransport()

    /// Gemma: min(3,000, 4,096 − prompt tokens − 64), prompt tokens estimated at 3 characters each.
    static func tokenBudget(engine: RecordsAIEngine, prompt: String) -> Int {
        guard engine == .gemma else { return maxOutputTokens }
        let promptTokens = (prompt.count + 2) / 3
        return max(256, min(maxOutputTokens, Gemma4LocalModelManager.maxContextTokens - promptTokens - 64))
    }

    func extract(
        record: HealthRecord,
        pages: [RecordPage],
        engine: RecordsAIEngine,
        originalURL: URL?,
        today: String,
        order: RecordDateOrder,
        rulesTypeHint: RecordType
    ) async throws -> RecordExtraction {
        let method: RecordFieldMethod = engine.isCloud ? .aiCloud : .aiLocal
        let mode = engine.isCloud ? "cloud" : "local"
        let local = !engine.isCloud
        let count = (pages.map(\.pageIndex).max() ?? -1) + 1
        var texts = [String](repeating: "", count: max(0, count))
        for page in pages where page.pageIndex >= 0 { texts[page.pageIndex] = page.text ?? "" }
        let hint = rulesTypeHint == .other ? "unknown" : rulesTypeHint.rawValue
        var extraction = RecordExtraction()
        var typeCandidates: [(RecordType, Double)] = []

        func merge(_ response: String, imagePages: [Int], coversFirstPage: Bool) {
            let result = RR.validateAI(pages: texts, aiJSON: .str(response), mode: mode, today: today, dateOrder: order.rawValue, imagePages: imagePages)
            extraction.fields += (result["items"].array ?? []).compactMap { ExtractedField(referenceItem: $0, method: method) }
            guard coversFirstPage else { return }
            if let rn = result["report_name"].object.map(RJ.obj), let field = ExtractedField(referenceItem: rn, method: method) {
                extraction.fields.append(field)
            }
            if let type = result["record_type"]["record_type"].string.flatMap(RecordType.init(rawValue:)) {
                typeCandidates.append((type, result["record_type"]["confidence"].double ?? 0))
            }
            if let text = result["summary"]["text"].string {
                extraction.summary = ExtractedHighlight(
                    section: .summary, text: text, method: method, provider: engine.providerName,
                    sourcePage: result["summary"]["source_pages"].array?.first?.double.map { Int($0) }, confidence: 0
                )
            }
        }

        for chunk in RR.aiChunks(texts, mode: mode) {
            try Task.checkCancellation()
            let text = chunk["text"].string ?? ""
            let prompt = RecordsAIPrompt.combined(local: local, recordTypeHint: hint, pages: text)
            let response = try await transport.complete(prompt: prompt, images: [], engine: engine, maxOutputTokens: Self.tokenBudget(engine: engine, prompt: prompt))
            merge(response, imagePages: [], coversFirstPage: (chunk["pages"].array ?? []).contains { $0.double == 1 })
        }
        if engine.supportsImages, let originalURL {
            let imagePages = pages.filter { RecordsFold.letterCount($0.text ?? "") < Self.usableTextLetters }.prefix(engine.isCloud ? 10 : 4)
            for page in imagePages {
                try Task.checkCancellation()
                guard let jpeg = Self.pageJPEG(url: originalURL, fileType: record.fileType, pageIndex: page.pageIndex + (record.pageStart ?? 0)) else { continue }
                let number = page.pageIndex + 1
                let prompt = RecordsAIPrompt.combined(local: local, recordTypeHint: hint, pages: "=== Page \(number) ===\n(image attached)")
                let response = try await transport.complete(prompt: prompt, images: [jpeg], engine: engine, maxOutputTokens: Self.tokenBudget(engine: engine, prompt: prompt))
                merge(response, imagePages: [number], coversFirstPage: number == 1)
            }
        }
        if let best = typeCandidates.first(where: { candidate in !typeCandidates.contains { $0.1 > candidate.1 } }) {
            extraction.recordType = best.0
            extraction.typeConfidence = best.1
            extraction.typeMethod = method
        }
        return extraction
    }

    /// JPEG (long side 1,600 px) of one page for vision calls.
    static func pageJPEG(url: URL, fileType: RecordFileType, pageIndex: Int) -> Data? {
        var image: CGImage?
        switch fileType {
        case .pdf:
            guard let document = PDFDocument(url: url), let page = document.page(at: pageIndex) else { return nil }
            image = RecordTextExtractor().render(page: page, longSide: 1_600)
        case .image:
            let options: [CFString: Any] = [
                kCGImageSourceCreateThumbnailFromImageAlways: true,
                kCGImageSourceCreateThumbnailWithTransform: true,
                kCGImageSourceThumbnailMaxPixelSize: 1_600,
            ]
            if let source = CGImageSourceCreateWithURL(url as CFURL, nil) {
                image = CGImageSourceCreateThumbnailAtIndex(source, 0, options as CFDictionary)
            }
        default:
            return nil
        }
        guard let image else { return nil }
        let data = NSMutableData()
        guard let destination = CGImageDestinationCreateWithData(data as CFMutableData, UTType.jpeg.identifier as CFString, 1, nil) else { return nil }
        CGImageDestinationAddImage(destination, image, [kCGImageDestinationLossyCompressionQuality: 0.8] as CFDictionary)
        return CGImageDestinationFinalize(destination) ? data as Data : nil
    }
}
