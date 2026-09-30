import Foundation

// Energy: Mifflin–St Jeor BMR, TDEE and physical activity level. Port of the "Energy" section of
// `scripts/derived_reference.py`.

nonisolated enum EnergyDerivation {
    struct DayInput: Sendable {
        var restingKcal: Double?
        /// Ayuvo's own workout burns excluded.
        var activeKcal: Double?
        var weightKg: Double?
        var heightCm: Double?
        var age: Double?
        var sex: String?
    }

    struct DayResult: DerivedOutput {
        var bmrMifflin: Double?
        var restingKcal: Double?
        var tdee: Double?
        var pal: Double?
        var palBandIndex: Int?
        var status = "no_resting"

        var jsonObject: [String: Any] {
            let j = DerivedMath.json
            return ["bmr_mifflin": j(bmrMifflin), "resting_kcal": j(restingKcal), "tdee": j(tdee), "pal": j(pal),
                    "pal_band_index": j(palBandIndex), "status": status]
        }
    }

    static func mifflin(weightKg: Double, heightCm: Double, age: Double, sex: String?, _ th: DerivedConfig.Thresholds) -> Double {
        10.0 * weightKg + 6.25 * heightCm - 5.0 * age + DerivedConfig.Thresholds.bySex(th.mifflin, sex)
    }

    static func energyDay(_ inp: DayInput, config: DerivedConfig) -> DayResult {
        let th = config.thresholds
        var bmr: Double?
        if let w = inp.weightKg, w != 0, let h = inp.heightCm, h != 0, let age = inp.age {
            bmr = mifflin(weightKg: w, heightCm: h, age: age, sex: inp.sex, th)
        }
        var out = DayResult(bmrMifflin: DerivedMath.roundTo(bmr, 0))
        guard let rest = inp.restingKcal, rest > 0 else { return out }
        if let bmr, rest < th.bmrMinShare * bmr {
            out.status = "partial_day"
            return out
        }
        let active = inp.activeKcal ?? 0.0
        let tdee = (rest + active) / (1.0 - th.tefShare)
        let pal = tdee / rest
        var band = 0
        for c in th.palBands where pal >= c { band += 1 }
        out.restingKcal = DerivedMath.roundTo(rest, 0)
        out.tdee = DerivedMath.roundTo(tdee, 0)
        out.pal = DerivedMath.roundTo(pal, 2)
        out.palBandIndex = band
        out.status = "ok"
        return out
    }
}
