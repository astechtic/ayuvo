import Foundation
import Testing
@testable import calorietracker

/// Runs every case of `shared/nutrients/test-vectors/*.json` through the Swift port (`NutrientsReference`)
/// and requires equality with the Python reference (numbers by value, object keys unordered, arrays ordered).
struct NutrientsVectorTests {
    nonisolated static let vectorFiles = [
        "reference_lines", "default_goal", "iu_conversion", "supplement_entries", "day_totals",
        "logged_day_average", "label_output",
    ]

    static var vectorsDirectory: URL {
        HealthTestFixtures.repoRootURL.appendingPathComponent("shared/nutrients/test-vectors")
    }

    /// Every vector file in shared/nutrients/test-vectors must have a runner here.
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
        let root = try #require(RJ.parse(try String(contentsOf: url, encoding: .utf8)), "unreadable \(name).json")
        #expect(root["format"].string == "ayuvo-nutrients-vectors")
        let function = try #require(root["function"].string)
        let cases = root["cases"].array ?? []
        #expect(!cases.isEmpty)
        var passed = 0
        var failures: [String] = []
        for c in cases {
            let actual = NutrientsReference.runCase(function: function, input: c["input"])
            if let diff = RecordsVectorTests.firstDifference(actual, c["expected"]) {
                failures.append("\(c["name"].string ?? "?"): \(diff)")
            } else {
                passed += 1
            }
        }
        print("NUTRIENTS-VECTORS \(name).json \(passed)/\(cases.count)")
        let report = "\(name).json \(passed)/\(cases.count) passed\n" + failures.joined(separator: "\n")
        #expect(failures.isEmpty, Comment(rawValue: report))
    }

    @Test func bundledResourcesAreByteIdenticalToShared() throws {
        let shared = HealthTestFixtures.repoRootURL.appendingPathComponent("shared/nutrients")
        let testsFolder = URL(fileURLWithPath: #filePath).deletingLastPathComponent()
        let resources = testsFolder.deletingLastPathComponent()
            .appendingPathComponent("calorietracker/Nutrients/Resources")
        for file in ["nutrient_reference.json", "ai_supplement_label.md"] {
            let a = try Data(contentsOf: shared.appendingPathComponent(file))
            let b = try Data(contentsOf: resources.appendingPathComponent(file))
            #expect(a == b, "Nutrients/Resources/\(file) differs from shared/nutrients/\(file)")
        }
    }

    @Test func bundledReferenceAndPromptsLoad() throws {
        let data = NutrientReferenceData.shared
        #expect(data.nutrients.count == 37)
        #expect(data.trackedNutrients.count == 23)
        #expect(data.sportsSupplements.count == 8)
        #expect(NutrientsReference.nutrientUnit("vitamin_d") == "mcg")
        let prompts = try #require(NutrientsReference.bundledPrompts, "ai_supplement_label.md is bundled")
        #expect(prompts.cloud.hasPrefix("You read dietary supplement labels"))
        #expect(prompts.userPhoto.contains("{dose_unit}") && prompts.userPhoto.contains("{name}") && prompts.userPhoto.contains("{strength}"))
        #expect(prompts.userText.contains("{strength}"))
        #expect(!prompts.local.isEmpty)
        #expect(!prompts.cloud.hasSuffix("\n"))
    }

    /// The app's nutrient units must match the reference (the checker parses the iOS model as well).
    @Test func optionalNutrientUnitsMatchReference() {
        for nutrient in OptionalNutrient.allCases {
            #expect(NutrientsReference.nutrientUnit(nutrient.jsonKey) == nutrient.unit, "\(nutrient.jsonKey)")
        }
    }

    // MARK: - AI output validator (simulated model answers)

    @Test func labelValidatorAcceptsAFencedMultivitamin() {
        let answer = """
        ```json
        {"serving_units": 2, "items": [
          {"key": "vitamin_d", "amount": 2000, "unit": "IU", "form": null},
          {"key": "zinc", "amount": 22, "unit": "mg", "form": null},
          {"key": "vitamin_e", "amount": 30, "unit": "IU", "form": "natural"}
        ]}
        ```
        """
        let result = NutrientsReference.parseLabelOutput(answer)
        #expect(result.ok)
        #expect(result.items.map(\.key) == ["vitamin_d", "zinc", "vitamin_e"])
        #expect(result.items[0].amount == 25 && result.items[0].unit == "mcg")
        #expect(result.items[1].amount == 11)
        #expect(abs(result.items[2].amount - 10.05) < 1e-9)
        #expect(result.rejected.isEmpty)
    }

    @Test func labelValidatorRejectsGuessesAndUnknowns() {
        let answer = #"{"items":[{"key":"grape_seed_extract","amount":30,"unit":"mg"},{"key":"vitamin_a","amount":5000,"unit":"IU"},{"key":"calcium","amount":200,"unit":"IU"},{"key":"vitamin_d","amount":150000,"unit":"mcg"},{"key":"iron","amount":18,"unit":"mg"},{"key":"iron","amount":9,"unit":"mg"}]}"#
        let result = NutrientsReference.parseLabelOutput(answer)
        #expect(result.ok)
        #expect(result.items.map(\.key) == ["iron"])
        #expect(result.rejected.map(\.code) == ["unknown_nutrient", "form_required", "iu_not_supported", "amount_too_large", "duplicate_nutrient"])
    }

    /// The contract's new cases (every reference nutrient is a supplement nutrient) must be present and pass, so
    /// a stale vector copy or a skipped case fails here.
    @Test(arguments: [
        ("day_totals", "kolkata_untracked_copper_iodine_supplements_only"),
        ("day_totals", "utc_untracked_food_only_no_key"),
        ("logged_day_average", "utc_untracked_iodine_dose_days_only"),
        ("logged_day_average", "utc_tracked_zinc_same_days"),
        ("iu_conversion", "vitamin_a_retinyl_acetate_mcg_form_null"),
        ("label_output", "multivitamin_all_reference_nutrients"),
    ])
    func contractCaseIsPresentAndPasses(_ file: String, _ caseName: String) throws {
        let url = Self.vectorsDirectory.appendingPathComponent("\(file).json")
        let root = try #require(RJ.parse(try String(contentsOf: url, encoding: .utf8)))
        let function = try #require(root["function"].string)
        let match = (root["cases"].array ?? []).first { $0["name"].string == caseName }
        let c = try #require(match, "\(file).json has no case \(caseName)")
        let diff = RecordsVectorTests.firstDifference(NutrientsReference.runCase(function: function, input: c["input"]), c["expected"])
        #expect(diff == nil, "\(caseName): \(diff ?? "")")
    }

    @Test func multivitaminLabelKeepsEveryReferenceNutrient() {
        let answer = #"{"serving_units":1,"items":[{"key":"thiamin","amount":1.4,"unit":"mg"},{"key":"riboflavin","amount":1.6,"unit":"mg"},{"key":"vitamin_b6","amount":2,"unit":"mg"},{"key":"biotin","amount":30,"unit":"mcg"},{"key":"iodine","amount":140,"unit":"mcg"},{"key":"manganese","amount":2,"unit":"mg"},{"key":"copper","amount":1.7,"unit":"mg"},{"key":"chromium","amount":50,"unit":"mcg"},{"key":"vitamin_a","amount":1000,"unit":"mcg","form":null},{"key":"grape_seed_extract","amount":25,"unit":"mg"}]}"#
        let result = NutrientsReference.parseLabelOutput(answer)
        #expect(result.items.map(\.key) == ["thiamin", "riboflavin", "vitamin_b6", "biotin", "iodine", "manganese", "copper", "chromium", "vitamin_a"])
        #expect(result.items.first { $0.key == "vitamin_a" }?.amount == 1000, "retinyl in mcg is 1:1 mcg RAE")
        #expect(result.rejected.map(\.code) == ["unknown_nutrient"])
    }

    @Test func labelValidatorFailsOnProse() {
        #expect(NutrientsReference.parseLabelOutput("Sorry, I can't read this label.").error == "parse_error")
        #expect(NutrientsReference.parseLabelOutput(#"{"nutrients": []}"#).error == "bad_shape")
        #expect(NutrientsReference.parseLabelOutput(#"{"serving_units": 1, "items": []}"#).items.isEmpty)
    }
}
