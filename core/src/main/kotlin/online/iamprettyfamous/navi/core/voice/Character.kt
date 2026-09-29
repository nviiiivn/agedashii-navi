package online.iamprettyfamous.navi.core.voice

import online.iamprettyfamous.navi.core.guidance.ManeuverKind
import online.iamprettyfamous.navi.core.voice.VoiceLines.Item
import kotlin.random.Random

/**
 * Her character, on top of the instruction and never in its place. Hers
 * (2026-09-28): "i want those peppered in randomly and accordingly", and "id
 * rather him scream things that make kind of sense than the normal waze arnold
 * talking". Three kinds of file, all in the same voice folders as the rest:
 *
 * - WILD LINES, `wild-<slot>-<slug>`: the voice for a slot. Per slot the chain
 *   is her imported `<slot>`, then a random wild one (hers and built-in in one
 *   pool), then the built-in `<slot>`, then the phone. A line that is vague
 *   about direction is never named for a turn slot: it is a flourish instead.
 * - FLOURISHES, `flourish-<mood>-<slug>`: at most one per prompt, always LAST,
 *   after the instruction, its tail, the street and any situation line. Never
 *   on the "now" band, except the arrival's own "now", since nothing follows it.
 * - THE COUGH, `interject-cough*`: rarely, in the one join between a distance
 *   and its turn, never on "now", never in two prompts running.
 *
 * A flourish and a cough are SOFT: any newer prompt cuts them at once (Voice),
 * so neither can be what is still playing when an instruction needs to speak.
 *
 * The names and rules were agreed by minions 2, 3 and 5 so both apps behave
 * alike; the picks differ (different random numbers), the rules do not.
 * Pure: [random] is injected so the tests can script it.
 */
class Character(private val random: Random = Random.Default) {
    /** Her "Character lines" switch. Off: no wild lines, flourishes or cough - the plain recorded voice. */
    @Volatile var on = true

    /** Her switch for a line when she taps something outside the ride screen. */
    @Volatile var taps = false

    private var lastFlourish: String? = null
    private val lastWild = HashMap<String, String>()
    private var lastCough: String? = null
    private var coughedLast = false
    private val given = ArrayDeque<Any>()

    /** Every slot she has a sound for: her names, plus the slots that only have wild lines. */
    fun slots(have: Set<String>): Set<String> =
        if (!on) have else have + have.mapNotNull { wildSlot(it) }

    /**
     * The file to play for [slot]: her imported one, else a random wild one
     * (never the same twice running for that slot), else the built-in one.
     * [hers] are the names she imported; [have] is every playable name.
     */
    fun voiceFor(slot: String, hers: Set<String>, have: Set<String>): String {
        if (slot in hers || !on) return slot
        val pool = have.filter { wildSlot(it) == slot }.sorted()
        if (pool.isEmpty()) return slot
        return fresh(pool, lastWild[slot]).also { lastWild[slot] = it }
    }

    /**
     * The flourish for one prompt, or null. [maneuver] is the maneuver the
     * prompt announces (null for situation lines alone): each gets one at most
     * across its far, near and now prompts. [cues] are the situation lines on
     * the same fix. The mood is the first that applies, by [PRIORITY], among
     * those she has lines for; then its [RATE] decides.
     */
    fun flourish(band: String?, kind: String?, maneuver: Any?, cues: List<String>, have: Set<String>): Item.Clip? {
        if (!on) return null
        if (maneuver != null && maneuver in given) return null
        // The arrival's far and near prompts get none: its line is for being there.
        if (kind == ManeuverKind.ARRIVE && band != "now") return null
        val moods = ArrayList<String>()
        if (band == "now") {
            if (maneuver != null && kind == ManeuverKind.ARRIVE) moods += "arrival"
        } else {
            if ("offroute" in cues || "rerouting" in cues) moods += "angry"
            if ("speeding" in cues) moods += "scold"
            if ("start" in cues) moods += "start"
            if (maneuver != null) moods += "urgent"
        }
        val mood = moods.firstOrNull { m -> have.any { mood(it) == m } } ?: return null
        val line = line(mood, have) ?: return null
        if (maneuver != null) {
            given.addLast(maneuver)
            if (given.size > REMEMBER) given.removeFirst()
        }
        return line
    }

    /** After the words saying what failed and what to do - never instead of them. */
    fun sorry(have: Set<String>): Item.Clip? = if (on) line("sorry", have) else null

    /** A tap outside the ride screen (opening search, picking a place), with her tap switch on. */
    fun tap(have: Set<String>): Item.Clip? = if (on && taps) line("ui", have) else null

    /**
     * [turn] with a cough in its one join - between the distance and the
     * maneuver - rarely. Only a far or near prompt that has a distance and more
     * than one piece has that join; the one after a cough never coughs.
     */
    fun cough(turn: List<Item>, band: String?, metres: Double?, have: Set<String>): List<Item> {
        if (!on || band == null || band == "now" || metres == null || metres <= 0.0 || turn.size < 2) return turn
        val pool = have.filter(::isCough).sorted()
        if (pool.isEmpty()) return turn
        if (coughedLast) {
            coughedLast = false
            return turn
        }
        if (random.nextDouble() >= COUGH_RATE) return turn
        val pick = fresh(pool, lastCough)
        lastCough = pick
        coughedLast = true
        return listOf(turn[0], Item.Clip(pick, null, soft = true)) + turn.drop(1)
    }

    private fun line(mood: String, have: Set<String>): Item.Clip? {
        val pool = have.filter { mood(it) == mood }.sorted()
        if (pool.isEmpty() || random.nextDouble() >= RATE.getValue(mood)) return null
        val pick = fresh(pool, lastFlourish)
        lastFlourish = pick
        return Item.Clip(pick, null, soft = true)
    }

    /** A uniform pick that is not [last], unless it is the only one. */
    private fun fresh(pool: List<String>, last: String?): String {
        val from = if (pool.size > 1) pool.filter { it != last } else pool
        return from[random.nextInt(from.size)]
    }

    companion object {
        /** Every mood a flourish can have; a name with any other is not hers to play. */
        val MOODS = listOf("start", "arrival", "angry", "urgent", "scold", "sorry", "ui")

        /** How often each mood's moment gets a line: one edit each. */
        val RATE = mapOf(
            "urgent" to 0.25, "arrival" to 1.0, "start" to 1.0, "angry" to 0.5, "scold" to 0.5,
            "sorry" to 1.0, "ui" to 0.15,
        )

        /** Which mood a prompt gets when several apply. */
        val PRIORITY = listOf("arrival", "angry", "scold", "start", "urgent")

        /** Rare: a cough is funny once. */
        const val COUGH_RATE = 0.08

        /** Maneuvers remembered as having had their flourish. */
        private const val REMEMBER = 32

        private val SLUG = Regex("""[a-z0-9-]+""")
        private val FLOURISH = Regex("""flourish-(${MOODS.joinToString("|")})-[a-z0-9-]+""")
        private val COUGH = Regex("""interject-cough(?:-[a-z0-9-]+)?""")
        private val NUMBER = Regex("""(?:number|hours|seconds)-(?:0|[1-9][0-9]*)""")

        fun mood(name: String): String? = FLOURISH.matchEntire(name)?.groupValues?.get(1)

        fun isCough(name: String): Boolean = COUGH.matches(name)

        /** number-3, hours-2, seconds-10: recognised and kept, not played until a rule says where. */
        fun isNumber(name: String): Boolean = NUMBER.matches(name)

        /** The slot a wild line stands in for: the longest known slot after "wild-" with a slug after it. */
        fun wildSlot(name: String): String? {
            if (!name.startsWith("wild-")) return null
            val rest = name.removePrefix("wild-")
            var best: String? = null
            var i = rest.indexOf('-')
            while (i > 0) {
                val slot = rest.substring(0, i)
                val slug = rest.substring(i + 1)
                if (SLUG.matches(slug) && VoiceLines.isSlot(slot)) best = slot
                i = rest.indexOf('-', i + 1)
            }
            return best
        }

        /** Any name of hers that is part of the character rather than a slot. */
        fun isName(name: String): Boolean = mood(name) != null || isCough(name) || isNumber(name) || wildSlot(name) != null
    }
}
