package org.imdtv.iptvtv.player

import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener

/**
 * Copies everything the player reads from a live stream into the [ReplayBuffer] — the same connection, so providers
 * that allow only one stream per account are not disturbed. Buffer problems never propagate to playback.
 */
@UnstableApi
class ReplayTeeDataSource(private val upstream: DataSource, private val buffer: ReplayBuffer) : DataSource {
    private val gen = buffer.generation()
    // Only media bytes: HLS/DASH manifests (.m3u8/.mpd) and fMP4 init/data segments (.mp4/.m4s) are not MPEG-TS.
    private var tee = false
    override fun addTransferListener(transferListener: TransferListener) = upstream.addTransferListener(transferListener)
    override fun open(dataSpec: DataSpec): Long {
        tee = dataSpec.uri.lastPathSegment?.endsWith(".ts", ignoreCase = true) == true
        return upstream.open(dataSpec)
    }
    override fun read(target: ByteArray, offset: Int, length: Int): Int {
        val n = upstream.read(target, offset, length)
        if (n > 0 && tee) runCatching { buffer.append(target, offset, n, gen) }
        return n
    }
    override fun getUri(): android.net.Uri? = upstream.uri
    override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders
    override fun close() = upstream.close()
}