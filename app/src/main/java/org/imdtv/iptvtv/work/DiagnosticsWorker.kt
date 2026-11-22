package org.imdtv.iptvtv.work

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.flow.first
import org.imdtv.iptvtv.BuildConfig
import org.imdtv.iptvtv.IptvApp
import org.imdtv.iptvtv.data.management.ChannelDiagnostics
import org.imdtv.iptvtv.data.management.DiagnosticsPayload
import java.util.concurrent.TimeUnit

/**
 * Playback › Send playback diagnostics (managed builds, off by default): uploads anonymous per-channel totals
 * (watched seconds, stalls, underruns, dropped frames, errors) plus the box model, so the panel can show which
 * channels and devices misbehave. No account data, no identifiers; a failed upload keeps its batch for next time.
 */
class DiagnosticsWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        if (!BuildConfig.MANAGED) return Result.success()
        val c = (applicationContext as IptvApp).container
        if (!c.settings.settings.first().diagnosticsOn) return Result.success()
        c.collectPlaybackStats()
        val batch = c.takePlaybackTotals()
        if (batch.isEmpty()) return Result.success()
        val payload = DiagnosticsPayload(
            versionCode = BuildConfig.VERSION_CODE,
            versionName = BuildConfig.VERSION_NAME,
            device = android.os.Build.MODEL.orEmpty().take(80),
            android = android.os.Build.VERSION.SDK_INT,
            channels = batch.map { (name, s) -> ChannelDiagnostics(name.take(120), s.seconds, s.stalls, s.underruns, s.droppedFrames, s.errors) },
        )
        return try {
            c.panel.diagnostics(payload)
            Log.i(TAG, "diagnostics sent: ${payload.channels.size} channels")
            Result.success()
        } catch (e: Exception) {
            Log.w(TAG, "diagnostics upload failed; keeping the batch", e)
            c.returnPlaybackTotals(batch)
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "IptvDiagnostics"
        private const val WORK_NAME = "playback-diagnostics"

        /** On: hourly uploads while the setting is on, plus one soon after it is switched on. Off: everything cancels. */
        fun schedule(context: Context, enabled: Boolean) {
            if (!BuildConfig.MANAGED) return
            val wm = WorkManager.getInstance(context)
            if (!enabled) {
                wm.cancelUniqueWork(WORK_NAME)
                wm.cancelUniqueWork("$WORK_NAME-now")
                return
            }
            val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
            wm.enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<DiagnosticsWorker>(1, TimeUnit.HOURS).setConstraints(constraints).build(),
            )
            wm.enqueueUniqueWork(
                "$WORK_NAME-now",
                ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<DiagnosticsWorker>().setConstraints(constraints).build(),
            )
        }
    }
}