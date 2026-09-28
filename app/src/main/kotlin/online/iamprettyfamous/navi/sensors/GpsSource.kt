package online.iamprettyfamous.navi.sensors

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import android.os.SystemClock

/**
 * The phone's own GNSS receiver, asked for directly.
 *
 * Not MapLibre's default location engine: that one asks Android for the
 * "fused" provider, which registered at BALANCED quality and never switched
 * the GPS on (seen in `dumpsys location` on the test emulator, airplane mode,
 * 2026-09-27), so with no signal the bike never appeared. Fused positions can
 * also come from wifi or cell towers, and the ride log wants the receiver's
 * own fix, whose speed and bearing are Doppler-derived.
 *
 * Phase 3 moves this into the recording service; the map will only listen.
 */
class GpsSource(context: Context) {
    private val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private var listener: LocationListener? = null

    var last: Location? = null
        private set

    val hasGps: Boolean get() = lm.allProviders.contains(LocationManager.GPS_PROVIDER)

    @SuppressLint("MissingPermission")
    fun start(intervalMs: Long, onFix: (Location) -> Unit) {
        if (listener != null || !hasGps) return
        val l = LocationListener { fix ->
            last = fix
            onFix(fix)
        }
        listener = l
        lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, intervalMs, 0f, l, Looper.getMainLooper())
    }

    fun stop() {
        listener?.let { lm.removeUpdates(it) }
        listener = null
    }

    /** Seconds since the last fix, or null before the first one. */
    fun ageSeconds(): Double? =
        last?.let { (SystemClock.elapsedRealtimeNanos() - it.elapsedRealtimeNanos) / 1e9 }
}
