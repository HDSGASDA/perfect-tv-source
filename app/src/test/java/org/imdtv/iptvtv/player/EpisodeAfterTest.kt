package org.imdtv.iptvtv.player

import org.imdtv.iptvtv.data.model.Episode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EpisodeAfterTest {
    private fun ep(id: String, season: Int, number: Int) = Episode(id, season, number, "t$id", null, null, null, "http://s/$id")
    private val eps = listOf(ep("s2e1", 2, 1), ep("s1e2", 1, 2), ep("s1e1", 1, 1))

    @Test fun nextInAiringOrderAcrossSeasons() {
        assertEquals(ep("s1e2", 1, 2), episodeAfter(eps, "s1e1"))
        assertEquals(ep("s2e1", 2, 1), episodeAfter(eps, "s1e2"))
    }

    @Test fun finaleHasNoNext() = assertNull(episodeAfter(eps, "s2e1"))

    @Test fun unknownOrEmptyHasNoNext() {
        assertNull(episodeAfter(eps, "gone"))
        assertNull(episodeAfter(emptyList(), "s1e1"))
    }
}