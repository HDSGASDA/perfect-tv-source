package org.imdtv.iptvtv.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ReplayBufferTest {
    @get:Rule val tmp = TemporaryFolder()

    /** [n] MPEG-TS packets (188 bytes, sync byte 0x47). */
    private fun ts(n: Int): ByteArray = ByteArray(188 * n).also { b ->
        for (i in 0 until n) b[i * 188] = 0x47
    }

    private fun buffer(segmentBytes: Long = 48L * 1024 * 1024) = ReplayBuffer(tmp.newFolder(), segmentBytes)

    @Test fun transportStreamPatternIsRecognised() {
        assertTrue(ReplayBuffer.isTransportStream(ts(6)))
        // A leading partial packet: the pattern still lines up within 188 bytes.
        val shifted = ByteArray(188 * 6 + 50).also { System.arraycopy(ts(6), 0, it, 50, 188 * 6) }
        assertTrue(ReplayBuffer.isTransportStream(shifted))
        assertFalse(ReplayBuffer.isTransportStream(ByteArray(188 * 6) { 0x00 }))
        assertFalse(ReplayBuffer.isTransportStream("not a stream at all".toByteArray()))
    }

    @Test fun becomesReadyAfterEnoughBytes() {
        val b = buffer()
        assertFalse(b.ready())
        val g = b.generation()
        val chunk = ts(1000) // 188 kB
        repeat((ReplayBuffer.MIN_BYTES / chunk.size).toInt() + 1) { b.append(chunk, 0, chunk.size, g) }
        assertTrue(b.ready())
        assertTrue(b.totalBytes() >= ReplayBuffer.MIN_BYTES)
        assertEquals(1, b.sources().size)
    }

    @Test fun nonTransportStreamIsDroppedNotSaved() {
        val b = buffer()
        val g = b.generation()
        val junk = "this is an fMP4 segment, not MPEG-TS".toByteArray()
        repeat(100) { b.append(junk, 0, junk.size, g) }
        assertFalse(b.ready())
        assertEquals(0L, b.totalBytes())
        assertTrue(b.sources().isEmpty())
    }

    @Test fun rotationKeepsTwoSegmentsOldestFirst() {
        val b = buffer(segmentBytes = 188L * 100) // tiny segments: rotate quickly
        val g = b.generation()
        val chunk = ts(60)
        repeat(9) { b.append(chunk, 0, chunk.size, g) }
        val files = b.sources()
        assertEquals(2, files.size)
        assertTrue(b.totalBytes() <= 2 * 188L * 100 + chunk.size)
        // Older file first: it was written before the active one.
        assertTrue(files[0].lastModified() <= files[1].lastModified())
    }

    @Test fun freezeStopsCaptureAndThawResumes() {
        val b = buffer()
        val g = b.generation()
        val chunk = ts(1000)
        repeat(20) { b.append(chunk, 0, chunk.size, g) }
        val held = b.totalBytes()
        b.freeze()
        b.append(chunk, 0, chunk.size, g)
        assertEquals(held, b.totalBytes())
        b.thaw()
        b.append(chunk, 0, chunk.size, g)
        assertTrue(b.totalBytes() > held)
    }

    @Test fun clearDropsEverythingAndOldTeesCannotAppend() {
        val b = buffer()
        val oldGen = b.generation()
        val chunk = ts(1000)
        repeat(20) { b.append(chunk, 0, chunk.size, oldGen) }
        assertTrue(b.ready())
        b.clear()
        assertEquals(0L, b.totalBytes())
        assertFalse(b.ready())
        // A tee still reading the previous channel's connection must not write into the new buffer.
        b.append(chunk, 0, chunk.size, oldGen)
        assertEquals(0L, b.totalBytes())
        b.append(chunk, 0, chunk.size, b.generation())
        assertTrue(b.totalBytes() > 0)
    }
}