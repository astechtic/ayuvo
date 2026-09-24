import Foundation
import Testing
@testable import calorietracker

/// Runs every case of `shared/ai/test-vectors/*.json` through the Swift port (`AIRef`) and requires
/// exact equality with the reference output (numbers by value, object keys unordered, array order
/// significant), then checks the two things a vector cannot: that the bundled catalogues are the
/// shared ones byte for byte, and that the registry still describes `AIProvider`.
struct AIVectorTests {
    static let vectorFiles = [
        "profile_migration", "adopt_legacy_primary", "legacy_projection", "role_assignment", "profile_add",
        "role_resolution",
        "fallback_resolution", "credential_lookup", "profile_delete", "vertex_endpoint",
        "conversation_override", "token_limit", "local_catalog",
    ]

    static var vectorsDirectory: URL {
        HealthTestFixtures.repoRootURL.appendingPathComponent("shared/ai/test-vectors")
    }

    /// Every vector file in shared/ai/test-vectors must have a runner here.
    @Test func everySharedVectorFileHasARunner() throws {
        let names = try FileManager.default.contentsOfDirectory(atPath: Self.vectorsDirectory.path)
            .filter { $0.hasSuffix(".json") }
            .map { String($0.dropLast(5)) }
        #expect(!names.isEmpty)
        for name in names.sorted() {
            #expect(Self.vectorFiles.contains(name), "no Swift runner for test-vectors/\(name).json")
        }
    }

    @Test(arguments: vectorFiles)
    func vectorFileMatchesReference(_ name: String) throws {
        let url = Self.vectorsDirectory.appendingPathComponent("\(name).json")
        let root = try #require(RJ.parse(try String(contentsOf: url, encoding: .utf8)),
                                "unreadable \(name).json")
        #expect(root["format"].string == "ayuvo-ai-vectors")
        let function = try #require(root["function"].string)
        let cases = root["cases"].array ?? []
        #expect(!cases.isEmpty)
        var passed = 0
        var failures: [String] = []
        for c in cases {
            let actual = AIRef.runCase(function, c["input"])
            if let diff = RecordsVectorTests.firstDifference(actual, c["expected"]) {
                failures.append("\(c["name"].string ?? "?"): \(diff)")
            } else {
                passed += 1
            }
        }
        print("AI-VECTORS \(name).json \(passed)/\(cases.count)")
        let report = "\(name).json \(passed)/\(cases.count) passed\n"
            + failures.joined(separator: "\n")
        #expect(failures.isEmpty, Comment(rawValue: report))
    }

    // MARK: - Bundled copies

    @Test(arguments: [("providers", "shared/ai/providers.json"),
                      ("vertex", "shared/ai/vertex.json"),
                      ("models_catalog", "local-models/catalog.v2.json")])
    func bundledCatalogMatchesShared(_ pair: (String, String)) throws {
        let shared = try String(contentsOf: HealthTestFixtures.repoRootURL
            .appendingPathComponent(pair.1), encoding: .utf8)
        let bundled = try #require(Self.bundledText(pair.0),
                                   "\(pair.0).json is not bundled in the app")
        #expect(bundled == shared, "\(pair.0).json differs from \(pair.1)")
    }

    // MARK: - The registry really describes AIProvider

    /// `shared/ai/providers.json` is the file Android is checked against too. If it drifts from the
    /// Swift enum, the two apps quietly disagree about what a saved profile means.
    @Test func everyProviderInTheRegistryMatchesTheEnum() throws {
        let rows = AICatalog.providers["providers"].array ?? []
        #expect(rows.count == AIProvider.allCases.count)
        for row in rows {
            let token = try #require(row["id"].string)
            let provider = try #require(AIProvider(rawValue: token), "unknown provider \(token)")
            #expect(provider.baseURL == row["base_url"].string, "\(token) base URL")
            #expect(provider.requiresAPIKey == row["requires_key"].bool, "\(token) requiresAPIKey")
            #expect(provider.supportsVision == row["supports_vision"].bool, "\(token) vision")
            #expect(provider.requiresCustomEndpoint == row["requires_custom_endpoint"].bool,
                    "\(token) custom endpoint")
            #expect(provider.requiresCustomModelName == row["requires_custom_model_name"].bool,
                    "\(token) requires custom model")
            #expect(provider.supportsCustomModelName == row["supports_custom_model_name"].bool,
                    "\(token) supports custom model")
            #expect(provider.usesConfigurableRequestTimeout == row["configurable_timeout"].bool,
                    "\(token) timeout")
            #expect(provider.apiKeyPlaceholder == row["key_placeholder"].string,
                    "\(token) placeholder")
            // The on-device row's lineup is the set of INSTALLED models, so it is resolved at
            // runtime and deliberately not compared here.
            if row["models_from"].isNull {
                #expect(provider.models == (row["models"].array ?? []).compactMap(\.string),
                        "\(token) model lineup")
                #expect(provider.textModels == (row["text_models"].array ?? []).compactMap(\.string),
                        "\(token) text lineup")
            }
        }
    }

    /// The override column packs the token after a `|`; a token containing one would make an
    /// imported conversation unparseable on the device that reads it.
    @Test func noProviderTokenCanBreakTheOverrideEncoding() throws {
        for provider in AIProvider.allCases {
            #expect(!provider.rawValue.contains("|"))
            let raw = try #require(try AIRef.encodeOverride("aip_0001", provider.rawValue))
            #expect(AIRef.parseOverride(raw)["provider"].string == provider.rawValue)
            #expect(AIRef.parseOverride(raw)["profile_id"].string == "aip_0001")
        }
    }

    /// Today's `openAICompatibleTokenLimitKey` and the reference must not drift apart.
    @Test func theTokenLimitRuleMatchesTheEnum() {
        let models = ["gpt-5.4-mini", "gpt-4o-mini", "o3-mini", "vendor/o1-preview",
                      "llama-3.3-70b", "claude-sonnet-5"]
        for provider in AIProvider.allCases {
            for model in models {
                #expect(provider.openAICompatibleTokenLimitKey(for: model)
                        == AIRef.tokenLimitKey(provider.rawValue, model),
                        "\(provider.rawValue)/\(model)")
            }
        }
    }

    /// The gate the shipped Gemma artifact already passes must not move when models are added.
    @Test func theMemoryGateDoesNotMoveForTheModelAlreadyInstalled() throws {
        let models = AICatalog.models["models"].array ?? []
        let gemma = try #require(models.first { $0["id"].string == "gemma-4-e2b-it-litertlm" })
        let size = try #require(gemma["artifact"]["sizeBytes"].double).rounded()
        #expect(gemma["memoryPolicy"]["minimumPhysicalMemoryBytes"].double == Double(8 * AIRef.gib))
        #expect(AIRef.memoryGate(Int(size)) == 8 * AIRef.gib)
    }

    private static func bundledText(_ name: String) -> String? {
        for bundle in [Bundle.main, Bundle(for: Marker.self)] {
            if let url = bundle.url(forResource: name, withExtension: "json"),
               let text = try? String(contentsOf: url, encoding: .utf8) {
                return text
            }
        }
        return nil
    }

    private final class Marker {}
}
