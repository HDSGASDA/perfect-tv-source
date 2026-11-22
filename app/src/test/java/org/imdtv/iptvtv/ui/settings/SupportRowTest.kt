package org.imdtv.iptvtv.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class SupportRowTest {
    @Test fun contactEmailIsTheCurrentSupportAddress() {
        assertEquals("admin@perfectiptv.co.uk", CONTACT_EMAIL)
    }
}
