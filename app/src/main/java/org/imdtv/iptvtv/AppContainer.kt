package org.imdtv.iptvtv

import android.content.Context
import kotlinx.coroutines.launch
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import org.imdtv.iptvtv.data.db.AppDatabase
import org.imdtv.iptvtv.data.db.ProviderEntity
import org.imdtv.iptvtv.data.db.ProviderType
import org.imdtv.iptvtv.data.epg.EpgImporter
import org.imdtv.iptvtv.data.epg.EpgMatcher
import org.imdtv.iptvtv.data.epg.EpgShareSources
import org.imdtv.iptvtv.data.epg.XmltvParser
import org.imdtv.iptvtv.data.repo.CatalogueSync
import org.imdtv.iptvtv.data.catalogue.tvLanguage
import org.imdtv.iptvtv.data.catalogue.countriesOfLanguage
import kotlinx.coroutines.flow.first
import org.imdtv.iptvtv.data.repo.ProviderRepository
import org.imdtv.iptvtv.data.logos.LogoRepository
import org.imdtv.iptvtv.data.logos.LogoPackRepository
import org.imdtv.iptvtv.data.settings.LogoKind
import org.imdtv.iptvtv.data.settings.SettingsRepository
import org.imdtv.iptvtv.data.source.M3uSource
import org.imdtv.iptvtv.data.source.PlaylistSource
import org.imdtv.iptvtv.data.source.XtreamSource
import org.imdtv.iptvtv.data.source.xtreamJson
import org.imdtv.iptvtv.player.PlayerManager
import java.util.concurrent.TimeUnit

/** Manual dependency wiring. */
class AppContainer(context: Context) {
    val appContext: Context = context.applicationContext
    val db: AppDatabase by lazy { AppDatabase.create(appContext) }
    val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }
    val json: Json = xtreamJson()
    val prefs: DataStore<Preferences> by lazy {
        PreferenceDataStoreFactory.create { appContext.preferencesDataStoreFile("settings") }
    }

    /** A fresh source per call: M3U caches its parse per instance, i.e. per sync. */
    fun sourceFor(p: ProviderEntity): PlaylistSource = when (p.type) {
        ProviderType.XTREAM -> XtreamSource(
            p.url, p.username.orEmpty(), p.password.orEmpty(), p.streamFormat, http, json, p.userAgent,
        )
        ProviderType.M3U -> M3uSource(p.url, p.epgOverrideUrls, http, p.userAgent)
        ProviderType.STALKER -> org.imdtv.iptvtv.data.source.StalkerSource(p.id, stalkerLinks.client(p))
    }

    /** Portal sessions per playlist, shared by sync, playback and recording. */
    val stalkerLinks by lazy { org.imdtv.iptvtv.data.source.StalkerLinks(http) { db.providerDao().get(it) } }

    val catalogueSync: CatalogueSync by lazy {
        CatalogueSync(db, keepLanguage = { if (settings.settings.first().matchTvLanguage) tvLanguage() else null },
            mergeOptions = { with(settings.settings.first()) { org.imdtv.iptvtv.data.repo.MergeOptions(mergeChannels, mergeAcross, mergeBy == org.imdtv.iptvtv.data.settings.MergeBy.RESOLUTION) } })
    }
    val providerRepository: ProviderRepository by lazy {
        // Logo matching runs after the sync result is shown, and one step failing doesn't skip the others.
        ProviderRepository(db, catalogueSync, ::sourceFor, prefs, validateProvider = { managedServers.validate(it) }, afterSync = { id ->
            background.launch {
                fun failed(what: String, e: Exception) = android.util.Log.w("AppContainer", "$what after sync failed: $e")
                try { logoPack.rematch(id) } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e; failed("pack rematch", e) }
                try { if (LogoKind.OPEN_SOURCE in settings.settings.first().logoOrder) logos.refreshBuiltin(id) } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e; failed("tv-logos match", e)
                }
                try { logos.pruneCustom() } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e; failed("logo clean-up", e) }
            }
        })
    }
    /** App-lifetime work that must not hold up the caller (logo matching after a sync). */
    val background = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)
    /** tv-logos list, packs in `<providerId>/` and hand-picked logos in `custom/` share this folder. */
    private val logoDir by lazy { java.io.File(appContext.filesDir, "logos") }
    val logos: LogoRepository by lazy { LogoRepository(db, http, logoDir) }
    val logoPack: LogoPackRepository by lazy { LogoPackRepository(db, logoDir) }
    val settings: SettingsRepository by lazy { SettingsRepository(prefs) }

    /** Parental controls: the app-wide PIN gate (live, movies, series, Settings). */
    val pin: org.imdtv.iptvtv.data.security.PinGate by lazy {
        org.imdtv.iptvtv.data.security.PinGate().also { g ->
            // Read the saved settings before anything can ask the gate: the default AppSettings has no PIN, so on a
            // slow TV box Settings or an adult channel could slip through in the window before the first flow value.
            // One small preferences file, read once; the collect below keeps it current afterwards.
            g.current = kotlinx.coroutines.runBlocking { settings.settings.first() }
            background.launch {
                var lastPin = ""
                var first = true
                suspend fun autoLock() {
                    val adult = db.providerDao().ids().flatMap { db.categoryDao().allKinds(it) }
                        .filter { org.imdtv.iptvtv.data.security.PinGate.looksAdult(it.name) }.map { it.id }.toSet()
                    settings.update { it.copy(lockSettings = true, lockedCategories = it.lockedCategories + adult) }
                }
                settings.settings.collect { s ->
                    val justSet = !first && s.parentalPin.isNotEmpty() && lastPin.isEmpty()
                    if (s.parentalPin != lastPin) { g.relock(); lastPin = s.parentalPin }
                    g.current = s
                    // Lock Settings and every adult-looking category when a PIN is first set, and once at startup for a
                    // PIN saved before auto-lock existed that still protects nothing. Not on later edits, so a user who
                    // deliberately clears every lock keeps that choice.
                    if (justSet || (first && s.parentalPin.isNotEmpty() && s.lockedCategories.isEmpty() && !s.lockSettings)) launch { autoLock() }
                    first = false
                }
            }
        }
    }

    /** Opt-in playback totals waiting for the next diagnostics upload (channel display names only, no account data). */
    val playbackTotals = java.util.concurrent.ConcurrentHashMap<String, PlayerManager.ChannelStats>()
    /** Moves the player's counters into [playbackTotals]; called periodically while the app is open. */
    fun collectPlaybackStats() {
        if (!player.isInitialized()) return
        playerManager.drainStats().forEach { (name, s) ->
            playbackTotals.merge(name, s) { a, b ->
                a.apply { seconds += b.seconds; stalls += b.stalls; underruns += b.underruns; droppedFrames += b.droppedFrames; errors += b.errors }
            }
        }
    }
    /** Takes up to [limit] channel totals for one upload (they keep counting from zero afterwards). */
    fun takePlaybackTotals(limit: Int = 200): Map<String, PlayerManager.ChannelStats> =
        playbackTotals.keys().asSequence().take(limit).mapNotNull { k -> playbackTotals.remove(k)?.let { k to it } }.toMap()
    /** An upload failed: the batch goes back so the next attempt sends it. */
    fun returnPlaybackTotals(batch: Map<String, PlayerManager.ChannelStats>) {
        batch.forEach { (name, s) ->
            playbackTotals.merge(name, s) { a, b ->
                a.apply { seconds += b.seconds; stalls += b.stalls; underruns += b.underruns; droppedFrames += b.droppedFrames; errors += b.errors }
            }
        }
    }
    val epgImporter: EpgImporter by lazy { EpgImporter(db, http, XmltvParser()) }
    val epgMatcher: EpgMatcher by lazy { EpgMatcher(db, settings = { settings.settings.first() }, ownUrls = { providerRepository.playlistGuideUrls(it) }) }
    val epgShare: EpgShareSources by lazy {
        // The TV language's countries (user, 2026-11-11): English → UK, US, CA, AU, IE, NZ (~22 MB), not every shown country (~32 MB).
        EpgShareSources(db, countries = { countriesOfLanguage(tvLanguage()) }) { url ->
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                http.newCall(okhttp3.Request.Builder().url(url).build()).execute().use { r ->
                    if (!r.isSuccessful) throw java.io.IOException("epgshare01: HTTP ${r.code}")
                    r.body!!.string()
                }
            }
        }
    }

    val backup: org.imdtv.iptvtv.data.backup.BackupRepository by lazy {
        org.imdtv.iptvtv.data.backup.BackupRepository(db, settings, providerRepository)
    }

    val sync: org.imdtv.iptvtv.data.sync.SyncManager by lazy {
        org.imdtv.iptvtv.data.sync.SyncManager(appContext, prefs, backup, afterMirror = { r ->
            // New playlists get matched by the guide import; the rest only when the matcher's inputs changed.
            if (r.added.isNotEmpty()) org.imdtv.iptvtv.work.EpgRefreshWorker.runNow(appContext)
            else if (r.guideChanged) db.providerDao().ids().forEach { epgMatcher.rematch(it) }
        }, background)
    }

    val panel: org.imdtv.iptvtv.data.management.PanelClient by lazy { org.imdtv.iptvtv.data.management.PanelClient(http) }
    val managedServers: org.imdtv.iptvtv.data.management.ManagedServerRepository by lazy {
        org.imdtv.iptvtv.data.management.ManagedServerRepository(appContext, panel, db)
    }
    /** Last known Xtream account expiry (epoch seconds), null for non-Xtream or unavailable. */
    val accountExpiry = kotlinx.coroutines.flow.MutableStateFlow<Long?>(null)
    /** Whether the account check is currently running. */
    val accountExpiryRefreshing = kotlinx.coroutines.flow.MutableStateFlow(false)
    /** Time of the most recent account check, including failed checks. */
    val accountExpiryCheckedAt = kotlinx.coroutines.flow.MutableStateFlow<Long?>(null)
    /** Playlist to which [accountExpiry] and [accountExpiryCheckedAt] belong. */
    val accountExpiryCheckedProviderId = kotlinx.coroutines.flow.MutableStateFlow<Long?>(null)
    private var accountExpiryProviderId: Long? = null
    suspend fun refreshAccountExpiry() {
        if (accountExpiryRefreshing.value) return
        accountExpiryRefreshing.value = true
        try {
            val id = providerRepository.activeProviderId.first() ?: run {
                accountExpiryProviderId = null
                accountExpiryCheckedProviderId.value = null
                accountExpiry.value = null
                accountExpiryCheckedAt.value = null
                return
            }
            if (id != accountExpiryProviderId) {
                accountExpiryProviderId = id
                accountExpiryCheckedProviderId.value = id
                accountExpiry.value = null
                accountExpiryCheckedAt.value = null
            }
            val p = db.providerDao().get(id)
            if (p?.type != ProviderType.XTREAM) accountExpiry.value = null
            else (sourceFor(p) as XtreamSource).accountExpiry()?.let { accountExpiry.value = it }
            accountExpiryCheckedAt.value = System.currentTimeMillis()
        } finally {
            accountExpiryRefreshing.value = false
        }
    }

    val announcements: org.imdtv.iptvtv.data.management.AnnouncementRepository by lazy {
        org.imdtv.iptvtv.data.management.AnnouncementRepository(panel, prefs)
    }
    val updater: org.imdtv.iptvtv.data.update.Updater by lazy { org.imdtv.iptvtv.data.update.Updater(appContext, http, json, prefs) }

    val search: org.imdtv.iptvtv.data.search.SearchRepository by lazy { org.imdtv.iptvtv.data.search.SearchRepository(db) }

    private val player = lazy {
        PlayerManager(appContext).also { p ->
            p.guard = { retry -> recordings.blocksWatching { main.post(retry) } }
            p.resolveLink = stalkerLinks::resolve
            p.smbSettings = { smbSettings(kotlinx.coroutines.runBlocking { settings.settings.first() }) } // on the player's loader thread
        }
    }
    /** First access must be on the main thread. */
    val playerManager: PlayerManager by player
    /** First access on the main thread, like the player (first played movie or episode). */
    val watchProgress by lazy { org.imdtv.iptvtv.player.WatchProgress(playerManager, db.progressDao(), background) }

    /** The player is showing a provider stream (read from any thread; false before the player exists). */
    fun isWatching(): Boolean = player.isInitialized() && playerManager.playingUrl.value?.let { !org.imdtv.iptvtv.recording.isRecordingLocation(it) } == true

    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    /** Last known stream limit per playlist (Xtream max_connections), for the player guard. */
    private val limits = java.util.concurrent.ConcurrentHashMap<Long, Int>()
    @Volatile private var activeId: Long? = null

    val recordingScheduler: org.imdtv.iptvtv.recording.RecordingScheduler by lazy {
        org.imdtv.iptvtv.recording.RecordingScheduler(appContext, db.recordingDao(), startNow = { recordings.onStart(it) }) // through the gate, as the alarm would
    }

    val recordings: org.imdtv.iptvtv.recording.RecordingRepository by lazy {
        background.launch {
            db.providerDao().all().forEach { p -> p.maxConnections?.let { limits[p.id] = it } }
            providerRepository.activeProviderId.collect { activeId = it }
        }
        org.imdtv.iptvtv.recording.RecordingRepository(
            db, settings = { settings.settings.first() }, arm = recordingScheduler::arm, disarm = recordingScheduler::cancel,
            limitOf = ::refreshLimit, storedLimit = { limits[it] }, activeProviderId = { activeId },
            startService = { org.imdtv.iptvtv.recording.RecordingService.start(appContext, it) },
            stopService = { org.imdtv.iptvtv.recording.RecordingService.stop(appContext, it) },
            stopPlayer = { main.post { if (player.isInitialized()) playerManager.stop() } },
            watching = ::isWatching, scope = background,
        )
    }

    /** Xtream: asks the provider for the login's stream limit and keeps it; falls back to the last known one. */
    private suspend fun refreshLimit(providerId: Long): Int? {
        val p = db.providerDao().get(providerId) ?: return null
        val source = sourceFor(p) as? XtreamSource ?: return null
        val fresh = source.maxConnections()
        if (fresh != null && fresh != p.maxConnections) db.providerDao().setMaxConnections(providerId, fresh)
        return (fresh ?: p.maxConnections)?.also { limits[providerId] = it }
    }

    /** Where Save to points now; null = USB chosen but no drive is plugged in. */
    fun recordingStore(s: org.imdtv.iptvtv.data.settings.AppSettings): org.imdtv.iptvtv.recording.RecordingStore? = when (s.recordTo) {
        org.imdtv.iptvtv.recording.RecStorage.INTERNAL -> org.imdtv.iptvtv.recording.FileStore(s.recordTo, org.imdtv.iptvtv.recording.internalDir(appContext))
        org.imdtv.iptvtv.recording.RecStorage.USB -> org.imdtv.iptvtv.recording.usbDirs(appContext)
            .let { dirs -> dirs.firstOrNull { it.path == s.recordUsbPath } ?: dirs.firstOrNull() }
            ?.let { org.imdtv.iptvtv.recording.FileStore(s.recordTo, it) }
        org.imdtv.iptvtv.recording.RecStorage.SMB -> org.imdtv.iptvtv.recording.SmbStore(smbSettings(s))
    }

    /** Removes a recording's file wherever it is (box, USB drive or network share). */
    suspend fun deleteRecordingFile(r: org.imdtv.iptvtv.recording.RecordingEntity) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val loc = r.location ?: return@withContext
        if (loc.startsWith("smb://")) org.imdtv.iptvtv.recording.SmbStore(smbSettings(settings.settings.first())).delete(loc) else java.io.File(loc).delete()
    }

    fun smbSettings(s: org.imdtv.iptvtv.data.settings.AppSettings) =
        org.imdtv.iptvtv.recording.SmbSettings(s.smbServer.trim(), s.smbShare.trim(), s.smbFolder.trim(), s.smbUser.trim(), s.smbPassword)
}
