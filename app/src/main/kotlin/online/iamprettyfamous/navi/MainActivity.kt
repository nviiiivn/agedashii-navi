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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import online.iamprettyfamous.navi.map.MapFiles
import online.iamprettyfamous.navi.map.RideMapController
import online.iamprettyfamous.navi.map.RideMapController.Problem
import android.content.res.Configuration
import androidx.compose.ui.platform.LocalConfiguration
import online.iamprettyfamous.navi.core.dash.Anchor
import online.iamprettyfamous.navi.core.dash.Hud
import online.iamprettyfamous.navi.core.dash.Layout
import online.iamprettyfamous.navi.dash.HudOverlay
import online.iamprettyfamous.navi.record.RecState
import online.iamprettyfamous.navi.record.RideService
import online.iamprettyfamous.navi.record.Telemetry
import online.iamprettyfamous.navi.prefs.Prefs
import online.iamprettyfamous.navi.ui.Changed
import online.iamprettyfamous.navi.ui.SettingsPanel
import androidx.activity.compose.BackHandler
import online.iamprettyfamous.navi.ui.ButtonColumnLayout
import online.iamprettyfamous.navi.ui.Chip
import online.iamprettyfamous.navi.ui.FollowGlyph
import online.iamprettyfamous.navi.ui.RoundButton
import online.iamprettyfamous.navi.ui.Palette
import online.iamprettyfamous.navi.ui.palette

/**
 * Ride: the map, her bike, and for now the least chrome that proves phase 1 —
 * a follow button, and temporary theme and bike buttons until Settings exists.
 */
class MainActivity : ComponentActivity() {

    private lateinit var map: RideMapController
    private val prefs by lazy { getSharedPreferences("navi", MODE_PRIVATE) }

    private var theme by mutableStateOf("night")
    private var bike by mutableStateOf("yellow")
    private var following by mutableStateOf(false)
    private var problem by mutableStateOf<Problem?>(null)
    private var locationGranted by mutableStateOf(false)

    private var hudLayout by mutableStateOf(Layout.DEFAULT)
    private var dashOn by mutableStateOf(true)

    private val settings by lazy { Prefs(this) }
    private var settingsOpen by mutableStateOf(false)
    private var settingsRev by mutableStateOf(0)

    /** Applies a changed setting at once - never "from the next start". */
    private fun applySetting(what: Changed) {
        when (what) {
            Changed.THEME -> { theme = settings.theme; map.applyTheme(theme) }
            Changed.BIKE -> { bike = settings.bike; map.setBike(bike) }
            Changed.BUILDINGS -> map.setBuildings(settings.buildings)
            Changed.NORTH_UP -> map.setNorthUp(settings.northUp)
            Changed.KEEP_AWAKE -> keepAwake(settings.keepAwake)
            Changed.HUD -> { hudLayout = Layout.of(settings.hud); dashOn = settings.dash }
            Changed.TELEMETRY -> Telemetry.refreshPrefs(this)
        }
        settingsRev++
    }

    private fun keepAwake(on: Boolean) {
        if (on) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private val askLocation =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            locationGranted = result.values.any { it }
            if (locationGranted) {
                map.enableLocation()
                startGps()
            }
        }

    /** The Ride screen holds telemetry while it is visible; a recording holds it on its own. */
    private var holding = false

    private fun startGps() {
        if (!locationGranted || holding) return
        holding = true
        Telemetry.acquire(this, "screen")
    }

    private fun stopGps() {
        if (!holding) return
        holding = false
        Telemetry.release("screen")
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
        if (intent?.hasExtra("buildings") == true) {
            val on = intent.getBooleanExtra("buildings", false)
            prefs.edit().putBoolean("buildings", on).apply()
            map.setBuildings(on)
        }
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
        keepAwake(settings.keepAwake)

        theme = prefs.getString("theme", "night") ?: "night"
        bike = prefs.getString("bike", "yellow") ?: "yellow"
        hudLayout = Layout.of(prefs.getString("hud", Layout.DEFAULT.key))
        dashOn = settings.dash

        map = RideMapController(this)
        map.onFollowingChanged = { following = it }
        map.onProblem = { problem = it }
        map.onCreate(savedInstanceState, theme, bike, prefs.getBoolean("north_up", false))
        // Buildings: off unless she turns them on (Settings, when it exists).
        map.setBuildings(prefs.getBoolean("buildings", false))

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
                fixAge = Telemetry.fixAgeSeconds()
                delay(1000)
            }
        }
        // The map follows the same fixes the telemetry uses; there is one GPS listener.
        LaunchedEffect(Unit) { Telemetry.fix.collect { it?.let(map::showFix) } }
        val rec by RideService.state.collectAsState()
        // Built on the ride thread from every sensor (minion 1's Telemetry), recording or not.
        val dash by Telemetry.dash.collectAsState()
        // Sensors on demand: not recording, only the sensors behind what this layout draws run.
        // Gauges off means no gauges AND no gauge sensors.
        LaunchedEffect(hudLayout, dashOn) { Telemetry.setDashChannels(Hud.channels(hudLayout, dashOn)) }
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
        @Suppress("UNUSED_VARIABLE") val rev = settingsRev // a change in Settings redraws the ride screen
        val sensorLines by Telemetry.sensors.collectAsState()
        BackHandler(enabled = settingsOpen) { settingsOpen = false }
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
            // How tall the lines beside record are, measured: the dash moves out from under them.
            var linesDp by remember { mutableStateOf(0) }
            HudOverlay(if (dashOn) Hud.plan(dash, hudLayout, wide, anchor) else null, p, linesDp = linesDp)

            // Top left, one column of round buttons (spec section 03), so the
            // bottom edge belongs to the ribbon: the menu first, record under it
            // (ButtonColumn.RECORD_SLOT) with the recording line and the status
            // message BESIDE it and out of the column's flow - a line appearing
            // never moves a button, never pushes one off screen, and the dash
            // clears all of it through ButtonColumn.
            ButtonColumnLayout(
                onLinesHeight = { linesDp = it },
                lines = {
                    // Recording is shouted, not implied: the gauges show numbers either
                    // way, so whether a ride is being WRITTEN is this red-edged chip
                    // and the red square, and nothing else.
                    recordLine(rec, nowMs)?.let { line ->
                        if (rec.recording) Chip("● $line", p, edge = p.bad, ink = p.bad) else Chip(line, p)
                    }
                    message(problem, locationGranted, fixAge)?.let { Chip(it, p) }
                },
            ) {
                RoundButton("☰", p) { settingsOpen = true }
                RoundButton(if (rec.recording) "■" else "●", p, if (rec.recording) p.bad else p.accent) {
                    onRecordTap()
                }
                if (locationGranted && !following) RoundButton(p, { map.follow() }) { FollowGlyph(p.accent) }
            }

            if (settingsOpen) {
                SettingsPanel(
                    p = p, prefs = settings, rev = settingsRev, sensors = sensorLines,
                    fixAgeS = fixAge, fixAccM = Telemetry.fix.value?.takeIf { it.hasAccuracy() }?.accuracy?.toDouble(),
                    version = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull() ?: "",
                    onChanged = ::applySetting,
                    onClose = { settingsOpen = false },
                )
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
        problem == Problem.STYLE_MISSING -> "No ${palette(theme).name} map in this build — showing Night's"
        !granted -> "Location is off for Navi — no bike on the map"
        !Telemetry.hasGps(this) -> "This phone reports no GPS receiver"
        fixAge == null -> "Waiting for GPS"
        fixAge > STALE_FIX_S -> "GPS lost ${fixAge.toInt()} s ago"
        else -> null
    }

    // GPS runs while Ride is visible. Recording with the screen off is phase 3's
    // foreground service, not this activity.
    override fun onStart() { super.onStart(); map.onStart(); startGps() }
    override fun onResume() { super.onResume(); map.onResume(); map.recheck() }
    override fun onPause() { map.onPause(); super.onPause() }
    override fun onStop() { stopGps(); map.onStop(); super.onStop() }
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
    }
}
