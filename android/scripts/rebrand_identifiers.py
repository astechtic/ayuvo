#!/usr/bin/env python3
"""Phase A3 of the Ayuvo rebrand: internal identifier, file and theme renames.

Rewrites Kotlin/XML/Gradle/docs under android/ (strings.xml is left to
rebrand_strings.py) and renames the brand-bearing source files. Idempotent.
"""
from __future__ import annotations

import re
import shutil
import sys
from pathlib import Path

ANDROID = Path(__file__).resolve().parents[1]
K = ANDROID / "app/src/main/java/com/ayuvo/health"
T = ANDROID / "app/src/test/java/com/ayuvo/health"

# Ordered: longer / more specific tokens first. Plain string replacements.
REPLACEMENTS: list[tuple[str, str]] = [
    # --- Compose component / class identifiers ---
    ("FudGlassDialogActions", "GlassDialogActions"),
    ("FudGlassPrimaryButton", "GlassPrimaryButton"),
    ("FudGlassTextButton", "GlassTextButton"),
    ("FudGlassTextField", "GlassTextField"),
    ("FudGlassSurface", "GlassSurface"),
    ("FudGlassDialog", "GlassDialog"),
    ("FudGlassColumn", "GlassColumn"),
    ("FudIconBubble", "IconBubble"),
    ("FudGlass", "Glass"),
    ("FudAIRoutesTest", "AppRoutesTest"),
    ("FudAIRoutes", "AppRoutes"),
    ("FudAINavHost", "AppNavHost"),
    ("FudAIBottomNavBar", "AppBottomNavBar"),
    ("FudAITheme", "AyuvoTheme"),
    ("FudAIApp", "AyuvoApp"),
    ("FudGlanceAppWidgetReceiver", "AyuvoGlanceAppWidgetReceiver"),
    ("FudGlanceAppWidget", "AyuvoGlanceAppWidget"),
    ("FudaiDataStoreHolder", "AppDataStoreHolder"),
    ("FudaiDataStore", "AppDataStore"),
    ("fudaiDataStore", "appDataStore"),
    ("FudAIKeyStore", "AyuvoKeyStore"),
    ("FudAIHealth", "AyuvoHealth"),
    ("FudAIWidget", "AyuvoWidget"),
    ("FudAiDebugSeeder", "AyuvoDebugSeeder"),
    # --- theme / tint ---
    ("FudPinkLauncherActivity", "RoseLauncherActivity"),
    ("Theme.FudAI.Splash.FudPink", "Theme.Ayuvo.Splash.Rose"),
    ("Theme.FudAI", "Theme.Ayuvo"),
    ("FUD_PINK", "ROSE"),
    ('"fudPink"', '"rose"'),
    ("theme_color_fud_pink", "theme_color_rose"),
    ("health_source_fud_ai", "health_source_own"),
    ("Fud Pink", "Rose"),
    # --- file formats, database, prefs, dirs ---
    ("fudai-health-android-fixture.zip", "ayuvo-health-android-fixture.zip"),
    ("FUDAI_HEALTH_FIXTURE_DIR", "AYUVO_HEALTH_FIXTURE_DIR"),
    ("fudai-health-data", "ayuvo-health-data"),
    ("fudai-cloud-backup", "ayuvo-cloud-backup"),
    ("fudai-backup.zip", "ayuvo-backup.zip"),
    ("fudai_health", "ayuvo_health"),
    ("fudai_prefs", "ayuvo_prefs"),
    ("fudai_keychain", "ayuvo_keychain"),
    ("fudai_workouts", "ayuvo_workouts"),
    ("fudai-food-thumbnails-v2", "ayuvo-food-thumbnails"),
    ("fudai-food-images", "ayuvo-food-images"),
    ("fudai-stt", "ayuvo-stt"),
    ("fudai-wav-recorder", "ayuvo-wav-recorder"),
    ("fud_ai_widget_periodic_refresh", "ayuvo_widget_periodic_refresh"),
    ("fud_ai_widget_refresh", "ayuvo_widget_refresh"),
    ("fudai_workout_burn|", "ayuvo_workout_burn|"),
    ("fudai_", "ayuvo_"),
    ("fudai-tagged", "ayuvo-tagged"),
    ("fud-ai-android-debug-year-v1", "ayuvo-android-debug-year-v1"),
    ('"fud-ai-${', '"ayuvo-${'),
    ("fud-user-exercise", "ayuvo-user-exercise"),
    ("Fud-Health-Data-", "Ayuvo-Health-Data-"),
    ("Fud-Health-", "Ayuvo-Health-"),
    ("Fud-Food-Diary-", "Ayuvo-Food-Diary-"),
    # --- signing / build ---
    ("fudai-release.jks", "ayuvo-release.jks"),
    ("-alias fudai", "-alias ayuvo"),
    ("keyAlias=fudai", "keyAlias=ayuvo"),
    ('rootProject.name = "Fud AI"', 'rootProject.name = "Ayuvo"'),
    ("Fud AI Debug 2", "Ayuvo Debug 2"),
    ("Fud AI Debug", "Ayuvo Debug"),
    # --- network ---
    ('builder.addHeader("HTTP-Referer", "https://github.com/apoorvdarshan/fud-ai")\n', ""),
    ('builder.addHeader("X-Title", "Fud AI")', 'builder.addHeader("X-Title", "Ayuvo")'),
    ('"FudAI/${BuildConfig.VERSION_NAME} (https://fud-ai.app)"', '"Ayuvo/${BuildConfig.VERSION_NAME} (${AppLinks.SITE_URL})"'),
    # --- prose (comments, messages, docs) ---
    ("(github.com/apoorvdarshan/delts)", "(the Delts workout app)"),
    ("Rate-fud", "Rate-app"),
    ("Fud AI's", "Ayuvo's"),
    ("Fud AI", "Ayuvo"),
    ("FudAI", "Ayuvo"),
]

FILE_RENAMES: list[tuple[Path, Path]] = [
    (K / "FudAIApp.kt", K / "AyuvoApp.kt"),
    (K / "ui/navigation/FudAINavHost.kt", K / "ui/navigation/AppNavHost.kt"),
    (K / "ui/navigation/FudAIRoutes.kt", K / "ui/navigation/AppRoutes.kt"),
    (T / "ui/navigation/FudAIRoutesTest.kt", T / "ui/navigation/AppRoutesTest.kt"),
    (K / "ui/components/FudGlass.kt", K / "ui/components/Glass.kt"),
    (K / "widget/FudGlanceAppWidget.kt", K / "widget/AyuvoGlanceAppWidget.kt"),
    (K / "widget/FudGlanceAppWidgetReceiver.kt", K / "widget/AyuvoGlanceAppWidgetReceiver.kt"),
    (K / "data/FudaiDataStore.kt", K / "data/AppDataStore.kt"),
]

TEXT_SUFFIXES = {".kt", ".kts", ".xml", ".md", ".pro", ".template", ".example", ".properties"}
SKIP_DIRS = {"build", ".gradle", ".kotlin", "release", ".idea", "scripts"}
SKIP_FILES = {"strings.xml"}  # handled by rebrand_strings.py


def iter_files():
    for path in ANDROID.rglob("*"):
        if path.is_dir() or path.suffix not in TEXT_SUFFIXES or path.name in SKIP_FILES:
            continue
        if any(part in SKIP_DIRS for part in path.relative_to(ANDROID).parts):
            continue
        yield path


def main() -> int:
    for src, dst in FILE_RENAMES:
        if src.exists():
            shutil.move(str(src), str(dst))
    changed = 0
    for path in iter_files():
        text = path.read_text(encoding="utf-8")
        new = text
        for old, replacement in REPLACEMENTS:
            new = new.replace(old, replacement)
        if new != text:
            path.write_text(new, encoding="utf-8")
            changed += 1
    print(f"rewrote {changed} files")
    # AppLinks import for the Open Food Facts user-agent.
    off = K / "services/OpenFoodFactsService.kt"
    text = off.read_text(encoding="utf-8")
    if "import com.ayuvo.health.AppLinks" not in text and "AppLinks.SITE_URL" in text:
        text = text.replace("import com.ayuvo.health.BuildConfig\n", "import com.ayuvo.health.AppLinks\nimport com.ayuvo.health.BuildConfig\n", 1)
        off.write_text(text, encoding="utf-8")
    return 0


if __name__ == "__main__":
    sys.exit(main())
