# Camera vitals: finger PPG and face rPPG

Ayuvo can take a manual pulse measurement with the phone camera in two ways:

- **Finger scan.** The fingertip covers the rear camera and the torch. This is contact photoplethysmography (PPG), and it gives the strongest optical signal a phone camera can produce.
- **Face scan.** The person looks at the front camera and keeps still. This is remote PPG (rPPG): small colour changes of facial skin, measured from several skin regions.

Both modes feed one engine. It produces heart rate, pulse intervals (IBI), HRV and respiratory rate, plus experimental and research estimates that stay off unless the user enables them. Each value carries:

- a classification (measured, calculated, estimated, experimental or research),
- a confidence,
- a status (valid or unavailable, with a reason),
- the algorithm version.

When the signal does not support a value, the value is **unavailable**. No number is shown just to make the screen look complete.

Camera measurements are for general wellness. They are not a medical measurement or diagnosis.

## 1. Rules

1. The DSP engine is the same on iOS and Android. `scripts/vitals_reference.py` is the reference, and both ports must reproduce `shared/vitals/test-vectors` exactly.
2. Acquisition is native:
   - iOS: AVFoundation, Vision and Accelerate.
   - Android: CameraX with Camera2 interop, and ML Kit face detection.
3. Platforms hand the engine per-frame **statistics**, never images. No video and no face image is stored or exported.
4. Scan results are kept in Ayuvo only. They are **never written** to Apple Health or Health Connect, and they are not stored in `health_samples`, so they never show up as wearable data.
5. Finger and face results always keep their source. They are never merged. Finger PPG HRV, face rPPG HRV and ECG HRV are separate kinds of measurement.
6. Raw per-frame statistics and the processed signal are stored with the algorithm version, so old scans can be reprocessed when the algorithm improves.

## 2. Contract

| File | Role |
|---|---|
| `shared/vitals/vitals_config.json` | Every threshold, band, weight and text key. Nothing is hard-coded in the ports. |
| `scripts/vitals_reference.py` | Pure-Python reference implementation. If the prose and the reference disagree, the reference wins. |
| `shared/vitals/test-vectors/<function>.json` | One file per function in `FUNCTIONS`. The format is `ayuvo-vitals-vectors`. |
| `scripts/vitals_contract_check.py [--write]` | Runs the config lint, vector replay, coverage tags, portability lint, platform copies and the generated doc block. |
| `scripts/vitals_eval.py` | Offline validation metrics (§8) on an exported validation dataset. |

Platform copies, which must be byte-identical:
- `android/app/src/main/assets/vitals/vitals_config.json`
- `ios/calorietracker/Services/Vitals/Resources/vitals_config.json`

Display text (metric titles, reasons, guidance, classification labels, disclaimer) is translated through `scripts/l10n/l10n_contracts.py` under the `vitals.` key prefix.

### 2.1 Encodings

- **Finger frame:** `[t_ms, r, g, b, r_std, sat_frac]`.
  - `r`, `g`, `b`: mean RGB (0–255) of the central fingertip ROI.
  - `r_std`: spatial SD of red inside the ROI.
  - `sat_frac`: fraction of red pixels at 250 or above.
- **Face frames:** parallel arrays `t_ms`, `motion`, `yaw`, `pitch`, `luma`, `face_count` and `face_fraction`, plus `rois.<name>` = `[[r, g, b, skin_frac], ...]` for `forehead`, `left_cheek`, `right_cheek`, `nose` and `chin`.
  - `motion` is the mean landmark displacement since the previous frame, divided by the inter-ocular distance.
- **Synthetic scans:** `synth_finger` and `synth_face` are deterministic generators that use a 64-bit LCG. Vectors and the in-app replay source use them, so a full 60 s scan fits in a few hundred bytes of vector input.

### 2.2 Portability

- Half-up `round_to`.
- Written-out loops: no built-in `sum`, no `statistics`, no numpy.
- Fixed iteration counts: Jacobi uses 12 sweeps and FastICA uses 60 iterations, so every port follows the same path.
- Ties break by config order.

## 3. Pipeline

```
camera frames ──► per-frame stats (ROI RGB, motion, pose, exposure)      [platform]
                         │
                         ▼
 resample to 30 Hz ─► gate mask ─► gap fill ─► normalise (x / 1.5 s MA − 1)
                         │
           finger: invert R and G, keep the higher-SNR channel
           face:   per ROI, green · normalised · CHROM · POS · PCA · ICA ─► method with the best median SNR
                   ─► SNR-weighted fusion of the ROIs at or above min_roi_snr_db
                         │
 band-pass 0.7–3.5 Hz (zero-phase Butterworth) ─► amplitude artifact mask (2nd pass, re-fill)
                         │
 Welch spectrum (HR, SNR) · Elgendi peaks (sub-sample) · template correlation
                         │
 IBI cleaning (range, local median ±20 %, template ≥ 0.8, masked beats) ─► per-IBI quality
                         │
 HR · IBI · HRV (time domain; Lomb-Scargle LF/HF ≥ 120 s) · respiration (RIAV/RIFV/RIIV fusion)
                         │
 quality score (0–100, components kept) ─► metric envelopes ─► [SpO₂ exp] [BP research] [indicators]
```

### 3.1 Quality

The score is `100 × Σ weight × component`. Every component is kept in `quality_json`:

| Component | Meaning |
|---|---|
| `snr` | Spectral SNR (de Haan), mapped linearly from −2 dB to 8 dB |
| `template` | Mean beat-template correlation |
| `ibi` | Accepted IBI fraction |
| `agreement` | Agreement between the spectral and beat-to-beat HR |
| `motion` | 1 − masked fraction / allowed |
| `signal` | Fraction of frames passing the finger or face gates |

Grades are poor below 50, fair from 50, good from 70 and excellent from 85.

A metric is valid only above its own gate:
- **HR:** quality at least 45, *and* the spectral and beat-to-beat HR within 6 bpm. A disagreement makes HR unavailable; the engine never picks the more plausible number.
- **HRV:** at least 55 s, 30 clean beats and 80 % accepted, plus quality of at least 65 (finger) or 75 (face).
- **Respiration:** at least 30 s, quality at least 60, and the modulation estimates agreeing within 4 /min.

### 3.2 Live control

- The scan runs to `target_s` (60 s).
- Past that point it extends while quality stays below 70, up to `max_s` (90 s).
- The live screen shows the guidance key of the first failing gate.

### 3.3 Face gates

Measurement needs:
- exactly one face,
- face width between 20 % and 75 % of the frame,
- |yaw| and |pitch| at most 20°,
- luminance between 40 and 235,
- motion at most 0.04,
- skin fraction at least 0.5.

Frames that fail a gate are masked. A scan with more than 30 % of samples masked is rejected, with reason `motion`, `lighting` or `no_face` depending on which gate failed most often.

ROIs come from the landmarks, with temporal tracking:
- iOS: `VNTrackObjectRequest` between full landmark detections.
- Android: ML Kit tracking IDs plus EMA smoothing of the ROI polygons.

## 4. Experimental and research estimates

- **SpO₂ (experimental, finger only).**
  - Method: ratio of ratios between the normalised red and green pulse amplitudes.
  - It is shown only when *Experimental estimates* is on, at least 3 personal calibrations exist (a scan paired with a pulse oximeter reading on the same device model), and the ratio lies inside the calibrated range.
  - Healthy readings cluster in a narrow band, so a calibration rarely covers low values. Values outside the calibration are unavailable.
  - Face scans never estimate SpO₂.
- **Blood pressure (research, finger only).**
  - Method: a per-user ridge regression from pulse morphology to cuff readings. The morphology features are rise time, decay time, width at 50 % and 25 %, and HR.
  - It needs at least 3 cuff calibrations, each taken within 5 minutes of a finger scan and no older than 30 days.
  - A feature more than 3 SD outside the calibration set makes the value unavailable.
  - It is labelled "Research estimate — not a blood pressure measurement" and is never used by insights or the Coach.

## 5. Baselines and indicators

- **Baselines:** 7, 14 and 30 days plus long-term. Each is a median and MAD over valid scans of the **same mode**, and needs at least 3 scans.
- **Recovery indicator and physiological stress indicator:**
  - Inputs: robust z-scores of ln RMSSD and HR against at least 5 earlier same-mode scans from the last 30 days.
  - Formula: `50 + 15 × (0.6·z_lnRMSSD − 0.4·z_HR)`, clamped to 0–100. The stress indicator is 100 minus that.
  - Copy describes these as indicators relative to the person's own usual values. It never says that the camera measures stress.

## 6. Comparison and validation mode

- **Compare:** a finger scan followed by a face scan within 3 minutes, sharing a `session_id`.
  - The result shows both values and their difference.
  - If HR differs by more than 5 bpm, or RMSSD by more than 15 ms, the screen says the measurements are inconsistent and asks for a repeat.
  - Neither result is preferred.
- **Reference readings:** the user can attach a chest strap or ECG HR, an oximeter SpO₂ or a cuff BP to any scan. The validation screen reports `validation_stats` (MAE, RMSE, bias, limits of agreement, r, failure rate, MAE by confidence) per mode, and can export the paired dataset for `scripts/vitals_eval.py`.

## 7. Storage, export and integration

- **Storage:** health DB schema v4 (`shared/health/schema.sql`) adds `vital_scans`, `vital_scan_signals` (float32 little-endian, deflate), `vital_calibrations` and `vital_device_profiles`.
- **Export:** scans, signals and calibrations are included in the health-data export and in portable data under `vitals/`.
- **Device profile:** each scan records the camera configuration (lens, resolution, achieved fps, exposure, ISO, white balance, torch). `device_overrides` in the config is the per-model calibration hook (§26 of PPG.md), and it starts empty.
- **Insights:** on a day with no wearable resting HR, HRV or respiratory rate, a **finger** scan taken at rest with quality of at least 70 is used as a fallback and flagged `scan_fallback`. HRV uses SDNN on iOS and RMSSD on Android, matching `hrv_kind`. Face scans are never used.

## 7.1 App integration (same on both platforms)

**Entry points**
- Summary "+" menu, in a **Measure** section: *Finger scan*, *Face scan* and *Compare finger & face*.
- A Browse catalog item, **Camera measurements**, opens the Vitals home screen.
- Metric detail for `heart_rate`, `resting_heart_rate`, HRV (`hrv_sdnn` / `hrv_rmssd`) and `respiratory_rate` has a *Measure with camera* action that starts a finger scan.

**Vitals home (Camera measurements)**
- Start buttons for the three flows.
- The latest scan card per mode.
- A trend chart of HR per mode, using the existing chart components.
- Baselines: HR and RMSSD medians over 7, 14 and 30 days and all time, per mode.
- History grouped by day: mode icon, time, HR and quality grade. Tapping a scan opens its detail.
- Links to *Validation* and *Calibration*. Calibration appears only when the experimental or research toggle is on.
- The disclaimer.

**Scan flow** (one flow, `mode` = finger | face, optional `session_id`)
1. **Intro.**
   - Finger instructions: cover the rear camera and flash with a fingertip, press lightly, rest the hand, stay still for about a minute.
   - Face instructions: sit still, face the camera, use even lighting, avoid talking, keep the whole face in the oval, and remove anything that hides the cheeks or forehead where possible.
   - Context picker: Resting (the default), After activity, Other.
2. **Camera permission.** Uses the existing patterns. If it is denied, explain and offer a button that opens Settings.
3. **Live.**
   - Finger mode shows no preview: the torch is on, with a live waveform and a quality chip.
   - Face mode shows the front preview with an oval guide, but no frames are kept.
   - The guidance text is the first failing gate (`guidance.*` keys).
   - The clock starts once the gates have passed for 2 s, and pauses while they fail for more than 3 s.
   - At `target_s` the engine analyses the buffered frames and `scan_control` decides between finish and extend. While extending it re-checks every 5 s, up to `max_s`.
   - Cancel stops the scan. The screen stays awake.
4. **Analysing.** The engine runs off the main thread.
5. **Results** (PPG.md §19 layout):
   - Signal quality: grade, score, and the components (expandable).
   - Then the metrics: HR, HRV (RMSSD, SDNN), mean pulse interval, respiratory rate, Recovery and Physiological stress indicators, SpO₂, and blood pressure.
   - Each row shows the value and unit (or "Unavailable" with its reason text), a classification badge and a confidence label.
   - A processed-waveform chart with beat markers.
   - Save, Discard, and *Add reference reading*.
   - In the compare flow: *Next: face scan*.

**Compare.** The face scan starts with the finger scan's `session_id`. If more than `compare.max_session_gap_s` passes, the two are not linked and the user is told so. The compare screen shows both values, the differences and the `compare` status text, and never prefers one result.

**Reference reading sheet** (available from results and from scan detail):
- Fields: HR (bpm), RMSSD (ms), respiratory rate (/min), SpO₂ (%), BP systolic/diastolic, and a device name. They are stored in `reference_json`.
- On a finger scan with an SpO₂ `ratio` and *Experimental estimates* on, an SpO₂ value adds a **calibration** (`vital_calibrations` kind `spo2`, features `{"ratio": r}`, keyed by device model).
- On a finger scan with BP `features` and *Research estimates* on, a BP value adds a **calibration** (kind `bp`, with the reference `scan_gap_min`).

**Validation screen.**
- `validation_stats` per mode × metric over scans that have a reference.
- *Export validation dataset* writes the `ayuvo-vitals-validation` file for `scripts/vitals_eval.py` and opens the share sheet.

**Settings → Camera measurements** (keys roam through portable data):

| Key | Default | Meaning |
|---|---|---|
| `vitalsKeepSignals` | true | When off, only results are saved and no signal rows are written. |
| `vitalsExperimentalEnabled` | false | Experimental estimates (SpO₂). |
| `vitalsResearchEnabled` | false | Research estimates (BP). |

A *Delete all camera scans* action asks for confirmation first.

**Saved record** (`vital_scans`):
- `results_json` is the engine output without `quality` and `signals`, plus `"indicators": {"recovery_indicator": envelope, "stress_indicator": envelope}`. The indicators come from `scan_indicator` against earlier valid same-mode scans, and are unavailable with a reason otherwise.
- `quality_json` is the engine `quality`.
- `camera_json` holds `{position, lens, width, height, target_fps, achieved_fps, exposure_ms, iso, white_balance_locked, torch}`.
- `platform` is `ios` or `android`.
- `device_model` is the `utsname` machine on iOS (for example `iPhone15,2`) and `Build.MANUFACTURER + " " + Build.MODEL` on Android.

**Signals** (`vital_scan_signals`, only when `vitalsKeepSignals` is on):

| Kind | Content |
|---|---|
| `frame_stats` | Finger columns `t_ms,r,g,b,r_std,sat_frac`. Face columns `t_ms,motion,yaw,pitch,luma,face_count,face_fraction` then `<roi>_r,<roi>_g,<roi>_b,<roi>_skin` per ROI in config order. `t_ms` is relative to the first frame. |
| `processed` | Column `x` at 30 Hz. |
| `mask` | Column `m`, 0/1. |
| `beats` | Column `t_ms`. |

`frame_stats` reproduces the engine input exactly, to float32 precision, so old scans can be reprocessed.

**Replay source.** Debug builds and UI tests can run the whole flow without a camera from `synth_finger` / `synth_face` frames:
- iOS: launch argument `-AyuvoVitalsReplay finger|face`.
- Android: intent extra or debug setting `vitals_replay`.

The simulator and the emulator use this source.

**Acquisition**

| Concern | iOS | Android |
|---|---|---|
| Session | `AVCaptureSession` with the 640×480 preset, `AVCaptureVideoDataOutput` producing BGRA, late frames discarded, on its own serial queue | CameraX `ImageAnalysis` with YUV_420_888 at about 640×480 and keep-only-latest; Camera2 interop sets `CONTROL_AE_TARGET_FPS_RANGE` to [30,30] |
| Frame rate | 30 fps through `activeVideoMin/MaxFrameDuration` | 30 fps through the AE target range |
| Torch (finger) | `setTorchModeOn(level:)` | `enableTorch(true)` |
| Exposure lock | After a 2 s settle (finger), or 2 s after the face gates first pass: lock exposure and white balance | `CONTROL_AE_LOCK` / `CONTROL_AWB_LOCK` set through `Camera2CameraControl` at the same moments |
| Exposure metadata | From the device | A session capture callback records exposure, ISO and frame duration |
| Timestamps | Sample-buffer presentation time | `ImageInfo.timestamp` |
| Face detection | Vision `VNDetectFaceLandmarksRequest` with `VNTrackObjectRequest` between full detections | ML Kit face detection, bundled, `PERFORMANCE_MODE_FAST`, `CONTOUR_MODE_ALL`, tracking enabled |
| Head pose | Yaw and pitch from the face observation | Euler Y and X |

Common to both platforms:
- **Finger ROI:** the central 50 % of the frame. Mean R/G/B is taken on a subsampled grid. Saturated pixels are those with red at 250 or above.
- **Face ROIs:** polygons for forehead (above the eyebrows), cheeks (between the lower eyelid, the side of the nose and the mouth corner), nose and chin, built from the landmarks or contours. The polygon points are smoothed with an EMA (α 0.3). Inside each polygon a YCbCr skin mask is applied (Cb 77–127, Cr 133–173), and the ROI value is the mean RGB of the skin pixels.
- **Face motion:** the mean landmark displacement divided by the inter-ocular distance.
- **Luma:** the mean Y over the face box.

## 7.2 Export / restore (section `camera_vitals` of Export All Data)

| Item | Value |
|---|---|
| Section id | `camera_vitals` |
| Manifest `format` | `ayuvo-camera-vitals`, `format_version` 1 |
| Entries | `camera-vitals/scans.ndjson`, `camera-vitals/signals.ndjson`, `camera-vitals/calibrations.ndjson`, `camera-vitals/device_profiles.ndjson` |
| Manifest counts | `scans`, `signals`, `calibrations`, `device_profiles` |
| Import order | After `health_data`; imported from the same or the other platform (scans are not in any app backup) |

**Rows.** Each scan is one JSON line. Field names are the column names, with these changes:
- The `*_json` columns are embedded as objects named `camera`, `quality`, `results` and `reference`.
- Times are ISO-8601 UTC with milliseconds: `start`, `end`, `updated` (calibrations: `t`, `updated`).

Signals are `{scan_id, kind, sample_rate, encoding, meta, data_base64}`, where `data_base64` holds the raw-deflate float32 LE blob unchanged. Deleted scans and calibrations are not exported. When `vitalsKeepSignals` is off, `signals.ndjson` is empty.

**Merge.**
- Insert by `id`. An id that already exists locally, live or tombstoned, is skipped together with its signals.
- Device profiles are merged by key, and the newer `updated` wins.
- Nothing is written to Health.

**Fixture.** `shared/vitals/fixtures/camera-vitals-sample/` holds 2 scans (one finger scan with four signals, one rejected face scan), 1 SpO₂ calibration and 1 device profile. Both platforms must:
- import it,
- reproduce the stored HR by re-analysing the decoded `frame_stats` (`manifest.json` → `expected`),
- round-trip export → import into an empty store.

**Preferences.** These keys travel in the portable `preferences` (see docs/portable-data.md):

| Portable key | App setting |
|---|---|
| `vitals_keep_signals` | `vitalsKeepSignals` |
| `vitals_experimental_enabled` | `vitalsExperimentalEnabled` |
| `vitals_research_enabled` | `vitalsResearchEnabled` |

**Validation dataset.** The *Export validation dataset* file is `ayuvo-vitals-validation.json` (format in `scripts/vitals_eval.py`). It contains only scans that have a reference.

## 8. Validation protocol (PPG.md §29)

Accuracy can only be established on real devices against reference instruments:
- **HR, IBI and HRV:** ECG or a validated chest strap.
- **Respiration:** a respiratory belt.
- **SpO₂:** a validated pulse oximeter.
- **BP:** a validated cuff.

Face rPPG must be tested across skin tones, ages, genders, lighting, indoor and outdoor settings, glasses, facial hair, makeup, distances, head movement, heart-rate ranges and phone models. Report MAE, RMSE, bias, limits of agreement, correlation, failure rate and confidence calibration with `scripts/vitals_eval.py`.

No ML model is trained until such a reference dataset exists. Classical DSP comes first, and DSP vs ML vs hybrid is compared on that dataset.

## 9. Generated reference

<!-- BEGIN GENERATED VITALS -->

| Metric | Unit | Classification | Finger | Face |
|---|---|---|---|---|
| Heart rate | bpm | Measured | yes | yes |
| Mean pulse interval | ms | Calculated | yes | yes |
| HRV (RMSSD) | ms | Calculated | yes | yes |
| HRV (SDNN) | ms | Calculated | yes | yes |
| pNN50 | % | Calculated | yes | yes |
| LF/HF ratio | ratio | Experimental | yes | yes |
| Respiratory rate | /min | Estimated | yes | yes |
| SpO₂ | % | Experimental | yes | no |
| Blood pressure | mmHg | Research | yes | no |
| Recovery indicator | score | Estimated | yes | yes |
| Physiological stress indicator | score | Estimated | yes | yes |

| Reason key | Text |
|---|---|
| `duration_short` | The recording was too short for this value. |
| `experimental_off` | Experimental estimates are turned off in Settings. |
| `face_not_supported` | Face scans can't estimate this value. |
| `few_beats` | Not enough clean pulse beats were detected. |
| `lighting` | The lighting was not suitable. |
| `low_quality` | Signal quality was too low. |
| `motion` | There was too much movement. |
| `needs_calibration` | Needs your own calibration against a validated device first. |
| `no_face` | A single face was not detected for long enough. |
| `no_finger` | The fingertip did not cover the camera for long enough. |
| `outside_calibration` | The signal is outside the range you calibrated. |
| `pressure` | The fingertip pressed too hard, so the camera was overexposed. |
| `research_off` | Research estimates are turned off in Settings. |
| `resp_disagree` | The breathing signals did not agree. |
| `short_history` | Needs at least 5 earlier scans of the same type. |

Sources:

- **chrom**: de Haan G, Jeanne V. Robust pulse rate from chrominance-based rPPG. IEEE Trans Biomed Eng 2013;60:2878-2886.
- **elgendi**: Elgendi M et al. Systolic peak detection in acceleration photoplethysmograms measured from emergency responders in tropical conditions. PLoS One 2013;8:e76585.
- **hrv**: Task Force of the ESC and NASPE. Heart rate variability: standards of measurement. Circulation 1996;93:1043-1065; Shaffer F, Ginsberg JP. Front Public Health 2017;5:258 (ultra-short-term HRV).
- **ica**: Poh MZ, McDuff DJ, Picard RW. Non-contact, automated cardiac pulse measurements using video imaging and blind source separation. Opt Express 2010;18:10762-10774.
- **lomb**: Lomb NR 1976; Scargle JD 1982; Clifford GD, Tarassenko L. Quantifying errors in spectral estimates of HRV due to beat replacement and resampling. IEEE Trans Biomed Eng 2005;52:630-638.
- **pca**: Lewandowska M et al. Measuring pulse rate with a webcam: a non-contact method for evaluating cardiac activity. FedCSIS 2011:405-410.
- **pos**: Wang W, den Brinker AC, Stuijk S, de Haan G. Algorithmic principles of remote PPG. IEEE Trans Biomed Eng 2017;64:1479-1491.
- **resp**: Karlen W et al. Multiparameter respiratory rate estimation from the photoplethysmogram. IEEE Trans Biomed Eng 2013;60:1946-1953 (RIIV/RIAV/RIFV smart fusion).
- **spo2**: Ratio-of-ratios pulse oximetry principle; smartphone camera feasibility: Scully CG et al. IEEE Trans Biomed Eng 2012;59:303-306. Camera SpO2 is not clinically validated.
- **sqi**: Elgendi M. Optimal signal quality index for photoplethysmogram signals. Bioengineering 2016;3:21; Orphanidou C et al. IEEE J Biomed Health Inform 2015;19:832-838 (template matching).

<!-- END GENERATED VITALS -->
