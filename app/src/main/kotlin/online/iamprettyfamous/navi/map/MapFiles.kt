package online.iamprettyfamous.navi.map

import java.io.File

/**
 * The offline map archive: one PMTiles file, copied to the phone once and read
 * in place. It lives in app storage and never in the APK, so an app update does
 * not re-ship the map and a map update does not need a new app.
 *
 * MapLibre 13.6.1 can only read PMTiles as `pmtiles://file://`; `asset://`
 * lacks the byte-range reads PMTiles needs (its docs, PMTiles.md, issue #4360).
 */
object MapFiles {
    const val TILES_NAME = "bayarea.pmtiles"

    /** `<external files>/map/bayarea.pmtiles`, which `adb push` can reach. */
    fun tilesFile(externalFilesDir: File?): File? =
        externalFilesDir?.let { File(File(it, "map"), TILES_NAME) }
}
