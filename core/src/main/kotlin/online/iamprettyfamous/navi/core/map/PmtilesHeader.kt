package online.iamprettyfamous.navi.core.map

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The bits of a PMTiles v3 header the camera and the style need. Read from the
 * archive itself rather than from whatever the builder wrote into its metadata,
 * so the zoom range and bounds are the data's real ones.
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
        const val SIZE = 127

        /**
         * Offsets from the PMTiles v3 spec §3.1: min/max zoom at 100/101, bounds
         * as little-endian int32 × 1e7 from 102, centre zoom at 118, centre at
         * 119. Checked against a real Bay Area archive (z0-14, -122.70,37.15 to
         * -121.70,38.15). Null for anything that is not a v3 archive.
         */
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
