package org.imdtv.iptvtv.data.diagnostics

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.first
import org.imdtv.iptvtv.BuildConfig
import java.io.File
import java.security.MessageDigest

/**
 * Opt-in crash reports (Playback › Send crash reports, managed builds, off by default).
 *
 * The handler runs while the process is dying, so it only writes a small file: the exception type, a fingerprint and
 * this app's own stack frames. No message text, no channel names, no account data, no provider URLs — a message can
 * carry a stream URL with credentials in it. [CrashReportsWorker] uploads the file on the next start, once settings
 * have been read, and deletes it whether or not the upload succeeded.
 */
object CrashStore {
    private const val TAG = "IptvCrash"
    private const val MAX_REPORTS = 5
    private const val MAX_FRAMES = 20
    private const val MAX_STACK_CHARS = 4000
    /** A report older than this is not worth sending: it belongs to a build the customer may have replaced. */
    private const val MAX_AGE_MS = 14L * 24 * 60 * 60 * 1000

    private fun dir(c: Context) = File(c.filesDir, "crashes")

    /** One report as it is stored and sent. [at] is epoch milliseconds. */
    data class Report(val at: Long, val type: String, val fingerprint: String, val stack: String, val thread: String)

    /**
     * Installs the handler, keeping any handler already there (Robolectric and test runners install their own) so the
     * process still dies as it would have. Never throws.
     */
    fun install(c: Context, enabled: suspend () -> Boolean) {
        if (!BuildConfig.MANAGED) return
        val app = c.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                // Settings are a DataStore flow and this thread is about to die: a short bounded read, then give up.
                val on = kotlinx.coroutines.runBlocking {
                    kotlinx.coroutines.withTimeoutOrNull(500) { enabled() }
                } ?: false
                if (on) write(app, thread, error)
            } catch (e: Exception) {
                Log.w(TAG, "crash report not written", e)
            }
            previous?.uncaughtException(thread, error)
        }
    }

    private fun write(c: Context, thread: Thread, error: Throwable, at: Long = System.currentTimeMillis()) {
        val frames = frames(error)
        val type = error.javaClass.name.take(200)
        val fingerprint = sha256(type + "\n" + frames.joinToString("\n")).take(16)
        val stack = frames.joinToString("\n").take(MAX_STACK_CHARS)
        val d = dir(c).apply { mkdirs() }
        prune(d)
        val name = "$at-$fingerprint.json"
        File(d, name).writeText(
            kotlinx.serialization.json.Json.encodeToString(Stored.serializer(),
                Stored(at = at, type = type, fingerprint = fingerprint, stack = stack,
                    thread = thread.name.take(80), versionCode = BuildConfig.VERSION_CODE, versionName = BuildConfig.VERSION_NAME,
                    device = android.os.Build.MODEL.orEmpty().take(80), android = android.os.Build.VERSION.SDK_INT))
        )
    }

    /** This app's frames, plus the cause chain's types: framework frames say nothing about our bug. */
    /** Visible for tests: the frames a report carries, and nothing else. */
    internal fun framesFor(error: Throwable): List<String> = frames(error)

    /** Visible for tests: writes a report as the handler would, without dying. */
    internal fun writeForTest(c: Context, thread: Thread, error: Throwable, at: Long = System.currentTimeMillis()) = write(c, thread, error, at)

    private fun frames(error: Throwable): List<String> {
        val out = ArrayList<String>(MAX_FRAMES)
        var e: Throwable? = error
        var depth = 0
        while (e != null && depth < 4 && out.size < MAX_FRAMES) {
            if (depth > 0) out += "caused by ${e.javaClass.name.take(200)}"
            e.stackTrace.asSequence()
                .filter { it.className.startsWith("org.imdtv.iptvtv") }
                .forEach { out += "${it.className}.${it.methodName}:${it.lineNumber}".take(200) }
            e = e.cause
            depth++
        }
        return out.take(MAX_FRAMES)
    }

    /** Reports waiting to be sent, newest last. Unreadable or stale files are dropped. */
    fun take(c: Context): List<Stored> {
        val d = dir(c)
        val files = d.listFiles().orEmpty().filter { it.isFile }.sortedBy { it.name }
        val cutoff = System.currentTimeMillis() - MAX_AGE_MS
        return files.mapNotNull { f ->
            val s = runCatching {
                kotlinx.serialization.json.Json.decodeFromString(Stored.serializer(), f.readText())
            }.getOrNull()
            if (s == null || s.at < cutoff) { runCatching { f.delete() }; null } else s
        }
    }

    fun clear(c: Context) {
        runCatching { dir(c).listFiles().orEmpty().forEach { it.delete() } }
    }

    private fun prune(d: File) {
        val files = d.listFiles().orEmpty().filter { it.isFile }.sortedBy { it.name }
        files.take((files.size - MAX_REPORTS + 1).coerceAtLeast(0)).forEach { runCatching { it.delete() } }
    }

    private fun sha256(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 0xff) }

    /** What is on disk: the report plus the build and box it came from, so an upload needs no other state. */
    @kotlinx.serialization.Serializable
    data class Stored(
        val at: Long, val type: String, val fingerprint: String, val stack: String, val thread: String,
        val versionCode: Int, val versionName: String, val device: String, val android: Int,
    )

    /** Convenience for the worker: reads the setting once. */
    suspend fun enabledIn(settings: org.imdtv.iptvtv.data.settings.SettingsRepository): Boolean =
        settings.settings.first().crashReportsOn
}