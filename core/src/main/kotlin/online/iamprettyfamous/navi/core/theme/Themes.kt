package online.iamprettyfamous.navi.core.theme

import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * The eleven roles every palette fills, so any widget can be written against a
 * role without knowing which theme is on.
 *
 * BG, SURFACE and BORDER are the chrome. TEXT, TEXT2 and TEXT3 are three weights
 * of foreground, faintest last. ACCENT is the one interactive colour. WARN, BAD
 * and GOOD are STATES and never decoration - the accent never doubles as an
 * alarm, or off-route stops being findable by colour alone. ROAD is the colour a
 * ROUTE is drawn in, kept apart from ACCENT because a route has to stand off
 * tiles that are already coloured; it is not the colour of the street network.
 */
enum class Role { BG, SURFACE, BORDER, TEXT, TEXT2, TEXT3, ACCENT, WARN, BAD, GOOD, ROAD }

/** One palette, as 0xRRGGBB. */
data class Palette(
    val key: String,
    val name: String,
    val bg: Int,
    val surface: Int,
    val border: Int,
    val text: Int,
    val text2: Int,
    val text3: Int,
    val accent: Int,
    val warn: Int,
    val bad: Int,
    val good: Int,
    val road: Int,
) {
    operator fun get(role: Role): Int = when (role) {
        Role.BG -> bg
        Role.SURFACE -> surface
        Role.BORDER -> border
        Role.TEXT -> text
        Role.TEXT2 -> text2
        Role.TEXT3 -> text3
        Role.ACCENT -> accent
        Role.WARN -> warn
        Role.BAD -> bad
        Role.GOOD -> good
        Role.ROAD -> road
    }
}

/** ui.py: four type sizes and nothing between them, and the glove target. */
object TypeScale {
    /** Minimum tappable edge. Bigger than Material's 48: she rides in gloves. */
    const val TOUCH_DP = 56

    /** Current speed - read most often. */
    const val HUGE_SP = 64

    /** Turn instruction, speed limit, ETA. */
    const val BIG_SP = 28

    /** Gauge values, list rows. */
    const val MID_SP = 16

    /** Unit labels, captions. */
    const val SMALL_SP = 12
}

/**
 * Her eight palettes, verbatim from apps/navi/agedashii_navi/theme.py, which is
 * authoritative over the spec's copy. ThemesTest proves every value against the
 * module itself.
 *
 * Every one answers one question: can you read this, at a glance, in a helmet,
 * in the sun, at 60 mph? Two greys, not five: depth cues cost contrast and
 * contrast is the whole budget. No glow, no gradient behind text.
 */
object Themes {
    private fun p(key: String, name: String, vararg c: Int) =
        Palette(key, name, c[0], c[1], c[2], c[3], c[4], c[5], c[6], c[7], c[8], c[9], c[10])

    /** In theme.py's declaration order. */
    val ALL: Map<String, Palette> = linkedMapOf(
        // The default, and the one most rides happen in. Near-black: a true black
        // makes the map's own dark tiles look like a hole punched in the chrome.
        "night" to p("night", "Night", 0x06080b, 0x0e1319, 0x1e2733, 0xf2f6fb, 0x9fb0c3, 0x5f7183, 0x39d0ff, 0xffb020, 0xff4d6d, 0x3ddc97, 0x39d0ff),
        // Overcast daylight. Light but not white: white behind white tiles loses the map's edge.
        "day" to p("day", "Day", 0xeef1f5, 0xffffff, 0xc8d2de, 0x0b1016, 0x41505f, 0x6d7d8c, 0x0068a8, 0x8a5200, 0xb3001f, 0x00684a, 0x0068a8),
        // Direct sun, visor down. Maximum contrast, no mid-tones. Ugly indoors on purpose.
        "sunlight" to p("sunlight", "Sunlight", 0xffffff, 0xffffff, 0x000000, 0x000000, 0x1a1a1a, 0x333333, 0x0040a0, 0x7a3d00, 0xa00000, 0x00502f, 0x0040a0),
        // Night vision. Rods are nearly blind to deep red, so red on black reads
        // without spending twenty minutes of dark adaptation. Every colour is a
        // red, GOOD included - a single green chip would undo it.
        "redshift" to p("redshift", "Red night", 0x000000, 0x0a0000, 0x3d0d0d, 0xff3b30, 0xb32a22, 0x7a1d17, 0xff5b4a, 0xff8f6a, 0xffd2c2, 0xc2402f, 0xff5b4a),
        // Magenta and cyan on near-black violet: route and warning at opposite ends of the spectrum.
        "cyberpunk" to p("cyberpunk", "Cyberpunk", 0x0a0612, 0x140b22, 0x2e1b4a, 0xf0e6ff, 0xb9a6d6, 0x7d6b99, 0x00f0ff, 0xff2bd6, 0xff4d6d, 0x5affc8, 0x00f0ff),
        // One hue and black behind it: nothing for the eye to re-focus on.
        "matrix" to p("matrix", "Matrix", 0x000000, 0x041008, 0x0e3d1f, 0x9dffbe, 0x4fbf7a, 0x2c7a4c, 0x00ff66, 0xd7ff4a, 0xff6b4a, 0x00ff66, 0x00ff66),
        // The warm half of the brand gradient. Pink leads, orange warns.
        "pink" to p("pink", "Pink", 0x120810, 0x1e0f1a, 0x40202f, 0xffe8f0, 0xd69fb6, 0x96687e, 0xff5e8a, 0xffb020, 0xff3b5c, 0x5affc8, 0xff5e8a),
        // An instrument cluster at night: amber on near-black, no blue at all.
        "cluster" to p("cluster", "Cluster", 0x080604, 0x141009, 0x2e2413, 0xffc857, 0xc9973b, 0x8a6a2c, 0xffb020, 0xffe08a, 0xff6b3d, 0xb8d84a, 0xffb020),
    )

    /**
     * The dashboard's own look, for the surfaces that are NOT the map when she
     * has "alola chrome" on: Where to, Around, Rides, Settings are read at a stop,
     * where matching the family is worth more than maximum contrast.
     */
    val ALOLA: Palette = p("alola", "Alola", 0x0d1117, 0x161b22, 0x30363d, 0xe6edf3, 0x9198a1, 0x6e7681, 0x39d0ff, 0xffb020, 0xff5e8a, 0x3fb950, 0x39d0ff)

    /** The order a picker shows them in: theme.py THEME_NAMES. */
    val PICKER_ORDER: List<String> = listOf("night", "day", "sunlight", "cluster", "redshift", "cyberpunk", "matrix", "pink")

    /** Named rather than computed from the background, which would break on the first theme starting with a letter. */
    val DARK: Set<String> = setOf("night", "cluster", "redshift", "cyberpunk", "matrix", "pink")

    /** Which map rendering suits each theme: a dark theme under bright tiles is the worst of both. */
    val TILES: Map<String, String> = linkedMapOf(
        "night" to "dark", "day" to "streets", "sunlight" to "streets", "cluster" to "dark",
        "redshift" to "dark", "cyberpunk" to "dark", "matrix" to "dark", "pink" to "dark",
    )

    const val DEFAULT = "night"

    /** Her two marker colours. Not themed: a marker that changed colour is one she cannot find by colour. */
    val BIKE_COLOURS: Map<String, Int> = linkedMapOf("yellow" to 0xffd93b, "black" to 0x101418)

    /** Around the marker body, so a black bike shows on dark tiles and a yellow one on light. */
    val BIKE_OUTLINE: Map<String, Int> = linkedMapOf("yellow" to 0x1a1200, "black" to 0xf2f4f8)

    /** The identity pill's orange, pink and cyan - the only gradient allowed, never behind a number. */
    val BRAND: List<Int> = listOf(0xffb020, 0xff5e8a, 0x39d0ff)

    /** A palette by key; an unknown or absent one is Night, never an exception. */
    operator fun get(key: String?): Palette = ALL[key] ?: ALL.getValue(DEFAULT)

    fun isDark(key: String?): Boolean = (key ?: DEFAULT) in DARK

    /** WCAG contrast ratio between two 0xRRGGBB colours, 1..21. */
    fun contrast(a: Int, b: Int): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
    }

    private fun luminance(c: Int): Double {
        fun channel(v: Int): Double {
            val s = v / 255.0
            // 0.04045 is the sRGB spec's threshold (WCAG 2.0 printed 0.03928); no
            // 8-bit channel falls between the two, so the ratios are identical.
            return if (s <= 0.04045) s / 12.92 else ((s + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * channel(c shr 16 and 0xff) + 0.7152 * channel(c shr 8 and 0xff) + 0.0722 * channel(c and 0xff)
    }
}
