package online.iamprettyfamous.navi.map

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import androidx.core.content.ContextCompat
import org.json.JSONObject
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
import java.io.File

/**
 * Owns the MapView and everything done to it. The activity forwards its
 * lifecycle here; Compose only hosts [mapView] and draws the chrome over it.
 *
 * Nothing in here touches the network: the tiles are a file, the style and
 * glyphs are in the APK, and the app does not hold the INTERNET permission.
 */
class RideMapController(context: Context) {

    enum class Problem { NO_TILES, UNREADABLE_STORAGE, BAD_TILES, NO_STYLE }

    private val appContext = context.applicationContext

    init {
        MapLibre.getInstance(appContext)
    }

    val tiles: File? = MapFiles.tilesFile(appContext.getExternalFilesDir(null))
    val header: PmtilesHeader? = tiles?.takeIf { it.isFile }?.let { PmtilesHeader.read(it) }

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
        // OSM's licence needs the credit reachable; the "i" button keeps it,
        // top right, clear of the round buttons along the bottom.
        m.uiSettings.isAttributionEnabled = true
        m.uiSettings.attributionGravity = Gravity.TOP or Gravity.END
        val dp = appContext.resources.displayMetrics.density
        m.uiSettings.setAttributionMargins(0, (56 * dp).toInt(), (12 * dp).toInt(), 0)
        m.uiSettings.isCompassEnabled = false
        m.setMaxZoomPreference(MAX_ZOOM)
        val h = header ?: return
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
            val readable = appContext.getExternalFilesDir(null)?.list() != null
            return onProblem(if (readable) Problem.NO_TILES else Problem.UNREADABLE_STORAGE)
        }
        val h = header ?: return onProblem(Problem.BAD_TILES)
        val json = MapStyles.load(appContext.assets, key, file)
            ?: MapStyles.load(appContext.assets, "night", file)
            ?: return onProblem(Problem.NO_STYLE)
        onProblem(null)
        m.setStyle(withBikes(Style.Builder().fromJson(withRealZoomRange(json, h)))) { s ->
            style = s
            if (locationWanted) activateLocation(s)
        }
    }

    /**
     * Sets every PMTiles source's zoom range to the archive's real one and makes
     * sure the OSM credit is present. Whatever the style file says, the data
     * decides: a maxzoom above the data's asks for tiles that do not exist and
     * draws nothing, which is exactly how the Flet map went blank above z15.
     */
    private fun withRealZoomRange(json: String, h: PmtilesHeader): String {
        val root = JSONObject(json)
        val sources = root.optJSONObject("sources") ?: return json
        for (name in sources.keys()) {
            val src = sources.optJSONObject(name) ?: continue
            if (!src.optString("url").startsWith("pmtiles://")) continue
            // Compared as numbers: JSON may say 14 or 14.0, and both are right.
            val said = src.opt("maxzoom")
            if ((said as? Number)?.toDouble() != h.maxZoom.toDouble()) {
                Log.w(TAG, "source $name maxzoom $said -> ${h.maxZoom} (from the archive)")
            }
            src.put("minzoom", h.minZoom)
            src.put("maxzoom", h.maxZoom)
            if (!src.optString("attribution").contains("OpenStreetMap")) {
                src.put("attribution", "© OpenStreetMap contributors")
            }
        }
        return root.toString()
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
            // No engine of its own: fixes come from GpsSource via showFix().
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
