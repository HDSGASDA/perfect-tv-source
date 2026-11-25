package org.imdtv.iptvtv.data.diagnostics

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CrashStoreTest {
    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test fun keepsOnlyAppFramesAndTheCauseTypes() {
        val error = IllegalStateException("stream http://user:secret@host/path failed",
            IllegalArgumentException("inner detail with a password"))
        error.stackTrace = arrayOf(
            StackTraceElement("org.imdtv.iptvtv.ui.live.GuideGrid", "row", "GuideGrid.kt", 174),
            StackTraceElement("androidx.compose.runtime.ComposerImpl", "recompose", "Composer.kt", 900),
            StackTraceElement("org.imdtv.iptvtv.player.PlayerManager", "start", "PlayerManager.kt", 512),
        )
        val frames = CrashStore.framesFor(error)
        assertTrue(frames.any { it.startsWith("org.imdtv.iptvtv.ui.live.GuideGrid.row:174") })
        assertFalse("framework frames say nothing about our bug", frames.any { it.contains("androidx.compose") })
        assertTrue(frames.any { it.startsWith("caused by java.lang.IllegalArgumentException") })
        val joined = frames.joinToString("\n")
        assertFalse("a message can carry a stream URL with credentials", joined.contains("secret"))
        assertFalse(joined.contains("password"))
    }

    @Test fun reportsAreReadBackAndCleared() {
        CrashStore.clear(context)
        CrashStore.writeForTest(context, Thread.currentThread(), IllegalStateException("boom"))
        CrashStore.writeForTest(context, Thread.currentThread(), NullPointerException("boom"))
        val reports = CrashStore.take(context)
        assertEquals(2, reports.size)
        assertTrue(reports.all { it.fingerprint.matches(Regex("[a-f0-9]{16}")) })
        assertTrue(reports.all { it.versionCode > 0 && it.android > 0 })
        CrashStore.clear(context)
        assertTrue(CrashStore.take(context).isEmpty())
    }

    @Test fun identicalCrashesShareAFingerprintAndDifferentOnesDoNot() {
        CrashStore.clear(context)
        val a = IllegalStateException("one"); a.stackTrace = arrayOf(StackTraceElement("org.imdtv.iptvtv.A", "f", "A.kt", 1))
        val b = IllegalStateException("two"); b.stackTrace = arrayOf(StackTraceElement("org.imdtv.iptvtv.A", "f", "A.kt", 1))
        val c = IllegalStateException("three"); c.stackTrace = arrayOf(StackTraceElement("org.imdtv.iptvtv.A", "g", "A.kt", 2))
        CrashStore.writeForTest(context, Thread.currentThread(), a)
        CrashStore.writeForTest(context, Thread.currentThread(), b)
        CrashStore.writeForTest(context, Thread.currentThread(), c)
        val prints = CrashStore.take(context).map { it.fingerprint }
        assertEquals("the same code line is one crash, whatever the message said", 2, prints.distinct().size)
        CrashStore.clear(context)
    }

    @Test fun staleReportsAreDropped() {
        CrashStore.clear(context)
        CrashStore.writeForTest(context, Thread.currentThread(), IllegalStateException("old"), at = System.currentTimeMillis() - 30L * 24 * 60 * 60 * 1000)
        assertTrue(CrashStore.take(context).isEmpty())
    }

    @Test fun backlogIsCapped() {
        CrashStore.clear(context)
        repeat(9) { i ->
            val e = IllegalStateException("n$i")
            e.stackTrace = arrayOf(StackTraceElement("org.imdtv.iptvtv.A", "f$i", "A.kt", i))
            CrashStore.writeForTest(context, Thread.currentThread(), e)
        }
        assertTrue("a repeating crash must not fill the disk", CrashStore.take(context).size <= 5)
        CrashStore.clear(context)
    }
}