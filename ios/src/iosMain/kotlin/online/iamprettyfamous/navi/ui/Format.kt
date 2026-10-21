package online.iamprettyfamous.navi.ui

import online.iamprettyfamous.navi.core.Py
import online.iamprettyfamous.navi.core.grouped

/**
 * `String.format`, which Kotlin has only on the JVM. The screens shared with
 * the Android app call it (RouteBands' "%.5f, %.5f"), so the iPhone build
 * answers the same call - for the conversions this app writes and nothing
 * more: %d %s %f with an optional flag (`,` or `0`), width and precision, and
 * %%. Anything else is written out as it is, never thrown on.
 *
 * Fixed-point uses Py.fixed, which rounds as Python does - the Android build
 * rounds ties up and this ties to even, a difference only an exact .5 at the
 * last printed digit could show.
 */
fun String.format(vararg args: Any?): String = printf(this, args)

internal fun printf(pattern: String, args: Array<out Any?>): String {
    val out = StringBuilder()
    var next = 0
    var i = 0
    while (i < pattern.length) {
        val c = pattern[i]
        if (c != '%' || i + 1 >= pattern.length) {
            out.append(c); i++; continue
        }
        if (pattern[i + 1] == '%') {
            out.append('%'); i += 2; continue
        }
        var j = i + 1
        var comma = false
        var zero = false
        while (j < pattern.length && pattern[j] in ",0-") {
            if (pattern[j] == ',') comma = true
            if (pattern[j] == '0') zero = true
            j++
        }
        val widthStart = j
        while (j < pattern.length && pattern[j].isDigit()) j++
        val width = pattern.substring(widthStart, j).toIntOrNull() ?: 0
        var precision: Int? = null
        if (j < pattern.length && pattern[j] == '.') {
            j++
            val ps = j
            while (j < pattern.length && pattern[j].isDigit()) j++
            precision = pattern.substring(ps, j).toIntOrNull() ?: 0
        }
        if (j >= pattern.length) {
            out.append(pattern.substring(i)); break
        }
        val conv = pattern[j]
        val arg = args.getOrNull(next)
        val body: String? = when (conv) {
            'd' -> (arg as? Number)?.toLong()?.let { if (comma) grouped(it) else it.toString() }
            'f' -> (arg as? Number)?.toDouble()?.let { x ->
                val text = Py.fixed(x, precision ?: 6)
                if (!comma) text else {
                    val neg = text.startsWith("-")
                    val bare = text.removePrefix("-")
                    val whole = bare.substringBefore('.')
                    val frac = bare.substringAfter('.', "")
                    (if (neg) "-" else "") + grouped(whole.toLong()) + (if (frac.isEmpty()) "" else ".$frac")
                }
            }
            's' -> arg.toString()
            else -> null
        }
        if (body == null) {
            out.append(pattern, i, j + 1)
        } else {
            next++
            val pad = if (zero && conv != 's') '0' else ' '
            if (body.length < width) {
                if (pad == '0' && body.startsWith("-")) out.append('-').append("0".repeat(width - body.length)).append(body, 1, body.length)
                else out.append(pad.toString().repeat(width - body.length)).append(body)
            } else {
                out.append(body)
            }
        }
        i = j + 1
    }
    return out.toString()
}
