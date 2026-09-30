import Foundation

/// Derived-metric switches (docs/derived-metrics.md §3). Only switches live here, never a value. Both keys roam in
/// portable data (`preferences`) and ride the cloud backup.
nonisolated enum DerivedSettings {
    /// Master switch, default on.
    static let enabledKey = "derivedMetricsEnabled"
    /// Metric ids the person switched off (every metric is on by default).
    static let disabledKey = "derivedMetricsDisabled"
    /// BMI cut-offs: "who" (default) or "asian" (WHO Expert Consultation 2004).
    static let bmiSchemeKey = "derivedBMIScheme"

    static func isEnabled(_ defaults: UserDefaults = .standard) -> Bool {
        defaults.object(forKey: enabledKey) as? Bool ?? true
    }

    static func disabledIDs(_ defaults: UserDefaults = .standard) -> Set<String> {
        Set(defaults.stringArray(forKey: disabledKey) ?? [])
    }

    static func bmiScheme(_ defaults: UserDefaults = .standard) -> String {
        defaults.string(forKey: bmiSchemeKey) == "asian" ? "asian" : "who"
    }

    /// True when the master switch is on and the metric is not switched off.
    static func isEnabled(_ id: String, defaults: UserDefaults = .standard) -> Bool {
        isEnabled(defaults) && !disabledIDs(defaults).contains(id)
    }

    /// Ids of every metric currently computed.
    static func enabledIDs(catalog: DerivedCatalog = .shared, defaults: UserDefaults = .standard) -> Set<String> {
        guard isEnabled(defaults) else { return [] }
        let off = disabledIDs(defaults)
        return Set(catalog.metrics.map(\.id).filter { !off.contains($0) })
    }

    static func setEnabled(_ id: String, _ on: Bool, defaults: UserDefaults = .standard) {
        var off = disabledIDs(defaults)
        if on { off.remove(id) } else { off.insert(id) }
        defaults.set(off.sorted(), forKey: disabledKey)
    }
}
