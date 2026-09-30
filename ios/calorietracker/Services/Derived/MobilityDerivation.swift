import Foundation

// Mobility and hearing: weekly gait summary and daily headphone sound dose. Port of the "Mobility and hearing"
// section of `scripts/derived_reference.py`.

nonisolated enum MobilityDerivation {
    struct GaitResult: DerivedOutput {
        var speedMedian: Double?
        var slowGait: Bool
        var doubleSupportMedian: Double?
        var asymmetryFlagCount: Int
        var asymmetryFlag: Bool

        var jsonObject: [String: Any] {
            let j = DerivedMath.json
            return ["speed_median": j(speedMedian), "slow_gait": slowGait, "double_support_median": j(doubleSupportMedian),
                    "asymmetry_flag_count": asymmetryFlagCount, "asymmetry_flag": asymmetryFlag]
        }
    }

    /// Readings from the last 7 days.
    static func gaitWeek(walkingSpeed: [Double?], doubleSupport: [Double?], asymmetry: [Double?],
                         config: DerivedConfig) -> GaitResult {
        let th = config.thresholds
        let sp = walkingSpeed.compactMap { $0 }.filter { $0 > 0 }
        let ds = doubleSupport.compactMap { $0 }
        let asy = asymmetry.compactMap { $0 }.filter { $0 < 100 }
        let speed: Double? = sp.isEmpty ? nil : DerivedMath.median(sp)
        let count = asy.filter { $0 > th.asymmetryPct }.count
        return GaitResult(speedMedian: DerivedMath.roundTo(speed, 2), slowGait: speed != nil && speed! < th.slowGaitMps,
                          doubleSupportMedian: ds.isEmpty ? nil : DerivedMath.roundTo(DerivedMath.median(ds), 1),
                          asymmetryFlagCount: count, asymmetryFlag: count >= th.asymmetryFlagCount)
    }

    struct AudioSample: Sendable {
        var startMs: Double
        var endMs: Double
        var db: Double
    }

    struct AudioResult: DerivedOutput {
        var dosePct: Double
        var loudMinutes: Double
        var jsonObject: [String: Any] { ["dose_pct": dosePct, "loud_minutes": loudMinutes] }
    }

    /// Headphone exposure samples for the day.
    static func audioDay(samples: [AudioSample], config: DerivedConfig) -> AudioResult {
        let th = config.thresholds
        var dose = 0.0, loud = 0.0
        for s in samples {
            let hours = max(0, s.endMs - s.startMs) / 3_600_000.0
            dose += hours * pow(2.0, (s.db - th.soundRefDb) / th.soundExchangeDb)
            if s.db >= th.soundRefDb { loud += max(0, s.endMs - s.startMs) / 60000.0 }
        }
        return AudioResult(dosePct: DerivedMath.roundTo(dose * 100.0 / th.soundWeeklyHours, 2),
                           loudMinutes: DerivedMath.roundTo(loud, 1))
    }
}
