package online.iamprettyfamous.navi.ui

import androidx.compose.ui.graphics.Color

/**
 * Her eight palettes, eleven keys each, copied from the spec's theme table
 * (itself verbatim from apps/navi theme.py). Any widget is written against a
 * [Palette] and never against a named theme.
 *
 * `warn`, `bad` and `good` are states, never decoration; `road` is the route
 * line, kept apart from `accent` so a route stands off already-coloured tiles.
 */
data class Palette(
    val key: String,
    val name: String,
    val bg: Color,
    val surface: Color,
    val border: Color,
    val text: Color,
    val text2: Color,
    val text3: Color,
    val accent: Color,
    val warn: Color,
    val bad: Color,
    val good: Color,
    val road: Color,
)

private fun c(hex: Long) = Color(0xFF000000 or hex)

val PALETTES: List<Palette> = listOf(
    Palette("night", "Night", c(0x06080b), c(0x0e1319), c(0x1e2733), c(0xf2f6fb), c(0x9fb0c3),
        c(0x5f7183), c(0x39d0ff), c(0xffb020), c(0xff4d6d), c(0x3ddc97), c(0x39d0ff)),
    Palette("day", "Day", c(0xeef1f5), c(0xffffff), c(0xc8d2de), c(0x0b1016), c(0x41505f),
        c(0x6d7d8c), c(0x0068a8), c(0x8a5200), c(0xb3001f), c(0x00684a), c(0x0068a8)),
    Palette("sunlight", "Sunlight", c(0xffffff), c(0xffffff), c(0x000000), c(0x000000), c(0x1a1a1a),
        c(0x333333), c(0x0040a0), c(0x7a3d00), c(0xa00000), c(0x00502f), c(0x0040a0)),
    Palette("redshift", "Red night", c(0x000000), c(0x0a0000), c(0x3d0d0d), c(0xff3b30), c(0xb32a22),
        c(0x7a1d17), c(0xff5b4a), c(0xff8f6a), c(0xffd2c2), c(0xc2402f), c(0xff5b4a)),
    Palette("cluster", "Cluster", c(0x080604), c(0x141009), c(0x2e2413), c(0xffc857), c(0xc9973b),
        c(0x8a6a2c), c(0xffb020), c(0xffe08a), c(0xff6b3d), c(0xb8d84a), c(0xffb020)),
    Palette("cyberpunk", "Cyberpunk", c(0x0a0612), c(0x140b22), c(0x2e1b4a), c(0xf0e6ff), c(0xb9a6d6),
        c(0x7d6b99), c(0x00f0ff), c(0xff2bd6), c(0xff4d6d), c(0x5affc8), c(0x00f0ff)),
    Palette("matrix", "Matrix", c(0x000000), c(0x041008), c(0x0e3d1f), c(0x9dffbe), c(0x4fbf7a),
        c(0x2c7a4c), c(0x00ff66), c(0xd7ff4a), c(0xff6b4a), c(0x00ff66), c(0x00ff66)),
    Palette("pink", "Pink", c(0x120810), c(0x1e0f1a), c(0x40202f), c(0xffe8f0), c(0xd69fb6),
        c(0x96687e), c(0xff5e8a), c(0xffb020), c(0xff3b5c), c(0x5affc8), c(0xff5e8a)),
)

fun palette(key: String): Palette = PALETTES.firstOrNull { it.key == key } ?: PALETTES[0]
