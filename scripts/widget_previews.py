"""Generates the Android widget picker previews (docs/widgets.md, "Picker previews").

Writes res/layout/widget_preview_*.xml (android:previewLayout, Android 12+), the vector art and
colours they use, and hooks both previewLayout and, when the PNG exists, previewImage into each
res/xml/*_widget_info.xml. The previewImage PNGs (Android 8-11) in res/drawable-nodpi are crops of
the Android 12+ picker; re-capture them after changing a preview. Run: python3 scripts/widget_previews.py
"""
import math, os

RES = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "android", "app", "src", "main", "res")
AAPT = 'xmlns:aapt="http://schemas.android.com/aapt"'
ANDROID = 'xmlns:android="http://schemas.android.com/apk/res/android"'
TOOLS = 'xmlns:tools="http://schemas.android.com/tools"'


def write(path, text):
    if path.startswith("layout/"):
        from xml.dom import minidom
        comment = text.split("\n")[1]
        body = minidom.parseString(text.split("-->\n", 1)[1]).documentElement.toprettyxml(indent="    ")
        text = '<?xml version="1.0" encoding="utf-8"?>\n' + comment + "\n" + body
    full = os.path.join(RES, path)
    os.makedirs(os.path.dirname(full), exist_ok=True)
    with open(full, "w") as f:
        f.write(text)


def f(v):
    return f"{v:.2f}".rstrip("0").rstrip(".")


def pt(cx, cy, r, deg):
    a = math.radians(deg)
    return cx + r * math.cos(a), cy + r * math.sin(a)


def arc(cx, cy, r, start, sweep):
    """SVG path for a clockwise arc (canvas degrees, 0 = 3 o'clock, y down)."""
    if sweep >= 359.99:
        x1, y1 = pt(cx, cy, r, start)
        x2, y2 = pt(cx, cy, r, start + 180)
        return (f"M{f(x1)},{f(y1)} A{f(r)},{f(r)} 0 1,1 {f(x2)},{f(y2)} "
                f"A{f(r)},{f(r)} 0 1,1 {f(x1)},{f(y1)}")
    x1, y1 = pt(cx, cy, r, start)
    x2, y2 = pt(cx, cy, r, start + sweep)
    large = 1 if sweep > 180 else 0
    return f"M{f(x1)},{f(y1)} A{f(r)},{f(r)} 0 {large},1 {f(x2)},{f(y2)}"


def vector(w, h, body):
    return (f'<?xml version="1.0" encoding="utf-8"?>\n'
            f'<!-- Widget picker preview art (generated; see docs/widgets.md). -->\n'
            f'<vector {ANDROID}\n    {AAPT}\n'
            f'    android:width="{f(w)}dp"\n    android:height="{f(h)}dp"\n'
            f'    android:viewportWidth="{f(w)}"\n    android:viewportHeight="{f(h)}">\n'
            f"{body}</vector>\n")


def stroke_path(d, color, width, cap="round"):
    return (f'    <path\n        android:pathData="{d}"\n        android:strokeColor="{color}"\n'
            f'        android:strokeWidth="{f(width)}"\n        android:strokeLineCap="{cap}"\n'
            f'        android:fillColor="#00000000" />\n')


def gradient_stroke_path(d, width, x1, y1, x2, y2, start, end, cap="butt"):
    return (f'    <path\n        android:pathData="{d}"\n        android:strokeWidth="{f(width)}"\n'
            f'        android:strokeLineCap="{cap}"\n        android:fillColor="#00000000">\n'
            f'        <aapt:attr name="android:strokeColor">\n'
            f'            <gradient android:type="linear" android:startX="{f(x1)}" android:startY="{f(y1)}"\n'
            f'                android:endX="{f(x2)}" android:endY="{f(y2)}"\n'
            f'                android:startColor="{start}" android:endColor="{end}" />\n'
            f'        </aapt:attr>\n    </path>\n')


THEME_START, THEME_END, THEME_TRACK = "#FF0A84FF", "#FF5EAEFF", "#260A84FF"


def gauge(progress):
    """Dashed top-semicircle speedometer, same proportions as speedometerBitmap()."""
    d = 100.0
    s = d * 0.085
    r = d / 2 - s / 2
    cx = cy = d / 2
    dash, gap = s * 0.28, s * 0.42
    circ = math.pi * r
    track, fill = [], []
    pos = 0.0
    while pos < circ:
        a0 = 180 + pos / circ * 180
        a1 = 180 + min(pos + dash, circ) / circ * 180
        seg = arc(cx, cy, r, a0, a1 - a0)
        track.append(seg)
        if pos / circ < progress:
            fill.append(seg)
        pos += dash + gap
    body = stroke_path(" ".join(track), THEME_TRACK, s, "butt")
    body += gradient_stroke_path(" ".join(fill), s, 0, 0, d, 0, THEME_START, THEME_END)
    return vector(d, d * 0.58, body)


def tube(progress):
    w, h = 14.0, 60.0
    rad = w / 2
    body = (f'    <path android:fillColor="{THEME_TRACK}"\n        android:pathData="'
            f'M{f(rad)},0 L{f(w-rad)},0 A{f(rad)},{f(rad)} 0 0,1 {f(w)},{f(rad)} L{f(w)},{f(h-rad)} '
            f'A{f(rad)},{f(rad)} 0 0,1 {f(w-rad)},{f(h)} L{f(rad)},{f(h)} A{f(rad)},{f(rad)} 0 0,1 0,{f(h-rad)} '
            f'L0,{f(rad)} A{f(rad)},{f(rad)} 0 0,1 {f(rad)},0 Z" />\n')
    fh = max(h * progress, w)
    top = h - fh
    body += (f'    <path android:pathData="'
             f'M{f(rad)},{f(top)} A{f(rad)},{f(rad)} 0 0,1 {f(w)},{f(top+rad)} L{f(w)},{f(h-rad)} '
             f'A{f(rad)},{f(rad)} 0 0,1 {f(w-rad)},{f(h)} L{f(rad)},{f(h)} A{f(rad)},{f(rad)} 0 0,1 0,{f(h-rad)} '
             f'L0,{f(top+rad)} A{f(rad)},{f(rad)} 0 0,1 {f(rad)},{f(top)} Z">\n'
             f'        <aapt:attr name="android:fillColor">\n'
             f'            <gradient android:type="linear" android:startX="0" android:startY="{f(h)}"\n'
             f'                android:endX="0" android:endY="{f(top)}"\n'
             f'                android:startColor="{THEME_START}" android:endColor="{THEME_END}" />\n'
             f'        </aapt:attr>\n    </path>\n')
    return vector(w, h, body)


def track_of(hex6):
    return "#40" + hex6


def ring(progress, hex6, size=58.0, stroke=8.0):
    r = size / 2 - stroke / 2
    body = stroke_path(arc(size / 2, size / 2, r, 0, 360), track_of(hex6), stroke)
    body += stroke_path(arc(size / 2, size / 2, r, -90, 360 * progress), "#FF" + hex6, stroke)
    return vector(size, size, body)


def concentric(rings):
    size, stroke = 100.0, 11.0
    gap = stroke * 0.18
    body = ""
    for i, (p, hex6) in enumerate(rings):
        r = size / 2 - stroke / 2 - i * (stroke + gap)
        body += stroke_path(arc(size / 2, size / 2, r, 0, 360), track_of(hex6), stroke)
        body += stroke_path(arc(size / 2, size / 2, r, -90, 360 * p), "#FF" + hex6, stroke)
    return vector(size, size, body)


def bar(progress, hex6):
    w, h = 100.0, 6.0
    body = stroke_path(f"M3,3 L97,3", track_of(hex6), h)
    body += stroke_path(f"M3,3 L{f(3 + 94 * progress)},3", "#FF" + hex6, h)
    return vector(w, h, body)


EAT, MOVE, DRINK, BODY = "34C759", "FF9500", "007AFF", "AF52DE"

art = {
    "widget_preview_gauge_calorie": gauge(0.62),
    "widget_preview_gauge_protein": gauge(0.72),
    "widget_preview_gauge_water": gauge(0.48),
    "widget_preview_tube_protein": tube(0.72),
    "widget_preview_tube_carbs": tube(0.56),
    "widget_preview_tube_fat": tube(0.65),
    "widget_preview_tube_fiber": tube(0.40),
    "widget_preview_ring_eat": ring(0.62, EAT),
    "widget_preview_ring_move": ring(0.65, MOVE),
    "widget_preview_ring_drink": ring(0.48, DRINK),
    "widget_preview_bar_eat": bar(0.62, EAT),
    "widget_preview_bar_move": bar(0.65, MOVE),
    "widget_preview_bar_drink": bar(0.48, DRINK),
}
for name, text in art.items():
    write(f"drawable/{name}.xml", text)


def shape(color, radius, oval=False):
    kind = 'android:shape="oval"' if oval else 'android:shape="rectangle"'
    corners = "" if oval else f'    <corners android:radius="{radius}dp" />\n'
    return (f'<?xml version="1.0" encoding="utf-8"?>\n<shape {ANDROID}\n    {kind}>\n'
            f'    <solid android:color="{color}" />\n{corners}</shape>\n')


write("drawable/widget_preview_bg_warm.xml", shape("@color/widget_preview_warm_bg", 22))
write("drawable/widget_preview_bg.xml", shape("@color/widget_preview_bg", 22))
write("drawable/widget_preview_tile.xml", shape("@color/widget_preview_tile", 14))
for name, hex6 in [("eat", EAT), ("drink", DRINK), ("body", BODY), ("move", MOVE)]:
    write(f"drawable/widget_preview_bubble_{name}.xml", shape("#2E" + hex6, 0, oval=True))

colors = {
    "widget_preview_warm_bg": ("#FFFFF8F2", "#FF0C0C0C"),
    "widget_preview_bg": ("#FFFFFFFF", "#FF1C1C1E"),
    "widget_preview_tile": ("#FFF2F2F7", "#FF2C2C2E"),
    "widget_preview_primary": ("#FF1C1C1E", "#FFF2F2F7"),
    "widget_preview_secondary": ("#FF8E8E93", "#FF8E8E93"),
}
for folder, idx in [("values", 0), ("values-night", 1)]:
    lines = "".join(f'    <color name="{k}">{v[idx]}</color>\n' for k, v in colors.items())
    write(f"{folder}/widget_preview_colors.xml",
          f'<?xml version="1.0" encoding="utf-8"?>\n<!-- Widget picker preview surfaces; match WidgetTheme / DashboardColors. -->\n<resources>\n{lines}</resources>\n')

# Illustrative sample values shown only in the launcher's widget picker.
strings = {
    "wp_today": "Today", "wp_protein": "Protein", "wp_water": "Water",
    "wp_carbs": "Carbs", "wp_fat": "Fat", "wp_fiber": "Fiber",
    "wp_kcal_value": "1,240", "wp_kcal_goal": "/ 2,000", "wp_kcal_left": "760 kcal left", "wp_kcal_left_short": "760 left",
    "wp_protein_value": "86g", "wp_protein_goal": "/ 120g", "wp_protein_left": "34g left",
    "wp_water_value": "1.2", "wp_water_goal": "/ 2.5 L", "wp_water_left": "1.3 L left",
    "wp_bar_protein": "86", "wp_bar_protein_goal": "/120g",
    "wp_bar_carbs": "140", "wp_bar_carbs_goal": "/250g",
    "wp_bar_fat": "42", "wp_bar_fat_goal": "/65g",
    "wp_bar_fiber": "12", "wp_bar_fiber_goal": "/30g",
    "wp_eat": "Eat", "wp_move": "Move", "wp_drink": "Drink",
    "wp_eat_value": "1,240", "wp_eat_goal": "/ 2,000 kcal",
    "wp_move_value": "6,512", "wp_move_goal": "/ 10,000 steps",
    "wp_drink_value": "1.2", "wp_drink_goal": "/ 2.5 L",
    "wp_calories": "Calories", "wp_steps": "Steps", "wp_weight": "Weight",
    "wp_unit_kcal": "kcal", "wp_unit_steps": "steps", "wp_unit_l": "L", "wp_unit_kg": "kg",
    "wp_weight_value": "72.4", "wp_caption_today": "Today", "wp_caption_weight": "2 hr. ago",
    "wp_camera": "Camera", "wp_workout": "Workout",
}
lines = "".join(f'    <string name="{k}" translatable="false">{v}</string>\n' for k, v in strings.items())
write("values/widget_preview_strings.xml",
      '<?xml version="1.0" encoding="utf-8"?>\n<!-- Illustrative values for widget picker previews only; never shown as user data. -->\n'
      f"<resources>\n{lines}</resources>\n")


# ── Layouts ──────────────────────────────────────────────────────────────────
HEAD = f'<?xml version="1.0" encoding="utf-8"?>\n<!-- Widget picker preview (android:previewLayout); mirrors the Glance layout. -->\n'


def text(res, size, color, bold=False, medium=False, extra=""):
    style = ' android:textStyle="bold"' if bold else ""
    font = ' android:fontFamily="sans-serif-medium"' if medium else ""
    return (f'<TextView android:layout_width="wrap_content" android:layout_height="wrap_content"'
            f' android:text="@string/{res}" android:textSize="{size}sp" android:textColor="{color}"'
            f' android:maxLines="1" android:includeFontPadding="false"{style}{font}{extra} />')


BLUE = "#FF0A84FF"
SEC = "@color/widget_preview_secondary"
PRI = "@color/widget_preview_primary"


def header(icon, label, tint, size=12, icon_dp=12):
    return (f'<LinearLayout android:layout_width="wrap_content" android:layout_height="wrap_content"'
            f' android:orientation="horizontal" android:gravity="center_vertical">'
            f'<ImageView android:layout_width="{icon_dp}dp" android:layout_height="{icon_dp}dp"'
            f' android:src="@drawable/{icon}" android:tint="{tint}" android:contentDescription="@null" />'
            f'<FrameLayout android:layout_width="4dp" android:layout_height="1dp" />'
            f'{text(label, size, SEC, medium=True)}</LinearLayout>')


def gauge_block(art_name, value, goal, height="match_parent", width="match_parent", value_sp=22):
    return (f'<FrameLayout android:layout_width="{width}" android:layout_height="{height}">'
            f'<ImageView android:layout_width="match_parent" android:layout_height="match_parent"'
            f' android:src="@drawable/{art_name}" android:scaleType="fitCenter" android:contentDescription="@null" />'
            f'<LinearLayout android:layout_width="wrap_content" android:layout_height="wrap_content"'
            f' android:layout_gravity="center" android:layout_marginTop="10dp" android:orientation="vertical" android:gravity="center_horizontal">'
            f'{text(value, value_sp, BLUE, bold=True)}{text(goal, 11, SEC)}</LinearLayout></FrameLayout>')


def small_gauge_widget(icon, label, art_name, value, goal, left):
    return (HEAD + f'<LinearLayout {ANDROID} {TOOLS} tools:ignore="UseAppTint"\n    android:layout_width="match_parent" android:layout_height="match_parent"'
            f' android:background="@drawable/widget_preview_bg_warm" android:orientation="vertical" android:padding="14dp">'
            f'{header(icon, label, BLUE)}'
            f'<FrameLayout android:layout_width="match_parent" android:layout_height="0dp" android:layout_weight="1">'
            f'{gauge_block(art_name, value, goal)}</FrameLayout>'
            f'{text(left, 12, BLUE, medium=True)}</LinearLayout>\n')


write("layout/widget_preview_calorie.xml",
      small_gauge_widget("ic_widget_flame", "wp_today", "widget_preview_gauge_calorie", "wp_kcal_value", "wp_kcal_goal", "wp_kcal_left"))
write("layout/widget_preview_protein.xml",
      small_gauge_widget("ic_widget_bolt", "wp_protein", "widget_preview_gauge_protein", "wp_protein_value", "wp_protein_goal", "wp_protein_left"))
write("layout/widget_preview_water.xml",
      small_gauge_widget("ic_widget_water", "wp_water", "widget_preview_gauge_water", "wp_water_value", "wp_water_goal", "wp_water_left"))


def tube_cell(art_name, value, label, goal):
    return (f'<LinearLayout android:layout_width="0dp" android:layout_height="wrap_content" android:layout_weight="1"'
            f' android:orientation="vertical" android:gravity="center_horizontal">'
            f'{text(value, 14, BLUE, bold=True)}'
            f'<ImageView android:layout_width="13dp" android:layout_height="46dp" android:layout_marginTop="3dp" android:layout_marginBottom="3dp"'
            f' android:src="@drawable/{art_name}" android:scaleType="fitXY" android:contentDescription="@null" />'
            f'{text(label, 11, PRI, medium=True)}{text(goal, 10, SEC)}</LinearLayout>')


write("layout/widget_preview_all_metrics.xml",
      HEAD + f'<LinearLayout {ANDROID} {TOOLS} tools:ignore="UseAppTint"\n    android:layout_width="match_parent" android:layout_height="match_parent"'
      f' android:background="@drawable/widget_preview_bg_warm" android:orientation="horizontal" android:gravity="center_vertical" android:padding="14dp">'
      f'<LinearLayout android:layout_width="wrap_content" android:layout_height="wrap_content" android:orientation="vertical" android:gravity="center_horizontal">'
      f'{gauge_block("widget_preview_gauge_calorie", "wp_kcal_value", "wp_kcal_goal", height="58dp", width="100dp", value_sp=19)}'
      f'<FrameLayout android:layout_width="1dp" android:layout_height="2dp" />{text("wp_kcal_left_short", 11, BLUE, medium=True)}</LinearLayout>'
      f'<FrameLayout android:layout_width="10dp" android:layout_height="1dp" />'
      f'{tube_cell("widget_preview_tube_protein", "wp_bar_protein", "wp_protein", "wp_bar_protein_goal")}'
      f'{tube_cell("widget_preview_tube_carbs", "wp_bar_carbs", "wp_carbs", "wp_bar_carbs_goal")}'
      f'{tube_cell("widget_preview_tube_fat", "wp_bar_fat", "wp_fat", "wp_bar_fat_goal")}'
      f'{tube_cell("widget_preview_tube_fiber", "wp_bar_fiber", "wp_fiber", "wp_bar_fiber_goal")}'
      f'</LinearLayout>\n')


def ring_cell(art_name, label, value, goal, color):
    return (f'<LinearLayout android:layout_width="0dp" android:layout_height="wrap_content" android:layout_weight="1"'
            f' android:orientation="vertical" android:gravity="center_horizontal">'
            f'<ImageView android:layout_width="58dp" android:layout_height="58dp" android:src="@drawable/{art_name}" android:contentDescription="@null" />'
            f'<FrameLayout android:layout_width="1dp" android:layout_height="4dp" />'
            f'{text(label, 11, SEC)}{text(value, 15, color, bold=True)}{text(goal, 10, SEC)}</LinearLayout>')


write("layout/widget_preview_today.xml",
      HEAD + f'<LinearLayout {ANDROID} {TOOLS} tools:ignore="UseAppTint"\n    android:layout_width="match_parent" android:layout_height="match_parent"'
      f' android:background="@drawable/widget_preview_bg" android:orientation="vertical" android:padding="12dp">'
      f'{header("ic_widget_favorite", "wp_today", "#FF" + EAT, icon_dp=13)}'
      f'<LinearLayout android:layout_width="match_parent" android:layout_height="0dp" android:layout_weight="1"'
      f' android:orientation="horizontal" android:gravity="center_vertical">'
      f'{ring_cell("widget_preview_ring_eat", "wp_eat", "wp_eat_value", "wp_eat_goal", "#FF" + EAT)}'
      f'{ring_cell("widget_preview_ring_move", "wp_move", "wp_move_value", "wp_move_goal", "#FF" + MOVE)}'
      f'{ring_cell("widget_preview_ring_drink", "wp_drink", "wp_drink_value", "wp_drink_goal", "#FF" + DRINK)}'
      f'</LinearLayout></LinearLayout>\n')


def metric_tile(icon, tint, label, value, unit, bar_art, caption, margin):
    bar_view = (f'<ImageView android:layout_width="match_parent" android:layout_height="5dp" android:layout_marginTop="5dp"'
                f' android:src="@drawable/{bar_art}" android:scaleType="fitXY" android:contentDescription="@null" />') if bar_art else ""
    return (f'<LinearLayout android:layout_width="0dp" android:layout_height="match_parent" android:layout_weight="1" {margin}'
            f' android:background="@drawable/widget_preview_tile" android:orientation="vertical" android:gravity="center_vertical" android:padding="10dp">'
            f'{header(icon, label, tint, icon_dp=13)}'
            f'<LinearLayout android:layout_width="wrap_content" android:layout_height="wrap_content" android:layout_marginTop="4dp"'
            f' android:orientation="horizontal" android:baselineAligned="true">'
            f'{text(value, 20, PRI, bold=True)}<FrameLayout android:layout_width="3dp" android:layout_height="1dp" />{text(unit, 12, SEC)}</LinearLayout>'
            f'{bar_view}{text(caption, 10, SEC, extra=" android:layout_marginTop=\"3dp\"")}</LinearLayout>')


write("layout/widget_preview_my_metrics.xml",
      HEAD + f'<LinearLayout {ANDROID} {TOOLS} tools:ignore="UseAppTint"\n    android:layout_width="match_parent" android:layout_height="match_parent"'
      f' android:background="@drawable/widget_preview_bg" android:orientation="vertical" android:padding="8dp">'
      f'<LinearLayout android:layout_width="match_parent" android:layout_height="0dp" android:layout_weight="1" android:orientation="horizontal">'
      f'{metric_tile("ic_widget_flame", "#FF" + EAT, "wp_calories", "wp_eat_value", "wp_unit_kcal", "widget_preview_bar_eat", "wp_caption_today", "android:layout_marginEnd=\"4dp\"")}'
      f'{metric_tile("ic_widget_walk", "#FF" + MOVE, "wp_steps", "wp_move_value", "wp_unit_steps", "widget_preview_bar_move", "wp_caption_today", "android:layout_marginStart=\"4dp\"")}'
      f'</LinearLayout><FrameLayout android:layout_width="1dp" android:layout_height="8dp" />'
      f'<LinearLayout android:layout_width="match_parent" android:layout_height="0dp" android:layout_weight="1" android:orientation="horizontal">'
      f'{metric_tile("ic_widget_water", "#FF" + DRINK, "wp_water", "wp_drink_value", "wp_unit_l", "widget_preview_bar_drink", "wp_caption_today", "android:layout_marginEnd=\"4dp\"")}'
      f'{metric_tile("ic_widget_scale", "#FF" + BODY, "wp_weight", "wp_weight_value", "wp_unit_kg", None, "wp_caption_weight", "android:layout_marginStart=\"4dp\"")}'
      f'</LinearLayout></LinearLayout>\n')


def action_cell(icon, bubble, tint, label, margin):
    return (f'<LinearLayout android:layout_width="0dp" android:layout_height="match_parent" android:layout_weight="1" {margin}'
            f' android:background="@drawable/widget_preview_tile" android:orientation="vertical" android:gravity="center" android:padding="6dp">'
            f'<FrameLayout android:layout_width="34dp" android:layout_height="34dp" android:background="@drawable/{bubble}">'
            f'<ImageView android:layout_width="18dp" android:layout_height="18dp" android:layout_gravity="center"'
            f' android:src="@drawable/{icon}" android:tint="{tint}" android:contentDescription="@null" /></FrameLayout>'
            f'<FrameLayout android:layout_width="1dp" android:layout_height="4dp" />'
            f'{text(label, 12, PRI, medium=True)}</LinearLayout>')


write("layout/widget_preview_quick_log.xml",
      HEAD + f'<LinearLayout {ANDROID} {TOOLS} tools:ignore="UseAppTint"\n    android:layout_width="match_parent" android:layout_height="match_parent"'
      f' android:background="@drawable/widget_preview_bg" android:orientation="horizontal" android:padding="6dp">'
      f'{action_cell("ic_widget_camera", "widget_preview_bubble_eat", "#FF" + EAT, "wp_camera", "android:layout_marginEnd=\"3dp\"")}'
      f'{action_cell("ic_widget_water", "widget_preview_bubble_drink", "#FF" + DRINK, "wp_water", "android:layout_marginStart=\"3dp\" android:layout_marginEnd=\"3dp\"")}'
      f'{action_cell("ic_widget_scale", "widget_preview_bubble_body", "#FF" + BODY, "wp_weight", "android:layout_marginStart=\"3dp\" android:layout_marginEnd=\"3dp\"")}'
      f'{action_cell("ic_widget_fitness", "widget_preview_bubble_move", "#FF" + MOVE, "wp_workout", "android:layout_marginStart=\"3dp\"")}'
      f'</LinearLayout>\n')

# ── Hook previews into the provider infos ────────────────────────────────────
for info, layout in [("calorie", "calorie"), ("protein", "protein"), ("water", "water"),
                     ("all_metrics", "all_metrics"), ("today", "today"),
                     ("my_metrics", "my_metrics"), ("quick_log", "quick_log")]:
    path = os.path.join(RES, f"xml/{info}_widget_info.xml")
    s = open(path).read()
    if "previewLayout" not in s:
        s = s.replace('    android:initialKeyguardLayout',
                      f'    android:previewLayout="@layout/widget_preview_{layout}"\n    android:initialKeyguardLayout', 1)
    png = os.path.join(RES, f"drawable-nodpi/widget_preview_image_{layout}.png")
    if os.path.exists(png) and "previewImage" not in s:
        s = s.replace('    android:initialKeyguardLayout',
                      f'    android:previewImage="@drawable/widget_preview_image_{layout}"\n    android:initialKeyguardLayout', 1)
    open(path, "w").write(s)
print("done")
