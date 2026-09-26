# Home-screen widgets

Ayuvo ships the same widget set on Android (Glance) and iOS (WidgetKit). This page is the contract both platforms follow. The option ids live in `shared/widgets/widget_options.json`; each platform has a test that fails when its enums drift from that file.

## Rules that never bend
- Widgets never invent data. A value with no source shows "—" or its row is hidden.
- Widgets never log anything on their own. Every action opens the app to the same place the Summary "+" menu opens.
- Widget configuration is per device and is not part of backups or exports.
- No analytics.

## Widgets
| Widget | Sizes | Config | Shows |
|---|---|---|---|
| Calorie, Protein, All Metrics (Android), Water, Log Food (iOS) | unchanged | – | food and water (older widgets, unchanged) |
| **Today** | Android 2×2 · 4×2 · 4×4; iOS small · medium · large | none | Eat · Move · Drink rings. Medium adds values. Large adds rows for Fasting, Next dose, Weight, Workout; rows without data are hidden. |
| **My Metrics** | Android 2×2 · 4×2; iOS small · medium | 4 metric slots | Four tiles: value, unit, goal bar where a goal exists. |
| **Quick Log** | Android 2×2 · 4×1; iOS small (2×2) · medium (1×4) | 4 action slots | Four log buttons. |

## Quick Log actions
Default slots: `food.camera`, `water`, `weight`, `workout`.

| Id | Lands on |
|---|---|
| `food.menu` | Nutrition with the "+" food menu open |
| `food.camera` · `food.photos` · `food.barcode` · `food.voice` · `food.text` · `food.manual` · `food.favorites` · `food.recent` · `food.frequent` · `food.copy_from_day` | Nutrition running that food log method (food logging is still blocked while a fast is active) |
| `water` | Summary with the custom water amount sheet |
| `fasting` | Summary with the start-fast dialog, or Fasting when a fast is active. The tile reads "Start fast" / "End fast". |
| `weight` · `body_fat` | Summary with the log dialog |
| `workout` | Workout log |
| `medication` | Medications |
| `record` | Records with the add-record sheet |

When water or fasting tracking is off, the tile is dimmed and opens Settings › Tracking › Hydration / Fasting.

Platform notes (each follows its own Summary "+" menu):
- iOS `food.menu` lands on Nutrition with the "+" button in reach; SwiftUI cannot open a menu programmatically.
- iOS `fasting` runs the same Nutrition fasting action as the iOS Summary "+" menu (start sheet, or the active fast).
- iOS small Quick Log uses interactive buttons (`OpenLogEntryIntent`, `openAppWhenRun`) because a small widget allows only one link; the intent leaves the route in the App Group (`widget.route.pending`) and the app consumes it when it becomes active. Medium uses one link per tile.
- iOS small My Metrics opens the first tile's metric (one link per small widget); medium has one link per tile.
- Android `food.menu` opens Nutrition with the "+" food menu open; every tile of every size has its own tap target.
- Android slots are chosen in a configure screen (`WidgetConfigureActivity`). On Android 12+ the widget is placed with the default slots and changed later from the widget's Settings (long press); on older versions the screen opens when the widget is placed.
- Android stores the My Metrics tiles pre-formatted by the same builder as Summary favourites (`MetricTileBuilder`), with a goal progress for calories, macros, water and steps.

## My Metrics keys
Default slots: `app:calories`, `steps`, `app:water`, `app:weight`.

Keys are the metric catalog ids (`shared/metrics/metric_catalog.json`, `shared/health/metric_registry.json`): `app:calories`, `app:protein`, `app:carbs`, `app:fat`, `app:fiber`, `app:water`, `app:weight`, `app:body_fat`, `app:fasting`, `app:workouts`, `app:workout_burn`, `steps`, `active_energy`, `sleep`, `heart_rate`, and the widget-only `medications:next_dose`.

A tile tap opens the metric detail. `app:fasting` opens Fasting and `medications:next_dose` opens Medications. An unknown or missing slot falls back to that slot's default; duplicate slots are allowed.

## Deep links
| Platform | Log action | Metric | Summary |
|---|---|---|---|
| iOS | `ayuvo://log/<actionId>` | `ayuvo://metric/<key>` | `ayuvo://summary` |
| Android | intent action `com.ayuvo.health.LOG_ENTRY`, extra `log_entry` | `com.ayuvo.health.OPEN_METRIC`, extra `metric_key` | `com.ayuvo.health.OPEN_SUMMARY` |

The older `ayuvo://log-food?method=` and `ayuvo://medications` links keep working.

`ayuvo://action/<id>?…` and `ayuvo://open/<section>` belong to the Actions platform (Siri, Shortcuts, Android shortcuts, Coach) and are specified in [actions.md](actions.md). They are parsed first, and any link with another host continues to the widget router above.

## Dashboard snapshot
The app writes `WidgetDashboardSnapshot` v1; widgets only read it. The older food/water `WidgetSnapshot` is untouched, because the Watch decodes its layout.

- Android: DataStore key `widgetDashboardSnapshot` (JSON).
- iOS: App Group file `Library/Application Support/AyuvoWidgets/widget_dashboard_v1.json`. The model file is copied byte-identically into the widget extension; a test compares the copies.
  iOS stores display-ready text: each ring and each of the 16 My Metrics keys carries its value and unit formatted with the user's units, the metric's domain colour and a goal progress. The Move ring reads today's steps from Apple Health, like the Summary ring; the other health tiles use the health mirror, like Summary favourites.

| Field | Contents |
|---|---|
| Header | `dayStart`, `generatedAt`, theme hex, weight and water units |
| Rings | `eat` (kcal, goal) · `move` (steps, step goal, health connected) · `drink` (ml, goal, enabled) |
| Fasting | tracking enabled, active start, goal minutes |
| Medications | today's doses (name, time, status), taken, total. Null when no medications database exists; the widget never creates it. |
| Body | latest weight and body fat with their dates |
| Workouts today | count, minutes, burn, first title |
| Health | steps, active energy, last night's sleep, latest heart rate (only from the health mirror, only when enabled) |
| Nutrients | calories, protein, carbs, fat, fiber and goals |

Every field can be null. Day-scoped values are ignored when `dayStart` is not today; weight and body fat stay visible with their date. Fasting elapsed time and the next dose are computed when the widget renders.

## Freshness
- The app republishes the snapshot (debounced by one second) whenever food, water, fasting, weight, body fat, workouts, medications, health sync, step goal, units or theme change, and when the app becomes active.
- Android also rebuilds it from the 30-minute WorkManager refresh, so widgets update without the app open, and schedules a one-time refresh at the next dose, the fasting goal and midnight.
- iOS adds timeline entries at each dose time, the fasting goal and midnight, with a 30-minute fallback. iOS has no background rebuild: the snapshot is refreshed while the app runs, and after midnight the widgets clear day-scoped values on their own.
- Health values are only as fresh as the last health sync.

## Picker previews
Android widgets declare `android:previewLayout` (Android 12+) and `android:previewImage` (Android 8–11) so the launcher's widget picker shows the widget, not the app icon. Both are generated by `scripts/widget_previews.py` and use illustrative values (`values/widget_preview_strings.xml`) that never reach the app. They follow light and dark mode. The previewImage PNGs are crops of the Android 12+ picker; re-capture them when a preview changes.

## Parity checklist
- Same widgets, sizes, option ids, defaults and tap destinations on both platforms.
- Same empty states: "—" for a missing value, hidden rows on Today.
- Same dimmed state for disabled water/fasting actions.
- Rings use the domain colours: Eat `#34C759`, Move `#FF9500`, Drink `#007AFF`.
