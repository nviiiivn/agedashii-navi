#!/usr/bin/env bash
# Phase 1 on her real phone: installs the APK, copies the map, turns airplane
# mode on, and opens Ride. What to look for is printed at the end; the emulator
# can only say the app RUNS, so this is the check that counts.
#
#   tools/phone-phase1.sh <app-debug.apk> <bayarea.pmtiles> [adb serial]
set -euo pipefail
APK=${1:?apk}; TILES=${2:?pmtiles}; SERIAL=${3:-}
ADB="adb ${SERIAL:+-s $SERIAL}"
PKG=online.iamprettyfamous.agedashii.navikt
DEST=/sdcard/Android/data/$PKG/files/map

$ADB install -r "$APK"
$ADB shell pm grant $PKG android.permission.ACCESS_FINE_LOCATION
# Open the app once BEFORE copying: a storage folder that adb makes first is
# one the app cannot read, and the map then looks missing (minion 3, x3).
$ADB shell am start -W -n $PKG/online.iamprettyfamous.navi.MainActivity >/dev/null
sleep 3
$ADB shell am force-stop $PKG
$ADB shell mkdir -p $DEST
$ADB push "$TILES" $DEST/bayarea.pmtiles
echo "APK permissions (INTERNET must NOT be listed):"
$ADB shell dumpsys package $PKG | grep -E 'android.permission.(INTERNET|ACCESS_FINE)' || true
$ADB shell cmd connectivity airplane-mode enable || echo "turn airplane mode on by hand"
echo "airplane_mode_on=$($ADB shell settings get global airplane_mode_on)"
$ADB shell am start -n $PKG/online.iamprettyfamous.navi.MainActivity
cat <<'EOF'

Phase 1 is done when, OUTDOORS with airplane mode on:
  1. the map draws (roads, water, parks) with no "No map file" message
  2. "Waiting for GPS" goes away within a minute or two
  3. the camera zooms in to street level on her bike and follows as she moves
  4. the map turns so the way she is going is up, with the pointer on top
  5. dragging the map stops following; the round button bottom right brings it back
  6. the Y/B button swaps the yellow and black bike
EOF
