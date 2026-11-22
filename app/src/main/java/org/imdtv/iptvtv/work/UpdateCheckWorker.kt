package org.imdtv.iptvtv.work

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import org.imdtv.iptvtv.IptvApp
import java.util.concurrent.TimeUnit

/** Checks for a published APK while the app is not on screen. */
class UpdateCheckWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val updater = (applicationContext as IptvApp).container.updater
        return try {
            if (updater.check()) Result.success() else Result.retry()
        } catch (e: Exception) {
            Log.w(TAG, "Background update check failed", e)
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "IptvUpdate"
        private const val WORK_NAME = "published-update-check"

        /** Android's minimum periodic interval is 15 minutes. Foreground polling is faster in MainActivity. */
        fun schedule(context: Context) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<UpdateCheckWorker>(15, TimeUnit.MINUTES)
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .build(),
            )
        }
    }
}
