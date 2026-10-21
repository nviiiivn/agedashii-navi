package online.iamprettyfamous.navi.core

import okio.FileSystem
import platform.Foundation.NSString
import platform.Foundation.decomposedStringWithCompatibilityMapping

actual val Disk: FileSystem = FileSystem.SYSTEM

@Suppress("CAST_NEVER_SUCCEEDS")
actual fun nfkd(text: String): String = (text as NSString).decomposedStringWithCompatibilityMapping

/**
 * Kotlin/Native's `\d` and `\s` are ASCII and it has no flag to widen them,
 * so they are widened here: `\d` to every decimal digit, `\s` to Unicode's
 * spaces as well (a pasted pair often carries a no-break space). Only the
 * escapes Places' patterns use, and `\s` only outside brackets, which is the
 * only place they use it.
 */
actual fun pythonRegex(pattern: String, ignoreCase: Boolean, dotAll: Boolean): Regex {
    val wide = pattern
        .replace("\\d", "\\p{Nd}")
        .replace("\\s", "[\\s\\u00A0\\u1680\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000]")
    return Regex(wide, buildSet {
        if (ignoreCase) add(RegexOption.IGNORE_CASE)
        if (dotAll) add(RegexOption.DOT_MATCHES_ALL)
    })
}
