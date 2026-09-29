package online.iamprettyfamous.navi.core.voice

import online.iamprettyfamous.navi.core.voice.VoiceLines.Item
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Scripted dice: each roll and pick in order, then 0 - a roll of 0 always passes its rate. */
private class Dice(vararg rolls: Double, picks: List<Int> = emptyList()) : Random() {
    private val rolls = ArrayDeque(rolls.toList())
    private val picks = ArrayDeque(picks)
    override fun nextBits(bitCount: Int): Int = error("unscripted")
    override fun nextDouble(): Double = rolls.removeFirstOrNull() ?: 0.0
    override fun nextInt(until: Int): Int = (picks.removeFirstOrNull() ?: 0).coerceAtMost(until - 1)
}

/** The rules agreed by minions 2, 3 and 5 (2026-09-28), so both apps behave alike. */
class CharacterTest {
    private val pack = setOf(
        "turn-left", "turn-right", "exit", "arrive", "offroute", "rerouting", "start", "speeding", "dist-0.1mile",
        "flourish-arrival-choppa", "flourish-arrival-stop-it", "flourish-angry-you-bastard", "flourish-angry-big-mistake",
        "flourish-urgent-do-it-now", "flourish-urgent-move", "flourish-scold-slow-down", "flourish-start-lets-go",
        "flourish-sorry-my-mistake", "flourish-ui-what", "interject-cough", "interject-cough-2",
    )
    private val maneuver = Any()

    @Test
    fun `her names - lenient spelling, known moods only, a slot for every wild line`() {
        assertEquals("flourish-arrival-choppa", VoiceLines.parse("flourish-arrival-choppa.mp3").first)
        assertEquals("flourish-arrival-choppa", VoiceLines.parse("Flourish_Arrival_Choppa.MP3").first)
        assertNull(VoiceLines.parse("flourish-happy-birthday.mp3").first, "an unknown mood is ignored")
        assertNull(VoiceLines.parse("flourish-arrival.mp3").first, "no slug")
        assertEquals("interject-cough", VoiceLines.parse("interject-cough.mp3").first)
        assertEquals("interject-cough-2", VoiceLines.parse("interject-cough-2.mp3").first)
        assertEquals("number-3", VoiceLines.parse("number-3.mp3").first)
        assertNull(VoiceLines.parse("number-03.mp3").first)
        assertEquals("turn-left", Character.wildSlot("wild-turn-left-go-left-if-you-want-to-live"))
        assertEquals("sharp-right", Character.wildSlot("wild-sharp-right-now"))
        assertEquals("dist-0.1mile", Character.wildSlot("wild-dist-0.1mile-close"))
        assertEquals("offroute", Character.wildSlot("wild-offroute-you-bastard"))
        assertNull(Character.wildSlot("wild-left-go"), "no such slot")
        assertNull(Character.wildSlot("wild-turn-left"), "no slug")
        assertNull(VoiceLines.parse("wild-sideways-x.mp3").first)
        assertNull(Character.mood("turn-left"))
        assertNull(VoiceLines.distanceFeet("flourish-urgent-move"), "never a distance")
    }

    @Test
    fun `the chain - hers, then a wild one never twice running, then the built-in`() {
        val have = setOf("turn-left", "wild-turn-left-a", "wild-turn-left-b")
        val c = Character(Dice(picks = listOf(0, 0, 0)))
        assertEquals("turn-left", c.voiceFor("turn-left", hers = setOf("turn-left"), have = have), "her own wins")
        val first = c.voiceFor("turn-left", emptySet(), have)
        val second = c.voiceFor("turn-left", emptySet(), have)
        assertTrue(first.startsWith("wild-") && second.startsWith("wild-"))
        assertNotEquals(first, second, "never the same twice running")
        assertEquals("turn-right", c.voiceFor("turn-right", emptySet(), have + "turn-right"), "no wild line: the built-in")
        c.on = false
        assertEquals("turn-left", c.voiceFor("turn-left", emptySet(), have), "off: the plain voice")
    }

    @Test
    fun `a slot with only wild lines is still hers, and the prompt plays one`() {
        val have = setOf("wild-turn-left-go-left-if-you-want-to-live")
        val c = Character(Dice())
        val slots = c.slots(have)
        assertTrue("turn-left" in slots)
        val items = VoiceLines.playlist("x", "turn-left", "now", null, "Turn left onto Grand", "Grand", true, slots) {
            c.voiceFor(it, emptySet(), have)
        }
        assertEquals(listOf(Item.Clip("wild-turn-left-go-left-if-you-want-to-live", "Turn left")), items)
        c.on = false
        assertFalse("turn-left" in c.slots(have))
    }

    @Test
    fun `no flourish on the now band - except the arrival's own now`() {
        val c = Character(Dice())
        assertNull(c.flourish("now", "turn-left", maneuver, emptyList(), pack))
        val arrive = Any()
        assertNull(c.flourish("far", "arrive", arrive, emptyList(), pack), "the arrive far and near prompts get none")
        assertNull(c.flourish("near", "arrive", arrive, emptyList(), pack))
        val line = c.flourish("now", "arrive", arrive, emptyList(), pack)
        assertNotNull(line)
        assertEquals("arrival", Character.mood(line.name))
        assertTrue(line.soft)
        assertNull(line.fallback, "a flourish is never said by the phone")
    }

    @Test
    fun `the mood - arrival, angry, scold, start, urgent - among the ones she has lines for`() {
        val c = Character(Dice())
        val all = listOf("start", "offroute", "speeding")
        assertEquals("angry", Character.mood(c.flourish("far", "turn-left", Any(), all, pack)!!.name))
        assertEquals("scold", Character.mood(c.flourish("far", "turn-left", Any(), listOf("start", "speeding"), pack)!!.name))
        assertEquals("start", Character.mood(c.flourish("far", "turn-left", Any(), listOf("start"), pack)!!.name))
        assertEquals("urgent", Character.mood(c.flourish("near", "turn-left", Any(), emptyList(), pack)!!.name))
        val noAngry = pack.filter { Character.mood(it) != "angry" }.toSet()
        assertEquals("scold", Character.mood(c.flourish("far", null, null, all, noAngry)!!.name), "an empty pool falls to the next mood")
        assertNull(c.flourish("far", null, null, emptyList(), pack), "nothing to say, nothing added")
    }

    @Test
    fun `each mood's rate decides, one named constant each`() {
        assertEquals(0.25, Character.RATE.getValue("urgent"))
        assertNull(Character(Dice(0.25)).flourish("far", "turn-left", maneuver, emptyList(), pack))
        assertNotNull(Character(Dice(0.24)).flourish("far", "turn-left", maneuver, emptyList(), pack))
        assertNotNull(Character(Dice(0.99)).flourish("now", "arrive", maneuver, emptyList(), pack), "arrival is every time")
        assertNull(Character(Dice(0.5)).flourish("far", null, null, listOf("offroute"), pack))
    }

    @Test
    fun `one per maneuver across its prompts, and never the same line twice running`() {
        val c = Character(Dice(picks = listOf(0, 0, 0)))
        val a = c.flourish("far", "turn-left", maneuver, emptyList(), pack)
        assertNotNull(a)
        assertNull(c.flourish("near", "turn-left", maneuver, emptyList(), pack), "that maneuver has had its line")
        val b = c.flourish("far", "turn-right", Any(), emptyList(), pack)
        assertNotNull(b)
        assertNotEquals(a.name, b.name)
    }

    @Test
    fun `off means none of it`() {
        val c = Character(Dice())
        c.on = false
        assertNull(c.flourish("now", "arrive", maneuver, emptyList(), pack))
        assertNull(c.sorry(pack))
        val turn = listOf(Item.Clip("dist-0.1mile", "In 500 feet,"), Item.Clip("turn-left", "Turn left"))
        assertEquals(turn, c.cough(turn, "near", 150.0, pack))
    }

    @Test
    fun `the cough goes in the join between the distance and the turn, rarely, never twice running`() {
        val turn = listOf(Item.Clip("dist-0.1mile", "In 500 feet,"), Item.Clip("turn-left", "Turn left"), Item.Speak("on the left"))
        val c = Character(Dice(0.0, 0.0, 0.0))
        val coughed = c.cough(turn, "near", 150.0, pack)
        assertEquals(4, coughed.size)
        assertEquals(turn[0], coughed[0])
        assertTrue(Character.isCough((coughed[1] as Item.Clip).name) && (coughed[1] as Item.Clip).soft)
        assertEquals(turn.drop(1), coughed.drop(2))
        assertEquals(turn, c.cough(turn, "near", 150.0, pack), "not in the next prompt")
        assertEquals(4, c.cough(turn, "far", 400.0, pack).size, "the one after may")
        assertEquals(turn, Character(Dice()).cough(turn, "now", 20.0, pack), "never on now")
        assertEquals(turn, Character(Dice()).cough(turn, "near", null, pack), "no distance, no join")
        assertEquals(turn, Character(Dice(Character.COUGH_RATE)).cough(turn, "near", 150.0, pack), "rare")
        val single = listOf(Item.Speak("In 500 ft, turn left"))
        assertEquals(single, Character(Dice()).cough(single, "near", 150.0, pack), "one piece has no join")
    }

    @Test
    fun `sorry follows the failure, and a tap line only with her tap switch`() {
        val c = Character(Dice())
        assertEquals("sorry", Character.mood(c.sorry(pack)!!.name))
        assertNull(c.tap(pack), "the tap switch is hers to turn on")
        c.taps = true
        assertEquals("ui", Character.mood(c.tap(pack)!!.name))
        assertNull(Character(Dice(Character.RATE.getValue("ui"))).also { it.taps = true }.tap(pack))
    }
}
