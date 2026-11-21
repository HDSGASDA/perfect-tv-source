package org.imdtv.iptvtv.data.diagnostics

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.imdtv.iptvtv.data.db.ProviderEntity
import org.imdtv.iptvtv.data.source.PlaylistSource
import org.imdtv.iptvtv.data.source.XtreamAuthException
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlin.system.measureTimeMillis

/** One-shot, credential-safe catalogue report. Never contains a URL, login, or server response body. */
data class PlaylistDiagnostic(
    val providerId: Long,
    val providerName: String,
    val providerType: String,
    val host: String,
    val login: Boolean?,
    val latencyMs: Long?,
    val liveCategories: Int?,
    val liveChannels: Int?,
    val movieCategories: Int?,
    val movies: Int?,
    val seriesCategories: Int?,
    val series: Int?,
    val error: String?,
) {
    fun safeSummary(): String = buildString {
        append("Playlist: $providerName ($providerType)\nServer: $host\n")
        append("Login: ${login?.let { if (it) "accepted" else "rejected" } ?: "not verified"}\n")
        latencyMs?.let { append("Response: ${it}ms\n") }
        append("Live categories/channels: ${liveCategories ?: "—"}/${liveChannels ?: "—"}\n")
        append("Movie categories/titles: ${movieCategories ?: "—"}/${movies ?: "—"}\n")
        append("Series categories/titles: ${seriesCategories ?: "—"}/${series ?: "—"}\n")
        error?.let { append("Issue: $it\n") }
    }.trim()
}

/** Fetches the provider catalogue once to check access and show how many entries are available. */
suspend fun testPlaylistConnection(p: ProviderEntity, source: PlaylistSource): PlaylistDiagnostic = withContext(Dispatchers.IO) {
    val parsed = p.url.toHttpUrlOrNull()
    val host = parsed?.host ?: "(invalid server address)"
    var login: Boolean? = null
    var elapsed: Long? = null
    var liveCats: Int? = null
    var liveChannels: Int? = null
    var movieCats: Int? = null
    var movies: Int? = null
    var seriesCats: Int? = null
    var series: Int? = null
    var error: String? = if (parsed == null) "Invalid server address" else null
    if (parsed != null) {
        try {
            elapsed = measureTimeMillis {
                liveCats = source.liveCategories().size
                liveChannels = source.liveChannels().size
                movieCats = source.vodCategories().size
                movies = source.movies().size
                seriesCats = source.seriesCategories().size
                series = source.series().size
            }
            login = true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            login = if (e is XtreamAuthException) false else null
            error = when (e) {
                is XtreamAuthException -> "Login or account rejected"
                is SocketTimeoutException -> "Connection timed out"
                is UnknownHostException -> "Server name could not be found"
                is IOException -> "Server request failed"
                else -> "Playlist check failed"
            }
        }
    }
    PlaylistDiagnostic(p.id, p.name, p.type.name, host, login, elapsed, liveCats, liveChannels, movieCats, movies, seriesCats, series, error)
}
