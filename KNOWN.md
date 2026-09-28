# Known, and not yet fixed

Things that are true of the app today, written down so they are not rediscovered
the hard way. Each says what, why it matters, and what would close it. Remove an
entry in the same commit that fixes it.

## Motion sensors run at 50 Hz whenever Ride is on screen

`record/Telemetry.kt` subscribes to all five motion sensors at 50 Hz as soon as
the Ride screen holds telemetry, recording or not. The spec's `sensors_on_demand`
preference (default on: "only subscribe while a gauge needs it") is not honoured.

Why it matters: on the bike there may be no charger, and the phone is already
driving a bright screen and the GPS. Battery drain from this will look like "the
app eats battery", not like a sensor setting.

Closes when: sensors are subscribed only while a recording runs or a visible
gauge reads a motion channel, and the rate drops when nothing needs 50 Hz.

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

## Barometric altitude is absolute from standard pressure

`DashState.altM` prefers the barometer, computed from 1013.25 hPa as the Python
did. Weather moves it about 8 m per hPa, so it can sit a hundred metres off. The
climb rate is unaffected. On the emulator it reads 0.0 m.
