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

## Map credits are in Settings/About only, by her decision

The OpenStreetMap (ODbL) credit is not on the map: nvii, 2026-09-28, "this is a
personal project ... i don't want it on my screen". Settings > About lists OSM
(ODbL), Protomaps (CC0), MapLibre (BSD-2) and Archivo (OFL), and the record is
kept in app/src/main/assets/styles/SOURCE.txt and glyphs/OFL-Archivo.txt. Her
decision covers HER screen on HER phones; it does not travel with the APK.

Closes when: never, as such - before the app goes to anyone else, decide again
whether the map itself must carry the credit.

## Sunlight's warning colours clear their floor by 0.06 - check it outdoors

Over a black Sunlight road the dash glass (0.92) measures #ebebeb on the
emulator, which puts the over-limit red (bad) and warn at 7.06:1 against a
7:1 floor. That is not a margin: a different panel, gamma or brightness curve
can take it under. The other Sunlight roles have more room (accent 7.86, good
8.05). DashOverMapTest proves the arithmetic over the shipped style; nothing
has proved it on her screen in direct sun. Sunlight is also the theme minion
3's map README flags for an outdoor check, for the same reason.

Closes when: Sunlight is read on her phone outdoors, in sun, over a road - or
the glass goes denser (0.95 gives bad 7.52, computed) if it is marginal there.

## The rev counter is UNCALIBRATED on both bikes

rpm comes from the exhaust note with each profile's `harmonic` = 1.0 - the
assumption that the loudest peak is the firing frequency (two pulses per
revolution on the CB750's inline four). Which harmonic actually dominates
through her CB750's airbox and exhaust is a fact about HER bike, and nothing has
been fitted to it; the gauge reads "rpm est" and Settings says "uncalibrated
estimate". A reading stands at most 2 s past its last accepted window, then
blanks with the reason (core Exhaust.RPM_HOLD_S).

The emulator here has no audio input (it runs -no-audio), so it only ever hears
silence: the whole mic path has been seen to start, say "too quiet", log
loudness into the ride, switch off when the ride ends, and carry the microphone
foreground-service type - but it has never heard an engine.

Closes when: a recording of her bike at known revs sets `harmonic` and
`calibratedFrom` in core/bikes/Bikes.kt (one profile change, nothing else), and
the readout is checked against the bike's own tach at steady revs (phase 4's
done-test: within ~5%).

## Turn-by-turn has only been followed on the emulator, standing still

Long-press, "Route here", the route drawn under the labels' slot, the banner,
off-route and the re-route, "Route ends at ... from your pin", rotation and the
two-tap End were all seen on emulator-5556 (2026-09-28). But `geo fix` reports
speed 0, so every prompt band there is the crawl band: the 60/20/6 s timing at
riding speed has never run outside core's tests.

Starting more than 45 m from any road - a big parking lot - reads as off route:
the Follower measures the fix against the line, and the line starts on the
road. It re-routes every 12 s (RerouteGate) until she reaches the road, and says
so. guidance.py behaves the same way; nothing here is new.

Street-name labels draw under the route on purpose: the style's own
agedashii:route-below puts it above them so no halo cuts the line.

Closes when: a real ride follows a route on her phone and the prompts land
where core's tests say they do.
