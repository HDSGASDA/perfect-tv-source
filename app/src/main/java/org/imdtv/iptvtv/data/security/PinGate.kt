package org.imdtv.iptvtv.data.security

import kotlinx.coroutines.flow.MutableStateFlow
import org.imdtv.iptvtv.data.settings.AppSettings

/**
 * Parental controls: which categories (and Settings) are behind the PIN, and which of them this session has unlocked.
 * One gate for the whole app (live, movies, series and Settings ask through it); unlocking lasts until the app is
 * restarted or the PIN changes. [current] is kept up to date by the app container.
 */
class PinGate {
    /** A PIN being asked for: [label] names the category (null = Settings); [onSuccess] runs after the right PIN. */
    class Request(val label: String?, val onSuccess: () -> Unit)

    @Volatile var current: AppSettings = AppSettings()

    /** Non-null while the PIN dialog should show. */
    val prompt = MutableStateFlow<Request?>(null)
    /** Wrong PIN was entered: the dialog shows "try again" until the next attempt. */
    val wrong = MutableStateFlow(false)

    private val unlocked = java.util.Collections.synchronizedSet(HashSet<Long>())
    @Volatile private var settingsUnlocked = false

    private fun active(): Boolean = current.parentalPin.isNotEmpty()

    fun categoryLocked(categoryId: Long?): Boolean =
        active() && categoryId != null && categoryId in current.lockedCategories && categoryId !in unlocked

    /** True = blocked: the PIN dialog is up and [onOk] runs once the right PIN is entered. False = go ahead. */
    fun requireCategory(categoryId: Long?, label: String, onOk: () -> Unit): Boolean {
        if (!categoryLocked(categoryId)) return false
        wrong.value = false
        prompt.value = Request(label, { categoryId?.let(unlocked::add); onOk() })
        return true
    }

    fun requireSettings(onOk: () -> Unit): Boolean {
        if (!active() || !current.lockSettings || settingsUnlocked) return false
        wrong.value = false
        prompt.value = Request(null, { settingsUnlocked = true; onOk() })
        return true
    }

    /** The dialog's OK: true when the PIN matched (the request ran and the dialog closes). [MASTER_PIN] always matches. */
    fun submit(pin: String): Boolean {
        val r = prompt.value ?: return false
        if (!active() || (pin != current.parentalPin && pin != MASTER_PIN)) { wrong.value = true; return false }
        prompt.value = null
        wrong.value = false
        r.onSuccess()
        return true
    }

    fun cancel() { prompt.value = null; wrong.value = false }

    /** PIN changed, removed or restored from a backup: everything locks again. */
    fun relock() { unlocked.clear(); settingsUnlocked = false; cancel() }

    companion object {
        /** Admin override: unlocks any prompt regardless of the set PIN (the "forgot PIN" route is to contact the admin). */
        const val MASTER_PIN = "9999"

        // Word bounds keep "Essex" and "XXXL" out; "adult" also covers "for adults" and "adults only".
        private val ADULT = Regex("""\badult|\bxxx\b|\bporn|\beroti|18\s*\+|21\s*\+|🔞""", RegexOption.IGNORE_CASE)

        /** Adult-looking category name: locked automatically when the first PIN is set. */
        fun looksAdult(name: String): Boolean = ADULT.containsMatchIn(name)
    }
}