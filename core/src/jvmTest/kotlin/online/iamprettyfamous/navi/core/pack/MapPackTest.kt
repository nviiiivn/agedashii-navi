package online.iamprettyfamous.navi.core.pack

import okio.Buffer
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toOkioPath
import okio.buffer
import okio.use
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class MapPackTest {
    private val fs = FileSystem.SYSTEM
    private val dir: Path = Files.createTempDirectory("mappack").toOkioPath()

    // /tmp is RAM on the Pi, and the real-pack test writes 200 MB there.
    @AfterTest
    fun cleanUp() = fs.deleteRecursively(dir)

    private val files = linkedMapOf(
        "bayarea.pmtiles" to ByteArray(300_000) { (it * 7).toByte() },
        "bayarea-valhalla-3.6.3.tar" to ByteArray(2_500_000) { (it % 251).toByte() },
        "places.sqlite" to "SQLite format 3\u0000 Greater Seattle".toByteArray(),
    )

    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    private fun manifest(region: String = "Greater Seattle", damage: String? = null) = buildString {
        append("version 9612009b26b87563\nregion $region\n")
        for ((name, bytes) in files) append("$name\t${bytes.size}\t${if (name == damage) sha(byteArrayOf(1)) else sha(bytes)}\n")
    }.toByteArray()

    /** A pack as make_pack writes one: stored entries, the MANIFEST first. */
    private fun pack(
        entries: List<Pair<String, ByteArray>> = listOf("MANIFEST" to manifest()) + files.toList(),
        deflate: Boolean = false,
    ): Buffer {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            for ((name, bytes) in entries) {
                val e = ZipEntry(name)
                if (!deflate) {
                    e.method = ZipEntry.STORED
                    e.size = bytes.size.toLong()
                    e.compressedSize = bytes.size.toLong()
                    e.crc = CRC32().apply { update(bytes) }.value
                }
                z.putNextEntry(e)
                z.write(bytes)
                z.closeEntry()
            }
        }
        return Buffer().write(out.toByteArray())
    }

    private fun target(name: String): Path? = if (name in files) dir / "data" / name else null

    private fun install(src: Buffer, free: Long? = null) = MapPack.install(src, fs, ::target, free)

    @Test
    fun putsEveryFileInPlaceAndSaysWhichRegion() {
        val r = install(pack())
        assertEquals(MapPack.Result.Installed("9612009b26b87563", "Greater Seattle", files.values.sumOf { it.size.toLong() }), r)
        for ((name, bytes) in files) assertEquals(sha(bytes), MapPack.sha256(fs, dir / "data" / name))
        assertTrue(fs.list(dir / "data").none { it.name.endsWith(".part") })
    }

    @Test
    fun aDamagedFileLeavesTheOldRegionWhole() {
        fs.createDirectories(dir / "data")
        for (name in files.keys) fs.write(dir / "data" / name) { writeUtf8("old $name") }
        val r = install(pack(listOf("MANIFEST" to manifest(damage = "places.sqlite")) + files.toList()))
        assertIs<MapPack.Result.Failed>(r)
        assertTrue("damaged (places.sqlite)" in r.why, r.why)
        // The two good files came first and were NOT swapped in on their own.
        for (name in files.keys) assertEquals("old $name", fs.read(dir / "data" / name) { readUtf8() })
        assertTrue(fs.list(dir / "data").none { it.name.endsWith(".part") })
    }

    @Test
    fun aPackShortOfAFileIsRefused() {
        val r = install(pack(listOf("MANIFEST" to manifest()) + files.toList().dropLast(1)))
        assertIs<MapPack.Result.Failed>(r)
        assertTrue("incomplete (no places.sqlite)" in r.why, r.why)
        assertFalse(fs.exists(dir / "data" / "bayarea.pmtiles"))
    }

    @Test
    fun aZipWithoutItsManifestFirstIsNotAPack() {
        val r = install(pack(files.toList() + ("MANIFEST" to manifest())))
        assertIs<MapPack.Result.Failed>(r)
        assertTrue("not a map pack" in r.why, r.why)
    }

    @Test
    fun aCompressedZipIsRefusedInHerWords() {
        val r = install(pack(deflate = true))
        assertIs<MapPack.Result.Failed>(r)
        assertTrue("make_pack" in r.why, r.why)
    }

    @Test
    fun filesTheManifestDoesNotNameAreSkipped() {
        val r = install(pack(listOf("MANIFEST" to manifest(), "README.txt" to "hi".toByteArray()) + files.toList()))
        assertIs<MapPack.Result.Installed>(r)
        assertFalse(fs.exists(dir / "data" / "README.txt"))
    }

    @Test
    fun notEnoughRoomIsSaidBeforeAnythingIsWritten() {
        val r = install(pack(), free = 1_000_000)
        assertIs<MapPack.Result.Failed>(r)
        assertTrue("Not enough space" in r.why, r.why)
        assertFalse(fs.exists(dir / "data"))
    }

    /** A real pack, when one is named: NAVI_REAL_PACK=/path/to/seattle.navipack ./gradlew :core:jvmTest */
    @Test
    fun aRealPackFromMakePackInstalls() {
        val real = System.getenv("NAVI_REAL_PACK")?.let { java.io.File(it) }?.takeIf { it.isFile } ?: return
        val r = fs.source(real.toOkioPath()).buffer().use { src ->
            MapPack.install(src, fs, { dir / "real" / it })
        }
        assertIs<MapPack.Result.Installed>(r, r.toString())
        println("[real pack] $r")
    }

    @Test
    fun aPackCutShortIsSaidSo() {
        val whole = pack().readByteArray()
        val r = install(Buffer().write(whole, 0, whole.size / 2))
        assertIs<MapPack.Result.Failed>(r)
        assertTrue("cut short" in r.why, r.why)
    }
}
