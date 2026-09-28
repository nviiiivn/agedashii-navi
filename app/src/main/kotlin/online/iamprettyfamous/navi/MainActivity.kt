package online.iamprettyfamous.navi

import android.Manifest
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import online.iamprettyfamous.navi.map.MapFiles
import online.iamprettyfamous.navi.map.RideMapController
import online.iamprettyfamous.navi.map.RideMapController.Problem
import android.content.res.Configuration
import androidx.compose.ui.platform.LocalConfiguration
import online.iamprettyfamous.navi.core.dash.Anchor
import online.iamprettyfamous.navi.core.dash.DashState
import online.iamprettyfamous.navi.core.dash.Hud
import online.iamprettyfamous.navi.core.dash.Layout
import online.iamprettyfamous.navi.dash.HudOverlay
import online.iamprettyfamous.navi.record.RecState
import online.iamprettyfamous.navi.record.RideService
import online.iamprettyfamous.navi.sensors.GpsSource
import online.iamprettyfamous.navi.ui.PALETTES
import online.iamprettyfamous.navi.ui.Palette
import online.iamprettyfamous.navi.ui.palette

/**
 * Ride: the map, her bike, and for now the least chrome that proves phase 1 —
 * a follow button, and temporary theme and bike buttons until Settings exists.
 */
class MainActivity : ComponentActivity() {

    private lateinit var map: RideMapController
    private lateinit var gps: GpsSource
    private val prefs by lazy { getSharedPreferences("navi", MODE_PRIVATE) }

    private var theme by mutableStateOf("night")
    private var bike by mutableStateOf("yellow")
    private var following by mutableStateOf(false)
    private var problem by mutableStateOf<Problem?>(null)
    private var locationGranted by mutableStateOf(false)

    /**
     * What the dash draws. Until the phase 3 ride service publishes the full
     * DashState (agreed with minion 1), only what a bare fix knows - speed and
     * altitude - and a dash for everything else.
     */
    private var dash by mutableStateOf(DashState())
    private var hudLayout by mutableStateOf(Layout.DEFAULT)

    private val askLocation =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            locationGranted = result.values.any { it }
            if (locationGranted) {
                map.enableLocation()
                startGps()
            }
        }

    private fun startGps() {
        if (locationGranted) {
            gps.start(FIX_INTERVAL_MS) { fix ->
                map.showFix(fix)
                dash = DashState(
                    imperial = prefs.getString("units", "imperial") != "metric",
                    machine = prefs.getString("machine", "cb750"),
                    speedMs = if (fix.hasSpeed()) fix.speed.toDouble() else null,
                    altM = if (fix.hasAltitude()) fix.altitude else null,
                )
            }
        }
    }

    // Asked for once, at the first recording. Refused, the ride still records;
    // only the notification with its Stop button goes missing.
    private val askNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    /** Set by the first tap on a running recording; a second tap within 3 s stops it. */
    private var stopArmedAt by mutableStateOf<Long?>(null)

    private fun onRecordTap() {
        val rec = RideService.state.value
        if (!rec.recording) {
            startRecording()
            return
        }
        val armed = stopArmedAt
        val now = SystemClock.elapsedRealtime()
        if (armed != null && now - armed < STOP_CONFIRM_MS) {
            stopArmedAt = null
            RideService.stop(this)
        } else {
            // One glove-tap on the wrong button must not end a ride.
            stopArmedAt = now
        }
    }

    private fun startRecording() {
        if (!locationGranted) return askLocation.launch(FINE_OR_COARSE)
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        RideService.start(this)
    }

    /**
     * Debug builds only: `am start ... --ez record true|false` starts or stops a
     * ride, so a 30-minute test can run with no hand on the screen.
     */
    private fun handleTestIntent(intent: Intent?) {
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) return
        if (intent?.hasExtra("record") != true) return
        val want = intent.getBooleanExtra("record", false)
        val rec = RideService.state.value.recording
        Log.i("NaviRec", "test intent record=$want (recording=$rec)")
        if (want && !rec) startRecording()
        if (!want && rec) RideService.stop(this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleTestIntent(intent)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // keep_awake defaults on: the screen stays lit while riding.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        theme = prefs.getString("theme", "night") ?: "night"
        bike = prefs.getString("bike", "yellow") ?: "yellow"
        hudLayout = Layout.of(prefs.getString("hud", Layout.DEFAULT.key))

        gps = GpsSource(this)
        map = RideMapController(this)
        map.onFollowingChanged = { following = it }
        map.onProblem = { problem = it }
        map.onCreate(savedInstanceState, theme, bike, prefs.getBoolean("north_up", false))

        locationGranted = FINE_OR_COARSE.any {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
        if (locationGranted) map.enableLocation() else askLocation.launch(FINE_OR_COARSE)

        setContent { Ride() }
        // A ride Android cut off while the app was dead is found and reported here.
        RideService.recoverInterrupted(this)
        handleTestIntent(intent)
    }

    @Composable
    private fun Ride() {
        val p = palette(theme)
        var fixAge by remember { mutableStateOf<Double?>(null) }
        LaunchedEffect(Unit) {
            while (true) {
                fixAge = gps.ageSeconds()
                delay(1000)
            }
        }
        val rec by RideService.state.collectAsState()
        var nowMs by remember { mutableStateOf(System.currentTimeMillis()) }
        LaunchedEffect(Unit) {
            while (true) {
                nowMs = System.currentTimeMillis()
                if (stopArmedAt?.let { SystemClock.elapsedRealtime() - it > STOP_CONFIRM_MS } == true) {
                    stopArmedAt = null
                }
                delay(500)
            }
        }
        Box(Modifier.fillMaxSize().background(p.bg)) {
            AndroidView(factory = { map.mapView }, modifier = Modifier.fillMaxSize())

            // The dash over the map. Landscape puts the gauges down her chosen
            // side (right by default), portrait along her chosen edge (bottom).
            val wide = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
            val anchor = if (wide) {
                if (prefs.getString("hud_side", "right") == "left") Anchor.LEFT else Anchor.RIGHT
            } else {
                if (prefs.getString("hud_edge", "bottom") == "top") Anchor.TOP else Anchor.BOTTOM
            }
            HudOverlay(Hud.plan(dash, hudLayout, wide, anchor), p)

            // Top left, one column of round buttons (spec section 03), so the
            // bottom edge belongs to the ribbon. Record first, where the spec
            // puts it under the future menu, with its line BESIDE it; the status
            // message last, so a long one can never push a button off screen.
            // Theme, bike and layout are temporary until Settings exists.
            Column(
                Modifier.align(Alignment.TopStart).safeDrawingPadding().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RoundButton(if (rec.recording) "■" else "●", p, if (rec.recording) p.bad else p.accent) {
                        onRecordTap()
                    }
                    recordLine(rec, nowMs)?.let { Chip(it, p) }
                }
                RoundButton(p.name.take(2), p) {
                    val next = PALETTES[(PALETTES.indexOfFirst { it.key == theme } + 1) % PALETTES.size]
                    theme = next.key
                    prefs.edit().putString("theme", theme).apply()
                    map.applyTheme(theme)
                }
                RoundButton(if (bike == "yellow") "Y" else "B", p) {
                    bike = if (bike == "yellow") "black" else "yellow"
                    prefs.edit().putString("bike", bike).apply()
                    map.setBike(bike)
                }
                RoundButton(hudLayout.title.take(2), p) {
                    val shown = Layout.entries.filter { it != Layout.OFF }
                    hudLayout = shown[(shown.indexOf(hudLayout) + 1) % shown.size]
                    prefs.edit().putString("hud", hudLayout.key).apply()
                }
                if (locationGranted && !following) RoundButton("◎", p) { map.follow() }
                message(problem, locationGranted, fixAge)?.let { Chip(it, p) }
            }
        }
    }

    /** The recording in one line: running, just stopped, or why it could not start. */
    private fun recordLine(rec: RecState, nowMs: Long): String? {
        if (rec.recording) {
            if (stopArmedAt != null) return "Tap ■ again to stop"
            val secs = ((nowMs / 1000.0) - (rec.startedS ?: nowMs / 1000.0)).toLong().coerceAtLeast(0)
            val miles = prefs.getString("units", "imperial") != "metric"
            val dist = if (miles) "%.1f mi".format(rec.distanceM / 1609.344) else "%.1f km".format(rec.distanceM / 1000)
            val waiting = if (rec.samples == 0) " · waiting for GPS" else ""
            return "REC %d:%02d · %s · %d rows%s".format(secs / 60, secs % 60, dist, rec.samples, waiting)
        }
        rec.errors.firstOrNull()?.let { return it }
        rec.cutOff?.let { why ->
            val miles = prefs.getString("units", "imperial") != "metric"
            val dist = if (miles) "%.1f mi".format(rec.distanceM / 1609.344) else "%.1f km".format(rec.distanceM / 1000)
            return "$why · ${rec.samples} rows · $dist"
        }
        if (rec.path != null) {
            val kinds = rec.exported.joinToString(" ") { it.substringAfterLast('.').uppercase() }
            return "Saved ${rec.samples} rows${if (kinds.isNotEmpty()) " · $kinds" else ""}"
        }
        return null
    }

    /** One honest line about why the map or the bike is not where she expects. */
    private fun message(problem: Problem?, granted: Boolean, fixAge: Double?): String? = when {
        problem == Problem.NO_TILES ->
            "No map file. Copy ${MapFiles.TILES_NAME} to\n${map.tiles?.path ?: "app storage"}"
        problem == Problem.UNREADABLE_STORAGE ->
            "Navi can't read its storage folder: it was made before Navi first ran.\n" +
                "Reinstall, open Navi once, then copy ${MapFiles.TILES_NAME} again"
        problem == Problem.BAD_TILES -> "${MapFiles.TILES_NAME} is not a readable PMTiles v3 archive"
        problem == Problem.NO_STYLE -> "This build has no map style"
        !granted -> "Location is off for Navi — no bike on the map"
        !gps.hasGps -> "This phone reports no GPS receiver"
        fixAge == null -> "Waiting for GPS"
        fixAge > STALE_FIX_S -> "GPS lost ${fixAge.toInt()} s ago"
        else -> null
    }

    @Composable
    private fun Chip(text: String, p: Palette) {
        BasicText(
            text,
            style = TextStyle(color = p.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
            modifier = Modifier
                .background(p.surface, RoundedCornerShape(8.dp))
                .border(1.dp, p.border, RoundedCornerShape(8.dp))
                .padding(horizontal = 14.dp, vertical = 8.dp),
        )
    }

    /** 56 dp: the spec's minimum target, for gloves. */
    @Composable
    private fun RoundButton(label: String, p: Palette, ink: Color = p.accent, onClick: () -> Unit) {
        Box(
            Modifier.size(56.dp)
                .background(p.surface, CircleShape)
                .border(1.dp, p.border, CircleShape)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            BasicText(label, style = TextStyle(color = ink, fontSize = 16.sp, fontWeight = FontWeight.Bold))
        }
    }

    // GPS runs while Ride is visible. Recording with the screen off is phase 3's
    // foreground service, not this activity.
    override fun onStart() { super.onStart(); map.onStart(); startGps() }
    override fun onResume() { super.onResume(); map.onResume(); map.recheck() }
    override fun onPause() { map.onPause(); super.onPause() }
    override fun onStop() { gps.stop(); map.onStop(); super.onStop() }
    override fun onLowMemory() { super.onLowMemory(); map.onLowMemory() }
    override fun onDestroy() { map.onDestroy(); super.onDestroy() }
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        map.onSaveInstanceState(outState)
    }

    private companion object {
        val FINE_OR_COARSE = arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        )
        const val STALE_FIX_S = 10.0
        const val STOP_CONFIRM_MS = 3000L
        // The spec's `hz` preference defaults to 1.
        const val FIX_INTERVAL_MS = 1000L
    }
}
