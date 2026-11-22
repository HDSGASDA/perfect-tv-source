package org.imdtv.iptvtv.data.management

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.imdtv.iptvtv.BuildConfig
import java.io.IOException
import java.util.concurrent.TimeUnit

@Serializable
data class PanelRelease(
    val versionCode: Long,
    val versionName: String,
    val apkUrl: String,
    val sha256: String,
    val notes: String = "",
)

@Serializable
data class StreamingServer(val id: String, val name: String, val url: String)
@Serializable
data class PanelConfig(val revision: Long, val servers: List<StreamingServer>, val defaultServerId: String) {
    fun validated(): PanelConfig {
        require(revision >= 0 && servers.size in 1..10) { "Invalid server configuration" }
        require(servers.map { it.id }.distinct().size == servers.size) { "Duplicate server IDs" }
        require(servers.any { it.id == defaultServerId }) { "Missing default server" }
        val urls = servers.map {
            require(it.id.matches(Regex("[a-zA-Z0-9_-]{1,64}"))) { "Invalid server ID" }
            require(it.name.isNotBlank() && it.name.length <= 80) { "Invalid server name" }
            val url = it.url.toHttpUrl()
            require(url.isHttps && url.username.isEmpty() && url.password.isEmpty() && url.encodedPath == "/" && url.query == null && url.fragment == null) { "Streaming server must be an HTTPS origin" }
            url
        }
        require(urls.distinct().size == urls.size) { "Duplicate streaming servers" }
        return this
    }
}

@Serializable
data class PanelMessage(val id: String, val title: String, val body: String)
@Serializable
private data class MessageEnvelope(val messages: List<PanelMessage> = emptyList())

/** Opt-in playback diagnostics (Playback › Send playback diagnostics): anonymous per-channel totals, no account data. */
@Serializable
data class ChannelDiagnostics(val name: String, val seconds: Long, val stalls: Int, val underruns: Int, val droppedFrames: Int, val errors: Int)
@Serializable
data class DiagnosticsPayload(val versionCode: Int, val versionName: String, val device: String, val android: Int, val channels: List<ChannelDiagnostics>)

/** Public broadcast feed. Never attach provider credentials, hardware identifiers or an embedded administrator key. */
class PanelClient(
    http: OkHttpClient,
    private val baseUrl: String = BuildConfig.PANEL_BASE_URL,
    private val allowHttp: Boolean = BuildConfig.DEBUG,
) {
    // No redirect to another host (including HTTPS -> HTTP); independent timeouts from long-running streams.
    val http: OkHttpClient = http.newBuilder().followRedirects(false).followSslRedirects(false)
        .connectTimeout(10, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS).build()
    private val json = Json { ignoreUnknownKeys = true }

    fun checkedUrl(url: String): HttpUrl = validatePanelUrl(baseUrl, url, allowHttp)

    private fun get(path: String): String? {
        val url = checkedUrl("${baseUrl.trimEnd('/')}$path")
        return http.newCall(Request.Builder().url(url).header("Accept", "application/json").build()).execute().use { r ->
            if (r.code == 204 || r.code == 404) return@use null
            if (!r.isSuccessful) throw IOException("Panel returned HTTP ${r.code}")
            val body = r.body ?: throw IOException("Empty panel response")
            // Check both declared and actual size (including chunked/gzipped responses).
            if (body.contentLength() > MAX_JSON_BYTES) throw IOException("Panel response too large")
            val source = body.source()
            if (source.request(MAX_JSON_BYTES + 1)) throw IOException("Panel response too large")
            source.readUtf8()
        }
    }

    fun release(versionCode: Long? = null): PanelRelease? {
        val raw = get("/api/app/releases/${versionCode ?: "latest"}") ?: return null
        return json.decodeFromString<PanelRelease>(raw).also {
            require(it.versionCode in 1..Int.MAX_VALUE.toLong()) { "Invalid release version code" }
            require(it.versionName.isNotBlank() && it.versionName.length <= 100) { "Invalid release name" }
            require(it.sha256.matches(Regex("[a-fA-F0-9]{64}"))) { "Missing APK digest" }
            require(it.notes.length <= 32000) { "Release notes too long" }
            checkedUrl(it.apkUrl)
            if (versionCode != null) require(it.versionCode == versionCode) { "Wrong release returned" }
        }
    }

    fun config(): PanelConfig? = get("/api/app/config")?.let { json.decodeFromString<PanelConfig>(it).validated() }

    fun messages(): List<PanelMessage> {
        val raw = get("/api/app/messages") ?: return emptyList()
        return json.decodeFromString<MessageEnvelope>(raw).messages.also { messages ->
            require(messages.size <= 20 && messages.distinctBy { it.id }.size == messages.size) { "Invalid message list" }
            messages.forEach {
                require(it.id.isNotBlank() && it.id.length <= 128 && '\n' !in it.id) { "Invalid message ID" }
                require(it.title.isNotBlank() && it.title.length <= 120) { "Invalid message title" }
                require(it.body.isNotBlank() && it.body.length <= 4000) { "Invalid message body" }
            }
        }
    }

    /** Uploads one batch of playback totals; throws IOException on any failure so the worker keeps the batch. */
    fun diagnostics(payload: DiagnosticsPayload) {
        val url = checkedUrl("${baseUrl.trimEnd('/')}/api/app/diagnostics")
        val body = json.encodeToString(DiagnosticsPayload.serializer(), payload)
            .toRequestBody("application/json; charset=utf-8".toMediaType())
        http.newCall(Request.Builder().url(url).post(body).build()).execute().use { r ->
            if (!r.isSuccessful) throw IOException("Panel returned HTTP ${r.code}")
        }
    }

    companion object { private const val MAX_JSON_BYTES = 512 * 1024L }
}

/** Restrict panel data and downloads to the configured origin. HTTP exists only for explicitly local debug builds. */
internal fun validatePanelUrl(base: String, candidate: String, allowHttp: Boolean): HttpUrl {
    val origin = base.toHttpUrl()
    val url = candidate.toHttpUrl()
    require(origin.username.isEmpty() && origin.password.isEmpty() && origin.encodedPath == "/" && origin.query == null && origin.fragment == null) { "Panel URL must be an origin" }
    require(url.username.isEmpty() && url.password.isEmpty() && url.fragment == null) { "Invalid panel URL" }
    require(origin.isHttps || allowHttp) { "The management panel must use HTTPS" }
    require(url.scheme == origin.scheme && url.host == origin.host && url.port == origin.port) { "APK must be hosted by the management panel" }
    return url
}
