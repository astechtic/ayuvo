# Workouts: GPS outdoor workouts, heart-rate windows and cardio fitness

## 1. Purpose
Workouts in Ayuvo need three things they didn't have before:

- **Real start and end times.** Strength sessions used to save with 0 duration.
- **GPS outdoor workouts.** Walk, run, cycle and hike are recorded with a route, per-km splits and elevation.
- **Better cardio-fitness estimates.** GPS sessions give a VO₂max estimate and one-minute heart-rate recovery, which improve on the resting-heart-rate formula in docs/derived-metrics.md.

Everything is calculated on the device.

- **Workouts:** real sessions are written to Apple Health (`HKWorkout` with an `HKWorkoutRoute`) and Health Connect (`ExerciseSessionRecord` with an `ExerciseRoute`), because they are real recordings.
- **Estimates:** VO₂max, HRR1 and zones are never written to either platform.

## 2. Contract
| File | Role |
|---|---|
| `shared/workout/workout_config.json` | Sports (speed gates, auto-pause speed, HealthKit / Health Connect activity type, MET item), thresholds and sources. |
| `scripts/workout_reference.py` | The reference implementation: `gps_track`, `hr_workout`, `hr_recovery`, `vo2max_gps`, `cooper`, `workout_windows`. |
| `scripts/workout_contract_check.py [--write]` | Config lint, vector runner with coverage, portability lint, unit tests and platform copies. |
| `shared/workout/test-vectors/*.json` | One file per function, format `ayuvo-workout-vectors`. |

Platform copies must stay byte-identical:

- `android/app/src/main/assets/workout/workout_config.json`
- `ios/calorietracker/Services/Workout/Resources/workout_config.json`

**Ports:**
- iOS: `ios/calorietracker/Services/Workout/`
- Android: `android/app/src/main/java/com/ayuvo/health/data/workout/`

**Vector tests:** `WorkoutVectorTests` on both platforms.

## 3. Methods
- **GPS track.**
  - A fix is dropped when its horizontal accuracy is worse than 20 m, or when the speed from the last kept fix is above the sport's maximum (a GPS jump).
  - A segment counts only if neither of its ends is inside a manual pause and it is at least the sport's auto-pause speed.
  - Distance is haversine on the WGS-84 mean radius.
  - Splits are interpolated inside the segment that crosses each kilometre.
  - Elevation gain and loss use a 3 m hysteresis. Barometric relative altitude is used when the device has it, otherwise GPS altitude.
- **Heart rate in a workout.**
  - Each sample covers the time until the next one, capped at 120 s.
  - Zones use % heart-rate reserve, or % HRmax when no resting heart rate is known (ACSM).
  - Training load is Banister TRIMP.
  - Energy uses Keytel 2005, but only when heart rate covers at least 70% of the workout. Otherwise the MET estimator is used.
- **HRR1.** The highest heart rate in the last 60 s before End minus the heart rate 60 s after End. Below 12 bpm is flagged, following Cole 1999. Confidence is "low" for per-minute bands and "medium" for dense watch samples.
- **VO₂max from GPS.**
  - The ACSM walking or running equation gives the oxygen cost of each steady segment: at least 6 minutes, grade under 2%, and %HRR between 0.4 and 0.9.
  - Each segment gives VO₂max = 3.5 + (VO₂ − 3.5) ÷ %HRR, because %HRR ≈ %VO₂R (Swain & Leutholtz 1997).
  - The estimate is the duration-weighted mean of those segments.
  - The Cooper 12-minute test gives (distance − 504.9) ÷ 44.73.
- **Strength windows from heart rate.**
  - A minute is active when heart rate is at or above min(100 bpm, resting HR + 50% of heart-rate reserve).
  - Active minutes join into one window across idle gaps of up to 5 minutes.
  - A window must be at least 15 minutes long.
  - The window is only a suggestion; the person confirms or edits it.

## 4. Recording
**iOS**
- Location: `CLLocationManager` with background location updates and the location indicator, plus `CLBackgroundActivitySession`. Only When-In-Use permission is requested.
- Altitude: `CMAltimeter`.
- Saving: `HKWorkoutBuilder` and `HKWorkoutRouteBuilder`.
- Live Activity: `WorkoutActivityAttributes`, with Pause, Resume, Lap and End as `LiveActivityIntent`s.

**Android**
- Location: a foreground service of type `location`, using `LocationManagerCompat` (the fused provider on API 31+, GPS otherwise). It starts while the app is in the foreground, so no background-location permission is needed.
- Altitude: the barometer when the phone has one.
- Saving: `ExerciseSessionRecord` with `ExerciseRoute`.
- Notification: an ongoing notification with Pause, Resume, Lap and End actions (a promoted ongoing notification on API 36).

**Map**
- iOS: MapKit.
- Android: osmdroid with OpenStreetMap attribution. There is no API key.

**Apple Watch**
- The watch runs `HKWorkoutSession` with `HKLiveWorkoutBuilder` and is mirrored to the iPhone.
- Only one device saves a workout. While a watch session is active, the iPhone recorder does not save its own `HKWorkout`.

**Crash recovery:** the track in progress is kept in a file, so the workout can be resumed or saved after a crash.
