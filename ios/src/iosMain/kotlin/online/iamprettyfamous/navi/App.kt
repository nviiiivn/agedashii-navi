package online.iamprettyfamous.navi

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.viewinterop.UIKitInteropInteractionMode
import androidx.compose.ui.viewinterop.UIKitInteropProperties
import androidx.compose.ui.viewinterop.UIKitView
import kotlinx.coroutines.delay
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.launch
import online.iamprettyfamous.navi.core.daylight.Daylight
import online.iamprettyfamous.navi.core.dash.Anchor
import online.iamprettyfamous.navi.core.dash.Hud
import online.iamprettyfamous.navi.core.dash.Layout
import online.iamprettyfamous.navi.core.dash.TurnBanner
import online.iamprettyfamous.navi.core.geo.LatLon
import online.iamprettyfamous.navi.core.nowSeconds
import online.iamprettyfamous.navi.core.theme.Role
import online.iamprettyfamous.navi.dash.HudOverlay
import online.iamprettyfamous.navi.dash.TurnBannerView
import online.iamprettyfamous.navi.dash.ink
import online.iamprettyfamous.navi.host.MapHost
import online.iamprettyfamous.navi.host.RouterHost
import online.iamprettyfamous.navi.map.RideMap
import online.iamprettyfamous.navi.map.RideMap.Problem
import online.iamprettyfamous.navi.places.PlacesStore
import online.iamprettyfamous.navi.prefs.Prefs
import online.iamprettyfamous.navi.record.Fix
import online.iamprettyfamous.navi.record.RecState
import online.iamprettyfamous.navi.record.Telemetry
import online.iamprettyfamous.navi.routing.Navigator
import online.iamprettyfamous.navi.routing.Router
import online.iamprettyfamous.navi.ui.ButtonColumnLayout
import online.iamprettyfamous.navi.ui.Changed
import online.iamprettyfamous.navi.ui.Chip
import online.iamprettyfamous.navi.ui.FollowGlyph
import online.iamprettyfamous.navi.ui.HelpPanel
import online.iamprettyfamous.navi.ui.PinBand
import online.iamprettyfamous.navi.ui.RoundButton
import online.iamprettyfamous.navi.ui.RouteStrip
import online.iamprettyfamous.navi.ui.SearchGlyph
import online.iamprettyfamous.navi.ui.SettingsPanel
import online.iamprettyfamous.navi.ui.TopStack
import online.iamprettyfamous.navi.ui.WhereToPanel
import online.iamprettyfamous.navi.ui.palette
import online.iamprettyfamous.navi.voice.Voice
import platform.Foundation.NSBundle
import platform.Foundation.NSProcessInfo
import platform.UIKit.UIApplication

/**
 * The Ride screen on the iPhone: the Android app's MainActivity, the same
 * screen in the same order - the map, her bike, the dash over it, the button
 * column, the turn banner and the route strip, Where to and Settings as panels
 * over the map - with the iPhone's own GPS, voice, map and router behind it.
 * The composables it draws are the Android app's own files (ios/build.gradle.kts).
 */
@OptIn(ExperimentalComposeUiApi::class)
class RideScreen(mapHost: MapHost, private val routerHost: RouterHost) {
    private val map = RideMap(mapHost)
    private val settings = Prefs()

    private var theme by mutableStateOf(settings.theme)
    private var following by mutableStateOf(false)
    private var problem by mutableStateOf<Problem?>(null)
    private var hudLayout by mutableStateOf(Layout.of(settings.hud))
    private var dashOn by mutableStateOf(settings.dash)
    private var settingsOpen by mutableStateOf(false)
    private var settingsRev by mutableStateOf(0)
    private var whereToOpen by mutableStateOf(false)
    private var helpOpen by mutableStateOf(false)

    /** The point she long-pressed, offered as a destination and not chosen yet. */
    private var pin by mutableStateOf<LatLon?>(null)
    private var pinName by mutableStateOf<String?>(null)
    private var pinAbout by mutableStateOf<String?>(null)

    /** The destination's name, for arrival and the "route ends" line. */
    private var destName by mutableStateOf<String?>(null)
    private var pendingName: String? = null

    /** Set by the first tap on a running recording; a second tap within 3 s stops it. */
    private var stopArmedAt by mutableStateOf<Double?>(null)
    private var endArmedAt by mutableStateOf<Double?>(null)

    private var started = false

    /** Everything that runs whether or not the screen is drawn. Once. */
    fun start() {
        if (started) return
        started = true
        keepAwake(settings.keepAwake)
        Navigator.setOffRouteM(settings.offrouteM)
        if (settings.autoTheme) theme = Daylight.themeFor(Daylight.phase(null, null, nowSeconds()))
        map.followingChanged = { following = it }
        map.problemChanged = { problem = it }
        map.longPressed = { offer(it, null, null) }
        map.start(theme, settings.bike, settings.northUp, settings.buildings)
        Telemetry.start(settings.machine, settings.imperial)
        Router.load(routerHost)
        PlacesStore.open()
        applyVoice()
        Voice.start()
        Voice.opening()
        smokeRoute()
    }

    /**
     * CI's simulator run sets XNAV_SMOKE_DEST="lat,lon" (ios.yml) and drives a
     * ride up Broadway, so the router, guidance and the voice are exercised
     * there before her phone ever is. Nothing on a phone sets it.
     */
    private fun smokeRoute() {
        val dest = NSProcessInfo.processInfo.environment["XNAV_SMOKE_DEST"] as? String ?: return
        val (lat, lon) = dest.split(",").mapNotNull { it.trim().toDoubleOrNull() }.takeIf { it.size == 2 } ?: return
        println("[smoke] routing to $lat,$lon")
        Navigator.go(LatLon(lat, lon), settings.imperial)
        // The route's own line, so CI rides exactly the road it was given.
        MainScope().launch {
            val line = Navigator.nav.mapNotNull { it.route }.first()
            println("[smoke-route] " + line.points.joinToString(";") { "${it.lat},${it.lon}" })
        }
    }

    private fun applyVoice() {
        Voice.enabled = settings.voice
        Voice.streets = settings.voiceStreets
        Voice.character.on = settings.voiceCharacter
        Voice.character.taps = settings.voiceTaps
    }

    /** Applies a changed setting at once - never "from the next start". */
    private fun applySetting(what: Changed) {
        when (what) {
            Changed.THEME -> {
                // Picking a theme by hand ends following the sun (the Flet app's rule).
                if (settings.autoTheme) { settings.autoTheme = false; sunPhase = null }
                theme = settings.theme; map.applyTheme(theme)
            }
            Changed.AUTO_THEME -> {
                sunPhase = null
                if (settings.autoTheme) followSun() else { theme = settings.theme; map.applyTheme(theme) }
            }
            Changed.BIKE -> map.setBike(settings.bike)
            Changed.BUILDINGS -> map.setBuildings(settings.buildings)
            Changed.NORTH_UP -> map.setNorthUp(settings.northUp)
            Changed.KEEP_AWAKE -> keepAwake(settings.keepAwake)
            Changed.HUD -> { hudLayout = Layout.of(settings.hud); dashOn = settings.dash }
            Changed.TELEMETRY -> Telemetry.refresh(settings.machine, settings.imperial)
            Changed.OFFROUTE -> Navigator.setOffRouteM(settings.offrouteM)
            Changed.VOICE -> applyVoice()
        }
        settingsRev++
    }

    /** The day's phase last applied, while following the sun. */
    private var sunPhase: String? = null

    /**
     * The theme where the sun says, from her position (the clock before the
     * first fix). Only the theme: on the iPhone a screen brightness set by an
     * app outlives the app, so the brightness stays hers.
     */
    private fun followSun() {
        if (!settings.autoTheme) return
        val fix = Telemetry.fix.value
        val phase = Daylight.phase(fix?.latitude, fix?.longitude, nowSeconds())
        if (phase == sunPhase) return
        sunPhase = phase
        val want = Daylight.themeFor(phase)
        if (want != theme) { theme = want; map.applyTheme(want) }
    }

    private fun keepAwake(on: Boolean) {
        UIApplication.sharedApplication.idleTimerDisabled = on
    }

    private fun offer(at: LatLon, name: String?, about: String?) {
        pin = at
        pinName = name
        pinAbout = about
    }

    private fun routeToPin(at: LatLon) {
        // The name becomes the destination's only once its route exists: set
        // here, a refused Napa renamed the Chabot route still being followed.
        pendingName = pinName
        Navigator.go(at, settings.imperial)
    }

    private fun cancelPin() {
        pin?.let(Navigator::forget)
        pin = null
        pinName = null
        pinAbout = null
    }

    private fun onEndTap() {
        val armed = endArmedAt
        val now = nowSeconds()
        if (armed != null && now - armed < STOP_CONFIRM_S) {
            endArmedAt = null
            destName = null
            Navigator.clear()
        } else {
            endArmedAt = now
        }
    }

    private fun onRecordTap() {
        if (!Telemetry.rec.value.recording) return Telemetry.startRecording()
        val armed = stopArmedAt
        val now = nowSeconds()
        if (armed != null && now - armed < STOP_CONFIRM_S) {
            stopArmedAt = null
            Telemetry.stopRecording()
            // Stopped BY HAND: one of the two moments her rule names for
            // "hasta la vista, baby". The bike stopping is not her leaving.
            Voice.farewell()
            autoRecordHeld = true
        } else {
            // One glove-tap on the wrong button must not end a ride.
            stopArmedAt = now
        }
    }

    // Record automatically: fixes in a row at rolling speed, and whether a
    // recording she stopped by hand is holding it off until the bike stops.
    private var rollingFixes = 0
    private var stoppedSince: Double? = null
    private var autoRecordHeld = false

    private fun autoRecord(fix: Fix) {
        if (!settings.autoRecord) return
        val speed = fix.speed ?: 0.0
        val now = nowSeconds()
        if (speed < STOPPED_MS) {
            rollingFixes = 0
            val since = stoppedSince ?: now.also { stoppedSince = it }
            if (now - since > 30) autoRecordHeld = false
            return
        }
        stoppedSince = null
        rollingFixes = if (speed >= ROLLING_MS) rollingFixes + 1 else 0
        if (rollingFixes >= 3 && !autoRecordHeld && !Telemetry.rec.value.recording) {
            rollingFixes = 0
            Telemetry.startRecording()
        }
    }

    @Composable
    fun Ride() {
        val p = palette(theme)
        var fixAge by remember { mutableStateOf<Double?>(null) }
        var now by remember { mutableStateOf(nowSeconds()) }
        LaunchedEffect(Unit) {
            while (true) {
                fixAge = Telemetry.fixAgeSeconds()
                now = nowSeconds()
                if (stopArmedAt?.let { now - it > STOP_CONFIRM_S } == true) stopArmedAt = null
                if (endArmedAt?.let { now - it > STOP_CONFIRM_S } == true) endArmedAt = null
                delay(500)
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
        val rec by Telemetry.rec.collectAsState()
        val dash by Telemetry.dash.collectAsState()
        val granted by Telemetry.granted.collectAsState()
        val lastFix by Telemetry.fix.collectAsState()
        val nav by Navigator.nav.collectAsState()
        val voice by Voice.state.collectAsState()
        val router by Router.state.collectAsState()
        val places by PlacesStore.state.collectAsState()
        @Suppress("UNUSED_VARIABLE") val rev = settingsRev // a change in Settings redraws the ride screen
        LaunchedEffect(nav.route) { map.showRoute(nav.route?.points) }
        // A route or a recording keeps the GPS going with the screen locked, so
        // guidance and the voice carry on in her pocket.
        val guiding = nav.route != null
        LaunchedEffect(guiding, rec.recording) { Telemetry.background(guiding || rec.recording) }
        LaunchedEffect(nav.dest, pin) { map.showMarks(nav.dest, pin) }
        // Routed: the offered pin has become the destination.
        LaunchedEffect(nav.dest, nav.route) {
            if (nav.route != null && nav.dest == pin) {
                destName = pendingName
                pin = null
            }
        }
        BoxWithConstraints(Modifier.fillMaxSize().background(p.bg)) {
            // The map takes its touches at once: nothing in Compose scrolls over
            // it, and the default hand-over delay made every pan feel late.
            UIKitView(
                factory = { mapHostView }, modifier = Modifier.fillMaxSize(),
                properties = UIKitInteropProperties(interactionMode = UIKitInteropInteractionMode.NonCooperative),
            )

            // The dash over the map. Landscape puts the gauges down her chosen
            // side (right by default), portrait along her chosen edge (bottom).
            val wide = maxWidth > maxHeight
            val anchor = if (wide) {
                if (settings.hudSide == "left") Anchor.LEFT else Anchor.RIGHT
            } else {
                if (settings.hudEdge == "top") Anchor.TOP else Anchor.BOTTOM
            }
            var topStack by remember { mutableStateOf(TopStack()) }
            // The camera centres below the banner, so the bike is never under it.
            val density = LocalDensity.current
            val safeTop = WindowInsets.safeDrawing.getTop(density) / density.density
            LaunchedEffect(topStack, safeTop) {
                map.setTopClear(if (topStack.banner) safeTop + topStack.clearDp.toDouble() else 0.0)
            }
            HudOverlay(if (dashOn) Hud.plan(dash, hudLayout, wide, anchor) else null, p, top = topStack)
            val banner = TurnBanner.plan(nav.status, settings.imperial, destName ?: "your pin")
            val here = lastFix?.let { LatLon(it.latitude, it.longitude) }
            val top = banner != null || nav.route != null || pin != null

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
                    // Recording is shouted, not implied: whether a ride is being
                    // WRITTEN is this red-edged chip and the red square, nothing else.
                    recordLine(rec, now)?.let { line ->
                        if (rec.recording) Chip("● $line", p, edge = p.bad, ink = p.bad) else Chip(line, p)
                    }
                    if (endArmedAt != null) Chip("Tap End again to end the route", p, edge = p.bad, ink = p.bad)
                    message(problem, granted, fixAge, router,
                        outside = here?.let { map.covers(it) == false } == true)?.let { Chip(it, p) }
                },
            ) {
                RoundButton("☰", p) { settingsOpen = true }
                RoundButton(if (rec.recording) "■" else "●", p, if (rec.recording) p.bad else p.accent) { onRecordTap() }
                // Opens empty: it kept the last search once, and "ikea" typed after
                // a ride became "ikeachabot space".
                RoundButton(p, { PlacesStore.search("", null); whereToOpen = true; Voice.tap("opened Where to") }) { SearchGlyph(p.accent) }
                // Follow: always here, lit while the map follows her, dark when she has panned away.
                if (granted == true) {
                    RoundButton(p, { map.follow() }, lit = following) { FollowGlyph(if (following) p.bg else p.accent) }
                }
                if (nav.route != null) {
                    RoundButton(if (endArmedAt != null) "End?" else "End", p, if (endArmedAt != null) p.bad else p.ink(Role.TEXT2)) {
                        onEndTap()
                    }
                }
            }

            if (whereToOpen) {
                WhereToPanel(
                    p = p, state = places, imperial = settings.imperial,
                    onQuery = { PlacesStore.search(it, here) },
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
                    p = p, prefs = settings, rev = settingsRev, voice = voice, router = router,
                    fixAgeS = fixAge, fixAccM = lastFix?.accuracy,
                    version = version(),
                    onChanged = ::applySetting,
                    onClose = { settingsOpen = false },
                    onHelp = { helpOpen = true },
                )
            }
            if (helpOpen) HelpPanel(p) { helpOpen = false }
        }
    }

    /** The map's view, made once by the Swift app; Compose only hosts it. */
    private val mapHostView = mapHost.view()

    private fun version(): String {
        val info = NSBundle.mainBundle.infoDictionary
        return "${info?.get("CFBundleShortVersionString") ?: ""} (${info?.get("CFBundleVersion") ?: ""})"
    }

    /** The recording in one line: running, or just stopped. */
    private fun recordLine(rec: RecState, nowS: Double): String? {
        if (rec.recording) {
            if (stopArmedAt != null) return "Tap ■ again to stop"
            val secs = (nowS - (rec.startedS ?: nowS)).toLong().coerceAtLeast(0)
            val waiting = if (rec.samples == 0) " · waiting for GPS" else ""
            return "REC ${secs / 60}:${(secs % 60).toString().padStart(2, '0')} · " +
                "${Telemetry.distanceText(rec.distanceM, settings.imperial)} · ${rec.samples} rows$waiting"
        }
        rec.errors.firstOrNull()?.let { return it }
        if (rec.path != null) {
            val kinds = rec.exported.joinToString(" ") { it.substringAfterLast('.').uppercase() }
            return "Saved ${rec.samples} rows${if (kinds.isNotEmpty()) " · $kinds" else ""} - in Files, xnav.k, rides"
        }
        return null
    }

    /** One honest line about why the map or the bike is not where she expects. */
    private fun message(problem: Problem?, granted: Boolean?, fixAge: Double?, router: Router.State, outside: Boolean): String? = when {
        problem == Problem.NO_TILES -> "This build carries no map"
        problem == Problem.BAD_TILES -> "The map in this build is not a readable PMTiles v3 archive"
        problem == Problem.NO_STYLE -> "This build has no map style"
        problem == Problem.STYLE_MISSING -> "No ${palette(theme).name} map in this build — showing Night's"
        granted == false -> "Location is off for xnav — Settings, xnav.k, Location, While Using, Precise on"
        granted == null -> "Waiting for permission to use location"
        fixAge == null -> "Waiting for GPS"
        // The Flet app's own sentence (routing.START_OUTSIDE), so the apps say it alike.
        outside -> "You are outside the offline map. Ride back into it."
        fixAge > STALE_FIX_S -> "GPS lost ${fixAge.toInt()} s ago"
        !router.ready && router.why != "not loaded" -> "No routing: ${router.why}"
        else -> null
    }

    private companion object {
        const val STALE_FIX_S = 10.0
        const val STOP_CONFIRM_S = 3.0
        /** The Flet app's "the bike is rolling": faster than a parked GPS wanders (m/s, ~5.6 mph). */
        const val ROLLING_MS = 2.5
        const val STOPPED_MS = 1.0
    }
}
