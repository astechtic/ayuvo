import Foundation

/// The cycle summary Coach may read for one turn (docs/cycle-tracking.md §8, `shared/cycle/coach.json`). Built only
/// when the user turned on Coach access (`coachCycleEnabled`, consent sheet) and finished setup. English on purpose:
/// it is prompt text. Notes and individual day logs are never included.
nonisolated struct CoachCycleContext: Sendable, Equatable {
    /// Header line + up to 9 summary lines.
    var summaryLines: [String]

    static var coach: CycleCoachConfig? { CycleCoachConfig.load() }

    /// `## Data available` lines: the summary (as "- " bullets) and the guardrails.
    var promptLines: [String] {
        guard let coach = Self.coach else { return [] }
        var out = ["", "## Cycle tracking"]
        out.append(contentsOf: summaryLines.enumerated().map { $0.offset == 0 ? $0.element : "- " + $0.element })
        out.append(contentsOf: coach.prompt)
        return out
    }

    /// The summary block for providers without tool calling (≤ 12 lines).
    var onDeviceBlock: String {
        (["## Cycle tracking"] + summaryLines.prefix(11)).joined(separator: "\n")
    }

    /// Did the user ask about periods or cycles? Same folding as the medications check.
    static func mentionsCycle(_ message: String) -> Bool {
        let words = Set(coach?.mentionsWords ?? [])
        guard !words.isEmpty else { return false }
        return RR.words(RR.fold(message)).contains { words.contains($0) }
    }

    static var notAvailableLine: String { coach?.notAvailableLine ?? "" }

    /// The lines from a snapshot and its trends. Pure; `CycleCoachContextTests` checks it never carries notes.
    static func build(snapshot: CycleSnapshot, trends: CycleTrends, coach: CycleCoachConfig, config: CycleConfig = .shared) -> CoachCycleContext {
        func fill(_ key: String, _ values: [String: String]) -> String? {
            guard var text = coach.lines[key] else { return nil }
            for (k, v) in values { text = text.replacingOccurrences(of: "{\(k)}", with: v) }
            return text
        }
        var lines = [coach.header]
        let stats = snapshot.stats
        if stats.cycleCount > 0, let median = stats.cycleMedian, let range = stats.cycleRange {
            let medianText = median == median.rounded() ? String(Int(median)) : String(median)
            if let line = fill("cycles", ["lengths": stats.cycleLengths.map(String.init).joined(separator: ", "), "median": medianText,
                                          "low": String(range[0]), "high": String(range[1]), "variability": stats.variability]) {
                lines.append(line)
            }
        } else if let line = coach.lines["no_cycles"] {
            lines.append(line)
        }
        let p = snapshot.prediction
        if let period = p.periodLength, let line = fill("period", ["days": String(period)]) { lines.append(line) }
        if let day = snapshot.today.cycleDay {
            let phase = config.phases.first { $0.key == snapshot.today.phase }?.title ?? snapshot.today.phase
            if let line = fill("current", ["day": String(day), "phase": phase]) { lines.append(line) }
        }
        if let next = p.nextStart, let range = p.nextRange, let line = fill("next", ["date": next, "low": range[0], "high": range[1]]) {
            lines.append(line)
        }
        if p.lateDays > 0, let line = fill("late", ["days": String(p.lateDays)]) { lines.append(line) }
        let cycles = String(trends.windowCycles)
        let symptoms = trends.symptomFrequency.prefix(5).map { f in
            "\(config.symptoms.first { $0.key == f.key }?.title ?? f.key) (\(f.cycles))"
        }
        if !symptoms.isEmpty, let line = fill("symptoms", ["cycles": cycles, "list": symptoms.joined(separator: ", ")]) { lines.append(line) }
        let moods = trends.moodFrequency.prefix(5).map { f in
            "\(config.moods.first { $0.key == f.key }?.title ?? f.key) (\(f.cycles))"
        }
        if !moods.isEmpty, let line = fill("moods", ["cycles": cycles, "list": moods.joined(separator: ", ")]) { lines.append(line) }
        let pains = trends.cycles.suffix(6).compactMap { $0.painMax.map(String.init) }
        if !pains.isEmpty, let line = fill("pain", ["list": pains.joined(separator: ", ")]) { lines.append(line) }
        return CoachCycleContext(summaryLines: Array(lines.prefix(10)))
    }

    /// The context for this turn, or nil when access is off, setup is not done or nothing is logged.
    @MainActor
    static func current(defaults: UserDefaults = .standard) async -> CoachCycleContext? {
        guard CycleSettings.enabled(defaults), CycleSettings.coachEnabled(defaults), let coach,
              CycleRuntime.shared.databaseExists else { return nil }
        let store = CycleStore.shared
        await store.refreshIfDayChanged()
        guard store.isSetUp, let snapshot = store.snapshot, let trends = store.trends, snapshot.prediction.basis != "none" else { return nil }
        return build(snapshot: snapshot, trends: trends, coach: coach)
    }
}
