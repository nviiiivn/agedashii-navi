package online.iamprettyfamous.navi.voice

import online.iamprettyfamous.navi.core.voice.VoiceLines
import online.iamprettyfamous.navi.data.DataFiles
import platform.Foundation.NSFileManager

/**
 * Her recorded voice on the iPhone. The Android app's VoiceClips, with the
 * same precedence: a clip she put on the phone wins, then the one built into
 * the app, then (by being absent here) the phone's own voice.
 *
 * THE PATH IN, on the iPhone: the Files app. The app's Documents folder is
 * visible there (UIFileSharingEnabled), so her clips go in "xnav.k/voice" by
 * drag, AirDrop or Save to Files - no picker of the app's own, and nothing to
 * import. File names are the prompts (VoiceLines.parse), as on Android.
 */
object VoiceClips {
    /** Where a clip comes from: one she put on the phone, or the one built into the app. */
    sealed interface Source {
        val path: String
        data class Imported(override val path: String) : Source
        data class BuiltIn(override val path: String) : Source
    }

    /** Documents/voice, which the Files app shows. */
    fun dir(): String = DataFiles.documents("voice")

    fun sources(): Map<String, Source> {
        val out = LinkedHashMap<String, Source>()
        for ((name, path) in imported()) out[name] = Source.Imported(path)
        for ((name, path) in builtIn()) if (name !in out) out[name] = Source.BuiltIn(path)
        return out
    }

    fun have(): Set<String> = sources().keys

    /** Name -> her file: the best-ranked format when there are two. */
    fun imported(): Map<String, String> = clipsIn(dir())

    private var builtInCache: Map<String, String>? = null

    /** Name -> path of the built-in clips; listed once, since the bundle does not change. */
    fun builtIn(): Map<String, String> = builtInCache ?: (DataFiles.folder("voice")?.let(::clipsIn) ?: emptyMap()).also { builtInCache = it }

    private fun clipsIn(folder: String): Map<String, String> {
        @Suppress("UNCHECKED_CAST")
        val names = (NSFileManager.defaultManager.contentsOfDirectoryAtPath(folder, error = null) as? List<String>).orEmpty()
            .filter { !it.startsWith(".") }
        val out = HashMap<String, String>()
        for (f in names.sortedBy(::rank)) {
            val name = VoiceLines.parse(f).first
            if (name != null && name !in out) out[name] = "$folder/$f"
        }
        return out
    }

    private fun rank(file: String): Int {
        val ext = "." + file.substringAfterLast('.', "").lowercase()
        return VoiceLines.EXTS.indexOf(ext).let { if (it < 0) Int.MAX_VALUE else it }
    }

    private fun few(items: List<String>, most: Int = 4): String =
        if (items.size <= most) items.joinToString(", ") else items.take(most).joinToString(", ") + " and ${items.size - most} more"

    /** One line: whose voice is in, and what the phone still says. The Android app's words. */
    fun statusText(): String {
        val all = have()
        val have = all.filter(VoiceLines::isSlot).toSet()
        val extra = all.size - have.size
        val hers = imported().keys.count(VoiceLines::isSlot)
        val built = have.size - hers
        val who = when {
            have.isEmpty() -> "No recorded voice."
            hers == 0 -> "The built-in voice: $built clip${if (built == 1) "" else "s"}."
            built == 0 -> "Your voice: $hers clip${if (hers == 1) "" else "s"}."
            else -> "Your voice: $hers clip${if (hers == 1) "" else "s"}; built-in for $built more."
        }
        val phone = VoiceLines.FIXED.filter { it !in have && it != "roundabout" && VoiceLines.standIn(it) != null }
        return who + (if (extra > 0) " Character: $extra line${if (extra == 1) "" else "s"}." else "") +
            if (phone.isNotEmpty()) " Phone voice for: ${few(phone)}." else ""
    }
}
