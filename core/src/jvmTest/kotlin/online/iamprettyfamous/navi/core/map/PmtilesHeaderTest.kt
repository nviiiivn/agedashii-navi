package online.iamprettyfamous.navi.core.map

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PmtilesHeaderTest {
    /** A v3 header with the Bay Area archive's real values (omt-slim.pmtiles, 2026-09-27). */
    private fun bayArea(magic: String = "PMTiles", version: Int = 3): ByteArray {
        val b = ByteBuffer.allocate(PmtilesHeader.SIZE).order(ByteOrder.LITTLE_ENDIAN)
        b.put(magic.toByteArray(Charsets.US_ASCII))
        b.put(7, version.toByte())
        b.put(100, 0)
        b.put(101, 14)
        b.putInt(102, -1_227_000_000).putInt(106, 371_500_000)
        b.putInt(110, -1_217_000_000).putInt(114, 381_500_000)
        b.put(118, 9)
        b.putInt(119, -1_222_000_000).putInt(123, 376_500_000)
        return b.array()
    }

    @Test
    fun readsTheZoomRangeBoundsAndCentre() {
        val h = PmtilesHeader.parse(bayArea())!!
        assertEquals(0, h.minZoom)
        assertEquals(14, h.maxZoom)
        assertEquals(-122.7, h.west, 1e-9)
        assertEquals(37.15, h.south, 1e-9)
        assertEquals(-121.7, h.east, 1e-9)
        assertEquals(38.15, h.north, 1e-9)
        assertEquals(9, h.centerZoom)
        assertEquals(-122.2, h.centerLon, 1e-9)
        assertEquals(37.65, h.centerLat, 1e-9)
    }

    @Test
    fun refusesWhatIsNotAV3Archive() {
        assertNull(PmtilesHeader.parse(bayArea(magic = "MBTiles")))
        assertNull(PmtilesHeader.parse(bayArea(version = 2)))
        assertNull(PmtilesHeader.parse(bayArea().copyOf(100)))
    }
}
