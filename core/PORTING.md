# :core - the maths, ported from apps/navi

Pure Kotlin/JVM, no Android imports (`grep -rn '^import android' core/src` is empty),
so `./gradlew :core:test` runs on a laptop or the Pi with no SDK. 211 tests.

The Python is the specification. Where the spec artifact and the Python disagreed,
the Python was ported and the disagreement reported to AGEDASHII.

## Ported from (read-only; apps/navi is untracked in git, so md5 is the version)

| Python | md5 | Kotlin |
|---|---|---|
| agedashii_navi/bikes.py | e7f27445 | `bikes.Bikes` |
| agedashii_navi/gears.py | 7031e120 | `gears.Gears`, `GearEstimate` |
| agedashii_navi/dsp.py | b471a7b2 | `rpm.Dsp`, `rpm.Fft` (replaces the hand DFT) |
| agedashii_navi/audio.py | 81ed2be4 | `rpm.Exhaust` (analyse, PCM, loudness), `rpm.ExhaustListener` (on_stream state) |
| agedashii_navi/telemetry.py | f3a7163e | `telemetry.Physics`, `Channels`, `Ride` |
| agedashii_navi/sensors.py | 11da116b | `telemetry.Motion` |
| agedashii_navi/live.py | 8680cc85 | `telemetry.GpsClimb`, `Climb`, `Live`, `guidance.RerouteGate` |
| agedashii_navi/guidance.py | 547c20f3 | `guidance.Guidance`, `Follower`, `Status` |
| washi routing.py:894-1351 | faff1245 | `geo.Geo`, `geo.RouteMath` (segment projection, not the vertex scan) |
| agedashii_navi/daylight.py | 1fea8625 | `daylight.Daylight` |
| agedashii_navi/places.py:57-133, 291-331 | 5baa7e78 | `places.Places` |

`Py` reproduces Python's `round`, `repr`/`str` and `f"{x:.Nf}"`; `PyJson` reproduces
`json.dumps`/`json.loads`. Both exist so logs, exports and spoken distances are the
same text the Python produced.

## Golden tests: the Python's own outputs

`tools/golden_*.py` run the Python and write `core/src/test/resources/golden/*.json`;
the Kotlin tests replay the same inputs and compare. Re-run all five after any change
to the Python, with the flet venv:

    N=/home/nvii/dashboredapp-server/NVII-DASH
    for g in golden_py golden_rpm golden_route golden_places golden_telemetry; do
      PYTHONPATH=$N/apps/navi:$N/packages/agedashii-washi \
        /home/nvii/venvs/flet/bin/python tools/$g.py
    done

What they pin: 946 values through round/repr/format; 15 exhaust windows (hz, rpm,
reason, contrast, level, bands) and the FFT bin-for-bin within 1e-9 of the peak; 404
positions through progress / next_maneuver / remaining within 1e-12; six whole rides
through the Follower, fix for fix, same words at the same moments; 280 sun altitudes
within 1e-6 degrees and 21 sunrise/sunset pairs; 41 pasted places and 13 GPX files;
two whole rides through Ride with the JSONL log, CSV, GPX and JSON byte-identical.

**UNVERIFIED: no golden has yet been seen to fail.** A suite that has only ever
passed proves it runs, not that it would catch a change. The check - break one
constant on purpose (MIN_CONTRAST, or one gear ratio), watch the golden test fail,
put it back - was declined at the permission prompt on 2026-09-27 and has not been
done. Until it is, read the green as "matches the Python on these inputs", not as
"would catch any regression".

## Tests ported

test_core.py: HeadingMaths, RideRecording, Exports, LogOnDisk, TheEngineMaths,
FindingTheEngine, TheSignalProcessing, TheSun, OffRouteHysteresis, Prompts, Wording,
SpeedLimits, ParsingAPlace, ImportingGpx, GpxImportDoesNotCareAboutAttributeOrder,
OffRouteIsMeasuredToTheLine.
test_shell.py: TheGearEstimate, SensorPlumbing (sensor and audio halves), LiveState
(GPS climb, compass fallback, magnetised mount, knock text).
test_road_test.py: EveryChannelReachesTheLogAndTheGpx, TheWholeRideIsSent (as
"a ride longer than the window is rebuilt whole" - the upload itself is the app's).

## Tests deliberately dropped

test_core.py:233-253 RpmFromTheExhaustNote (3 tests) with `telemetry.rpm_from_samples`
and `Ride.feed_audio`. Nothing outside tests called them, and they are the old fixed
20-200 Hz twin detector that bikes.py:13-20 documents as a bug: measured, a CB750 at
9000 rpm reads 5977, and a true 4000 reads 7969 with its default arguments.

Not in scope, and not ported: prefs, theme, tiles, tile server, the Flet shell tests.

## Known weaknesses - the Python's, reproduced on purpose, pinned by tests

- **Pure wind still reads as an engine about 1 window in 20.** MIN_CONTRAST 8.0 sits
  at the 95th percentile of white noise (Python: 10 of 200 windows; Kotlin: 11 of 200).
  Kept at 8.0 by her decision, 2026-09-27. `ExhaustTest` KNOWN WEAKNESS.

Found by this port and since FIXED in the Python (places.py 5baa7e78), then here: a
GPX point that wrote lon before lat imported as an empty list. Both now read the
attributes in either order and skip a tag missing either one.

## Deviations from the Python, each deliberate

1. `ExhaustListener` writes "too quiet" to `rpmRejected`; audio.py:270 wrote it to
   `STATE["rejected"]`, a key nothing reads - a typo.
2. `Guidance.limitMph` takes the road class the router snapped to; the Python snapped
   the `.rg` graph itself, which the router owns.
3. `Guidance.speedLimit(postedMph, roadClass)` adds the spec's section 11 "posted
   first, typical as the fallback"; the Python had only the typical half.
4. The 12 s re-route cap and its `routing_now` interlock moved from live.py into
   `guidance.RerouteGate`, so the app cannot forget either.
5. Time is a parameter everywhere (`t`, `now`); where the Python defaulted to
   `time.time()`, `Ride` takes an injectable `clock`.
6. `Ride.readLog` skips a line that parses to something other than an object; the
   Python would raise AttributeError on it.
7. A sensor extreme fed as an integer (say `g_vert = 1`) prints as `1.0` in the
   summary where Python keeps `1`. In practice only `rpm_est` arrives as an integer,
   and the summary rounds that to an integer either way.
