package org.imdtv.iptvtv.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RefreshRateTest {
    private fun mode(id: Int, hz: Float, w: Int = 1920, h: Int = 1080) = RefreshRate.Mode(id, w, h, hz)
    private val modes = listOf(mode(1, 60f), mode(2, 50f), mode(3, 24f), mode(4, 30f), mode(5, 23.976f), mode(6, 24f, 3840, 2160))

    @Test fun filmPicksTheExactModeThenTheSlowest() {
        assertEquals(mode(5, 23.976f), RefreshRate.pick(modes, mode(1, 60f), 23.976f))
        assertEquals(mode(3, 24f), RefreshRate.pick(modes, mode(1, 60f), 24f))
    }

    @Test fun alreadySmoothKeepsTheCurrentMode() {
        assertNull(RefreshRate.pick(modes, mode(3, 24f), 23.976f))
        assertNull(RefreshRate.pick(modes, mode(1, 60f), 30f))
        assertNull(RefreshRate.pick(modes, mode(2, 50f), 25f))
    }

    @Test fun europeanSportPicks50() {
        assertEquals(mode(2, 50f), RefreshRate.pick(modes, mode(1, 60f), 25f))
        assertEquals(mode(2, 50f), RefreshRate.pick(modes, mode(1, 60f), 50f))
    }

    @Test fun onlyTheCurrentResolutionIsConsidered() {
        assertNull(RefreshRate.pick(listOf(mode(6, 24f, 3840, 2160)), mode(1, 60f), 24f))
    }

    @Test fun noFrameRateOrSillyRatesChangeNothing() {
        assertNull(RefreshRate.pick(modes, mode(1, 60f), null))
        assertNull(RefreshRate.pick(modes, mode(1, 60f), 0f))
        assertNull(RefreshRate.pick(modes, mode(1, 60f), 120f))
        assertNull(RefreshRate.pick(modes, null, 24f))
    }

    @Test fun matchesAllowsWholeMultiplesWithinTolerance() {
        assertTrue(RefreshRate.matches(24f, 23.976f))
        assertTrue(RefreshRate.matches(48f, 23.976f))
        assertTrue(RefreshRate.matches(60f, 29.97f))
        assertTrue(RefreshRate.matches(50f, 25f))
        assertFalse(RefreshRate.matches(60f, 25f)) // 2.4×: pulldown judder
        assertFalse(RefreshRate.matches(50f, 23.976f))
        assertFalse(RefreshRate.matches(24f, 30f)) // slower than the stream
    }
}