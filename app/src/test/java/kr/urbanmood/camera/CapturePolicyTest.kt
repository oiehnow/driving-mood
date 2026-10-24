package kr.urbanmood.camera

import org.junit.Assert.*
import org.junit.Test

class CapturePolicyTest {
    @Test fun fullHdIsReducedWithoutDistortion() {
        assertEquals(640 to 360, CapturePolicy.scaledSize(1920, 1080))
        assertEquals(360 to 640, CapturePolicy.scaledSize(1080, 1920))
    }
    @Test fun smallAndExtremeFramesStayValid() {
        assertEquals(320 to 240, CapturePolicy.scaledSize(320, 240))
        assertEquals(640 to 1, CapturePolicy.scaledSize(8000, 1))
    }
    @Test fun stoppedAndRestartedSessionsRejectLateCallbacks() {
        val tokens = SessionTokens()
        val first = tokens.advance()
        assertTrue(tokens.isCurrent(first))
        tokens.advance() // stop while analyze is in flight
        assertFalse(tokens.isCurrent(first))
        val restarted = tokens.advance()
        assertFalse(tokens.isCurrent(first))
        assertTrue(tokens.isCurrent(restarted))
    }
    @Test(expected = IllegalArgumentException::class)
    fun invalidFrameIsRejected() { CapturePolicy.scaledSize(0, 200) }
}
