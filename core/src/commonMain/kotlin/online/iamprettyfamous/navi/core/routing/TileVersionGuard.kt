package online.iamprettyfamous.navi.core.routing

import okio.EOFException
import okio.FileHandle
import okio.Path.Companion.toPath
import okio.use
import online.iamprettyfamous.navi.core.Disk

/**
 * The Valhalla version the routing tiles must be built with: the Valhalla tag
 * that valhalla-mobile pins as its `src/valhalla` submodule.
 *
 * valhalla-mobile 0.6.3 -> submodule e2f017b16080f49203de245a211b09efab09cf72,
 * which is exactly Valhalla's `3.6.3` tag. Read from the GitHub API on
 * 2026-09-27 (rallista/valhalla-mobile tag 0.6.3; valhalla/valhalla
 * refs/tags/3.6.3). That mapping is a HUMAN step: nothing offline can derive it
 * from the binding, so bumping valhalla-mobile means re-reading the submodule
 * pin and changing this, `valhalla` in gradle/libs.versions.toml, and the tiles.
 * TileVersionGuardTest fails until all three agree.
 */
const val VALHALLA_TILES = "3.6.3"

/** The valhalla-mobile version the mapping above was verified for. */
const val VALHALLA_MOBILE_VERIFIED = "0.6.3"

/** What [TileVersionGuard.check] found. */
sealed interface TileCheck {
    data class Ok(val tiles: Int, val version: String) : TileCheck

    /** [reason] is written for the screen: routing is off, and this says why. */
    data class Refused(val reason: String) : TileCheck
}

/**
 * Refuses a routing tile archive unless EVERY tile in it was built by exactly
 * [VALHALLA_TILES].
 *
 * Valhalla will not do this itself. At 3.6.3 its only check
 * (src/baldr/graphtile.cc:320-323) compares the MAJOR digit alone -
 * `header_->version()[0] - '0'` - and on a mismatch only logs a warning, so
 * tiles from 3.5 or 3.7 load and are then read through the wrong struct
 * layout. The one thing that throws is a size mismatch (graphtile.cc:315).
 * "Loads fine, then behaves wrongly" is the failure this guard exists for; run
 * it before the router is constructed, and keep routing off if it refuses.
 *
 * The archive is the tar valhalla_build_extract writes (PAX format, an
 * `index.bin` member and one `<level>/<a>/<b>.gph` member per tile). Each
 * tile starts with a GraphTileHeader whose `version_` is 16 chars at byte
 * offset 16 (valhalla/baldr/graphtileheader.h:606-619 at 3.6.3: a 64-bit
 * bitfield, then `base_ll_` as two floats, then `version_`). Only 32 bytes of
 * each tile are read, so the 133 MB Bay Area tar is checked almost instantly.
 */
object TileVersionGuard {
    const val VERSION_OFFSET = 16
    const val VERSION_SIZE = 16
    private const val BLOCK = 512

    fun check(tar: String, expected: String = VALHALLA_TILES): TileCheck {
        val path = tar.toPath()
        if (Disk.metadataOrNull(path)?.isRegularFile != true) return TileCheck.Refused("No routing tiles at $tar")
        return Disk.openReadOnly(path).use { check(it, expected, path.name) }
    }

    private fun check(f: FileHandle, expected: String, label: String): TileCheck {
        val length = f.size()
        val header = ByteArray(BLOCK)
        var pos = 0L
        var tiles = 0
        var paxPath: String? = null
        var paxSize: Long? = null
        var longName: String? = null
        while (true) {
            if (pos + BLOCK > length) return TileCheck.Refused("$label is truncated: no end-of-archive marker")
            readFully(f, pos, header)
            if (header.all { it == 0.toByte() }) break
            if (!checksumOk(header)) return TileCheck.Refused("$label is not a tar archive (bad header at byte $pos)")
            val type = header[156].toInt().toChar()
            val size = paxSize ?: octal(header, 124, 12)
                ?: return TileCheck.Refused("$label has an unreadable member size at byte $pos")
            val data = pos + BLOCK
            if (data + size > length) return TileCheck.Refused("$label is truncated inside a member at byte $pos")
            when (type) {
                'x' -> {
                    val pax = readPax(f, data, size)
                    paxPath = pax["path"]
                    paxSize = pax["size"]?.toLongOrNull()
                }
                'g' -> Unit // global PAX header: nothing in it matters here
                'L' -> longName = readString(f, data, size.toInt())
                else -> {
                    val name = paxPath ?: longName ?: ustarName(header)
                    if ((type == '0' || type == '\u0000') && name.endsWith(".gph")) {
                        if (size < VERSION_OFFSET + VERSION_SIZE) {
                            return TileCheck.Refused("Routing tile $name is only $size bytes")
                        }
                        val raw = ByteArray(VERSION_SIZE).also { readFully(f, data + VERSION_OFFSET, it) }
                        val found = raw.decodeToString().substringBefore('\u0000')
                        if (found != expected) {
                            return TileCheck.Refused(
                                "Routing tiles were built with Valhalla $found; this app needs $expected ($name)")
                        }
                        tiles++
                    }
                    paxPath = null
                    paxSize = null
                    longName = null
                }
            }
            pos = data + (size + BLOCK - 1) / BLOCK * BLOCK
        }
        return if (tiles == 0) TileCheck.Refused("$label holds no routing tiles")
        else TileCheck.Ok(tiles, expected)
    }

    private fun checksumOk(h: ByteArray): Boolean {
        val stored = octal(h, 148, 8) ?: return false
        var sum = 0L
        for (i in 0 until BLOCK) sum += if (i in 148 until 156) 32 else (h[i].toInt() and 0xFF)
        return sum == stored
    }

    /** A NUL/space-terminated octal field; base-256 (high bit set) for big sizes. */
    private fun octal(h: ByteArray, off: Int, len: Int): Long? {
        if (h[off].toInt() and 0x80 != 0) {
            var v = 0L
            for (i in off + 1 until off + len) v = (v shl 8) or (h[i].toLong() and 0xFF)
            return v
        }
        val s = h.decodeToString(off, off + len).trim { it == ' ' || it == '\u0000' }
        return if (s.isEmpty()) 0L else s.toLongOrNull(8)
    }

    private fun ustarName(h: ByteArray): String {
        val name = cString(h, 0, 100)
        val isUstar = h.decodeToString(257, 262) == "ustar"
        val prefix = if (isUstar) cString(h, 345, 155) else ""
        return if (prefix.isEmpty()) name else "$prefix/$name"
    }

    private fun cString(h: ByteArray, off: Int, len: Int): String {
        var end = off
        while (end < off + len && h[end] != 0.toByte()) end++
        return h.decodeToString(off, end)
    }

    private fun readString(f: FileHandle, at: Long, len: Int): String {
        val b = ByteArray(len).also { readFully(f, at, it) }
        return b.decodeToString().substringBefore('\u0000')
    }

    /** RandomAccessFile.readFully at an offset: all of `into`, or EOFException. */
    private fun readFully(f: FileHandle, at: Long, into: ByteArray) {
        var done = 0
        while (done < into.size) {
            val n = f.read(at + done, into, done, into.size - done)
            if (n == -1) throw EOFException("end of file at byte ${at + done}")
            done += n
        }
    }

    /** PAX records are `"<len> <key>=<value>\n"`, len counting the whole record. */
    private fun readPax(f: FileHandle, at: Long, size: Long): Map<String, String> {
        val text = readString(f, at, size.toInt())
        val out = mutableMapOf<String, String>()
        var i = 0
        while (i < text.length) {
            val sp = text.indexOf(' ', i).takeIf { it > i } ?: break
            val n = text.substring(i, sp).toIntOrNull() ?: break
            val rec = text.substring(sp + 1, minOf(text.length, i + n)).trimEnd('\n')
            val eq = rec.indexOf('=')
            if (eq > 0) out[rec.substring(0, eq)] = rec.substring(eq + 1)
            i += n
        }
        return out
    }
}
