package online.iamprettyfamous.navi.core.routing

import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The guard is only load-bearing on a day nobody is looking, so these feed it
 * the archives that must be refused. Tars are built here byte by byte (ustar
 * headers with real checksums, plus a PAX extended header), so nothing depends
 * on the 133 MB Bay Area tar - which is checked too when NAVI_ROUTING_TAR
 * points at it.
 */
class TileVersionGuardTest {
    private fun tile(version: String, size: Int = 64): ByteArray =
        ByteArray(size).also { version.toByteArray().copyInto(it, TileVersionGuard.VERSION_OFFSET) }

    private fun tar(vararg members: Pair<String, ByteArray>, pax: Map<String, String> = emptyMap()): File {
        val out = ByteArrayOutputStream()
        fun header(name: String, size: Int, type: Char) {
            val h = ByteArray(512)
            name.toByteArray().copyInto(h, 0)
            "0000644\u0000".toByteArray().copyInto(h, 100)
            "0000000\u0000".toByteArray().copyInto(h, 108)
            "0000000\u0000".toByteArray().copyInto(h, 116)
            "%011o\u0000".format(size).toByteArray().copyInto(h, 124)
            "%011o\u0000".format(0).toByteArray().copyInto(h, 136)
            h[156] = type.code.toByte()
            "ustar\u000000".toByteArray().copyInto(h, 257)
            for (i in 148 until 156) h[i] = ' '.code.toByte()
            val sum = h.sumOf { it.toInt() and 0xFF }
            "%06o\u0000 ".format(sum).toByteArray().copyInto(h, 148)
            out.write(h)
        }
        fun body(b: ByteArray) {
            out.write(b)
            out.write(ByteArray((512 - b.size % 512) % 512))
        }
        for ((name, data) in members) {
            pax[name]?.let { long ->
                val rec = " path=$long\n"
                var len = rec.length + 1
                while ("$len$rec".length != len) len = "$len$rec".length
                val text = "$len$rec".toByteArray()
                header("PaxHeader/$name", text.size, 'x')
                body(text)
            }
            header(name, data.size, '0')
            body(data)
        }
        out.write(ByteArray(1024))
        return File.createTempFile("tiles", ".tar").apply { deleteOnExit(); writeBytes(out.toByteArray()) }
    }

    @Test
    fun `tiles built by the pinned version pass`() {
        val r = TileVersionGuard.check(tar("index.bin" to ByteArray(528), "2/000/737/508.gph" to tile("3.6.3"),
                                           "0/002/804.gph" to tile("3.6.3")))
        assertEquals(TileCheck.Ok(2, "3.6.3"), r)
    }

    @Test
    fun `a deliberately wrong version is refused, and the reason names it`() {
        val r = TileVersionGuard.check(tar("0/002/804.gph" to tile("3.7.0")))
        assertIs<TileCheck.Refused>(r)
        assertTrue("3.7.0" in r.reason && "3.6.3" in r.reason, r.reason)
    }

    @Test
    fun `the same major version is still refused - Valhalla itself would only warn`() {
        assertIs<TileCheck.Refused>(TileVersionGuard.check(tar("0/002/804.gph" to tile("3.5.0"))))
    }

    @Test
    fun `one stale tile in an otherwise good archive is refused`() {
        val r = TileVersionGuard.check(tar("0/002/804.gph" to tile("3.6.3"), "1/010/913.gph" to tile("3.6.3"),
                                           "2/000/737/508.gph" to tile("3.5.1")))
        assertIs<TileCheck.Refused>(r)
        assertTrue("2/000/737/508.gph" in r.reason, r.reason)
    }

    @Test
    fun `an archive with no tiles is refused`() {
        assertIs<TileCheck.Refused>(TileVersionGuard.check(tar("index.bin" to ByteArray(528))))
    }

    @Test
    fun `a tile too short to hold a header is refused`() {
        assertIs<TileCheck.Refused>(TileVersionGuard.check(tar("0/002/804.gph" to ByteArray(20))))
    }

    @Test
    fun `a file that is not a tar is refused`() {
        val f = File.createTempFile("junk", ".tar").apply { deleteOnExit(); writeBytes(ByteArray(4096) { (it * 31).toByte() }) }
        assertIs<TileCheck.Refused>(TileVersionGuard.check(f))
    }

    @Test
    fun `an archive cut off mid-copy is refused`() {
        val whole = tar("0/002/804.gph" to tile("3.6.3", 5000)).readBytes()
        val cut = File.createTempFile("cut", ".tar").apply { deleteOnExit(); writeBytes(whole.copyOf(3000)) }
        assertIs<TileCheck.Refused>(TileVersionGuard.check(cut))
    }

    @Test
    fun `a missing file is refused`() {
        assertIs<TileCheck.Refused>(TileVersionGuard.check(File("/nonexistent/bayarea.tar")))
    }

    @Test
    fun `a tile named only in a PAX header is still checked`() {
        // The ustar name does not end in .gph; only the PAX path does.
        val r = TileVersionGuard.check(tar("tile0" to tile("3.7.0"), pax = mapOf("tile0" to "2/000/737/508.gph")))
        assertIs<TileCheck.Refused>(r)
        assertTrue("2/000/737/508.gph" in r.reason, r.reason)
    }

    @Test
    fun `the version catalog and the guard agree`() {
        val toml = File("../gradle/libs.versions.toml").readText()
        fun entry(key: String) = Regex("""(?m)^${Regex.escape(key)}\s*=\s*"([^"]+)"""").find(toml)?.groupValues?.get(1)
        assertEquals(VALHALLA_TILES, entry("valhalla"),
            "gradle/libs.versions.toml `valhalla` must equal VALHALLA_TILES")
        assertEquals(VALHALLA_MOBILE_VERIFIED, entry("valhalla-mobile"),
            "valhalla-mobile changed: re-read which Valhalla tag it pins (src/valhalla submodule), " +
                "then update VALHALLA_TILES, VALHALLA_MOBILE_VERIFIED, the catalog and the tiles together")
    }

    @Test
    fun `the real Bay Area tar passes, when it is present`() {
        val path = System.getenv("NAVI_ROUTING_TAR")
        assumeTrue(path != null, "set NAVI_ROUTING_TAR to the built tar to run this")
        val r = TileVersionGuard.check(File(path!!))
        assertIs<TileCheck.Ok>(r)
        assertEquals(33, r.tiles)
    }
}
