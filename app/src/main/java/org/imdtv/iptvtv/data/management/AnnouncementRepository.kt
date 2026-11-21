package org.imdtv.iptvtv.data.management

import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Foreground polling, with per-installation dismissal persisted across process restarts. */
class AnnouncementRepository(private val panel: PanelClient, private val prefs: DataStore<Preferences>) {
    val pending = MutableStateFlow<List<PanelMessage>>(emptyList())
    private val dismissed = stringSetPreferencesKey("managed_dismissed_messages")
    private val lock = Mutex()

    /**
     * Refresh the live alert queue and report whether the panel responded successfully.
     * A failed request clears the queue so a withdrawn or expired alert is never left visible.
     */
    suspend fun refresh(): Boolean = withContext(Dispatchers.IO) {
        try {
            lock.withLock {
                val messages = panel.messages()
                val seen = prefs.data.first()[dismissed].orEmpty()
                pending.value = messages.filterNot { it.id in seen }
            }
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Panel downtime must never stop playback or login. Never keep stale notices on screen.
            pending.value = emptyList()
            Log.i("Announcements", "Panel messages unavailable (${e.javaClass.simpleName})")
            false
        }
    }

    suspend fun dismiss(id: String) = lock.withLock {
        prefs.edit { it[dismissed] = it[dismissed].orEmpty() + id }
        pending.value = pending.value.filterNot { it.id == id }
    }
}
