package online.iamprettyfamous.navi.core.voice

import online.iamprettyfamous.navi.core.guidance.Guidance
import online.iamprettyfamous.navi.core.guidance.ManeuverKind
import kotlin.math.abs
import kotlin.math.ln

/**
 * Her recorded lines: which clip says what, and what fills in where one is
 * missing. Hers: "GO LEFT IF YOU WANT TO LIVE". WHOLE LINES in her own words,
 * one per maneuver - so a clip replaces the maneuver outright, a distance clip
 * plays before it when the band calls for one, and street names are left off by
 * default, because a robot saying "Broadway" after her line would ruin the joke.
 *
 * The Flet app's voicelines.py and voice_import.py, with the file-name grammar
 * agreed with minion 3 (2026-09-28) so ONE set of her files works in both apps.
 * The maneuver comes from Status.kind (Valhalla's type), not from the words.
 *
 * THE RULE: a missing clip is replaced by the phone's voice for THAT ITEM ALONE.
 * Never silence; a half-filled set works and improves as she adds files. With
 * none of her clips applying, the prompt is our whole sentence, unchanged.
 *
 * Pure: no audio. [playlist] returns [Item]s and the app plays them.
 */
object VoiceLines {
    /** What the player plays, in the order one wins when a name has two files. */
    val EXTS = listOf(".mp3", ".wav", ".m4a", ".aac", ".ogg", ".opus", ".flac")

    /** A prompt is seconds long; 10 MB is a minute of CD-quality stereo WAV. */
    const val MAX_CLIP_BYTES = 10L * 1024 * 1024

    /** Entries read from one ZIP before it is given up on. */
    const val MAX_ZIP_ENTRIES = 1000

    /**
     * How far off a distance clip may be before the phone says the distance
     * instead, as a ratio either way, inclusive. "Two hundred" for a turn a mile
     * away is a wrong instruction, and a wrong distance at speed is worse than a
     * robot.
     */
    const val DISTANCE_BOUND = 1.5

    /**
     * What the phone says for a situation she has no clip for. `speeding` has
     * none on purpose: a robot nagging about speed is not what she asked for.
     */
    val SITUATIONS: Map<String, String?> = linkedMapOf(
        "offroute" to "Off route.", "rerouting" to "Re-routing.", "start" to "Starting the route.", "speeding" to null,
    )

    /** Every fixed name: the twelve maneuvers, then the situations. Distances are open-ended beside them. */
    val FIXED: List<String> = ManeuverKind.MANEUVERS + SITUATIONS.keys

    /** The two distances sent to her before the set was opened up; still accepted. */
    private val LEGACY_FEET = mapOf("dist-quarter" to 1320.0, "dist-half" to 2640.0)

    /**
     * minion 4's exact grammar for the Flet import (2026-09-28): no leading
     * zero, so each distance has one spelling; an optional dash before the unit,
     * dropped from the stored name (dist_5_mile is dist-5mile).
     */
    private val DISTANCE = Regex("""dist-((?:0|[1-9][0-9]*)(?:\.[0-9]+)?)-?(ft|mile|miles)""")

    const val FEET_PER_M = 3.280839895

    /** "Turn_Left", "turn left " -> "turn-left": case, spaces and underscores are forgiven. */
    fun normalise(stem: String): String = stem.trim().lowercase().replace(Regex("""[\s_]+"""), "-")

    /** (prompt name or null, lower-case extension) for a file name. */
    fun parse(filename: String): Pair<String?, String> {
        val dot = filename.lastIndexOf('.')
        if (dot <= 0) return null to ""
        val ext = filename.substring(dot).lowercase()
        if (ext !in EXTS) return null to ext
        val name = canonical(normalise(filename.substring(0, dot)))
        return (if (isName(name)) name else null) to ext
    }

    /** The stored spelling of a name: a distance loses the dash before its unit. */
    fun canonical(name: String): String =
        DISTANCE.matchEntire(name)?.let { "dist-${it.groupValues[1]}${it.groupValues[2]}" } ?: name

    fun isName(name: String): Boolean = name in FIXED || distanceFeet(name) != null

    /** dist-400ft -> 400, dist-0.5mile -> 2640, dist-quarter -> 1320; null for anything else or zero. */
    fun distanceFeet(name: String): Double? {
        LEGACY_FEET[name]?.let { return it }
        val m = DISTANCE.matchEntire(name) ?: return null
        val n = m.groupValues[1].toDoubleOrNull() ?: return null
        if (n <= 0.0) return null
        return if (m.groupValues[2] == "ft") n else n * 5280.0
    }

    /**
     * Her distance clip for [metres]: of those she HAS, the nearest by ratio,
     * within [DISTANCE_BOUND]; a tie goes to the smaller, so a turn is never
     * announced further away than it is. Null: say the distance instead.
     */
    fun nearestDistance(metres: Double?, have: Set<String>): String? {
        if (metres == null || metres <= 0.0) return null
        val feet = metres * FEET_PER_M
        // minion 3's exact key (voicelines.distance_clip): |ln ratio| rounded to
        // 9 decimals, so exactly x1.5 still counts and a true tie is a tie
        // despite floating point; then the shorter clip.
        fun nine(x: Double) = Math.round(x * 1e9)
        val limit = nine(ln(DISTANCE_BOUND))
        return have.mapNotNull { name -> distanceFeet(name)?.let { name to it } }
            .map { (name, clipFeet) -> Triple(name, clipFeet, nine(abs(ln(feet / clipFeet)))) }
            .filter { it.third <= limit }
            .minWithOrNull(compareBy<Triple<String, Double, Long>> { it.third }.thenBy { it.second })
            ?.first
    }

    /** One thing to play: her clip (with the words it stands for), or words for the phone. */
    sealed interface Item {
        data class Clip(val name: String, val fallback: String?) : Item
        data class Speak(val text: String) : Item
    }

    /** "400 ft" -> "400 feet": the distance spelled out for the ear. */
    fun spokenDistance(metres: Double?, imperial: Boolean): String {
        val text = Guidance.distanceText(metres, imperial)
        val (n, unit) = text.split(" ").let { it.first() to it.getOrElse(1) { "" } }
        val word = when (unit) {
            "ft" -> "feet"
            "mi" -> if (n == "1") "mile" else "miles"
            "m" -> "metres"
            "km" -> "kilometres"
            else -> unit
        }
        return "$n $word".trim()
    }

    /** "Turn right onto 13th Street." -> "Turn right": her line replaces the verb only. */
    fun bare(instruction: String?): String =
        (instruction?.takeIf { it.isNotBlank() } ?: "continue").split(" onto ").first().trim().trimEnd('.')

    /**
     * The items for one fired prompt. [say] is the whole sentence the Follower
     * made; [kind], [band] and [metres] are the same prompt as parts. [streets]
     * is her switch for saying the street after her line (off by default).
     */
    fun playlist(
        say: String,
        kind: String?,
        band: String?,
        metres: Double?,
        instruction: String?,
        street: String?,
        imperial: Boolean,
        have: Set<String>,
        streets: Boolean = false,
    ): List<Item> {
        val distance = if (band == "now") null else metres
        val man = kind?.takeIf { it in ManeuverKind.MANEUVERS }
        val manClip = man?.takeIf { it in have }
        val distClip = nearestDistance(distance, have)
        if (manClip == null && distClip == null) return listOf(Item.Speak(say))
        val items = ArrayList<Item>()
        if (distance != null && distance > 0.0) {
            val spoken = "In ${spokenDistance(distance, imperial)},"
            items += if (distClip != null) Item.Clip(distClip, spoken) else Item.Speak(spoken)
        }
        val verb = bare(instruction)
        items += if (manClip != null) Item.Clip(manClip, verb) else Item.Speak(verb)
        if (streets && !street.isNullOrBlank() && man != ManeuverKind.ARRIVE) items += Item.Speak("onto $street")
        return items
    }

    /** A situation: her clip, else the phone's words, else nothing (speeding). */
    fun situation(name: String, have: Set<String>): List<Item> {
        if (name in have) return listOf(Item.Clip(name, SITUATIONS[name]))
        return SITUATIONS[name]?.let { listOf(Item.Speak(it)) } ?: emptyList()
    }

    /** What the phone says in place of each maneuver clip, for the preview. */
    private val STAND_IN = mapOf(
        ManeuverKind.TURN_LEFT to "turn left", ManeuverKind.TURN_RIGHT to "turn right",
        ManeuverKind.SLIGHT_LEFT to "bear left", ManeuverKind.SLIGHT_RIGHT to "bear right",
        ManeuverKind.SHARP_LEFT to "sharp left", ManeuverKind.SHARP_RIGHT to "sharp right",
        ManeuverKind.UTURN to "make a U-turn", ManeuverKind.CONTINUE to "continue straight",
        ManeuverKind.MERGE to "merge", ManeuverKind.EXIT to "take the exit",
        ManeuverKind.ARRIVE to "arrive at your destination", ManeuverKind.ROUNDABOUT to "enter the roundabout",
    )

    /** The phone's words for [name] when she has no clip, or null (speeding). */
    fun standIn(name: String, imperial: Boolean = true): String? {
        STAND_IN[name]?.let { return it }
        distanceFeet(name)?.let { return "In ${spokenDistance(it / FEET_PER_M, imperial)}," }
        return SITUATIONS[name]
    }

    /** What the panel's play button plays: exactly what a ride would for that name. */
    fun preview(name: String, have: Set<String>, imperial: Boolean = true): List<Item> {
        val words = standIn(name, imperial)
        if (name in have) return listOf(Item.Clip(name, words))
        return words?.let { listOf(Item.Speak(it)) } ?: emptyList()
    }

    /** How a name will sound on a ride: hers, the phone's, or nothing. */
    enum class Source { YOURS, PHONE, SILENT }

    fun source(name: String, have: Set<String>): Source = when {
        name in have -> Source.YOURS
        standIn(name) != null -> Source.PHONE
        else -> Source.SILENT
    }

    /** Seconds between two "speeding" lines: one per climb over the limit, not a nag. */
    const val SPEEDING_EVERY_S = 60.0

    /**
     * Which situation lines a fix calls for - each on its EDGE, never per fix.
     * start: a route to a NEW destination is up (a re-route of the same one is
     * not); offroute: off-route just confirmed; rerouting: the first re-route
     * asked for in that spell; speeding: just gone over, at most once per
     * [SPEEDING_EVERY_S]. voicelines.py's Cues; [destination] is a token that
     * changes when she picks a destination, even the same place again.
     */
    class Cues {
        private var destination: Any? = null
        private var off = false
        private var rerouted = false
        private var fast = false
        private var fastAt: Double? = null

        fun update(ok: Boolean, offRoute: Boolean, destination: Any?, reroute: Boolean, over: Boolean, now: Double): List<String> {
            val names = ArrayList<String>()
            if (ok && destination != null && destination !== this.destination) {
                this.destination = destination
                names += "start"
            }
            if (offRoute && !off) names += "offroute"
            if (!offRoute) rerouted = false else if (reroute && !rerouted) {
                rerouted = true
                names += "rerouting"
            }
            off = offRoute
            val last = fastAt
            if (over && !fast && (last == null || now - last >= SPEEDING_EVERY_S)) {
                fastAt = now
                names += "speeding"
            }
            fast = over
            return names
        }
    }
}
