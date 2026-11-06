package io.github.akane599.umbra

import io.github.akane599.umbra.PanelService.Companion.restoreLocks
import io.github.akane599.umbra.PanelService.Mode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** SEC-002: a dark, unlocked phone must not come back unlocked to whoever double-taps it. */
class RestoreLockTest {

    @Test
    fun securePhoneWithDefaultSettingsLocksOnRestore() {
        assertTrue(AppSettings.DEF_LOCK_ON_RESTORE)
        assertTrue(restoreLocks(Mode.PANEL, AppSettings.DEF_LOCK_ON_RESTORE, deviceSecure = true))
    }

    @Test
    fun onlyPanelSessionsOnSecurePhonesWithTheOptionOn() {
        for (mode in Mode.entries) for (option in listOf(false, true)) for (secure in listOf(false, true)) {
            val want = mode == Mode.PANEL && option && secure
            assertEquals("$mode option=$option secure=$secure", want, restoreLocks(mode, option, secure))
        }
        // No screen lock set: nothing to lock, the panel just comes back.
        assertFalse(restoreLocks(Mode.PANEL, lockOnRestore = true, deviceSecure = false))
    }
}
