package online.iamprettyfamous.navi.core.telemetry

import online.iamprettyfamous.navi.core.Py
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.sqrt

/** Whether an upright has been set, and at what angles. */
data class Zeroed(val set: Boolean, val roll: Double, val pitch: Double)

/** Which sensors have actually produced a reading, and which failed and why. */
data class Available(val seen: List<String>, val errors: Map<String, String>)

/**
 * The motion sensors: raw readings in, ride-log channels out.
 *
 * Ported from apps/navi/agedashii_navi/sensors.py minus Flet. The app's
 * SensorManager listeners call the `on*` methods with raw values and the time;
 * the log and the gauges read [latest]. Not thread-safe: feed and read on one
 * thread.
 *
 * What each sensor is good for. The accelerometer includes gravity, so it
 * answers orientation. The user accelerometer has gravity removed, so it answers
 * what just happened - bumps, potholes, a drop. The gyroscope gives angular
 * rate directly, and does not collapse into noise at walking pace. The
 * barometer resolves relative altitude to about a metre, where GPS altitude
 * wanders five to ten. The magnetometer is the only heading while stopped.
 *
 * What roll is NOT: the lean of the bike. A phone on a leaning bike measures
 * the resultant of gravity and cornering force, which points down the bike's
 * centreline, so in a steady corner roll reads near zero. tilt_deg is the roll
 * of the PHONE; lean comes from [Physics.leanFrom], and the UI must never
 * conflate them.
 *
 * Peaks: every sensor runs far faster than the 1 Hz log, so the largest value
 * since [resetPeaks] is kept beside the current one. A pothole is a spike two
 * samples wide; logging the instantaneous value once a second misses it nine
 * times in ten.
 */
class Motion {
    // Accelerometer, gravity included.
    var x: Double? = null; private set
    var y: Double? = null; private set
    var z: Double? = null; private set
    var gTotal: Double? = null; private set
    var gPeak: Double = 0.0; private set
    var pitchDeg: Double? = null; private set
    var rollDeg: Double? = null; private set
    var lastT: Double? = null; private set

    // User accelerometer, gravity removed.
    var gBump: Double? = null; private set
    var gBumpPeak: Double = 0.0; private set

    // Gyroscope, rad/s.
    var gyroX: Double? = null; private set
    var gyroY: Double? = null; private set
    var gyroZ: Double? = null; private set
    var gyroPeak: Double = 0.0; private set

    // Barometer.
    var pressureHpa: Double? = null; private set
    var altBaroM: Double? = null; private set
    var climbMps: Double? = null; private set
    var basePressureHpa: Double? = null; private set
    var relativeM: Double? = null; private set

    // Magnetometer: raw microtesla as well as the heading, because a large total
    // field means the phone is next to something magnetic and the heading is junk.
    var magX: Double? = null; private set
    var magY: Double? = null; private set
    var magZ: Double? = null; private set
    var magTotalUt: Double? = null; private set
    private var compassRaw: Double? = null

    var shakes: Int = 0; private set
    var lastShake: Double? = null; private set
    var batteryPct: Int? = null; private set
    var batteryState: String = ""; private set

    private var zeroRoll = 0.0
    private var zeroPitch = 0.0
    private val seen = sortedSetOf<String>()
    private val errors = linkedMapOf<String, String>()
    private val history = ArrayDeque<Pair<Double, Double?>>()

    /**
     * Called with (when, gBumpPeak) on every knock, so a knock while recording
     * becomes an incident. A listener that throws must never take the sensor
     * down with it.
     */
    var onShakeListener: ((t: Double, forceG: Double) -> Unit)? = null

    fun onAccelerometer(x: Double, y: Double, z: Double, t: Double) {
        this.x = x
        this.y = y
        this.z = z
        val total = sqrt(x * x + y * y + z * z) / Physics.G
        gTotal = total
        gPeak = max(gPeak, total)
        // atan2 against the other two axes rather than asin(x / g): under braking
        // the total is not 1 g, and asin is NaN the moment it exceeds gravity -
        // which happens on every pothole.
        pitchDeg = Math.toDegrees(atan2(-x, sqrt(y * y + z * z)))
        rollDeg = Math.toDegrees(atan2(y, z))
        seen.add("accelerometer")
        lastT = t
    }

    fun onUserAccelerometer(x: Double, y: Double, z: Double) {
        val bump = sqrt(x * x + y * y + z * z) / Physics.G
        gBump = bump
        gBumpPeak = max(gBumpPeak, bump)
        seen.add("user_accelerometer")
    }

    /** Angular rate in rad/s. z is yaw for a phone lying flat. */
    fun onGyroscope(x: Double, y: Double, z: Double) {
        gyroX = x
        gyroY = y
        gyroZ = z
        gyroPeak = max(gyroPeak, sqrt(x * x + y * y + z * z))
        seen.add("gyroscope")
    }

    /**
     * Pressure, and the altitude and climb rate that follow. The first reading
     * is the reference for relative altitude. The climb rate is
     * (last - first) / span over the last [CLIMB_WINDOW_S] - an endpoint
     * difference, as sensors.py:272-275 computes it; the GPS fallback is the
     * least-squares one ([GpsClimb]).
     */
    fun onBarometer(hpa: Double, t: Double) {
        pressureHpa = hpa
        if (basePressureHpa == null) basePressureHpa = hpa
        val absolute = Physics.altitudeFromPressure(hpa)
        val base = Physics.altitudeFromPressure(basePressureHpa)
        altBaroM = absolute
        relativeM = if (absolute == null || base == null) null else absolute - base
        history.addLast(t to absolute)
        while (history.isNotEmpty() && t - history.first().first > CLIMB_WINDOW_S) history.removeFirst()
        if (history.size >= 2 && absolute != null) {
            val span = history.last().first - history.first().first
            val rise = (history.last().second ?: 0.0) - (history.first().second ?: 0.0)
            climbMps = if (span > 0) rise / span else null
        }
        seen.add("barometer")
    }

    /**
     * A compass heading, which GPS cannot supply at a standstill. Flat-phone and
     * uncompensated for tilt: on a mount the tilt is roughly constant, so the
     * heading is consistently offset rather than unstable, the more useful failure.
     */
    fun onMagnetometer(x: Double, y: Double, z: Double) {
        magX = x
        magY = y
        magZ = z
        magTotalUt = sqrt(x * x + y * y + z * z)
        compassRaw = (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
        seen.add("magnetometer")
    }

    /** A hard knock, from the platform's shake detector. Its threshold, not one invented here. */
    fun onShake(t: Double) {
        shakes += 1
        lastShake = t
        seen.add("shake")
        val listener = onShakeListener ?: return
        try {
            listener(t, gBumpPeak)
        } catch (_: Exception) {
            // A listener must never take the sensor down with it.
        }
    }

    fun onBattery(state: String?) {
        batteryState = state ?: ""
        seen.add("battery")
    }

    fun setBatteryPct(level: Int?) {
        if (level != null) batteryPct = level
    }

    /** A phone without a barometer is the normal case, not a fault; Settings names it. */
    fun onError(name: String, message: String) {
        errors[name] = message
    }

    /**
     * Call THIS orientation upright, or null when no accelerometer has reported
     * - so the caller can say "no sensor" instead of claiming a calibration that
     * never happened. Her ask: "a 'reset' sensors ... recalibrate what is
     * 'home' or 'straight'".
     */
    fun zero(): Zeroed? {
        val roll = rollDeg ?: return null
        zeroRoll = roll
        zeroPitch = pitchDeg ?: 0.0
        return Zeroed(true, Py.round(zeroRoll, 1), Py.round(zeroPitch, 1))
    }

    /**
     * A level set on an earlier ride. Her mount does not move between rides, so
     * the angle she called "level" is kept and put back on every fresh Motion
     * rather than asked for again.
     */
    fun zeroAt(roll: Double, pitch: Double) {
        zeroRoll = roll
        zeroPitch = pitch
    }

    fun unzero() {
        zeroRoll = 0.0
        zeroPitch = 0.0
    }

    fun zeroed(): Zeroed = Zeroed(zeroRoll != 0.0 || zeroPitch != 0.0, Py.round(zeroRoll, 1), Py.round(zeroPitch, 1))

    fun available(): Available = Available(seen.toList(), LinkedHashMap(errors))

    /**
     * The current values, named for the ride-log columns, peaks included. A key
     * only when a real reading is behind it, so a phone with no barometer
     * contributes no altitude column rather than a zero.
     */
    fun latest(): Map<String, Any> {
        val out = LinkedHashMap<String, Any>()
        gTotal?.let {
            out["g_vert"] = Py.round(it, 3)
            out["g_peak"] = Py.round(gPeak, 3)
            // Relative to whatever she called upright, so a mount at an angle reads
            // zero when the bike is level.
            out["tilt_deg"] = Py.round(rollDeg!! - zeroRoll, 1)
            out["pitch_deg"] = Py.round(pitchDeg!! - zeroPitch, 1)
        }
        if (gBump != null) out["g_bump"] = Py.round(gBumpPeak, 3)
        gyroZ?.let {
            // Degrees per second, to match the GPS yaw channel beside it.
            out["yaw_gyro_dps"] = Py.round(Math.toDegrees(it), 2)
            out["gyro_peak"] = Py.round(Math.toDegrees(gyroPeak), 2)
        }
        compassDeg()?.let { out["compass_deg"] = Py.round(it, 1) }
        batteryPct?.let { out["battery_pct"] = it }
        pressureHpa?.let {
            out["pressure_hpa"] = Py.round(it, 2)
            altBaroM?.let { alt -> out["alt_baro_m"] = Py.round(alt, 1) }
            climbMps?.let { climb -> out["climb_mps"] = Py.round(climb, 2) }
        }
        return out
    }

    /** The peaks alone; gyro in degrees per second. */
    fun peaks(): Map<String, Double> =
        linkedMapOf("g_peak" to gPeak, "g_bump_peak" to gBumpPeak, "gyro_peak" to Math.toDegrees(gyroPeak))

    /** Start a new window. Called once per logged sample. */
    fun resetPeaks() {
        gPeak = 0.0
        gBumpPeak = 0.0
        gyroPeak = 0.0
    }

    /**
     * A compass heading, or null when the total field is implausible for the
     * earth's - the phone is by a tank bag clasp, a speaker, the bike's wiring.
     * A confidently wrong heading at a junction is worse than none.
     */
    fun compassDeg(): Double? {
        val total = magTotalUt ?: return null
        if (total < FIELD_MIN_UT || total > FIELD_MAX_UT) return null
        return compassRaw
    }

    /**
     * The best heading available: GPS while moving, compass while stopped. GPS
     * course is excellent above walking pace and meaningless below it; the
     * compass is the reverse.
     */
    fun heading(gpsHeading: Double?, speedMs: Double?, minSpeedMs: Double = HEADING_CROSSOVER_MS): Double? {
        if (gpsHeading != null && (speedMs ?: 0.0) >= minSpeedMs) return gpsHeading
        return compassDeg() ?: gpsHeading
    }

    companion object {
        /** How often each motion sensor is asked to report. 100 ms made the Python app unusable. */
        const val INTERVAL_MS = 400

        /** The barometer is slower still; pressure does not change quickly. */
        const val BARO_INTERVAL_MS = 1000

        /** Seconds of pressure history behind the climb rate. */
        const val CLIMB_WINDOW_S = 6.0

        /** Earth's field in microtesla; outside this the heading means nothing. Spec section 07. */
        const val FIELD_MIN_UT = 20.0
        const val FIELD_MAX_UT = 75.0

        /** GPS heading above this, compass below it. sensors.py:493. */
        const val HEADING_CROSSOVER_MS = 2.5
    }
}
