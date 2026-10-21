package online.iamprettyfamous.navi.record

import kotlinx.cinterop.useContents
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import online.iamprettyfamous.navi.core.Py
import online.iamprettyfamous.navi.core.bikes.Bikes
import online.iamprettyfamous.navi.core.dash.DashState
import online.iamprettyfamous.navi.core.guidance.SpeedLimit
import online.iamprettyfamous.navi.core.nowSeconds
import online.iamprettyfamous.navi.core.telemetry.GpsClimb
import online.iamprettyfamous.navi.core.telemetry.Live
import online.iamprettyfamous.navi.core.telemetry.Motion
import online.iamprettyfamous.navi.core.telemetry.Ride
import online.iamprettyfamous.navi.data.DataFiles
import online.iamprettyfamous.navi.routing.Navigator
import online.iamprettyfamous.navi.ui.format
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import okio.Path.Companion.toPath
import kotlin.time.Instant
import online.iamprettyfamous.navi.core.Disk
import platform.CoreLocation.CLActivityTypeAutomotiveNavigation
import platform.CoreLocation.CLAuthorizationStatus
import platform.CoreLocation.CLLocation
import platform.CoreLocation.CLLocationManager
import platform.CoreLocation.CLLocationManagerDelegateProtocol
import platform.CoreLocation.kCLAuthorizationStatusAuthorizedAlways
import platform.CoreLocation.kCLAuthorizationStatusAuthorizedWhenInUse
import platform.CoreLocation.kCLAuthorizationStatusNotDetermined
import platform.CoreLocation.kCLDistanceFilterNone
import platform.CoreLocation.kCLLocationAccuracyBestForNavigation
import platform.Foundation.NSError
import platform.Foundation.timeIntervalSince1970
import platform.UIKit.UIDevice
import platform.darwin.NSObject

/** Whether a ride is being written, and how it is going - the Android app's RecState. */
data class RecState(
    val recording: Boolean = false,
    val name: String? = null,
    val path: String? = null,
    val startedS: Double? = null,
    val samples: Int = 0,
    val distanceM: Double = 0.0,
    val lastFixS: Double? = null,
    val errors: List<String> = emptyList(),
    val exported: List<String> = emptyList(),
)

/**
 * The telemetry pipeline on the iPhone: CoreLocation's fixes and the core's
 * maths, the Android app's Telemetry with the GPS half ported first. It always
 * owns a Ride - with no path it derives the gauges' numbers exactly as a
 * recording would and writes nothing; recording swaps in a Ride with a file.
 * Whether anything is being written is [rec], never inferred from the gauges.
 *
 * Everything runs on the main thread: CoreLocation delivers there, and the
 * core is single-threaded by contract.
 *
 * Not ported yet: the motion sensors (lean from the accelerometer, the gyro,
 * the barometer's climb) and the microphone's rev counter. Lean, cornering g
 * and climb still come from the GPS alone, as on Android with the sensors off.
 *
 * With the screen locked the fixes keep coming while a route is up or a ride
 * records ([background]) - UIBackgroundModes location - and the blue pill in
 * the status bar is the iPhone's version of Android's ongoing notification.
 */
object Telemetry {
    private val _fix = MutableStateFlow<Fix?>(null)
    /** The latest accepted GPS fix, for the map. */
    val fix: StateFlow<Fix?> = _fix

    private val _dash = MutableStateFlow(DashState())
    /** Every number the gauges draw. Recording or not. */
    val dash: StateFlow<DashState> = _dash

    private val _rec = MutableStateFlow(RecState())
    /** Whether a ride is being written. The ONLY source of that fact. */
    val rec: StateFlow<RecState> = _rec

    private val _granted = MutableStateFlow<Boolean?>(null)
    /** Location permission: null before she has been asked. */
    val granted: StateFlow<Boolean?> = _granted

    private var ride = Ride(path = null)
    private var recording = false
    private val motion = Motion()
    private var gpsClimb = GpsClimb()
    private var machine = "cb750"
    private var imperial = true
    private var lastFixAtS: Double? = null

    private val manager = CLLocationManager()

    private val delegate = object : NSObject(), CLLocationManagerDelegateProtocol {
        override fun locationManager(manager: CLLocationManager, didUpdateLocations: List<*>) {
            for (l in didUpdateLocations) (l as? CLLocation)?.let(::onFix)
        }

        override fun locationManager(manager: CLLocationManager, didFailWithError: NSError) {
            println("[telemetry] location failed: ${didFailWithError.localizedDescription}")
        }

        override fun locationManagerDidChangeAuthorization(manager: CLLocationManager) {
            authorized(manager.authorizationStatus)
        }
    }

    /** Ask for location (once) and start the fixes. Main thread. */
    fun start(machine: String, imperial: Boolean) {
        this.machine = machine
        this.imperial = imperial
        manager.delegate = delegate
        manager.desiredAccuracy = kCLLocationAccuracyBestForNavigation
        manager.distanceFilter = kCLDistanceFilterNone
        manager.activityType = CLActivityTypeAutomotiveNavigation
        // Never paused: iOS's guess that she has stopped moving is a red light.
        manager.pausesLocationUpdatesAutomatically = false
        manager.showsBackgroundLocationIndicator = true
        UIDevice.currentDevice.batteryMonitoringEnabled = true
        val status = manager.authorizationStatus
        if (status == kCLAuthorizationStatusNotDetermined) manager.requestWhenInUseAuthorization()
        authorized(status)
    }

    private fun authorized(status: CLAuthorizationStatus) {
        if (status == kCLAuthorizationStatusNotDetermined) return
        val ok = status == kCLAuthorizationStatusAuthorizedWhenInUse || status == kCLAuthorizationStatusAuthorizedAlways
        _granted.value = ok
        if (ok) manager.startUpdatingLocation()
    }

    /**
     * Keep the fixes coming with the screen locked: on while a route is up or a
     * ride records, off otherwise, so an idle app in her pocket is not a GPS.
     */
    fun background(on: Boolean) {
        manager.allowsBackgroundLocationUpdates = on
    }

    /** Re-read what Settings can change. */
    fun refresh(machine: String, imperial: Boolean) {
        this.machine = machine
        this.imperial = imperial
        publishDash()
    }

    /** Seconds since the last fix, or null before the first one. */
    fun fixAgeSeconds(): Double? = lastFixAtS?.let { nowSeconds() - it }

    private fun onFix(l: CLLocation) {
        val (lat, lon) = l.coordinate.useContents { latitude to longitude }
        val acc = l.horizontalAccuracy.takeIf { it >= 0 }
        if (!Live.accepts(acc)) return
        val t = l.timestamp.timeIntervalSince1970
        val fix = Fix(
            latitude = lat, longitude = lon, time = (t * 1000).toLong(),
            speed = l.speed.takeIf { it >= 0 },
            bearing = l.course.takeIf { it >= 0 },
            bearingAccuracy = l.courseAccuracy.takeIf { it >= 0 },
            accuracy = acc,
            altitude = l.ellipsoidalAltitude.takeIf { l.verticalAccuracy >= 0 },
            altitudeMsl = l.altitude.takeIf { l.verticalAccuracy >= 0 },
        )
        lastFixAtS = nowSeconds()
        _fix.value = fix
        // Guidance follows the same accepted fixes, here rather than in the
        // screen, so it keeps its place with the screen locked.
        Navigator.onFix(fix)
        val gpsClimbMps = gpsClimb.add(fix.altitude, t)
        ride.addFix(
            lat = lat, lon = lon, t = t, speedMs = fix.speed, heading = fix.bearing,
            altM = fix.altitude, accM = acc,
        ) ?: return
        motion.setBatteryPct(batteryPct())
        val extra = LinkedHashMap<String, Any?>(motion.latest())
        if ("climb_mps" !in extra && gpsClimbMps != null) extra["climb_mps"] = Py.round(gpsClimbMps, 2)
        ride.feedExtra(extra)
        publishDash()
        motion.resetPeaks()
        if (recording) publishRec(lastFixS = t)
    }

    private fun publishDash() {
        val f = _fix.value
        _dash.value = DashState.build(
            row = ride.samples.lastOrNull(), motion = motion, gpsClimb = gpsClimb, ear = null,
            fixSpeedMs = f?.speed,
            mslAltM = f?.altitudeMsl,
            climbSinceStartM = null,
            machine = machine, imperial = imperial, limit = SpeedLimit.NONE,
        )
    }

    /** Start writing a ride: Documents/rides/ride-<stamp>.jsonl, which the Files app shows. */
    fun startRecording() {
        if (recording) return
        val nowS = nowSeconds()
        val t = Instant.fromEpochMilliseconds((nowS * 1000).toLong()).toLocalDateTime(TimeZone.currentSystemDefault())
        // `ride-20260927-143005.jsonl`, and seconds in the NAME too: the server
        // keys one ride per name, and two rides started in the same minute
        // overwrote each other on the dashboard (live.py:219-227).
        val stamp = "${t.year}${two(t.month.ordinal + 1)}${two(t.day)}-${two(t.hour)}${two(t.minute)}${two(t.second)}"
        val name = "${t.year}-${two(t.month.ordinal + 1)}-${two(t.day)} ${two(t.hour)}:${two(t.minute)}:${two(t.second)}"
        val path = "${DataFiles.documents("rides")}/ride-$stamp.jsonl"
        ride = Ride(path = path, name = name, machine = machineHeader(machine)).also { it.start(nowS) }
        recording = true
        gpsClimb = GpsClimb()
        publishRec()
    }

    /**
     * End the ride and write its CSV, GPX and JSON beside the log - from the
     * whole log read back off disk, never the live window (the Flet app cut
     * every ride over ten minutes short that way).
     */
    fun stopRecording() {
        if (!recording) return
        val finished = ride
        finished.stop(nowSeconds())
        recording = false
        ride = Ride(path = null)
        val path = finished.path
        val exported = path?.let { runCatching { export(it) }.getOrElse { e -> listOf("export failed: ${e.message}") } } ?: emptyList()
        _rec.value = RecState(
            recording = false, name = finished.name, path = path, startedS = finished.started,
            samples = finished.count, distanceM = finished.distanceM, errors = finished.errors, exported = exported,
        )
    }

    private fun export(path: String): List<String> {
        val full = Ride.loadRide(path)
        val rows = full.samples
        val base = path.removeSuffix(".jsonl")
        val out = listOf("$base.csv" to full.toCsv(rows), "$base.gpx" to full.toGpx(rows), "$base.json" to full.toJson(rows))
        for ((p, text) in out) Disk.write(p.toPath()) { writeUtf8(text) }
        return out.map { it.first }
    }

    private fun publishRec(lastFixS: Double? = null) {
        val r = ride
        _rec.value = RecState(
            recording = true, name = r.name, path = r.path, startedS = r.started,
            samples = r.count, distanceM = r.distanceM,
            lastFixS = lastFixS ?: _rec.value.lastFixS,
            errors = r.errors,
        )
    }

    private fun two(n: Int) = n.toString().padStart(2, '0')

    private fun batteryPct(): Int? =
        UIDevice.currentDevice.batteryLevel.takeIf { it >= 0f }?.let { (it * 100).toInt() }

    /** The whole bike profile, not just its key, as live.py records it. */
    fun machineHeader(key: String): Map<String, Any?> {
        val bike = Bikes.profile(key)
        return linkedMapOf(
            "machine" to key, "machine_name" to bike.name,
            "cylinders" to bike.cylinders, "strokes" to bike.strokes,
            "firing_per_rev" to Bikes.firingRatio(key), "redline_rpm" to bike.redlineRpm,
        )
    }

    /** "1.2 mi" or "1.9 km", for the recording line. */
    fun distanceText(m: Double, imperial: Boolean): String =
        if (imperial) "%.1f mi".format(m / 1609.344) else "%.1f km".format(m / 1000)
}
