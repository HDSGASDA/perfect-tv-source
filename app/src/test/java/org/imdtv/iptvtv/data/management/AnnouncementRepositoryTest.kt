package org.imdtv.iptvtv.data.management

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AnnouncementRepositoryTest {
    @get:Rule val tmp = TemporaryFolder()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val prefs by lazy { PreferenceDataStoreFactory.create(scope = scope) { tmp.newFile("messages.preferences_pb") } }
    private val server = MockWebServer()
    private lateinit var client: PanelClient
    @Before fun setup() {
        server.start()
        client = PanelClient(OkHttpClient(), server.url("/").toString().trimEnd('/'), true)
    }
    @After fun cleanup() { scope.cancel(); server.shutdown() }

    @Test fun dismissedMessagesStayDismissedInNewRepository() = runTest {
        val repo = AnnouncementRepository(client, prefs)
        server.enqueue(messages())
        repo.refresh()
        assertEquals(1, repo.pending.value.size)
        repo.dismiss("test")
        assertTrue(repo.pending.value.isEmpty())
        val recreated = AnnouncementRepository(client, prefs)
        server.enqueue(messages())
        recreated.refresh()
        assertTrue(recreated.pending.value.isEmpty())
    }

    @Test fun withdrawnMessageLeavesTheQueue() = runTest {
        val repo = AnnouncementRepository(client, prefs)
        server.enqueue(messages()); repo.refresh()
        server.enqueue(MockResponse().setBody("""{"messages":[]}""")); repo.refresh()
        assertTrue(repo.pending.value.isEmpty())
    }

    private fun messages() = MockResponse().setBody("""{"messages":[{"id":"test","title":"Notice","body":"Service message"}]}""")
}
