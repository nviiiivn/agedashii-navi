# mapdata - state at handover (minion 4 -> minion 3, 2026-09-27)

Vector tiles and per-theme styles for Agedashii Navi. Everything below was
measured or read; "believed" marks the rest. Built outputs live in `out/`
(gitignored) and on Tower at `~/mapdata-build/`.

## Tools in this folder
- `inspect_pmtiles.py` - reads REAL zoom range/bounds/tile counts from the
  directories, not the header; flags any disagreement. Hilbert decode checked
  against all 7 rows of the PMTiles v3 spec 4.1 table.
- `layer_values.py` - which layers/fields/values the tiles actually carry.
  Every filter in `schemas.py` was taken from its output.
- `strip_layers.py` - drops whole MVT layers from a .pmtiles, copying kept
  layers bit for bit. Exists because planetiler's `--exclude_layers` is
  silently ignored by the Protomaps profile (Basemap.java:43 calls the no-arg
  ForwardingProfile constructor, which hard-sets the list empty). Verified
  tile-by-tile on 3 archives: 0 differing tiles, leaf dirs included.
- `gen_styles.py` + `schemas.py` + `colour.py` - the 8 theme styles, from
  `themes.json` (extracted from theme.py by `--sync-from`; `--check-sync`
  verifies). The build FAILS unless every gate passes (WCAG / CIEDE2000; table
  in the docstring). Both schemas: 193/193 gate rows pass. All 16 styles pass
  `gl-style-validate` (@maplibre/maplibre-gl-style-spec 26.4.4).
- `build_glyphs.sh` - Archivo (OFL-1.1) Medium/SemiBold/ExtraBold -> SDF PBFs.
  894,288 B total, 3 x 256 range files.
- `preview/` - range-capable server + MapLibre GL JS page with a demo route.
  NOT YET LOOKED AT: the session died before the first screenshot.

## Decisions made and why
- `road` is the ROUTE colour (theme.py:34-36), so streets are a theme-tinted
  neutral solved to a contrast target below the route. `ROAD_TINT` (0.30) is
  the one taste knob. AGEDASHII approved this.
- Container: PMTiles, agreed with minion 1. MapLibre Android 13.6.1 reads
  `pmtiles://file://` only - NOT `asset://` (minion 1 verified at that tag).
- No POIs, house numbers, paths, boundaries or sprites in the map.

## Measured sizes (same extract, Bay Area -122.70,37.15,-121.70,38.15)
| archive | bytes |
|---|---|
| OpenMapTiles z14, all layers | 97,123,292 |
| OpenMapTiles z14, only what the style draws | 41,048,296 |
| + buildings | 72,323,902 |
| Protomaps z14, all layers | 59,115,734 |
| Protomaps z14, only what the style draws | 37,323,692 |
| + buildings | 57,303,346 |
| Protomaps z15, only what the style draws | 65,978,445 |

## Open
- Schema choice is nvii's (via AGEDASHII): OpenMapTiles needs a visible
  "(c) OpenMapTiles" credit (LICENSE.md:45-52, CC-BY-4.0 design); Protomaps
  needs only the OSM credit (design CC0). Protomaps is also smaller.
- Look at the preview before shipping any style. Shortbread not built.
- Extract source differs from routing (california 2026-08-30 vs Geofabrik
  norcal 2026-09-27); rebuild both from one file before release.
- No build_tiles.sh yet: the planetiler/Protomaps docker runs are in
  ~/mapdata-build/{omt,pm}-variants.sh on Tower, images pinned by digest.
