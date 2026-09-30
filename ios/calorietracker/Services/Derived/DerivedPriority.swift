import Foundation

// Priority: a native platform reading always wins over an Ayuvo-derived value. Port of the "Priority" section of
// `scripts/derived_reference.py`.

nonisolated enum DerivedPriority {
    /// A platform reading for the metric and day that Ayuvo did not write.
    struct Native: Sendable {
        var value: Double?
        var source: String?
    }

    struct Result: DerivedOutput {
        var value: Double?
        /// "native", "derived" or nil.
        var sourceKind: String?
        var source: String?

        var jsonObject: [String: Any] {
            let j = DerivedMath.json
            return ["value": j(value), "source_kind": j(sourceKind), "source": j(source)]
        }
    }

    static func priority(enabled: Bool, native: Native?, derived: Double?) -> Result {
        if let native, let value = native.value {
            return Result(value: value, sourceKind: "native", source: native.source)
        }
        if enabled, let derived {
            return Result(value: derived, sourceKind: "derived", source: nil)
        }
        return Result()
    }
}
