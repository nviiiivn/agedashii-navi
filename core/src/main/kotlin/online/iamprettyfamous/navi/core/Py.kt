package online.iamprettyfamous.navi.core

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import kotlin.math.abs

/**
 * Python's arithmetic where it differs from Kotlin's.
 *
 * Every ride log, CSV export and spoken distance so far was written by Python.
 * The port has to produce the same text for the same number, or a log written by
 * one cannot be compared with the other, and a tie like "in 425 ft" rounds to a
 * different fifty feet. Three things differ:
 *
 * - `round(x, n)` rounds the exact binary value, ties to even, and keeps the
 *   sign of a negative number that rounds to zero. Kotlin has no such call.
 * - `repr(x)` prints the shortest digits that read back as x, and only switches
 *   to an exponent below 1e-4 or from 1e16. Kotlin's `toString` writes
 *   1700000000.0 as "1.7E9", which is every unix timestamp in a ride log.
 * - `f"{x:.Nf}"` rounds ties to even; `String.format` rounds them up.
 */
object Py {
    /** `round(x, places)`. */
    fun round(x: Double, places: Int): Double {
        if (!x.isFinite()) return x
        val rounded = BigDecimal(x).setScale(places, RoundingMode.HALF_EVEN).toDouble()
        return if (rounded == 0.0 && isNegative(x)) -0.0 else rounded
    }

    /** `round(x, places)` for a value that may be `None`. */
    fun round(x: Double?, places: Int): Double? = x?.let { round(it, places) }

    /** `round(x)` with no places: a whole number, ties to even. */
    fun roundToLong(x: Double): Long = Math.rint(x).toLong()

    /** `repr(x)`, which for a float is also `str(x)`. */
    fun repr(x: Double): String {
        if (x.isNaN()) return "nan"
        if (x.isInfinite()) return if (x > 0) "inf" else "-inf"
        if (x == 0.0) return if (isNegative(x)) "-0.0" else "0.0"
        val shortest = shortest(abs(x))
        val digits = shortest.unscaledValue().toString()
        // The decimal point sits `decpt` digits into `digits`: 0.d1d2... x 10^decpt.
        val decpt = digits.length - shortest.scale()
        val body = if (decpt > 16 || decpt < -3) {
            val mantissa = if (digits.length > 1) digits[0] + "." + digits.substring(1) else digits
            val exponent = decpt - 1
            mantissa + "e" + (if (exponent < 0) "-" else "+") + abs(exponent).toString().padStart(2, '0')
        } else if (decpt <= 0) {
            "0." + "0".repeat(-decpt) + digits
        } else if (decpt >= digits.length) {
            digits + "0".repeat(decpt - digits.length) + ".0"
        } else {
            digits.substring(0, decpt) + "." + digits.substring(decpt)
        }
        return if (x < 0) "-$body" else body
    }

    /** `str(v)` for anything a ride row holds. */
    fun str(v: Any?): String = when (v) {
        null -> "None"
        is Double -> repr(v)
        is Float -> repr(v.toDouble())
        is Boolean -> if (v) "True" else "False"
        else -> v.toString()
    }

    /** `f"{x:.{places}f}"`. */
    fun fixed(x: Double, places: Int): String {
        if (x.isNaN()) return "nan"
        if (x.isInfinite()) return if (x > 0) "inf" else "-inf"
        val text = BigDecimal(x).setScale(places, RoundingMode.HALF_EVEN).toPlainString()
        return if (isNegative(x) && !text.startsWith("-")) "-$text" else text
    }

    private fun isNegative(x: Double): Boolean = x < 0.0 || (x == 0.0 && 1.0 / x < 0.0)

    /**
     * The shortest decimal that reads back as exactly [x]. The correctly
     * rounded p-digit decimal is the nearest one, so the first precision whose
     * rounding survives the trip back is the shortest, and the closest of that
     * length - which is the string Python prints.
     */
    private fun shortest(x: Double): BigDecimal {
        val exact = BigDecimal(x)
        for (precision in 1..17) {
            val candidate = exact.round(MathContext(precision, RoundingMode.HALF_EVEN))
            if (candidate.toDouble() == x) return candidate.stripTrailingZeros()
        }
        return exact.stripTrailingZeros()
    }
}
