package online.iamprettyfamous.navi

import android.Manifest
import android.content.Intent
import android.content.pm.ActivityInfo
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
import kotlinx.coroutines.flow.first
import online.iamprettyfamous.navi.core.telemetry.Physics
import online.iamprettyfamous.navi.map.MapFiles
import online.iamprettyfamous.navi.map.RideMapController
import online.iamprettyfamous.navi.map.RideMapController.Problem
import android.content.res.Configuration
import androidx.compose.ui.platform.LocalConfiguration
import online.iamprettyfamous.navi.core.dash.Anchor
import online.iamprettyfamous.navi.core.dash.Hud
import online.iamprettyfamous.navi.core.dash.Layout
import online.iamprettyfamous.navi.dash.HudOverlay
import online.iamprettyfamous.navi.core.theme.Role
import online.iamprettyfamous.navi.dash.ink
import online.iamprettyfamous.navi.dash.TurnBannerView
import online.iamprettyfamous.navi.core.dash.TurnBanner
import online.iamprettyfamous.navi.core.guidance.Status
import online.iamprettyfamous.navi.core.guidance.Guidance
import online.iamprettyfamous.navi.record.RecState
import online.iamprettyfamous.navi.record.RideService
import online.iamprettyfamous.navi.record.Telemetry
import online.iamprettyfamous.navi.prefs.Prefs
import online.iamprettyfamous.navi.ui.Changed
import online.iamprettyfamous.navi.ui.SettingsPanel
import online.iamprettyfamous.navi.ui.micAccount
import online.iamprettyfamous.navi.routing.Router
import online.iamprettyfamous.navi.core.geo.LatLon
import online.iamprettyfamous.navi.core.geo.Maneuver
import androidx.activity.compose.BackHandler
import online.iamprettyfamous.navi.ui.ButtonColumn
import online.iamprettyfamous.navi.ui.ButtonColumnLayout
import online.iamprettyfamous.navi.ui.CameraClear
import online.iamprettyfamous.navi.ui.TopStack
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.IntSize
import online.iamprettyfamous.navi.ui.Chip
import online.iamprettyfamous.navi.ui.FollowGlyph
import online.iamprettyfamous.navi.ui.RoundButton
import online.iamprettyfamous.navi.ui.Palette
import online.iamprettyfamous.navi.ui.palette
import online.iamprettyfamous.navi.routing.Navigator
import online.iamprettyfamous.navi.core.daylight.Daylight
import online.iamprettyfamous.navi.routing.GuideService
import online.iamprettyfamous.navi.voice.Voice
import online.iamprettyfamous.navi.places.PlacesStore
import online.iamprettyfamous.navi.data.DataPack
import online.iamprettyfamous.navi.ui.SearchGlyph
import online.iamprettyfamous.navi.ui.WhereToPanel
import online.iamprettyfamous.navi.ui.HelpPanel
import online.iamprettyfamous.navi.ui.PinBand
import online.iamprettyfamous.navi.ui.RouteStrip
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.ui.platform.LocalDensity

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
            Changed.THEME -> {
                // Picking a theme by hand ends following the sun (the Flet app's rule).
                if (settings.autoTheme) { settings.autoTheme = false; sunPhase = null; brightness(null) }
                theme = settings.theme; map.applyTheme(theme)
            }
            Changed.AUTO_THEME -> {
                sunPhase = null
                if (settings.autoTheme) followSun() else { brightness(null); theme = settings.theme; map.applyTheme(theme) }
            }
            Changed.BIKE -> { bike = settings.bike; map.setBike(bike) }
            Changed.BUILDINGS -> map.setBuildings(settings.buildings)
            Changed.NORTH_UP -> map.setNorthUp(settings.northUp)
            Changed.KEEP_AWAKE -> keepAwake(settings.keepAwake)
            Changed.HUD -> { hudLayout = Layout.of(settings.hud); dashOn = settings.dash }
            Changed.TELEMETRY -> Telemetry.refreshPrefs(this)
            Changed.ORIENTATION -> applyOrientation()
            Changed.OFFROUTE -> Navigator.setOffRouteM(settings.offrouteM)
            Changed.VOICE -> {
                Voice.enabled = settings.voice
                Voice.streets = settings.voiceStreets
                Voice.character.on = settings.voiceCharacter
                Voice.character.taps = settings.voiceTaps
            }
            Changed.MIC_ON -> micOn()
            Changed.MIC_OFF -> {
                RideService.micWanted = false
                Telemetry.setMic(this, false)
                RideService.refreshTypes(this)
            }
        }
        settingsRev++
    }

    private val askMic =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) micOn()
        }

    /** The microphone on, for this ride: permission first, then the service told before capture starts. */
    private fun micOn() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) return askMic.launch(Manifest.permission.RECORD_AUDIO)
        RideService.micWanted = true
        RideService.refreshTypes(this)
        Telemetry.setMic(this, true)
    }

    /** Her "Which way up", held against a lean the accelerometer reads as a rotation. */
    private fun applyOrientation() {
        requestedOrientation = when (settings.orientation) {
            "landscape" -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            "portrait" -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            else -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    /** The day's phase last applied, while following the sun. */
    private var sunPhase: String? = null

    /**
     * Theme and screen brightness where the sun says, from her position (the
     * clock before the first fix). Only acts when the phase changes - four
     * times a day - and never stores the theme as her choice.
     */
    private fun followSun() {
        if (!settings.autoTheme) return
        val fix = Telemetry.fix.value
        val phase = Daylight.phase(fix?.latitude, fix?.longitude, System.currentTimeMillis() / 1000.0)
        if (phase == sunPhase) return
        sunPhase = phase
        brightness(Daylight.brightnessFor(phase))
        val want = Daylight.themeFor(phase)
        if (want != theme) { theme = want; map.applyTheme(want) }
    }

    /** The window's brightness, or the phone's own with null. */
    private fun brightness(level: Double?) {
        window.attributes = window.attributes.apply {
            screenBrightness = level?.toFloat() ?: WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        }
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

    /** The point she long-pressed, offered as a destination and not chosen yet. */
    private var pin by mutableStateOf<LatLon?>(null)

    /** The offered point's name and "kind · town", when it came from Where to. */
    private var pinName by mutableStateOf<String?>(null)
    private var pinAbout by mutableStateOf<String?>(null)

    /** The destination's name, for arrival and the "route ends" line. */
    private var destName by mutableStateOf<String?>(null)

    private var whereToOpen by mutableStateOf(false)
    private var helpOpen by mutableStateOf(false)

    /** What setting the level last had to say, when it could not. */
    private var levelNote by mutableStateOf("")

    private fun offer(at: LatLon, name: String?, about: String?) {
        pin = at
        pinName = name
        pinAbout = about
    }

    private fun routeToPin(at: LatLon) {
        // The name becomes the destination's only once its route exists: set
        // here, a refused Napa renamed the Chabot route still being followed
        // ("Route ends at Skyline Boulevard, 300 ft from Napa").
        pendingName = pinName
        Navigator.go(at, settings.imperial)
    }

    private var pendingName: String? = null

    /** The same two-tap guard for ending a route. */
    private var endArmedAt by mutableStateOf<Long?>(null)

    private fun onEndTap() {
        val armed = endArmedAt
        val now = SystemClock.elapsedRealtime()
        if (armed != null && now - armed < STOP_CONFIRM_MS) {
            endArmedAt = null
            destName = null
            Navigator.clear()
        } else {
            endArmedAt = now
        }
    }

    private fun cancelPin() {
        pin?.let(Navigator::forget)
        pin = null
        pinName = null
        pinAbout = null
    }

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
            // Stopped by hand: no automatic restart until the bike has stopped.
            autoRecordHeld = true
        } else {
            // One glove-tap on the wrong button must not end a ride.
            stopArmedAt = now
        }
    }

    // Record automatically: fixes in a row at rolling speed, and whether a
    // recording she stopped by hand is holding it off until the bike stops.
    private var rollingFixes = 0
    private var stoppedSinceMs: Long? = null
    private var autoRecordHeld = false

    /** Every fix while the Ride screen is up: start a ride log once the bike is rolling. */
    private fun autoRecord(fix: android.location.Location) {
        if (!settings.autoRecord) return
        val speed = if (fix.hasSpeed()) fix.speed else 0f
        val now = SystemClock.elapsedRealtime()
        if (speed < STOPPED_MS) {
            rollingFixes = 0
            val since = stoppedSinceMs ?: now.also { stoppedSinceMs = it }
            if (now - since > 30_000) autoRecordHeld = false
            return
        }
        stoppedSinceMs = null
        rollingFixes = if (speed >= ROLLING_MS) rollingFixes + 1 else 0
        if (rollingFixes >= 3 && !autoRecordHeld && !RideService.state.value.recording) {
            Log.i("NaviRec", "recording automatically: rolling at %.1f m/s".format(speed))
            rollingFixes = 0
            startRecording()
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
        intent?.getStringExtra("route")?.let { spec ->
            // Debug: "lat,lon_lat,lon" routes between two points and logs the result.
            runCatching {
                val (a, b) = spec.split("_").map { it.split(",").map(String::toDouble) }
                Router.route(LatLon(a[0], a[1]), LatLon(b[0], b[1]), settings.imperial) { }
            }.onFailure { Log.w("NaviRouter", "bad route extra '$spec': ${it.message}") }
        }
        // Debug: "lat,lon" offers a pin as a long press would; routeto also routes to it.
        for (key in listOf("pin", "routeto")) intent?.getStringExtra(key)?.let { spec ->
            runCatching {
                val (lat, lon) = spec.split(",").map(String::toDouble)
                offer(LatLon(lat, lon), null, null)
                if (key == "routeto") routeToPin(LatLon(lat, lon))
            }.onFailure { Log.w("NaviGuide", "bad $key extra '$spec': ${it.message}") }
        }
        if (intent?.getBooleanExtra("endroute", false) == true) Navigator.clear()
        // Debug: search the places list as if typed into Where to.
        intent?.getStringExtra("search")?.let { q ->
            whereToOpen = true
            PlacesStore.search(q, Telemetry.fix.value?.let { LatLon(it.latitude, it.longitude) })
        }
        intent?.getStringExtra("say")?.let { Voice.say(it) }
        // Debug: "kind,band,metres,instruction" plays one Follower-shaped prompt
        // through her clips, as a ride would - so a mixed prompt (her clip, the
        // phone's words, her clip) can be proved on a phone that is not moving.
        intent?.getStringExtra("prompt")?.split(",", limit = 4)?.takeIf { it.size == 4 }?.let { (kind, band, metres, words) ->
            val m = metres.toDoubleOrNull()
            val say = Guidance.spoken(words, "", m, settings.imperial, band)
            // Each test prompt is its own maneuver, as on a route, so her character's per-maneuver rules apply.
            val man = Maneuver(words, "", 0.0, LatLon(0.0, 0.0), ++testManeuvers, 0.0, kind = kind)
            Voice.prompt(Status(ok = true, maneuver = man, say = say, kind = kind, band = band, turnInM = m, instruction = words), settings.imperial)
        }
        if (intent?.hasExtra("mic") == true) {
            applySetting(if (intent.getBooleanExtra("mic", false)) Changed.MIC_ON else Changed.MIC_OFF)
        }
        if (intent?.hasExtra("record") != true) return
        val want = intent.getBooleanExtra("record", false)
        val rec = RideService.state.value.recording
        Log.i("NaviRec", "test intent record=$want (recording=$rec)")
        if (want && !rec) startRecording()
        if (!want && rec) RideService.stop(this)
    }

    private var testManeuvers = 0

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleTestIntent(intent)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // keep_awake defaults on: the screen stays lit while riding.
        keepAwake(settings.keepAwake)
        applyOrientation()
        Navigator.setOffRouteM(settings.offrouteM)

        theme = prefs.getString("theme", "night") ?: "night"
        if (settings.autoTheme) {
            val phase = Daylight.phase(null, null, System.currentTimeMillis() / 1000.0)
            theme = Daylight.themeFor(phase)
        }
        bike = prefs.getString("bike", "yellow") ?: "yellow"
        hudLayout = Layout.of(prefs.getString("hud", Layout.DEFAULT.key))
        dashOn = settings.dash

        map = RideMapController(this)
        map.onFollowingChanged = { following = it }
        map.onProblem = { problem = it }
        map.onLongPress = { offer(it, null, null) }
        map.onCreate(savedInstanceState, theme, bike, prefs.getBoolean("north_up", false))
        // Buildings: off unless she turns them on (Settings, when it exists).
        map.setBuildings(prefs.getBoolean("buildings", false))

        locationGranted = FINE_OR_COARSE.any {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
        if (locationGranted) map.enableLocation() else askLocation.launch(FINE_OR_COARSE)

        Router.load(this)
        PlacesStore.open(this)
        // The map data this APK carries, unpacked on first start (and after an
        // update with new data); then everything that reads it looks again.
        DataPack.ensure(this) {
            map.reloadTiles()
            Router.reload(this)
            PlacesStore.open(this)
        }
        Voice.enabled = settings.voice
        Voice.streets = settings.voiceStreets
        Voice.character.on = settings.voiceCharacter
        Voice.character.taps = settings.voiceTaps
        Voice.start(this)
        Voice.opening()
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
        LaunchedEffect(Unit) { Telemetry.fix.collect { it?.let { f -> map.showFix(f); autoRecord(f) } } }
        // The sun, once a minute: the phase changes over minutes, not seconds.
        LaunchedEffect(Unit) {
            while (true) {
                followSun()
                delay(60_000)
            }
        }
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
                if (endArmedAt?.let { SystemClock.elapsedRealtime() - it > STOP_CONFIRM_MS } == true) {
                    endArmedAt = null
                }
                delay(500)
            }
        }
        @Suppress("UNUSED_VARIABLE") val rev = settingsRev // a change in Settings redraws the ride screen
        val sensorLines by Telemetry.sensors.collectAsState()
        val micState by Telemetry.mic.collectAsState()
        val level by Telemetry.level.collectAsState()
        val lastFix by Telemetry.fix.collectAsState()
        val nav by Navigator.nav.collectAsState()
        val voice by Voice.state.collectAsState()
        LaunchedEffect(nav.route) { map.showRoute(nav.route?.points) }
        // A route holds the GPS the way a recording does, so guidance and the
        // voice carry on with the screen locked - until she arrives, as the Flet
        // app keys its service (minion 3). The service watches for arrival
        // itself: locked, nothing here runs to tell it.
        val guiding = nav.route != null && nav.status?.arrived != true
        LaunchedEffect(guiding, destName) {
            if (guiding) GuideService.start(this@MainActivity, destName ?: "your pin")
            // Arrived, the service lets go once the arrival lines have played.
            else if (nav.route == null) GuideService.stop(this@MainActivity)
        }
        LaunchedEffect(nav.dest, pin) { map.showMarks(nav.dest, pin) }
        // Routed: the offered pin has become the destination.
        LaunchedEffect(nav.dest, nav.route) {
            if (nav.route != null && nav.dest == pin) {
                destName = pendingName
                pin = null
            }
        }
        // A refused destination's card goes once she rides on: it stayed up
        // through 110 s of riding north (emulator ride v7), over the map,
        // waiting for a Try again she had plainly not wanted. Standing still,
        // it waits for her.
        LaunchedEffect(nav.failedFor) {
            val at = nav.failedFor ?: return@LaunchedEffect
            var from: LatLon? = null
            Telemetry.fix.first { f ->
                val here = f?.let { LatLon(it.latitude, it.longitude) } ?: return@first false
                val start = from ?: here.also { from = it }
                Physics.haversineM(start, here) > REFUSED_CARD_RIDDEN_M
            }
            if (pin == at) cancelPin()
        }
        BackHandler(enabled = settingsOpen && !helpOpen) { settingsOpen = false }
        BackHandler(enabled = helpOpen) { helpOpen = false }
        BackHandler(enabled = !settingsOpen && pin != null && !whereToOpen) { cancelPin() }
        BackHandler(enabled = whereToOpen && !settingsOpen) { whereToOpen = false }
        val places by PlacesStore.state.collectAsState()
        val dataPack by DataPack.state.collectAsState()
        var screenSize by remember { mutableStateOf(IntSize.Zero) }
        Box(Modifier.fillMaxSize().background(p.bg).onSizeChanged { screenSize = it }) {
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
            var topStack by remember { mutableStateOf(TopStack()) }
            // The camera centres below the banner and clear of the dash's panels,
            // so the bike is never under either (CameraClear).
            val density = LocalDensity.current
            val safeTop = WindowInsets.safeDrawing.getTop(density)
            val safeLeft = WindowInsets.safeDrawing.getLeft(density, LocalLayoutDirection.current)
            var dashBoxes by remember { mutableStateOf(emptyList<Rect>()) }
            LaunchedEffect(topStack, safeTop, safeLeft, dashBoxes, screenSize, wide) {
                val top = if (topStack.banner) safeTop + topStack.clearDp * density.density.toDouble() else 0.0
                map.setClear(CameraClear.of(screenSize.width.toFloat(), screenSize.height.toFloat(), density.density, top,
                    dashBoxes, wide, safeLeft + ButtonColumn.CLEAR_DP * density.density).array())
            }
            HudOverlay(if (dashOn) Hud.plan(dash, hudLayout, wide, anchor) else null, p, top = topStack,
                onBounds = { dashBoxes = it })
            // The turn banner, from the Follower's Status - the same turn the voice
            // announces, in the banner's own words (the voice's are hers). Null
            // until minion 1's Follower publishes it; then this line is the only
            // wiring left.
            val banner = TurnBanner.plan(nav.status, settings.imperial, destName ?: "your pin")
            val here = lastFix?.let { LatLon(it.latitude, it.longitude) }
            val top = banner != null || nav.route != null || pin != null

            // Top left, one column of round buttons (spec section 03), so the
            // bottom edge belongs to the ribbon: the menu first, record under it
            // (ButtonColumn.RECORD_SLOT) with the recording line and the status
            // message BESIDE it and out of the column's flow - a line appearing
            // never moves a button, never pushes one off screen, and the dash
            // clears all of it through ButtonColumn.
            ButtonColumnLayout(
                onTopStack = { topStack = it },
                banner = if (!top) null else {
                    {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            banner?.let { TurnBannerView(it, p) }
                            RouteStrip(nav, p, settings.imperial, destName = destName,
                                voiceWhy = when {
                                    !settings.voice -> null
                                    !voice.ready -> voice.why
                                    else -> voice.failed.ifEmpty { null }
                                })
                            pin?.let { at ->
                                PinBand(at, nav, here, p, settings.imperial,
                                    onRoute = { routeToPin(at) },
                                    onCancel = ::cancelPin,
                                    name = pinName, about = pinAbout,
                                    outside = map.covers(at) == false)
                            }
                        }
                    }
                },
                lines = {
                    // Recording is shouted, not implied: the gauges show numbers either
                    // way, so whether a ride is being WRITTEN is this red-edged chip
                    // and the red square, and nothing else.
                    recordLine(rec, nowMs)?.let { line ->
                        if (rec.recording) Chip("● $line", p, edge = p.bad, ink = p.bad) else Chip(line, p)
                    }
                    // The UI says when the mic listens (spec rule 5), and what it hears -
                    // including why there is no rpm, rather than a blank that looks broken.
                    if (micState.on || micState.error.isNotEmpty()) Chip(micAccount(micState), p)
                    if (endArmedAt != null) Chip("Tap End again to end the route", p, edge = p.bad, ink = p.bad)
                    message(problem, locationGranted, fixAge, dataPack,
                        outside = lastFix?.let { map.covers(LatLon(it.latitude, it.longitude)) == false } == true)?.let { Chip(it, p) }
                },
            ) {
                RoundButton("☰", p) { settingsOpen = true }
                RoundButton(if (rec.recording) "■" else "●", p, if (rec.recording) p.bad else p.accent) {
                    onRecordTap()
                }
                // Opens empty: it kept the last search, with the cursor at its
                // start, so "ikea" typed after a ride became "ikeachabot space".
                RoundButton(p, { PlacesStore.search("", null); whereToOpen = true; Voice.tap("opened Where to") }) { SearchGlyph(p.accent) }
                // Follow: always here, lit while the map follows her, dark when
                // she has panned away; one tap centres on the bike and follows.
                // It used to appear only after a pan, so she never saw it
                // ("the center on bike and follow bike ... i don't see that").
                if (locationGranted) {
                    RoundButton(p, { map.follow() }, lit = following) { FollowGlyph(if (following) p.bg else p.accent) }
                }
                // Ending a route takes two taps, as stopping a recording does. Last
                // in the column: it comes and goes, and nothing below it moves.
                if (nav.route != null) {
                    RoundButton(if (endArmedAt != null) "End?" else "End", p, if (endArmedAt != null) p.bad else p.ink(Role.TEXT2)) {
                        onEndTap()
                    }
                }
            }

            if (whereToOpen) {
                WhereToPanel(
                    p = p, state = places, imperial = settings.imperial,
                    onQuery = { PlacesStore.search(it, lastFix?.let { f -> LatLon(f.latitude, f.longitude) }) },
                    onPick = { hit ->
                        offer(hit.at, hit.name, hit.detail)
                        whereToOpen = false
                        Voice.tap("picked ${hit.name}")
                    },
                    onClose = { whereToOpen = false },
                    routable = { map.covers(it) != false },
                )
            }

            if (settingsOpen) {
                SettingsPanel(
                    p = p, prefs = settings, rev = settingsRev, sensors = sensorLines, mic = micState, voice = voice,
                    fixAgeS = fixAge, fixAccM = lastFix?.takeIf { it.hasAccuracy() }?.accuracy?.toDouble(),
                    version = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull() ?: "",
                    onChanged = ::applySetting,
                    onClose = { settingsOpen = false },
                    level = level, levelNote = levelNote,
                    onHelp = { helpOpen = true },
                    onLevel = { set ->
                        if (set) {
                            Telemetry.setLevel(this@MainActivity) { ok ->
                                levelNote = if (ok) "" else "No accelerometer has reported yet - is Motion sensors on?"
                            }
                        } else {
                            Telemetry.clearLevel(this@MainActivity)
                            levelNote = ""
                        }
                    },
                )
            }
            if (helpOpen) HelpPanel(p) { helpOpen = false }
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
    private fun message(problem: Problem?, granted: Boolean, fixAge: Double?, data: DataPack.State, outside: Boolean): String? = when {
        data.busy -> "Unpacking the map for the first time: %,d of %,d MB".format(data.doneBytes / 1_000_000, data.totalBytes / 1_000_000)
        data.error.isNotEmpty() -> data.error
        problem == Problem.NO_TILES ->
            "No map file. Copy ${MapFiles.TILES_NAME} to\n${map.tiles?.path ?: "app storage"}"
        problem == Problem.UNREADABLE_STORAGE ->
            "xnav.k can't read its storage folder: it was made before xnav.k first ran.\n" +
                "Reinstall, open xnav.k once, then copy ${MapFiles.TILES_NAME} again"
        problem == Problem.BAD_TILES -> "${MapFiles.TILES_NAME} is not a readable PMTiles v3 archive"
        problem == Problem.NO_STYLE -> "This build has no map style"
        problem == Problem.STYLE_MISSING -> "No ${palette(theme).name} map in this build — showing Night's"
        !granted -> "Location is off for xnav.k — no bike on the map"
        !Telemetry.hasGps(this) -> "This phone reports no GPS receiver"
        fixAge == null -> "Waiting for GPS"
        // The Flet app's own sentence (routing.START_OUTSIDE), so both apps say it alike.
        // No "or find signal": this app has no network permission and routes offline only.
        outside -> "You are outside the offline map. Ride back into it."
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
        /** Ridden this far from where a destination was refused, its card goes (m, ~650 ft). */
        const val REFUSED_CARD_RIDDEN_M = 200.0
        /** The Flet app's "the bike is rolling": faster than a parked GPS wanders (m/s, ~5.6 mph). */
        const val ROLLING_MS = 2.5f
        const val STOPPED_MS = 1.0f
    }
}
