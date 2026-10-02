#if DEBUG
import Foundation

/// UI-test launch arguments (DEBUG builds only):
///   `-AyuvoCycleReset` wipes the cycle database once per launch (setup flow from empty);
///   `-AyuvoCycleSeed` (after the reset, when there are no periods) writes four regular periods ending ~26 days ago
///   relative to today, a few day logs and finished setup, so every screen has real data.
@MainActor
enum CycleDebugSeed {
    private static var done = false

    static var requested: (reset: Bool, seed: Bool) {
        let args = ProcessInfo.processInfo.arguments
        return (args.contains("-AyuvoCycleReset"), args.contains("-AyuvoCycleSeed"))
    }

    static func applyIfRequested(runtime: CycleRuntime) async {
        guard !done else { return }
        done = true
        let (reset, seed) = requested
        guard reset || seed, let repository = await runtime.openRepository() else { return }
        if reset { try? await repository.deleteAll() }
        guard seed, ((try? await repository.periods()) ?? []).isEmpty else { return }
        let today = CycleDay.today()
        let now = CycleDates.nowMs()
        let todayText = CycleDay.string(today)
        // Cycle lengths 29, 28, 30; the last period started 26 days ago.
        let lastStart = today - 26
        let starts = [lastStart - 87, lastStart - 58, lastStart - 30, lastStart]
        for (i, s) in starts.enumerated() {
            _ = try? await repository.savePeriod(id: nil, start: CycleDay.string(s), end: CycleDay.string(s + 4), today: todayText,
                                                 nowMs: now + Int64(i))
        }
        let flows = ["medium", "heavy", "heavy", "medium", "light"]
        for (k, flow) in flows.enumerated() {
            let log = CycleDayLogRecord(day: CycleDay.string(lastStart + k), flow: flow, pain: [6, 5, 3, 1, 0][k],
                                        painLocations: k < 2 ? ["lower_abdomen"] : [], symptoms: k < 2 ? ["cramps", "fatigue"] : ["bloating"],
                                        moods: k == 0 ? ["low_energy"] : [], note: nil, updatedMs: now)
            try? await repository.saveDayLog(log, today: todayText, nowMs: now)
        }
        var settings = CycleSettingsRecord()
        settings.setupDone = true
        settings.cycleLength = 29
        settings.periodLength = 5
        var options = CycleSettingsOptions()
        options.healthSync = false
        settings.options = options
        try? await repository.saveSettings(settings, nowMs: now)
    }
}
#endif
