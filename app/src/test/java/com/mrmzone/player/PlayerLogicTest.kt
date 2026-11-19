package com.mrmzone.player

import com.mrmzone.player.player.PlayerLogic
import com.mrmzone.player.player.PlayerLogic.Repeat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerLogicTest {
    @Test fun nextIndex() {
        assertEquals(1, PlayerLogic.nextIndex(0, 3, Repeat.OFF, true))
        assertEquals(-1, PlayerLogic.nextIndex(0, 3, Repeat.OFF, false))
        assertEquals(-1, PlayerLogic.nextIndex(2, 3, Repeat.OFF, true))
        assertEquals(0, PlayerLogic.nextIndex(2, 3, Repeat.ALL, true))
        assertEquals(1, PlayerLogic.nextIndex(0, 3, Repeat.ALL, false))
        assertEquals(2, PlayerLogic.nextIndex(2, 3, Repeat.ONE, false))
        assertEquals(-1, PlayerLogic.nextIndex(0, 0, Repeat.ALL, true))
    }

    @Test fun resume() {
        assertFalse(PlayerLogic.shouldResume(2_000, 600_000))      // too early
        assertTrue(PlayerLogic.shouldResume(60_000, 600_000))
        assertFalse(PlayerLogic.shouldResume(598_000, 600_000))    // at the very end
        assertTrue(PlayerLogic.shouldResume(30_000, 0))            // unknown length (stream)
    }

    @Test fun volumeBoost() {
        assertEquals(0 to 100, PlayerLogic.volume(0, 15))
        assertEquals(15 to 100, PlayerLogic.volume(15, 15))
        assertEquals(15 to 200, PlayerLogic.volume(30, 15))
        assertEquals(15 to 200, PlayerLogic.volume(99, 15))
        assertEquals(100, PlayerLogic.volumePercent(15, 15))
        assertEquals(200, PlayerLogic.volumePercent(30, 15))
    }

    @Test fun seek() {
        assertEquals(45_000L, PlayerLogic.seekDelta(540f, 1080))
        assertEquals(-90_000L, PlayerLogic.seekDelta(-1080f, 1080))
        assertEquals(0L, PlayerLogic.clampSeek(-5_000, 100_000))
        assertEquals(99_500L, PlayerLogic.clampSeek(200_000, 100_000))
    }

    @Test fun aspectCycle() {
        var a = PlayerLogic.Aspect.FIT
        repeat(PlayerLogic.Aspect.entries.size) { a = PlayerLogic.nextAspect(a) }
        assertEquals(PlayerLogic.Aspect.FIT, a)
    }
}
