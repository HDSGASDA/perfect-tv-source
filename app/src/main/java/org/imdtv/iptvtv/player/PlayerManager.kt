package org.imdtv.iptvtv.player

import org.imdtv.iptvtv.data.source.uiText
import org.imdtv.iptvtv.R
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.video.VideoRendererEventListener
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import org.imdtv.iptvtv.data.security.Login
import org.imdtv.iptvtv.data.source.Drm
import org.imdtv.iptvtv.data.source.DrmException
import org.imdtv.iptvtv.data.source.DrmScheme
import org.imdtv.iptvtv.data.source.DrmSpec
import org.imdtv.iptvtv.data.source.drmSpec
import org.imdtv.iptvtv.data.source.isStalkerLink
import kotlinx.coroutines.launch
import androidx.media3.exoplayer.drm.DefaultDrmSessionManager
import androidx.media3.exoplayer.drm.DefaultDrmSessionManagerProvider
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.drm.FrameworkMediaDrm
import androidx.media3.exoplayer.drm.LocalMediaDrmCallback
import org.imdtv.iptvtv.data.security.withLogin
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Format
import androidx.media3.common.Tracks
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import org.imdtv.iptvtv.data.settings.AudioDecoder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** The app's single ExoPlayer. Create and use on the main thread. */
@OptIn(UnstableApi::class)
class PlayerManager(private val context: Context) {
    /** The mode [player] was built with. A change waits until the player is idle (spec §4.4). */
    var audioDecoder: AudioDecoder = AudioDecoder.SOFTWARE
        private set
    private var wantedDecoder = audioDecoder

    /** Playback › Connection profile; like the decoder, a change rebuilds the player when idle. */
    var connectionProfile: org.imdtv.iptvtv.data.settings.ConnectionProfile = org.imdtv.iptvtv.data.settings.ConnectionProfile.STABLE
        private set
    private var wantedProfile = connectionProfile

    /** Rebuilt when the audio decoder or connection profile changes: read it at use time, don't keep a reference. */
    var player: ExoPlayer = build(audioDecoder, connectionProfile)
        private set

    private fun build(d: AudioDecoder, profile: org.imdtv.iptvtv.data.settings.ConnectionProfile): ExoPlayer = ExoPlayer.Builder(
        context,
        // SOFTWARE (PREFER): the FFmpeg audio renderer handles AC3/E-AC3/DTS that many TV boxes can't decode.
        // HARDWARE (ON): the platform decoders or passthrough to the TV/receiver first; FFmpeg only for audio the box can't
        // play (with OFF, Dolby channels were silent or failed on boxes/TVs without Dolby; Reddit, 2026-11-19).
        // Video stays on the hardware decoders either way: the FFmpeg artifact also ships a software video renderer.
        object : DefaultRenderersFactory(context) {
            override fun buildVideoRenderers(
                context: Context, extensionRendererMode: Int, mediaCodecSelector: MediaCodecSelector,
                enableDecoderFallback: Boolean, eventHandler: Handler, eventListener: VideoRendererEventListener,
                allowedVideoJoiningTimeMs: Long, out: ArrayList<Renderer>,
            ) { out += StreamAspectVideoRenderer(context, codecAdapterFactory, mediaCodecSelector, allowedVideoJoiningTimeMs,
                enableDecoderFallback, eventHandler, eventListener) }
        }.setExtensionRendererMode(
            if (d == AudioDecoder.SOFTWARE) DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER
            else DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON,
        ),
    ).setLoadControl(loadControl(profile)).build()

    fun setAudioDecoder(d: AudioDecoder) {
        wantedDecoder = d
        if (player.playbackState != Player.STATE_IDLE || d == audioDecoder) return
        // Idle with a stream (a failed one waiting to retry): the new player must load it again, or it stays empty.
        val u = url
        if (u != null) { this.url = null; reload(u, userAgent, login, vod, drm, null) } else applyDecoder()
    }

    fun setConnectionProfile(p: org.imdtv.iptvtv.data.settings.ConnectionProfile) {
        wantedProfile = p
        if (player.playbackState != Player.STATE_IDLE || p == connectionProfile) return
        val u = url
        if (u != null) { this.url = null; reload(u, userAgent, login, vod, drm, null) } else applyDecoder()
    }

    /** Swaps in a player built for [wantedDecoder]/[wantedProfile], keeping the listener and the attached view. */
    private fun applyDecoder() {
        if (wantedDecoder == audioDecoder && wantedProfile == connectionProfile) return
        val old = player
        old.removeListener(listener)
        old.removeAnalyticsListener(diagnostics)
        player = build(wantedDecoder, wantedProfile).also { it.addListener(listener); it.addAnalyticsListener(diagnostics); applySubtitles(it); applyAudioLanguage(it) }
        audioDecoder = wantedDecoder
        connectionProfile = wantedProfile
        view?.let { PlayerView.switchTargetView(player, null, it) }
        old.release()
        Log.i(TAG, "audio decoder $audioDecoder, profile $connectionProfile")
    }

    private var subtitlesOn = false
    private var liveSubtitlesOn = false
    private var subtitleLanguage = java.util.Locale.getDefault().language

    /** The Subtitles setting; [language] null = the TV's language. Applies now and to every title played after. */
    fun setSubtitles(on: Boolean, language: String?, liveOn: Boolean = false) {
        subtitlesOn = on
        liveSubtitlesOn = liveOn
        subtitleLanguage = language ?: java.util.Locale.getDefault().language
        applySubtitles(player)
    }

    /** Movies and series follow the Subtitles setting, live channels "Subtitles › Live TV". */
    private fun applySubtitles(p: ExoPlayer) {
        p.trackSelectionParameters = if (vod) subtitleParams(p.trackSelectionParameters, subtitlesOn, subtitleLanguage)
            else if (liveSubtitlesOn) liveSubtitleParams(p.trackSelectionParameters, subtitleLanguage)
            else liveOffParams(p.trackSelectionParameters)
    }

    // --- Subtitle appearance (Playback › Subtitles › size, position, background) ---

    private var subtitleStyle = androidx.media3.ui.CaptionStyleCompat.DEFAULT
    private var subtitleSizeFraction = .0533f
    private var subtitleBottomFraction = .08f

    /** Applies now (to the attached view) and to every view attached after. */
    fun setSubtitleAppearance(sizeFraction: Float, bottomFraction: Float, backgroundColor: Int) {
        subtitleSizeFraction = sizeFraction
        subtitleBottomFraction = bottomFraction
        // CaptionStyleCompat(foreground, background, window, edgeType, edgeColor, typeface): the edge type is the
        // fourth argument, not the fifth. Passing the colour there left an undefined edge type in the style.
        subtitleStyle = androidx.media3.ui.CaptionStyleCompat(
            android.graphics.Color.WHITE, backgroundColor, android.graphics.Color.TRANSPARENT,
            androidx.media3.ui.CaptionStyleCompat.EDGE_TYPE_NONE, android.graphics.Color.WHITE, android.graphics.Typeface.DEFAULT)
        applySubtitleStyle()
    }

    private fun applySubtitleStyle() {
        view?.subtitleView?.apply {
            setStyle(subtitleStyle)
            setFractionalTextSize(subtitleSizeFraction, false)
            setBottomPaddingFraction(subtitleBottomFraction)
        }
    }

    // --- Audio language preference and the audio-track picker ---

    private var audioLanguage: String? = null

    /** Playback › Audio language; null = the stream's own default. Applies now and to every stream played after. */
    fun setAudioLanguage(language: String?) {
        audioLanguage = language
        applyAudioLanguage(player)
    }

    private fun applyAudioLanguage(p: ExoPlayer) {
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon().setPreferredAudioLanguage(audioLanguage).build()
    }

    /** The audio tracks of the stream playing now, for the overlay's Audio track menu. */
    fun audioTracks(): List<AudioTrackOption> = player.currentTracks.groups.mapIndexedNotNull { gi, g ->
        if (g.type != C.TRACK_TYPE_AUDIO) null else (0 until g.length).map { ti ->
            val f = g.getTrackFormat(ti)
            AudioTrackOption(gi, ti, f.language, f.label, f.channelCount.takeIf { it != Format.NO_VALUE },
                f.codecs ?: f.sampleMimeType, g.isTrackSelected(ti))
        }
    }.flatten()

    /** Picks one track (the Audio track menu); stays until the next stream or [autoAudioTrack]. */
    fun selectAudioTrack(option: AudioTrackOption) {
        val g = player.currentTracks.groups.getOrNull(option.groupIndex) ?: return
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setOverrideForType(androidx.media3.common.TrackSelectionOverride(g.mediaTrackGroup, option.trackIndex)).build()
    }

    /** Back to automatic: the Audio language setting (or the stream's default) picks again. */
    fun autoAudioTrack() {
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_AUDIO).build()
        applyAudioLanguage(player)
    }

    // --- Sleep timer (Playback › Sleep timer; off by default) ---

    /** The setting: armed when a stream starts and no timer is running yet. */
    var defaultSleepMin: Int = 0

    private val _sleepEndsAt = MutableStateFlow<Long?>(null)
    /** When the running sleep timer fires (elapsedRealtime ms); null = no timer. */
    val sleepEndsAt: StateFlow<Long?> = _sleepEndsAt

    private val _sleepExpired = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    /** The timer fired: playback was stopped; the full-screen player leaves the screen. */
    val sleepExpired: SharedFlow<Unit> = _sleepExpired

    private val sleepRun = Runnable {
        _sleepEndsAt.value = null
        Log.i(TAG, "sleep timer expired")
        stop()
        _sleepExpired.tryEmit(Unit)
    }

    /** Minutes from now; 0 cancels. Survives zapping (one timer per viewing session). */
    fun setSleepTimer(minutes: Int) {
        handler.removeCallbacks(sleepRun)
        if (minutes <= 0) { _sleepEndsAt.value = null; return }
        val ms = minutes * 60_000L
        _sleepEndsAt.value = android.os.SystemClock.elapsedRealtime() + ms
        handler.postDelayed(sleepRun, ms)
    }

    // --- Live pause / instant replay (Playback › Live pause & instant replay) ---

    /** The setting; off clears the buffer and leaves replay if it is showing. */
    var instantReplayOn: Boolean = true
        set(v) { field = v; if (!v) leaveReplay(clearBuffer = true) }

    private val replayBuffer = ReplayBuffer(java.io.File(context.cacheDir, "replay"))
    private val _replaying = MutableStateFlow(false)
    /** The rolling buffer is on screen instead of the live stream (movie controls: pause and seek). */
    val replaying: StateFlow<Boolean> = _replaying
    private val _replayReady = MutableStateFlow(false)
    /** Enough of the channel is buffered to pause live (a few seconds at least). */
    val replayReady: StateFlow<Boolean> = _replayReady
    private var replaySeeked = false

    /** Pause live: play the rolling buffer from its newest point. False when nothing is buffered yet. */
    fun startReplay(): Boolean {
        if (_replaying.value || vod || url == null || !replayBuffer.ready()) return false
        val files = replayBuffer.sources()
        if (files.isEmpty()) return false
        replayBuffer.freeze() // the files must not change under the player
        handler.removeCallbacks(watchdog)
        handler.removeCallbacks(retry)
        resolving?.cancel()
        retries = 0
        _replaying.value = true
        _replayReady.value = false
        replaySeeked = false
        vod = true // seekable: no live watchdog, no reconnect path
        val progressive = androidx.media3.exoplayer.source.ProgressiveMediaSource.Factory(DefaultDataSource.Factory(context))
        player.setMediaSources(files.map { progressive.createMediaSource(MediaItem.fromUri(android.net.Uri.fromFile(it))) })
        player.playWhenReady = true
        player.prepare()
        Log.i(TAG, "instant replay: ${files.size} files, ${replayBuffer.totalBytes() / (1024 * 1024)} MB")
        return true
    }

    /** Leave the buffer and rejoin the live stream at its edge. */
    fun backToLive() {
        if (!_replaying.value) return
        val u = url ?: return leaveReplay(clearBuffer = true)
        leaveReplay(clearBuffer = false)
        this.url = null // force play() to load again (it skips a URL already "playing")
        player.stop(); player.clearMediaItems()
        play(u, userAgent, login, false, drm, guarded = false)
    }

    private fun leaveReplay(clearBuffer: Boolean) {
        _replaying.value = false
        replaySeeked = false
        if (clearBuffer) replayBuffer.clear() else replayBuffer.thaw()
    }
    /** The stream loaded now is a movie/episode (else live). */
    private var vod = false

    private val _errors = MutableSharedFlow<PlaybackException>(extraBufferCapacity = 1)
    val errors: SharedFlow<PlaybackException> = _errors

    private val _ended = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    /** A movie/episode played to its end (not live, not the replay buffer): the next-episode countdown starts here. */
    val ended: SharedFlow<Unit> = _ended

    private val handler = Handler(Looper.getMainLooper())
    private var url: String? = null
        set(v) { field = v; _playingUrl.value = v }
    private val _playingUrl = MutableStateFlow<String?>(null)
    /** The stream loaded now; null once stopped (including while the app is in the background). */
    val playingUrl: StateFlow<String?> = _playingUrl

    private val _buffering = MutableStateFlow(false)
    /** Loading (a new stream starting or a rebuffer): full screen shows a spinner instead of a bare black screen. */
    val buffering: StateFlow<Boolean> = _buffering

    private val _formats = MutableStateFlow(StreamFormats.UNKNOWN)
    /** The selected video/audio formats of the stream playing now; all-null after [play] of a new URL and [stop]. */
    val formats: StateFlow<StreamFormats> = _formats

    private fun readFormats() {
        fun selected(type: Int): Format? = player.currentTracks.groups.firstOrNull { it.type == type && it.isSelected }
            ?.let { g -> (0 until g.length).firstOrNull { g.isTrackSelected(it) }?.let { g.getTrackFormat(it) } }
        val v = selected(C.TRACK_TYPE_VIDEO)
        val a = selected(C.TRACK_TYPE_AUDIO)
        val size = player.videoSize.height.takeIf { it > 0 && v != null } // the old stream's size lingers until the new one decodes
        val f = StreamFormats(
            height = size ?: v?.height?.takeIf { it != Format.NO_VALUE },
            frameRate = v?.frameRate?.takeIf { it > 0f },
            audioChannels = a?.channelCount?.takeIf { it != Format.NO_VALUE },
        )
        if (f != _formats.value) Log.i(TAG, "formats $f ${a?.sampleMimeType} stream par ${v?.pixelWidthHeightRatio} ${tail(url)}")
        _formats.value = f
    }
    private var userAgent: String? = null
    private var retries = 0
    // A portal link is usually good for one connection: a retry asks the portal again.
    private val retry = Runnable { if (url?.let(::isStalkerLink) == true) start() else player.prepare() }

    /** Turns a saved `stalker://` link into a playable one (set by the app container); may throw with a message for the user. */
    var resolveLink: suspend (String) -> String = { it }
    private val scope = kotlinx.coroutines.MainScope()
    private var resolving: kotlinx.coroutines.Job? = null
    private var view: PlayerView? = null

    private val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                Log.i(TAG, "state ${arrayOf("?", "IDLE", "BUFFERING", "READY", "ENDED")[state]} ${tail(url)}")
                if (state == Player.STATE_READY) retries = 0
                if (state == Player.STATE_ENDED && vod && !_replaying.value) _ended.tryEmit(Unit)
                // Instant replay opens at the newest buffered point, not minutes back at the buffer's start.
                if (state == Player.STATE_READY && _replaying.value && !replaySeeked) {
                    replaySeeked = true
                    val d = player.duration
                    if (d != C.TIME_UNSET && d > 5_000) player.seekTo(d - 2_000)
                }
                _buffering.value = state == Player.STATE_BUFFERING
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) = onPlayingChanged(isPlaying)

            override fun onTracksChanged(tracks: Tracks) = readFormats()

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (videoSize.width > 0) Log.i(TAG, "video ${videoSize.width}x${videoSize.height} par ${videoSize.pixelWidthHeightRatio} ${tail(url)}")
                readFormats()
            }

            override fun onPlayerError(error: PlaybackException) = streamError(error)

            override fun onRenderedFirstFrame() {
                if (attachedAt > 0) Log.i(TAG, "first frame ${android.os.SystemClock.elapsedRealtime() - attachedAt} ms after attach")
                attachedAt = 0
            }
        }
    private var attachedAt = 0L

    @androidx.annotation.VisibleForTesting
    internal fun streamError(error: PlaybackException) {
        Log.w(TAG, "error ${error.errorCodeName} ${tail(url)} (retry ${retries + 1}/$MAX_RETRIES)", error)
        stat { it.errors++ }
        fallback?.let { f -> fallback = null; Log.i(TAG, "trying the other link form"); return play(f, userAgent, login, vod, drm, guarded = false) }
        // A backup stream (or another merged source) jumps the queue: the viewer keeps watching instead of an error card.
        if (!_replaying.value && failover?.invoke() == true) return
        if (!scheduleRetry()) _errors.tryEmit(error)
    }

    /** Set by the live view model: play a backup of the current channel; true = taken over (no error shown, no retry). */
    var failover: (() -> Boolean)? = null

    /** Queues the next retry (1s, 2s, 4s); false once they are used up. */
    private fun scheduleRetry(): Boolean {
        if (retries >= MAX_RETRIES) { retries = 0; return false }
        handler.postDelayed(retry, 1000L shl retries++)
        return true
    }

    init { player.addListener(listener) }

    // Silent hiccups (users' "jumps back a second", 2026-11-18): a dropped connection that Media3 retries by itself.
    private val diagnostics = object : androidx.media3.exoplayer.analytics.AnalyticsListener {
        override fun onLoadError(t: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime, l: androidx.media3.exoplayer.source.LoadEventInfo,
            m: androidx.media3.exoplayer.source.MediaLoadData, e: java.io.IOException, wasCanceled: Boolean) =
            Log.w(TAG, "load error (retried by the player) ${tail(url)}: $e").let { }
        override fun onAudioUnderrun(t: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime, bufferSize: Int, bufferSizeMs: Long, elapsedSinceLastFeedMs: Long) {
            Log.w(TAG, "audio underrun ${elapsedSinceLastFeedMs} ms since last feed ${tail(url)}")
            stat { it.underruns++ }
        }
        override fun onDroppedVideoFrames(t: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime, droppedFrames: Int, elapsedMs: Long) {
            Log.w(TAG, "dropped $droppedFrames video frames ${tail(url)}")
            stat { it.droppedFrames += droppedFrames }
        }
        override fun onAudioDecoderInitialized(t: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime, decoderName: String,
            initializedTimestampMs: Long, initializationDurationMs: Long) =
            Log.i(TAG, "audio decoder $decoderName ($audioDecoder setting)").let { }
    }
    init { player.addAnalyticsListener(diagnostics) }

    // --- Playback diagnostics (opt-in upload to the panel; counted always, sent only when asked) ---

    /** Per-channel totals since the last [drainStats]; the channel's display name is set by the view models. */
    class ChannelStats {
        var seconds = 0L; var stalls = 0; var underruns = 0; var droppedFrames = 0; var errors = 0
        fun copy() = ChannelStats().also { it.seconds = seconds; it.stalls = stalls; it.underruns = underruns; it.droppedFrames = droppedFrames; it.errors = errors }
    }
    var channelLabel: String? = null
    private val stats = java.util.concurrent.ConcurrentHashMap<String, ChannelStats>()
    private fun stat(f: (ChannelStats) -> Unit) { channelLabel?.let { f(stats.getOrPut(it) { ChannelStats() }) } }
    /** Takes the totals collected so far (and starts counting again); empty when nothing played. */
    fun drainStats(): Map<String, ChannelStats> {
        val out = stats.entries.associate { it.key to it.value.copy() }
        stats.clear()
        return out
    }
    // Watched seconds: a coarse 5 s tick while actually playing (no per-frame work).
    private val statsTick = object : Runnable {
        override fun run() {
            if (player.isPlaying) stat { it.seconds += 5 }
            _replayReady.value = !_replaying.value && !vod && replayBuffer.ready()
            handler.postDelayed(this, 5_000)
        }
    }
    init { handler.postDelayed(statsTick, 5_000) }

    /** [login] fills the Xtream placeholders in [url] (saved links hold no login); [url] itself stays the key for comparisons. */
    /** [startMs]: a movie/episode starts there (resume), applied when the stream loads, also after the guard or a portal link. */
    /** [fallbackUrl]: played instead when [url] fails (catch-up's second link form), without the usual retries first. */
    fun play(url: String, userAgent: String?, login: Login? = null, vod: Boolean = false, drm: Drm? = null, startMs: Long = 0,
             fallbackUrl: String? = null) {
        fallback = fallbackUrl
        play(url, userAgent, login, vod, drm, guarded = true, startMs)
    }
    private var fallback: String? = null

    /** Asked before a provider stream starts (a recording may hold the login's only connection); true = not now. It
     *  gets a retry that plays without asking again. */
    var guard: ((retry: () -> Unit) -> Boolean)? = null

    private fun play(url: String, userAgent: String?, login: Login?, vod: Boolean, drm: Drm?, guarded: Boolean, startMs: Long = 0) {
        if (url == this.url && player.playbackState != Player.STATE_IDLE) { player.playWhenReady = true; return }
        if (guarded && guard?.invoke { play(url, userAgent, login, vod, drm, guarded = false, startMs) } == true) return
        failedUrl = null
        startAt = startMs
        handler.removeCallbacks(retry)
        retries = 0
        stallReloads = 0
        applyDecoder() // a new stream: a pending decoder/profile change takes effect now, before playWhenReady is set on it
        player.playWhenReady = true
        this.vod = vod
        // A new title follows the settings again: last title's subtitles/audio-button choices are dropped.
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().clearOverridesOfType(C.TRACK_TYPE_AUDIO).build()
        applySubtitles(player)
        applyAudioLanguage(player)
        if (defaultSleepMin > 0 && _sleepEndsAt.value == null) setSleepTimer(defaultSleepMin)
        leaveReplay(clearBuffer = true)
        this.url = url
        this.userAgent = userAgent
        this.login = login
        this.drm = drm
        _formats.value = StreamFormats.UNKNOWN
        Log.i(TAG, "play ${tail(url)}${if (vod) " (vod)" else ""}${drm?.let { " drm=${it.type}" }.orEmpty()}")
        start()
        stall.reset()
        handler.removeCallbacks(watchdog)
        if (!vod) handler.postDelayed(watchdog, WATCH_MS)
    }

    private val stall = StallClock(START_STALL_MS, STALL_MS)
    private var stallReloads = 0
    // A live stream can freeze with no error (picture and sound stop); loading it again is what fixed it by hand.
    private val watchdog = object : Runnable {
        override fun run() {
            if (url == null || vod) return
            handler.postDelayed(this, WATCH_MS)
            // IDLE = a retry is pending; not playing = paused on purpose.
            if (player.playbackState == Player.STATE_IDLE || !player.playWhenReady) return stall.reset()
            if (stall.update(player.currentPosition, android.os.SystemClock.elapsedRealtime())) {
                Log.w(TAG, "stalled in ${arrayOf("?", "IDLE", "BUFFERING", "READY", "ENDED")[player.playbackState]}, reloading ${tail(url)}")
                stat { it.stalls++ }
                stall.reset() // the reconnect gets the start limit, or a slow provider would be reloaded in a loop
                // Three freezes in a row on the same stream: a backup takes over if the channel has one.
                if (++stallReloads == STALLS_BEFORE_FAILOVER && failover?.invoke() == true) return
                start()
            }
        }
    }

    /** Loads the current [url]; a portal link is resolved first, and dropped if another stream was asked for meanwhile. */
    private fun start() {
        val url = url ?: return
        resolving?.cancel()
        if (!isStalkerLink(url)) return load(url)
        player.stop() // the last stream mustn't keep playing (or hold the connection) while the portal answers
        resolving = scope.launch {
            val real = try { resolveLink(url) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
                Log.w(TAG, "link not resolved ${tail(url)}: $e")
                // In a retry the portal may be down with the stream: keep retrying (asking it again) like any lost stream.
                if (this@PlayerManager.url == url && (retries == 0 || !scheduleRetry()))
                    fail(e.message ?: context.getString(R.string.live_err_no_stream_link), e, PlaybackException.ERROR_CODE_IO_UNSPECIFIED)
                return@launch
            }
            if (this@PlayerManager.url == url) load(real)
        }
    }

    /** [address]: the saved link, or what a portal link resolved to. */
    private fun load(address: String) {
        // Headers on the link (M3U #EXTVLCOPT or Kodi "url|User-Agent=…") win over the playlist's user agent.
        val req = org.imdtv.iptvtv.data.source.streamRequest(address, userAgent, login)
        // Some providers send live streams in bursts ~10 s apart: Media3's 8 s default dropped the connection in each gap.
        val http = DefaultHttpDataSource.Factory().setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000).setReadTimeoutMs(READ_TIMEOUT_MS)
        req.headers["User-Agent"]?.let { http.setUserAgent(it) }
        (req.headers - "User-Agent").takeIf { it.isNotEmpty() }?.let { http.setDefaultRequestProperties(it) }
        val spec = try { drm?.let(::drmSpec) } catch (e: DrmException) {
            return fail((e.uiText(context) ?: e.message!!), e, PlaybackException.ERROR_CODE_DRM_SCHEME_UNSUPPORTED)
        }
        // A live .ts stream reconnects by itself when the provider ends or stalls the connection (LiveTsDataSource).
        val liveTs = !vod && spec == null && android.net.Uri.parse(req.url).lastPathSegment?.endsWith(".ts", ignoreCase = true) == true
        var upstream = if (liveTs) androidx.media3.datasource.DataSource.Factory { LiveTsDataSource(http.createDataSource()) } else http
        // Instant replay: tee the bytes this same connection reads into the rolling buffer (no second provider connection).
        if (!vod && spec == null && instantReplayOn) {
            val inner = upstream
            upstream = androidx.media3.datasource.DataSource.Factory { ReplayTeeDataSource(inner.createDataSource(), replayBuffer) }
        }
        val factory = DefaultMediaSourceFactory(DefaultDataSource.Factory(context, upstream))
        spec?.let { factory.setDrmSessionManagerProvider(drmSessions(it, http)) }
        // A stream format with no module in the app (e.g. Smooth Streaming) throws here: report it, don't crash.
        val source = try {
            factory.createMediaSource(mediaItem(req.url, spec))
        } catch (e: IllegalStateException) {
            Log.w(TAG, "unsupported stream format ${tail(url)}", e)
            return fail(context.getString(R.string.live_err_unsupported_format), e, PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED)
        }
        val at = startAt; startAt = 0 // once: a later reload of this stream (retry) is not a resume
        if (at > 0) player.setMediaSource(source, at) else player.setMediaSource(source)
        player.prepare()
    }
    private var startAt = 0L
    private var failedUrl: String? = null

    /** Lets the viewer retry after the automatic retries have been exhausted. */
    fun retryFailed() {
        val target = failedUrl ?: url ?: return
        val ua = userAgent
        val l = login
        val isVod = vod
        val d = drm
        failedUrl = null
        if (url != null) {
            handler.removeCallbacks(retry)
            url = null
            player.stop()
            player.clearMediaItems()
        }
        play(target, ua, l, isVod, d, guarded = false)
    }

    /** Ends the start of the current stream with [message] shown to the user (see [StreamProblem]). */
    private fun fail(message: String, cause: Throwable?, code: Int) {
        failedUrl = url
        url = null
        player.stop(); player.clearMediaItems()
        _errors.tryEmit(PlaybackException(message, StreamProblem(message, cause), code))
    }

    private fun mediaItem(uri: String, spec: DrmSpec?): MediaItem = MediaItem.Builder().setUri(uri).apply {
        // Low latency: stay a few seconds behind the live edge (HLS/DASH; a raw .ts stream is already at the edge).
        if (!vod && connectionProfile == org.imdtv.iptvtv.data.settings.ConnectionProfile.LOW_LATENCY) {
            setLiveConfiguration(MediaItem.LiveConfiguration.Builder()
                .setTargetOffsetMs(4_000).setMinOffsetMs(2_000).setMaxOffsetMs(8_000)
                .setMinPlaybackSpeed(0.97f).setMaxPlaybackSpeed(1.03f).build())
        }
        spec ?: return@apply
        setDrmConfiguration(MediaItem.DrmConfiguration.Builder(if (spec.scheme == DrmScheme.WIDEVINE) C.WIDEVINE_UUID else C.CLEARKEY_UUID).apply {
            // The playlist's licence server wins over one named in the manifest, as in Kodi.
            spec.licenseUrl?.let { setLicenseUri(it); setForceDefaultLicenseUri(true) }
            setLicenseRequestHeaders(spec.headers)
        }.build())
    }.build()

    /** ClearKey keys from the playlist are answered locally; a licence server is asked with the stream's headers. */
    private fun drmSessions(spec: DrmSpec, http: DefaultHttpDataSource.Factory): DrmSessionManagerProvider =
        spec.keysJson?.let { keys ->
            DrmSessionManagerProvider {
                DefaultDrmSessionManager.Builder().setUuidAndExoMediaDrmProvider(C.CLEARKEY_UUID, FrameworkMediaDrm.DEFAULT_PROVIDER)
                    .build(LocalMediaDrmCallback(keys.toByteArray()))
            }
        } ?: DefaultDrmSessionManagerProvider().apply { setDrmHttpDataSourceFactory(http) }

    /** Network-share login for smb:// recordings (set by the app container). */
    var smbSettings: () -> org.imdtv.iptvtv.recording.SmbSettings = { org.imdtv.iptvtv.recording.SmbSettings("", "", "", "", "") }

    /** A recording (file path or smb://) from [startMs]; no guard: it uses no provider connection. */
    fun playRecording(location: String, startMs: Long) {
        fallback = null
        handler.removeCallbacks(retry)
        resolving?.cancel()
        retries = 0
        leaveReplay(clearBuffer = true)
        applyDecoder()
        player.playWhenReady = true
        this.vod = true
        applySubtitles(player)
        this.url = location
        this.userAgent = null
        this.login = null
        this.drm = null
        _formats.value = StreamFormats.UNKNOWN
        val smb = location.startsWith("smb://")
        val factory: androidx.media3.datasource.DataSource.Factory =
            if (smb) androidx.media3.datasource.DataSource.Factory { org.imdtv.iptvtv.recording.SmbDataSource(smbSettings) } else DefaultDataSource.Factory(context)
        val uri = if (smb) android.net.Uri.parse(location) else android.net.Uri.fromFile(java.io.File(location))
        player.setMediaSource(androidx.media3.exoplayer.source.ProgressiveMediaSource.Factory(factory).createMediaSource(MediaItem.fromUri(uri)))
        player.prepare()
        if (startMs > 0) player.seekTo(startMs)
    }

    private fun reload(u: String, userAgent: String?, login: Login?, vod: Boolean, drm: Drm?, positionMs: Long?) {
        if (org.imdtv.iptvtv.recording.isRecordingLocation(u)) playRecording(u, positionMs ?: 0)
        else play(u, userAgent, login, vod, drm, startMs = positionMs ?: 0)
    }

    /** Moves the player to [view] without re-preparing, so playback continues uninterrupted. */
    fun attach(view: PlayerView) {
        if (this.view === view) return
        attachedAt = android.os.SystemClock.elapsedRealtime()
        PlayerView.switchTargetView(player, this.view, view)
        this.view?.keepScreenOn = false
        this.view = view
        applySubtitleStyle()
        view.keepScreenOn = player.isPlaying
    }

    fun detach(view: PlayerView) {
        if (this.view !== view) return
        view.player = null
        view.keepScreenOn = false
        this.view = null
    }

    /** Stops the TV screensaver (which would also stop playback via onStop) only while video plays. */
    fun onPlayingChanged(isPlaying: Boolean) { view?.keepScreenOn = isPlaying }

    /** The stream playing now, or the one [toForeground] will resume. */
    val currentUrl: String? get() = url ?: resume?.url

    private data class Resume(val url: String, val userAgent: String?, val login: Login?, val positionMs: Long?, val vod: Boolean, val drm: Drm?)
    private var login: Login? = null
    private var drm: Drm? = null
    private var resume: Resume? = null

    /** Releases the provider connection (accounts allow only 1–2) when the app leaves the screen. */
    fun toBackground() {
        val u = url ?: return
        val vod = player.isCurrentMediaItemSeekable && player.duration != C.TIME_UNSET
        resume = Resume(u, userAgent, login, if (vod) player.currentPosition else null, this.vod, drm)
        stop()
    }

    /** Live restarts at the live edge; VOD continues where it was. */
    fun toForeground() {
        val r = resume ?: return
        resume = null
        reload(r.url, r.userAgent, r.login, r.vod, r.drm, r.positionMs)
    }

    /** Called before every stop (Back, the app leaving the screen, a tab change) while the stream is still loaded. */
    var beforeStop: () -> Unit = {}

    fun stop() {
        failedUrl = null
        fallback = null
        beforeStop()
        handler.removeCallbacks(retry)
        handler.removeCallbacks(watchdog)
        resolving?.cancel()
        retries = 0
        leaveReplay(clearBuffer = true)
        url = null
        _formats.value = StreamFormats.UNKNOWN
        player.stop()
        player.clearMediaItems()
        applyDecoder()
    }

    private companion object {
        const val MAX_RETRIES = 3
        /** Watchdog reloads of the same stream before a backup (if any) takes over. */
        const val STALLS_BEFORE_FAILOVER = 3
        /** Longer than the gaps of providers that send in bursts, short enough that a dead stream is retried within the buffer. */
        const val READ_TIMEOUT_MS = 20_000
        const val WATCH_MS = 2_000L
        /** A stream that played and froze; longer than a normal live rebuffer. */
        const val STALL_MS = 5_000L
        /** A stream that never started: longer than a slow provider's connect. */
        const val START_STALL_MS = 12_000L
        const val TAG = "IptvPlayer"
        /** Last path segment only: Xtream URLs carry the username and password. */
        fun tail(url: String?) = url?.substringBefore('|')?.substringAfterLast('/') // headers may hold tokens too
    }
}

/**
 * Buffer sizes for the Connection profile setting. Stable line keeps Media3's deep minimum buffer and raises the
 * maximum (bad Wi-Fi rides through longer drops); Low latency holds a shallow buffer and starts sooner.
 */
@OptIn(UnstableApi::class)
fun loadControl(profile: org.imdtv.iptvtv.data.settings.ConnectionProfile): androidx.media3.exoplayer.LoadControl =
    when (profile) {
        org.imdtv.iptvtv.data.settings.ConnectionProfile.STABLE -> androidx.media3.exoplayer.DefaultLoadControl.Builder()
            .setBufferDurationsMs(50_000, 90_000, 2_500, 5_000).build()
        org.imdtv.iptvtv.data.settings.ConnectionProfile.LOW_LATENCY -> androidx.media3.exoplayer.DefaultLoadControl.Builder()
            .setBufferDurationsMs(8_000, 20_000, 1_000, 2_000).build()
    }

/** One audio track of the playing stream (the overlay's Audio track menu); [groupIndex]/[trackIndex] address it. */
data class AudioTrackOption(val groupIndex: Int, val trackIndex: Int, val language: String?, val label: String?,
                            val channelCount: Int?, val codec: String?, val selected: Boolean)

/**
 * Subtitle track selection for the Subtitles setting: on → only a track in [language]; off → none. Either way a track
 * the file flags default is not picked by itself (it may be any language), forced tracks still are, and the last
 * title's subtitles-button choice is dropped.
 */
fun subtitleParams(base: TrackSelectionParameters, on: Boolean, language: String): TrackSelectionParameters =
    base.buildUpon()
        .clearOverridesOfType(C.TRACK_TYPE_TEXT)
        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
        .setIgnoredTextSelectionFlags(C.SELECTION_FLAG_DEFAULT)
        .setSelectUndeterminedTextLanguage(false)
        .setPreferredTextLanguage(if (on) language else null)
        .build()

/** Live with "Subtitles on live TV": the subtitle language, also taking tracks with no language (live CEA-608 is often "und"). */
fun liveSubtitleParams(base: TrackSelectionParameters, language: String): TrackSelectionParameters =
    subtitleParams(base, true, language).buildUpon().setSelectUndeterminedTextLanguage(true).build()

/** Live with "Subtitles on live TV" off: no text track at all. Media3's defaults still picked TV-language, forced and
 *  caption-setting tracks (users saw subtitles with the setting off, 2026-11-18). */
fun liveOffParams(base: TrackSelectionParameters): TrackSelectionParameters =
    base.buildUpon().clearOverridesOfType(C.TRACK_TYPE_TEXT).setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()

/** True once the playback position hasn't moved for [startMs] (before it first moves) or [playingMs] (after); then it
 *  starts counting again. */
class StallClock(private val startMs: Long, private val playingMs: Long) {
    private var pos = Long.MIN_VALUE
    private var since = 0L
    private var played = false
    fun reset() { pos = Long.MIN_VALUE; played = false }
    fun update(positionMs: Long, nowMs: Long): Boolean {
        if (positionMs != pos) { played = pos != Long.MIN_VALUE; pos = positionMs; since = nowMs; return false }
        if (nowMs - since < if (played) playingMs else startMs) return false
        since = nowMs
        return true
    }
}

/** A start failure with a message meant for the user (bad DRM data, a portal link that couldn't be fetched). */
class StreamProblem(message: String, cause: Throwable? = null) : Exception(message, cause)

/** The text the player shows for [e]. */
fun playErrorText(context: Context, e: PlaybackException): String = (e.cause as? StreamProblem)?.message ?: when (e.errorCode) {
    in 6000..6999 -> context.getString(R.string.live_err_drm, e.errorCodeName)
    else -> e.errorCodeName
}

/**
 * Media3's video renderer with the stream's pixel aspect ratio kept. On Android 11+ Media3 takes the decoder's
 * sar-width/sar-height over the stream's; decoders on some boxes (Thomson 140, Kickpi KP1, issue #1) report 1:1 for
 * anamorphic SD, so 16:9 SD at 720x576 showed as ~5:4 with bars.
 */
@OptIn(UnstableApi::class)
private class StreamAspectVideoRenderer(
    context: Context, adapters: androidx.media3.exoplayer.mediacodec.MediaCodecAdapter.Factory, selector: MediaCodecSelector,
    joiningMs: Long, fallback: Boolean, handler: Handler, listener: VideoRendererEventListener,
) : androidx.media3.exoplayer.video.MediaCodecVideoRenderer(context, adapters, selector, joiningMs, fallback, handler, listener,
    DefaultRenderersFactory.MAX_DROPPED_VIDEO_FRAME_COUNT_TO_NOTIFY) {
    override fun onOutputFormatChanged(format: Format, mediaFormat: android.media.MediaFormat?) {
        if (mediaFormat != null && android.os.Build.VERSION.SDK_INT >= 29 && decoderSarIsSquare(mediaFormat) && format.pixelWidthHeightRatio != 1f) {
            mediaFormat.removeKey(SAR_W); mediaFormat.removeKey(SAR_H)
        }
        super.onOutputFormatChanged(format, mediaFormat)
    }
    private fun decoderSarIsSquare(m: android.media.MediaFormat) =
        m.containsKey(SAR_W) && m.containsKey(SAR_H) && m.getInteger(SAR_W) == m.getInteger(SAR_H)
    private companion object { const val SAR_W = "sar-width"; const val SAR_H = "sar-height" }
}

