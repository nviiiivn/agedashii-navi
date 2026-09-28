package online.iamprettyfamous.navi.core.voice

import online.iamprettyfamous.navi.core.voice.VoiceLines.Item
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Against the names in her real Arnold pack (arnold-navi.zip, 19 clips), so a
 * rule that would refuse or misplay one of her files fails here first.
 */
class VoiceLinesTest {
    private val pack = listOf(
        "arrive.mp3", "continue.mp3", "dist-1500ft.mp3", "dist-200ft.mp3", "dist-400ft.mp3", "dist-800ft.mp3",
        "exit.mp3", "merge.mp3", "offroute.mp3", "rerouting.mp3", "sharp-left.mp3", "sharp-right.mp3",
        "slight-left.mp3", "slight-right.mp3", "speeding.mp3", "start.mp3", "turn-left.mp3", "turn-right.mp3", "uturn.mp3",
    )
    private val have: Set<String> = pack.mapNotNull { VoiceLines.parse(it).first }.toSet()

    private fun ft(feet: Double) = feet / VoiceLines.FEET_PER_M

    @Test
    fun `every file in her pack is taken - none refused`() {
        assertEquals(19, have.size, "refused: ${pack.filter { VoiceLines.parse(it).first == null }}")
    }

    @Test
    fun `names forgive case, spaces and underscores, and nothing else`() {
        assertEquals("turn-left" to ".mp3", VoiceLines.parse("Turn_Left.MP3"))
        assertEquals("slight-right" to ".wav", VoiceLines.parse("slight right.wav"))
        assertEquals("roundabout" to ".m4a", VoiceLines.parse("roundabout.m4a"))
        assertEquals("dist-0.5mile" to ".ogg", VoiceLines.parse("dist-0.5mile.ogg"))
        assertEquals("dist-2miles" to ".mp3", VoiceLines.parse("dist-2miles.mp3"))
        assertEquals("dist-quarter" to ".mp3", VoiceLines.parse("dist-quarter.mp3"))
        assertNull(VoiceLines.parse("turnleft.mp3").first, "not guessed at")
        assertNull(VoiceLines.parse("turn-left.txt").first)
        assertNull(VoiceLines.parse("dist-0ft.mp3").first)
        assertNull(VoiceLines.parse("dist-400.mp3").first, "a distance has a unit")
    }

    @Test
    fun `distances read in feet`() {
        assertEquals(400.0, VoiceLines.distanceFeet("dist-400ft"))
        assertEquals(2640.0, VoiceLines.distanceFeet("dist-0.5mile"))
        assertEquals(10560.0, VoiceLines.distanceFeet("dist-2miles"))
        assertEquals(1320.0, VoiceLines.distanceFeet("dist-quarter"))
        assertEquals(2640.0, VoiceLines.distanceFeet("dist-half"))
    }

    @Test
    fun `the nearest clip she HAS, within one and a half times, the smaller on a tie`() {
        assertEquals("dist-200ft", VoiceLines.nearestDistance(ft(200.0), have))
        assertEquals("dist-400ft", VoiceLines.nearestDistance(ft(300.0), have), "300 is nearer 400 than 200 by ratio")
        assertEquals("dist-200ft", VoiceLines.nearestDistance(ft(Math.sqrt(200.0 * 400.0)), have), "a tie goes to the smaller")
        assertEquals("dist-1500ft", VoiceLines.nearestDistance(ft(2250.0), have), "exactly x1.5 is inside")
        assertNull(VoiceLines.nearestDistance(ft(2260.0), have), "past x1.5: the phone says it")
        assertEquals("dist-200ft", VoiceLines.nearestDistance(ft(134.0), have))
        assertNull(VoiceLines.nearestDistance(ft(133.0), have))
        assertNull(VoiceLines.nearestDistance(ft(5280.0), have), "two hundred for a mile away is a wrong instruction")
        assertNull(VoiceLines.nearestDistance(null, have))
    }

    @Test
    fun `her line and her distance, then nothing of ours`() {
        val items = VoiceLines.playlist(
            "In 400 ft, Turn right onto 13th Street", "turn-right", "near", ft(400.0),
            "Turn right onto 13th Street.", "13th Street", imperial = true, have = have,
        )
        assertEquals(listOf(Item.Clip("dist-400ft", "In 400 feet,"), Item.Clip("turn-right", "Turn right")), items)
    }

    @Test
    fun `a missing item falls back for that item alone`() {
        val far = VoiceLines.playlist(
            "In 1.0 mi, Turn right", "turn-right", "far", 1609.344, "Turn right", "", true, have,
        )
        assertEquals(listOf(Item.Speak("In 1.0 miles,"), Item.Clip("turn-right", "Turn right")), far)
        val noRoundabout = VoiceLines.playlist(
            "In 400 ft, Enter the roundabout", "roundabout", "near", ft(400.0), "Enter the roundabout", "", true, have,
        )
        assertEquals(listOf(Item.Clip("dist-400ft", "In 400 feet,"), Item.Speak("Enter the roundabout")), noRoundabout)
    }

    @Test
    fun `none of hers applies - our whole sentence, unchanged`() {
        val say = "In 1.0 mi, Take the ferry"
        assertEquals(listOf(Item.Speak(say)), VoiceLines.playlist(say, null, "far", 1609.344, "Take the ferry", "", true, have))
        assertEquals(listOf(Item.Speak("x")), VoiceLines.playlist("x", "turn-left", "near", ft(400.0), "Turn left", "", true, emptySet()))
    }

    @Test
    fun `now is the maneuver alone`() {
        assertEquals(
            listOf(Item.Clip("turn-left", "Turn left")),
            VoiceLines.playlist("Turn left onto Grand", "turn-left", "now", ft(80.0), "Turn left onto Grand", "Grand", true, have),
        )
    }

    @Test
    fun `street names only when she turns them on, and never on arrival`() {
        val on = VoiceLines.playlist("x", "turn-left", "now", null, "Turn left onto Grand", "Grand Ave", true, have, streets = true)
        assertEquals(Item.Speak("onto Grand Ave"), on.last())
        val arrive = VoiceLines.playlist("x", "arrive", "now", null, "You have arrived", "Grand Ave", true, have, streets = true)
        assertTrue(arrive.none { it is Item.Speak && "onto" in it.text })
    }

    @Test
    fun `situations - hers, the phone's, or for speeding nothing`() {
        assertEquals(listOf(Item.Clip("offroute", "Off route.")), VoiceLines.situation("offroute", have))
        assertEquals(listOf(Item.Speak("Re-routing.")), VoiceLines.situation("rerouting", emptySet()))
        assertEquals(emptyList(), VoiceLines.situation("speeding", emptySet()), "a robot nagging about speed is not what she asked for")
        assertEquals(VoiceLines.Source.SILENT, VoiceLines.source("speeding", emptySet()))
        assertEquals(VoiceLines.Source.YOURS, VoiceLines.source("speeding", have))
        assertEquals(VoiceLines.Source.PHONE, VoiceLines.source("roundabout", have))
    }

    @Test
    fun `cues fire on their edges only`() {
        val cues = VoiceLines.Cues()
        val dest = Any()
        assertEquals(listOf("start"), cues.update(true, false, dest, false, false, 0.0))
        assertEquals(emptyList(), cues.update(true, false, dest, false, false, 1.0), "the same destination is no new start")
        assertEquals(listOf("offroute"), cues.update(true, true, dest, false, false, 2.0))
        assertEquals(listOf("rerouting"), cues.update(true, true, dest, true, false, 3.0))
        assertEquals(emptyList(), cues.update(true, true, dest, true, false, 15.0), "once per off-route spell")
        assertEquals(listOf("speeding"), cues.update(true, false, dest, false, true, 20.0))
        assertEquals(emptyList(), cues.update(true, false, dest, false, false, 21.0))
        assertEquals(emptyList(), cues.update(true, false, dest, false, true, 30.0), "not again inside a minute")
        assertEquals(listOf("start"), cues.update(true, false, Any(), false, true, 90.0).filter { it == "start" })
    }
}
