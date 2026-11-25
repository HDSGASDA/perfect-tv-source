package org.imdtv.iptvtv

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class IptvApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun attachBaseContext(base: android.content.Context) {
        super.attachBaseContext(org.imdtv.iptvtv.ui.AppLanguage.wrap(base))
        // Background work (sync workers, the player's loader thread) reads the JVM default locale too.
        org.imdtv.iptvtv.ui.AppLanguage.applyDefault(this)
    }

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        // Crash reports are written by the dying process and uploaded on the next start, once settings are readable.
        org.imdtv.iptvtv.data.diagnostics.CrashStore.install(this) { container.settings.settings.first().crashReportsOn }
        // Robolectric builds the Application without running manifest ContentProviders, so WorkManager is absent in
        // unit tests; the same guard as the recording re-arm below keeps start-up usable there.
        runCatching { org.imdtv.iptvtv.work.CrashReportWorker.schedule(this) }
            .onFailure { android.util.Log.w("IptvApp", "Crash report upload not scheduled", it) }
        // Once per old playlist: encrypt its login and take it out of saved links (no-op afterwards).
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            org.imdtv.iptvtv.data.support.LogCollector.start(this@IptvApp)
            container.sync.start()
            container.search // created here, not lazily on the main thread (it registers a Room observer)
            runCatching { container.providerRepository.sealOldRows() }.onFailure { android.util.Log.e("IptvApp", "Sealing logins failed", it) }
            // A fresh process runs no recording: alarms are set again (boot and updates clear them) and cut-off rows closed.
            // Do not initialize the recording repository just to rearm; its app-lifetime observer would outlive
            // a Robolectric application's in-memory database, and there cannot be a running recording after a process restart.
            runCatching { container.recordingScheduler.rearm { emptySet() } }
                .onFailure { android.util.Log.e("IptvApp", "Setting recording alarms failed", it) }
        }
    }
}
