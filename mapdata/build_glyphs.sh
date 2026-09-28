#!/usr/bin/env bash
# Build the SDF glyph PBFs the styles name, from Archivo - the display face of
# the Agedashii UI, so the map's street names are in the same type as the HUD.
#
# Archivo ships only as a variable font, so fontTools cuts three static weights
# (Medium 500, SemiBold 600, ExtraBold 800, all at wdth 100) and node-fontnik's
# build-glyphs turns each into 256 range files, 0-255.pbf ... 65280-65535.pbf.
# Folder names have no spaces: they appear verbatim in the {fontstack} URL.
#
# Licences (read from the files, not READMEs):
#   Archivo      OFL-1.1        google/fonts ofl/archivo/OFL.txt (shipped beside the glyphs)
#   fontTools    MIT            GitHub API, fonttools/fonttools
#   node-fontnik BSD-3-Clause   GitHub API, mapbox/node-fontnik
#
# Needs docker. Usage: build_glyphs.sh [OUT_DIR]   (default mapdata/out/glyphs)
set -euo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
OUT=${1:-$HERE/out/glyphs}
WORK=$HERE/out/fonts

FONTS_SHA=95f4904fc8bcf26d3420fe315560c96417c6dec7   # google/fonts, last change to ofl/archivo
FONT_SHA256=${FONT_SHA256:-}                          # set after the first verified download
FONTTOOLS=4.66.0
FONTNIK=0.7.7
PY_IMG=python:3.12-slim
NODE_IMG=node:22-bookworm
WEIGHTS="Medium:500 SemiBold:600 ExtraBold:800"

mkdir -p "$WORK" "$OUT"
base=https://raw.githubusercontent.com/google/fonts/$FONTS_SHA/ofl/archivo
curl -fsSL -o "$WORK/Archivo-VF.ttf" "$base/Archivo%5Bwdth,wght%5D.ttf"
curl -fsSL -o "$WORK/OFL.txt" "$base/OFL.txt"
got=$(sha256sum "$WORK/Archivo-VF.ttf" | cut -d' ' -f1)
echo "Archivo-VF.ttf sha256 $got"
if [ -n "$FONT_SHA256" ] && [ "$got" != "$FONT_SHA256" ]; then
  echo "font checksum mismatch: want $FONT_SHA256" >&2; exit 1
fi

docker run --rm -u "$(id -u):$(id -g)" -e HOME=/tmp -v "$WORK:/w" "$PY_IMG" sh -c "
  pip install -q --no-cache-dir --target /tmp/ft fonttools==$FONTTOOLS && cd /w &&
  for pair in $WEIGHTS; do n=\${pair%%:*}; w=\${pair##*:};
    PYTHONPATH=/tmp/ft python -m fontTools.varLib.instancer Archivo-VF.ttf wght=\$w wdth=100 -q -o Archivo-\$n.ttf; done"

docker run --rm -u "$(id -u):$(id -g)" -e HOME=/tmp -v "$WORK:/w" -v "$OUT:/o" "$NODE_IMG" sh -c "
  cd /tmp && npm install --silent --no-audit --no-fund fontnik@$FONTNIK >/dev/null &&
  for pair in $WEIGHTS; do n=\${pair%%:*}; mkdir -p /o/Archivo-\$n &&
    node node_modules/fontnik/bin/build-glyphs /w/Archivo-\$n.ttf /o/Archivo-\$n; done"

cp "$WORK/OFL.txt" "$OUT/OFL-Archivo.txt"
for pair in $WEIGHTS; do n=${pair%%:*}
  printf '%-18s %4s files %9s bytes\n' "Archivo-$n" "$(ls "$OUT/Archivo-$n" | wc -l)" "$(du -sb "$OUT/Archivo-$n" | cut -f1)"
done
echo "glyphs total $(du -sb "$OUT" | cut -f1) bytes"
