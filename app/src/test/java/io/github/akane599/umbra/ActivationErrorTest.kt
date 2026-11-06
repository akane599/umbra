package io.github.akane599.umbra

import io.github.akane599.umbra.ActivationError.Companion.classify
import io.github.akane599.umbra.PrivilegedBridge.Companion.ERR_SERVER_NOT_STARTED
import io.github.akane599.umbra.PrivilegedBridge.Companion.ERR_SHIZUKU_NOT_RUNNING
import io.github.akane599.umbra.PrivilegedBridge.Companion.ERR_SHIZUKU_NO_PERMISSION
import org.junit.Assert.assertEquals
import org.junit.Test

/** ActivationError.classify over (privilege mode × Shizuku state × su state), with the bridge's message shapes. */
class ActivationErrorTest {

    /** What PrivilegedBridge.start() throws: one "<Backend>: <message>" line per backend tried. */
    private fun raw(shizuku: String? = null, root: String? = null) =
        listOfNotNull(shizuku?.let { "Shizuku: $it" }, root?.let { "Root: $it" }).joinToString("\n")

    /** The real failure of starting a su that isn't there (no root, or KernelSU hiding it from us). */
    private val noSu: String = runCatching { PrivilegedBridge.launchSu("true", su = "/nonexistent/umbra-test/su") }
        .exceptionOrNull()!!.let { it.message ?: it.javaClass.simpleName }

    private val suDenied = "$ERR_SERVER_NOT_STARTED (permission denied or no su)"
    private val timedOut = "server not ready within 8 s"

    @Test
    fun autoWithShizukuStoppedAndNoSuAsksToStartShizuku() {
        assertEquals(ActivationError.SHIZUKU_OFF, classify(raw(ERR_SHIZUKU_NOT_RUNNING, noSu), "auto"))
    }

    @Test
    fun autoWithShizukuStoppedAndSuDeniedAsksToStartShizuku() {
        assertEquals(ActivationError.SHIZUKU_OFF, classify(raw(ERR_SHIZUKU_NOT_RUNNING, suDenied), "auto"))
    }

    @Test
    fun shizukuPermissionMissingWinsInAnyMode() {
        assertEquals(ActivationError.SHIZUKU_PERMISSION, classify(raw(ERR_SHIZUKU_NO_PERMISSION, noSu), "auto"))
        assertEquals(ActivationError.SHIZUKU_PERMISSION, classify(raw(ERR_SHIZUKU_NO_PERMISSION), "shizuku"))
    }

    @Test
    fun shizukuModeWithShizukuStopped() {
        assertEquals(ActivationError.SHIZUKU_OFF, classify(raw(ERR_SHIZUKU_NOT_RUNNING), "shizuku"))
    }

    @Test
    fun rootModeFailuresAreRootDenied() {
        assertEquals(ActivationError.ROOT_DENIED, classify(raw(root = noSu), "root"))
        assertEquals(ActivationError.ROOT_DENIED, classify(raw(root = suDenied), "root"))
        assertEquals(ActivationError.ROOT_DENIED, classify(raw(root = timedOut), "root"))
    }

    @Test
    fun otherFailuresAreGeneric() {
        // Shizuku is running, but the helper didn't come up; no su either.
        assertEquals(ActivationError.GENERIC, classify(raw(timedOut, noSu), "auto"))
        // Shizuku stopped, and su exists and started, but the helper timed out under root.
        assertEquals(ActivationError.GENERIC, classify(raw(ERR_SHIZUKU_NOT_RUNNING, timedOut), "auto"))
        assertEquals(ActivationError.GENERIC, classify(raw("Shizuku is too old (v11+ required)"), "shizuku"))
        // The panel-off command itself failed after the helper started.
        assertEquals(ActivationError.GENERIC, classify("no physical display token found", "auto"))
    }
}
