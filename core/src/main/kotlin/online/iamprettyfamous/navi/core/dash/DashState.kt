package online.iamprettyfamous.navi.core.dash

import online.iamprettyfamous.navi.core.gears.Gears
import online.iamprettyfamous.navi.core.guidance.SpeedLimit
import online.iamprettyfamous.navi.core.rpm.ExhaustListener
import online.iamprettyfamous.navi.core.telemetry.Climb
import online.iamprettyfamous.navi.core.telemetry.GpsClimb
import online.iamprettyfamous.navi.core.telemetry.Motion

/**
 * Every number the dash draws, gathered once, so no two gauges can disagree.
 *
 * The contract between the ride service (phase 3, minion 1) and the dash
 * (phase 7), agreed in writing 2026-09-27. EVERY field is nullable and every
 * gauge draws a dash for null - her rule three, never invent a number to fill
 * a gauge - so a missing value cannot become a number by construction.
 *
 * Built by [build] on the service's thread and published immutable; the UI
 * only reads it.
 */
data class DashState(
    val imperial: Boolean = true,
    /** "cb750" | "ex250" | "other". */
    val machine: String? = null,
    /** The fix's own Doppler speed. */
    val speedMs: Double? = null,
    /** Posted, typical, or none - and which, because the two never share a style. */
    val limit: SpeedLimit = SpeedLimit.NONE,
    /** The BIKE's lean, from speed and yaw; null below 5 m/s. */
    val leanDeg: Double? = null,
    /** The PHONE's roll, after her "set level here" calibration. Never the lean. */
    val tiltDeg: Double? = null,
    /** The phone's pitch, calibrated. */
    val pitchDeg: Double? = null,
    val gLat: Double? = null,
    val gLong: Double? = null,
    /**
     * Elevation above MEAN SEA LEVEL (Android 14's AltitudeConverter: the fix's
     * height less the geoid), or null where the phone cannot compute it. Never
     * the absolute barometric figure - off about 8 m per hPa of weather - and
     * never the raw GPS height, which is ellipsoidal and about 32 m (105 ft) low
     * around the Bay. The Python showed one or the other; see Gauges.PYTHON_WRONG.
     */
    val altM: Double? = null,
    /**
     * Height gained since the RIDE started, from the barometer's relative
     * altitude (weather cancels over a ride), else the sea-level delta. Null
     * when not recording: there is no start to measure from.
     */
    val climbSinceStartM: Double? = null,
    val climbMps: Double? = null,
    val rpm: Double? = null,
    /** Derived by [build] from speed, rpm and bike, never set on its own. */
    val gear: Int? = null,
    /** "up", "down" or "". */
    val shift: String = "",
    /** The multi-band meter, (label, 0..1). */
    val bands: List<Pair<String, Double>>? = null,
    /**
     * The engine band's level, falling back to the whole microphone's. UNCALIBRATED:
     * engine_db is band power in dB, not dBFS, so on the -60..-6 dBFS meter scale
     * it reads nearly full from idle up. Kept as the Python had it until a real
     * scale is measured on her bike; the meter is visibly uncalibrated, not
     * quietly wrong.
     */
    val levelDb: Double? = null,
    /** The engine meter's peak, 0..1. */
    val levelPeak: Double? = null,
) {
    /**
     * What feeds each field, as ride channels (Channels.ORDER's names, plus
     * alt_msl_m, which is in the JSONL log only). The dash's half of sensors on
     * demand: Telemetry runs only the sensors behind the channels the visible
     * gauges read, so a gauge reading a field whose sensor is off would FREEZE
     * rather than blank. Every Hud item declares its fields (Item.reads), and
     * HudChannelsTest proves no item reads a field it did not declare.
     *
     * lean, g_lat and g_long are GPS alone - speed and heading (Ride.kt) - not
     * the gyroscope. The engine fields are the microphone's, not a sensor's.
     */
    enum class Field(val channels: Set<String>) {
        SPEED(setOf("speed_ms")),
        /** From routing, not a sensor. */
        LIMIT(emptySet()),
        LEAN(setOf("lean_deg")),
        TILT(setOf("tilt_deg")),
        PITCH(setOf("pitch_deg")),
        G_LAT(setOf("g_lat")),
        G_LONG(setOf("g_long")),
        ALT(setOf("alt_msl_m")),
        CLIMB_SINCE_START(setOf("alt_baro_m")),
        CLIMB(setOf("climb_mps")),
        RPM(setOf("rpm_est")),
        GEAR(setOf("speed_ms", "rpm_est")),
        SHIFT(setOf("speed_ms", "rpm_est")),
        BANDS(setOf("engine_db")),
        LEVEL(setOf("engine_db", "loudness_db")),
        LEVEL_PEAK(setOf("engine_db")),
    }

    companion object {
        /**
         * One snapshot from what the ride pipeline holds - ride.py `_hud_state()`,
         * with two fixes: tilt and pitch are the CALIBRATED ones (the Python passed
         * raw roll and pitch, so the "set level" button did nothing to the horizon),
         * and there is one tilt field (the Python's Gunsight read `tilt_deg` from a
         * state that only had `roll_deg`, so its roll needle never drew).
         *
         * [row] is the latest derived ride row, recording or not; nothing here
         * asks whether a ride is being recorded.
         */
        fun build(
            row: Map<String, Any?>?,
            motion: Motion?,
            gpsClimb: GpsClimb?,
            ear: ExhaustListener?,
            fixSpeedMs: Double?,
            mslAltM: Double?,
            climbSinceStartM: Double?,
            machine: String?,
            imperial: Boolean,
            limit: SpeedLimit,
        ): DashState {
            fun rowNumber(key: String) = (row?.get(key) as? Number)?.toDouble()
            val calibrated = motion?.latest().orEmpty()
            val rpm = ear?.rpmEst
            val gear = Gears.estimate(fixSpeedMs, rpm, machine).gear
            return DashState(
                imperial = imperial,
                machine = machine,
                speedMs = fixSpeedMs,
                limit = limit,
                leanDeg = rowNumber("lean_deg"),
                tiltDeg = (calibrated["tilt_deg"] as? Number)?.toDouble(),
                pitchDeg = (calibrated["pitch_deg"] as? Number)?.toDouble(),
                gLat = rowNumber("g_lat"),
                gLong = rowNumber("g_long"),
                altM = mslAltM,
                climbSinceStartM = climbSinceStartM,
                climbMps = Climb.best(motion?.climbMps, gpsClimb?.climbMps),
                rpm = rpm,
                gear = gear,
                shift = Gears.shiftHint(rpm, gear, machine),
                bands = ear?.bands?.takeIf { it.isNotEmpty() },
                levelDb = ear?.engineDb ?: ear?.loudnessDb,
                levelPeak = ear?.enginePeak,
            )
        }
    }
}
