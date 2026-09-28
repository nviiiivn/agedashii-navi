package online.iamprettyfamous.navi.core.telemetry

/**
 * The three things a channel can be. Rule 3 of the spec: every value is labelled,
 * and an estimate that cannot be made shows a dash - never a number invented to
 * fill a gauge.
 */
enum class Kind(val wire: String) {
    /** Read straight off a sensor. */
    MEASURED("measured"),

    /** Computed from measured channels, with the physics written down. */
    DERIVED("derived"),

    /** A best guess, allowed to report nothing rather than guess. */
    ESTIMATED("estimated"),
}

data class Channel(val name: String, val kind: Kind, val unit: String, val about: String)

/**
 * The stable public vocabulary: a gauge, an export column or a widget spec names
 * a channel and keeps working. Ported from telemetry.py:105-145, in the same
 * order, which is the order of the CSV columns and the JSONL header.
 */
object Channels {
    val ALL: List<Channel> = listOf(
        Channel("t", Kind.MEASURED, "s", "unix time of the fix"),
        Channel("lat", Kind.MEASURED, "deg", "latitude"),
        Channel("lon", Kind.MEASURED, "deg", "longitude"),
        Channel("speed_ms", Kind.MEASURED, "m/s", "ground speed from the receiver"),
        Channel("heading", Kind.MEASURED, "deg", "course over ground"),
        Channel("alt_m", Kind.MEASURED, "m", "altitude"),
        Channel("acc_m", Kind.MEASURED, "m", "horizontal accuracy, 68% confidence"),
        Channel("g_long", Kind.DERIVED, "g", "dv/dt: braking negative, drive positive"),
        Channel("yaw_dps", Kind.DERIVED, "deg/s", "rate of change of heading"),
        Channel("g_lat", Kind.DERIVED, "g", "v * yaw rate: the cornering load"),
        Channel("lean_deg", Kind.DERIVED, "deg", "atan(g_lat), the lean that load implies"),
        Channel("grade_pct", Kind.DERIVED, "%", "rise over run between fixes"),
        Channel("dist_m", Kind.DERIVED, "m", "distance travelled since the start"),
        // The accelerometer, gyroscope and barometer.
        Channel("g_vert", Kind.MEASURED, "g", "total acceleration, gravity included"),
        Channel("g_peak", Kind.MEASURED, "g", "hardest total load since the last sample"),
        Channel("g_bump", Kind.MEASURED, "g", "worst jolt with gravity removed"),
        Channel("tilt_deg", Kind.MEASURED, "deg", "roll of the PHONE, not the bike's lean"),
        Channel("pitch_deg", Kind.MEASURED, "deg", "pitch of the phone"),
        Channel("yaw_gyro_dps", Kind.MEASURED, "deg/s", "yaw rate from the gyroscope"),
        Channel("gyro_peak", Kind.MEASURED, "deg/s", "fastest rotation since the last sample"),
        Channel("pressure_hpa", Kind.MEASURED, "hPa", "atmospheric pressure"),
        Channel("alt_baro_m", Kind.DERIVED, "m", "altitude from pressure - better than GPS"),
        Channel("climb_mps", Kind.DERIVED, "m/s", "rate of climb from pressure"),
        Channel("compass_deg", Kind.MEASURED, "deg", "heading from the magnetometer, usable while stopped"),
        Channel("battery_pct", Kind.MEASURED, "%", "what the ride is costing the phone"),
        // The microphone. Only present while she has switched listening on.
        Channel("loudness_db", Kind.MEASURED, "dBFS", "how loud everything is, wind included"),
        Channel("engine_db", Kind.DERIVED, "dB", "level in the engine's band alone"),
        Channel("exhaust_hz", Kind.ESTIMATED, "Hz", "the exhaust fundamental that was found"),
        Channel("rpm_est", Kind.ESTIMATED, "rpm", "engine speed from the exhaust fundamental - absent in wind noise"),
    )

    /** The order channels are written in, for the CSV and the JSONL header. */
    val ORDER: List<String> = ALL.map { it.name }

    private val byName: Map<String, Channel> = ALL.associateBy { it.name }

    operator fun get(name: String): Channel? = byName[name]

    operator fun contains(name: String): Boolean = name in byName

    /** Every channel of one kind, in declaration order. */
    fun kinds(kind: Kind): List<String> = ALL.filter { it.kind == kind }.map { it.name }
}
