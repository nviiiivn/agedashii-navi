package online.iamprettyfamous.navi.data

import platform.Foundation.NSBundle
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSUserDomainMask

/**
 * Where this build's files are. Her map data rides INSIDE the app on the
 * iPhone - the map, the router's tiles and the places list are read where they
 * lie in the bundle, so there is nothing to unpack on first start (Android has
 * to copy them out of the APK; an iOS bundle is already files on disk).
 *
 * Her rides go in Documents, which the Files app shows (UIFileSharingEnabled),
 * so a ride log can leave the phone with no cable and no server.
 */
object DataFiles {
    const val TILES = "bayarea.pmtiles"
    const val ROUTER = "bayarea-valhalla-3.6.3.tar"
    const val PLACES = "places.sqlite"

    private fun bundled(dir: String, name: String): String? {
        val dot = name.lastIndexOf('.')
        return NSBundle.mainBundle.pathForResource(name.substring(0, dot), ofType = name.substring(dot + 1), inDirectory = dir)
    }

    /** The map archive, or null when this build carries none. */
    val tiles: String? get() = bundled("data", TILES)
    val router: String? get() = bundled("data", ROUTER)
    val places: String? get() = bundled("data", PLACES)

    /** A folder of the bundle - styles, glyphs, voice - or null. */
    fun folder(name: String): String? =
        NSBundle.mainBundle.resourcePath?.let { "$it/$name" }?.takeIf { NSFileManager.defaultManager.fileExistsAtPath(it) }

    /** Documents/<name>, made if it is not there. */
    fun documents(name: String): String {
        @Suppress("UNCHECKED_CAST")
        val docs = (NSSearchPathForDirectoriesInDomains(NSDocumentDirectory, NSUserDomainMask, true) as List<String>).first()
        val path = "$docs/$name"
        NSFileManager.defaultManager.createDirectoryAtPath(path, withIntermediateDirectories = true, attributes = null, error = null)
        return path
    }
}
