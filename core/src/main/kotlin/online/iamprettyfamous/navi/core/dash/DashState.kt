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
     * Barometric where the phone has a barometer, else the fix's. NOTE: the
     * barometric figure is absolute from standard sea-level pressure (1013.25
     * hPa), as the Python's was - about 8 m per hPa of weather, so it can sit
     * a hundred metres off on a stormy day. The climb RATE is unaffected.
     */
    val altM: Double? = null,
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
            fixAltM: Double?,
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
                altM = Climb.bestAltitude(motion?.altBaroM, fixAltM),
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
