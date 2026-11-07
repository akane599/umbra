package io.github.akane599.umbra

import io.github.akane599.umbra.ScreenTimeout.Companion.FOREVER
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenTimeoutTest {

    private class FakeSetting(var value: Int?, var writable: Boolean = true) : ScreenTimeout.Setting {
        val writes = mutableListOf<Int>()
        override fun get() = value
        override fun put(ms: Int): Boolean {
            if (!writable) return false
            writes += ms
            value = ms
            return true
        }
    }

    private class FakeStore(var saved: Int? = null) : ScreenTimeout.Store {
        /** What the setting held at the moment each save happened: the save must come first. */
        val settingAtSave = mutableListOf<Int?>()
        var setting: FakeSetting? = null
        override fun load() = saved
        override fun save(ms: Int?) {
            settingAtSave += setting?.value
            saved = ms
        }
    }

    private fun timeout(setting: FakeSetting, store: FakeStore = FakeStore()) =
        ScreenTimeout(setting, store.also { it.setting = setting })

    @Test
    fun holdRaisesTheTimeoutAndReleasePutsTheUsersValueBack() {
        val setting = FakeSetting(30_000)
        val store = FakeStore()
        val t = timeout(setting, store)

        assertTrue(t.hold())
        assertEquals(FOREVER, setting.value)
        assertEquals(30_000, store.saved)

        t.release()
        assertEquals(30_000, setting.value)
        assertNull(store.saved)
    }

    @Test
    fun theUsersValueIsSavedBeforeTheSettingChanges() {
        val setting = FakeSetting(60_000)
        val store = FakeStore()
        timeout(setting, store).hold()
        // Saved while the setting still held the user's value: a crash right after can't lose it.
        assertEquals(listOf<Int?>(60_000), store.settingAtSave)
    }

    @Test
    fun holdingTwiceKeepsTheFirstSavedValue() {
        val setting = FakeSetting(30_000)
        val store = FakeStore()
        val t = timeout(setting, store)
        t.hold()
        assertTrue(t.hold())
        assertEquals(30_000, store.saved)
        assertEquals(listOf(FOREVER), setting.writes)
    }

    @Test
    fun aTimeoutTheUserAlreadySetToForeverIsLeftAlone() {
        // e.g. their own caffeine script: nothing to save, nothing to put back.
        val setting = FakeSetting(FOREVER)
        val store = FakeStore()
        val t = timeout(setting, store)
        assertTrue(t.hold())
        t.release()
        assertEquals(FOREVER, setting.value)
        assertTrue(setting.writes.isEmpty())
        assertNull(store.saved)
    }

    @Test
    fun withoutPermissionNothingIsChangedOrKept() {
        val setting = FakeSetting(30_000, writable = false)
        val store = FakeStore()
        val t = timeout(setting, store)
        assertFalse(t.hold())
        assertEquals(30_000, setting.value)
        assertNull(store.saved)
    }

    @Test
    fun aTimeoutTheUserChangedDuringTheSessionIsKept() {
        val setting = FakeSetting(30_000)
        val store = FakeStore()
        val t = timeout(setting, store)
        t.hold()
        setting.value = 120_000 // changed in system settings meanwhile
        t.release()
        assertEquals(120_000, setting.value)
        assertNull(store.saved)
    }

    @Test
    fun releaseAfterACrashPutsTheValueBack() {
        // A session raised it, then the process died: a fresh instance only has the saved value.
        val setting = FakeSetting(FOREVER)
        val store = FakeStore(saved = 15_000)
        timeout(setting, store).release()
        assertEquals(15_000, setting.value)
        assertNull(store.saved)
    }

    @Test
    fun aStaleSavedValueSurvivesANewHoldAndIsPutBackLater() {
        val setting = FakeSetting(FOREVER)
        val store = FakeStore(saved = 15_000)
        val t = timeout(setting, store)
        assertTrue(t.hold())
        assertEquals(15_000, store.saved)
        t.release()
        assertEquals(15_000, setting.value)
    }

    @Test
    fun releaseThatCantWriteKeepsTheValueForALaterTry() {
        val setting = FakeSetting(30_000)
        val store = FakeStore()
        val t = timeout(setting, store)
        t.hold()
        setting.writable = false // permission revoked mid-session
        t.release()
        assertEquals(FOREVER, setting.value)
        assertEquals(30_000, store.saved)

        setting.writable = true
        t.release()
        assertEquals(30_000, setting.value)
        assertNull(store.saved)
    }

    @Test
    fun releaseWithNothingSavedDoesNothing() {
        val setting = FakeSetting(30_000)
        timeout(setting).release()
        assertEquals(30_000, setting.value)
        assertTrue(setting.writes.isEmpty())
    }
}
