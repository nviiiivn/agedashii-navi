# Known, and not yet fixed

Things that are true of the app today, written down so they are not rediscovered
the hard way. Each says what, why it matters, and what would close it. Remove an
entry in the same commit that fixes it.

## Sensors on demand is verified on the emulator only

Not recording, only the sensors behind the visible gauges run, at ~16 Hz
(Hud.channels -> Telemetry.setDashChannels); recording runs all five at 50 Hz.
Verified per layout on emulator-5556 from the app's log AND dumpsys
sensorservice (Ribbon: barometer only; Corners/Column/Minimal: none; Gunsight:
accelerometer; Strip: accelerometer + barometer). What it saves on her phone's
battery has not been measured.

Closes when: an hour on her phone with the dash showing and nothing recording
costs noticeably less battery than before, or someone measures it.

## lean_deg and g_lat are untested anywhere but on a real ride

Tower's emulator ignores `adb emu geo nmea`, and `geo fix` reports speed 0 and
bearing 0. So in every emulator ride lean and cornering load are derived from
nothing, and a clean emulator result says nothing about them. The core's
arithmetic is proved against the Python by golden tests; the pipeline that feeds
it real fixes is not proved by anything yet.

Closes when: a real ride on her phone is checked (`tools/phone-phase3.sh check`)
and its lean figures look plausible for the roads ridden.

## A ride cut off by Android can lose its last few rows

If Android kills the process mid-ride, nothing restarts it (a location-type
foreground service cannot start from the background), and the next launch says
"Android closed Navi mid-ride". The log keeps everything except UP TO the core's
flush buffer's worth of rows - at most 5 plus the one held back - and how many
is timing-dependent: 15 of 15 were kept in one test, 10 of 15 in another.

Closes when: flushing is tied to time (every few seconds) as well as to row
count, or accepted as is.

## Elevation needs Android 14, and is only as good as GPS height

The dash's elevation is height above MEAN SEA LEVEL from Android 14's
AltitudeConverter (a geoid model applied to the GPS fix), and a dash on older
Android - never raw GPS height (~105 ft low around the Bay) and never absolute
barometric altitude (~8 m per hPa of weather). GPS vertical accuracy is worse
than horizontal (believed +-5-15 m, not measured). "Climb since start" uses
the barometer's relative altitude, baselined when the ride starts; the emulator's
pressure never changes, so that path is only tested on a real phone.

Closes when: checked on her phone against a known height (a summit marker), and
the Moto G's Android version is known (adb shell getprop ro.build.version.release).

## No map credits on screen, by her decision - they must come back before sharing

The OpenStreetMap (ODbL) credit is not on the map: nvii, 2026-09-28, "this is a
personal project ... i don't want it on my screen". The credits are kept as a
record (app/src/main/assets/styles/SOURCE.txt, glyphs/OFL-Archivo.txt, and the
style's own source attribution) but are shown nowhere.

Closes when: Settings/About shows them - required before the app goes to anyone
beyond her own phones.

## Buildings have no on-screen switch yet

The buildings layer ships hidden (default off, her choice) and
RideMapController.setBuildings() flips it, persisted as the "buildings" pref.
There is no Settings screen yet, so she cannot turn it on; only a debug-build
test intent can (`--ez buildings true`).

Closes when: Settings has the toggle.
