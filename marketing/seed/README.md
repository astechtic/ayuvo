# Store screenshot capture

Eight screens, same order on both platforms (`marketing/storyboard.json`), English, default
Rose accent, dark mode for screens 1, 3 and 6, light for the rest. Composites and the website
set are built by `python3 scripts/brand/compose_marketing.py`; until real captures exist the
script writes labelled placeholders so the site validates.

| # | id | Screen | Set-up |
|---|----|--------|--------|
| 1 | `home` | Home dashboard | today with health tiles, water card, an active fast, 3 logged meals |
| 2 | `health` | Health hub | Health tab → Health Data, categories populated |
| 3 | `health-detail` | Health detail | Heart rate or Sleep, Week range |
| 4 | `snap` | Snap a meal | camera with 2 photos in the tray |
| 5 | `review` | Review nutrition | analysed plate, nutrition unlocked |
| 6 | `coach` | AI Coach | a health-aware answer (sleep + training) visible |
| 7 | `workouts` | Workouts | diary week with sets logged; exercise detail with animation |
| 8 | `fasting` | Fasting & water | timer running + water progress |

## Seed the app

1. Fresh install, onboarding with the demo profile (30 y, 175 cm, 72 kg, Moderate, Maintain).
2. Settings → AI Providers → Google Gemini → paste the demo key.
3. Settings → Health & Data → import the health fixture (`-ayuvoHealthFixture <zip>` launch argument on iOS; Import Health Data on Android) so the hub and Home tiles have a month of data.
4. Log three meals by photo (breakfast, lunch, dinner), 6 glasses of water, start a 16 h fast.
5. Workouts → plan Push day, log 3 sets each of bench press, incline press, lateral raise.
6. Coach → ask "How did I sleep this week and should I train hard today?"

## Capture

**iOS** (iPhone 16 simulator, 1179×2556):
```bash
xcrun simctl boot "iPhone 16"
xcrun simctl status_bar booted override --time 9:41 --batteryState charged --batteryLevel 100 --cellularBars 4 --wifiBars 3
xcrun simctl io booted screenshot --type=png marketing/raw/ios/01-home.png   # …02-health.png etc.
```
For the 6.9" store set the composites are rendered at 1290×2796 from the same captures. Apple Watch:
boot the paired "Apple Watch Ultra 2 (49mm)" and `xcrun simctl io <watch-udid> screenshot marketing/appstore/watch/01-rings-416x496.png`.

**Android** (Pixel 8 emulator, 1080×2400):
```bash
adb shell settings put global sysui_demo_allowed 1
adb shell am broadcast -a com.android.systemui.demo -e command enter
adb shell am broadcast -a com.android.systemui.demo -e command clock -e hhmm 0941
adb shell am broadcast -a com.android.systemui.demo -e command battery -e level 100 -e plugged false
adb shell am broadcast -a com.android.systemui.demo -e command network -e wifi show -e level 4
adb exec-out screencap -p > marketing/raw/android/01-home.png                 # …02-health.png etc.
adb shell am broadcast -a com.android.systemui.demo -e command exit
```

## Compose and verify

```bash
python3 scripts/brand/compose_marketing.py        # marketing/store/**, web/assets/screenshots/*
python3 scripts/web_check.py                      # dimension + link checks
```
