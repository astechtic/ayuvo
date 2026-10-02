# Ayuvo — Native Camera PPG + Face rPPG + Physiological Health Measurement Engine

You are a senior biomedical signal-processing engineer, computer-vision engineer, ML engineer, and senior native Android/iOS engineer.

I am building **Ayuvo**, a privacy-first health application.

I want to build a native physiological measurement engine that uses the smartphone camera for two different manual measurement modes:

## MODE A — Finger PPG

The user places their fingertip over the rear camera + torch/flash.

## MODE B — Face rPPG

The user looks at the front camera while keeping their face reasonably still.

The goal is to extract physiological information from both approaches while preserving the underlying raw signal and maintaining a clear distinction between:

* measured
* calculated
* estimated
* experimental
* research-only

Do not represent experimental estimates as medically validated measurements.

---

# 1. Platform Architecture

Build two completely independent native implementations.

## Android

Use:

* Kotlin
* Camera2 / CameraX as appropriate
* ML Kit or native computer vision where useful
* native image processing
* native signal processing
* Android hardware/camera APIs

## iOS

Use:

* Swift
* AVFoundation
* Vision where useful
* Core Image / Accelerate / vDSP where appropriate
* native signal processing
* native camera APIs

Do NOT implement the core acquisition or signal-processing engine in Flutter/Dart.

Flutter should only act as an optional UI/integration layer.

Android and iOS should each have their own native implementation.

---

# 2. Two Measurement Modes

Create:

```text
Ayuvo Measurement Engine

├── Finger PPG
│   ├── Rear camera
│   ├── Torch
│   ├── Finger ROI
│   └── Contact PPG
│
└── Face rPPG
    ├── Front camera
    ├── Face detection
    ├── Facial landmarks
    ├── Skin ROI
    └── Remote PPG
```

Do not assume that algorithms optimized for finger PPG will work directly for face rPPG.

Maintain separate signal-acquisition and preprocessing pipelines.

---

# 3. MODE A — Finger PPG

User flow:

```text
Start measurement
        ↓
Rear camera
        ↓
Torch ON
        ↓
Place finger over camera
        ↓
Finger detection
        ↓
ROI detection
        ↓
Raw frames
        ↓
RGB optical signal
        ↓
PPG
        ↓
Signal quality
        ↓
Physiological metrics
```

Target:

30–60 seconds.

Extend the measurement if signal quality is insufficient.

---

# 4. MODE B — Face rPPG

User flow:

```text
Start Face Measurement
        ↓
Front camera
        ↓
Face detection
        ↓
Face positioning guidance
        ↓
Facial landmark detection
        ↓
Skin-region selection
        ↓
Raw camera frames
        ↓
Remote PPG extraction
        ↓
Signal quality assessment
        ↓
Physiological metrics
```

Target:

30–60 seconds initially.

The user should be instructed:

* sit still
* face the camera
* use adequate lighting
* avoid talking
* avoid excessive head movement
* keep the face fully visible
* remove conditions that significantly obscure skin regions where possible

The system should reject measurements with excessive motion or insufficient illumination.

---

# 5. Face ROI Strategy

Do NOT simply average the entire face.

Investigate multiple facial regions:

```text
Forehead
Left cheek
Right cheek
Nose/central facial region
Chin where appropriate
```

Create independent signals:

```text
PPG_forehead
PPG_left_cheek
PPG_right_cheek
PPG_nose
```

Then evaluate:

* signal quality
* periodicity
* consistency
* motion contamination

Combine the best-quality regions.

For example:

```text
             FACE

       ┌───────────────┐
       │   FOREHEAD    │
       │               │
       │ L CHEEK R CHEEK│
       │               │
       │      NOSE     │
       └───────────────┘

             ↓

    Multiple rPPG signals
             ↓
       Quality weighting
             ↓
       Combined rPPG
```

---

# 6. Face Detection

Implement robust face detection.

The engine should determine:

```text
faceDetected
faceCount
facePosition
faceSize
faceAngle
faceMovement
illumination
skinRegionAvailability
```

Only allow measurement when exactly one suitable face is detected.

Reject:

* multiple faces
* face too far away
* face too close
* extreme yaw/pitch
* excessive movement
* poor illumination

---

# 7. Facial Motion Compensation

This is a critical requirement.

Face rPPG is highly sensitive to motion.

Implement:

```text
Face landmarks
        ↓
Head pose estimation
        ↓
ROI tracking
        ↓
Motion estimation
        ↓
Motion artifact suppression
```

Use temporal tracking instead of detecting a completely new ROI on every frame.

Record:

```text
motionScore
headPose
ROI stability
```

and incorporate these into signal quality.

---

# 8. Raw Face Camera Data

Preserve sufficient information for future research.

For each frame capture/store where practical:

```text
timestamp
frame index
resolution
FPS
camera configuration
face ROI
skin ROI
RGB statistics
motion information
```

Do NOT automatically store full-resolution video if it isn't necessary.

Prefer storing extracted physiological signals and required metadata.

Privacy is critical.

Raw facial imagery should remain local by default.

---

# 9. rPPG Extraction

Implement and compare multiple approaches.

At minimum investigate:

### Channel methods

```text
R
G
B
```

### Normalized methods

```text
r = R / (R+G+B)
g = G / (R+G+B)
b = B / (R+G+B)
```

### Chrominance methods

Investigate established chrominance-based rPPG approaches.

### POS

Investigate Plane-Orthogonal-to-Skin approaches.

### CHROM

Investigate CHROM-style approaches.

### ICA/PCA

Investigate:

* PCA
* ICA

where useful.

Do not assume the simplest green-channel approach is always optimal.

Compare methods using signal-quality metrics.

---

# 10. Face rPPG Signal Pipeline

Build:

```text
Camera frames
      ↓
Face detection
      ↓
Landmarks
      ↓
Skin ROI
      ↓
RGB extraction
      ↓
Normalization
      ↓
Motion compensation
      ↓
Detrending
      ↓
Band-pass filtering
      ↓
rPPG extraction
      ↓
Signal quality
      ↓
Pulse detection
```

Preserve:

```text
raw RGB signal
processed rPPG
```

where practical.

---

# 11. Heart Rate

Support both:

```text
Finger PPG HR
Face rPPG HR
```

Output:

```text
heartRateBpm
confidence
signalQuality
source
```

Example:

```json
{
  "heartRateBpm": 68,
  "source": "face_rppg",
  "confidence": 0.91
}
```

Do not merge finger and face measurements without retaining the source.

---

# 12. IBI

Attempt to extract pulse-to-pulse intervals from both:

```text
finger PPG
face rPPG
```

However, recognize that face rPPG generally has greater susceptibility to motion/noise.

Every IBI should have quality information.

```text
ibiMs[]
ibiTimestamp[]
ibiQuality[]
```

Reject artifacts before HRV calculation.

---

# 13. HRV

Calculate HRV only when signal quality and measurement duration are sufficient.

Potential metrics:

### Time domain

* RMSSD
* SDNN
* mean NN
* pNN50 where appropriate

### Frequency domain

Only when the recording duration and signal quality justify it:

* LF
* HF
* LF/HF

Clearly distinguish:

```text
ECG-derived HRV
Finger-PPG-derived HRV
Face-rPPG-derived HRV
```

Do not imply that they are interchangeable.

---

# 14. Respiratory Rate

Investigate respiratory extraction from both:

### Finger PPG

* amplitude modulation
* baseline modulation
* respiratory sinus arrhythmia

### Face rPPG

* pulse-amplitude modulation
* respiratory modulation
* subtle facial/skin variations

Potential pipeline:

```text
PPG/rPPG
   ↓
Respiratory modulation
   ↓
Low-frequency extraction
   ↓
Respiratory rate
```

Output:

```text
respiratoryRate
confidence
source
```

---

# 15. SpO₂ — EXPERIMENTAL

Investigate whether camera-based optical signals can provide an SpO₂ estimate.

Support:

```text
Finger camera signal
```

as the primary experimental approach.

Do not assume face rPPG can provide clinically meaningful SpO₂.

Investigate:

* RGB channel relationships
* AC/DC components
* pulse amplitude
* wavelength/channel characteristics
* calibration models

Output:

```text
spo2Estimate
confidence
status: experimental
```

If confidence is poor:

```text
SpO₂ unavailable
```

Do not fabricate a value.

---

# 16. Blood Pressure — RESEARCH ONLY

Create a separate research module.

Potential inputs:

```text
PPG morphology
pulse width
pulse amplitude
rise time
decay time
dicrotic features if measurable
HR
IBI
HRV
demographics
personal calibration
```

Investigate:

```text
PPG
 ↓
Feature extraction
 ↓
ML model
 ↓
SBP / DBP estimate
```

Require calibration against an actual validated cuff.

Never describe camera-derived BP as directly measured BP.

---

# 17. Face-Based Stress / Recovery

Build a derived physiological model.

Potential inputs:

```text
HR
HRV
RMSSD
SDNN
respiratory rate
pulse variability
signal quality
repeated measurements
personal baseline
```

Optionally investigate facial/rPPG-derived additional features.

But do NOT claim that the camera directly measures psychological stress.

Use language such as:

> Physiological stress indicator

or:

> Recovery indicator

rather than:

> You are stressed.

---

# 18. FaceVital-Style Health Scan

Implement a separate **Face Health Scan** mode inspired by the general concept of apps that use facial scanning for wellness insights.

The current FaceVital App Store description advertises face scanning for:

* heart rate monitoring
* stress monitoring
* blood-pressure-related insights
* historical wellness tracking
* personalized health information

It also states that the app is for general wellness/fitness and is not a medical device.

Ayuvo should implement the underlying engineering as a transparent measurement system rather than copying another application's proprietary implementation.

The Face Health Scan should provide:

```text
Face detected
        ↓
30–60 second scan
        ↓
rPPG
        ↓
Signal quality
        ↓
HR
        ↓
IBI
        ↓
HRV when valid
        ↓
Respiratory rate
        ↓
Experimental indicators
        ↓
Personal baseline
        ↓
Wellness insights
```

---

# 19. Face Scan Output

Example:

```text
FACE HEALTH SCAN

Signal Quality
Excellent

Heart Rate
68 BPM

Heart Rate Variability
44 ms
Confidence: High

Respiratory Rate
15 /min
Confidence: Medium

Physiological Recovery
78/100
Confidence: Medium

SpO₂
Unavailable
Camera measurement confidence insufficient

Blood Pressure
Research estimate unavailable
```

Do not display an experimental result simply to make the screen look complete.

---

# 20. Compare Finger PPG vs Face rPPG

Ayuvo should support comparison.

Example:

```text
Measurement

Finger PPG
HR: 67 BPM
Quality: 96%

Face rPPG
HR: 68 BPM
Quality: 89%

Difference: 1 BPM
```

This can become part of the validation system.

If the two methods disagree significantly:

```text
The measurements are inconsistent.
Please repeat the measurement.
```

Do not automatically select whichever result looks better.

---

# 21. Cross-Validation Architecture

Create a validation mode:

```text
Finger PPG
     │
     ├───────── HR
     │
     ├───────── IBI
     │
     └───────── HRV

Face rPPG
     │
     ├───────── HR
     │
     ├───────── IBI
     │
     └───────── HRV

        ↓

Comparison Engine
        ↓
Agreement / disagreement
        ↓
Quality assessment
```

This will allow Ayuvo to determine how reliable face rPPG is compared with the stronger fingertip configuration.

---

# 22. Personal Baseline

Store repeated measurements.

Example:

```text
Day 1
Face HR: 70
Finger HR: 68

Day 7
Face HR: 67
Finger HR: 66

Day 14
Face HR: 69
Finger HR: 68
```

Calculate:

```text
7-day baseline
14-day baseline
30-day baseline
Long-term baseline
```

Use personal baselines for derived wellness indicators.

---

# 23. Health Measurement Data Model

Create a unified model:

```text
Measurement
 ├── measurementId
 ├── timestamp
 ├── duration
 ├── platform
 ├── device
 ├── source
 │     ├── finger_ppg
 │     └── face_rppg
 │
 ├── rawSignal
 ├── processedSignal
 ├── signalQuality
 │
 ├── heartRate
 ├── ibi
 ├── hrv
 ├── respiratoryRate
 ├── spo2
 ├── bloodPressure
 └── stressRecovery
```

Every derived result must contain:

```text
value
unit
confidence
algorithmVersion
source
status
```

---

# 24. Native Android Architecture

```text
android/
├── camera/
│   ├── CameraController
│   ├── CameraConfiguration
│   ├── FrameProcessor
│   └── TorchController
│
├── finger/
│   ├── FingerDetector
│   ├── FingerRoiExtractor
│   └── FingerPpgExtractor
│
├── face/
│   ├── FaceDetector
│   ├── FaceLandmarkTracker
│   ├── SkinRoiExtractor
│   ├── MotionCompensator
│   └── FaceRppgExtractor
│
├── signal/
│   ├── Filter
│   ├── Detrender
│   ├── PulseDetector
│   ├── SignalQuality
│   └── ArtifactDetector
│
├── metrics/
│   ├── HeartRateCalculator
│   ├── IbiCalculator
│   ├── HrvCalculator
│   ├── RespiratoryRateCalculator
│   ├── Spo2Estimator
│   ├── BloodPressureResearchModel
│   └── StressRecoveryModel
│
└── storage/
    └── MeasurementRepository
```

---

# 25. Native iOS Architecture

```text
ios/
├── Camera/
│   ├── CameraManager
│   ├── CameraConfiguration
│   ├── FrameProcessor
│   └── TorchController
│
├── Finger/
│   ├── FingerDetector
│   ├── FingerRoiExtractor
│   └── FingerPpgExtractor
│
├── Face/
│   ├── FaceDetector
│   ├── FaceLandmarkTracker
│   ├── SkinRoiExtractor
│   ├── MotionCompensator
│   └── FaceRppgExtractor
│
├── Signal/
│   ├── Filter
│   ├── Detrender
│   ├── PulseDetector
│   ├── SignalQuality
│   └── ArtifactDetector
│
├── Metrics/
│   ├── HeartRateCalculator
│   ├── IbiCalculator
│   ├── HrvCalculator
│   ├── RespiratoryRateCalculator
│   ├── Spo2Estimator
│   ├── BloodPressureResearchModel
│   └── StressRecoveryModel
│
└── Storage/
    └── MeasurementRepository
```

---

# 26. Device-Specific Calibration

This is mandatory.

Android devices have huge camera variation.

iPhones also have camera differences between generations.

Detect and store:

```text
device
camera
resolution
FPS
exposure
ISO
white balance
torch
lens
camera position
```

Create a device capability profile.

Do not assume the same algorithm parameters work perfectly across all devices.

---

# 27. Signal Quality System

Every measurement must have quality.

Example:

```text
Signal Quality = 94/100

Finger:
  fingerDetected = true
  motion = low
  illumination = good
  saturation = good

Face:
  faceDetected = true
  faceCount = 1
  headMotion = low
  lighting = good
  ROI stability = high
  rPPG periodicity = high
```

Create a unified quality score but retain the underlying components.

---

# 28. ML Strategy

Do NOT begin with deep learning.

First implement deterministic signal processing.

Then compare:

```text
Classical DSP
vs
Machine Learning
vs
Hybrid DSP + ML
```

For ML investigate:

* feature-based regression
* gradient boosting
* random forest
* lightweight neural networks
* temporal models where justified

Do not train a model without a properly collected reference dataset.

---

# 29. Validation

For HR/IBI/HRV:

Reference:

* ECG
* validated chest strap where appropriate

For respiratory rate:

* respiratory belt or validated reference

For SpO₂:

* validated pulse oximeter

For BP:

* validated cuff

For face rPPG specifically, test:

* different skin tones
* different ages
* different genders
* different lighting
* indoor/outdoor
* glasses
* facial hair
* makeup
* different camera qualities
* different phones
* different distances
* head movement
* different heart rates

Measure:

* MAE
* RMSE
* bias
* limits of agreement
* correlation
* failure rate
* confidence calibration

---

# 30. Privacy

Ayuvo is privacy-first.

Default:

```text
Camera frames → local processing
Raw PPG → local
Face rPPG → local
Health metrics → local
```

Do not upload face video or raw biometric signals to a server unless the user explicitly enables a future feature requiring it.

Prefer storing derived signals rather than identifiable video.

---

# 31. Important Medical/UX Classification

Every output must have a classification.

### MEASURED

Directly obtained from the sensor/signal.

### CALCULATED

Mathematically derived.

### ESTIMATED

Algorithm/model-derived.

### EXPERIMENTAL

Insufficiently validated.

### RESEARCH

Experimental model requiring calibration/reference validation.

Example:

```text
HR
Measured

IBI
Calculated from PPG

HRV
Calculated from IBI

Respiratory Rate
Estimated from PPG/rPPG

SpO₂
Experimental camera estimate

Blood Pressure
Research estimate

Stress
Derived physiological indicator
```

---

# 32. First Development Milestone

Do NOT implement everything simultaneously.

First deliver:

## Milestone 1

Finger PPG:

```text
Camera
 ↓
Torch
 ↓
Finger detection
 ↓
Raw RGB
 ↓
PPG
 ↓
Quality
 ↓
HR
 ↓
IBI
 ↓
HRV
```

## Milestone 2

Face rPPG:

```text
Front camera
 ↓
Face detection
 ↓
Landmarks
 ↓
Skin ROI
 ↓
Motion compensation
 ↓
rPPG
 ↓
Quality
 ↓
HR
```

## Milestone 3

Face:

```text
IBI
HRV
Respiratory rate
```

## Milestone 4

Experimental:

```text
SpO₂
BP
```

## Milestone 5

Derived:

```text
Personal baseline
Stress/recovery
Longitudinal health trends
```

---

# 33. Final Product Vision

Ayuvo should eventually provide two manual measurement experiences:

## Finger Scan

> Put your finger over the camera.

Produces the highest-quality phone-camera optical signal available from the device.

## Face Scan

> Look at the camera and stay still.

Uses facial remote PPG to estimate physiological signals without requiring finger contact.

Both should feed the same longitudinal health engine:

```text
                 AYUVO
                   │
          ┌────────┴────────┐
          ↓                 ↓
     Finger PPG         Face rPPG
          ↓                 ↓
       Raw Signal       Raw Signal
          ↓                 ↓
       DSP/Quality      DSP/Quality
          └────────┬────────┘
                   ↓
            Physiological
               Metrics
                   ↓
          Personal Baseline
                   ↓
           Health Trends
                   ↓
             AI Coach
```

The goal is to create a reusable native **physiological signal acquisition platform**, not merely a heart-rate screen.

Preserve the raw/processed signal and algorithm version so Ayuvo can improve its models in future without requiring users to recollect historical measurements.
