package online.iamprettyfamous.navi.ui

import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraClearTest {
    private val d = 2.625f  // the emulators' density (420 dpi)
    private val column = ButtonColumn.CLEAR_DP * d

    private fun centre(w: Float, h: Float, pad: CameraClear.Pad) =
        (pad.left + (w - pad.right - pad.left) / 2) to (pad.top + (h - pad.bottom - pad.top) / 2)

    @Test
    fun `sideways, a two-line banner and the edge panel pushed low - the bike clears the panel`() {
        // Ride v8 at 05:44: the stack reached 60% down, the panel sat bottom right.
        val panel = Rect(1300f, 820f, 2380f, 1060f)
        val pad = CameraClear.of(2400f, 1080f, d, 650.0, listOf(panel), wide = true, columnPx = column)
        val (x, _) = centre(2400f, 1080f, pad)
        assertTrue("bike at x=$x, panel from ${panel.left}", x + 30 * d < panel.left)
        assertTrue("the button column is cleared too", pad.left >= column)
        assertEquals("sideways, the panel is cleared sideways", 0.0, pad.bottom, 0.0)
    }

    @Test
    fun `sideways, the compact box at the right is cleared to its left`() {
        val box = Rect(1780f, 640f, 2380f, 1060f)
        val pad = CameraClear.of(2400f, 1080f, d, 300.0, listOf(box), wide = true, columnPx = column)
        assertTrue(pad.right >= 2400 - box.left)
        assertEquals(300.0, pad.top, 0.0)
    }

    @Test
    fun `upright, the gauge row along the bottom lifts the bike above it, and the column is not cleared`() {
        val row = Rect(20f, 2150f, 1060f, 2380f)
        val pad = CameraClear.of(1080f, 2400f, d, 400.0, listOf(row), wide = false, columnPx = column)
        assertTrue(pad.bottom >= 2400 - row.top)
        assertEquals(0.0, pad.left, 0.0)
        assertEquals(0.0, pad.right, 0.0)
    }

    @Test
    fun `sideways, a full-width strip is cleared from the bottom, not the side`() {
        val strip = Rect(0f, 900f, 2400f, 1080f)
        val pad = CameraClear.of(2400f, 1080f, d, 300.0, listOf(strip), wide = true, columnPx = column)
        assertTrue(pad.bottom >= 180)
        assertEquals(0.0, pad.right, 0.0)
    }

    @Test
    fun `a readout in the middle of the map is left over the map`() {
        val gunsight = Rect(1000f, 400f, 1400f, 700f)
        val pad = CameraClear.of(2400f, 1080f, d, 0.0, listOf(gunsight), wide = true, columnPx = column)
        assertEquals(CameraClear.Pad(column.toDouble(), 0.0, 0.0, 0.0), pad)
    }

    @Test
    fun `a gauge's digits changing width does not move the camera`() {
        val a = CameraClear.of(2400f, 1080f, d, 300.0, listOf(Rect(1790f, 640f, 2380f, 1060f)), true, column)
        val b = CameraClear.of(2400f, 1080f, d, 300.0, listOf(Rect(1782f, 640f, 2380f, 1060f)), true, column)
        assertEquals(a, b)
    }

    @Test
    fun `the free map is never squeezed to nothing`() {
        val row = Rect(20f, 1500f, 1060f, 2380f)
        val pad = CameraClear.of(1080f, 2400f, d, 1400.0, listOf(row), wide = false, columnPx = column)
        assertTrue(2400 - pad.top - pad.bottom >= 120 * d - 0.5)
    }

    @Test
    fun `no dash, only the banner - as before`() {
        val pad = CameraClear.of(1080f, 2400f, d, 400.0, emptyList(), wide = false, columnPx = column)
        assertEquals(CameraClear.Pad(0.0, 400.0, 0.0, 0.0), pad)
    }
}
