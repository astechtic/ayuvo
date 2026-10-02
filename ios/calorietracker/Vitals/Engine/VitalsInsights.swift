import Foundation

// Finger-scan fallback for Recovery / Health Age (docs/camera-vitals.md §7): port of `insights_fallback` in
// `scripts/vitals_reference.py`. Face scans never count; days with a wearable value are never filled.

nonisolated extension VitalsEngine {
    private typealias M = VitalsMath

    /// One saved scan as `insights_fallback` reads it: `metrics` is the results' `{metric_id: envelope}` object.
    struct FallbackScan: Sendable {
        var localDay: String
        var mode: String
        var context: String
        var qualityScore: Double?
        var rejectReason: String?
        var metrics: RJ
    }

    /// The insights series a scan metric can stand in for, by `hrv_kind` ("sdnn" on iOS, "rmssd" on Android).
    static func fallbackSources(hrvKind: String) -> [String: String] {
        ["resting_heart_rate": "heart_rate", "hrv": "hrv_" + hrvKind, "respiratory_rate": "respiratory_rate"]
    }

    /// `insights_fallback`: per series, day → median value of the valid finger scans taken in a configured context
    /// with quality at or above `min_quality`, for days without a platform value. Empty series are kept.
    static func insightsFallback(platformDays: [String: [String]], scans: [FallbackScan], hrvKind: String,
                                 _ cfg: VitalsConfig) -> [String: [String: Double]] {
        let fb = cfg.insightsFallback
        let sources = fallbackSources(hrvKind: hrvKind)
        var out: [String: [String: Double]] = [:]
        for series in sources.keys.sorted() {
            let have = Set(platformDays[series] ?? [])
            var perDay: [String: [Double]] = [:]
            for sc in scans {
                if sc.mode != fb.mode || sc.rejectReason != nil { continue }
                guard fb.contexts.contains(sc.context), let q = sc.qualityScore else { continue }
                if q < fb.minQuality || have.contains(sc.localDay) { continue }
                let env = sc.metrics[sources[series]!]
                guard env["status"].string == "valid", let value = env["value"].double else { continue }
                perDay[sc.localDay, default: []].append(value)
            }
            var days: [String: Double] = [:]
            for day in perDay.keys.sorted() { days[day] = M.roundTo(M.median(perDay[day]!), 2) }
            out[series] = days
        }
        return out
    }
}
