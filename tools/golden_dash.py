"""Golden outputs from the Python theme, type scale and gauges, for phase 7.

    PYTHONPATH=<NVII-DASH>/apps/navi:<NVII-DASH>/packages/agedashii-washi \
        /home/nvii/venvs/flet/bin/python tools/golden_dash.py

Writes core/src/jvmTest/resources/golden/dash.json. The palettes and constants
are read straight off theme.py, ui.py and gauges.py. Each gauge is BUILT by the
Python and its Flet controls are taken apart: every text it shows, the angle
it turns, the fraction it fills and the colour it paints - so the Kotlin can be
checked against what the Python actually drew, not against a reading of it.
Colours are recorded as hex under the "night" palette, where no two of the
roles the gauges use share a value.
"""
import json
import math
import os

import flet as ft

from agedashii_navi import gauges, theme, ui

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "..", "core", "src", "test", "resources", "golden",
                   "dash.json")


def texts(control):
    """Every Text value in the tree, depth first."""
    out = []

    def walk(c):
        if c is None:
            return
        if isinstance(c, ft.Text):
            out.append(c.value)
        for child in getattr(c, "controls", None) or []:
            walk(child)
        walk(getattr(c, "content", None))

    walk(control)
    return out


def icon_name(icon):
    return None if icon is None else getattr(icon, "name", str(icon))


def fill(expand_filled, expand_empty):
    return expand_filled / float(expand_filled + expand_empty)


def horizon_case(lean, pitch, roll):
    col = gauges.horizon(lean_deg=lean, pitch_deg=pitch, roll_deg=roll)
    stack = col.controls[0]
    rotated = stack.controls[0].content
    needle = stack.controls[2] if len(stack.controls) > 2 else None
    return {"lean": lean, "pitch": pitch, "roll": roll,
            "angle_deg": math.degrees(rotated.rotate.angle),
            "shift": rotated.content.offset.y,
            "needle_deg": (math.degrees(needle.rotate.angle)
                           if needle is not None else None),
            "texts": texts(col)}


def g_ball_case(g_lat, g_long):
    col = gauges.g_ball(g_lat=g_lat, g_long=g_long)
    dot = col.controls[0].controls[2]
    return {"g_lat": g_lat, "g_long": g_long, "x": dot.alignment.x,
            "y": dot.alignment.y, "colour": dot.content.bgcolor,
            "texts": texts(col)}


def meter_case(fraction, peak, label, value):
    col = gauges.meter(fraction, peak, label, value)
    layers = col.controls[0].content.controls
    bar = layers[0].controls
    tick = None
    if len(layers) > 1:
        t = layers[1].controls
        tick = fill(t[2].expand, t[0].expand)
    return {"fraction": fraction, "peak": peak, "label": label,
            "value": value, "fill": fill(bar[1].expand, bar[0].expand),
            "colour": bar[1].bgcolor, "tick": tick, "texts": texts(col)}


def climb_case(alt, rate, imperial):
    box = gauges.climb(alt_m=alt, climb_mps=rate, imperial=imperial)
    icon = box.content.controls[0].controls[0]
    return {"alt_m": alt, "climb_mps": rate, "imperial": imperial,
            "icon": icon_name(icon.icon), "colour": icon.color,
            "texts": texts(box)}


def band_case(bands):
    col = gauges.band_meter(bands)
    bars = []
    for bar in col.controls[0].controls:
        halves = bar.controls[0].content.controls
        bars.append({"fill": fill(halves[1].expand, halves[0].expand),
                     "colour": halves[1].bgcolor})
    return {"bands": bands, "bars": bars, "texts": texts(col)}


def gear_case(gear, hint):
    row = gauges.gear_badge(gear, hint)
    text, icon = row.controls
    return {"gear": gear, "hint": hint, "text": text.value,
            "text_colour": text.color, "icon": icon_name(icon.icon),
            "icon_colour": icon.color}


def gforce_case(g_lat, g_long):
    col = gauges.gforce_bars(g_lat, g_long)
    out = []
    for bar in col.controls[:2]:
        left, filled, right = bar.content.controls
        total = float(left.expand + filled.expand + right.expand)
        out.append({"left": left.expand / total, "fill": filled.expand / total,
                    "right": right.expand / total, "colour": filled.bgcolor})
    return {"g_lat": g_lat, "g_long": g_long, "long": out[0], "lat": out[1],
            "texts": texts(col)}


def rpm_case(rpm, machine, hint):
    col = gauges.rpm_line(rpm, machine, hint=hint)
    filled, empty = col.controls[0].content.controls
    label = col.controls[1].controls[0]
    return {"rpm": rpm, "machine": machine, "hint": hint,
            "fill": fill(filled.expand, empty.expand),
            "gradient": list(filled.gradient.colors), "text": label.value,
            "text_colour": label.color}


def horizon_line_case(lean, pitch):
    box = gauges.horizon_line(lean, pitch)
    inner = box.content
    wing = inner.content.controls[0]
    return {"lean": lean, "pitch": pitch,
            "angle_deg": math.degrees(inner.rotate.angle),
            "shift": inner.offset.y, "colour": wing.bgcolor}


FULL = {"imperial": True, "machine": "cb750", "speed_ms": 27.0, "limit": 45,
        "lean_deg": 28.0, "pitch_deg": -4.0, "g_lat": 0.4, "g_long": -0.3,
        "rpm": 5200, "gear": 3, "shift": "up", "alt_m": 104.0,
        "climb_mps": 1.1, "roll_deg": 6.0, "tilt_deg": 6.0,
        "bands": [["low", 0.2], ["mid", 1.0], ["high", 0.4]],
        "loudness": -22.0, "loudness_peak": 0.7}
BARE = {"imperial": True, "machine": "cb750"}
METRIC = dict(FULL, imperial=False, speed_ms=31.3, limit=None, gear=None,
              shift="", lean_deg=-35.6)
SLOW = dict(FULL, speed_ms=12.0, limit=25, lean_deg=-12.5, shift="down",
            gear=2, rpm=1900)


def overlay_case(name, state, layout, wide=False, anchor="bottom"):
    built = gauges.overlay(dict(state), layout, wide=wide, anchor=anchor)
    return {"state": name, "layout": layout, "wide": wide, "anchor": anchor,
            "none": built is None, "texts": [] if built is None else texts(built)}


def main():
    theme.S["surface"] = "map"
    theme.set_theme("night")
    night = theme.THEMES["night"]
    blob = {
        "themes": theme.THEMES, "theme_order": list(theme.THEMES),
        "alola": theme.ALOLA, "names": theme.THEME_NAMES,
        "names_order": list(theme.THEME_NAMES),
        "bike_colours": theme.BIKE_COLOURS, "bike_outline": theme.BIKE_OUTLINE,
        "brand": [theme.ORANGE, theme.PINK, theme.CYAN],
        "dark": sorted(theme.DARK_THEMES), "tiles": theme.THEME_TILES,
        "default": theme.DEFAULT,
        "type": {"TOUCH": ui.TOUCH, "HUGE": ui.HUGE, "BIG": ui.BIG,
                 "MID": ui.MID, "SMALL": ui.SMALL},
        "constants": {"LEAN_FULL_DEG": gauges.LEAN_FULL_DEG,
                      "PITCH_FULL_DEG": gauges.PITCH_FULL_DEG,
                      "G_FULL": gauges.G_FULL,
                      "ROLL_HINT_DEG": gauges.ROLL_HINT_DEG},
        "lean_bands": [list(b) for b in gauges.LEAN_BANDS],
        "layouts": [list(l) for l in gauges.LAYOUTS],
        "night": night,
    }
    blob["horizon"] = [horizon_case(*c) for c in (
        (None, None, None), (28.0, -4.0, 6.0), (28.0, -4.0, 45.0),
        (28.0, -4.0, 40.0), (28.0, -4.0, 39.999), (-60.0, 50.0, None),
        (400.0, -999.0, 180.0), (0.0, 0.0, 0.0), (27.5, 0.0, None),
        (26.5, 0.0, None), (-0.4, 15.0, -30.0), (None, 10.0, 40.0))]
    blob["g_ball"] = [g_ball_case(*c) for c in (
        (None, None), (0.4, -0.3), (9.0, -9.0), (0.6, 0.6), (None, 0.5),
        (-0.8, 0.0), (0.0, 0.0))]
    blob["meter"] = [meter_case(*c) for c in (
        (None, None, "rpm est", "—"), (0.5, None, "rpm est", "2600"),
        (0.85, 0.9, "level", "-12 dB"), (1.5, -0.2, "level", ""),
        (0.8, 0.0, "x", "y"), (0.80001, None, "x", "y"))]
    blob["climb"] = [climb_case(*c) for c in (
        (None, None, True), (300.0, 1.0, True), (300.0, 1.0, False),
        (104.0, -0.5, True), (50.0, 0.2, False), (None, 0.0, True),
        (12.5, 0.3, True), (12.5, -0.31, False), (-18.4, 0.05, True))]
    blob["bands"] = [band_case(c) for c in (
        None, [], [["low", 0.2], ["mid", 1.0], ["high", 0.4]],
        [["alpha", 1.5], ["beta", None]],
        [["a", 0.1], ["b", 0.2], ["c", 0.3], ["d", 0.4]])]
    blob["lean_colour"] = {
        key: [{"lean": lean, "colour": _lean_colour(key, lean)}
              for lean in (None, 0.0, 17.9, 18.0, 31.9, 32.0, -40.0, -18.0)]
        for key in theme.THEMES}
    blob["gear"] = [gear_case(*c) for c in (
        (None, ""), (3, "up"), (1, "down"), (5, ""), (0, ""), (2, "sideways"))]
    blob["gforce"] = [gforce_case(*c) for c in (
        (None, None), (0.4, -0.3), (2.0, -2.0), (-0.5, 0.7), (0.0, 0.0))]
    blob["rpm_line"] = [rpm_case(*c) for c in (
        (None, "cb750", ""), (5200, "cb750", "up"), (20000, "ex250", ""),
        (4000, "other", "down"), (9500.5, "cb750", ""))]
    blob["horizon_line"] = [horizon_line_case(*c) for c in (
        (None, None), (28.0, -4.0), (-90.0, 60.0), (20.0, None))]
    blob["loudness_norm"] = [[x, gauges.loudness_norm(x)] for x in (
        None, -200.0, -60.0, -33.0, -6.0, 0.0, 12.0)]
    blob["rpm_fraction"] = [[r, m, gauges.rpm_fraction(r, m)] for r, m in (
        (None, "cb750"), (99999, "cb750"), (4750, "cb750"), (7000, "ex250"),
        (4500, None), (0, "other"))]
    states = {"full": FULL, "bare": BARE, "metric": METRIC, "slow": SLOW}
    cases = []
    for key, _n, _d in gauges.LAYOUTS + (("off", "", ""),):
        for name, state in states.items():
            cases.append(overlay_case(name, state, key))
    for anchor in ("left", "right"):
        cases.append(overlay_case("full", FULL, "ribbon", True, anchor))
        cases.append(overlay_case("full", FULL, "column", False, anchor))
    for anchor in ("top", "left", "right"):
        cases.append(overlay_case("full", FULL, "minimal", False, anchor))
    blob["overlay"] = cases
    blob["states"] = states

    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    with open(OUT, "w", encoding="utf-8") as fh:
        json.dump(blob, fh, ensure_ascii=False)
    print(f"wrote {len(blob['themes'])} themes and {len(cases)} overlays -> {OUT}")


def _lean_colour(key, lean):
    theme.set_theme(key)
    try:
        return gauges.lean_colour(lean)
    finally:
        theme.set_theme("night")


if __name__ == "__main__":
    main()
