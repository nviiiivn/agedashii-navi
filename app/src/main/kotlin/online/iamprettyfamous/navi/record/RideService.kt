package online.iamprettyfamous.navi.record

import online.iamprettyfamous.navi.sync.RideSync

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.StateFlow
import online.iamprettyfamous.navi.MainActivity
import online.iamprettyfamous.navi.core.telemetry.Ride

/** What the Ride screen shows about the recording. */
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
 * The recording, as a location-type foreground service, so a ride survives the
 * screen locking, the app being switched away from, a theme change or a
 * rotation: none of those touch a service, and a screen's state would lose the
 * ride to any of them.
 *
 * The work itself is [Telemetry]'s; this service is what keeps it alive with the
 * screen off: the foreground notification, and a partial wake lock without which
 * the CPU sleeps between fixes and the motion sensors stop reporting - the rows
 * would still come, but empty of everything but position.
 */
class RideService : Service() {

    private val prefs by lazy { getSharedPreferences("navi", MODE_PRIVATE) }
    private val main = Handler(Looper.getMainLooper())
    private var wake: PowerManager.WakeLock? = null
    private var active = false
    @Volatile private var lastNotifiedS = 0.0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "onStartCommand ${intent?.action} (active=$active)")
        when (intent?.action) {
            ACTION_STOP -> finish()
            ACTION_START -> begin()
            ACTION_TYPES -> if (active) promote()
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
        if (active) return
        try {
            // Within five seconds of startForegroundService(), or Android kills us.
            ServiceCompat.startForeground(this, NOTIFY_ID, notification("Starting…"), types())
        } catch (e: RuntimeException) {
            // No location permission, or started from the background.
            Telemetry.publishEnded(RecState(errors = listOf("could not start recording: ${e.message}")))
            stopSelf()
            return
        }
        active = true
        running = true
        wake = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "navi:ride")
            .apply { setReferenceCounted(false); acquire(MAX_RIDE_MS) }
        val nowMs = System.currentTimeMillis()
        val path = RideFiles.newPath(this, nowMs)
        val name = RideFiles.newName(nowMs)
        prefs.edit().putString(KEY_PATH, path).putString(KEY_NAME, name).apply()
        lastNotifiedS = 0.0
        Telemetry.onRecordedRow = { r -> notifyProgress(r) }
        Telemetry.acquire(this, "ride")
        Telemetry.startRecording(path, name, Telemetry.machineHeader(prefs.getString("machine", "cb750") ?: "cb750"))
    }

    /**
     * Location always; microphone too while she has it on. Without the microphone
     * type, Android silences the mic the moment the screen locks, and the rev
     * counter would go quiet mid-ride with nothing on screen to say why.
     */
    private fun types(): Int =
        ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION or
            (if (micWanted) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0)

    /** The mic was switched during a ride: tell Android what this service now uses. */
    private fun promote() {
        try {
            ServiceCompat.startForeground(this, NOTIFY_ID, notification("Recording"), types())
        } catch (e: RuntimeException) {
            Log.w(TAG, "could not change the service type: ${e.message}")
        }
    }

    /** Ride thread, every recorded row. The notification changes every 10 s, not every fix. */
    private fun notifyProgress(r: Ride) {
        val t = System.currentTimeMillis() / 1000.0
        if (t - lastNotifiedS < NOTIFY_EVERY_S) return
        lastNotifiedS = t
        val minutes = (r.durationS(t) / 60).toInt()
        notify(notification("Recording · $minutes min · ${distanceText(r.distanceM)} · ${r.count} rows"))
    }

    /** Finishes the log, writes the exports, and takes the service down. */
    private fun finish() {
        if (!active) {
            teardown()
            return
        }
        end { main.post { teardown() } }
    }

    /** Stop recording, export from the whole log on disk, publish the result, then [after]. */
    private fun end(after: () -> Unit) {
        Telemetry.onRecordedRow = null
        Telemetry.stopRecording { r ->
            // Ride thread. Exports are always from Ride.loadRide(path) - the whole
            // log read back off disk - never from the live Ride's 600-row window,
            // which cut every Flet ride over ten minutes short.
            var exported = emptyList<String>()
            var error: String? = null
            if (r?.path != null) {
                try {
                    exported = RideFiles.export(r.path!!).map { it.path }
                } catch (e: Exception) {
                    error = "export failed: ${e.message}"
                }
            }
            prefs.edit().remove(KEY_PATH).remove(KEY_NAME).apply()
            micWanted = false // the ride ended, and the mic with it (per ride)
            Telemetry.publishEnded(RecState(
                recording = false, name = r?.name, path = r?.path,
                samples = r?.count ?: 0, distanceM = r?.distanceM ?: 0.0,
                errors = r?.errors.orEmpty() + listOfNotNull(error), exported = exported,
            ))
            Log.i(TAG, "finished: ${r?.count} rows, exported $exported, error $error")
            running = false
            if (exported.isNotEmpty()) RideSync.kick(this, "ride ended")
            after()
        }
    }

    private fun teardown() {
        if (active) Telemetry.release("ride")
        active = false
        running = false
        wake?.takeIf { it.isHeld }?.release()
        wake = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        // Destroyed while recording (not killed - a kill never gets here): end
        // the ride properly rather than leave a log with no footer.
        if (active) {
            end { }
            Telemetry.release("ride")
            active = false
        }
        wake?.takeIf { it.isHeld }?.release()
        super.onDestroy()
    }

    // ------------------------------------------------------------ outputs

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
        const val ACTION_TYPES = "online.iamprettyfamous.navi.record.TYPES"

        /**
         * Whether the mic is meant to be on. Set by the screen when she switches
         * it, BEFORE the service is started or told - the capture itself starts
         * a moment later on the ride thread, so its own state would be too late.
         */
        @Volatile var micWanted = false

        /** Call after switching the mic while recording, from the foreground. */
        fun refreshTypes(context: Context) {
            if (Telemetry.rec.value.recording) {
                context.startService(Intent(context, RideService::class.java).setAction(ACTION_TYPES))
            }
        }

        /** The recording's state. Telemetry publishes it; this is the same flow. */
        val state: StateFlow<RecState> get() = Telemetry.rec

        /** True from the moment a recording is asked for until its log is closed. */
        @Volatile private var running = false

        fun start(context: Context) = ContextCompat.startForegroundService(
            context, Intent(context, RideService::class.java).setAction(ACTION_START))

        fun stop(context: Context) {
            context.startService(Intent(context, RideService::class.java).setAction(ACTION_STOP))
        }

        /**
         * Call when the app opens. A recording that is marked as running but has
         * no service behind it was cut off - Android killed the process, which
         * never reaches onDestroy. Its log is intact except for UP TO the core's
         * flush buffer's worth of rows (at most 5 plus the one held back): how
         * many depends on where the kill lands, from none (15 of 15 kept, in
         * AGEDASHII's run) to all of it (10 of 15, in mine). Export what is
         * there and SAY it was cut off,
         * rather than let her believe a ride is still recording, or quietly
         * start another one.
         */
        fun recoverInterrupted(context: Context) {
            if (running || Telemetry.rec.value.recording) return
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
                Telemetry.publishEnded(RecState(
                    recording = false, name = full?.name, path = path,
                    samples = full?.count ?: 0, distanceM = full?.distanceM ?: 0.0,
                    exported = files,
                    cutOff = "Android closed Navi mid-ride" + (at?.let { ". Saved up to $it" } ?: ""),
                ))
            }, "ride-recover").start()
        }

        private const val TAG = "NaviRec"
        private const val CHANNEL = "ride"
        private const val NOTIFY_ID = 1
        private const val NOTIFY_EVERY_S = 10.0
        private const val KEY_PATH = "recording_path"
        private const val KEY_NAME = "recording_name"
        // A backstop so a wake lock can never outlive a forgotten ride by days.
        private const val MAX_RIDE_MS = 12L * 60 * 60 * 1000
    }
}
