package org.imdtv.iptvtv.recording

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.imdtv.iptvtv.IptvApp
import org.imdtv.iptvtv.R
import java.io.File

/** Runs the recordings (one job each) in the foreground, with the box kept awake if the setting says so. */
class RecordingService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = HashMap<Long, Job>() // main thread only
    private var wake: PowerManager.WakeLock? = null
    private var wifi: WifiManager.WifiLock? = null
    /** The scheduled ends of the recordings holding the locks, so a later, longer one extends them. */
    private val lockEnds = HashMap<Long, Long>() // main thread only
    private val container get() = (application as IptvApp).container

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // startForegroundService needs this within seconds, whatever the request.
        notify(container.recordings.running.value.values.map { it.title })
        val id = intent?.getLongExtra(EXTRA_ID, -1L) ?: -1L
        when (intent?.action) {
            ACTION_START -> if (id >= 0 && id !in jobs) start(id)
            ACTION_STOP -> jobs[id]?.cancel()
        }
        if (jobs.isEmpty()) finishIfIdle()
        return START_NOT_STICKY
    }

    private fun start(id: Long) {
        val repo = container.recordings
        jobs[id] = scope.launch {
            val s = container.settings.settings.first()
            val store = container.recordingStore(s)
            if (store == null) {
                container.db.recordingDao().get(id)?.let {
                    container.db.recordingDao().update(it.copy(status = RecStatus.FAILED, statusReason = "Failed — no USB drive found"))
                }
                repo.prompt.value = Prompt.Notice(getString(R.string.vod_rec_notice_no_usb))
                return@launch
            }
            container.db.recordingDao().get(id)?.let { r -> repo.running.update { it + (id to r) } }
            val endsAt = container.db.recordingDao().get(id)?.scheduledEnd ?: System.currentTimeMillis()
            withContext(Dispatchers.Main) { holdLocks(s.keepAwake, id, endsAt); notify(repo.running.value.values.map { it.title }) }
            val status = runRecording(id, container.db, store, StreamCapture(container.http),
                otherStreams = { repo.running.value.size > 1 || container.isWatching() }, resolve = container.stalkerLinks::resolve)
            container.db.recordingDao().get(id)?.let { r ->
                if (status == RecStatus.FAILED || r.statusReason != null && status == RecStatus.STOPPED) repo.prompt.value = Prompt.Notice(getString(R.string.vod_rec_notice_title_reason, r.title, r.statusReason?.let { s -> recordingReason(this@RecordingService, s) }.orEmpty()))
            }
        }.also { job ->
            job.invokeOnCompletion {
                repo.running.update { it - id }
                scope.launch(Dispatchers.Main) { jobs.remove(id); lockEnds.remove(id); finishIfIdle(); if (jobs.isNotEmpty()) notify(repo.running.value.values.map { it.title }) }
            }
        }
    }

    private fun finishIfIdle() {
        if (jobs.isNotEmpty()) return
        releaseLocks()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /**
     * Held until the last recording's scheduled end plus a margin, never indefinitely: if the process is killed without
     * [onDestroy] running, the OS releases them instead of keeping the box awake for good. A later, longer recording
     * re-acquires with the new timeout. The capture itself always ends at its deadline (60s read timeout per attempt).
     */
    private fun holdLocks(keepAwake: Boolean, id: Long, endsAt: Long) {
        if (!keepAwake) return
        lockEnds[id] = endsAt
        val timeout = (lockEnds.values.max() - System.currentTimeMillis() + LOCK_MARGIN_MS).coerceAtLeast(LOCK_MARGIN_MS)
        if (wake == null) wake = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "imdtv:recording")
        wake?.acquire(timeout)
        @Suppress("DEPRECATION")
        val mode = if (Build.VERSION.SDK_INT >= 29) WifiManager.WIFI_MODE_FULL_LOW_LATENCY else WifiManager.WIFI_MODE_FULL_HIGH_PERF
        if (wifi == null) wifi = runCatching { applicationContext.getSystemService(WifiManager::class.java).createWifiLock(mode, "imdtv:recording") }
            .onFailure { Log.w("IptvRecord", "no Wi-Fi lock: $it") }.getOrNull()
        wifi?.acquire()
    }

    private fun releaseLocks() {
        lockEnds.clear()
        wake?.let { if (it.isHeld) it.release() }; wake = null
        wifi?.let { if (it.isHeld) it.release() }; wifi = null
    }

    private fun notify(titles: List<String>) {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.vod_rec_channel_name), NotificationManager.IMPORTANCE_LOW))
        val text = when (titles.size) {
            0 -> getString(R.string.vod_rec_notification_starting)
            1 -> getString(R.string.vod_rec_notification_one, titles[0])
            else -> resources.getQuantityString(R.plurals.vod_rec_notification_many, titles.size, titles.size)
        }
        @Suppress("DEPRECATION") val n = (if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL) else Notification.Builder(this)).setSmallIcon(R.drawable.ic_tab_recordings).setContentTitle(text).setOngoing(true).build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE) else startForeground(1, n)
    }

    override fun onDestroy() {
        releaseLocks()
        scope.cancel() // only when Android kills the service: running rows become "app was closed" at the next start
        super.onDestroy()
    }

    companion object {
        private const val ACTION_START = "org.imdtv.iptvtv.recording.RUN"
        private const val ACTION_STOP = "org.imdtv.iptvtv.recording.STOP"
        private const val EXTRA_ID = "id"
        /** Past the last scheduled end: the capture's own retries (60s) and final write fit inside this. */
        private const val LOCK_MARGIN_MS = 10 * 60_000L
        private const val CHANNEL = "recording"

        fun start(context: Context, id: Long) = Intent(context, RecordingService::class.java).setAction(ACTION_START).putExtra(EXTRA_ID, id).let {
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(it) else context.startService(it) // Fire OS 6 = Android 7.1
        }

        fun stop(context: Context, id: Long) = context.startService(
            Intent(context, RecordingService::class.java).setAction(ACTION_STOP).putExtra(EXTRA_ID, id))
    }
}
