package online.iamprettyfamous.navi.prefs

import android.content.Context
import android.content.SharedPreferences

/**
 * Every preference in one place, each with the spec's default (§12), so no
 * screen hard-codes one. The keys are the spec's own; they persist across
 * restarts and app updates (SharedPreferences, file "navi").
 *
 * Units default to IMPERIAL and are not a courtesy: miles and feet are how
 * she reads the road. Metric exists for completeness.
 */
class Prefs(context: Context) {
    val raw: SharedPreferences = context.getSharedPreferences("navi", Context.MODE_PRIVATE)

    var theme: String
        get() = raw.getString("theme", "night") ?: "night"
        set(v) = put("theme", v)

    /** The marker colour, "yellow" or "black" - NOT which motorcycle. */
    var bike: String
        get() = raw.getString("bike", "yellow") ?: "yellow"
        set(v) = put("bike", v)

    /** Which motorcycle: cb750, ex250, other. */
    var machine: String
        get() = raw.getString("machine", "cb750") ?: "cb750"
        set(v) = put("machine", v)

    var imperial: Boolean
        get() = raw.getString("units", "imperial") != "metric"
        set(v) = put("units", if (v) "imperial" else "metric")

    var buildings: Boolean
        get() = raw.getBoolean("buildings", false)
        set(v) = raw.edit().putBoolean("buildings", v).apply()

    var northUp: Boolean
        get() = raw.getBoolean("north_up", false)
        set(v) = raw.edit().putBoolean("north_up", v).apply()

    /** Spoken turns. On by default, as in the Flet app: the instruction she hears costs no glance. */
    var voice: Boolean
        get() = raw.getBoolean("voice", true)
        set(v) = raw.edit().putBoolean("voice", v).apply()

    /**
     * The street name after her own recorded line. Off by default: a robot saying
     * "Broadway" after "GO LEFT IF YOU WANT TO LIVE" ruins the joke (voicelines.py).
     */
    var voiceStreets: Boolean
        get() = raw.getBoolean("voice_streets", false)
        set(v) = raw.edit().putBoolean("voice_streets", v).apply()

    /** "Character lines": wild lines, flourishes and the cough. On by default; off is the plain recorded voice. */
    var voiceCharacter: Boolean
        get() = raw.getBoolean("voice_character", true)
        set(v) = raw.edit().putBoolean("voice_character", v).apply()

    /** A line, rarely, when she opens Where to or picks a place. On: she asked for it ("WANT 2"). */
    var voiceTaps: Boolean
        get() = raw.getBoolean("voice_taps", true)
        set(v) = raw.edit().putBoolean("voice_taps", v).apply()

    /** Which way up: auto (follow the phone), landscape (always sideways), portrait (always upright). */
    var orientation: String
        get() = raw.getString("orientation", "auto") ?: "auto"
        set(v) = put("orientation", v)

    /** Metres from the line before "off route" (with three fixes in a row). The Flet app's choices. */
    var offrouteM: Int
        get() = raw.getInt("offroute_m", 45).coerceIn(30, 200)
        set(v) = raw.edit().putInt("offroute_m", v).apply()

    /**
     * Follow the sun: Sunlight by day, Day in the golden hour, Night at dusk, Red
     * night after dark, the screen dimmed to match - never stored as her theme.
     * On by default, as in the Flet app (AGEDASHII's ruling): picking a theme by
     * hand turns it off, so she is never kept in a theme she did not choose.
     */
    var autoTheme: Boolean
        get() = raw.getBoolean("auto_theme", true)
        set(v) = raw.edit().putBoolean("auto_theme", v).apply()

    /** Start a ride log as soon as the bike is rolling (the Flet app's switch). */
    var autoRecord: Boolean
        get() = raw.getBoolean("autorecord", false)
        set(v) = raw.edit().putBoolean("autorecord", v).apply()

    var keepAwake: Boolean
        get() = raw.getBoolean("keep_awake", true)
        set(v) = raw.edit().putBoolean("keep_awake", v).apply()

    /** Her master switch for the motion sensors. Off holds while recording too. */
    var sensors: Boolean
        get() = raw.getBoolean("sensors", true)
        set(v) = raw.edit().putBoolean("sensors", v).apply()

    var sensorsOnDemand: Boolean
        get() = raw.getBoolean("sensors_on_demand", true)
        set(v) = raw.edit().putBoolean("sensors_on_demand", v).apply()

    /** GPS fixes, and so log rows, per second: 1, 2, 5 or 10. */
    var hz: Int
        get() = raw.getInt("hz", 1).coerceIn(1, 10)
        set(v) = raw.edit().putInt("hz", v).apply()

    /** The ONLY on/off for the gauges (spec §12 `dash`). Off: no gauges and no gauge sensors. */
    var dash: Boolean
        get() = raw.getBoolean("dash", true)
        set(v) = raw.edit().putBoolean("dash", v).apply()

    /** The HUD layout key (the dash's Layout). */
    var hud: String?
        get() = raw.getString("hud", null)
        set(v) = put("hud", v)

    /** Landscape gauge side: right, left. */
    var hudSide: String
        get() = raw.getString("hud_side", "right") ?: "right"
        set(v) = put("hud_side", v)

    /** Portrait gauge edge: bottom, top. */
    var hudEdge: String
        get() = raw.getString("hud_edge", "bottom") ?: "bottom"
        set(v) = put("hud_edge", v)

    private fun put(key: String, v: String?) = raw.edit().putString(key, v).apply()

    companion object {
        val HZ_CHOICES = listOf(1, 2, 5, 10)
        val OFFROUTE_CHOICES = listOf(30, 45, 75, 120)
    }
}
