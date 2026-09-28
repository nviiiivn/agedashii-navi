#!/bin/sh
# Stage her built-in voice into the APK's assets before a build.
#
# The clips are hers and are never committed (.gitignore); this copies them
# into app/src/main/assets/voice/, where the build picks them up. Run it before
# building the APK she installs, then CHECK THE APK, not this script's output:
#   unzip -v app/build/outputs/apk/debug/app-debug.apk | grep assets/voice/
# must list every clip, "Stored" (uncompressed, so they play as file descriptors).
#
#   tools/stage-voice.sh [clip folder]   default: /home/nvii/Sandbox/tmp/arnold-navi
set -eu
src="${1:-/home/nvii/Sandbox/tmp/arnold-navi}"
dst="$(dirname "$0")/../app/src/main/assets/voice"
[ -d "$src" ] || { echo "no clip folder at $src" >&2; exit 1; }
mkdir -p "$dst"
rm -f "$dst"/*
cp "$src"/*.mp3 "$src"/*.wav "$src"/*.m4a "$src"/*.aac "$src"/*.ogg "$src"/*.opus "$src"/*.flac "$dst"/ 2>/dev/null || true
n=$(ls "$dst" | wc -l)
[ "$n" -gt 0 ] || { echo "no clips found in $src" >&2; exit 1; }
echo "staged $n clips from $src into $dst"
