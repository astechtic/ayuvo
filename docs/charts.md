# Charts

Every metric chart in Ayuvo follows this page on both platforms. The logic lives in `scripts/metrics_reference.py` (it wins over this prose), and both ports run `shared/metrics/test-vectors/*.json`:
- Android: `data/metrics/MetricsReference.kt`
- iOS: `Services/Metrics/MetricsReference.swift`

`python3 scripts/metrics_contract_check.py` checks the vectors, and `--write` regenerates their expected values. Bucketing, headline and ranges are in `docs/ui-structure.md`.

## Rules that never bend
- No data means no mark. Empty buckets draw nothing (no zero bars). An empty range shows the empty state.
- Charts never smooth or fill in values that were not recorded.
- Ranges stay calendar-aligned (D, W, M, 6M, Y). The ‹ › arrows never go past today.

## Visual spec
| Part | Spec |
|---|---|
| Plot | Height 200 pt/dp. Y labels on the trailing side, placed exactly on their gridlines. |
| Gridlines | 1 px hairlines, label colour at 8% opacity, one per y tick. A solid baseline at the bottom. |
| Y ticks | `nice_ticks(min, max, 4, include_zero)`: steps of 1 / 2 / 2.5 / 5 × 10ⁿ. The domain grows to whole steps. Bars include 0. Lines and ranges hug the data; a flat series gets ±1. |
| X labels | `x_ticks(range, …)`: centred under their bucket. D shows 0/6/12/18 h (system 12/24 h). W shows every weekday (narrow). M shows days that start a week. 6M shows the first bucket of each month. Y shows every month (narrow). |
| Bars | 60% of the bucket width, top corners rounded 4 pt, domain colour. |
| Lines | Monotone interpolation (no overshoot), 2 pt stroke, a point per value up to 40 values, soft gradient area to the baseline. |
| Range | Min–max capsule (60% width) with the average as a dot. Blood pressure pairs systolic and diastolic. |
| Goal | Dashed rule with a small trailing "Goal" label, only when a goal is set. |
| Motion | ≤ 250 ms reveal when the range or anchor changes. No animation while scrubbing. |

## Selection (Apple Health behaviour)
- A tap or drag selects the nearest bucket. A vertical rule marks it and the other marks fade to 40%.
- The headline above the chart switches to the selected bucket's value and date or time. Clearing the selection restores the range summary.
- The selection stays after the finger lifts. It clears on a tap outside the marks, a range change or an anchor step.
- Haptic tick on each new bucket while dragging.

## Tap a day → Day chart
`drill_target(range, bucket_start_ms, has_data, metric_ranges, time_zone)`: on W and M, tapping a bucket that has data opens range D for that day, when the metric offers D. Otherwise the tap only selects. For sleep, the bucket day is the wake day, so the tap opens that night.

The anchor is kept when the range changes. After drilling in, W shows the week containing that day. The anchor never passes today.

## Sleep
Nights come from the existing nights analysis: one source per wake day, overlaps merged (`HealthSleepAnalysis` on both platforms).

**D: the night that woke up on the anchor day** (`sleep_night_window`)
- **Domain:** bedtime floored to the hour → wake ceiled to the hour, at least 4 h. For example, 22:40 → 06:55 plots 22:00 → 07:00. It never runs midnight to midnight.
- **Ticks:** every hour, or every 2 h when the domain is longer than 8 h.
- **Chart:** a lane hypnogram (Google Health style), drawn from bedtime to the time out of bed (`bedtime_ms` → `wake_ms`, widened to cover every stage row); the hour domain above only feeds the hour marks.
  - **Four lanes**, Awake, REM, Core, Deep from top to bottom. Each lane is a title row, `<stage> · <total>` (the Core lane total includes unstaged asleep), above a full-width pill track in a faint neutral grey.
  - **Segments** fill the full track height in the hypnogram palette (Awake pink, REM pale cyan, Core blue, Deep purple; lighter variants in dark mode). Corners are rounded, except the corner facing a stage change, which is square on the side of the lane being left or entered. A segment narrower than 4 pt/dp still draws at 4 pt/dp.
  - **Transition stems:** at each stage change (gap ≤ 60 s, different lane), a 2 pt/dp vertical bar from the centre of one track to the centre of the other, a vertical gradient between the two stage colours at 50% opacity, drawn behind the segments and across any lanes in between.
  - **Scale and scrolling:** a fixed 1.8 pt/dp per minute, at least the available width. A long night scrolls horizontally; the lane titles stay pinned and the hour marks (small, leading-aligned at each tick) scroll with the plot.
  - **Footer:** the bedtime, the midpoint and the out-of-bed time, with "Time that you went to bed" under the first and "Time out of bed" under the last.
  - **Selection:** a tap selects the segment under the finger (tap again to clear); Android also scrubs with a long press then drag. iOS has no drag gesture on the plot, because SwiftUI lets it block the horizontal ScrollView. The selected stage, its duration and its time span show above the lanes in a row that keeps its height when empty, and the other segments fade to 40%.
  - An in-bed-only night draws empty tracks and shows the empty state text under the header.
- **Headline:** TIME IN BED and TIME ASLEEP side by side, each `8 hr 32 min` with the units in a smaller, secondary style, and the date underneath (Apple's layout). A night with no asleep data shows TIME IN BED only; no asleep time is invented.
- **Below the chart:** the stage list (Awake / REM / Core / Deep: duration and share of asleep), its dots in the hypnogram palette. The Total / Average / Latest badges are hidden on sleep D, where all three would repeat the headline.

**W / M**
- One floating bar per night on a clock axis, from bedtime to wake. Evening is at the top and morning at the bottom.
- The axis is `sleep_clock_offset`: wall-clock minutes from 12:00 on the day before the wake day. 22:30 → 630 and 06:45 → 1125.
- Stage segments are drawn inside the bar at their real times when stages exist; otherwise the bar is the solid asleep colour.
- **Y domain:** earliest bedtime floored to the hour → latest wake ceiled to the hour, at least 4 h, with ticks every 2 h (`sleep_range_series`).
- **Headline:** AVG TIME ASLEEP, plus the average bedtime – wake.

**6M / Y**
- One bar per week or month, from the mean bedtime to the mean wake over the nights in it, in the solid asleep colour. Nights are averaged, never sampled.

**Stage colours**

| Stage | Colour |
|---|---|
| Awake | `#FF6250` (Apple's red-orange, not amber) |
| REM | `#3ACBFF` |
| Core / Light | `#0A84FF` |
| Deep | `#3634A3` (dark mode `#5856D6`, so it stays visible on black) |
| Asleep (no stages) | `#5E5CE6` |
| In bed | `#5E5CE6` at 25% |

## Parity checklist
- The same ticks, labels, selection, drill-down and sleep windows on both platforms, pinned by the vectors `axis_ticks`, `x_ticks`, `drill_down`, `sleep_window`, `sleep_offset` and `sleep_range`.
- The same empty states and the same colours.
