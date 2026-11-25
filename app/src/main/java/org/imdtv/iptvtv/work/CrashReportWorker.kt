package org.imdtv.iptvtv.work

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.flow.first
import org.imdtv.iptvtv.BuildConfig
import org.imdtv.iptvtv.IptvApp
import org.imdtv.iptvtv.data.diagnostics.CrashStore
import org.imdtv.iptvtv.data.management.CrashReport

/**
 * Sends the crash reports a previous run left behind (Playback › Send crash reports, managed builds, off by default).
 * Runs on start-up, after settings have been read, so nothing is sent while the setting is off. Reports are deleted
 * once handled: a crash that repeats must not queue up an endless backlog, and the panel groups them by fingerprint.
 */
class CrashReportWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        if (!BuildConfig.MANAGED) return Result.success()
        val c = (applicationContext as IptvApp).container
        if (!c.settings.settings.first().crashReportsOn) { CrashStore.clear(applicationContext); return Result.success() }
        val reports = CrashStore.take(applicationContext)
        if (reports.isEmpty()) return Result.success()
        var failed = false
        for (r in reports) {
            try {
                c.panel.crashReport(CrashReport(r.at, r.type, r.fingerprint, r.stack, r.thread, r.versionCode, r.versionName, r.device, r.android))
            } catch (e: Exception) {
                Log.w(TAG, "crash report not sent", e)
                failed = true
            }
        }
        // Keep at most one retry: WorkManager's backoff, then the files are dropped rather than sent weeks later.
        return if (failed && runAttemptCount < 2) Result.retry() else { CrashStore.clear(applicationContext); Result.success() }
    }

    companion object {
        private const val TAG = "IptvCrashReport"
        private const val WORK_NAME = "crash-reports"

        fun schedule(context: Context) {
            if (!BuildConfig.MANAGED) return
            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME, ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<CrashReportWorker>()
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .build(),
            )
        }
    }
}