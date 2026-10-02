import Foundation

/// Typed view of `shared/vitals/vitals_config.json` (bundled as `Services/Vitals/Resources/vitals_config.json`,
/// byte-identical, checked by `scripts/vitals_contract_check.py` and `VitalsVectorTests`). The raw JSON is kept so
/// `device_overrides` can be deep-merged exactly like `effective_config` in `scripts/vitals_reference.py`.
nonisolated struct VitalsConfig: Sendable {
    enum LoadError: Error { case missing(String) }

    struct Signal: Sendable {
        var fs: Double
        var artifactRmsFactor: Double
        var artifactWindowS: Double
        var detrendWindowS: Double
        var hrBandHz: [Double]
        var maxGapMs: Double
        var peakRefractoryS: Double
        var peakWindowS: Double
        var refractoryFraction: Double
        var beatWindowS: Double
        var peakOffset: Double
        var spectrumStepHz: Double
        var welchSegmentS: Double
        var welchOverlap: Double
    }

    struct Grade: Sendable { var id: String; var min: Double }

    struct Quality: Sendable {
        var grades: [Grade]
        var hrAgreementBpm: Double
        var minHrQuality: Double
        var minRespQuality: Double
        var snrDbHigh: Double
        var snrDbLow: Double
        var weights: [String: Double]
    }

    struct Hrv: Sendable {
        var freqGridStepHz: Double
        var freqMinS: Double
        var hfBand: [Double]
        var lfBand: [Double]
        var minAcceptedFraction: Double
        var minBeats: Double
        var minQuality: [String: Double]
        var minS: Double
    }

    struct Ibi: Sendable {
        var maxMs: Double
        var maxRelDeviation: Double
        var medianWindow: Int
        var minMs: Double
        var minTemplateCorr: Double
    }

    struct Respiration: Sendable {
        var agreementPerMin: Double
        var bandHz: [Double]
        var gridStepHz: Double
        var minS: Double
        var resampleHz: Double
    }

    struct Finger: Sendable {
        var maxMaskedFraction: Double
        var maxSaturatedFraction: Double
        var maxSpatialStd: Double
        var minRed: Double
        var minRedGreenRatio: Double
        var stepJumpFraction: Double
    }

    struct Scan: Sendable {
        var extendBelowQuality: Double
        var maxS: Double
        var minS: Double
        var targetS: Double
    }

    struct Research: Sendable {
        var bpCalibrationMaxAgeDays: Double
        var bpCalibrationMaxGapMin: Double
        var bpMaxAbsZ: Double
        var bpFeatures: [String]
        var bpMinCalibrations: Double
        var bpRidgeLambda: Double
        var spo2Channels: [String]
        var spo2MaxRMargin: Double
        var spo2MinCalibrations: Double
        var spo2MinQuality: Double
        var spo2Range: [Double]
    }

    struct Gates: Sendable {
        var lumaMax: Double
        var lumaMin: Double
        var maxMaskedFraction: Double
        var maxMotion: Double
        var maxPitchDeg: Double
        var maxYawDeg: Double
        var maxFaceFraction: Double
        var minFaceFraction: Double
        var minSkinFraction: Double
    }

    struct Face: Sendable {
        var gates: Gates
        var methods: [String]
        var minRoiSnrDb: Double
        var posWindowS: Double
        var rois: [String]
    }

    struct Compare: Sendable {
        var maxHrDiffBpm: Double
        var maxRmssdDiffMs: Double
        var maxSessionGapS: Double
    }

    struct Band: Sendable { var id: String; var min: Double }

    /// `insights_fallback`: which scans may stand in for a missing wearable value in Recovery / Health Age.
    struct InsightsFallback: Sendable {
        var contexts: [String]
        var minQuality: Double
        var mode: String
    }

    struct Indicator: Sendable {
        var bands: [Band]
        var hrWeight: Double
        var hrvWeight: Double
        var minHistory: Double
        var minSpreadHr: Double
        var minSpreadLnRmssd: Double
        var scale: Double
        var windowDays: Double
    }

    struct Metric: Sendable {
        var id: String
        var title: String
        var unit: String
        var classification: String
    }

    /// The JSON this config was built from (after any device override merge).
    let raw: RJ
    let format: String
    let configVersion: Int
    let algoVersion: Int
    let signal: Signal
    let quality: Quality
    let hrv: Hrv
    let ibi: Ibi
    let respiration: Respiration
    let finger: Finger
    let scan: Scan
    let research: Research
    let face: Face
    let compare: Compare
    let baselineMinN: Double
    let baselineWindowsDays: [Double?]
    let indicator: Indicator
    let insightsFallback: InsightsFallback
    let metrics: [Metric]

    init(raw: RJ) throws {
        func num(_ v: RJ, _ path: String) throws -> Double {
            guard let d = v.double else { throw LoadError.missing(path) }
            return d
        }
        func str(_ v: RJ, _ path: String) throws -> String {
            guard let s = v.string else { throw LoadError.missing(path) }
            return s
        }
        func nums(_ v: RJ, _ path: String) throws -> [Double] {
            guard let a = v.array else { throw LoadError.missing(path) }
            return try a.map { try num($0, path) }
        }
        func strs(_ v: RJ, _ path: String) throws -> [String] {
            guard let a = v.array else { throw LoadError.missing(path) }
            return try a.map { try str($0, path) }
        }
        func numMap(_ v: RJ, _ path: String) throws -> [String: Double] {
            guard let o = v.object else { throw LoadError.missing(path) }
            return try o.mapValues { try num($0, path) }
        }
        self.raw = raw
        format = try str(raw["format"], "format")
        configVersion = Int(try num(raw["config_version"], "config_version"))
        algoVersion = Int(try num(raw["algo_version"], "algo_version"))
        let s = raw["signal"]
        signal = Signal(
            fs: try num(s["fs"], "signal.fs"), artifactRmsFactor: try num(s["artifact_rms_factor"], "signal.artifact_rms_factor"),
            artifactWindowS: try num(s["artifact_window_s"], "signal.artifact_window_s"),
            detrendWindowS: try num(s["detrend_window_s"], "signal.detrend_window_s"),
            hrBandHz: try nums(s["hr_band_hz"], "signal.hr_band_hz"), maxGapMs: try num(s["max_gap_ms"], "signal.max_gap_ms"),
            peakRefractoryS: try num(s["peak_refractory_s"], "signal.peak_refractory_s"),
            peakWindowS: try num(s["peak_window_s"], "signal.peak_window_s"),
            refractoryFraction: try num(s["refractory_fraction"], "signal.refractory_fraction"),
            beatWindowS: try num(s["beat_window_s"], "signal.beat_window_s"), peakOffset: try num(s["peak_offset"], "signal.peak_offset"),
            spectrumStepHz: try num(s["spectrum_step_hz"], "signal.spectrum_step_hz"),
            welchSegmentS: try num(s["welch_segment_s"], "signal.welch_segment_s"),
            welchOverlap: try num(s["welch_overlap"], "signal.welch_overlap"))
        let q = raw["quality"]
        quality = Quality(
            grades: try (q["grades"].array ?? []).map { Grade(id: try str($0["id"], "quality.grades.id"), min: try num($0["min"], "quality.grades.min")) },
            hrAgreementBpm: try num(q["hr_agreement_bpm"], "quality.hr_agreement_bpm"),
            minHrQuality: try num(q["min_hr_quality"], "quality.min_hr_quality"),
            minRespQuality: try num(q["min_resp_quality"], "quality.min_resp_quality"),
            snrDbHigh: try num(q["snr_db_high"], "quality.snr_db_high"), snrDbLow: try num(q["snr_db_low"], "quality.snr_db_low"),
            weights: try numMap(q["weights"], "quality.weights"))
        let h = raw["hrv"]
        hrv = Hrv(
            freqGridStepHz: try num(h["freq_grid_step_hz"], "hrv.freq_grid_step_hz"), freqMinS: try num(h["freq_min_s"], "hrv.freq_min_s"),
            hfBand: try nums(h["hf_band"], "hrv.hf_band"), lfBand: try nums(h["lf_band"], "hrv.lf_band"),
            minAcceptedFraction: try num(h["min_accepted_fraction"], "hrv.min_accepted_fraction"),
            minBeats: try num(h["min_beats"], "hrv.min_beats"), minQuality: try numMap(h["min_quality"], "hrv.min_quality"),
            minS: try num(h["min_s"], "hrv.min_s"))
        let i = raw["ibi"]
        ibi = Ibi(maxMs: try num(i["max_ms"], "ibi.max_ms"), maxRelDeviation: try num(i["max_rel_deviation"], "ibi.max_rel_deviation"),
                  medianWindow: Int(try num(i["median_window"], "ibi.median_window")), minMs: try num(i["min_ms"], "ibi.min_ms"),
                  minTemplateCorr: try num(i["min_template_corr"], "ibi.min_template_corr"))
        let r = raw["respiration"]
        respiration = Respiration(
            agreementPerMin: try num(r["agreement_per_min"], "respiration.agreement_per_min"),
            bandHz: try nums(r["band_hz"], "respiration.band_hz"), gridStepHz: try num(r["grid_step_hz"], "respiration.grid_step_hz"),
            minS: try num(r["min_s"], "respiration.min_s"), resampleHz: try num(r["resample_hz"], "respiration.resample_hz"))
        let f = raw["finger"]
        finger = Finger(
            maxMaskedFraction: try num(f["max_masked_fraction"], "finger.max_masked_fraction"),
            maxSaturatedFraction: try num(f["max_saturated_fraction"], "finger.max_saturated_fraction"),
            maxSpatialStd: try num(f["max_spatial_std"], "finger.max_spatial_std"), minRed: try num(f["min_red"], "finger.min_red"),
            minRedGreenRatio: try num(f["min_red_green_ratio"], "finger.min_red_green_ratio"),
            stepJumpFraction: try num(f["step_jump_fraction"], "finger.step_jump_fraction"))
        let sc = raw["scan"]
        scan = Scan(extendBelowQuality: try num(sc["extend_below_quality"], "scan.extend_below_quality"),
                    maxS: try num(sc["max_s"], "scan.max_s"), minS: try num(sc["min_s"], "scan.min_s"),
                    targetS: try num(sc["target_s"], "scan.target_s"))
        let rs = raw["research"]
        research = Research(
            bpCalibrationMaxAgeDays: try num(rs["bp_calibration_max_age_days"], "research.bp_calibration_max_age_days"),
            bpCalibrationMaxGapMin: try num(rs["bp_calibration_max_gap_min"], "research.bp_calibration_max_gap_min"),
            bpMaxAbsZ: try num(rs["bp_max_abs_z"], "research.bp_max_abs_z"), bpFeatures: try strs(rs["bp_features"], "research.bp_features"),
            bpMinCalibrations: try num(rs["bp_min_calibrations"], "research.bp_min_calibrations"),
            bpRidgeLambda: try num(rs["bp_ridge_lambda"], "research.bp_ridge_lambda"),
            spo2Channels: try strs(rs["spo2_channels"], "research.spo2_channels"),
            spo2MaxRMargin: try num(rs["spo2_max_r_margin"], "research.spo2_max_r_margin"),
            spo2MinCalibrations: try num(rs["spo2_min_calibrations"], "research.spo2_min_calibrations"),
            spo2MinQuality: try num(rs["spo2_min_quality"], "research.spo2_min_quality"),
            spo2Range: try nums(rs["spo2_range"], "research.spo2_range"))
        let fc = raw["face"]
        let g = fc["gates"]
        face = Face(
            gates: Gates(lumaMax: try num(g["luma_max"], "face.gates.luma_max"), lumaMin: try num(g["luma_min"], "face.gates.luma_min"),
                         maxMaskedFraction: try num(g["max_masked_fraction"], "face.gates.max_masked_fraction"),
                         maxMotion: try num(g["max_motion"], "face.gates.max_motion"),
                         maxPitchDeg: try num(g["max_pitch_deg"], "face.gates.max_pitch_deg"),
                         maxYawDeg: try num(g["max_yaw_deg"], "face.gates.max_yaw_deg"),
                         maxFaceFraction: try num(g["max_face_fraction"], "face.gates.max_face_fraction"),
                         minFaceFraction: try num(g["min_face_fraction"], "face.gates.min_face_fraction"),
                         minSkinFraction: try num(g["min_skin_fraction"], "face.gates.min_skin_fraction")),
            methods: try strs(fc["methods"], "face.methods"), minRoiSnrDb: try num(fc["min_roi_snr_db"], "face.min_roi_snr_db"),
            posWindowS: try num(fc["pos_window_s"], "face.pos_window_s"), rois: try strs(fc["rois"], "face.rois"))
        let cp = raw["compare"]
        compare = Compare(maxHrDiffBpm: try num(cp["max_hr_diff_bpm"], "compare.max_hr_diff_bpm"),
                          maxRmssdDiffMs: try num(cp["max_rmssd_diff_ms"], "compare.max_rmssd_diff_ms"),
                          maxSessionGapS: try num(cp["max_session_gap_s"], "compare.max_session_gap_s"))
        baselineMinN = try num(raw["baseline"]["min_n"], "baseline.min_n")
        baselineWindowsDays = (raw["baseline"]["windows_days"].array ?? []).map(\.double)
        let ic = raw["indicator"]
        indicator = Indicator(
            bands: try (ic["bands"].array ?? []).map { Band(id: try str($0["id"], "indicator.bands.id"), min: try num($0["min"], "indicator.bands.min")) },
            hrWeight: try num(ic["hr_weight"], "indicator.hr_weight"), hrvWeight: try num(ic["hrv_weight"], "indicator.hrv_weight"),
            minHistory: try num(ic["min_history"], "indicator.min_history"), minSpreadHr: try num(ic["min_spread_hr"], "indicator.min_spread_hr"),
            minSpreadLnRmssd: try num(ic["min_spread_ln_rmssd"], "indicator.min_spread_ln_rmssd"),
            scale: try num(ic["scale"], "indicator.scale"), windowDays: try num(ic["window_days"], "indicator.window_days"))
        let fb = raw["insights_fallback"]
        insightsFallback = InsightsFallback(contexts: try strs(fb["contexts"], "insights_fallback.contexts"),
                                            minQuality: try num(fb["min_quality"], "insights_fallback.min_quality"),
                                            mode: try str(fb["mode"], "insights_fallback.mode"))
        metrics = try (raw["metrics"].array ?? []).map {
            Metric(id: try str($0["id"], "metrics.id"), title: try str($0["title"], "metrics.title"),
                   unit: try str($0["unit"], "metrics.unit"), classification: try str($0["classification"], "metrics.classification"))
        }
    }

    /// `metric_class`: the config entry of a metric (the reference raises KeyError for unknown ids).
    func metric(_ id: String) -> Metric {
        guard let m = metrics.first(where: { $0.id == id }) else { preconditionFailure("vitals_config.json has no metric \(id)") }
        return m
    }

    /// `cfg["signal"]["fs"]` exactly as written in the config (an int in the shipped file).
    var fsJSON: RJ { raw["signal"]["fs"] }

    var deviceOverrides: RJ { raw["device_overrides"] }

    // MARK: Device overrides (§26 calibration hook)

    /// The config with `device_overrides[deviceModel]` deep-merged in (`effective_config` / `merge`). Returns self when
    /// the model has no override.
    func effective(deviceModel: String) -> VitalsConfig {
        let patch = deviceOverrides[deviceModel]
        guard !patch.isNull, let merged = try? VitalsConfig(raw: Self.merge(raw, patch)) else { return self }
        return merged
    }

    /// `merge(base, patch)`: dicts merge key by key, anything else is replaced by the patch.
    static func merge(_ base: RJ, _ patch: RJ) -> RJ {
        guard case .obj(let b) = base, case .obj(let p) = patch else { return patch }
        var out = b
        for (k, v) in p {
            out[k] = b[k].map { merge($0, v) } ?? v
        }
        return .obj(out)
    }

    // MARK: Loading

    /// The bundled config. Missing or malformed resources are a build error, so this traps loudly in development.
    static let shared: VitalsConfig = {
        guard let config = load() else { fatalError("Services/Vitals/Resources/vitals_config.json is missing or invalid") }
        return config
    }()

    static var bundledURL: URL? { Bundle.main.url(forResource: "vitals_config", withExtension: "json") }

    static func load(from url: URL? = bundledURL) -> VitalsConfig? {
        guard let url, let data = try? Data(contentsOf: url) else { return nil }
        return decode(data)
    }

    static func decode(_ data: Data) -> VitalsConfig? {
        guard let raw = try? VitalsJSON.parse(data) else { return nil }
        return try? VitalsConfig(raw: raw)
    }
}
