package kr.urbanmood.audio

import org.junit.Assert.*
import org.junit.Test

class PlaybackPoliciesTest {
    @Test fun nextAndCompletionAvoidRepeatingWithStaticQueue() {
        val p = QueuePolicy()
        var current: String? = null
        val sequence = (1..6).map { p.choose(listOf("a","b","c"),current)!!.also { p.played(it); current=it } }
        assertEquals(listOf("a","b","c","a","b","c"),sequence)
    }
    @Test fun emptyAndSingleTrackAreExplicit() {
        val p = QueuePolicy()
        assertNull(p.choose(emptyList(),null)); p.played("a")
        assertEquals("a",p.choose(listOf("a"),"a"))
    }
    @Test fun replacementQueueCannotChooseRemovedTrack() {
        val p = QueuePolicy(); p.played("a")
        assertEquals("b",p.choose(listOf("b","b"),"a"))
    }
    @Test fun transientLossResumesOnce() {
        val p = PlaybackIntent(); p.play(); p.loseFocus(true)
        assertFalse(p.wantsPlay); assertTrue(p.gainFocus()); assertTrue(p.wantsPlay); assertFalse(p.gainFocus())
    }
    @Test fun userPauseDuringFocusLossPreventsAutomaticResume() {
        val p = PlaybackIntent(); p.play(); p.loseFocus(true); p.pause()
        assertFalse(p.gainFocus()); assertFalse(p.wantsPlay)
    }
    @Test fun permanentLossAndPausedStateNeverAutoResume() {
        val p = PlaybackIntent(); p.play(); p.loseFocus(false)
        assertFalse(p.gainFocus()); p.pause(); p.loseFocus(true); assertFalse(p.gainFocus())
    }
    @Test fun repeatedTransientLossPreservesResumeIntent() {
        val p = PlaybackIntent(); p.play(); p.loseFocus(true); p.loseFocus(true); assertTrue(p.gainFocus())
    }
}
