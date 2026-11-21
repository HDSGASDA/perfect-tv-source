package org.imdtv.iptvtv.data.management

import android.content.Context
import android.util.Log
import androidx.room.withTransaction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.imdtv.iptvtv.BuildConfig
import org.imdtv.iptvtv.data.db.AppDatabase
import org.imdtv.iptvtv.data.db.ProviderEntity

/** Caches panel servers and applies endpoint rotations without changing saved credentials. */
class ManagedServerRepository(
    context: Context,
    private val panel: PanelClient,
    private val db: AppDatabase,
) {
    private val storage = context.getSharedPreferences("managed_servers", Context.MODE_PRIVATE)
    private val key = "config:${BuildConfig.PANEL_BASE_URL}"
    private val json = Json { ignoreUnknownKeys = true }
    private val bootstrap = PanelConfig(0, listOf(StreamingServer("primary", "Primary server", BuildConfig.STREAMING_BASE_URL)), "primary")
    private val mutableConfig = MutableStateFlow(runCatching {
        storage.getString(key, null)?.let { json.decodeFromString<PanelConfig>(it).validated() }
    }.getOrNull() ?: bootstrap)
    val config = mutableConfig.asStateFlow()
    private val lock = Mutex()

    suspend fun refresh() = withContext(Dispatchers.IO) {
        try {
            lock.withLock {
                val received = panel.config() ?: return@withLock
                val previous = config.value
                if (received.revision < previous.revision) return@withLock
                updateManagedProviders(previous, received)
                if (!storage.edit().putString(key, json.encodeToString(received)).commit()) return@withLock
                mutableConfig.value = received
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.i("ManagedServers", "Keeping cached servers (${e.javaClass.simpleName})")
        }
    }

    /** Match by stable ID; legacy rows are safely backfilled only from a unique previous URL match. */
    private suspend fun updateManagedProviders(previous: PanelConfig, received: PanelConfig) {
        db.withTransaction {
            db.providerDao().all().forEach { provider ->
                val previousServer = provider.managedServerId?.let { id ->
                    previous.servers.singleOrNull { it.id == id }
                } ?: previous.servers.filter { ManagedProvider.accepts(provider.type, provider.url, it.url) }.singleOrNull()
                val serverId = provider.managedServerId ?: previousServer?.id ?: return@forEach
                val server = received.servers.singleOrNull { it.id == serverId } ?: return@forEach
                val oldOrigin = origin(provider.url)
                val newOrigin = origin(server.url)
                if (provider.managedServerId != serverId || oldOrigin != newOrigin) {
                    // This DAO operation preserves username/password byte-for-byte while updating the encrypted URL.
                    db.providerDao().setManagedServer(provider.id, server.url, serverId)
                }
                if (oldOrigin != newOrigin) {
                    val dao = db.providerDao()
                    dao.replaceInChannelUrls(provider.id, oldOrigin, newOrigin)
                    dao.replaceInMovieUrls(provider.id, oldOrigin, newOrigin)
                    dao.replaceInEpisodeUrls(provider.id, oldOrigin, newOrigin)
                    if (provider.epgOverrideUrls.isEmpty()) {
                        db.epgDao().replaceInPlaylistUrls(provider.id, oldOrigin, newOrigin)
                    }
                }
            }
        }
    }

    private fun origin(url: String): String {
        val parsed = url.toHttpUrl()
        // HttpUrl omits a default port when serialising. Keep an explicitly written
        // port because generated playlist URLs must preserve the panel's spelling.
        val authority = runCatching { java.net.URI(url).rawAuthority.orEmpty() }.getOrDefault("")
        val hostPort = authority.substringAfterLast('@')
        val explicitPort = if (hostPort.startsWith("[")) {
            hostPort.substringAfter(']').startsWith(":")
        } else {
            hostPort.count { it == ':' } == 1 && hostPort.substringAfterLast(':').all { it.isDigit() }
        }
        val host = parsed.host.let { if (it.contains(':')) "[$it]" else it }
        val defaultPort = when (parsed.scheme.lowercase()) {
            "http" -> 80
            "https" -> 443
            else -> -1
        }
        val port = if (explicitPort || parsed.port != defaultPort) ":${parsed.port}" else ""
        return "${parsed.scheme}://$host$port"
    }

    fun validate(provider: ProviderEntity) {
        if (!BuildConfig.MANAGED) return
        val servers = config.value.servers
        val valid = provider.managedServerId?.let { id ->
            servers.any { it.id == id && ManagedProvider.accepts(provider.type, provider.url, it.url) }
        } ?: servers.any { ManagedProvider.accepts(provider.type, provider.url, it.url) }
        require(valid) {
            "This server is no longer in the service list. Edit your playlist, select a server and enter your login again."
        }
    }
}
