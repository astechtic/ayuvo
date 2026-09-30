import Foundation

// Links a lab value below the report's printed reference range to the nutrient's average intake (e.g. low
// haemoglobin or MCV ↔ iron). Port of `lab_nutrient_links` in `scripts/intake_reference.py`. The card built on it
// only suggests talking to a doctor and never names a condition.

nonisolated enum LabLinks {
    struct Lab: Sendable {
        var analyte: String
        var value: Double
        var refLow: Double?
        var refHigh: Double?
    }

    struct Link: Sendable, Equatable {
        var id: String
        var analytesLow: [String]
        var nutrient: String
        var intakePct: Double?
        var supplementProvides: Bool

        var jsonObject: [String: Any] {
            ["id": id, "analytes_low": analytesLow, "nutrient": nutrient, "intake_pct": DerivedMath.json(intakePct),
             "supplement_provides": supplementProvides]
        }
    }

    struct Result: DerivedOutput {
        var links: [Link]
        var jsonObject: [String: Any] { ["links": links.map(\.jsonObject)] }
    }

    /// `labs` latest per analyte, `intakeAvg` per day incl. supplements, `supplementNutrients` keys any active
    /// supplement provides.
    static func labNutrientLinks(labs: [Lab], intakeAvg: [String: Double], goals: [String: Double],
                                 supplementNutrients: [String], config: IntakeConfig) -> Result {
        var byAnalyte: [String: Lab] = [:]
        for l in labs { byAnalyte[l.analyte] = l }
        var out: [Link] = []
        for r in config.labLinks {
            let low = r.analytes.filter { a in
                guard let lab = byAnalyte[a], let lo = lab.refLow else { return false }
                return lab.value < lo
            }
            if low.isEmpty { continue }
            let n = r.nutrient
            var pct: Double?
            if let g = goals[n], g != 0, let intake = intakeAvg[n] { pct = DerivedMath.roundTo(intake * 100.0 / g, 0) }
            out.append(Link(id: r.id, analytesLow: low, nutrient: n, intakePct: pct,
                            supplementProvides: supplementNutrients.contains(n)))
        }
        return Result(links: out)
    }
}
