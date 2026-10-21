package online.iamprettyfamous.navi.core

import okio.FileSystem
import java.text.Normalizer
import java.util.regex.Pattern

actual val Disk: FileSystem = FileSystem.SYSTEM

actual fun nfkd(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFKD)

/**
 * Android's regex (ICU) refuses UNICODE_CHARACTER_CLASS outright - the whole
 * Places object failed to load there, and Where to crashed on its first
 * keystroke (emulator, 2026-09-28) - while the JVM's `\d` and `\s` are ASCII
 * without it. ICU's are Unicode already, so there the flag is simply not needed.
 */
private val UNICODE: Int = try {
    Pattern.compile("x", Pattern.UNICODE_CHARACTER_CLASS)
    Pattern.UNICODE_CHARACTER_CLASS or Pattern.UNICODE_CASE
} catch (e: IllegalArgumentException) {
    Pattern.UNICODE_CASE
}

actual fun pythonRegex(pattern: String, ignoreCase: Boolean, dotAll: Boolean): Regex {
    var flags = UNICODE
    if (ignoreCase) flags = flags or Pattern.CASE_INSENSITIVE
    if (dotAll) flags = flags or Pattern.DOTALL
    return Pattern.compile(pattern, flags).toRegex()
}
