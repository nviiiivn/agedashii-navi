package online.iamprettyfamous.navi.core

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
        val rounded = Decimal.exact(abs(x)).toScale(places).toDouble()
        return if (isNegative(x)) -rounded else rounded
    }

    /** `round(x, places)` for a value that may be `None`. */
    fun round(x: Double?, places: Int): Double? = x?.let { round(it, places) }

    /** `round(x)` with no places: a whole number, ties to even. */
    fun roundToLong(x: Double): Long = kotlin.math.round(x).toLong()

    /** `repr(x)`, which for a float is also `str(x)`. */
    fun repr(x: Double): String {
        if (x.isNaN()) return "nan"
        if (x.isInfinite()) return if (x > 0) "inf" else "-inf"
        if (x == 0.0) return if (isNegative(x)) "-0.0" else "0.0"
        val shortest = shortest(abs(x))
        val digits = shortest.digits
        // The decimal point sits `decpt` digits into `digits`: 0.d1d2... x 10^decpt.
        val decpt = digits.length - shortest.scale
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
        val text = Decimal.exact(abs(x)).toScale(places).plain()
        return if (isNegative(x)) "-$text" else text
    }

    private fun isNegative(x: Double): Boolean = x < 0.0 || (x == 0.0 && 1.0 / x < 0.0)

    /**
     * The shortest decimal that reads back as exactly [x]. The correctly
     * rounded p-digit decimal is the nearest one, so the first precision whose
     * rounding survives the trip back is the shortest, and the closest of that
     * length - which is the string Python prints.
     */
    private fun shortest(x: Double): Decimal {
        val exact = Decimal.exact(x)
        for (precision in 1..17) {
            val candidate = exact.toPrecision(precision)
            if (candidate.toDouble() == x) return candidate.stripTrailingZeros()
        }
        return exact.stripTrailingZeros()
    }

    /**
     * A non-negative decimal, `digits` x 10^-`scale` - the part of
     * java.math.BigDecimal this file used, in common Kotlin so the iPhone
     * build writes the same numbers. Every finite double is exactly
     * m x 2^e, which is m x 2^e (e >= 0) or m x 5^-e / 10^-e (e < 0), so its
     * exact decimal digits come from multiplying m by small numbers - no
     * division, and no general big-number library.
     */
    internal class Decimal(val digits: String, val scale: Int) {
        fun toDouble(): Double = "${digits}E${-scale}".toDouble()

        /** Rounded half-even to `places` digits after the point (BigDecimal.setScale). */
        fun toScale(places: Int): Decimal =
            if (scale <= places) Decimal(digits + "0".repeat(places - scale), places)
            else Decimal(roundOff(scale - places), places)

        /** Rounded half-even to `precision` significant digits (BigDecimal.round). */
        fun toPrecision(precision: Int): Decimal {
            val drop = digits.length - precision
            return if (drop <= 0) this else Decimal(roundOff(drop), scale - drop)
        }

        fun stripTrailingZeros(): Decimal {
            if (digits.all { it == '0' }) return Decimal("0", 0)
            val kept = digits.trimEnd('0')
            return Decimal(kept, scale - (digits.length - kept.length))
        }

        /** BigDecimal.toPlainString for a scale of zero or more. */
        fun plain(): String {
            if (scale <= 0) return digits + "0".repeat(-scale)
            val padded = digits.padStart(scale + 1, '0')
            val point = padded.length - scale
            return padded.substring(0, point) + "." + padded.substring(point)
        }

        /** These digits with the last `drop` removed, rounding half-even. */
        private fun roundOff(drop: Int): String {
            val keep = digits.length - drop
            val kept = if (keep > 0) digits.substring(0, keep) else "0"
            val first = if (keep >= 0) digits[keep] else '0'
            val rest = if (keep >= 0) digits.substring(keep + 1) else digits
            val up = when {
                first > '5' -> true
                first < '5' -> false
                rest.any { it != '0' } -> true
                else -> (kept.last() - '0') % 2 == 1
            }
            return if (up) increment(kept) else kept
        }

        companion object {
            private const val BASE = 1_000_000_000L

            /** The exact value of a finite, non-negative double. */
            fun exact(x: Double): Decimal {
                val bits = x.toRawBits()
                val field = ((bits ushr 52) and 0x7ff).toInt()
                val fraction = bits and ((1L shl 52) - 1)
                val m = if (field == 0) fraction else fraction or (1L shl 52)
                val e = if (field == 0) -1074 else field - 1075
                if (m == 0L) return Decimal("0", 0)
                val limbs = mutableListOf(m % BASE, m / BASE % BASE, m / BASE / BASE).let { l ->
                    while (l.size > 1 && l.last() == 0L) l.removeAt(l.size - 1)
                    l
                }
                return if (e >= 0) {
                    repeat(e / 30) { multiply(limbs, 1L shl 30) }
                    multiply(limbs, 1L shl (e % 30))
                    Decimal(text(limbs), 0)
                } else {
                    repeat(-e / 12) { multiply(limbs, 244_140_625L) }      // 5^12
                    var rest = 1L
                    repeat(-e % 12) { rest *= 5 }
                    multiply(limbs, rest)
                    Decimal(text(limbs), -e)
                }
            }

            private fun multiply(limbs: MutableList<Long>, by: Long) {
                var carry = 0L
                for (i in limbs.indices) {
                    val v = limbs[i] * by + carry
                    limbs[i] = v % BASE
                    carry = v / BASE
                }
                while (carry > 0) {
                    limbs.add(carry % BASE)
                    carry /= BASE
                }
            }

            private fun text(limbs: List<Long>): String {
                val out = StringBuilder(limbs.last().toString())
                for (i in limbs.size - 2 downTo 0) out.append(limbs[i].toString().padStart(9, '0'))
                return out.toString()
            }

            private fun increment(digits: String): String {
                val c = digits.toCharArray()
                var i = c.size - 1
                while (i >= 0) {
                    if (c[i] == '9') {
                        c[i] = '0'
                        i--
                    } else {
                        c[i] = c[i] + 1
                        return c.concatToString()
                    }
                }
                return "1" + c.concatToString()
            }
        }
    }
}
