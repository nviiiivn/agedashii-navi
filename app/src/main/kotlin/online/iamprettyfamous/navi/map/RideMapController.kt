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
import online.iamprettyfamous.navi.R
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

    fun onCreate(savedInstanceState: Bundle?, theme: String, bike: String, northUp: Boolean) {
        this.theme = theme
        this.bike = bike
        this.northUp = northUp
        mapView.onCreate(savedInstanceState)
        mapView.getMapAsync { m ->
            map = m
            configure(m)
            applyTheme(theme)
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
        buildingsLayer = runCatching {
            org.json.JSONObject(json).optJSONObject("metadata")?.optString("agedashii:buildings-layer")
        }.getOrNull()?.takeIf { it.isNotEmpty() }
        m.setStyle(withBikes(Style.Builder().fromJson(patched.json))) { s ->
            style = s
            showBuildings(s)
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

    private fun problem(p: Problem?) {
        lastProblem = p
        onProblem(p)
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
        val BIKES = mapOf("yellow" to R.drawable.bike_yellow, "black" to R.drawable.bike_black)
    }
}
