package org.imdtv.iptvtv

import org.junit.Assert.assertEquals
import org.junit.Test

class SmokeTest {
    @Test
    fun applicationIdIsCorrect() {
        assertEquals(if (BuildConfig.MANAGED) "org.imdtv.iptvtv.managed" else "org.imdtv.iptvtv", BuildConfig.APPLICATION_ID)
    }
}
