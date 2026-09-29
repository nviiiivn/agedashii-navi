package online.iamprettyfamous.navi.routing

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import online.iamprettyfamous.navi.MainActivity
import online.iamprettyfamous.navi.record.Telemetry

/**
 * Keeps GPS, the guidance and the voice running while a route is followed,
 * screen on or off - the same foreground location service a recording uses.
 * AGEDASHII's ruling: "a rider following turn-by-turn with the screen off and
 * the phone in a tank bag is not an edge case, it is Tuesday." Before this,
 * locking the screen without recording released the GPS, and the voice fell
 * silent mid-route.
 *
 * Started when a route begins, stopped when it ends; its notification says
 * where to, and ends the route from there too.
 */
class GuideService : Service() {
    private var wake: PowerManager.WakeLock? = null
    private var holding = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_GUIDE -> guide(intent.getStringExtra(EXTRA_TO) ?: "your pin")
            ACTION_END -> {
                Navigator.clear()
                done()
            }
            else -> done()
        }
        return START_NOT_STICKY
    }

    private fun guide(to: String) {
        try {
            // Within five seconds of startForegroundService(), or Android kills us.
            ServiceCompat.startForeground(this, NOTIFY_ID, notification(to), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } catch (e: RuntimeException) {
            // No location permission, or started from the background: guidance
            // still runs while the screen is on, as it did before.
            Log.w(TAG, "could not hold the GPS for the route: ${e.message}")
            done()
            return
        }
        running = true
        if (holding) return
        holding = true
        Telemetry.acquire(this, "route")
        wake = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "navi:route")
            .apply { setReferenceCounted(false); acquire(MAX_ROUTE_MS) }
        Log.i(TAG, "holding the GPS for the route to $to")
    }

    private fun done() {
        if (holding) {
            holding = false
            Telemetry.release("route")
        }
        wake?.takeIf { it.isHeld }?.release()
        wake = null
        running = false
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        if (holding) Telemetry.release("route")
        holding = false
        wake?.takeIf { it.isHeld }?.release()
        running = false
        super.onDestroy()
    }

    private fun notification(to: String): Notification {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Route guidance", NotificationManager.IMPORTANCE_LOW))
        }
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val end = PendingIntent.getService(this, 2, Intent(this, GuideService::class.java).setAction(ACTION_END),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_directions)
            .setContentTitle("Navi")
            .setContentText("Guiding you to $to")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(0, "End route", end)
            .build()
    }

    companion object {
        private const val TAG = "NaviGuide"
        private const val CHANNEL = "route"
        private const val NOTIFY_ID = 2
        private const val ACTION_GUIDE = "online.iamprettyfamous.navi.routing.GUIDE"
        private const val ACTION_END = "online.iamprettyfamous.navi.routing.END"
        private const val ACTION_STOP = "online.iamprettyfamous.navi.routing.STOP"
        private const val EXTRA_TO = "to"
        private const val MAX_ROUTE_MS = 12L * 60 * 60 * 1000

        @Volatile private var running = false

        /** A route began (or its destination's name changed). From the foreground. */
        fun start(context: Context, to: String) = ContextCompat.startForegroundService(
            context, Intent(context, GuideService::class.java).setAction(ACTION_GUIDE).putExtra(EXTRA_TO, to))

        /** The route ended. */
        fun stop(context: Context) {
            if (running) context.startService(Intent(context, GuideService::class.java).setAction(ACTION_STOP))
        }
    }
}
