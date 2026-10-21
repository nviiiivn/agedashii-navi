package online.iamprettyfamous.navi.record

/**
 * One GPS fix, as the app uses it - the parts of Android's Location the ride
 * screen, the guidance and the ride log read, from CoreLocation's CLLocation.
 * Unknown values are null rather than CoreLocation's negative sentinels, so
 * "no speed" can never be read as "stopped".
 */
data class Fix(
    val latitude: Double,
    val longitude: Double,
    /** Unix milliseconds, as Location.time. */
    val time: Long,
    /** Metres a second, from the receiver's Doppler solution. */
    val speed: Double?,
    /** Degrees clockwise from true north. */
    val bearing: Double?,
    val bearingAccuracy: Double?,
    /** Horizontal accuracy, metres. */
    val accuracy: Double?,
    /** Metres above the WGS84 ellipsoid. */
    val altitude: Double?,
    /** Metres above mean sea level, where the phone says. */
    val altitudeMsl: Double?,
)
