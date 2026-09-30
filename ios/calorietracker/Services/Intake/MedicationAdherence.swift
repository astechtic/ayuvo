import Foundation

// Per-medication adherence, on-time share, median delay, missed doses per ISO weekday, streak and a suggested
// reminder time. Port of `meds_adherence` in `scripts/intake_reference.py` (docs/intake-metrics.md §3).

nonisolated enum MedsAdherence {
    struct Log: Sendable {
        var scheduledMs: Int64
        var takenMs: Int64?
        var status: String
    }

    struct Result: DerivedOutput {
        var scheduled = 0
        var taken = 0
        var adherencePct: Double?
        var adherent: Bool?
        var onTimePct: Double?
        var medianDelayMin: Double?
        /// Minutes after local midnight, rounded to 15 minutes.
        var suggestedClockMin: Int?
        /// Monday … Sunday.
        var missedByWeekday = [0, 0, 0, 0, 0, 0, 0]
        var streak = 0

        var jsonObject: [String: Any] {
            let j = DerivedMath.json
            return ["scheduled": scheduled, "taken": taken, "adherence_pct": j(adherencePct), "adherent": j(adherent),
                    "on_time_pct": j(onTimePct), "median_delay_min": j(medianDelayMin),
                    "suggested_clock_min": j(suggestedClockMin), "missed_by_weekday": missedByWeekday, "streak": streak]
        }
    }

    /// `logs` of one medication in the window (PRN doses excluded by the caller).
    static func medsAdherence(timeZone: String, logs input: [Log], config: IntakeConfig) -> Result {
        let th = config.thresholds
        let tz = DerivedDay.timeZone(timeZone)
        let logs = IntakeMath.stableSorted(input) { $0.scheduledMs }
        let taken = logs.filter { $0.status == "taken" && $0.takenMs != nil }
        let missed = logs.filter { $0.status == "missed" }
        let denom = taken.count + missed.count
        var out = Result(scheduled: denom, taken: taken.count)
        if denom == 0 { return out }
        let pct = Double(taken.count) * 100.0 / Double(denom)
        out.adherencePct = DerivedMath.roundTo(pct, 1)
        out.adherent = pct >= th.adherentPct
        if !taken.isEmpty {
            let delays = taken.map { Double($0.takenMs! - $0.scheduledMs) / 60000.0 }
            let on = delays.filter { abs($0) <= th.onTimeMin }.count
            out.onTimePct = DerivedMath.roundTo(Double(on) * 100.0 / Double(taken.count), 1)
            let md = DerivedMath.median(delays)
            out.medianDelayMin = DerivedMath.roundTo(md, 0)
            if taken.count >= th.suggestMinTaken, md > th.suggestDelayMin {
                let clocks = taken.map { Double(IntakeMath.minuteOfDay($0.takenMs!, tz)) }
                let c = DerivedMath.median(clocks)
                let slots = Int((c / 15.0 + 0.5).rounded(.down)) * 15
                out.suggestedClockMin = ((slots % 1440) + 1440) % 1440
            }
        }
        for l in missed {
            let day = DerivedDay.format(DerivedDay.localTime(l.scheduledMs, tz).dayOrdinal)
            out.missedByWeekday[DerivedDay.isoWeekday(day) - 1] += 1
        }
        var streak = 0
        for l in logs.reversed() {
            if l.status == "taken" {
                streak += 1
            } else if l.status == "missed" {
                break
            }
        }
        out.streak = streak
        return out
    }
}
