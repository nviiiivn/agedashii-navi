package online.iamprettyfamous.navi.host

import platform.UIKit.UIView

/**
 * The two things only Swift can reach, handed to the Kotlin app by the Swift
 * one (iosApp/): MapLibre's map view and Valhalla's router. Both are Swift
 * packages with no Objective-C surface Kotlin/Native could bind to, so Swift
 * implements these and Kotlin decides everything else - the same split as the
 * Android app, where RideMapController and Router own the decisions and
 * MapLibre and Valhalla only draw and route.
 *
 * Kept deliberately small and stringly: a style is a JSON document, a source's
 * data is GeoJSON, a route request and answer are Valhalla's own JSON. Nothing
 * here needs a Kotlin type on the Swift side, and nothing in Swift decides what
 * the map shows.
 */
interface MapHost {
    /** The map's view, for Compose to put under the chrome. Created once. */
    fun view(): UIView

    /** Load a complete MapLibre style document, replacing the one shown. */
    fun loadStyle(json: String)

    /**
     * Replace the data of the GeoJSON source [sourceId] in the loaded style. A
     * call before the style has loaded is kept and applied once it has.
     */
    fun setSourceData(sourceId: String, geojson: String)

    /** Show or hide one layer of the loaded style (buildings). Kept across reloads. */
    fun setLayerVisible(layerId: String, visible: Boolean)

    /** Her marker: "yellow" or "black", the same two bikes the Android app draws. */
    fun setBike(colour: String)

    /**
     * Her position, from the app's own GPS stream. [course] is degrees from
     * north, or negative when unknown; [accuracy] metres, or negative.
     */
    fun showFix(lat: Double, lon: Double, course: Double, accuracy: Double)

    /** Follow her at [zoom]: heading up, or north up. A pan stops it (onFollowingChanged). */
    fun follow(northUp: Boolean, zoom: Double)

    /** Where the camera starts, before any fix. */
    fun setCamera(lat: Double, lon: Double, zoom: Double)

    /** Keep the camera inside the map's own box and zoom range. */
    fun setLimits(south: Double, west: Double, north: Double, east: Double, minZoom: Double, maxZoom: Double)

    /** Centre the camera this many points below the top, so the bike is never under the banner. */
    fun setTopInset(points: Double)

    /** Told what the map does that the Kotlin side has to know. Set once. */
    fun setEvents(events: MapEvents)
}

/** What the map tells the app. Called on the main thread. */
interface MapEvents {
    fun onStyleLoaded()
    fun onStyleFailed(why: String)
    fun onFollowingChanged(following: Boolean)
    fun onLongPress(lat: Double, lon: Double)
}

/** Valhalla on the phone. Called off the main thread, one call at a time. */
interface RouterHost {
    /** Open the tile archive at [tarPath]. Null when it is ready, else why not. */
    fun open(tarPath: String): String?

    /**
     * Valhalla's /route for the request JSON: the response JSON, or the error
     * prefixed with [ERROR] when it refuses (Valhalla's own text after it).
     */
    fun route(request: String): String

    companion object {
        const val ERROR = "ERROR:"
    }
}

/**
 * Files only Swift can hand over: a map pack picked in Files (the document
 * picker) or opened with "Open in xnav.k". Swift holds the file's
 * security-scoped access while Kotlin reads it, and the pack arrives through
 * MainViewController.kt's OpenPack, whichever way it came.
 */
interface FileHost {
    /** Show the Files picker for a map pack. */
    fun pickPack()

    /** The pack at [path] has been read to the end: give it back to iOS. */
    fun releasePack(path: String)
}
