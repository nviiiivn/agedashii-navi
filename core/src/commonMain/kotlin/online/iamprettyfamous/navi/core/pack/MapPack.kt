package online.iamprettyfamous.navi.core.pack

import okio.BufferedSource
import okio.FileSystem
import okio.HashingSink
import okio.Path
import okio.Path.Companion.toPath
import okio.blackholeSink
import okio.buffer
import okio.use

/**
 * A map pack (.navipack, tools/make_pack.py) put in place, for a phone that
 * has no java.util.zip: the iPhone. The Android app does the same in its own
 * DataPack with ZipInputStream; this is that, in common code, so the Pi tests it.
 *
 * A pack is a zip whose FIRST entry is the MANIFEST, so the region and every
 * size and sha256 are known before a byte of the 200 MB behind them is read,
 * and the pack is read once, front to back, never copied somewhere seekable.
 * make_pack stores its entries (the tiles and the router's archive are already
 * compressed), so each entry is a local header and then exactly its bytes.
 *
 * Unlike a file-at-a-time swap, nothing is replaced until every file has
 * arrived and matched the MANIFEST: a damaged or short pack leaves the region
 * already on the phone whole, never half one region and half another.
 */
object MapPack {
    const val SUFFIX = ".navipack"

    data class Entry(val name: String, val size: Long, val sha256: String)

    /**
     * A MANIFEST: `version`, `region` header lines, then name TAB size TAB
     * sha256 per file. A pack with no region line still reads.
     */
    class Listing(lines: List<String>) {
        val version: String = lines.firstOrNull { it.startsWith("version ") }?.removePrefix("version ")?.trim() ?: ""
        val region: String = lines.firstOrNull { it.startsWith("region ") }?.removePrefix("region ")?.trim() ?: ""
        val entries: List<Entry> = lines.filter { it.contains('\t') }.map { line ->
            val (name, size, sha) = line.split("\t")
            Entry(name, size.toLong(), sha)
        }
    }

    sealed interface Result {
        data class Installed(val version: String, val region: String, val bytes: Long) : Result
        /** Why not, in words she can act on. */
        data class Failed(val why: String) : Result
    }

    /** Room left over after the unpack: the ride logs and the system need some. */
    const val SPARE_BYTES = 200_000_000L

    private const val LOCAL = 0x04034b50L
    private const val MAX32 = 0xFFFFFFFFL

    private class Header(val name: String, val size: Long)

    /**
     * The next entry's local header, or null at the central directory (the
     * end of the entries). Throws [IllegalStateException] with her words for
     * a zip this reader cannot follow.
     */
    private fun next(src: BufferedSource): Header? {
        if (src.exhausted()) return null
        val sig = src.readIntLe().toLong() and MAX32
        if (sig != LOCAL) return null
        src.skip(2)                                   // version needed
        val flags = src.readShortLe().toInt() and 0xFFFF
        val method = src.readShortLe().toInt() and 0xFFFF
        src.skip(8)                                   // time, date, crc
        var compressed = src.readIntLe().toLong() and MAX32
        val size = src.readIntLe().toLong() and MAX32
        val nameLen = src.readShortLe().toInt() and 0xFFFF
        var extraLen = src.readShortLe().toInt() and 0xFFFF
        val name = src.readUtf8(nameLen.toLong())
        // Zip64: the real sizes are in the extra field, uncompressed first.
        var real = size
        while (extraLen >= 4) {
            val id = src.readShortLe().toInt() and 0xFFFF
            val len = src.readShortLe().toInt() and 0xFFFF
            extraLen -= 4 + len
            if (id == 0x0001 && len >= 16 && size == MAX32) {
                real = src.readLongLe()
                compressed = src.readLongLe()
                src.skip(len - 16L)
            } else {
                src.skip(len.toLong())
            }
        }
        if (extraLen > 0) src.skip(extraLen.toLong())
        check(flags and 0x8 == 0) { "That map pack was zipped by something other than make_pack - its sizes are not in it" }
        check(method == 0) { "That map pack is compressed - xnav.k reads packs as make_pack writes them, stored" }
        check(compressed == real) { "That map pack's sizes disagree ($name)" }
        return Header(name.substringAfterLast('/'), real)
    }

    /**
     * Read a pack from [src] and put its files where [target] says (null: a
     * file this phone has no use for, skipped). Each goes to a .part beside
     * its target first; all of them are checked, and only then renamed over
     * the old ones. [freeBytes] is the room on the phone, when known.
     */
    fun install(
        src: BufferedSource,
        fs: FileSystem,
        target: (String) -> Path?,
        freeBytes: Long? = null,
        progress: (done: Long, total: Long) -> Unit = { _, _ -> },
    ): Result {
        val parts = ArrayList<Pair<Path, Path>>()
        fun fail(why: String): Result.Failed {
            parts.forEach { (part, _) -> fs.delete(part, mustExist = false) }
            return Result.Failed(why)
        }
        return try {
            val first = next(src)
            if (first == null || first.name != "MANIFEST") {
                return fail("That file is not a map pack - a pack is a $SUFFIX whose first entry is its MANIFEST")
            }
            val listing = Listing(src.readUtf8(first.size).lines())
            if (listing.entries.isEmpty()) return fail("That map pack names no files - it may be incomplete")
            val total = listing.entries.sumOf { it.size }
            if (freeBytes != null && freeBytes < total + SPARE_BYTES) {
                return fail("Not enough space for the map: it needs ${(total + SPARE_BYTES) / 1_000_000} MB free and the phone has ${freeBytes / 1_000_000} MB")
            }
            var done = 0L
            val seen = HashSet<String>()
            while (true) {
                val h = next(src) ?: break
                val e = listing.entries.firstOrNull { it.name == h.name }
                val dst = e?.let { target(it.name) }
                if (e == null || dst == null || h.name in seen) {
                    src.skip(h.size)
                    continue
                }
                if (h.size != e.size) return fail("The map data is damaged (${e.name}) - ask for the pack again")
                dst.parent?.let { fs.createDirectories(it) }
                val part = "$dst.part".toPath()
                parts += part to dst
                val hash = HashingSink.sha256(fs.sink(part))
                hash.buffer().use { out ->
                    var left = e.size
                    while (left > 0) {
                        val n = minOf(left, 1L shl 20)
                        out.write(src, n)
                        left -= n
                        done += n
                        progress(done, total)
                    }
                }
                if (hash.hash.hex() != e.sha256) return fail("The map data is damaged (${e.name}) - ask for the pack again")
                seen += e.name
            }
            val missing = listing.entries.filter { target(it.name) != null && it.name !in seen }
            if (missing.isNotEmpty()) return fail("That map pack is incomplete (no ${missing.first().name}) - ask for it again")
            for ((part, dst) in parts) fs.atomicMove(part, dst)
            parts.clear()
            Result.Installed(listing.version, listing.region, done)
        } catch (ex: IllegalStateException) {
            fail(ex.message ?: "That map pack could not be read")
        } catch (ex: okio.IOException) {
            fail("The map pack could not be read to the end (${ex.message}) - it may be cut short; ask for it again")
        }
    }

    /** sha256 of a file, for the tests and for checking what is in place. */
    fun sha256(fs: FileSystem, path: Path): String =
        HashingSink.sha256(blackholeSink()).use { h -> fs.source(path).buffer().use { it.readAll(h) }; h.hash.hex() }
}
