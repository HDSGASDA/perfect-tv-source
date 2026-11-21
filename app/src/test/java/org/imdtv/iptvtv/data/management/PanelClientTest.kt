package org.imdtv.iptvtv.data.management

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.IOException

class PanelClientTest {
    private val server = MockWebServer()
    private lateinit var client: PanelClient
    @Before fun setup() {
        server.start()
        client = PanelClient(OkHttpClient(), server.url("/").toString().trimEnd('/'), allowHttp = true)
    }
    @After fun cleanup() = server.shutdown()

    @Test fun noReleaseIsNormal() {
        server.enqueue(MockResponse().setResponseCode(204))
        assertNull(client.release())
        assertEquals("/api/app/releases/latest", server.takeRequest().path)
    }

    @Test fun parsesReleaseAndUsesVersionCode() {
        server.enqueue(MockResponse().setBody(releaseJson()))
        assertEquals(12L, client.release()!!.versionCode)
    }

    @Test fun rejectsMissingHash() {
        server.enqueue(MockResponse().setBody(releaseJson(hash = "bad")))
        assertThrows(IllegalArgumentException::class.java) { client.release() }
    }

    @Test fun rejectsDifferentDownloadOrigin() {
        server.enqueue(MockResponse().setBody(releaseJson(url = "https://other.example/app.apk")))
        assertThrows(IllegalArgumentException::class.java) { client.release() }
    }

    @Test fun rejectsRedirects() {
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "https://other.example"))
        assertThrows(IOException::class.java) { client.release() }
        assertEquals(1, server.requestCount)
    }

    @Test fun rejectsOversizedBodiesWithoutContentLength() {
        server.enqueue(MockResponse().setChunkedBody("a".repeat(512 * 1024 + 1), 8192))
        assertThrows(IOException::class.java) { client.messages() }
    }

    @Test fun messagesRemainPlainText() {
        server.enqueue(MockResponse().setBody("""{"messages":[{"id":"abc","title":"Update","body":"<script>alert(1)</script>"}]}"""))
        assertEquals("<script>alert(1)</script>", client.messages().single().body)
        assertNull(server.takeRequest().getHeader("Authorization"))
    }

    @Test fun rejectsDuplicateMessageIds() {
        val message = """{"id":"abc","title":"Title","body":"Body"}"""
        server.enqueue(MockResponse().setBody("""{"messages":[$message,$message]}"""))
        assertThrows(IllegalArgumentException::class.java) { client.messages() }
    }

    @Test fun productionRequiresHttpsAndMatchingOrigin() {
        assertThrows(IllegalArgumentException::class.java) { validatePanelUrl("http://localhost:3000", "http://localhost:3000/api", false) }
        assertThrows(IllegalArgumentException::class.java) { validatePanelUrl("https://panel.example", "http://panel.example/file.apk", false) }
        assertThrows(IllegalArgumentException::class.java) { validatePanelUrl("https://panel.example", "https://user:pass@panel.example/file.apk", false) }
        assertEquals("panel.example", validatePanelUrl("https://panel.example", "https://panel.example/file.apk", false).host)
    }

    @Test fun rejectsWrongRequestedVersion() {
        server.enqueue(MockResponse().setBody(releaseJson()))
        assertThrows(IllegalArgumentException::class.java) { client.release(11) }
    }

    private fun releaseJson(hash: String = "a".repeat(64), url: String = server.url("/api/app/apks/test.apk").toString()) =
        """{"versionCode":12,"versionName":"0.6.0-managed.2","apkUrl":"$url","sha256":"$hash","notes":"Changes"}"""
}
