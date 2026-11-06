package io.github.akane599.umbra

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/** Invariant I9: a temporary helper is always stopped, whatever happens to the command (PanelService recovery paths). */
class OneShotTest {

    private class FakeHelper(val failStart: Boolean = false, val failRequest: Boolean = false) : Helper {
        val calls = mutableListOf<String>()
        override fun start(pref: String): PrivilegedBridge.Backend {
            calls += "start $pref"
            if (failStart) throw IllegalStateException("Shizuku: ${PrivilegedBridge.ERR_SHIZUKU_NOT_RUNNING}")
            return PrivilegedBridge.Backend.SHIZUKU
        }
        override fun request(command: String, timeoutMs: Long): String {
            calls += command
            if (failRequest) throw IOException("RuntimeException: no physical display token found")
            return "surface displays=1"
        }
        override fun stop() {
            calls += "stop"
        }
    }

    @Test
    fun returnsTheReplyAndStops() {
        val h = FakeHelper()
        assertEquals("surface displays=1", h.oneShot("auto", "on"))
        assertEquals(listOf("start auto", "on", "stop"), h.calls)
    }

    @Test
    fun failedCommandStillStopsTheHelper() {
        val h = FakeHelper(failRequest = true)
        val e = runCatching { h.oneShot("auto", "on") }.exceptionOrNull()
        assertTrue(e is IOException)
        assertEquals(listOf("start auto", "on", "stop"), h.calls)
    }

    @Test
    fun failedStartStillStopsTheHelper() {
        val h = FakeHelper(failStart = true)
        assertTrue(runCatching { h.oneShot("shizuku", "on") }.isFailure)
        assertEquals(listOf("start shizuku", "stop"), h.calls)
    }
}
