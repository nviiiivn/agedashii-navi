# mapdata - vector tiles and per-theme styles for Agedashii Navi

State as of 2026-09-28 (minion 3, continuing minion 4's handover at cced557).
"Verified" means a command was run or a file read, with the result below;
"believed" marks the rest. Built outputs live in `out/` (gitignored) and on
Tower in `~/mapdata-build/norcal/`.

## Build it

    # 1. on the Pi (osmium): cut the Bay Area from the SAME file routing uses
    SRC_PBF=~/Sandbox/tmp/navi-routing/work/src/norcal-latest.osm.pbf ./build_tiles.sh extract
    # 2. copy work/ and this folder to Tower, then (docker, 12 GB heap):
    ./build_tiles.sh tiles
    # finish = strip, check and style raw archives already built
    BUILDINGS=1 ./build_tiles.sh finish

`build_tiles.sh` pins planetiler by image digest and Protomaps basemaps by
commit, keeps exactly the layers the styles draw (`gen_styles.py
--list-layers`), REFUSES an archive whose real or header max zoom is not
`MAXZOOM` (14), writes the 8 styles only if every contrast gate passes, and
records inputs and outputs in `out/manifest.json`.

Verified on 2026-09-28: the extract stage is deterministic (script cut and a
manual cut both md5 4807e82c); a wrong `MAXZOOM=15` exits 1 with `REFUSED`;
a bad palette (route = border grey, a green in Red night) exits 1 from
gen_styles and writes nothing.

## Tools in this folder
- `build_tiles.sh` - the reproducible build, above.
- `inspect_pmtiles.py` - reads the REAL zoom range from PMTiles directories.
- `layer_values.py` - which layers/fields/values the tiles actually carry.
- `strip_layers.py` - drops whole MVT layers; needed because planetiler's
  `--exclude_layers` is silently ignored by the Protomaps profile.
- `gen_styles.py` + `schemas.py` + `colour.py` - the 8 theme styles from
  `themes.json` (= theme.py, checked with `--check-sync`). Gates in the
  docstring; labels are 4.5:1, and 7:1 in Sunlight (WCAG AAA, the same bar
  the dash uses there - agreed with minion 2).
- `build_glyphs.sh` - Archivo (OFL-1.1) glyph PBFs, 894,288 B (verified).
- `preview/` - MapLibre GL JS 6.11.2 preview. Fixed 2026-09-28: v6 ships an ES
  module only, so the old `<script src=.../maplibre-gl.js>` was a 404 and the
  page had never drawn anything.

## Measured (verified, out/manifest.json)
Extract: norcal-latest.osm.pbf (Geofabrik, md5 86cb34a3, OSM timestamp
2026-09-27T20:23:36Z - the routing tiles' source), cut at
-122.70,37.15,-121.70,38.15 -s smart, 177,287,345 B.

| archive, z0-14, layers the style draws | bytes | MiB |
|---|---|---|
| Protomaps | 37,694,300 | 35.9 |
| Protomaps + buildings | 57,925,216 | 55.2 |
| OpenMapTiles | 41,398,301 | 39.5 |
| OpenMapTiles + buildings | 72,982,669 | 69.6 |
| glyphs (3 weights) | 894,288 | 0.85 |
| 8 styles, Protomaps / OpenMapTiles (pretty JSON) | 1,614,476 / 598,626 | 1.5 / 0.6 |

With routing at 133 MiB (AGEDASHII's figure, believed), Protomaps without
buildings totals about 171 MiB; the largest option (OpenMapTiles + buildings)
about 204 MiB. All inside 300 MB.

## Looked at (2026-09-28, headless Chrome, SwiftShader WebGL)
All 16 styles render; READY, no errors, route drawn. The route stands clear in
every dark theme and in Day. Sunlight's route (dark blue) sits among heavy
BLACK streets and is told apart by its white casing and hue - it passes the
gate on CIEDE2000, but judge it on her phone in real sun, not on this render.
Software GL says a style RUNS; it says nothing about how it looks on her
screen. Fixed from the look: OpenMapTiles drew "CA CA 61" (18 of 877 state
refs at z14 are already prefixed in the data).

Second look, close up at z17 (prompted by minion 2 - "a screenshot outranks a
passing test"): the route was slotted below EVERY label, so the name of the
road it follows sat on it and its land-coloured halo bit boxes out of the
route line. Every contrast gate was green. The route now goes above the
labels that run along roads and below the point labels (`agedashii:route-below`
= label-neighbourhood), and a structural gate, `route/labels`, fails the build
if any line-placed label is ever above that slot (it reports the old slot as 2
labels over the route). 201/201 gate rows pass.

## The credit - a design cost (licences read from the LICENSE files)
- OpenMapTiles (openmaptiles/openmaptiles LICENSE.md, design CC-BY-4.0):
  "Products or services using maps derived from OpenMapTiles schema need to
  visibly credit ... the credit should appear in the corner of the map." No
  collapse is allowed; exceptions only by written grant from MapTiler.
- Protomaps (protomaps/basemaps LICENSE.md): design CC0, schema Tilezen (MIT).
  Only the data credit applies. (ESA WorldCover credit only if `landcover` is
  shown; it is stripped.)
- OSM (OSMF Attribution Guidelines): "the credit should typically appear in a
  corner of the map", but may collapse "automatically after five seconds" or
  on panning, if it stays findable "from an '(i)' button".
- Measured at a 412 px phone width, MapLibre GL JS default control:
  "© OpenStreetMap" 107 x 20 px (26% of the width), and it may fold into an
  (i) after 5 s. "© OpenMapTiles © OpenStreetMap contributors" 266 x 20 px
  (65% of the width), permanently.

## Decided by nvii (2026-09-28)
- PROTOMAPS, and no credit on her screen (personal, non-commercial, her own
  phone). The licence files and this provenance stay in the repo as the
  record; nothing grows into a credit strip in the UI.
- BUILDINGS SHIP IN THE TILES, HIDDEN BY DEFAULT, with a Settings switch -
  her improvement on our either/or. Final archive: out/protomaps.pmtiles,
  57,925,216 B, md5 d36570443e5b, z0-14, layers buildings, earth, landuse,
  places, roads, water.
- The switch contract, the same in both apps: ONE layer, id `buildings`
  (schemas.BUILDINGS_ID), in every theme, layout visibility "none" by default.
  Flip visibility and nothing else. Also in the style metadata as
  `agedashii:buildings-layer` / `agedashii:buildings-default`.
- Buildings are gated as scenery: `building/land` <= 1.4 (never as strong as
  the faintest street) and `navigable/bldg` >= 3.0 (a road she rides still
  clears 3:1 with buildings behind it). Shown failing on the unfixed tree:
  12 rows, down to 2.65:1. Fixed by placing buildings first - on the far side
  of land from the roads where the palette has room (Day: white buildings,
  which RAISE road contrast), otherwise lifting the navigable floor by the
  buildings' own contrast. 241/241 rows pass.
- Rendered: all 8 themes default (no buildings) and with the switch on via
  setLayoutProperty("buildings", "visibility", "visible").

## Known, not fixed
- MapLibre GL JS 6.11.2 (the PREVIEW renderer) draws no buildings in downtown
  Oakland at z16.0-16.5 (37.8030, -122.2712) with the switch on - 85 at
  z15.99, 55 at z17, and 69 / 91 elsewhere at z16.2. No error raised; the 48
  polygons are in the tile, byte-identical in the browser, and the raw
  unstripped archive behaves the same. Not tested on MapLibre Native, which is
  what the phone uses; minion 1 has the repro. Riding zoom is 16.5.
- The FLET app cannot draw any of this. flet_map 0.86.5 has a raster TileLayer
  only - no vector layer, no MapLibre style. Its tile choice must not claim
  "vector" and its Buildings switch must stay hidden; the themed vector map is
  the Kotlin app's.

## Open - for nvii via AGEDASHII, not decided here
- DECIDED (AGEDASHII, 2026-09-28), now in the gate as `NAVIGABLE`: the 3:1
  floor goes by NAVIGATIONAL LOAD. Motorway, trunk, primary and secondary -
  roads she rides or is told to turn onto - clear 3:1 in every theme (lowest
  now 3.02, Red night secondary). Tertiary, minor, service and track are
  context and keep the 1.4 floor (lowest 1.40). Cost, measured: in Day all four
  navigable classes now sit at the same contrast (told apart by width), and in
  Red night the route's lead over secondary narrows from 3.16:1 to 2.27:1.
- THE ONE THING THAT NEEDS AN OUTDOOR LOOK: Sunlight. Its dark-blue route
  runs among black streets and passes route-vs-street on CIEDE2000 (hue), NOT
  on luminance - and hue separation is the first thing lost behind a tinted
  visor in direct sun. Do not trust it at speed until it has been looked at
  on her phone, outdoors, visor down. Not changed on anyone's say-so.
- A Kotlin test asserting the shipped styles with core.theme.Contrast
  (minion 2's helper) - waits for the `dash` branch to merge.
