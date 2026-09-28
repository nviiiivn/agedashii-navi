#!/usr/bin/env python3
"""One MapLibre style per Agedashii Navi theme, refused if it is not legible.

Every colour on the map is derived from the theme's eleven palette keys
(themes.json, extracted from apps/navi/agedashii_navi/theme.py), so the map
wears the same theme as the chrome around it. Nothing is picked by eye.

The route is not drawn here - the app adds it as its own GeoJSON layer - but
the style reserves the slot and is built around it. `road` is the ROUTE colour
(theme.py:34-36: "what a route is drawn in on the map ... a route has to stand
off tiles that are themselves already coloured"), so streets are never painted
in it at full strength or the route would vanish into the street network.
Streets instead take a theme-tinted neutral, dimmed until each class sits at a
target contrast against the land that leaves room for the route above it.

GATES - the build writes nothing and exits 1 if any of these fail, per theme:
  route/land      the route against land                  WCAG >= 4.5
  navigable/land  motorway, trunk, primary, secondary     WCAG >= 3.0  (WCAG 2.1 SC 1.4.11)
  street/land     every other drawn street class          WCAG >= 1.4
Which side of 3.0 a road class sits on is decided by NAVIGATIONAL LOAD (see
NAVIGABLE): a road she might ride on or be told to turn onto must be legible;
a road that is only there so the map looks like a place need not be.
  route/street    the route against every street class    WCAG >= 1.7 or CIEDE2000 >= 20
  label/halo      every label against its halo            WCAG >= 4.5  (WCAG 2.1 SC 1.4.3)
  water/land      water against land                      CIEDE2000 >= 4
  red-only        Red night: every colour in the style a red, or black
Luminance separates route from street in the single-hue themes, where hue
cannot; CIEDE2000 separates them in the light themes, where a saturated route
and a neutral street can share a luminance.

ROAD_TINT is the one knob for taste: 0 gives neutral grey streets, 1 gives
streets in the road hue (still dimmed to their targets, so the route keeps its
margin - the gates re-check whichever value is set).

    python3 gen_styles.py --schema protomaps --tiles out/bayarea.pmtiles
    python3 gen_styles.py --report              # gate tables only, writes nothing
    python3 gen_styles.py --sync-from .../agedashii_navi/theme.py

The written styles carry placeholders the app substitutes at load time:
  {TILES}   the source url, e.g. pmtiles://file:///.../bayarea.pmtiles
  {GLYPHS}  the glyph root, e.g. asset://glyphs  (followed by the literal
            /{fontstack}/{range}.pbf, which MapLibre fills in)
"""
import argparse
import ast
import json
import pathlib
import sys

from colour import contrast, delta_e2000, hue_deg, luminance, mix
import schemas

HERE = pathlib.Path(__file__).resolve().parent

ROAD_TINT = 0.30
ROUTE_MARGIN = 1.7        # route/street WCAG the street targets are solved for
MOTORWAY_CAP = 4.5        # motorway never brighter than this against land
STREET_FLOOR = 1.4        # no drawn street fainter than this

#: Street contrast against land, as a fraction of the motorway's.
STREET_SCALE = {"motorway": 1.00, "trunk": 1.00, "primary": 0.85,
                "secondary": 0.72, "tertiary": 0.64, "minor": 0.54,
                "service": 0.42, "track": 0.42}

#: THE RULE for the 3:1 floor is NAVIGATIONAL LOAD, not road size. A class is in
#: here if she might be riding on it or be told to turn onto it; then it must
#: clear WCAG 1.4.11's 3:1 against land in every theme. A class that is only
#: there so the map looks like a place stays out, and gets only STREET_FLOOR -
#: those are what the route and the majors are meant to dominate.
#:
#: Secondary is IN on purpose: around the Bay it is frequently the actual ride
#: (Skyline, Grizzly Peak), not background. It measured 2.17 in Day, Red night
#: and Pink before this rule, and primary 2.58 in Day. AGEDASHII's ruling,
#: 2026-09-28. Adding a class? Ask which side of that sentence it is on.
NAVIGABLE = ("motorway", "trunk", "primary", "secondary")

#: theme.py:75-76 - "Direct sun, visor down. Maximum contrast, no mid-tones,
#: and the heaviest text weight the widgets offer." The map follows: black
#: streets, no fills but water, heaviest label weight, everything wider.
MAX_CONTRAST = {"sunlight"}
#: theme.py:83-91 - rods are nearly blind to deep red; one non-red pixel
#: undoes the point. Gated by `red-only`.
RED_ONLY = {"redshift"}

FONT = {"medium": "Archivo-Medium", "semibold": "Archivo-SemiBold",
        "heavy": "Archivo-ExtraBold"}

GATES = {"route/land": 4.5, "navigable/land": 3.0, "street/land": STREET_FLOOR,
         "route/street WCAG": ROUTE_MARGIN, "route/street dE00": 20.0,
         "label/halo": 4.5, "water/land dE00": 4.0}
#: Sunlight's labels answer to WCAG AAA (SC 1.4.6, 7:1), the same bar the
#: dash holds its readouts to in that theme - one set of thresholds across the
#: map and the chrome, agreed with the dash (minion 2).
SUN_LABEL = 7.0


def label_need(key):
    return SUN_LABEL if key in MAX_CONTRAST else GATES["label/halo"]


def solve_toward(land, src, target):
    """The mix of land -> src whose contrast against land is `target`.

    If src itself is fainter than the target, src is returned - a palette
    colour is never pushed past itself.
    """
    if contrast(src, land) <= target:
        return src
    lo, hi = 0.0, 1.0
    for _ in range(40):
        m = (lo + hi) / 2
        if contrast(mix(land, src, m), land) < target:
            lo = m
        else:
            hi = m
    return mix(land, src, hi)


def readable(candidates, halo, need=GATES["label/halo"]):
    """First candidate that clears the label gate against `halo`, else the
    strongest one (the gate will then report it)."""
    for c in candidates:
        if contrast(c, halo) >= need:
            return c
    return max(candidates, key=lambda c: contrast(c, halo))


def derive(key, theme):
    """Every map colour for one theme, plus the numbers the gates read."""
    p, dark = theme["palette"], theme["dark"]
    land, route = p["bg"], p["road"]
    c = {"land": land, "route": route, "route_casing": land}
    sun = key in MAX_CONTRAST

    # route/street = route/land / street/land exactly (luminance ratios chain),
    # so the motorway sits 2% inside the route margin; the navigable/land floor
    # wins if the palette leaves no room (Day: route/land is only 5.23).
    route_land = contrast(route, land)
    nav_floor = GATES["navigable/land"] * 1.005
    motorway_target = max(nav_floor, min(MOTORWAY_CAP, route_land / (ROUTE_MARGIN * 1.02)))
    # Every NAVIGABLE class is lifted to the 3:1 floor where its share of the
    # motorway's contrast would leave it below; the rest keep STREET_FLOOR.
    targets = {cls: max(nav_floor if cls in NAVIGABLE else STREET_FLOOR, motorway_target * s)
               for cls, s in STREET_SCALE.items()}
    street_src = mix(p["text"], route, ROAD_TINT)
    for cls, tgt in targets.items():
        if sun:
            c[cls] = p["text"] if cls in ("motorway", "trunk", "primary", "secondary", "tertiary") else p["text3"]
        else:
            c[cls] = solve_toward(land, street_src, tgt)

    c["water"] = solve_toward(land, p["accent"], 1.25 if dark else 1.18)
    c["shore"] = p["text3"] if sun else None
    c["green"] = None if sun else solve_toward(land, p["good"], 1.10)
    c["building"] = None if sun else solve_toward(land, p["text3"], 1.14)
    c["aeroway"] = solve_toward(land, p["text3"], 1.35)
    c["rail"] = p["text3"] if sun else solve_toward(land, p["text3"], 1.7)
    c["waterway"] = solve_toward(land, p["accent"], 1.6)

    halo = land
    need = label_need(key)
    c["halo"] = halo
    c["label_major"] = readable([p["text"]], halo, need)
    c["label_minor"] = readable([p["text2"], p["text"]], halo, need)
    c["label_place"] = readable([p["text"]], halo, need)
    c["label_suburb"] = readable([p["text2"], p["text"]], halo, need)
    c["label_water_halo"] = c["water"]
    c["label_water"] = readable([mix(p["text3"], p["accent"], 0.4), p["text2"], p["text"]],
                                c["water"], need)
    # Ref labels ("I-880") sit on the motorway itself, but a halo in the
    # motorway's mid-tone failed label/halo in six themes; the land halo cuts
    # a clean gap in the line instead.
    c["label_ref"] = readable([p["text"]], halo, need)
    c["label_ref_halo"] = halo
    return c, {"route_land": route_land, "street_targets": targets}


def gate(key, c):
    """[(check, subject, value, need, ok)] for one theme."""
    rows = []

    def add(check, subject, value, need):
        rows.append((check, subject, round(value, 2), need, value >= need))

    add("route/land", "route", contrast(c["route"], c["land"]), GATES["route/land"])
    for cls in STREET_SCALE:
        check = "navigable/land" if cls in NAVIGABLE else "street/land"
        add(check, cls, contrast(c[cls], c["land"]), GATES[check])
    for cls in STREET_SCALE:
        w, d = contrast(c["route"], c[cls]), delta_e2000(c["route"], c[cls])
        rows.append(("route/street", cls, f"WCAG {w:.2f} / dE00 {d:.1f}",
                     f">= {GATES['route/street WCAG']} or >= {GATES['route/street dE00']:.0f}",
                     w >= GATES["route/street WCAG"] or d >= GATES["route/street dE00"]))
    for lab, halo in (("label_major", "halo"), ("label_minor", "halo"), ("label_place", "halo"),
                      ("label_suburb", "halo"), ("label_water", "label_water_halo"),
                      ("label_ref", "label_ref_halo")):
        add("label/halo", lab, contrast(c[lab], c[halo]), label_need(key))
    add("water/land dE00", "water", delta_e2000(c["water"], c["land"]), GATES["water/land dE00"])
    return rows


def is_red_or_black(h):
    hue, sat = hue_deg(h)
    return luminance(h) < 0.002 or (sat < 0.02 and luminance(h) < 0.01) or hue <= 20 or hue >= 340


def all_colours(style):
    """Every #rrggbb string anywhere in the layers - what red-only must see."""
    out = []

    def walk(v):
        if isinstance(v, str) and v.startswith("#") and len(v) == 7:
            out.append(v)
        elif isinstance(v, dict):
            for x in v.values():
                walk(x)
        elif isinstance(v, list):
            for x in v:
                walk(x)
    walk(style["layers"])
    walk(style["metadata"]["agedashii:route"])
    return out


def build(key, theme, schema, tiles_info):
    c, numbers = derive(key, theme)
    sun = key in MAX_CONTRAST
    wide = 1.3 if sun else 1.0
    layers = schema.layers(c, FONT, wide=wide, sun=sun)
    if tiles_info:
        # A layer whose source-layer the archive does not carry draws nothing
        # and says nothing; leave it out so the style lists what is really there.
        have = set(tiles_info["layers"])
        layers = [l for l in layers if "source-layer" not in l or l["source-layer"] in have]
    first_label = next(l["id"] for l in layers if l["type"] == "symbol")
    source = {"type": "vector", "url": "{TILES}", "attribution": schema.ATTRIBUTION}
    if tiles_info:
        source.update(minzoom=tiles_info["min_zoom"], maxzoom=tiles_info["max_zoom"],
                      bounds=tiles_info["bounds"])
    style = {
        "version": 8,
        "name": f"Agedashii Navi - {theme['name']}",
        "metadata": {
            "agedashii:theme": key,
            "agedashii:schema": schema.NAME,
            "agedashii:route-below": first_label,
            "agedashii:route": {
                "line-color": c["route"], "casing-color": c["route_casing"],
                "line-width": schema.width("motorway", 1.25 * wide),
                "casing-extra-px": 3,
                "note": "draw the route as a GeoJSON line layer added below agedashii:route-below",
            },
            "agedashii:road-tint": ROAD_TINT,
            "agedashii:palette": theme["palette"],
            "agedashii:tiles": tiles_info,
            "agedashii:generator": "mapdata/gen_styles.py",
        },
        "sources": {schema.SOURCE: source},
        "glyphs": "{GLYPHS}/{fontstack}/{range}.pbf",
        "layers": [{"id": "background", "type": "background",
                    "paint": {"background-color": c[schema.BACKGROUND]}}] + layers,
    }
    rows = gate(key, c)
    if key in RED_ONLY:
        bad = sorted({h for h in all_colours(style) if not is_red_or_black(h)})
        rows.append(("red-only", "every style colour", f"{len(bad)} non-red {bad[:4]}", "0", not bad))
    return style, rows, numbers


def sync_from(theme_py):
    tree = ast.parse(pathlib.Path(theme_py).read_text())
    found = {}
    for node in tree.body:
        if isinstance(node, ast.Assign) and getattr(node.targets[0], "id", None) in (
                "THEMES", "THEME_NAMES", "DARK_THEMES"):
            found[node.targets[0].id] = ast.literal_eval(node.value)
    themes = {k: {"name": found["THEME_NAMES"][k], "dark": k in found["DARK_THEMES"], "palette": v}
              for k, v in found["THEMES"].items()}
    return {"_source": "apps/navi/agedashii_navi/theme.py THEMES / THEME_NAMES / DARK_THEMES, "
                       "extracted with ast.literal_eval by gen_styles.py --sync-from; do not hand-edit",
            "themes": themes}


def tiles_info_from(path):
    from inspect_pmtiles import inspect
    r = inspect(str(path))
    if r["problems"]:
        sys.exit(f"{path}: {'; '.join(r['problems'])}")
    return {"file": pathlib.Path(path).name, "min_zoom": r["real"]["min_zoom"],
            "max_zoom": r["real"]["max_zoom"], "bounds": r["header"]["bounds"],
            "layers": r["vector_layers"]}


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--schema", choices=sorted(schemas.ADAPTERS), default="protomaps")
    ap.add_argument("--out", default=str(HERE / "styles"))
    ap.add_argument("--tiles", help="built .pmtiles: bake its REAL zoom range and bounds into the source")
    ap.add_argument("--themes", default=str(HERE / "themes.json"))
    ap.add_argument("--report", action="store_true", help="print the gate tables, write nothing")
    ap.add_argument("--sync-from", metavar="THEME_PY")
    ap.add_argument("--check-sync", metavar="THEME_PY", help="exit 1 if themes.json differs from theme.py")
    ap.add_argument("--list-layers", action="store_true",
                    help="print the source-layers the styles draw, comma-separated, and exit - "
                         "build_tiles.sh keeps exactly these and strips the rest")
    a = ap.parse_args()

    if a.list_layers:
        themes = json.loads(pathlib.Path(a.themes).read_text())["themes"]
        schema = schemas.ADAPTERS[a.schema]
        used = set()
        for key, theme in themes.items():
            c, _numbers = derive(key, theme)
            sun = key in MAX_CONTRAST
            used |= {l["source-layer"] for l in schema.layers(c, FONT, wide=1.3 if sun else 1.0, sun=sun)
                     if "source-layer" in l}
        print(",".join(sorted(used)))
        return

    if a.sync_from:
        pathlib.Path(a.themes).write_text(json.dumps(sync_from(a.sync_from), indent=2) + "\n")
        print(f"wrote {a.themes}")
        return
    themes = json.loads(pathlib.Path(a.themes).read_text())
    if a.check_sync:
        if sync_from(a.check_sync)["themes"] != themes["themes"]:
            sys.exit(f"themes.json is out of date with {a.check_sync}: run --sync-from")
        print("themes.json matches theme.py")

    schema = schemas.ADAPTERS[a.schema]
    tiles_info = tiles_info_from(a.tiles) if a.tiles else None
    failed, out = [], {}
    for key, theme in themes["themes"].items():
        style, rows, numbers = build(key, theme, schema, tiles_info)
        out[key] = style
        print(f"\n{theme['name']} ({key})  route/land {numbers['route_land']:.2f}  "
              f"motorway target {numbers['street_targets']['motorway']:.2f}")
        for check, subject, value, need, ok in rows:
            print(f"  {'ok  ' if ok else 'FAIL'} {check:15s} {subject:18s} {value!s:26s} need {need}")
            if not ok:
                failed.append(f"{key}: {check} {subject} {value}")
    if failed:
        print("\nGATE FAILED - nothing written:\n  " + "\n  ".join(failed))
        sys.exit(1)
    if a.report:
        return
    outdir = pathlib.Path(a.out)
    outdir.mkdir(parents=True, exist_ok=True)
    for key, style in out.items():
        (outdir / f"{key}.json").write_text(json.dumps(style, indent=1, ensure_ascii=False) + "\n")
    print(f"\nall gates passed; wrote {len(out)} styles to {outdir}")


if __name__ == "__main__":
    main()
