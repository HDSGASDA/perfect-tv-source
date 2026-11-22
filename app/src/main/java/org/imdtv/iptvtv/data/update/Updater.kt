package org.imdtv.iptvtv.data.update

import org.imdtv.iptvtv.R
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import org.imdtv.iptvtv.BuildConfig
import org.imdtv.iptvtv.IptvApp
import java.io.File
import java.io.IOException

private const val RELEASES = "https://api.github.com/repos/HDSGASDA/app-perfect-tv/releases"
private const val APK_NAME = "imd-tv.apk"
private const val CHECK_EVERY_MS = 10_000L

@Serializable private data class GhAsset(val name: String, @SerialName("browser_download_url") val url: String)
@Serializable private data class GhRelease(@SerialName("tag_name") val tag: String, val body: String? = null, val assets: List<GhAsset> = emptyList())

data class Update(val version: String, val apkUrl: String, val versionCode: Long? = null, val sha256: String? = null, val notes: String = "")

/** True when release tag [tag] ("v0.2.0") is a newer version than [current] ("0.1.0"). */
fun isNewer(tag: String, current: String): Boolean {
    fun parts(v: String) = v.trim().trimStart('v', 'V').substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
    val a = parts(tag); val b = parts(current)
    for (i in 0 until maxOf(a.size, b.size)) {
        val x = a.getOrElse(i) { 0 }; val y = b.getOrElse(i) { 0 }
        if (x != y) return x > y
    }
    return false
}

/**
 * One-click updates from GitHub Releases: the latest release's imd-tv.apk goes to Android's installer, which asks
 * the user to confirm (and, the first time, to allow installs from Perfect TV). Releases must keep the same signing key.
 */
class Updater(private val context: Context, private val http: OkHttpClient, private val json: Json, private val prefs: DataStore<Preferences>, private val checkEveryMs: Long = CHECK_EVERY_MS, private val releasesUrl: String = RELEASES) {
    private val panel by lazy { org.imdtv.iptvtv.data.management.PanelClient(http) }
    val available = MutableStateFlow<Update?>(null)
    /** The update prompt is open. Opens by itself once per version; About › Version opens it any time. */
    val prompt = MutableStateFlow(false)
    /** "Downloading… 40%", "Installing…", or null when idle. */
    val progress = MutableStateFlow<String?>(null)
    val error = MutableStateFlow<String?>(null)
    private val lastCheckAt = longPreferencesKey("update_last_check_at")
    private val notesSeen = stringPreferencesKey("notes_seen")
    private val checkLock = kotlinx.coroutines.sync.Mutex()
    // Dismissal only quiets this running app session. A later launch checks again and
    // automatically shows any still-published update, even if an earlier test tapped Later.
    @Volatile private var dismissedUpdateId: String? = null
    /** This version's release notes, shown once after an update; null when closed. */
    val whatsNew = MutableStateFlow<String?>(null)

    private fun release(path: String): GhRelease =
        http.newCall(Request.Builder().url("$releasesUrl/$path").header("Accept", "application/vnd.github+json").build()).execute().use { r ->
            if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
            json.decodeFromString<GhRelease>(r.body!!.string())
        }

    /** After an update (not a fresh install), fetches this version's release notes once. Offline: tries next launch. */
    suspend fun checkWhatsNew() = withContext(Dispatchers.IO) {
        val v = BuildConfig.VERSION_NAME
        if (prefs.data.first()[notesSeen] == v) return@withContext
        val pkg = context.packageManager.getPackageInfo(context.packageName, 0)
        val body = if (pkg.firstInstallTime == pkg.lastUpdateTime) "" else try {
            if (BuildConfig.MANAGED) panel.release(BuildConfig.VERSION_CODE.toLong())?.notes.orEmpty()
            else release("tags/v$v").body.orEmpty()
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            if (e.message != "HTTP 404") { Log.i("Updater", "Release notes check failed: ${e.javaClass.simpleName}"); return@withContext }
            "" // no release for this version (a dev build)
        }
        if (body.isBlank()) prefs.edit { it[notesSeen] = v } else whatsNew.value = plainNotes(body, max = Int.MAX_VALUE)
    }

    suspend fun closeWhatsNew() {
        whatsNew.value = null
        prefs.edit { it[notesSeen] = BuildConfig.VERSION_NAME }
    }

    /** Checks the published release endpoint; automatic checks are spaced ten seconds apart and [manual] ignores that spacing and "Later". Returns false on failure. */
    suspend fun check(manual: Boolean = false): Boolean = checkLock.withLock {
        withContext(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            val last = prefs.data.first()[lastCheckAt] ?: 0L
            if (!manual && now - last < checkEveryMs) return@withContext true
            val u = try {
                if (BuildConfig.MANAGED) {
                    panel.release()?.takeIf { it.versionCode > BuildConfig.VERSION_CODE }?.let {
                        Update(it.versionName, it.apkUrl, it.versionCode, it.sha256, plainNotes(it.notes))
                    }
                } else {
                    val release = release("latest")
                    val apk = release.assets.find { it.name == APK_NAME }
                    if (apk != null && isNewer(release.tag, BuildConfig.VERSION_NAME)) Update(release.tag.trimStart('v', 'V'), apk.url, notes = plainNotes(release.body.orEmpty())) else null
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.i("Updater", "Update check failed (${e.javaClass.simpleName})")
                return@withContext false
            }
            prefs.edit { it[lastCheckAt] = now }
            available.value = u
            if (u == null) prompt.value = false
            val updateId = u?.let { it.versionCode?.toString() ?: it.version }
            // Automatic polling must be able to surface the release after it is published.
            // A dismissal is session-local; it must not become a 24-hour suppression window.
            if (u != null && (manual || updateId != dismissedUpdateId)) prompt.value = true
            true
        }
    }

    /** "Later" (or Back) closes the prompt for this app session. A newly published version
     * still opens automatically, and any still-published update is checked again next launch. */
    suspend fun later() {
        prompt.value = false
        dismissedUpdateId = available.value?.let { it.versionCode?.toString() ?: it.version }
    }

    private val installLock = kotlinx.coroutines.sync.Mutex()

    suspend fun install(u: Update) = withContext(Dispatchers.IO) {
        if (!installLock.tryLock()) return@withContext
        if (progress.value != null) { installLock.unlock(); return@withContext }
        error.value = null
        val file = File(context.cacheDir, APK_NAME)
        var sessionId: Int? = null
        var committed = false
        try {
            progress.value = context.getString(R.string.set_b_downloading)
            val downloadHttp = if (BuildConfig.MANAGED) panel.http.newBuilder()
                .callTimeout(10, java.util.concurrent.TimeUnit.MINUTES).build() else http
            val downloadUrl = if (BuildConfig.MANAGED) panel.checkedUrl(u.apkUrl).toString() else u.apkUrl
            downloadHttp.newCall(Request.Builder().url(downloadUrl).build()).execute().use { r ->
                if (!r.isSuccessful) throw IOException("download failed (HTTP ${r.code})")
                val body = r.body!!
                val total = body.contentLength()
                val maxBytes = 256 * 1024 * 1024L
                if (BuildConfig.MANAGED && total > maxBytes) throw IOException("APK exceeds 256 MiB limit")
                body.byteStream().use { input -> file.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024); var done = 0L; var n: Int
                    while (input.read(buf).also { n = it } >= 0) {
                        done += n
                        if (BuildConfig.MANAGED && done > maxBytes) throw IOException("APK exceeds 256 MiB limit")
                        out.write(buf, 0, n)
                        if (total > 0) progress.value = context.getString(R.string.set_b_downloading_pct, (done * 100 / total).toInt())
                    }
                } }
            }
            if (BuildConfig.MANAGED) org.imdtv.iptvtv.data.management.verifyManagedApk(context, file, u)
            progress.value = context.getString(R.string.set_b_installing)
            val installer = context.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            if (BuildConfig.MANAGED) {
                params.setAppPackageName(context.packageName)
                if (Build.VERSION.SDK_INT >= 31) params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
            }
            val id = installer.createSession(params)
            sessionId = id
            installer.openSession(id).use { s ->
                s.openWrite(APK_NAME, 0, file.length()).use { out -> file.inputStream().use { it.copyTo(out) }; s.fsync(out) }
                // Mutable: the installer adds the status extras UpdateReceiver reads.
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
                reopenMarker(context).createNewFile()
                s.commit(PendingIntent.getBroadcast(context, id, Intent(context, UpdateReceiver::class.java), flags).intentSender)
                committed = true
            }
        } catch (e: Exception) {
            if (e is CancellationException) { progress.value = null; throw e }
            Log.w("Updater", "Update failed", e)
            failed(context.getString(R.string.set_b_update_failed_retry, e.message ?: e.javaClass.simpleName))
        } finally {
            if (!committed) {
                sessionId?.let { runCatching { context.packageManager.packageInstaller.abandonSession(it) } }
                reopenMarker(context).delete()
            }
            file.delete()
            installLock.unlock()
        }
    }

    internal fun failed(message: String) { progress.value = null; error.value = message; prompt.value = true }

    /** The installer is up (or was cancelled). The prompt stays open behind it: Android TV closes the installer when the
     *  user goes to Settings to allow installs, and they come back to Update now. */
    internal fun waitingForUser() { progress.value = null }
}

/** Exists while a one-click update is installing: BootReceiver reopens the app once it's replaced. */
fun reopenMarker(context: Context) = File(context.noBackupFilesDir, "reopen-after-update")

/** Release notes as plain text: no Markdown headings, bold or links' brackets, at most ~[max] characters. */
internal fun plainNotes(md: String, max: Int = 600): String = md.lines()
    .map { it.trimStart('#', ' ').replace("**", "").replace(Regex("""\[([^\]]+)\]\([^)]*\)"""), "$1") }
    .joinToString("\n").trim().let { if (it.length > max) it.take(max).substringBeforeLast('\n') + "\n…" else it }

/** Android's installer reports here: show its confirm screen, or say why the install didn't happen. */
class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val updater = (context.applicationContext as IptvApp).container.updater
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION") val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                updater.waitingForUser()
                context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            PackageInstaller.STATUS_SUCCESS -> updater.waitingForUser() // the app restarts as the new version
            PackageInstaller.STATUS_FAILURE_ABORTED -> updater.waitingForUser() // the user pressed Cancel
            else -> updater.failed(context.getString(R.string.set_b_install_failed, intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: context.getString(R.string.set_b_install_error, status)))
        }
    }
}
