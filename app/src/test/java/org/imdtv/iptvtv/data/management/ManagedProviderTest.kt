package org.imdtv.iptvtv.data.management

import org.imdtv.iptvtv.data.db.ProviderType
import org.junit.Assert.*
import org.junit.Test

class ManagedProviderTest {
    private val expected = "https://protect.securemy.network:443"

    @Test fun acceptsOnlyConfiguredXtreamOrigin() {
        assertTrue(ManagedProvider.accepts(ProviderType.XTREAM, expected))
        assertTrue(ManagedProvider.accepts(ProviderType.XTREAM, "https://protect.securemy.network/"))
        assertFalse(ManagedProvider.accepts(ProviderType.M3U, expected))
        assertFalse(ManagedProvider.accepts(ProviderType.STALKER, expected))
        assertFalse(ManagedProvider.accepts(ProviderType.XTREAM, "http://protect.securemy.network"))
        assertFalse(ManagedProvider.accepts(ProviderType.XTREAM, "https://protect.securemy.network:8443"))
        assertFalse(ManagedProvider.accepts(ProviderType.XTREAM, "$expected/other"))
        assertFalse(ManagedProvider.accepts(ProviderType.XTREAM, "$expected?other=1"))
        assertFalse(ManagedProvider.accepts(ProviderType.XTREAM, "https://elsewhere.example"))
        assertFalse(ManagedProvider.accepts(ProviderType.XTREAM, ""))
    }
}
