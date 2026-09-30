import Foundation

/// GPS-workout VO₂max as an input of `DerivedMetricsService`: the latest value from an outdoor session
/// (Cooper test first, else ACSM steady segments) on or before a day, within `lookbackDays`.
extension DerivedMetricsService {
    nonisolated static let gpsVO2maxLookbackDays = 180

    /// Diary day → best VO₂max of that day's outdoor sessions.
    static func gpsVO2maxByDay(defaults: UserDefaults) -> [String: Double] {
        let store = StrengthWorkoutStore(defaults: defaults, observesExternalChanges: false)
        var out: [String: Double] = [:]
        for session in store.completedSessions {
            guard let value = session.outdoor?.bestVO2max, value.isFinite, value > 0 else { continue }
            let day = session.stableDiaryDateKey
            out[day] = max(out[day] ?? 0, value)
        }
        return out
    }

    nonisolated static func latestGPSVO2max(_ series: [String: Double], _ day: String) -> Double? {
        guard !series.isEmpty else { return nil }
        let oldest = InsightsDay.add(day, -gpsVO2maxLookbackDays)
        return series.filter { $0.key <= day && $0.key >= oldest }.max { $0.key < $1.key }?.value
    }
}
