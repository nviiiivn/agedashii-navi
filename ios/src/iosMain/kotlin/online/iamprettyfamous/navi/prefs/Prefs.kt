package online.iamprettyfamous.navi.prefs

import platform.Foundation.NSUserDefaults

/**
 * Every preference in one place, each with the spec's default (§12) - the
 * Android app's Prefs, kept in NSUserDefaults under the same keys, so the two
 * phones' settings read alike. Only the settings this build has a feature for
 * are here; the rest arrive with their features.
 *
 * Units default to IMPERIAL and are not a courtesy: miles and feet are how
 * she reads the road.
 */
class Prefs {
    private val raw = NSUserDefaults.standardUserDefaults

    var theme: String
        get() = string("theme", "night")
        set(v) = put("theme", v)

    /** The marker colour, "yellow" or "black" - NOT which motorcycle. */
    var bike: String
        get() = string("bike", "yellow")
        set(v) = put("bike", v)

    /** Which motorcycle: cb750, ex250, other. */
    var machine: String
        get() = string("machine", "cb750")
        set(v) = put("machine", v)

    var imperial: Boolean
        get() = string("units", "imperial") != "metric"
        set(v) = put("units", if (v) "imperial" else "metric")

    var buildings: Boolean
        get() = bool("buildings", false)
        set(v) = putBool("buildings", v)

    var northUp: Boolean
        get() = bool("north_up", false)
        set(v) = putBool("north_up", v)

    /** Spoken turns. On by default: the instruction she hears costs no glance. */
    var voice: Boolean
        get() = bool("voice", true)
        set(v) = putBool("voice", v)

    /** The street name after her own recorded line. Off: a robot saying "Broadway" after her joke ruins it. */
    var voiceStreets: Boolean
        get() = bool("voice_streets", false)
        set(v) = putBool("voice_streets", v)

    /** "Character lines": wild lines, flourishes and the cough. */
    var voiceCharacter: Boolean
        get() = bool("voice_character", true)
        set(v) = putBool("voice_character", v)

    /** A line, rarely, when she opens Where to or picks a place. */
    var voiceTaps: Boolean
        get() = bool("voice_taps", true)
        set(v) = putBool("voice_taps", v)

    /** Metres from the line before "off route" (with three fixes in a row). */
    var offrouteM: Int
        get() = int("offroute_m", 45).coerceIn(30, 200)
        set(v) = putInt("offroute_m", v)

    /** Follow the sun: the theme and the brightness from where the sun is. On by default. */
    var autoTheme: Boolean
        get() = bool("auto_theme", true)
        set(v) = putBool("auto_theme", v)

    /** Start a ride log as soon as the bike is rolling. */
    var autoRecord: Boolean
        get() = bool("autorecord", false)
        set(v) = putBool("autorecord", v)

    var keepAwake: Boolean
        get() = bool("keep_awake", true)
        set(v) = putBool("keep_awake", v)

    /** The ONLY on/off for the gauges (spec §12 `dash`). */
    var dash: Boolean
        get() = bool("dash", true)
        set(v) = putBool("dash", v)

    /** The HUD layout key (the dash's Layout). */
    var hud: String?
        get() = raw.stringForKey("hud")
        set(v) = if (v == null) raw.removeObjectForKey("hud") else put("hud", v)

    /** Landscape gauge side: right, left. */
    var hudSide: String
        get() = string("hud_side", "right")
        set(v) = put("hud_side", v)

    /** Portrait gauge edge: bottom, top. */
    var hudEdge: String
        get() = string("hud_edge", "bottom")
        set(v) = put("hud_edge", v)

    private fun string(key: String, default: String): String = raw.stringForKey(key) ?: default

    // NSUserDefaults answers false and 0 for a key never set, so "never set"
    // is asked separately - otherwise every default-on switch would read off.
    private fun bool(key: String, default: Boolean): Boolean =
        if (raw.objectForKey(key) == null) default else raw.boolForKey(key)

    private fun int(key: String, default: Int): Int =
        if (raw.objectForKey(key) == null) default else raw.integerForKey(key).toInt()

    private fun put(key: String, v: String) = raw.setObject(v, forKey = key)
    private fun putBool(key: String, v: Boolean) = raw.setBool(v, forKey = key)
    private fun putInt(key: String, v: Int) = raw.setInteger(v.toLong(), forKey = key)

    companion object {
        val OFFROUTE_CHOICES = listOf(30, 45, 75, 120)
    }
}
