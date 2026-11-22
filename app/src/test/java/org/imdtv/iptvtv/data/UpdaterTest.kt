package org.imdtv.iptvtv.data.update

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.imdtv.iptvtv.BuildConfig
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class UpdaterTest {
    @get:Rule val tmp = TemporaryFolder()
    private val server = MockWebServer()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @After fun tearDown() {
        server.shutdown()
        scope.cancel()
    }

    private fun updater() = Updater(
        context, OkHttpClient(), Json { ignoreUnknownKeys = true },
        PreferenceDataStoreFactory.create(scope = scope) { tmp.newFile("update.preferences_pb") },
        checkEveryMs = 0,
        releasesUrl = server.url("/releases").toString().trimEnd('/'),
        panelBaseUrl = server.url("/").toString().trimEnd('/'),
    )

    /** The published-release body of the flavor under test: the panel's JSON for managed, GitHub's for standard. */
    private fun release(tag: String, code: Long = tag.trimStart('v', 'V').substringBefore('.').toLongOrNull() ?: 1L) = MockResponse().setBody(
        if (BuildConfig.MANAGED)
            """{"versionCode":$code,"versionName":"${tag.trimStart('v', 'V')}","apkUrl":"${server.url("/app.apk")}","sha256":"${"0".repeat(64)}","notes":""}"""
        else
            """{"tag_name":"$tag","assets":[{"name":"imd-tv.apk","browser_download_url":"https://example.com/imd-tv.apk"}]}""",
    )

    @Test fun versions() {
        assertTrue(isNewer("v0.2.0", "0.1.0"))
        assertTrue(isNewer("v0.10.0", "0.9.5"))
        assertTrue(isNewer("1.1", "1.0.9"))
        assertFalse(isNewer("v0.1.0", "0.1.0"))
        assertFalse(isNewer("v0.1", "0.1.0"))
        assertFalse(isNewer("v0.0.9", "0.1.0"))
        assertFalse(isNewer("v0.2.0-beta", "0.2.0"))
    }

    @Test fun notes() {
        assertEquals("What's new\nBulk hide channels\nSee the site", plainNotes("## What's new\n**Bulk hide** channels\nSee [the site](https://x)"))
    }

    @Test fun automaticCheckOpensThePrompt() = runTest {
        val updater = updater()
        server.enqueue(release("v99.0.0"))
        assertTrue(updater.check())
        assertEquals("99.0.0", updater.available.value?.version)
        assertTrue(updater.prompt.value)
    }

    @Test fun laterDoesNotReopenTheSameReleaseOnTheNextPoll() = runTest {
        val updater = updater()
        server.enqueue(release("v99.0.0"))
        updater.check()
        updater.later()
        server.enqueue(release("v99.0.0"))
        updater.check()
        assertFalse(updater.prompt.value)
    }

    @Test fun aNewReleaseOpensThePromptAfterLater() = runTest {
        val updater = updater()
        server.enqueue(release("v99.0.0"))
        updater.check()
        updater.later()
        server.enqueue(release("v100.0.0"))
        updater.check()
        assertEquals("100.0.0", updater.available.value?.version)
        assertTrue(updater.prompt.value)
    }

    @Test fun aMissingReleaseClearsThePrompt() = runTest {
        val updater = updater()
        server.enqueue(release("v99.0.0"))
        updater.check()
        server.enqueue(MockResponse().setResponseCode(404))
        // The panel answers 404 with "no release" (a successful check); GitHub's 404 is a failed request.
        if (BuildConfig.MANAGED) assertTrue(updater.check()) else assertFalse(updater.check())
        server.enqueue(release("v0.0.1", code = 1))
        assertTrue(updater.check())
        assertNull(updater.available.value)
        assertFalse(updater.prompt.value)
    }

    @Test fun fullNotesAreNotCut() {
        val long = (1..100).joinToString("\n") { "- change $it" }
        assertEquals(long, plainNotes(long, max = Int.MAX_VALUE))
        assertTrue(plainNotes(long).endsWith("…"))
    }
}
