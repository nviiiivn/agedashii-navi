#!/usr/bin/env bash
# Build Agedashii Navi's Bay Area vector tiles, both schemas, from one OSM file.
#
#   SRC_PBF=/path/norcal-latest.osm.pbf ./build_tiles.sh extract   # needs osmium
#   ./build_tiles.sh tiles                                          # needs docker
#
# `extract` cuts the Bay Area out of the SAME Geofabrik file the routing tiles
# are built from, so the map and the router describe one version of the roads.
# `tiles` runs planetiler for each schema, keeps exactly the layers the styles
# draw, refuses an archive whose real zoom range is not what it claims, writes
# the eight theme styles (which refuse to be written if a contrast gate fails),
# and records every input and output in out/manifest.json.
#
# The two halves run on different machines here: osmium is on the Pi, docker
# and the memory are on Tower. Copy work/sources/bayarea.osm.pbf and
# work/extract.json across between them. Everything else is in this folder.
#
# Why each pin:
#   PLANETILER_IMAGE  by digest, not tag - a tag can move under a rebuild
#   PROTOMAPS_COMMIT  the basemaps repo has no release images; built from source
#   MAXZOOM=14        both schemas' own ceiling. The app overzooms past it; the
#                     archive and the style both SAY 14, which is the point -
#                     the raster map blanked at riding zoom because a layer
#                     assumed z19 over an archive that stopped at z15.
#   BUILDINGS=0       buildings cost about +20 MB (Protomaps) / +31 MB
#                     (OpenMapTiles), measured by minion 4; BUILDINGS=1 keeps them.
set -euo pipefail

HERE=$(cd "$(dirname "$0")" && pwd)
BOUNDS=${BOUNDS:--122.70,37.15,-121.70,38.15}
MAXZOOM=${MAXZOOM:-14}
WORK=${WORK:-$HERE/work}
OUT=${OUT:-$HERE/out}
JAVA_MEM=${JAVA_MEM:-12g}
SCHEMAS=${SCHEMAS:-protomaps openmaptiles}
BUILDINGS=${BUILDINGS:-0}
PLANETILER_IMAGE=ghcr.io/onthegomap/planetiler@sha256:cf32202dbc001a9ab4bc11534b642b13de3798179817da8558e567a3d13dd403
PROTOMAPS_REPO=https://github.com/protomaps/basemaps.git
PROTOMAPS_COMMIT=42ffaaa4a85a41bfcb23e43cc0f5b492a5eca123
PROTOMAPS_IMAGE=mapdata-protomaps:$PROTOMAPS_COMMIT
EXTRACT=$WORK/sources/bayarea.osm.pbf

say() { printf '\n== %s\n' "$*"; }
die() { printf 'build_tiles: %s\n' "$*" >&2; exit 1; }

cmd_extract() {
    : "${SRC_PBF:?set SRC_PBF to the Geofabrik extract the routing tiles use}"
    command -v osmium >/dev/null || die "osmium is not installed here"
    mkdir -p "$WORK/sources"
    say "cutting $BOUNDS out of $SRC_PBF"
    # Same strategy flags as the routing cut, so a way crossing the edge is
    # kept whole and water and park polygons close.
    osmium extract --overwrite -b "$BOUNDS" -s smart -S types=multipolygon,boundary \
        -o "$EXTRACT" "$SRC_PBF"
    python3 - "$SRC_PBF" "$EXTRACT" "$BOUNDS" > "$WORK/extract.json" <<'PY'
import hashlib, json, os, subprocess, sys
src, dst, bounds = sys.argv[1:4]
def md5(p):
    h = hashlib.md5()
    with open(p, "rb") as f:
        for b in iter(lambda: f.read(1 << 20), b""):
            h.update(b)
    return h.hexdigest()
stamp = subprocess.run(["osmium", "fileinfo", "-g", "header.option.osmosis_replication_timestamp", src],
                       capture_output=True, text=True).stdout.strip()
print(json.dumps({"source": os.path.basename(src), "source_bytes": os.path.getsize(src),
                  "source_md5": md5(src), "source_osm_timestamp": stamp, "bounds": bounds,
                  "extract_bytes": os.path.getsize(dst), "extract_md5": md5(dst)}, indent=1))
PY
    cat "$WORK/extract.json"
}

protomaps_image() {
    docker image inspect "$PROTOMAPS_IMAGE" >/dev/null 2>&1 && return
    say "building $PROTOMAPS_IMAGE from source"
    [ -d "$WORK/src-protomaps/.git" ] || git clone "$PROTOMAPS_REPO" "$WORK/src-protomaps"
    git -C "$WORK/src-protomaps" fetch -q origin
    git -C "$WORK/src-protomaps" checkout -q "$PROTOMAPS_COMMIT"
    docker build -t "$PROTOMAPS_IMAGE" "$WORK/src-protomaps/tiles"
}

# planetiler needs Natural Earth, the coastline polygons and (for OpenMapTiles)
# lake centrelines. Downloaded once into work/sources and reused, so a rebuild
# does not quietly pick up a newer coastline; delete them to refresh.
download_flag() {
    [ -s "$WORK/sources/water-polygons-split-3857.zip" ] \
        && [ -s "$WORK/sources/natural_earth_vector.sqlite.zip" ] && return
    echo --download
}

build_raw() {
    local schema=$1 raw=$WORK/$1-raw.pmtiles
    say "planetiler: $schema, z0-$MAXZOOM"
    case $schema in
        protomaps)
            protomaps_image
            docker run --rm -e JAVA_TOOL_OPTIONS="-Xmx$JAVA_MEM" -v "$WORK:/tiles/data" \
                "$PROTOMAPS_IMAGE" --area=bayarea --bounds="$BOUNDS" --maxzoom="$MAXZOOM" \
                --output="data/$schema-raw.pmtiles" --force $(download_flag) ;;
        openmaptiles)
            docker run --rm -e JAVA_TOOL_OPTIONS="-Xmx$JAVA_MEM" -v "$WORK:/data" \
                "$PLANETILER_IMAGE" --osm-path=/data/sources/bayarea.osm.pbf \
                --download_dir=/data/sources --bounds="$BOUNDS" --maxzoom="$MAXZOOM" \
                --output="/data/$schema-raw.pmtiles" --force $(download_flag) ;;
        *) die "unknown schema $schema" ;;
    esac
    [ -s "$raw" ] || die "$schema: planetiler wrote nothing"
}

finish() {
    local schema=$1 raw=$WORK/$1-raw.pmtiles final=$OUT/$1.pmtiles keep have drop
    keep=$(python3 "$HERE/gen_styles.py" --schema "$schema" --list-layers)
    if [ "$BUILDINGS" = 0 ]; then
        keep=$(echo "$keep" | tr , '\n' | grep -vxE 'building|buildings' | paste -sd,)
    fi
    have=$(python3 "$HERE/inspect_pmtiles.py" "$raw" --json \
        | python3 -c 'import json,sys; print(",".join(json.load(sys.stdin)["vector_layers"]))')
    drop=$(comm -23 <(echo "$have" | tr , '\n' | sort) <(echo "$keep" | tr , '\n' | sort) | paste -sd,)
    say "$schema: keep [$keep]  drop [$drop]"
    if [ -n "$drop" ]; then
        # planetiler's --exclude_layers is ignored by the Protomaps profile
        # (see strip_layers.py), so both schemas are stripped the same way.
        python3 "$HERE/strip_layers.py" "$raw" "$final" --drop "$drop"
    else
        cp "$raw" "$final"
    fi
    say "$schema: checking the archive says what it holds"
    python3 "$HERE/inspect_pmtiles.py" "$final" --json | python3 -c '
import json, sys
r = json.load(sys.stdin); want = int(sys.argv[1])
if r["problems"]:
    sys.exit("REFUSED: " + "; ".join(r["problems"]))
real, head, layers = r["real"]["max_zoom"], r["header"]["max_zoom"], r["vector_layers"]
if real != want or head != want:
    sys.exit(f"REFUSED: max zoom real {real} header {head}, wanted {want}")
print(f"ok: real max zoom {real}, header agrees, layers {layers}")
' "$MAXZOOM"
    say "$schema: styles (a failed contrast gate stops the build here)"
    python3 "$HERE/gen_styles.py" --schema "$schema" --tiles "$final" --out "$OUT/styles-$schema" \
        | tail -1
}

manifest() {
    python3 - "$WORK" "$OUT" "$PLANETILER_IMAGE" "$PROTOMAPS_COMMIT" "$MAXZOOM" "$BUILDINGS" $SCHEMAS \
        > "$OUT/manifest.json" <<'PY'
import hashlib, json, pathlib, sys
work, out, image, commit, maxzoom, buildings, *schemas = sys.argv[1:]
work, out = pathlib.Path(work), pathlib.Path(out)
def md5(p):
    h = hashlib.md5()
    with open(p, "rb") as f:
        for b in iter(lambda: f.read(1 << 20), b""):
            h.update(b)
    return h.hexdigest()
def tree_bytes(p):
    return sum(f.stat().st_size for f in p.rglob("*") if f.is_file()) if p.exists() else None
extract = json.loads((work / "extract.json").read_text()) if (work / "extract.json").exists() else None
sources = {f.name: f.stat().st_size for f in sorted((work / "sources").glob("*"))
           if f.is_file() and f.name != "bayarea.osm.pbf"}
m = {"extract": extract, "planetiler_image": image, "protomaps_commit": commit,
     "maxzoom": int(maxzoom), "buildings": buildings == "1", "downloaded_sources_bytes": sources,
     "glyphs_bytes": tree_bytes(out / "glyphs"), "schemas": {}}
for s in schemas:
    f = out / f"{s}.pmtiles"
    m["schemas"][s] = {"pmtiles_bytes": f.stat().st_size, "pmtiles_md5": md5(f),
                       "raw_bytes": (work / f"{s}-raw.pmtiles").stat().st_size,
                       "styles_bytes": tree_bytes(out / f"styles-{s}")}
print(json.dumps(m, indent=1))
PY
    say "manifest"; cat "$OUT/manifest.json"
}

cmd_tiles() {
    command -v docker >/dev/null || die "docker is not installed here"
    [ -s "$EXTRACT" ] || die "no extract at $EXTRACT - run '$0 extract' (or copy it here)"
    mkdir -p "$OUT"
    for schema in $SCHEMAS; do
        build_raw "$schema"
        finish "$schema"
    done
    manifest
}

case ${1:-} in
    extract) cmd_extract ;;
    tiles) cmd_tiles ;;
    finish) mkdir -p "$OUT"; for s in $SCHEMAS; do finish "$s"; done; manifest ;;
    *) die "usage: $0 extract|tiles|finish   (finish = strip, check and style existing raw archives)" ;;
esac
