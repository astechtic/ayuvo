import Foundation

/// One set of tape-measure circumferences logged at a point in time. Every site is optional —
/// the user logs only what they want, and an entry with even a single value is valid. Stored
/// internally in centimetres (display converts to inches when the app is in imperial mode),
/// mirroring how WeightEntry stores kg regardless of the display unit.
///
/// The derived metrics (waist-to-hip, waist-to-height, US-Navy body-fat %, wrist frame size) are
/// computed on the fly from the latest entry plus the profile's height + gender. Nothing here is
/// written back to UserProfile — these are purely extra signal for the AI goal calc and the Coach.
struct BodyMeasurement: Identifiable, Codable, Equatable {
    let id: UUID
    let date: Date
    var neckCm: Double?
    var waistCm: Double?
    var hipsCm: Double?
    var chestCm: Double?
    var upperArmCm: Double?
    var thighCm: Double?
    var calfCm: Double?
    var wristCm: Double?

    init(
        id: UUID = UUID(),
        date: Date = .now,
        neckCm: Double? = nil,
        waistCm: Double? = nil,
        hipsCm: Double? = nil,
        chestCm: Double? = nil,
        upperArmCm: Double? = nil,
        thighCm: Double? = nil,
        calfCm: Double? = nil,
        wristCm: Double? = nil
    ) {
        self.id = id
        self.date = date
        self.neckCm = neckCm
        self.waistCm = waistCm
        self.hipsCm = hipsCm
        self.chestCm = chestCm
        self.upperArmCm = upperArmCm
        self.thighCm = thighCm
        self.calfCm = calfCm
        self.wristCm = wristCm
    }

    /// True when at least one circumference is present — an entry with nothing logged is meaningless.
    var hasAnyValue: Bool {
        [neckCm, waistCm, hipsCm, chestCm, upperArmCm, thighCm, calfCm, wristCm].contains { $0 != nil }
    }

    // MARK: - Derived metrics (all optional — nil when the inputs they need aren't logged)

    /// Waist ÷ hips. WHO cardiometabolic-risk marker. Needs waist + hips.
    var waistToHipRatio: Double? {
        guard let waist = waistCm, let hips = hipsCm, hips > 0 else { return nil }
        return waist / hips
    }

    /// Waist ÷ height. "Keep your waist under half your height." Needs waist + a height.
    func waistToHeightRatio(heightCm: Double) -> Double? {
        guard let waist = waistCm, heightCm > 0 else { return nil }
        return waist / heightCm
    }

    /// U.S. Navy body-fat % estimate (metric coefficients, inputs in cm). Men use neck + waist;
    /// women use neck + waist + hips. Returns nil when the required sites are missing or the
    /// logarithm domain is invalid, and clamps obviously-bad outputs out.
    func usNavyBodyFatPercent(gender: Gender, heightCm: Double) -> Double? {
        guard heightCm > 0, let neck = neckCm, let waist = waistCm else { return nil }
        let result: Double
        switch gender {
        case .female:
            guard let hips = hipsCm else { return nil }
            let inner = waist + hips - neck
            guard inner > 0 else { return nil }
            result = 495.0 / (1.29579 - 0.35004 * log10(inner) + 0.22100 * log10(heightCm)) - 450.0
        case .male, .other:
            let inner = waist - neck
            guard inner > 0 else { return nil }
            result = 495.0 / (1.0324 - 0.19077 * log10(inner) + 0.15456 * log10(heightCm)) - 450.0
        }
        guard result.isFinite, result >= 2, result <= 65 else { return nil }
        return result
    }

    /// Bone-frame size from height ÷ wrist circumference (gender-specific cut-offs). Needs wrist.
    func wristFrame(gender: Gender, heightCm: Double) -> FrameSize? {
        guard let wrist = wristCm, wrist > 0, heightCm > 0 else { return nil }
        let ratio = heightCm / wrist
        switch gender {
        case .female:
            if ratio > 11.0 { return .small }
            if ratio >= 10.1 { return .medium }
            return .large
        case .male, .other:
            if ratio > 10.4 { return .small }
            if ratio >= 9.6 { return .medium }
            return .large
        }
    }

    enum FrameSize: String {
        case small, medium, large
        var label: String {
            switch self {
            case .small: return "Small"
            case .medium: return "Medium"
            case .large: return "Large"
            }
        }
    }

    /// The eight circumference sites, in display order. Used to render the per-site editor rows.
    enum Site: String, CaseIterable, Identifiable {
        case neck, waist, hips, chest, upperArm, thigh, calf, wrist
        var id: String { rawValue }
        var label: String {
            switch self {
            case .neck: return "Neck"
            case .waist: return "Waist"
            case .hips: return "Hips"
            case .chest: return "Chest"
            case .upperArm: return "Upper Arm"
            case .thigh: return "Thigh"
            case .calf: return "Calf"
            case .wrist: return "Wrist"
            }
        }
    }

    func value(for site: Site) -> Double? {
        switch site {
        case .neck: return neckCm
        case .waist: return waistCm
        case .hips: return hipsCm
        case .chest: return chestCm
        case .upperArm: return upperArmCm
        case .thigh: return thighCm
        case .calf: return calfCm
        case .wrist: return wristCm
        }
    }

    /// A copy with one site changed (same id + date — used for in-place daily updates).
    func setting(_ site: Site, to cm: Double?) -> BodyMeasurement {
        var copy = self
        switch site {
        case .neck: copy.neckCm = cm
        case .waist: copy.waistCm = cm
        case .hips: copy.hipsCm = cm
        case .chest: copy.chestCm = cm
        case .upperArm: copy.upperArmCm = cm
        case .thigh: copy.thighCm = cm
        case .calf: copy.calfCm = cm
        case .wrist: copy.wristCm = cm
        }
        return copy
    }

    /// Compact AI-prompt summary of the logged sites + derived metrics, always in cm for a single
    /// consistent unit. Returns nil when nothing is logged so callers can omit the section entirely.
    func promptSummary(gender: Gender, heightCm: Double) -> String? {
        guard hasAnyValue else { return nil }
        var sites: [String] = []
        func site(_ label: String, _ value: Double?) {
            if let value { sites.append("\(label) \(String(format: "%.1f", value)) cm") }
        }
        site("neck", neckCm)
        site("waist", waistCm)
        site("hips", hipsCm)
        site("chest", chestCm)
        site("upper arm", upperArmCm)
        site("thigh", thighCm)
        site("calf", calfCm)
        site("wrist", wristCm)

        var metrics: [String] = []
        if let whr = waistToHipRatio {
            metrics.append("waist-to-hip \(String(format: "%.2f", whr))")
        }
        if let whtr = waistToHeightRatio(heightCm: heightCm) {
            metrics.append("waist-to-height \(String(format: "%.2f", whtr))")
        }
        if let bf = usNavyBodyFatPercent(gender: gender, heightCm: heightCm) {
            metrics.append("US-Navy body fat ~\(String(format: "%.0f", bf))%")
        }
        if let frame = wristFrame(gender: gender, heightCm: heightCm) {
            metrics.append("frame \(frame.label.lowercased())")
        }

        var summary = sites.joined(separator: ", ")
        if !metrics.isEmpty {
            summary += " | derived: " + metrics.joined(separator: ", ")
        }
        return summary
    }
}
