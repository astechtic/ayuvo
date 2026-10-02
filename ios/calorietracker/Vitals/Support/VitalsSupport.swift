import Foundation

/// Scan mode (docs/camera-vitals.md). Raw values are the stored `vital_scans.mode`.
nonisolated enum VitalsMode: String, Sendable, CaseIterable, Identifiable, Hashable {
    case finger = "finger_ppg"
    case face = "face_rppg"

    var id: String { rawValue }

    /// The launch-argument / replay name (`-AyuvoVitalsReplay finger|face`).
    var shortName: String { self == .finger ? "finger" : "face" }

    init?(shortName: String) {
        switch shortName.lowercased() {
        case "finger": self = .finger
        case "face": self = .face
        default: return nil
        }
    }

    var systemImage: String { self == .finger ? "hand.point.up.left.fill" : "face.smiling" }
}

/// Settings → Camera measurements (docs/camera-vitals.md §7.1). Switches only, never a value.
nonisolated enum VitalsSettings {
    /// When off, only results are saved and no signal rows are written. Default on.
    static let keepSignalsKey = "vitalsKeepSignals"
    /// Experimental estimates (SpO₂). Default off.
    static let experimentalKey = "vitalsExperimentalEnabled"
    /// Research estimates (BP). Default off.
    static let researchKey = "vitalsResearchEnabled"
    /// `-AyuvoVitalsReplay finger|face`: run the flow on synthetic frames (simulator, UI tests).
    static let replayKey = "AyuvoVitalsReplay"

    static func keepSignals(_ defaults: UserDefaults = .standard) -> Bool {
        defaults.object(forKey: keepSignalsKey) as? Bool ?? true
    }

    static func experimentalEnabled(_ defaults: UserDefaults = .standard) -> Bool {
        defaults.object(forKey: experimentalKey) as? Bool ?? false
    }

    static func researchEnabled(_ defaults: UserDefaults = .standard) -> Bool {
        defaults.object(forKey: researchKey) as? Bool ?? false
    }

    /// The replay source is used when the launch argument is set (debug builds and the simulator only) or when the
    /// device has no camera for the mode (the simulator).
    static func replayRequested(_ defaults: UserDefaults = .standard) -> Bool {
        #if DEBUG || targetEnvironment(simulator)
        return defaults.string(forKey: replayKey).flatMap(VitalsMode.init(shortName:)) != nil
        #else
        return false
        #endif
    }
}

/// Display text of the vitals contract through `ContractText` (`vitals.*` keys), English from the config as fallback.
nonisolated enum VitalsText {
    private static var raw: RJ { VitalsConfig.shared.raw }

    static func guidance(_ key: String) -> String {
        ContractText.text("vitals.guidance.\(key)", raw["guidance"][key].string ?? key)
    }

    static func reason(_ key: String) -> String {
        ContractText.text("vitals.reasons.\(key)", raw["reasons"][key].string ?? key)
    }

    static func metricTitle(_ id: String) -> String {
        let english = VitalsConfig.shared.metrics.first { $0.id == id }?.title ?? id
        return ContractText.text("vitals.metrics.\(id).title", english)
    }

    static func classification(_ id: String) -> String {
        ContractText.text("vitals.classifications.\(id).label", raw["classifications"][id]["label"].string ?? id)
    }

    static func classificationAbout(_ id: String) -> String {
        ContractText.text("vitals.classifications.\(id).about", raw["classifications"][id]["about"].string ?? "")
    }

    static func grade(_ id: String) -> String {
        let english = (raw["quality"]["grades"].array ?? []).first { $0["id"].string == id }?["label"].string ?? id
        return ContractText.text("vitals.quality.grades.\(id).label", english)
    }

    static func band(_ id: String) -> String {
        let english = (raw["indicator"]["bands"].array ?? []).first { $0["id"].string == id }?["label"].string ?? id
        return ContractText.text("vitals.indicator.bands.\(id).label", english)
    }

    static var disclaimer: String {
        ContractText.text("vitals.disclaimer", raw["disclaimer"].string ?? "")
    }
}

/// Device identity for `vital_scans.device_model` and `vital_device_profiles`.
nonisolated enum VitalsDevice {
    /// `utsname` machine (e.g. `iPhone15,2`); on the simulator, the simulated model.
    static var model: String {
        if let simulated = ProcessInfo.processInfo.environment["SIMULATOR_MODEL_IDENTIFIER"], !simulated.isEmpty {
            return simulated
        }
        var info = utsname()
        uname(&info)
        let machine = withUnsafeBytes(of: &info.machine) { buffer in
            String(decoding: buffer.prefix { $0 != 0 }, as: UTF8.self)
        }
        return machine.isEmpty ? "unknown" : machine
    }
}
