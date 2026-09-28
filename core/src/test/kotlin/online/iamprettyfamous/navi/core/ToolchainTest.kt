package online.iamprettyfamous.navi.core

import kotlin.test.Test
import kotlin.test.assertTrue

// Proves the :core test harness runs before any maths is ported into it.
// Delete once real tests exist.
class ToolchainTest {
    @Test
    fun runsOnJdk17OrNewer() {
        assertTrue(Runtime.version().feature() >= 17)
    }
}
