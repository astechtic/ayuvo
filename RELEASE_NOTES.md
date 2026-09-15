# Release notes

Reviewed notes per store tag. `scripts/release_notes.py <tag>` prints the matching `## <tag>` section
and refuses to publish an empty or missing one. iOS tags are `vX.Y`; the Android workflow strips its
`android-` prefix before looking the section up, so one section serves both platforms.

## v1.0

Ayuvo 1.0 — nutrition, workouts, the Health data hub, fasting, water and an AI coach in one private app. First release of Ayuvo; there is no upgrade path from other apps.

NEW
- Log meals by photo (up to 10 at once), barcode, voice, text, saved meals or manual entry, with 30+ nutrients and a review step before anything is saved.
- Plan and log workouts (sets, reps, weight, RPE) from a 1,300-exercise library with photos and animations.
- Health data hub: mirror the Apple Health / Health Connect types you grant into a local database with Home tiles, day/week/month/6-month/year charts, Show All Data, Data Sources and export/import.
- Optional fasting timer (1–168 h) and water tracking with reminders and widgets.
- AI coach on your own provider key (15 providers) or fully on-device; it sees your health data only after a visible consent toggle.
- Apple Watch app and complications, iOS and Android widgets, Siri Shortcuts, iOS Share Extension, 18 languages, 18 accent colours with matching icons.
- Optional iCloud / Google Drive backup that never includes health data or coach chat.
