package online.iamprettyfamous.navi.voice

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import online.iamprettyfamous.navi.core.voice.VoiceLines
import java.io.File
import java.io.InputStream
import java.util.zip.ZipException
import java.util.zip.ZipInputStream

/**
 * Her recorded voice on the phone: getting the clips from her laptop into
 * Navi, and out again. voice_import.py's path and rules, so one set of her
 * files works in both apps.
 *
 * THE PATH: no adb and no cable. She puts the clips on the phone any way she
 * already knows (a ZIP in Drive, Quick Share into Download), then in Settings
 * taps "Import voice" and picks them in Android's own document picker - the
 * loose clips or one ZIP. The picker (ACTION_OPEN_DOCUMENT) needs no
 * permission: the file she picks is granted for that one read. The app's own
 * storage is invisible to file managers, so there is no folder to drop into.
 *
 * MERGE: each clip picked replaces that clip, whatever format either is in,
 * and every other clip stays. A new clip is written beside the old one and
 * renamed over it, so an import that fails partway leaves the old one
 * playable. The file name is the prompt (VoiceLines.parse); anything that is
 * no prompt's is left out and named in the result, never guessed at.
 */
object VoiceClips {
    private const val TAG = "NaviVoice"
    private const val INCOMING = ".incoming-"

    /** Her built-in voice, shipped inside the APK (assets/voice) so it works on first launch. */
    private const val BUILT_IN = "voice"

    fun dir(context: Context): File = File(context.filesDir, "voice")

    /** Where a clip comes from: one she imported, or the one built into the app. */
    sealed interface Source {
        data class Imported(val file: File) : Source
        data class BuiltIn(val asset: String) : Source
    }

    /**
     * THE precedence, in one place: her imported clip wins, then the built-in
     * one, then (by being absent here) the phone's voice. "Remove voice" clears
     * only hers, so it returns her to the built-in set, never to silence.
     */
    fun sources(context: Context): Map<String, Source> {
        val out = LinkedHashMap<String, Source>()
        for ((name, file) in imported(context)) out[name] = Source.Imported(file)
        for ((name, asset) in builtIn(context)) if (name !in out) out[name] = Source.BuiltIn(asset)
        return out
    }

    fun have(context: Context): Set<String> = sources(context).keys

    /** Name -> her imported file: the best-ranked format when there are two. */
    fun imported(context: Context): Map<String, File> = clipsIn(dir(context))

    @Volatile private var builtInCache: Map<String, String>? = null

    /** Name -> asset path of the built-in clips; listed once, since the APK does not change. */
    fun builtIn(context: Context): Map<String, String> = builtInCache ?: run {
        val out = HashMap<String, String>()
        val files = context.assets.list(BUILT_IN).orEmpty().sortedBy { f ->
            VoiceLines.EXTS.indexOf("." + f.substringAfterLast('.', "").lowercase()).let { if (it < 0) Int.MAX_VALUE else it }
        }
        for (f in files) VoiceLines.parse(f).first?.let { name -> if (name !in out) out[name] = "$BUILT_IN/$f" }
        out.also { builtInCache = it }
    }

    private fun clipsIn(folder: File): Map<String, File> {
        val out = HashMap<String, File>()
        val files = folder.listFiles().orEmpty().filter { it.isFile && !it.name.startsWith(INCOMING) }
        for (f in files.sortedBy { rank(f = it) }) {
            val name = VoiceLines.parse(f.name).first
            if (name != null && name !in out) out[name] = f
        }
        return out
    }

    private fun rank(f: File): Int {
        val ext = "." + f.name.substringAfterLast('.', "").lowercase()
        return VoiceLines.EXTS.indexOf(ext).let { if (it < 0) Int.MAX_VALUE else it }
    }

    data class Result(
        val imported: MutableList<String> = ArrayList(),
        val replaced: MutableList<String> = ArrayList(),
        val ignored: MutableList<String> = ArrayList(),
        val refused: MutableList<Pair<String, String>> = ArrayList(),
        val failed: MutableList<Pair<String, String>> = ArrayList(),
        val duplicates: MutableMap<String, List<String>> = LinkedHashMap(),
    ) {
        val trouble: Boolean get() = refused.isNotEmpty() || failed.isNotEmpty() || duplicates.isNotEmpty()
    }

    private class TooBig : Exception()

    private class Candidate(val name: String, val ext: String, val label: String, val temp: File)

    /** Import clips from what she picked: audio files, ZIPs of them, or both. Off the main thread. */
    fun import(context: Context, uris: List<Uri>): Result {
        val result = Result()
        val folder = dir(context).apply { mkdirs() }
        val found = ArrayList<Candidate>()
        var n = 0
        // A ZIP is read once, as a stream, so every candidate lands as a temp file
        // first; the best one per name is chosen after, then renamed in.
        fun offer(label: String, open: () -> InputStream) {
            val (name, ext) = VoiceLines.parse(label)
            if (name == null) {
                result.ignored += label
                return
            }
            val temp = File(folder, "$INCOMING${n++}-$name$ext")
            try {
                val written = open().use { copyBounded(it, temp) }
                if (written == 0L) {
                    temp.delete()
                    result.refused += label to "empty"
                } else {
                    found += Candidate(name, ext, label, temp)
                }
            } catch (e: TooBig) {
                temp.delete()
                result.refused += label to "too big for a prompt"
            } catch (e: Exception) {
                temp.delete()
                result.failed += label to why(e)
            }
        }
        for (uri in uris) {
            val label = displayName(context, uri) ?: uri.lastPathSegment ?: "a file"
            when {
                label.lowercase().endsWith(".zip") -> readZip(context, uri, label, result, ::offer)
                debris(listOf(label)) -> {}
                else -> offer(label) { context.contentResolver.openInputStream(uri) ?: error("the phone did not hand it over") }
            }
        }
        for ((name, cands) in found.groupBy { it.name }) {
            val ordered = cands.sortedBy { VoiceLines.EXTS.indexOf(it.ext) }
            if (ordered.size > 1) result.duplicates[name] = ordered.map { it.label }
            install(folder, ordered.first(), result)
            ordered.drop(1).forEach { it.temp.delete() }
        }
        return result
    }

    private fun readZip(context: Context, uri: Uri, label: String, result: Result, offer: (String, () -> InputStream) -> Unit) {
        try {
            val input = context.contentResolver.openInputStream(uri)
            if (input == null) {
                result.failed += label to "the phone did not hand it over"
                return
            }
            ZipInputStream(input.buffered()).use { zip ->
                var entries = 0
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (++entries > VoiceLines.MAX_ZIP_ENTRIES) {
                        result.failed += label to "more than ${VoiceLines.MAX_ZIP_ENTRIES} files"
                        break
                    }
                    if (entry.isDirectory) continue
                    // Flattened: only the last part of the path is used, so
                    // "../../x.mp3" lands in the voice folder, never beside it.
                    val parts = entry.name.replace('\\', '/').split('/').filter { it.isNotEmpty() }
                    if (parts.isEmpty() || debris(parts)) continue
                    offer(parts.last()) { Entry(zip) }
                }
            }
        } catch (e: Exception) {
            result.failed += label to (if (e is ZipException) "not a readable ZIP" else why(e))
        }
    }

    /** One ZIP entry's bytes; closing it must not close the whole archive. */
    private class Entry(private val zip: ZipInputStream) : InputStream() {
        override fun read(): Int = zip.read()
        override fun read(b: ByteArray, off: Int, len: Int): Int = zip.read(b, off, len)
        override fun close() {}
    }

    private fun install(folder: File, c: Candidate, result: Result) {
        val had = VoiceLines.EXTS.any { File(folder, c.name + it).isFile }
        val final = File(folder, c.name + c.ext)
        if (!c.temp.renameTo(final)) {
            c.temp.delete()
            result.failed += c.label to "could not be saved"
            return
        }
        // The other formats of this name go, so the one just picked is the one that plays.
        for (ext in VoiceLines.EXTS) if (ext != c.ext) File(folder, c.name + ext).delete()
        result.imported += c.name
        if (had) result.replaced += c.name
    }

    private fun copyBounded(input: InputStream, dst: File): Long {
        var total = 0L
        dst.outputStream().use { out ->
            val buf = ByteArray(65536)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                total += n
                if (total > VoiceLines.MAX_CLIP_BYTES) throw TooBig()
                out.write(buf, 0, n)
            }
            out.fd.sync()
        }
        return total
    }

    /** What an archiver or a Mac leaves behind, never a clip. */
    private fun debris(parts: List<String>): Boolean = parts.any { it == "__MACOSX" } || parts.last().startsWith(".")

    private fun displayName(context: Context, uri: Uri): String? = try {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    } catch (e: Exception) {
        Log.w(TAG, "no name for $uri: $e")
        null
    }

    private fun why(e: Exception): String = when (e) {
        is java.io.FileNotFoundException -> "the file was not there"
        is SecurityException -> "the phone would not let it be read"
        else -> e.message ?: e.javaClass.simpleName
    }

    /** Remove every clip she imported. Returns how many files went; the built-in voice stays. */
    fun clear(context: Context): Int = dir(context).listFiles().orEmpty().count { it.isFile && it.delete() }

    private fun few(items: List<String>, most: Int = 4): String =
        if (items.size <= most) items.joinToString(", ") else items.take(most).joinToString(", ") + " and ${items.size - most} more"

    /** One line: whose voice is in, and what the phone still says. */
    fun statusText(context: Context): String {
        val hers = imported(context).size
        val have = have(context)
        val built = have.size - hers
        val who = when {
            have.isEmpty() -> "No recorded voice."
            hers == 0 -> "The built-in voice: $built clip${if (built == 1) "" else "s"}."
            built == 0 -> "Your voice: $hers clip${if (hers == 1) "" else "s"}."
            else -> "Your voice: $hers clip${if (hers == 1) "" else "s"}; built-in for $built more."
        }
        val phone = VoiceLines.FIXED.filter { it !in have && it != "roundabout" }
        return who + if (phone.isNotEmpty()) " Phone voice for: ${few(phone)}." else ""
    }

    /** One line for what an import just did. */
    fun summary(r: Result): String {
        val parts = ArrayList<String>()
        val n = r.imported.size
        parts += if (n > 0) {
            "Imported $n clip${if (n == 1) "" else "s"}" + (if (r.replaced.isNotEmpty()) " (${r.replaced.size} replaced)" else "") + "."
        } else {
            "Nothing imported."
        }
        for ((name, files) in r.duplicates) parts += "Two files for $name; used ${files.first()}."
        if (r.refused.isNotEmpty()) parts += "Not imported: " + few(r.refused.map { "${it.first} (${it.second})" }) + "."
        if (r.failed.isNotEmpty()) parts += "Could not read: " + few(r.failed.map { "${it.first} (${it.second})" }) + "."
        if (r.ignored.isNotEmpty()) parts += "Left out, not a voice-line name: " + few(r.ignored) + "."
        return parts.joinToString(" ")
    }
}
