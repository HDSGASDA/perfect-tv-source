package org.imdtv.iptvtv.player

import java.io.File
import java.io.RandomAccessFile

/**
 * The rolling buffer behind live pause / instant replay: the bytes the player itself reads (tee'd, so no second
 * provider connection) land in two alternating files. When the active one is full, the older one is truncated and
 * becomes active — no copying, so the loader thread never stalls on a big rewrite. Replay plays older + active in
 * order; capture freezes while replaying so the files can't change under the player.
 *
 * Only MPEG-TS is kept: the first bytes are checked for the 188-byte packet sync pattern, and anything else (fMP4
 * HLS, audio-only) marks the buffer unsupported instead of saving unplayable data.
 */
class ReplayBuffer(private val dir: File, private val segmentBytes: Long = 48L * 1024 * 1024) {
    private var active = 0
    private var out: RandomAccessFile? = null
    private var sniffed = 0
    private var sniffBuf = ByteArray(188 * 6)
    private var unsupported = false
    private var frozen = false
    private var gen = 0

    /** Identifies this channel's buffer; a tee from a previous stream appends to nothing after [clear]. */
    @Synchronized fun generation(): Int = gen

    /** Bytes held now (both files). */
    @Synchronized fun totalBytes(): Long = file(0).length() + file(1).length()

    /** Enough buffered to be worth replaying (~a few seconds of SD). */
    @Synchronized fun ready(): Boolean = !unsupported && totalBytes() >= MIN_BYTES

    /** The files to play, oldest first; empty when nothing usable is buffered. */
    @Synchronized fun sources(): List<File> {
        if (unsupported) return emptyList()
        val older = file(1 - active)
        val current = file(active)
        return listOfNotNull(older.takeIf { it.length() > 0 }, current.takeIf { it.length() > 0 })
    }

    /** Called from the player's loader thread with the bytes just read; [g] must be the current [generation]. */
    @Synchronized fun append(buf: ByteArray, offset: Int, length: Int, g: Int) {
        if (unsupported || frozen || length <= 0 || g != gen) return
        if (sniffed < sniffBuf.size) {
            val n = minOf(length, sniffBuf.size - sniffed)
            System.arraycopy(buf, offset, sniffBuf, sniffed, n)
            sniffed += n
            if (sniffed >= sniffBuf.size && !isTransportStream(sniffBuf)) { unsupported = true; clear(); return }
            if (sniffed < sniffBuf.size) return // wait for a full pattern before writing anything
            write(sniffBuf, 0, sniffed)
            if (n < length) write(buf, offset + n, length - n)
            return
        }
        write(buf, offset, length)
    }

    private fun write(buf: ByteArray, offset: Int, length: Int) {
        try {
            val o = out ?: RandomAccessFile(file(active).also { it.parentFile?.mkdirs() }, "rw").also { it.seek(it.length()); out = it }
            o.write(buf, offset, length)
            if (o.length() >= segmentBytes) rotate()
        } catch (e: java.io.IOException) {
            // A full or slow cache dir must never break playback: drop the buffer, keep playing.
            unsupported = true
            runCatching { out?.close() }
            out = null
        }
    }

    private fun rotate() {
        out?.close(); out = null
        active = 1 - active
        file(active).delete() // the oldest data goes; the new active file starts empty
    }

    /** No more bytes until [thaw] (replay is playing the files). */
    @Synchronized fun freeze() { frozen = true; runCatching { out?.close() }; out = null }

    @Synchronized fun thaw() { frozen = false }

    @Synchronized fun clear() {
        runCatching { out?.close() }; out = null
        sniffed = 0; unsupported = false; frozen = false; active = 0
        gen++
        file(0).delete(); file(1).delete()
    }

    private fun file(i: Int) = File(dir, "buf$i.ts")

    companion object {
        const val MIN_BYTES = 2L * 1024 * 1024

        /** Six consecutive 188-byte packets starting with the TS sync byte (allowing a leading partial packet). */
        fun isTransportStream(b: ByteArray): Boolean {
            for (start in 0 until minOf(188, b.size - 188 * 5)) {
                if ((0..4).all { b[start + it * 188] == 0x47.toByte() }) return true
            }
            return false
        }
    }
}