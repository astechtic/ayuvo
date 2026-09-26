import Foundation

// Training load: overlapping workouts merged into sessions, minutes × intensity per local day, compared with the
// 28-day mean. Port of the "Training load" section of `scripts/insights_reference.py`.

nonisolated enum TrainingLoadEngine {
    struct Session: Sendable {
        var startMs: Int64
        var endMs: Int64
        var intensity: Double
        var hasEffort: Bool
        var members: Int
        var day: String = ""
        var minutes: Double = 0
        var load: Double = 0
    }

    struct DayLoad: Sendable {
        var load: Double = 0
        var minutes: Double = 0
        var sessions: Int = 0
    }

    struct Raw: Sendable {
        var day: String
        var load: Double
        var minutes: Double
        var sessions: Int
        var mean28d: Double
        var ratio: Double?
        var category: String
    }

    /// Workouts sorted by (start, end); an overlapping workout extends the previous session and keeps the max
    /// intensity. Day = local day of the session start.
    static func sessions(_ workouts: [InsightsWorkout], timeZone: TimeZone, config: InsightsConfig) -> [Session] {
        let t = config.trainingLoad
        let ws = workouts.filter { $0.endMs > $0.startMs }.enumerated().sorted { a, b in
            if a.element.startMs != b.element.startMs { return a.element.startMs < b.element.startMs }
            if a.element.endMs != b.element.endMs { return a.element.endMs < b.element.endMs }
            return a.offset < b.offset
        }.map(\.element)
        var out: [Session] = []
        for w in ws {
            let hasEffort = w.effort != nil
            let inten = w.effort.map { InsightsMath.clamp($0 / t.effortDivisor, t.intensityMin, t.intensityMax) }
                ?? t.defaultIntensity
            if let last = out.last, w.startMs < last.endMs {
                var s = last
                s.endMs = max(s.endMs, w.endMs)
                s.intensity = max(s.intensity, inten)
                s.hasEffort = s.hasEffort || hasEffort
                s.members += 1
                out[out.count - 1] = s
            } else {
                out.append(Session(startMs: w.startMs, endMs: w.endMs, intensity: inten, hasEffort: hasEffort, members: 1))
            }
        }
        for i in out.indices {
            out[i].day = InsightsDay.localDay(ms: out[i].startMs, timeZone)
            out[i].minutes = Double(out[i].endMs - out[i].startMs) / 60000.0
            out[i].load = out[i].minutes * out[i].intensity
        }
        return out
    }

    static func sessions(_ workouts: [InsightsWorkout], timeZone: String, config: InsightsConfig) -> [Session] {
        sessions(workouts, timeZone: InsightsDay.timeZone(timeZone), config: config)
    }

    static func dailyLoads(_ workouts: [InsightsWorkout], timeZone: TimeZone, config: InsightsConfig) -> [String: DayLoad] {
        var days: [String: DayLoad] = [:]
        for s in sessions(workouts, timeZone: timeZone, config: config) {
            var d = days[s.day] ?? DayLoad()
            d.load += s.load
            d.minutes += s.minutes
            d.sessions += 1
            days[s.day] = d
        }
        return days
    }

    static func loadRaw(_ loads: [String: DayLoad], _ day: String, config: InsightsConfig) -> Raw {
        let t = config.trainingLoad
        let today = loads[day] ?? DayLoad()
        var total = 0.0
        for k in stride(from: t.meanWindowDays, to: 0, by: -1) {
            total += loads[InsightsDay.add(day, -k)]?.load ?? 0.0
        }
        let avg = total / Double(t.meanWindowDays)
        let ratio: Double? = avg > 0 ? today.load / avg : nil
        let cat: String
        if today.load == 0 {
            cat = "none"
        } else if ratio == nil || ratio! > t.highAbove {
            cat = "high"
        } else if ratio! < t.lightBelow {
            cat = "light"
        } else {
            cat = "moderate"
        }
        return Raw(day: day, load: today.load, minutes: today.minutes, sessions: today.sessions, mean28d: avg,
                   ratio: ratio, category: cat)
    }

    /// Load of `day` (sum of session minutes × intensity) vs the mean daily load of the 28 days before it.
    static func trainingLoad(_ workouts: [InsightsWorkout], day: String, timeZone: String, config: InsightsConfig) -> TrainingLoadResult {
        let r = loadRaw(dailyLoads(workouts, timeZone: InsightsDay.timeZone(timeZone), config: config), day, config: config)
        return TrainingLoadResult(
            day: r.day, load: InsightsMath.roundTo(r.load, 1), minutes: InsightsMath.roundTo(r.minutes, 1),
            sessions: r.sessions, mean28d: InsightsMath.roundTo(r.mean28d, 1), ratio: InsightsMath.roundTo(r.ratio, 2),
            category: r.category, label: config.trainingLoad.labels[r.category] ?? r.category)
    }
}
