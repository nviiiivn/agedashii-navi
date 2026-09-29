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
 *   The arrive slot's wild lines play on ARRIVING only: "In 1000 ft, MISSION
 *   ACCOMPLISHED" says she is there when she is not (AGEDASHII's ruling).
 *   A DIRECTION slot (turn, slight, sharp, uturn) takes only wild lines SHE
 *   imported: she heard it and named the direction herself - "GO LEFT IF YOU
 *   WANT TO LIVE", the line that started all this. Never one from the
 *   built-in pool: that would be us guessing left from right.
 * - FLOURISHES, `flourish-<mood>-<slug>`: up to TWO per prompt, always after
 *   the whole instruction, its tail, the street and any situation line.
 *   URGENT lines ("DO IT NOW") only on "near" and "now", never "far": half a
 *   mile out they could put her into a junction early. On "now", only urgent
 *   or the arrival's own line, and only one. ANGRY at most once a minute: a
 *   wrong turn is no time to be shouted at five times.
 * - THE COUGH, `interject-cough*`: rarely, in a join INSIDE the instruction -
 *   between distance and maneuver, or in the middle of the maneuver when it
 *   comes in two halves (`part-<slot>-a` / `-b`) - never on "now", never in
 *   two prompts running.
 *
 * ARRANGEMENT (hers: "everything needs to be significantly CREATIVE"), agreed
 * with minion 3 so both apps sound alike: on far and near the maneuver may
 * come before the distance ("turn left, in 200 feet"); no far/near prompt has
 * the same shape (order, where the cough is, how many flourishes) as the one
 * before it. The distance, the direction and its tail always come through
 * whole, and "now" is only the maneuver, its tail and one flourish at most.
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

    /** Her switch for a line when she taps Where to or picks a place. On: she asked for it by name. */
    @Volatile var taps = true

    private var lastFlourish: String? = null
    private val lastWild = HashMap<String, String>()
    private var lastCough: String? = null
    private var coughedLast = false
    private val given = ArrayDeque<Any>()
    private var angryAt: Double? = null
    private var lastShape: Triple<Boolean, Int, Int>? = null

    /** Every slot she has a sound for: her names, plus the slots that only have wild lines. */
    fun slots(have: Set<String>, hers: Set<String> = emptySet()): Set<String> =
        if (!on) have else have + have.filter { usable(it, hers) }.mapNotNull { wildSlot(it) }

    /** A wild line may stand in for its slot: always, but for a direction only if she imported it. */
    fun usable(name: String, hers: Set<String>): Boolean {
        val slot = wildSlot(name) ?: return false
        return slot !in DIRECTIONS || name in hers
    }

    /**
     * The file to play for [slot]: her imported one, else a random wild one
     * (never the same twice running for that slot), else the built-in one.
     * [hers] are the names she imported; [have] is every playable name. With
     * a [band], the arrive slot is the plain line short of "now".
     */
    fun voiceFor(slot: String, hers: Set<String>, have: Set<String>, band: String? = null): String {
        if (slot in hers || !on) return slot
        if (slot == ManeuverKind.ARRIVE && band != null && band != "now") return slot
        val wild = have.filter { wildSlot(it) == slot && usable(it, hers) }.sorted()
        if (wild.isEmpty()) return slot
        // One wild line alone takes turns with the built-in one, so it is still
        // never the same twice running (minion 3's Flet rule).
        val pool = if (wild.size == 1 && slot in have) wild + slot else wild
        return fresh(pool, lastWild[slot]).also { lastWild[slot] = it }
    }

    /**
     * The flourish for one prompt, or null. [maneuver] is the maneuver the
     * prompt announces (null for situation lines alone): each gets one at most
     * across its far, near and now prompts. [cues] are the situation lines on
     * the same fix. The mood is the first that applies, by [PRIORITY], among
     * those she has lines for; then its [RATE] decides. [now] is in seconds,
     * for the angry lines' [ANGRY_EVERY_S].
     */
    fun flourish(band: String?, kind: String?, maneuver: Any?, cues: List<String>, have: Set<String>, now: Double = 0.0): Item.Clip? {
        if (!on) return null
        if (maneuver != null && maneuver in given) return null
        // The arrival's far and near prompts get none: its line is for being there.
        if (kind == ManeuverKind.ARRIVE && band != "now") return null
        val moods = ArrayList<String>()
        val angryOk = angryAt.let { it == null || now - it >= ANGRY_EVERY_S }
        if (band == "now") {
            if (maneuver != null) moods += if (kind == ManeuverKind.ARRIVE) "arrival" else "urgent"
        } else {
            if (angryOk && ("offroute" in cues || "rerouting" in cues)) moods += "angry"
            if ("speeding" in cues) moods += "scold"
            if ("start" in cues) moods += "start"
            if (maneuver != null && band == "near") moods += "urgent"
        }
        val mood = moods.firstOrNull { m -> have.any { mood(it) == m } } ?: return null
        val line = line(mood, have) ?: return null
        if (maneuver != null) {
            given.addLast(maneuver)
            if (given.size > REMEMBER) given.removeFirst()
        }
        return line
    }

    /**
     * A flourish has started playing (Voice). The angry minute counts from a
     * line she HEARD, not one a newer prompt replaced before it played.
     */
    fun heard(name: String, now: Double) {
        if (mood(name) == "angry") angryAt = now
    }

    /** After the words saying what failed and what to do - never instead of them. */
    fun sorry(have: Set<String>): Item.Clip? = if (on) line("sorry", have) else null

    /** A tap outside the ride screen (opening search, picking a place), with her tap switch on. */
    fun tap(have: Set<String>): Item.Clip? = if (on && taps) line("ui", have) else null

    /** A prompt arranged: the instruction's pieces in order, then the flourishes. */
    data class Arranged(val turn: List<Item>, val after: List<Item.Clip>)

    /**
     * Arrange one prompt. [core] is its instruction in pieces (null: [whole],
     * our own sentence, played as it is). The rolls, in this order: the order
     * (maneuver first, [P_MANEUVER_FIRST], far/near with a distance), the
     * cough ([COUGH_RATE], inside the maneuver's halves if usable, else the
     * distance join), the first flourish, the second ([P_SECOND]). Then a
     * far/near prompt shaped like the one before it has its order flipped.
     */
    fun arrange(
        core: VoiceLines.Core?,
        whole: List<Item>,
        band: String?,
        kind: String?,
        maneuver: Any?,
        cues: List<String>,
        have: Set<String>,
        hers: Set<String>,
        now: Double = 0.0,
        flourishes: Boolean = true,
    ): Arranged {
        val wide = band == "far" || band == "near"
        val shaped = on && wide && core != null
        var first = shaped && core!!.distance != null && random.nextDouble() < P_MANEUVER_FIRST
        val halves = if (shaped) halves(core!!, have, hers) else null
        val where = if (!shaped) NONE else when {
            halves != null -> INSIDE
            core!!.distance != null -> JOIN
            else -> NONE
        }
        // Never two prompts running: any prompt without a cough - "now", our
        // own sentence - clears the way for the next (minion 3's Flet rule).
        val blocked = coughedLast
        val cough = if (where != NONE && !blocked) cough(have) else null
        coughedLast = cough != null
        val coughAt = if (cough != null) where else NONE
        val before = lastFlourish
        // A "now" that carries "and then" stays short: no flourish on it.
        val f1 = if (flourishes) flourish(band, kind, maneuver, cues, have, now) else null
        val f2 = if (f1 != null && wide) second(f1, before, band, kind, maneuver, cues, have, now) else null
        val after = listOfNotNull(f1, f2)
        // The shape counts only far/near prompts with a distance to arrange.
        if (shaped && core!!.distance != null) {
            var shape = Triple(first, coughAt, after.size)
            if (shape == lastShape) {
                first = !first
                shape = Triple(first, coughAt, after.size)
            }
            lastShape = shape
        }
        if (core == null) return Arranged(whole, after)
        if (!shaped) return Arranged(core.classic(), after)
        val verb = (core.maneuver as? Item.Clip)?.fallback
        val m = if (coughAt == INSIDE && halves != null) {
            // Either half that will not play is replaced by the WHOLE words:
            // a lost "left" would lose the direction.
            listOf(Item.Clip(halves.first, verb), cough!!, Item.Clip(halves.second, verb))
        } else {
            listOf(core.maneuver)
        }
        val group = m + core.glued
        val d = listOfNotNull(core.distance)
        val join = if (coughAt == JOIN) listOf(cough!!) else emptyList()
        return Arranged(if (first) group + join + d else d + join + group, after)
    }

    /**
     * The maneuver's two halves, when it is the slot's own clip (not a wild
     * line) and both halves exist from the SAME source as it: hers with hers,
     * built-in with built-in - never her half and Arnold's.
     */
    private fun halves(core: VoiceLines.Core, have: Set<String>, hers: Set<String>): Pair<String, String>? {
        val slot = core.slot ?: return null
        if ((core.maneuver as? Item.Clip)?.name != slot) return null
        val a = "part-$slot-a"
        val b = "part-$slot-b"
        if (a !in have || b !in have) return null
        val mine = slot in hers
        return if ((a in hers) == mine && (b in hers) == mine) a to b else null
    }

    /** The cough, rarely. */
    private fun cough(have: Set<String>): Item.Clip? {
        val pool = have.filter(::isCough).sorted()
        if (pool.isEmpty() || random.nextDouble() >= COUGH_RATE) return null
        val pick = fresh(pool, lastCough)
        lastCough = pick
        return Item.Clip(pick, null, soft = true)
    }

    /**
     * A second flourish after [first], [P_SECOND] of the time: a mood picked
     * evenly among those this moment allows (angry after off route or on a
     * near turn - under the minute - scold when speeding, start at a start,
     * urgent on near) that still has a line other than [first], then a line -
     * never [first], and not [before] (the flourish heard before it) when
     * there is a choice.
     */
    private fun second(first: Item.Clip, before: String?, band: String?, kind: String?, maneuver: Any?, cues: List<String>, have: Set<String>, now: Double): Item.Clip? {
        if (kind == ManeuverKind.ARRIVE || random.nextDouble() >= P_SECOND) return null
        val angryOk = angryAt.let { it == null || now - it >= ANGRY_EVERY_S }
        val moods = LinkedHashSet<String>()
        if (angryOk && ("offroute" in cues || "rerouting" in cues)) moods += "angry"
        if ("speeding" in cues) moods += "scold"
        if ("start" in cues) moods += "start"
        if (maneuver != null && band == "near") {
            moods += "urgent"
            if (angryOk) moods += "angry"
        }
        val open = moods.filter { m -> have.any { mood(it) == m && it != first.name } }
        if (open.isEmpty()) return null
        val mood = open[random.nextInt(open.size)]
        val pick = fresh(have.filter { mood(it) == mood && it != first.name }.sorted(), before)
        lastFlourish = pick
        return Item.Clip(pick, null, soft = true)
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

        /** The slots that say which way: a wild line for one must be hers. */
        val DIRECTIONS = setOf(
            ManeuverKind.TURN_LEFT, ManeuverKind.TURN_RIGHT, ManeuverKind.SLIGHT_LEFT, ManeuverKind.SLIGHT_RIGHT,
            ManeuverKind.SHARP_LEFT, ManeuverKind.SHARP_RIGHT, ManeuverKind.UTURN, "exit-left", "exit-right",
        )

        /** Seconds between two angry lines, as for speeding: one per minute at most. */
        const val ANGRY_EVERY_S = 60.0

        /** Rare: a cough is funny once. */
        const val COUGH_RATE = 0.08

        /** How often a far or near prompt puts the maneuver before the distance. */
        const val P_MANEUVER_FIRST = 0.3

        /** How often a first flourish gets a second. */
        const val P_SECOND = 0.35

        /** Where the cough went: nowhere, between distance and maneuver, inside the maneuver. */
        const val NONE = 0
        const val JOIN = 1
        const val INSIDE = 2

        /** Maneuvers remembered as having had their flourish. */
        private const val REMEMBER = 32

        private val SLUG = Regex("""[a-z0-9-]+""")
        private val FLOURISH = Regex("""flourish-(${MOODS.joinToString("|")})-[a-z0-9-]+""")
        private val COUGH = Regex("""interject-cough(?:-[a-z0-9-]+)?""")
        private val NUMBER = Regex("""(?:number|hours|seconds)-(?:0|[1-9][0-9]*)""")
        private val PART = Regex("""part-(.+)-([ab])""")

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

        /** The slot a half belongs to: part-continue-a -> continue; null for anything else. */
        fun partSlot(name: String): String? = PART.matchEntire(name)?.groupValues?.get(1)?.takeIf { VoiceLines.isSlot(it) }

        /** Any name of hers that is part of the character rather than a slot. */
        fun isName(name: String): Boolean =
            mood(name) != null || isCough(name) || isNumber(name) || wildSlot(name) != null || partSlot(name) != null
    }
}
