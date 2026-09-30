package online.iamprettyfamous.navi.map

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.os.Bundle
import android.util.Log
import androidx.core.content.ContextCompat
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.location.LocationComponentActivationOptions
import org.maplibre.android.location.LocationComponentOptions
import org.maplibre.android.location.OnCameraTrackingChangedListener
import org.maplibre.android.location.modes.CameraMode
import org.maplibre.android.location.modes.RenderMode
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.json.JSONArray
import org.json.JSONObject
import online.iamprettyfamous.navi.R
import online.iamprettyfamous.navi.core.geo.LatLon
import online.iamprettyfamous.navi.core.map.PmtilesHeader
import online.iamprettyfamous.navi.core.map.StylePatch
import java.io.File

/**
 * Owns the MapView and everything done to it. The activity forwards its
 * lifecycle here; Compose only hosts [mapView] and draws the chrome over it.
 *
 * Nothing in here touches the network: the tiles are a file, the style and
 * glyphs are in the APK, and the app does not hold the INTERNET permission.
 */
class RideMapController(context: Context) {

    /** STYLE_MISSING is the one that still draws a map: this theme's, no; Night's, yes. */
    enum class Problem { NO_TILES, UNREADABLE_STORAGE, BAD_TILES, NO_STYLE, STYLE_MISSING }

    private val appContext = context.applicationContext

    init {
        MapLibre.getInstance(appContext)
    }

    val tiles: File? = MapFiles.tilesFile(appContext.getExternalFilesDir(null))

    // Read when the style loads rather than once at start, so a map copied in
    // while the app was open is found on the next resume (see recheck()).
    private var header: PmtilesHeader? = null
    private var lastProblem: Problem? = null
    private var cameraSet = false

    val mapView = MapView(context)
    private var map: MapLibreMap? = null
    private var style: Style? = null
    private var locationWanted = false
    private var locationActive = false

    var theme = "night"
        private set
    var bike = "yellow"
        private set
    var northUp = false
        private set

    /** Told whenever the camera starts or stops following her. */
    var onFollowingChanged: (Boolean) -> Unit = {}

    /** What stops the map drawing, in words she can act on; null when nothing. */
    var onProblem: (Problem?) -> Unit = {}

    /** A long press on the map: the point she might want to go to. */
    var onLongPress: (LatLon) -> Unit = {}

    fun onCreate(savedInstanceState: Bundle?, theme: String, bike: String, northUp: Boolean) {
        this.theme = theme
        this.bike = bike
        this.northUp = northUp
        mapView.onCreate(savedInstanceState)
        mapView.getMapAsync { m ->
            map = m
            configure(m)
            applyTheme(theme)
            // A clearance measured before the map was ready is not lost.
            if (clear.any { it != 0.0 }) m.moveCamera(org.maplibre.android.camera.CameraUpdateFactory.paddingTo(clear))
        }
    }

    private fun configure(m: MapLibreMap) {
        m.uiSettings.isLogoEnabled = false
        // No credit button on the map: her decision, 2026-09-28 - "this is a
        // personal project ... i don't want it on my screen". The credits are
        // kept as a record (assets/styles/SOURCE.txt, the style's source
        // attribution) and belong in Settings/About before this is ever shared
        // beyond her own phones (KNOWN.md).
        m.uiSettings.isAttributionEnabled = false
        m.uiSettings.isCompassEnabled = false
        m.setMaxZoomPreference(MAX_ZOOM)
        // A LONG press, so a pan or a stray tap never offers a destination.
        m.addOnMapLongClickListener { point ->
            onLongPress(LatLon(point.latitude, point.longitude))
            true
        }
    }

    /** Bounds and a starting view from the archive, once it is known. */
    private fun placeCamera(m: MapLibreMap, h: PmtilesHeader) {
        if (cameraSet) return
        cameraSet = true
        m.setMinZoomPreference(maxOf(h.minZoom.toDouble(), MIN_ZOOM))
        m.setLatLngBoundsForCameraTarget(LatLngBounds.from(h.north, h.east, h.south, h.west))
        m.cameraPosition = CameraPosition.Builder()
            .target(LatLng(h.centerLat, h.centerLon))
            .zoom(maxOf(h.centerZoom.toDouble(), 11.0))
            .build()
    }

    fun applyTheme(key: String) {
        theme = key
        val m = map ?: return
        val file = tiles
        if (file == null || !file.isFile) {
            // A storage folder made by `adb shell` before the app ever ran is
            // one the app cannot read (minion 3 reproduced it three times), so
            // a copied map looks absent. Say that, not "copy the file".
            // The files folder itself stays listable (emulator, 2026-09-27); it is
            // the adb-made map/ inside it that the app cannot see into.
            val ext = appContext.getExternalFilesDir(null)
            val mapDir = ext?.let { java.io.File(it, "map") }
            val present = mapDir != null && (mapDir.exists() || ext.list()?.contains("map") == true)
            val unreadable = present && mapDir.list() == null
            return problem(if (unreadable) Problem.UNREADABLE_STORAGE else Problem.NO_TILES)
        }
        val h = header ?: PmtilesHeader.read(file)?.also { header = it } ?: return problem(Problem.BAD_TILES)
        placeCamera(m, h)
        // A theme with no map style still gets a map - Night's - but SAYS so. It
        // used to swap silently, and a fallback nobody can see is
        // indistinguishable from a feature that works: every theme "worked",
        // drawing Night's map under Sunlight's chrome.
        val own = MapStyles.load(appContext.assets, key, file)
        val json = own
            ?: MapStyles.load(appContext.assets, "night", file)
            ?: return problem(Problem.NO_STYLE)
        problem(if (own == null) Problem.STYLE_MISSING else null)
        val patched = StylePatch.realZoomRange(json, h.minZoom, h.maxZoom)
        for (change in patched.changes) Log.w(TAG, "$change (from the archive)")
        val meta = runCatching { JSONObject(json).optJSONObject("metadata") }.getOrNull()
        buildingsLayer = meta?.optString("agedashii:buildings-layer")?.takeIf { it.isNotEmpty() }
        routeBelow = meta?.optString("agedashii:route-below")?.takeIf { it.isNotEmpty() }
        routeLook = meta?.optJSONObject("agedashii:route")
        colours = meta?.optJSONObject("agedashii:palette")
        // The old style's sources go with it; nothing may be set on them meanwhile.
        style = null
        m.setStyle(withBikes(Style.Builder().fromJson(patched.json))) { s ->
            style = s
            showBuildings(s)
            addRouteLayers(s)
            if (locationWanted) activateLocation(s)
        }
    }

    /**
     * Buildings ship in the tiles as one layer, hidden by default in every
     * style; this only flips its visibility - no second archive. Her idea: "can
     * there be a turn off/on button?". The layer id comes from the style's own
     * metadata (agedashii:buildings-layer) so the Flet app and this one flip
     * the same thing.
     */
    fun setBuildings(on: Boolean) {
        buildings = on
        style?.let { showBuildings(it) }
    }

    private var buildings = false
    private var buildingsLayer: String? = null

    private fun showBuildings(s: Style) {
        val id = buildingsLayer ?: return
        s.getLayer(id)?.setProperties(
            org.maplibre.android.style.layers.PropertyFactory.visibility(
                if (buildings) org.maplibre.android.style.layers.Property.VISIBLE
                else org.maplibre.android.style.layers.Property.NONE,
            ),
        )
    }

    // ------------------------------------------------------------ the route

    private var routeBelow: String? = null
    private var routeLook: JSONObject? = null
    private var colours: JSONObject? = null
    private var routePoints: List<LatLon>? = null
    private var dest: LatLon? = null
    private var pin: LatLon? = null

    /** Draws [points] as the route, or none. Kept across theme changes. */
    fun showRoute(points: List<LatLon>?) {
        routePoints = points
        style?.getSourceAs<GeoJsonSource>(ROUTE_SOURCE)?.setGeoJson(lineJson(points))
    }

    /** The destination she has, and the point she long-pressed and has not chosen yet. */
    fun showMarks(dest: LatLon?, pin: LatLon?) {
        this.dest = dest
        this.pin = pin
        style?.getSourceAs<GeoJsonSource>(MARKS_SOURCE)?.setGeoJson(marksJson())
    }

    /**
     * The route as the style asks for it (its metadata, agedashii:route, written
     * by mapdata/gen_styles.py so the Flet app draws the same): a casing, then
     * the line, both BELOW agedashii:route-below - the first label layer - so
     * street names stay readable on top of the route and no halo cuts it.
     */
    private fun addRouteLayers(s: Style) {
        val look = routeLook ?: JSONObject()
        val width = look.optJSONArray("line-width")?.toString() ?: FALLBACK_WIDTH
        // The casing is the line's own width plus casing-extra-px each side.
        val casingWidth = widened(width, 2 * look.optDouble("casing-extra-px", 3.0))
        val bg = colour("bg", "#06080b")
        val lineColour = look.optString("line-color").ifEmpty { colour("road", "#39d0ff") }
        val casingColour = look.optString("casing-color").ifEmpty { bg }
        s.addSource(GeoJsonSource(ROUTE_SOURCE, lineJson(routePoints)))
        s.addSource(GeoJsonSource(MARKS_SOURCE, marksJson()))
        val casing = LineLayer(ROUTE_CASING, ROUTE_SOURCE).withProperties(
            PropertyFactory.lineColor(casingColour),
            PropertyFactory.lineWidth(Expression.Converter.convert(casingWidth)),
            PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
            PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
        )
        val line = LineLayer(ROUTE_LINE, ROUTE_SOURCE).withProperties(
            PropertyFactory.lineColor(lineColour),
            PropertyFactory.lineWidth(Expression.Converter.convert(width)),
            PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
            PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
        )
        val below = routeBelow?.takeIf { s.getLayer(it) != null }
        if (below != null) {
            s.addLayerBelow(casing, below)
            s.addLayerBelow(line, below)
        } else {
            Log.w(TAG, "no layer '$routeBelow' to put the route under; drawing it on top")
            s.addLayer(casing)
            s.addLayer(line)
        }
        // The Flet app's markers: the destination in warn, the offered point in
        // accent and bigger, so the two are never mistaken for each other.
        val accent = colour("accent", "#39d0ff")
        val warn = colour("warn", "#ffb020")
        s.addLayer(
            CircleLayer(MARKS_LAYER, MARKS_SOURCE).withProperties(
                PropertyFactory.circleColor(byKind("\"$accent\"", "\"$warn\"")),
                PropertyFactory.circleRadius(byKind("14", "11")),
                PropertyFactory.circleStrokeColor(bg),
                PropertyFactory.circleStrokeWidth(byKind("3", "2")),
            ),
        )
    }

    private fun colour(name: String, fallback: String): String =
        colours?.optString(name)?.takeIf { it.isNotEmpty() } ?: fallback

    private fun byKind(pin: String, dest: String): Expression =
        Expression.Converter.convert("[\"match\",[\"get\",\"kind\"],\"pin\",$pin,$dest]")

    private fun lineJson(points: List<LatLon>?): String {
        if (points == null || points.size < 2) return EMPTY
        val coords = points.joinToString(",") { "[${it.lon},${it.lat}]" }
        return "{\"type\":\"FeatureCollection\",\"features\":[{\"type\":\"Feature\",\"properties\":{}," +
            "\"geometry\":{\"type\":\"LineString\",\"coordinates\":[$coords]}}]}"
    }

    private fun marksJson(): String {
        val marks = listOfNotNull(dest?.let { "dest" to it }, pin?.let { "pin" to it })
        val features = marks.joinToString(",") { (kind, p) ->
            "{\"type\":\"Feature\",\"properties\":{\"kind\":\"$kind\"}," +
                "\"geometry\":{\"type\":\"Point\",\"coordinates\":[${p.lon},${p.lat}]}}"
        }
        return "{\"type\":\"FeatureCollection\",\"features\":[$features]}"
    }

    /**
     * [width] with [extra] px added at every stop. A zoom expression may only be
     * the top-level interpolate, so `["+", width, extra]` is refused; the stops
     * themselves have to grow.
     */
    private fun widened(width: String, extra: Double): String = runCatching {
        val a = JSONArray(width)
        if (a.optString(0) != "interpolate") return@runCatching width
        for (i in 4 until a.length() step 2) a.put(i, a.getDouble(i) + extra)
        a.toString()
    }.getOrDefault(width)

    private fun problem(p: Problem?) {
        lastProblem = p
        onProblem(p)
    }

    /**
     * Whether [p] is inside the offline map, or null before the map is read.
     * The router's tiles cover the same box, so outside it there is no map
     * AND no route.
     */
    fun covers(p: LatLon): Boolean? = header?.let { h ->
        p.lat in h.south..h.north && p.lon in h.west..h.east
    }

    /** A new map file is in place (DataPack): read its header again and redraw. */
    fun reloadTiles() {
        header = null
        applyTheme(theme)
    }

    /**
     * Looks again for the map after the app comes back to the front. Without
     * this, "No map file" outlived the problem: copying the map in while the
     * message showed changed nothing until a restart.
     */
    fun recheck() {
        // Only the file problems can fix themselves while the app is away; the
        // styles are inside the APK, so a missing one stays missing.
        val p = lastProblem
        if (p != null && p != Problem.NO_STYLE && p != Problem.STYLE_MISSING) applyTheme(theme)
    }

    /** Call once location permission is granted. Safe before the style loads. */
    fun enableLocation() {
        locationWanted = true
        style?.let { activateLocation(it) }
    }

    @SuppressLint("MissingPermission")
    private fun activateLocation(s: Style) {
        val m = map ?: return
        val lc = m.locationComponent
        if (!locationActive) {
            // No engine of its own: fixes come from Telemetry via showFix().
            lc.activateLocationComponent(
                LocationComponentActivationOptions.builder(appContext, s)
                    .locationComponentOptions(markerOptions())
                    .useDefaultLocationEngine(false)
                    .build(),
            )
            lc.addOnCameraTrackingChangedListener(object : OnCameraTrackingChangedListener {
                override fun onCameraTrackingDismissed() = onFollowingChanged(false)
                override fun onCameraTrackingChanged(currentMode: Int) =
                    onFollowingChanged(currentMode != CameraMode.NONE)
            })
            locationActive = true
        }
        lc.isLocationComponentEnabled = true
        lc.renderMode = RenderMode.GPS
        follow()
    }

    /**
     * Both bikes go into every style as named images and the marker picks one
     * by name. Swapping drawables through applyStyle() left the old bike on
     * screen (emulator, 2026-09-27); swapping the name is a layer property
     * change, which always redraws.
     */
    private fun withBikes(builder: Style.Builder): Style.Builder {
        for ((colour, res) in BIKES) {
            ContextCompat.getDrawable(appContext, res)?.let { builder.withImage(bikeImage(colour), it) }
        }
        return builder
    }

    private fun bikeImage(colour: String) = "agedashii-bike-$colour"

    private fun markerOptions(): LocationComponentOptions {
        val icon = BIKES[bike] ?: R.drawable.bike_yellow
        return LocationComponentOptions.builder(appContext)
            .gpsDrawable(icon)
            .foregroundDrawable(icon)
            .gpsName(bikeImage(bike))
            .foregroundName(bikeImage(bike))
            // A stale fix is shown as it is, not greyed into a different icon;
            // "how old is this fix" belongs in the chrome, in words.
            .enableStaleState(false)
            .elevation(0f)
            .accuracyAlpha(0.12f)
            .trackingGesturesManagement(true)
            .build()
    }

    fun setBike(colour: String) {
        bike = colour
        if (locationActive) map?.locationComponent?.applyStyle(markerOptions())
    }

    fun setNorthUp(on: Boolean) {
        northUp = on
        if (locationActive) follow()
    }

    /** Back to following her after she has panned away. */
    fun follow() {
        val lc = map?.locationComponent ?: return
        if (!locationActive) return
        // Zoom goes in with a mode change, because a separate zoomWhileTracking()
        // is ignored while the mode is still transitioning (seen in logcat). But
        // asking for the mode it is already in changes nothing, zoom included
        // (emulator: stayed at z11), so then zoom on its own.
        val mode = if (northUp) CameraMode.TRACKING_GPS_NORTH else CameraMode.TRACKING_GPS
        // No fix yet: follow, but keep the overview. Riding zoom at the map's
        // centre was a blank patch of the Bay that looked broken (first start,
        // indoors); the first fix zooms in (showFix).
        if (!hadFix) {
            if (lc.cameraMode != mode) lc.cameraMode = mode
            return
        }
        if (lc.cameraMode == mode) lc.zoomWhileTracking(RIDING_ZOOM)
        else lc.setCameraMode(mode, 750L, RIDING_ZOOM, null, null, null)
    }

    /** Moves her bike to [fix]; the camera follows if it is tracking. */
    fun showFix(fix: Location) {
        if (!locationActive) return
        map?.locationComponent?.forceLocationUpdate(fix)
        // Following starts before any fix exists, and a camera transition with
        // no location to go to keeps the overview zoom. Redo it on the first.
        if (!hadFix) {
            hadFix = true
            follow()
        }
        if (appContext.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            // Debug builds: the camera as it really is, so "the map was at riding
            // zoom" is read back rather than assumed.
            map?.cameraPosition?.let { Log.d(TAG, "camera zoom=%.2f bearing=%.1f".format(it.zoom, it.bearing)) }
        }
    }

    private var hadFix = false

    private var clear = doubleArrayOf(0.0, 0.0, 0.0, 0.0)

    /**
     * Keeps her bike out from under the turn banner and the dash: the camera
     * centres in the map inside [pad] (left, top, right, bottom, px - see
     * CameraClear). In landscape the banner and the route line under it cover
     * nearly half the screen, and the bike sat behind them - then, pushed below
     * them, on the gauges.
     */
    fun setClear(pad: DoubleArray) {
        if (pad.contentEquals(clear)) return
        clear = pad
        val m = map ?: return
        if (locationActive && m.locationComponent.cameraMode != CameraMode.NONE) {
            m.locationComponent.paddingWhileTracking(pad, 300L)
        } else {
            m.moveCamera(org.maplibre.android.camera.CameraUpdateFactory.paddingTo(pad))
        }
    }

    fun onStart() = mapView.onStart()
    fun onResume() = mapView.onResume()
    fun onPause() = mapView.onPause()
    fun onStop() = mapView.onStop()
    fun onLowMemory() = mapView.onLowMemory()
    fun onDestroy() = mapView.onDestroy()
    fun onSaveInstanceState(out: Bundle) = mapView.onSaveInstanceState(out)

    private companion object {
        const val TAG = "NaviMap"
        const val MIN_ZOOM = 8.0
        // Vector tiles overzoom sharply past the archive's own maxzoom.
        const val MAX_ZOOM = 19.0
        const val RIDING_ZOOM = 16.5
        const val ROUTE_SOURCE = "agedashii-route"
        const val ROUTE_CASING = "agedashii-route-casing"
        const val ROUTE_LINE = "agedashii-route-line"
        const val MARKS_SOURCE = "agedashii-marks"
        const val MARKS_LAYER = "agedashii-marks"
        const val EMPTY = "{\"type\":\"FeatureCollection\",\"features\":[]}"
        const val FALLBACK_WIDTH = "[\"interpolate\",[\"exponential\",1.5],[\"zoom\"],10,2.75,14,7.5,18,32.5]"
        val BIKES = mapOf("yellow" to R.drawable.bike_yellow, "black" to R.drawable.bike_black)
    }
}
