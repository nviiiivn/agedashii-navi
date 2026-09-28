package online.iamprettyfamous.navi.map

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

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

/**
 * The bits of a PMTiles v3 header the camera needs. Read from the archive
 * itself rather than from whatever the builder wrote into its metadata, so the
 * zoom range and bounds are the data's real ones (the raster archive's z15
 * ceiling going unnoticed is what blanked the Flet map at riding zoom).
 */
data class PmtilesHeader(
    val minZoom: Int,
    val maxZoom: Int,
    val west: Double,
    val south: Double,
    val east: Double,
    val north: Double,
    val centerZoom: Int,
    val centerLon: Double,
    val centerLat: Double,
) {
    companion object {
        private const val SIZE = 127

        /** Offsets from the PMTiles v3 spec, §3.1; checked against a real file. */
        fun parse(bytes: ByteArray): PmtilesHeader? {
            if (bytes.size < SIZE) return null
            if (String(bytes, 0, 7, Charsets.US_ASCII) != "PMTiles" || bytes[7].toInt() != 3) return null
            val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            fun e7(at: Int) = b.getInt(at) / 1e7
            return PmtilesHeader(
                minZoom = bytes[100].toInt() and 0xff,
                maxZoom = bytes[101].toInt() and 0xff,
                west = e7(102), south = e7(106),
                east = e7(110), north = e7(114),
                centerZoom = bytes[118].toInt() and 0xff,
                centerLon = e7(119), centerLat = e7(123),
            )
        }

        fun read(file: File): PmtilesHeader? = runCatching {
            RandomAccessFile(file, "r").use { f ->
                val buf = ByteArray(SIZE)
                f.readFully(buf)
                parse(buf)
            }
        }.getOrNull()
    }
}
