import Foundation
import Testing
@testable import calorietracker

/// Runs every case of `shared/intake/test-vectors/*.json` through the Swift engines (docs/intake-metrics.md) and
/// requires equality with `scripts/intake_reference.py` (numbers by value, object keys unordered, absent == null).
struct IntakeVectorTests {
    /// One file per function in `intake_reference.FUNCTIONS`.
    nonisolated static let vectorFiles = ["nutrition_day", "dri_goals", "nutrient_coverage", "supplement_daily",
                                          "meds_adherence", "strength_week", "energy_balance", "paired_difference",
                                          "lab_nutrient_links"]

    static var vectorsDirectory: URL {
        HealthTestFixtures.repoRootURL.appendingPathComponent("shared/intake/test-vectors")
    }

    @Test func sharedVectorFilesMatchRunnersExactly() throws {
        let names = try FileManager.default.contentsOfDirectory(atPath: Self.vectorsDirectory.path)
            .filter { $0.hasSuffix(".json") }
            .map { String($0.dropLast(5)) }
        for name in names.sorted() {
            #expect(Self.vectorFiles.contains(name), "no Swift runner for test-vectors/\(name).json")
        }
        for name in Self.vectorFiles {
            #expect(names.contains(name), "runner \(name) has no test-vectors/\(name).json")
        }
        #expect(Set(names) == Set(Self.vectorFiles))
    }

    @Test(arguments: vectorFiles)
    func vectorFileMatchesReference(_ name: String) throws {
        let url = Self.vectorsDirectory.appendingPathComponent("\(name).json")
        let root = try #require(RJ.parse(try String(contentsOf: url, encoding: .utf8)), "unreadable \(name).json")
        #expect(root["format"].string == "ayuvo-intake-vectors")
        let function = try #require(root["function"].string)
        #expect(function == name)
        let cases = root["cases"].array ?? []
        #expect(!cases.isEmpty)
        var passed = 0
        var failures: [String] = []
        for c in cases {
            do {
                let actual = try IntakeVectorRunner.run(function: function, input: c["input"], config: IntakeConfig.shared)
                if let diff = InsightsVectorRunner.firstDifference(actual, c["expected"]) {
                    failures.append("\(c["name"].string ?? "?"): \(diff)")
                } else {
                    passed += 1
                }
            } catch {
                failures.append("\(c["name"].string ?? "?"): threw \(error)")
            }
        }
        print("INTAKE-VECTORS \(name).json \(passed)/\(cases.count)")
        let report = "\(name).json \(passed)/\(cases.count) passed\n" + failures.joined(separator: "\n")
        #expect(failures.isEmpty, Comment(rawValue: report))
    }

    @Test func bundledConfigIsByteIdenticalToShared() throws {
        let shared = try Data(contentsOf: HealthTestFixtures.repoRootURL.appendingPathComponent("shared/intake/intake_config.json"))
        // The app sources sit next to this test target's folder (`<app>Tests` → `<app>`).
        let testsFolder = URL(fileURLWithPath: #filePath).deletingLastPathComponent()
        let source = testsFolder.deletingLastPathComponent()
            .appendingPathComponent(testsFolder.lastPathComponent.replacingOccurrences(of: "Tests", with: ""))
            .appendingPathComponent("Services/Intake/Resources/intake_config.json")
        #expect(try Data(contentsOf: source) == shared, "Services/Intake/Resources/intake_config.json differs from shared/intake")
        let bundled = try Data(contentsOf: try #require(IntakeConfig.bundledURL, "intake_config.json is bundled"))
        #expect(bundled == shared, "bundled intake_config.json differs from shared/intake")
    }

    @Test func bundledConfigDecodes() throws {
        let config = try #require(IntakeConfig.load())
        #expect(config.format == "ayuvo-intake-config")
        #expect(config.dri.goals["iron_mg"]?["19-30"] == [8, 18])
        #expect(config.labLinks.map(\.id).contains("iron"))
    }

    @Test func driGoalsMapToAppNutrients() {
        let dri = NutrientGoals.driGoals(sex: "male", age: 40, config: IntakeConfig.shared)
        let goals = IntakeNutrientKeys.optionalGoals(from: dri)
        #expect(goals["iron"] == 8)
        #expect(goals["vitamin_d"] == 15)
        #expect(goals["fiber"] == 38)
        #expect(IntakeNutrientKeys.intakeKey("vitamin_b12") == "vitamin_b12_mcg")
        let female = NutrientsReference.Profile(age: 45, sex: "female", calorieGoal: nil)
        #expect(IntakeNutrientKeys.driDefaultGoal(key: "iron", profile: female) == 18)
        #expect(IntakeNutrientKeys.driDefaultGoal(key: "iron", profile: .unknown) == nil)
    }
}

/// Decodes the vectors' plain inputs into typed engine inputs and runs one case.
nonisolated enum IntakeVectorRunner {
    enum Failure: Error { case unknownFunction(String) }

    static func ms(_ x: RJ) -> Int64? { x.double.map { Int64($0) } }

    static func numbers(_ x: RJ) -> [String: Double] {
        (x.object ?? [:]).reduce(into: [:]) { out, kv in if let v = kv.value.double { out[kv.key] = v } }
    }

    static func output(_ result: some DerivedOutput) -> RJ { RJ.from(result.jsonObject) }

    static func run(function: String, input c: RJ, config: IntakeConfig) throws -> RJ {
        switch function {
        case "nutrition_day":
            let items: [NutritionDerivation.Item] = (c["items"].array ?? []).map { i in
                NutritionDerivation.Item(
                    eatenMs: ms(i["eaten_ms"]) ?? 0, meal: i["meal"].string, calories: i["calories"].double,
                    proteinG: i["protein_g"].double, carbsG: i["carbs_g"].double, fatG: i["fat_g"].double,
                    saturatedFatG: i["saturated_fat_g"].double, fiberG: i["fiber_g"].double,
                    sodiumMg: i["sodium_mg"].double, potassiumMg: i["potassium_mg"].double, ironMg: i["iron_mg"].double,
                    caffeineMg: i["caffeine_mg"].double, isTeaOrCoffee: i["is_tea_or_coffee"].truthy
                )
            }
            return output(NutritionDerivation.nutritionDay(.init(timeZone: c["time_zone"].string ?? "UTC",
                                                                 weightKg: c["weight_kg"].double, items: items,
                                                                 bedtimeMs: ms(c["bedtime_ms"])), config: config))
        case "dri_goals":
            return output(NutrientGoals.driGoals(sex: c["sex"].string, age: c["age"].double, config: config))
        case "nutrient_coverage":
            let days = (c["days"].array ?? []).map { d in
                NutrientGoals.CoverageDay(day: d["day"].string ?? "", totals: numbers(d["totals"]))
            }
            return output(NutrientGoals.nutrientCoverage(days: days, goals: numbers(c["goals"]), limits: numbers(c["limits"]),
                                                         config: config))
        case "supplement_daily":
            return output(NutrientGoals.supplementDaily(amountPerDose: c["amount_per_dose"].double ?? 0,
                                                        doses: c["doses"].array?.count ?? 0,
                                                        windowDays: c["window_days"].double ?? 0,
                                                        upper: c["upper"].double))
        case "meds_adherence":
            let logs = (c["logs"].array ?? []).map { l in
                MedsAdherence.Log(scheduledMs: ms(l["scheduled_ms"]) ?? 0, takenMs: ms(l["taken_ms"]),
                                        status: l["status"].string ?? "")
            }
            return output(MedsAdherence.medsAdherence(timeZone: c["time_zone"].string ?? "UTC", logs: logs,
                                                            config: config))
        case "strength_week":
            let sessions = (c["sessions"].array ?? []).map { s in
                StrengthWeek.Session(day: s["day"].string ?? "", exercises: (s["exercises"].array ?? []).map { e in
                    StrengthWeek.Exercise(name: e["name"].string ?? "",
                                          primaryMuscles: (e["primary_muscles"].array ?? []).compactMap(\.string),
                                          sets: (e["sets"].array ?? []).map { st in
                                              StrengthWeek.WorkSet(reps: st["reps"].double, weightKg: st["weight_kg"].double)
                                          })
                })
            }
            return output(StrengthWeek.strengthWeek(sessions: sessions, plannedSessions: c["planned_sessions"].double.map { Int($0) },
                                                    config: config))
        case "energy_balance":
            return output(EnergyBalance.energyBalance(day: c["day"].string ?? "", intake: numbers(c["intake"]),
                                                      tdee: numbers(c["tdee"]), weights: numbers(c["weights"]),
                                                      config: config))
        case "paired_difference":
            let exposure = (c["exposure"].object ?? [:]).mapValues(\.truthy)
            return output(EnergyBalance.pairedDifference(exposure: exposure, outcome: numbers(c["outcome"]),
                                                         lagDays: Int(c["lag_days"].double ?? 0), config: config))
        case "lab_nutrient_links":
            let labs = (c["labs"].array ?? []).map { l in
                LabLinks.Lab(analyte: l["analyte"].string ?? "", value: l["value"].double ?? 0,
                             refLow: l["ref_low"].double, refHigh: l["ref_high"].double)
            }
            return output(LabLinks.labNutrientLinks(labs: labs, intakeAvg: numbers(c["intake_avg"]), goals: numbers(c["goals"]),
                                                    supplementNutrients: (c["supplement_nutrients"].array ?? []).compactMap(\.string),
                                                    config: config))
        default:
            throw Failure.unknownFunction(function)
        }
    }
}
