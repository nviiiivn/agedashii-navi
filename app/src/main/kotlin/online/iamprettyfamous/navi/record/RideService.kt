package online.iamprettyfamous.navi.record

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.BatteryManager
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import online.iamprettyfamous.navi.MainActivity
import online.iamprettyfamous.navi.core.Py
import online.iamprettyfamous.navi.core.bikes.Bikes
import online.iamprettyfamous.navi.core.telemetry.GpsClimb
import online.iamprettyfamous.navi.core.telemetry.Live
import online.iamprettyfamous.navi.core.telemetry.Motion
import online.iamprettyfamous.navi.core.telemetry.Ride

/** What the Ride screen shows about the recording. Published from the ride thread. */
data class RecState(
    val recording: Boolean = false,
    val name: String? = null,
    val path: String? = null,
    val startedS: Double? = null,
    val samples: Int = 0,
    val distanceM: Double = 0.0,
    val lastFixS: Double? = null,
    val sensors: List<String> = emptyList(),
    val errors: List<String> = emptyList(),
    val exported: List<String> = emptyList(),
    /** Set when the last ride was cut off by Android rather than stopped by her. */
    val cutOff: String? = null,
)

/**
 * The recording. A foreground service, so a ride survives the screen locking,
 * the app being switched away from, a theme change or a rotation: none of those
 * touch a service, and a screen's state would lose the ride to any of them.
 *
 * Everything the ride touches - GPS, sensors, the Ride, Motion, the log file -
 * lives on ONE thread, "ride", because the core is single-threaded by contract.
 * A partial wake lock keeps that thread fed while the screen is off: without it
 * the CPU sleeps between GPS fixes and the motion sensors stop reporting, so
 * the rows would still come, but empty of everything but position.
 *
 * Each fix makes a row, and the sensors are merged INTO it straight after, then
 * their peaks reset. The core writes a row to disk only when the next fix
 * arrives (or at stop), so the merge always lands before the write - the Flet
 * app wrote first, and no sensor channel ever reached a log on the phone.
 */
class RideService : Service() {

    private val prefs by lazy { getSharedPreferences("navi", MODE_PRIVATE) }
    private val main = Handler(Looper.getMainLooper())
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var wake: PowerManager.WakeLock? = null

    // Ride-thread only, from here down.
    private var ride: Ride? = null
    private var motion = Motion()
    private var gpsClimb = GpsClimb()
    private var lastNotifiedS = 0.0
    private var exported: List<String> = emptyList()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "onStartCommand ${intent?.action} (thread=${thread != null})")
        when (intent?.action) {
            ACTION_STOP -> finish()
            ACTION_START -> begin()
            else -> stopSelf()
        }
        // NOT sticky, on purpose. If Android kills the process mid-ride, a
        // restart would be in the background, and a location-type foreground
        // service cannot be created from the background at all ("even if the
        // app falls into one of the exemptions", developer.android.com
        // .../fgs/restrictions-bg-start). So a restart could never record; it
        // could only start a second file she never asked for. Instead the next
        // launch finds the cut-off log and says so: see recoverInterrupted().
        return START_NOT_STICKY
    }

    private fun begin() {
        if (thread != null) return
        try {
            // Within five seconds of startForegroundService(), or Android kills us.
            ServiceCompat.startForeground(this, NOTIFY_ID, notification("Starting…"),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } catch (e: RuntimeException) {
            // No location permission, or started from the background.
            publish(errors = listOf("could not start recording: ${e.message}"))
            stopSelf()
            return
        }
        wake = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "navi:ride")
            .apply { setReferenceCounted(false); acquire(MAX_RIDE_MS) }
        val t = HandlerThread("ride").apply { start() }
        thread = t
        val h = Handler(t.looper)
        handler = h
        h.post {
            open()
            listen(h)
        }
    }

    /** Ride thread. A new log. */
    private fun open() {
        val nowMs = System.currentTimeMillis()
        val path = RideFiles.newPath(this, nowMs)
        val name = RideFiles.newName(nowMs)
        val machine = prefs.getString("machine", "cb750") ?: "cb750"
        val hz = prefs.getInt("hz", 1).coerceIn(1, 10)
        val r = Ride(path = path, name = name, hz = hz, machine = machineHeader(machine))
        r.start(nowMs / 1000.0)
        ride = r
        motion = Motion()
        gpsClimb = GpsClimb()
        exported = emptyList()
        prefs.edit().putString(KEY_PATH, path).putString(KEY_NAME, name).apply()
        publish()
    }

    /** The whole bike profile, not just its key, as live.py:229-234 records it. */
    private fun machineHeader(key: String): Map<String, Any?> {
        val bike = Bikes.profile(key)
        return linkedMapOf(
            "machine" to key, "machine_name" to bike.name,
            "cylinders" to bike.cylinders, "strokes" to bike.strokes,
            "firing_per_rev" to Bikes.firingRatio(key), "redline_rpm" to bike.redlineRpm,
        )
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

    @SuppressLint("MissingPermission")
    private fun listen(h: Handler) {
        val lm = getSystemService(LOCATION_SERVICE) as LocationManager
        val hz = ride?.hz ?: 1
        try {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L / hz, 0f, gps, h.looper)
        } catch (e: RuntimeException) {
            publish(errors = listOf("no GPS: ${e.message}"))
        }
        val sm = getSystemService(SENSOR_SERVICE) as SensorManager
        for ((type, name) in SENSORS) {
            val sensor = sm.getDefaultSensor(type)
            if (sensor == null) {
                motion.onError(name, "this phone has no $name")
            } else {
                sm.registerListener(sensorListener, sensor, SENSOR_PERIOD_US, h)
            }
        }
    }

    private fun unlisten() {
        (getSystemService(LOCATION_SERVICE) as LocationManager).removeUpdates(gps)
        (getSystemService(SENSOR_SERVICE) as SensorManager).unregisterListener(sensorListener)
    }

    /** Ride thread. One fix: one row, then the sensors merged into it. */
    private fun onFix(fix: Location) {
        val r = ride ?: return
        val t = fix.time / 1000.0
        val acc = if (fix.hasAccuracy()) fix.accuracy.toDouble() else null
        if (!Live.accepts(acc)) return
        val alt = if (fix.hasAltitude()) fix.altitude else null
        val gpsClimbMps = gpsClimb.add(alt, t)
        r.addFix(
            lat = fix.latitude, lon = fix.longitude, t = t,
            speedMs = if (fix.hasSpeed()) fix.speed.toDouble() else null,
            heading = if (fix.hasBearing()) fix.bearing.toDouble() else null,
            altM = alt, accM = acc,
        ) ?: return
        motion.setBatteryPct(batteryPct())
        val extra = LinkedHashMap<String, Any?>(motion.latest())
        // GPS climb only where the barometer gave none, as ride.py:473-479 does.
        if ("climb_mps" !in extra && gpsClimbMps != null) extra["climb_mps"] = Py.round(gpsClimbMps, 2)
        r.feedExtra(extra)
        motion.resetPeaks()
        publish(lastFixS = t)
        if (t - lastNotifiedS >= NOTIFY_EVERY_S) {
            lastNotifiedS = t
            val minutes = (r.durationS(t) / 60).toInt()
            notify(notification("Recording · $minutes min · ${distanceText(r.distanceM)} · ${r.count} rows"))
        }
    }

    private fun batteryPct(): Int? =
        (getSystemService(BATTERY_SERVICE) as BatteryManager)
            .getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            .takeIf { it in 0..100 }

    // --------------------------------------------------------------- stop

    /** Finishes the log, writes the exports, and takes the service down. */
    private fun finish() {
        val h = handler
        if (h == null) {
            teardown()
            return
        }
        h.post {
            Log.i(TAG, "finishing ${ride?.path}")
            unlisten()
            val r = ride
            var exportError: String? = null
            if (r != null) {
                r.stop(System.currentTimeMillis() / 1000.0)
                exported = try {
                    RideFiles.export(r.path!!).map { it.path }
                } catch (e: Exception) {
                    exportError = "export failed: ${e.message}"
                    emptyList()
                }
            }
            ride = null
            prefs.edit().remove(KEY_PATH).remove(KEY_NAME).apply()
            publish(ended = r, errors = r?.errors.orEmpty() + listOfNotNull(exportError))
            Log.i(TAG, "finished: ${r?.count} rows, exported $exported, error $exportError")
            main.post { teardown() }
        }
    }

    private fun teardown() {
        wake?.takeIf { it.isHeld }?.release()
        wake = null
        thread?.quitSafely()
        thread = null
        handler = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        // Destroyed while recording (not killed - a kill never gets here): end
        // the ride properly rather than leave a log with no footer.
        handler?.post {
            unlisten()
            ride?.let { r ->
                r.stop(System.currentTimeMillis() / 1000.0)
                runCatching { RideFiles.export(r.path!!) }
            }
            ride = null
            prefs.edit().remove(KEY_PATH).remove(KEY_NAME).apply()
            publish(ended = null)
        }
        thread?.quitSafely()
        wake?.takeIf { it.isHeld }?.release()
        super.onDestroy()
    }

    // ------------------------------------------------------------ outputs

    private fun publish(lastFixS: Double? = null, errors: List<String>? = null, ended: Ride? = null) {
        val r = ride
        _state.value = if (r != null) {
            RecState(
                recording = true, name = r.name, path = r.path, startedS = r.started,
                samples = r.count, distanceM = r.distanceM,
                lastFixS = lastFixS ?: _state.value.lastFixS,
                sensors = motion.available().seen,
                errors = errors ?: (r.errors + motion.available().errors.values),
            )
        } else {
            RecState(
                recording = false, name = ended?.name, path = ended?.path,
                samples = ended?.count ?: 0, distanceM = ended?.distanceM ?: 0.0,
                errors = errors ?: ended?.errors.orEmpty(), exported = exported,
            )
        }
    }

    private fun distanceText(m: Double): String =
        if (prefs.getString("units", "imperial") == "metric") "%.1f km".format(m / 1000)
        else "%.1f mi".format(m / 1609.344)

    private fun notification(text: String): Notification {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Ride recording", NotificationManager.IMPORTANCE_LOW),
            )
        }
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, 1, Intent(this, RideService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("Navi")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(0, "Stop recording", stop)
            .build()
    }

    private fun notify(n: Notification) {
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIFY_ID, n)
    }

    companion object {
        const val ACTION_START = "online.iamprettyfamous.navi.record.START"
        const val ACTION_STOP = "online.iamprettyfamous.navi.record.STOP"

        private val _state = MutableStateFlow(RecState())
        val state: StateFlow<RecState> = _state

        fun start(context: Context) = ContextCompat.startForegroundService(
            context, Intent(context, RideService::class.java).setAction(ACTION_START))

        fun stop(context: Context) {
            context.startService(Intent(context, RideService::class.java).setAction(ACTION_STOP))
        }

        /**
         * Call when the app opens. A recording that is marked as running but has
         * no service behind it was cut off - Android killed the process, which
         * never reaches onDestroy. Its log is intact up to the last few rows
         * (the core flushes as it goes), so export it and SAY it was cut off,
         * rather than let her believe a ride is still recording, or quietly
         * start another one.
         */
        fun recoverInterrupted(context: Context) {
            if (_state.value.recording) return
            val prefs = context.getSharedPreferences("navi", MODE_PRIVATE)
            val path = prefs.getString(KEY_PATH, null) ?: return
            prefs.edit().remove(KEY_PATH).remove(KEY_NAME).apply()
            Thread({
                val full = runCatching { Ride.loadRide(path) }.getOrNull()
                val files = runCatching { RideFiles.export(path).map { it.path } }.getOrDefault(emptyList())
                val lastT = (full?.samples?.lastOrNull()?.get("t") as? Number)?.toDouble()
                val at = lastT?.let {
                    java.text.SimpleDateFormat("h:mm a", java.util.Locale.US).format(java.util.Date((it * 1000).toLong()))
                }
                Log.w(TAG, "recovered cut-off ride $path: ${full?.count} rows, last at $at")
                _state.value = RecState(
                    recording = false, name = full?.name, path = path,
                    samples = full?.count ?: 0, distanceM = full?.distanceM ?: 0.0,
                    exported = files,
                    cutOff = "Android closed Navi mid-ride" + (at?.let { ". Saved up to $it" } ?: ""),
                )
            }, "ride-recover").start()
        }

        private const val TAG = "NaviRec"
        private const val CHANNEL = "ride"
        private const val NOTIFY_ID = 1
        private const val NOTIFY_EVERY_S = 10.0
        private const val KEY_PATH = "recording_path"
        private const val KEY_NAME = "recording_name"
        // 50 Hz: a pothole is a spike a few samples wide; the Flet app's 400 ms missed most.
        private const val SENSOR_PERIOD_US = 20_000
        // A backstop so a wake lock can never outlive a forgotten ride by days.
        private const val MAX_RIDE_MS = 12L * 60 * 60 * 1000

        private val SENSORS = listOf(
            Sensor.TYPE_ACCELEROMETER to "accelerometer",
            Sensor.TYPE_LINEAR_ACCELERATION to "user_accelerometer",
            Sensor.TYPE_GYROSCOPE to "gyroscope",
            Sensor.TYPE_PRESSURE to "barometer",
            Sensor.TYPE_MAGNETIC_FIELD to "magnetometer",
        )

        /** A SensorEvent's boot-clock nanoseconds as unix seconds, the ride log's clock. */
        fun unixSeconds(eventNanos: Long): Double =
            (System.currentTimeMillis() + (eventNanos - SystemClock.elapsedRealtimeNanos()) / 1_000_000L) / 1000.0
    }
}
