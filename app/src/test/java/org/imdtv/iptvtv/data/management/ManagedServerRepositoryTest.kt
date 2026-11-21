package org.imdtv.iptvtv.data.management

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.imdtv.iptvtv.BuildConfig
import org.imdtv.iptvtv.data.db.AppDatabase
import org.imdtv.iptvtv.data.db.ChannelEntity
import org.imdtv.iptvtv.data.db.EpgSourceEntity
import org.imdtv.iptvtv.data.db.ProviderEntity
import org.imdtv.iptvtv.data.db.ProviderType
import org.imdtv.iptvtv.data.model.Kind
import org.imdtv.iptvtv.data.model.StreamFormat
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ManagedServerRepositoryTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private lateinit var db: AppDatabase
    private lateinit var panelServer: MockWebServer

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        panelServer = MockWebServer().also { it.start() }
        context.getSharedPreferences("managed_servers", 0).edit().clear().commit()
    }

    @After fun tearDown() { db.close(); panelServer.shutdown() }

    @Test fun endpointRotationKeepsCredentialsAndUpdatesGeneratedUrls() = kotlinx.coroutines.runBlocking {
        val storedUrl = BuildConfig.STREAMING_BASE_URL
        val oldUrl = storedUrl.toHttpUrl().toString().trimEnd('/')
        val newUrl = "https://rotated.example:443"
        val id = db.providerDao().insert(ProviderEntity(
            name = "Managed", type = ProviderType.XTREAM, url = oldUrl,
            username = "saved-user", password = "saved-password", userAgent = null,
            streamFormat = StreamFormat.TS, epgOverrideUrls = emptyList(), lastSync = null,
            managedServerId = "primary",
        ))
        db.channelDao().upsert(listOf(ChannelEntity(
            providerId = id, categoryId = null, remoteId = "1", name = "Channel", logo = null,
            number = null, hidden = false, tvgId = null, streamUrl = "$oldUrl/live/__USER__/__PASS__/1.ts",
        )))
        val sourceId = db.epgDao().insertSource(EpgSourceEntity(
            providerId = id, url = "$oldUrl/xmltv.php?username=__USER__&password=__PASS__", priority = 0,
        ))
        panelServer.enqueue(MockResponse().setHeader("Content-Type", "application/json")
            .setBody("""{"revision":1,"servers":[{"id":"primary","name":"Primary","url":"$newUrl"}],"defaultServerId":"primary"}"""))
        val panel = PanelClient(okhttp3.OkHttpClient(), panelServer.url("/").toString(), allowHttp = true)
        ManagedServerRepository(context, panel, db).refresh()

        val saved = db.providerDao().get(id)!!
        assertEquals("primary", saved.managedServerId)
        assertEquals(newUrl, saved.url)
        assertEquals("saved-user", saved.username)
        assertEquals("saved-password", saved.password)
        assertEquals("$newUrl/live/__USER__/__PASS__/1.ts", db.channelDao().byProvider(id).single().streamUrl)
        assertEquals("$newUrl/xmltv.php?username=__USER__&password=__PASS__", db.epgDao().source(sourceId)!!.url)
    }

    @Test fun customEpgOverrideIsNotRewritten() = kotlinx.coroutines.runBlocking {
        val oldUrl = BuildConfig.STREAMING_BASE_URL
        val newUrl = "https://rotated.example"
        val id = db.providerDao().insert(ProviderEntity(
            name = "Managed", type = ProviderType.XTREAM, url = oldUrl,
            username = "u", password = "p", userAgent = null, streamFormat = StreamFormat.TS,
            epgOverrideUrls = listOf("$oldUrl/custom.xml"), lastSync = null, managedServerId = "primary",
        ))
        val sourceId = db.epgDao().insertSource(EpgSourceEntity(providerId = id, url = "$oldUrl/custom.xml", priority = 0))
        panelServer.enqueue(MockResponse().setHeader("Content-Type", "application/json")
            .setBody("""{"revision":1,"servers":[{"id":"primary","name":"Primary","url":"$newUrl"}],"defaultServerId":"primary"}"""))
        ManagedServerRepository(context, PanelClient(okhttp3.OkHttpClient(), panelServer.url("/").toString(), allowHttp = true), db).refresh()
        assertEquals("$oldUrl/custom.xml", db.epgDao().source(sourceId)!!.url)
    }
}
