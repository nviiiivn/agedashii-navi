#!/usr/bin/env bash
# Phase 3 on her real phone: a 30-minute ride recorded with the screen locked.
#
#   tools/phone-phase3.sh setup <app-debug.apk> [adb serial]   before the ride
#   tools/phone-phase3.sh check [adb serial]                   after it
#
# `check` pulls the newest ride and its exports and runs tools/check-ride.py.
# A real ride is also the only test of lean_deg and g_lat: the emulator here
# cannot feed speed or bearing, so every emulator ride has them empty.
set -euo pipefail
cmd=${1:?setup or check}; shift
PKG=online.iamprettyfamous.agedashii.navikt
RIDES=/sdcard/Android/data/$PKG/files/rides
here=$(cd "$(dirname "$0")" && pwd)

case $cmd in
setup)
    APK=${1:?apk}; SERIAL=${2:-}; ADB="adb ${SERIAL:+-s $SERIAL}"
    $ADB install -r "$APK"
    $ADB shell pm grant $PKG android.permission.ACCESS_FINE_LOCATION
    $ADB shell pm grant $PKG android.permission.POST_NOTIFICATIONS || true
    $ADB shell am start -n $PKG/online.iamprettyfamous.navi.MainActivity >/dev/null
    echo "battery optimisation for Navi (want: not restricted):"
    $ADB shell dumpsys deviceidle whitelist | grep -i "$PKG" || echo "  not exempt - Android may still pause it; note this if the ride has gaps"
    cat <<'EOF'

On the bike:
  1. tap the round ● top left - it turns to ■ and "REC 0:00 · 0.0 mi" appears
  2. lock the screen and ride for at least 30 minutes
  3. unlock, tap ■, then tap ■ again within 3 seconds ("Tap ■ again to stop")
  4. "Saved N rows · CSV GPX JSON" means the exports were written
Then run:  tools/phone-phase3.sh check
EOF
    ;;
check)
    SERIAL=${1:-}; ADB="adb ${SERIAL:+-s $SERIAL}"
    LOG=$($ADB shell ls -t $RIDES | grep -m1 'jsonl$' | tr -d '\r')
    [ -n "$LOG" ] || { echo "no ride on the phone"; exit 1; }
    out=$(mktemp -d)
    for ext in jsonl csv gpx json; do $ADB pull "$RIDES/${LOG%.jsonl}.$ext" "$out/" >/dev/null 2>&1 || true; done
    echo "pulled to $out"
    python3 "$here/check-ride.py" "$out/$LOG" --minutes 30
    ;;
*) echo "setup or check"; exit 2 ;;
esac
