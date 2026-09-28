package online.iamprettyfamous.navi.map

import java.io.File

/**
 * The offline map archive: one PMTiles file, read in place from app storage.
 * A build made with -PnaviData carries it inside the APK and DataPack unpacks
 * it there on first start - so her phone needs one download and no cable - or
 * it can still be copied in with adb, as the test scripts do.
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
