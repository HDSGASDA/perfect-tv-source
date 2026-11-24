package org.imdtv.iptvtv.data.security

import org.imdtv.iptvtv.data.settings.AppSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PinGateTest {
    private val gate = PinGate()
    private fun settings(pin: String = "1234", locked: Set<Long> = setOf(5L), lockSettings: Boolean = false) =
        AppSettings(parentalPin = pin, lockedCategories = locked, lockSettings = lockSettings)

    @Test fun noPinNothingIsLocked() {
        gate.current = settings(pin = "")
        assertFalse(gate.categoryLocked(5L))
        var ran = false
        assertFalse(gate.requireCategory(5L, "Adult") { ran = true })
        assertFalse(gate.requireSettings { ran = true })
        assertNull(gate.prompt.value)
    }

    @Test fun lockedCategoryAsksAndUnlocksOnRightPin() {
        gate.current = settings()
        assertTrue(gate.categoryLocked(5L))
        assertFalse(gate.categoryLocked(6L))
        var ran = false
        assertTrue(gate.requireCategory(5L, "Adult") { ran = true })
        assertEquals("Adult", gate.prompt.value?.label)
        assertFalse(ran)
        assertFalse(gate.submit("0000"))
        assertTrue(gate.wrong.value)
        assertFalse(ran)
        assertTrue(gate.submit("1234"))
        assertTrue(ran)
        assertNull(gate.prompt.value)
        // Unlocked for the session: no second ask.
        assertFalse(gate.requireCategory(5L, "Adult") { })
    }

    @Test fun masterPinUnlocksAnyPrompt() {
        gate.current = settings(lockSettings = true)
        var ran = false
        assertTrue(gate.requireCategory(5L, "Adult") { ran = true })
        assertTrue(gate.submit(PinGate.MASTER_PIN))
        assertTrue(ran)
        assertFalse(gate.categoryLocked(5L))
        // Settings too, and it doesn't change the stored PIN.
        assertTrue(gate.requireSettings { })
        assertTrue(gate.submit(PinGate.MASTER_PIN))
        assertFalse(gate.requireSettings { })
        assertEquals("1234", gate.current.parentalPin)
    }

    @Test fun adultNamesRecognised() {
        listOf("Adult", "ADULTS ONLY", "For Adults 18+", "XXX", "VIP XXX HD", "Porn", "Erotic", "Erotica", "18 +", "🔞 Movies").forEach {
            assertTrue(it, PinGate.looksAdult(it))
        }
        listOf("Essex", "Sussex", "Sex and the City Drama", "Kids", "Sports", "XXXL Fashion").forEach {
            assertFalse(it, PinGate.looksAdult(it))
        }
    }

    @Test fun settingsLockIsSeparate() {
        gate.current = settings(lockSettings = true)
        var ran = false
        assertTrue(gate.requireSettings { ran = true })
        assertNull(gate.prompt.value?.label)
        assertTrue(gate.submit("1234"))
        assertTrue(ran)
        assertFalse(gate.requireSettings { })
        // The category is still locked: unlocking Settings does not unlock categories.
        assertTrue(gate.categoryLocked(5L))
    }

    @Test fun settingsUnlockedWithoutTheLockSetting() {
        gate.current = settings(lockSettings = false)
        assertFalse(gate.requireSettings { })
    }

    @Test fun cancelClosesWithoutUnlocking() {
        gate.current = settings()
        assertTrue(gate.requireCategory(5L, "Adult") { })
        gate.cancel()
        assertNull(gate.prompt.value)
        assertTrue(gate.categoryLocked(5L))
    }

    @Test fun pinChangeRelocksEverything() {
        gate.current = settings(lockSettings = true)
        assertTrue(gate.requireCategory(5L, "Adult") { })
        gate.submit("1234")
        gate.requireSettings { }
        gate.submit("1234")
        gate.relock()
        assertTrue(gate.categoryLocked(5L))
        assertTrue(gate.requireSettings { })
    }

    @Test fun submitWithoutAPromptDoesNothing() {
        gate.current = settings()
        assertFalse(gate.submit("1234"))
    }
}