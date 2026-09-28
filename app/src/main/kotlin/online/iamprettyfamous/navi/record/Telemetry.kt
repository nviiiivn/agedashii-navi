package online.iamprettyfamous.navi.record

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.location.altitude.AltitudeConverter
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import online.iamprettyfamous.navi.core.Py
import online.iamprettyfamous.navi.core.bikes.Bikes
import online.iamprettyfamous.navi.core.dash.DashState
import online.iamprettyfamous.navi.core.guidance.SpeedLimit
import online.iamprettyfamous.navi.core.telemetry.GpsClimb
import online.iamprettyfamous.navi.core.telemetry.Live
import online.iamprettyfamous.navi.core.telemetry.Motion
import online.iamprettyfamous.navi.core.telemetry.Ride

/**
 * The telemetry pipeline: GPS, five motion sensors and the core's maths, running
 * whenever the Ride screen is open OR a ride is recording - so the gauges work on
 * a ride she is not recording, and a recording survives the screen going away.
 *
 * It always owns a Ride. Not recording, that Ride has no path: it derives lean,
 * g and the rest exactly as a recording would, and writes nothing. Recording
 * swaps in a Ride with a file, and swaps back when it stops. Whether anything is
 * being written is [rec], never inferred from the gauges having numbers - the
 * two states must never look alike on screen.
 *
 * Everything here that touches the Ride, Motion or GpsClimb runs on ONE thread,
 * "ride": the core is single-threaded by contract. Callers only acquire,
 * release, start and stop, and read the published StateFlows.
 *
 * GPS is LocationManager.GPS_PROVIDER, asked for directly. Not MapLibre's
 * default engine, which asks for the FUSED provider at BALANCED quality and
 * never turned the GPS on (dumpsys location, airplane mode, 2026-09-27), so the
 * bike never appeared; and fused positions can come from wifi or cell towers,
 * where the log wants the receiver's own fix, with its Doppler speed.
 *
 * Each fix makes a row and the sensors are merged INTO it straight after, then
 * their peaks reset. The core writes a row to disk only when the next fix
 * arrives (Ride.kt:39-43), so the merge always lands before the write - the
 * Flet app wrote first, and no sensor channel ever reached a log on the phone.
 */
object Telemetry {
    private const val TAG = "NaviTelemetry"

    // 50 Hz: a pothole is a spike a few samples wide; the Flet app's 400 ms missed most.
    // KNOWN COST, NOT YET PAID DOWN: this runs whenever Ride is on screen, recording
    // or not, and the spec's sensors_on_demand pref is not honoured. On a bike with
    // no charger, driving a screen and GPS in the sun, that is battery. See KNOWN.md.
    private const val SENSOR_PERIOD_US = 20_000

    // What a gauge needs when nothing is being recorded: about 16 Hz.
    private const val SENSOR_UI_US = 60_000

    // The gauges redraw at 5 Hz between 1 Hz fixes, so the horizon and g-ball move.
    private const val DASH_EVERY_MS = 200L

    // A sea-level elevation older than this is not shown.
    private const val MSL_FRESH_S = 5.0

    /** A sensor, and the ride-log channels that come from it (Motion.latest()). */
    private class Source(val type: Int, val name: String, val channels: Set<String>)

    private val SENSORS = listOf(
        Source(Sensor.TYPE_ACCELEROMETER, "accelerometer", setOf("g_vert", "g_peak", "tilt_deg", "pitch_deg")),
        Source(Sensor.TYPE_LINEAR_ACCELERATION, "user_accelerometer", setOf("g_bump")),
        Source(Sensor.TYPE_GYROSCOPE, "gyroscope", setOf("yaw_gyro_dps", "gyro_peak")),
        Source(Sensor.TYPE_PRESSURE, "barometer", setOf("pressure_hpa", "alt_baro_m", "climb_mps")),
        Source(Sensor.TYPE_MAGNETIC_FIELD, "magnetometer", setOf("compass_deg")),
    )

    private val _fix = MutableStateFlow<Location?>(null)
    /** The latest accepted GPS fix, for the map. */
    val fix: StateFlow<Location?> = _fix

    private val _dash = MutableStateFlow(DashState())
    /** Every number the gauges draw. Recording or not. */
    val dash: StateFlow<DashState> = _dash

    private val _rec = MutableStateFlow(RecState())
    /** Whether a ride is being written, and how it is going. The ONLY source of that fact. */
    val rec: StateFlow<RecState> = _rec

    /** Told on the ride thread after every recorded row; the service updates its notification. */
    @Volatile var onRecordedRow: ((Ride) -> Unit)? = null

    // Main thread only.
    private val holders = mutableSetOf<String>()
    private lateinit var app: Context
    private var handler: Handler? = null

    // Ride thread only, from here down.
    private var ride = Ride(path = null)
    private var recording = false
    private var motion = Motion()
    private var gpsClimb = GpsClimb()
    private var machine = "cb750"
    private var imperial = true
    private var hz = 1
    private var listening = false
    private var debuggable = false

    // sensors_on_demand: which channels the visible gauges read (null = not
    // told yet, so everything), whether the pref is on, and what is subscribed
    // now, as sensor type -> sampling period in microseconds.
    private var dashChannels: Set<String>? = null
    private var onDemand = true
    private val subscribed = HashMap<Int, Int>()

    // Elevation above MEAN SEA LEVEL (API 34+), and the climb since the ride began.
    // Raw GPS altitude is height above the WGS84 ellipsoid - about 32 m / 105 ft
    // below sea level around the Bay - and the barometer's absolute figure moves
    // ~8 m per hPa of weather, so neither is ever shown as an elevation.
    private var mslM: Double? = null
    private var mslAtS: Double? = null
    private var rideFirstMslM: Double? = null

    fun hasGps(context: Context): Boolean =
        (context.getSystemService(Context.LOCATION_SERVICE) as LocationManager)
            .allProviders.contains(LocationManager.GPS_PROVIDER)

    /** Seconds since the last fix, or null before the first one. */
    fun fixAgeSeconds(): Double? =
        _fix.value?.let { (SystemClock.elapsedRealtimeNanos() - it.elapsedRealtimeNanos) / 1e9 }

    /**
     * [who] needs telemetry: "screen" while Ride is visible, "ride" while
     * recording. The first holder starts GPS and sensors. Main thread.
     */
    fun acquire(context: Context, who: String) {
        app = context.applicationContext
        debuggable = app.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0
        val first = holders.isEmpty()
        holders += who
        if (!first) return
        val prefs = app.getSharedPreferences("navi", Context.MODE_PRIVATE)
        val m = prefs.getString("machine", "cb750") ?: "cb750"
        val metric = prefs.getString("units", "imperial") == "metric"
        val rate = prefs.getInt("hz", 1).coerceIn(1, 10)
        val demand = prefs.getBoolean("sensors_on_demand", true)
        val h = ensureThread()
        h.post {
            machine = m
            imperial = !metric
            hz = rate
            onDemand = demand
            listen(h)
        }
    }

    /** The last holder going stops GPS and sensors. Main thread. */
    fun release(who: String) {
        holders -= who
        if (holders.isNotEmpty()) return
        handler?.post { unlisten() }
    }

    /** Swap in a Ride that writes to [path]. */
    fun startRecording(path: String, name: String, machineHeader: Map<String, Any?>) {
        ensureThread().post {
            val nowS = System.currentTimeMillis() / 1000.0
            ride = Ride(path = path, name = name, hz = hz, machine = machineHeader).also { it.start(nowS) }
            recording = true
            // Fresh sensor state for a fresh log: a sensor the gauges did not need
            // was off, and its last reading may be minutes old. This also makes
            // the barometer's baseline the start of the ride.
            motion = Motion()
            gpsClimb = GpsClimb()
            rideFirstMslM = null
            resubscribe()
            publishRec()
        }
    }

    /**
     * Ends the recording and swaps back to a Ride that writes nothing. [done]
     * runs on the ride thread with the finished Ride (null if none was running).
     */
    fun stopRecording(done: (Ride?) -> Unit) {
        ensureThread().post {
            val finished = if (recording) ride else null
            finished?.stop(System.currentTimeMillis() / 1000.0)
            recording = false
            ride = Ride(path = null)
            resubscribe()
            done(finished)
        }
    }

    /**
     * sensors_on_demand: the channels the visible gauges read (Hud.channels), or
     * null for "everything". Not recording, only the sensors behind these run,
     * at gauge rate; recording always runs every sensor at 50 Hz. Main thread.
     */
    fun setDashChannels(channels: Set<String>?) {
        ensureThread().post {
            dashChannels = channels
            resubscribe()
        }
    }

    /** Publish the state of a recording that has ended, or was cut off. */
    fun publishEnded(state: RecState) {
        _rec.value = state
    }

    /** Run [work] on the ride thread. */
    fun post(work: () -> Unit) {
        ensureThread().post(work)
    }

    /**
     * The geoid conversion's own thread. AltitudeConverter's first call loads
     * data "and may take several seconds" (its docs), so it never runs on the
     * ride thread: a fix is posted here, and its sea-level altitude is posted
     * back to be merged into that fix's row. Until the data has loaded, the
     * rows and the gauge carry no elevation - a dash, not a wait.
     */
    private var mslHandler: Handler? = null
    private var converter: Any? = null

    private fun requestMsl(fix: Location) {
        if (Build.VERSION.SDK_INT < 34 || !fix.hasAltitude()) return
        val h = mslHandler ?: Handler(HandlerThread("msl").apply { start() }.looper).also { mslHandler = it }
        val copy = Location(fix)
        h.post {
            try {
                val conv = (converter as? AltitudeConverter) ?: AltitudeConverter().also { converter = it }
                conv.addMslAltitudeToLocation(app, copy)
                if (copy.hasMslAltitude()) {
                    val m = copy.mslAltitudeMeters
                    val at = copy.time / 1000.0
                    handler?.post { onMsl(m, at) }
                }
            } catch (e: Exception) {
                // IOException loading the geoid data, or a fix it cannot convert.
                Log.w(TAG, "no sea-level altitude: ${e.message}")
            }
        }
    }

    /** Ride thread. A fix's sea-level altitude arrived; merge it into that fix's row if it is still the newest. */
    private fun onMsl(m: Double, atS: Double) {
        mslM = m
        mslAtS = atS
        if (recording && rideFirstMslM == null) rideFirstMslM = m
        val newest = ride.samples.lastOrNull()
        if ((newest?.get("t") as? Number)?.toDouble()?.let { kotlin.math.abs(it - atS) < 0.01 } == true) {
            ride.feedExtra(mapOf("alt_msl_m" to Py.round(m, 1)))
        }
    }

    /** Sea-level elevation, only while it is fresh: an old one is not where she is now. */
    private fun elevationM(nowS: Double): Double? =
        mslM?.takeIf { mslAtS?.let { nowS - it <= MSL_FRESH_S } == true }

    /**
     * Climb since the RIDE began, never since the app opened - a baseline taken
     * in the garage an hour before would be wrong in a way she would not notice.
     * The barometer where there is one (good to about a metre, relatively), else
     * the change in sea-level elevation. Null when not recording.
     */
    private fun climbSinceStartM(): Double? {
        if (!recording) return null
        motion.relativeM?.let { return it }
        val first = rideFirstMslM ?: return null
        return mslM?.let { it - first }
    }

    @Synchronized
    private fun ensureThread(): Handler {
        handler?.let { return it }
        val t = HandlerThread("ride").apply { start() }
        return Handler(t.looper).also { handler = it }
    }

    // ------------------------------------------------------------ sources

    private val gps = LocationListener { fix -> onFix(fix) }

    private val sensorListener = object : SensorEventListener {
        override fun onSensorChanged(e: SensorEvent) {
            val v = e.values
            when (e.sensor.type) {
                Sensor.TYPE_ACCELEROMETER ->
                    motion.onAccelerometer(v[0].toDouble(), v[1].toDouble(), v[2].toDouble(), unixSeconds(e.timestamp))
                Sensor.TYPE_LINEAR_ACCELERATION ->
                    motion.onUserAccelerometer(v[0].toDouble(), v[1].toDouble(), v[2].toDouble())
                Sensor.TYPE_GYROSCOPE ->
                    motion.onGyroscope(v[0].toDouble(), v[1].toDouble(), v[2].toDouble())
                Sensor.TYPE_PRESSURE -> motion.onBarometer(v[0].toDouble(), unixSeconds(e.timestamp))
                Sensor.TYPE_MAGNETIC_FIELD ->
                    motion.onMagnetometer(v[0].toDouble(), v[1].toDouble(), v[2].toDouble())
            }
        }

        override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit
    }

    private val dashTick = object : Runnable {
        override fun run() {
            publishDash()
            handler?.postDelayed(this, DASH_EVERY_MS)
        }
    }

    @SuppressLint("MissingPermission")
    private fun listen(h: Handler) {
        if (listening) return
        listening = true
        val lm = app.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        try {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L / hz, 0f, gps, h.looper)
        } catch (e: RuntimeException) {
            // No permission yet, or no GPS: the screen says which.
            Log.w(TAG, "no GPS updates: ${e.message}")
        }
        resubscribe()
        h.postDelayed(dashTick, DASH_EVERY_MS)
    }

    /** Which sensors should run now, and how fast. */
    private fun wanted(): Map<Int, Int> {
        val all = SENSORS.associate { it.type to SENSOR_PERIOD_US }
        // A recording logs every channel, and its peaks need 50 Hz. With the pref
        // off, or before the gauges have said what they read, run everything.
        if (recording || !onDemand) return all
        val need = dashChannels ?: return all
        return SENSORS.filter { s -> s.channels.any { it in need } }.associate { it.type to SENSOR_UI_US }
    }

    /** Ride thread. Subscribe and unsubscribe until what runs is what is wanted. */
    private fun resubscribe() {
        if (!listening) return
        val h = handler ?: return
        val sm = app.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val want = wanted()
        for ((type, period) in subscribed.toMap()) {
            if (want[type] == period) continue
            sm.getDefaultSensor(type)?.let { sm.unregisterListener(sensorListener, it) }
            subscribed.remove(type)
        }
        for (s in SENSORS) {
            val period = want[s.type] ?: continue
            if (subscribed[s.type] == period) continue
            val sensor = sm.getDefaultSensor(s.type)
            if (sensor == null) {
                motion.onError(s.name, "this phone has no ${s.name}")
            } else {
                sm.registerListener(sensorListener, sensor, period, h)
                subscribed[s.type] = period
            }
        }
        if (debuggable) {
            val on = SENSORS.filter { it.type in subscribed }.map { "${it.name}@${1_000_000 / subscribed.getValue(it.type)}Hz" }
            Log.d(TAG, "sensors (rec=$recording onDemand=$onDemand) ${if (on.isEmpty()) "none" else on.joinToString()}")
        }
    }

    private fun unlisten() {
        if (!listening) return
        listening = false
        (app.getSystemService(Context.LOCATION_SERVICE) as LocationManager).removeUpdates(gps)
        (app.getSystemService(Context.SENSOR_SERVICE) as SensorManager).unregisterListener(sensorListener)
        subscribed.clear()
        handler?.removeCallbacks(dashTick)
    }

    /** Ride thread. One fix: one row, then the sensors merged into it. */
    private fun onFix(fix: Location) {
        val t = fix.time / 1000.0
        val acc = if (fix.hasAccuracy()) fix.accuracy.toDouble() else null
        if (!Live.accepts(acc)) return
        _fix.value = fix
        val alt = if (fix.hasAltitude()) fix.altitude else null
        val gpsClimbMps = gpsClimb.add(alt, t)
        ride.addFix(
            lat = fix.latitude, lon = fix.longitude, t = t,
            speedMs = if (fix.hasSpeed()) fix.speed.toDouble() else null,
            heading = if (fix.hasBearing()) fix.bearing.toDouble() else null,
            altM = alt, accM = acc,
        ) ?: return
        motion.setBatteryPct(batteryPct())
        val extra = LinkedHashMap<String, Any?>(motion.latest())
        // GPS climb only where the barometer gave none, as ride.py:473-479 does.
        if ("climb_mps" !in extra && gpsClimbMps != null) extra["climb_mps"] = Py.round(gpsClimbMps, 2)
        ride.feedExtra(extra)
        requestMsl(fix)
        publishDash()
        if (debuggable) {
            // Debug builds only: one line per fix, so a test can see the gauges'
            // numbers and the recording state without a screenshot.
            val d = _dash.value
            Log.d(TAG, "fix rec=$recording rows=${ride.count} speed=${d.speedMs} alt=${d.altM} " +
                "msl=${elevationM(t)} sinceStart=${climbSinceStartM()} " +
                "tilt=${d.tiltDeg} lean=${d.leanDeg} gLat=${d.gLat} climb=${d.climbMps}")
        }
        motion.resetPeaks()
        if (recording) {
            publishRec(lastFixS = t)
            onRecordedRow?.invoke(ride)
        }
    }

    private fun publishDash() {
        val f = _fix.value
        _dash.value = DashState.build(
            row = ride.samples.lastOrNull(), motion = motion, gpsClimb = gpsClimb, ear = null,
            fixSpeedMs = f?.takeIf { it.hasSpeed() }?.speed?.toDouble(),
            fixAltM = f?.takeIf { it.hasAltitude() }?.altitude,
            machine = machine, imperial = imperial, limit = SpeedLimit.NONE,
        )
    }

    private fun publishRec(lastFixS: Double? = null) {
        val r = ride
        _rec.value = RecState(
            recording = true, name = r.name, path = r.path, startedS = r.started,
            samples = r.count, distanceM = r.distanceM,
            lastFixS = lastFixS ?: _rec.value.lastFixS,
            sensors = motion.available().seen,
            errors = r.errors + motion.available().errors.values,
        )
    }

    private fun batteryPct(): Int? =
        (app.getSystemService(Context.BATTERY_SERVICE) as BatteryManager)
            .getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            .takeIf { it in 0..100 }

    /** The whole bike profile, not just its key, as live.py:229-234 records it. */
    fun machineHeader(key: String): Map<String, Any?> {
        val bike = Bikes.profile(key)
        return linkedMapOf(
            "machine" to key, "machine_name" to bike.name,
            "cylinders" to bike.cylinders, "strokes" to bike.strokes,
            "firing_per_rev" to Bikes.firingRatio(key), "redline_rpm" to bike.redlineRpm,
        )
    }

    /** A SensorEvent's boot-clock nanoseconds as unix seconds, the ride log's clock. */
    private fun unixSeconds(eventNanos: Long): Double =
        (System.currentTimeMillis() + (eventNanos - SystemClock.elapsedRealtimeNanos()) / 1_000_000L) / 1000.0
}
